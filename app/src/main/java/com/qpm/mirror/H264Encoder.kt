package com.qpm.mirror

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
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
            val buffer = codec.getOutputBuffer(index)
            if (buffer == null || info.size <= 0) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            val out = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(out)
            codec.releaseOutputBuffer(index, false)

            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

            if (isConfig) {
                // 这条 buffer 本身就是参数集（部分设备走这里），缓存起来
                cacheCsd(out)
                return
            }

            // 兜底：异步回调 onOutputFormatChanged 在部分设备上不触发，
            // 这里在首个有效输出帧时直接从 codec.outputFormat 取一次 csd。
            if (spsPps == null) {
                runCatching { cacheFromFormat(codec.outputFormat) }
            }

            var flags = 0
            if (isKey) {
                flags = flags or FLAG_KEY
                // 每个 IDR 前先补发 SPS/PPS，保证中途连入的接收端也能解码
                spsPps?.let { onFrame(it, it.size, FLAG_CONFIG, info.presentationTimeUs) }
            }
            onFrame(out, info.size, flags, info.presentationTimeUs)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // 用字面量键 "csd-0"/"csd-1"：MediaFormat.KEY_CSD_0 等常量在较新
            // compileSdk/AGP 组合下已被移除（改为 KEY_CSD_HEVC_* 等），用字符串键最稳。
            cacheFromFormat(format)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "onError: ${e.diagnosticInfo ?: e.message}")
            onError(e.diagnosticInfo ?: e.message ?: "未知编码器错误")
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
            }
            codec.setCallback(callback)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败: ${e.message}")
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
