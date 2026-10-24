package com.qpm.protocol

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** 消息类型。手机 -> 接收端用 0x01/0x20/0x15/0x17/0x19/0x1A/0x1C，接收端 -> 手机用 0x10-0x14/0x16/0x18/0x1B。 */
object Type {
    const val VIDEO_FRAME: Byte = 0x01
    const val SCREEN_INFO: Byte = 0x20

    const val TAP: Byte = 0x10
    const val SWIPE: Byte = 0x11
    const val TEXT: Byte = 0x12
    const val KEY: Byte = 0x13

    /** 配对：接收端 -> 手机，body 为 UTF-8 配对码；空 body = 免密直连请求（仅网络内唯一发送端时有效） */
    const val PAIR: Byte = 0x14
    /** 配对结果：手机 -> 接收端，body 为 u8（1=成功 0=失败） */
    const val PAIR_RESULT: Byte = 0x15

    /** 文件：请求列出目录，body 为 UTF-8 路径 */
    const val FILE_LIST_REQ: Byte = 0x16
    /** 文件：目录列表，body 为 UTF-8，条目以 \n 分隔，字段以 \t 分隔：name\tisDir\tsize\tmtime */
    const val FILE_LIST_RES: Byte = 0x17
    /** 文件：请求下载，body 为 UTF-8 路径 */
    const val FILE_GET_REQ: Byte = 0x18
    /** 文件：数据分片，body = [offset:u64][total:u64][flags:u8(eof)][chunk] */
    const val FILE_DATA: Byte = 0x19
    /** 文件：错误提示，body 为 UTF-8（如未授权、路径非法） */
    const val FILE_ERROR: Byte = 0x1A

    /** 接收端上线宣告：接收端 -> 手机，body 为 UTF-8 设备名（如 Quest 3 / Pixel 8 / PC），连接建立后立即发送 */
    const val HELLO: Byte = 0x1B
    /** 发送端拒绝连接：手机 -> 接收端，body 为 UTF-8 原因（如未在白名单），发送后即断开 */
    const val REJECT: Byte = 0x1C
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

    /** 配对码（4-6 位数字字符串）。 */
    fun pair(code: String): ByteArray {
        val raw = code.toByteArray(StandardCharsets.UTF_8)
        val b = head(Type.PAIR, raw.size)
        b.put(raw)
        return b.array()
    }

    /** 配对结果，1=成功 0=失败。 */
    fun pairResult(ok: Boolean): ByteArray {
        val b = head(Type.PAIR_RESULT, 1)
        b.put(if (ok) 1.toByte() else 0.toByte())
        return b.array()
    }

    // ---------- 文件 ----------

    fun fileListReq(path: String): ByteArray {
        val raw = path.toByteArray(StandardCharsets.UTF_8)
        val b = head(Type.FILE_LIST_REQ, raw.size)
        b.put(raw)
        return b.array()
    }

    fun fileListRes(entries: List<String>): ByteArray {
        val raw = entries.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val b = head(Type.FILE_LIST_RES, raw.size)
        b.put(raw)
        return b.array()
    }

    fun fileGetReq(path: String): ByteArray {
        val raw = path.toByteArray(StandardCharsets.UTF_8)
        val b = head(Type.FILE_GET_REQ, raw.size)
        b.put(raw)
        return b.array()
    }

    /** [offset:u64][total:u64][flags:u8][chunk]，flags bit0=eof。 */
    fun fileData(offset: Long, total: Long, eof: Boolean, chunk: ByteArray): ByteArray {
        val b = head(Type.FILE_DATA, 17 + chunk.size)
        b.putLong(offset)
        b.putLong(total)
        b.put(if (eof) 1.toByte() else 0.toByte())
        b.put(chunk)
        return b.array()
    }

    fun fileError(msg: String): ByteArray {
        val raw = msg.toByteArray(StandardCharsets.UTF_8)
        val b = head(Type.FILE_ERROR, raw.size)
        b.put(raw)
        return b.array()
    }

    // ---------- 连接管理 ----------

    /** 接收端设备名宣告。 */
    fun hello(name: String): ByteArray {
        val raw = name.toByteArray(StandardCharsets.UTF_8)
        val b = head(Type.HELLO, raw.size)
        b.put(raw)
        return b.array()
    }

    /** 拒绝连接及原因。 */
    fun reject(reason: String): ByteArray {
        val raw = reason.toByteArray(StandardCharsets.UTF_8)
        val b = head(Type.REJECT, raw.size)
        b.put(raw)
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

    fun pairOf(f: Frame): String = String(f.body, StandardCharsets.UTF_8)

    fun pairResultOf(f: Frame): Boolean =
        f.body.isNotEmpty() && f.body[0] != 0.toByte()

    fun pathOf(f: Frame): String = String(f.body, StandardCharsets.UTF_8)

    fun fileEntriesOf(f: Frame): List<String> =
        String(f.body, StandardCharsets.UTF_8)
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 返回 [offset, total, eof, chunk]。 */
    fun fileDataOf(f: Frame): FileChunk {
        val b = ByteBuffer.wrap(f.body).order(ByteOrder.LITTLE_ENDIAN)
        val offset = b.long
        val total = b.long
        val eof = b.get() != 0.toByte()
        val chunk = ByteArray(f.body.size - 17)
        b.get(chunk)
        return FileChunk(offset, total, eof, chunk)
    }
}

class FileChunk(
    val offset: Long,
    val total: Long,
    val eof: Boolean,
    val chunk: ByteArray
)
