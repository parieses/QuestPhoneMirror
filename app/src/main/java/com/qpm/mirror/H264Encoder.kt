package com.qpm.mirror

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface

/**
 * H.264 硬编码器，输入用 Surface（VirtualDisplay 直接往里画），
 * 输出经回调交给外层发送。
 */
class H264Encoder(
    width: Int,
    height: Int,
    bitRate: Int,
    private val onFrame: (ByteArray, Int, Int, Long) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    val inputSurface: Surface

    private val codec: MediaCodec =
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    private val callback = object : MediaCodec.Callback() {
        // 缓存 SPS/PPS（Annex-B 起始码开头），在每个 IDR 前重发。
        // 不同设备放 SPS/PPS 的位置不一：有的在 csd-0/csd-1，有的在 CODEC_CONFIG
        // output buffer，有的两者都没有、而是内联在 IDR 帧里。这里两条来源都缓存，
        // 谁有算谁的。接收端只要在每个 IDR 前拿到 SPS/PPS 即可初始化解码器，
        // 与“何时连上”无关（KEY_I_FRAME_INTERVAL=1，最多 1 秒必有一个 IDR）。
        private var spsPps: ByteArray? = null
        private val cached = mutableSetOf<String>()

        /**
         * 输出缓冲复用池。onFrame 是同步调用：调用方必须在回调内把数据复制走，
         * 返回后这里立即回收该数组。只在同一条 MediaCodec 回调线程上取用，无需加锁。
         */
        private val outPool = ArrayDeque<ByteArray>()

        private fun takeBuf(size: Int): ByteArray {
            val reused = outPool.removeLastOrNull()
            return if (reused != null && reused.size >= size) reused else ByteArray(size)
        }

        private fun recycle(buf: ByteArray) {
            if (outPool.size < 4) outPool.addLast(buf)
        }

        private fun annexb(csd: ByteArray): ByteArray {
            return if (csd.size >= 4 && csd[0] == 0.toByte() && csd[1] == 0.toByte()
                && csd[2] == 0.toByte() && csd[3] == 1.toByte()) {
                csd
            } else {
                byteArrayOf(0, 0, 0, 1) + csd
            }
        }

        // 把一段 csd 去重后并入 spsPps。用内容指纹去重，避免重复拼接导致参数集膨胀。
        private fun cacheCsd(csd: ByteArray?) {
            if (csd == null || csd.isEmpty()) return
            val a = annexb(csd)
            val key = a.joinToString(",")
            if (cached.contains(key)) return
            cached.add(key)
            spsPps = if (spsPps == null) a else spsPps!! + a
        }

        // 从 MediaFormat 里读 csd-0/csd-1（SPS/PPS）。这是最可靠的一次性来源：
        // 异步回调 onOutputFormatChanged 在部分设备上不触发，但 codec.outputFormat
        // 属性一定有。两条路径都调它，确保任何设备都能取到参数集。
        private fun cacheFromFormat(format: MediaFormat) {
            for (k in arrayOf("csd-0", "csd-1")) {
                if (!format.containsKey(k)) continue
                val buf = runCatching { format.getByteBuffer(k) }.getOrNull() ?: continue
                val csd = ByteArray(buf.remaining())
                buf.get(csd)
                cacheCsd(csd)
            }
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            // codec 被 stop()/release() 后（旋转重建、停止投屏）回调线程仍可能收到在途缓冲，
            // getOutputBuffer 会抛 IllegalStateException；回调线程未捕获异常直接杀进程，
            // 整个方法体必须兜底（旧实现只包了 releaseOutputBuffer，漏了 getOutputBuffer）
            runCatching {
                val buffer = codec.getOutputBuffer(index)
                if (buffer == null || info.size <= 0) {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    return@runCatching
                }
                val out = takeBuf(info.size)
                buffer.position(info.offset)
                buffer.get(out, 0, info.size)
                runCatching { codec.releaseOutputBuffer(index, false) }

                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                if (isConfig) {
                    // 这条 buffer 本身就是参数集（部分设备走这里），缓存起来
                    cacheCsd(out.copyOf(info.size))
                    recycle(out)
                    return@runCatching
                }

                // 兜底：异步回调 onOutputFormatChanged 在部分设备上不触发，
                // 这里在首个有效输出帧时直接从 codec.outputFormat 取一次 csd。
                if (spsPps == null) {
                    runCatching { cacheFromFormat(codec.outputFormat) }
                }

                val pts = info.presentationTimeUs
                val csd = spsPps
                if (isKey && csd != null && csd.isNotEmpty()) {
                    // SPS/PPS 与 IDR 合并成**一帧**再发：分两帧时参数集可能被发送队列
                    // 挤掉，接收端拿到没有参数集的 IDR 会花屏一整个 GOP。Annex-B 流里
                    // 参数集紧邻 IDR 是标准形式，MediaCodec 解码器普遍支持。
                    val merged = takeBuf(csd.size + info.size)
                    System.arraycopy(csd, 0, merged, 0, csd.size)
                    System.arraycopy(out, 0, merged, csd.size, info.size)
                    onFrame(merged, csd.size + info.size, FLAG_KEY, pts)
                    recycle(merged)
                } else {
                    // 拿不到参数集的设备通常把 SPS/PPS 内联在 IDR 里，直接发即可
                    onFrame(out, info.size, if (isKey) FLAG_KEY else 0, pts)
                }
                recycle(out)
            }.onFailure { QpmLog.w(TAG, "输出回调异常（codec 可能已停止）: ${it.message}") }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // 用字面量键 "csd-0"/"csd-1"：MediaFormat.KEY_CSD_0 等常量在较新
            // compileSdk/AGP 组合下已被移除（改为 KEY_CSD_HEVC_* 等），用字符串键最稳。
            runCatching { cacheFromFormat(format) }
                .onFailure { QpmLog.w(TAG, "格式回调异常: ${it.message}") }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            QpmLog.e(TAG, "onError: ${e.diagnosticInfo ?: e.message}")
            // 外层回调（ CastService 代际守卫/stopAll ）也不能让异常逃逸到回调线程
            runCatching { onError(e.diagnosticInfo ?: e.message ?: "未知编码器错误") }
                .onFailure { QpmLog.w(TAG, "onError 回调异常: ${it.message}") }
        }
    }

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                // 画质档位由调用方给定（默认 1080p 约 6Mbps），网络差可调低
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // 实时投屏参数：恒定码率避免画面突变时码率尖峰打爆 Wi-Fi；关 B 帧与
                // lookahead 消掉编码内 2~4 帧排队（约 60~130ms）；realtime 优先级 +
                // operating-rate 防止系统把 VENC 调度到低频后帧率上不去。
                // 用字面量键兼容 minSdk 24：未知键会被编码器忽略，不会让 configure 失败。
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger("max-bframes", 0)
                setInteger("latency", 0)
                setInteger("priority", 0)
                setInteger("operating-rate", 30)
            }
            codec.setCallback(callback)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            QpmLog.e(TAG, "初始化失败: ${e.message}")
            runCatching { codec.release() }
            throw e
        }
    }

    /** 新客户端接入时主动要一个关键帧，避免花屏等待。 */
    fun requestKeyFrame() {
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        runCatching { codec.setParameters(params) }
    }

    /** 自适应码率：不重建编码器，运行时改 VENC 目标码率（多数硬编支持；不支持则忽略）。 */
    fun setBitrate(bps: Int) {
        if (bps <= 0) return
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
        }
        runCatching { codec.setParameters(params) }
            .onFailure { QpmLog.w(TAG, "动态码率设置失败（${bps}bps）: ${it.message}") }
    }

    fun stop() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val TAG = "H264Encoder"

        const val FLAG_KEY = 1
        const val FLAG_CONFIG = 2
    }
}
