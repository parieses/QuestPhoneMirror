package com.qpm.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.qpm.protocol.Proto
import com.qpm.protocol.VFlag
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 硬解码器，直接渲染到 SurfaceView 的 Surface。
 * 编码端会把 SPS/PPS 以 CODEC_CONFIG 标志透传，这里原样入队即可。
 */
class H264Decoder(
    private val surface: Surface,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit = {},
    /**
     * configure 用的尺寸，应与发送端实际码流一致（接收端从 SCREEN_INFO 拿到）。
     * 用写死的默认尺寸去解一个分辨率明显不同的码流时，部分机型的硬解会在
     * native 层直接崩溃（Java 层捕获不到，表现为「连上就闪退/一转屏就断」）。
     */
    val videoW: Int = 1920,
    val videoH: Int = 1080
) {
    private val codec: MediaCodec =
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    /** 已成功渲染的帧数（接收端统计浮窗的「解码 FPS」用）。 */
    private val decodedFrames = AtomicLong(0)

    /** 存活标志：stop() 后为 false，异步回调与 feed 不再触碰 codec/Surface，避免向已销毁的 Surface 渲染导致原生崩溃。 */
    @Volatile
    private var alive = true

    // 里程碑标记：拿到参数集/关键帧之前绝不能往硬解里塞 P 帧——
    // 没有 SPS/PPS 时部分机型的硬解会在 native 层直接崩溃（Java 层捕获不到，
    // 表现为「配对成功后进程消失、没有任何堆栈」）。
    private var sawCsd = false
    private var sawKey = false
    private var loggedFirstRender = false
    private var droppedEarly = 0

    /**
     * PTS pacing：以「首帧到达本机时刻」为锚点，把编码端 pts 的节奏映射到本机
     * System.nanoTime()，再用 releaseOutputBuffer(index, timestampNs) 定时渲染。
     * 网络抖动不再直接变成视觉抖动；两端时钟不同源也没关系，只用相对 pts 差值。
     */
    private var basePtsUs: Long? = null
    private var baseArriveNs = 0L

    private fun renderTimeNs(ptsUs: Long): Long {
        val base = basePtsUs
        if (base == null || ptsUs - base !in -1_000_000L..1_000_000L) {
            // 首帧 / 编码器重启导致 pts 跳变：重新锚定，避免把帧排到几秒之后
            basePtsUs = ptsUs
            baseArriveNs = System.nanoTime()
            return baseArriveNs + PACING_NS
        }
        return baseArriveNs + (ptsUs - base) * 1000L + PACING_NS
    }

    // 异步模式下输入缓冲由 onInputBufferAvailable 回调分配，必须入队后再由 feed() 取用；
    // 不能依赖 dequeueInputBuffer（异步模式下它不等待、直接返回 -1，会导致帧全丢、画面黑屏）。
    private val availableInput = ArrayDeque<Int>()

    /**
     * 无空闲输入缓冲时暂存一帧关键帧（IDR / SPS-PPS），等下一个缓冲回调优先入队。
     * 丢关键帧会花屏整整一个 GOP（1s）；P 帧仍直接丢以保延迟。
     */
    private val pendingLock = Any()
    private var pendingCritical: PendingFrame? = null

    /** 保留完整帧体，h264 段按 [VIDEO_HEADER, ...) 直接写进 codec 缓冲，不再拆一份副本。 */
    private data class PendingFrame(
        val body: ByteArray,
        val payloadLen: Int,
        val pts: Long,
        val flags: Int
    )

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!alive) return
            val held = synchronized(pendingLock) {
                val p = pendingCritical
                pendingCritical = null
                p
            }
            if (held != null && feedBuffer(index, held.body, held.payloadLen, held.pts, held.flags)) return
            synchronized(availableInput) { availableInput.addLast(index) }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (!alive) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            // releaseOutputBuffer 在 codec 已停止/出错时会抛 IllegalStateException，
            // 回调线程未捕获异常会直接杀进程——全部兜底
            if (info.size > 0) {
                // 按 pts 节奏渲染；个别设备不支持带 timestamp 的重载时退回立即渲染
                val targetNs = renderTimeNs(info.presentationTimeUs)
                val rendered = runCatching { codec.releaseOutputBuffer(index, targetNs) }.isSuccess ||
                    runCatching { codec.releaseOutputBuffer(index, true) }.isSuccess
                if (rendered) {
                    decodedFrames.incrementAndGet()
                    if (!loggedFirstRender) {
                        loggedFirstRender = true
                        QpmLog.i(TAG, "首个输出帧已渲染到 Surface")
                    }
                }
            } else {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (!alive) return
            val w = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull() ?: return
            val h = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull() ?: return
            onVideoSize(w, h)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            QpmLog.e(TAG, "onError: ${e.diagnosticInfo ?: e.message}")
            onError(e.diagnosticInfo ?: e.message ?: "未知解码器错误")
        }
    }

    init {
        try {
            // 刻意不设 max-input-size / "low-latency" 之类的可选 configure 参数：
            // 部分机型（尤其 codec2/联发科）对未知键直接 configure 失败，异常冒出去
            // 会触发 buildDecoder 的候选尺寸降级——用错尺寸解码器解降采样码流正是
            // 上一轮「连上就 native 闪退」的成因。帧太大的兜底放在 feedBuffer
            // （归还缓冲 + 丢该帧），低延迟改由 PTS pacing（定时 releaseOutputBuffer）实现。
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoW, videoH)
            codec.setCallback(callback)
            codec.configure(format, surface, null, 0)
            codec.start()
            QpmLog.i(TAG, "解码器已启动 ${videoW}x${videoH}（等待 SPS/PPS 与首个关键帧）")
        } catch (e: Exception) {
            QpmLog.e(TAG, "初始化失败: ${e.message}")
            alive = false
            runCatching { codec.release() }
            throw e
        }
    }

    /** body = [pts:u64][flags:u8][sentAtMs:u32][h264 data] */
    fun feed(body: ByteArray) {
        if (!alive || body.size <= Proto.VIDEO_HEADER) return
        val bb = ByteBuffer.wrap(body)
        val pts = bb.long
        val flags = bb.get().toInt() and 0xFF
        val payloadLen = body.size - Proto.VIDEO_HEADER

        // 只把合法 Annex-B 码流交给硬解。加密规则/计数器错位或新旧版本混连时，
        // 视频帧位置可能送来密文或别的帧体；把随机字节当成 H.264 喂进去，部分机型
        // 硬解会在 native 层 SIGSEGV（Java 层拦不住，表现就是「一连上就闪退」）。
        if (!hasStartCode(body, payloadLen)) {
            if (droppedEarly++ < 3) {
                QpmLog.w(TAG, "帧体缺少 Annex-B 起始码，丢弃（疑似密文/协议错位），size=$payloadLen")
            }
            return
        }

        val isConfig = flags and VFlag.CONFIG != 0
        val isKey = flags and VFlag.KEY != 0
        // 拿到 SPS/PPS（或首个 IDR，参数集可能内联在 IDR 里）之前，P 帧对解码器毫无意义：
        // 往没有参数集的硬解里塞 P 帧，部分机型会在 native 层直接崩溃，Java 层拦不住
        if (!sawCsd && !sawKey && !isConfig && !isKey) {
            if (droppedEarly++ == 0) QpmLog.w(TAG, "参数集到达前收到 P 帧，先丢弃（避免硬解 native 崩溃）")
            return
        }
        if (isConfig && !sawCsd) {
            sawCsd = true
            QpmLog.i(TAG, "收到首个 SPS/PPS（${payloadLen} 字节）")
        }
        if (isKey && !sawKey) {
            sawKey = true
            QpmLog.i(TAG, "收到首个关键帧（${payloadLen} 字节）")
        }

        var f = 0
        if (isKey) f = f or MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (isConfig) f = f or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        enqueueOrHold(PendingFrame(body, payloadLen, pts, f))
    }

    /** 从回调入队的空闲缓冲里取一个；没有时关键帧暂存，P 帧直接丢（不阻塞网络线程）。 */
    private fun enqueueOrHold(frame: PendingFrame) {
        while (true) {
            val index = synchronized(availableInput) { availableInput.removeFirstOrNull() }
            if (index == null) {
                if (frame.flags != 0) synchronized(pendingLock) { pendingCritical = frame }
                return
            }
            if (feedBuffer(index, frame.body, frame.payloadLen, frame.pts, frame.flags)) return
        }
    }

    /** 返回是否成功占用该输入缓冲；false 表示可以换下一个。 */
    private fun feedBuffer(index: Int, body: ByteArray, payloadLen: Int, pts: Long, flags: Int): Boolean {
        // 本方法既可能在网络线程、也可能在 MediaCodec 异步回调线程执行。回调线程里
        // 任何未捕获异常都会直接杀进程，所以整个方法体（含 getInputBuffer / clear / put）
        // 必须兜底，且异常时归还缓冲，否则缓冲池逐个泄漏最终永久黑屏。
        return runCatching {
            val buffer = codec.getInputBuffer(index)
            if (buffer == null) {
                false
            } else {
                buffer.clear()
                if (payloadLen <= buffer.capacity()) {
                    buffer.put(ByteBuffer.wrap(body, Proto.VIDEO_HEADER, payloadLen))
                    runCatching { codec.queueInputBuffer(index, 0, payloadLen, pts, flags) }
                } else {
                    QpmLog.w(TAG, "输入缓冲不足，丢弃 ${payloadLen} 字节帧（容量 ${buffer.capacity()}）")
                    runCatching { codec.queueInputBuffer(index, 0, 0, pts, 0) }
                }
                true
            }
        }.getOrElse {
            QpmLog.w(TAG, "输入缓冲写入异常，归还 index=$index: ${it.javaClass.simpleName}: ${it.message}")
            runCatching { codec.queueInputBuffer(index, 0, 0, pts, 0) }
            false
        }
    }

    /** payload 是否以 00 00 01 / 00 00 00 01 起始码开头（MediaCodec H.264 输出格式）。 */
    private fun hasStartCode(body: ByteArray, payloadLen: Int): Boolean {
        if (payloadLen < 3) return false
        val p = Proto.VIDEO_HEADER
        if (body[p] != 0.toByte() || body[p + 1] != 0.toByte()) return false
        if (body[p + 2] == 1.toByte()) return true
        return payloadLen >= 4 && body[p + 2] == 0.toByte() && body[p + 3] == 1.toByte()
    }

    fun stop() {
        alive = false
        synchronized(pendingLock) { pendingCritical = null }
        synchronized(availableInput) { availableInput.clear() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /** 已解码并渲染的帧数（供接收端计算解码 FPS）。 */
    fun framesDecoded(): Long = decodedFrames.get()

    private companion object {
        private const val TAG = "H264Decoder"
        /** 固定 25ms 渲染偏移：给网络抖动留一点余量，又不会明显抬高延迟。 */
        private const val PACING_NS = 25_000_000L
    }
}
