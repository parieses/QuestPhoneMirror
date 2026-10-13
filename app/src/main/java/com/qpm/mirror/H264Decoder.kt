package com.qpm.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.qpm.protocol.VFlag
import java.nio.ByteBuffer

/**
 * H.264 硬解码器，直接渲染到 SurfaceView 的 Surface。
 * 编码端会把 SPS/PPS 以 CODEC_CONFIG 标志透传，这里原样入队即可。
 */
class H264Decoder(
    private val surface: Surface,
    private val onVideoSize: (Int, Int) -> Unit
) {
    private val codec: MediaCodec =
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    // 异步模式下输入缓冲由 onInputBufferAvailable 回调分配，必须入队后再由 feed() 取用；
    // 不能依赖 dequeueInputBuffer（异步模式下它不等待、直接返回 -1，会导致帧全丢、画面黑屏）。
    private val availableInput = ArrayDeque<Int>()

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(availableInput) { availableInput.addLast(index) }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (info.size > 0) codec.releaseOutputBuffer(index, true)
            else codec.releaseOutputBuffer(index, false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            onVideoSize(
                format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT)
            )
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = Unit
    }

    init {
        codec.setCallback(callback)
        codec.configure(
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080),
            surface,
            null,
            0
        )
        codec.start()
    }

    /** body = [pts:u64][flags:u8][h264 data] */
    fun feed(body: ByteArray) {
        if (body.size < 9) return
        val bb = ByteBuffer.wrap(body)
        val pts = bb.long
        val flags = bb.get().toInt()
        val data = ByteArray(body.size - 9)
        System.arraycopy(body, 9, data, 0, data.size)

        // 异步模式下不能用 dequeueInputBuffer（不等待、直接返回 -1），必须从回调入队的空闲缓冲里取
        val index = synchronized(availableInput) { availableInput.removeFirstOrNull() }
            ?: return  // 暂无空闲输入缓冲，丢弃该帧以保流畅（不阻塞网络线程）
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(data)

        var f = 0
        if (flags and VFlag.KEY != 0) f = f or MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (flags and VFlag.CONFIG != 0) f = f or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        codec.queueInputBuffer(index, 0, data.size, pts, f)
    }

    fun stop() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
