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
    private val onFrame: (ByteArray, Int, Int, Long) -> Unit
) {
    val inputSurface: Surface

    private val codec: MediaCodec =
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    private val callback = object : MediaCodec.Callback() {
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

            var flags = 0
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or FLAG_CONFIG
            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or FLAG_KEY
            onFrame(out, info.size, flags, info.presentationTimeUs)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // 把 SPS/PPS (csd-0 / csd-1) 作为配置帧发出。
            // 否则接收端缺 SPS/PPS 无法初始化 H.264 解码器（画面全黑/无帧）。
            // 部分设备只把 SPS/PPS 放在 MediaFormat 的 csd 里，而不会通过
            // output buffer 的 BUFFER_FLAG_CODEC_CONFIG 回调出来，所以这里必须处理。
            for (key in arrayOf(MediaFormat.KEY_CSD_0, MediaFormat.KEY_CSD_1)) {
                if (!format.containsKey(key)) continue
                val buf = format.getByteBuffer(key) ?: continue
                val csd = ByteArray(buf.remaining())
                buf.get(csd)
                if (csd.isEmpty()) continue
                // 确保带 Annex-B 起始码（00 00 00 01），接收端按起始码解析最稳
                val out = if (csd.size >= 4 && csd[0] == 0.toByte() && csd[1] == 0.toByte()
                    && csd[2] == 0.toByte() && csd[3] == 1.toByte()) {
                    csd
                } else {
                    byteArrayOf(0, 0, 0, 1) + csd
                }
                onFrame(out, out.size, FLAG_CONFIG, 0L)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = Unit
    }

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            // 每像素约 4 bit，1080p 约 8.3Mbps，网络差可调低
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.setCallback(callback)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
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
        const val FLAG_KEY = 1
        const val FLAG_CONFIG = 2
    }
}
