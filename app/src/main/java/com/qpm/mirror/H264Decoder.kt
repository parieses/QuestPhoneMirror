package com.qpm.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
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
    private val onError: (String) -> Unit = {}
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

    // 异步模式下输入缓冲由 onInputBufferAvailable 回调分配，必须入队后再由 feed() 取用；
    // 不能依赖 dequeueInputBuffer（异步模式下它不等待、直接返回 -1，会导致帧全丢、画面黑屏）。
    private val availableInput = ArrayDeque<Int>()

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!alive) return
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
                val rendered = runCatching { codec.releaseOutputBuffer(index, true) }.isSuccess
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
            codec.setCallback(callback)
            codec.configure(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080),
                surface,
                null,
                0
            )
            codec.start()
            QpmLog.i(TAG, "解码器已启动（等待 SPS/PPS 与首个关键帧）")
        } catch (e: Exception) {
            QpmLog.e(TAG, "初始化失败: ${e.message}")
            alive = false
            runCatching { codec.release() }
            throw e
        }
    }

    /** body = [pts:u64][flags:u8][h264 data] */
    fun feed(body: ByteArray) {
        if (!alive || body.size < 9) return
        val bb = ByteBuffer.wrap(body)
        val pts = bb.long
        val flags = bb.get().toInt()
        val data = ByteArray(body.size - 9)
        System.arraycopy(body, 9, data, 0, data.size)

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
            QpmLog.i(TAG, "收到首个 SPS/PPS（${data.size} 字节）")
        }
        if (isKey && !sawKey) {
            sawKey = true
            QpmLog.i(TAG, "收到首个关键帧（${data.size} 字节）")
        }

        // 异步模式下不能用 dequeueInputBuffer（不等待、直接返回 -1），必须从回调入队的空闲缓冲里取
        val index = synchronized(availableInput) { availableInput.removeFirstOrNull() }
            ?: return  // 暂无空闲输入缓冲，丢弃该帧以保流畅（不阻塞网络线程）
        val buffer = runCatching { codec.getInputBuffer(index) }.getOrNull() ?: return
        buffer.clear()

        var f = 0
        if (isKey) f = f or MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (isConfig) f = f or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        if (data.size <= buffer.capacity()) {
            buffer.put(data)
            runCatching { codec.queueInputBuffer(index, 0, data.size, pts, f) }
        } else {
            // 个别机型/高分辨率下输入缓冲小于关键帧：丢载荷但必须归还缓冲，
            // 否则缓冲池逐个泄漏、最终无缓冲可用永久黑屏；同时绝不让 BufferOverflowException
            // 逃逸到网络线程把整条连接打断（表现为「配对成功后立马断」）
            QpmLog.w(TAG, "输入缓冲不足，丢弃 ${data.size} 字节帧（容量 ${buffer.capacity()}）")
            runCatching { codec.queueInputBuffer(index, 0, 0, pts, 0) }
        }
    }

    fun stop() {
        alive = false
        synchronized(availableInput) { availableInput.clear() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /** 已解码并渲染的帧数（供接收端计算解码 FPS）。 */
    fun framesDecoded(): Long = decodedFrames.get()

    private companion object {
        private const val TAG = "H264Decoder"
    }
}
