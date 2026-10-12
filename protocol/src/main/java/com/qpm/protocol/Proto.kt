package com.qpm.protocol

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** 消息类型。手机 -> 头显用 0x01/0x20，头显 -> 手机用 0x10-0x13。 */
object Type {
    const val VIDEO_FRAME: Byte = 0x01
    const val SCREEN_INFO: Byte = 0x20

    const val TAP: Byte = 0x10
    const val SWIPE: Byte = 0x11
    const val TEXT: Byte = 0x12
    const val KEY: Byte = 0x13
}

/** 视频帧附加标志 */
object VFlag {
    const val KEY = 1
    const val CONFIG = 2
}

class Frame(val type: Byte, val body: ByteArray)

/**
 * 统一帧格式：[type:u8][length:u32 LE][body]
 * 双向同一条 TCP 连接，靠 type 分流。
 */
object Proto {
    const val MAX_BODY = 4 shl 20

    private fun head(type: Byte, bodySize: Int): ByteBuffer =
        ByteBuffer.allocate(5 + bodySize).order(ByteOrder.LITTLE_ENDIAN)
            .put(type).putInt(bodySize)

    fun videoFrame(ptsUs: Long, flags: Int, data: ByteArray, off: Int, len: Int): ByteArray {
        val b = head(Type.VIDEO_FRAME, 9 + len)
        b.putLong(ptsUs)
        b.put(flags.toByte())
        b.put(data, off, len)
        return b.array()
    }

    fun screenInfo(w: Int, h: Int, dpi: Int): ByteArray {
        val b = head(Type.SCREEN_INFO, 12)
        b.putInt(w); b.putInt(h); b.putInt(dpi)
        return b.array()
    }

    fun tap(x: Float, y: Float): ByteArray {
        val b = head(Type.TAP, 8)
        b.putFloat(x); b.putFloat(y)
        return b.array()
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): ByteArray {
        val b = head(Type.SWIPE, 20)
        b.putFloat(x1); b.putFloat(y1); b.putFloat(x2); b.putFloat(y2)
        b.putInt(durationMs)
        return b.array()
    }

    fun text(s: String): ByteArray {
        val raw = s.toByteArray(StandardCharsets.UTF_8)
        val b = head(Type.TEXT, raw.size)
        b.put(raw)
        return b.array()
    }

    fun key(keycode: Int): ByteArray {
        val b = head(Type.KEY, 4)
        b.putInt(keycode)
        return b.array()
    }
}

/** 阻塞读取完整帧；流结束返回 null。 */
class FrameReader(private val input: InputStream) {
    private val headBuf = ByteArray(5)

    fun read(): Frame? {
        if (!fill(headBuf)) return null
        val type = headBuf[0]
        val len = ByteBuffer.wrap(headBuf, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (len < 0 || len > Proto.MAX_BODY) throw java.io.IOException("非法帧长度: $len")
        val body = ByteArray(len)
        if (!fill(body)) return null
        return Frame(type, body)
    }

    private fun fill(buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) return false
            n += r
        }
        return true
    }
}

object Decoders {
    fun screenInfo(f: Frame): Triple<Int, Int, Int> {
        val b = ByteBuffer.wrap(f.body).order(ByteOrder.LITTLE_ENDIAN)
        return Triple(b.int, b.int, b.int)
    }

    fun tap(f: Frame): Pair<Float, Float> {
        val b = ByteBuffer.wrap(f.body).order(ByteOrder.LITTLE_ENDIAN)
        return Pair(b.float, b.float)
    }

    /** 返回 [x1, y1, x2, y2, durationMs] */
    fun swipe(f: Frame): FloatArray {
        val b = ByteBuffer.wrap(f.body).order(ByteOrder.LITTLE_ENDIAN)
        return floatArrayOf(b.float, b.float, b.float, b.float, b.int.toFloat())
    }

    fun textOf(f: Frame): String = String(f.body, StandardCharsets.UTF_8)

    fun keyOf(f: Frame): Int =
        ByteBuffer.wrap(f.body).order(ByteOrder.LITTLE_ENDIAN).int
}
