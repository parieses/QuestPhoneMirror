package com.qpm.mirror

import android.util.Log
import com.qpm.protocol.Frame
import javax.crypto.SecretKey

/**
 * 传输层加密通道（#25 加密通道）。
 *
 * 本类把「帧体 AES-GCM 加解密」收敛成单点，调用方（CastService.Client / MirrorClient）
 * 的所有写出口统一过 [seal]，所有读入口统一过 [open]，未武装（密钥交换前/失败回落）时
 * 两者都原样透传，协议帧 type 与 [FrameReader] 解析逻辑完全不变。
 *
 * 握手时序（由调用方保证，不死锁）：
 * - 接收端连上先发本方 KE（明文），随后阻塞预读一帧，期望对端 KE；
 * - 发送端 accept 后先在读循环读首帧，收到 KE 则发本方 KE（明文）再武装；
 * 这样「双方发出的第一帧」都晚于「收到的第一帧」，不会互等 KE 死锁。
 *
 * nonce 方向：构造参数 [sendDir] 为本端「发出」方向；解密对端帧时用 [sendDir] xor 1。
 */
internal class SecureChannel(private val sendDir: Int) {
    @Volatile
    private var key: SecretKey? = null
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val lock = Any()

    /** 加密握手/运算一旦异常，置位后所有 seal/open 退化为原样透传，避免异常逃逸导致连接卡死或崩溃。 */
    @Volatile
    var broken: Boolean = false
        private set

    val armed: Boolean
        get() = key != null && !broken

    fun arm(sessionKey: SecretKey) {
        synchronized(lock) {
            key = sessionKey
            sendCounter = 0L
            recvCounter = 0L
            broken = false
        }
    }

    /**
     * 把一帧（带 [type:u8][length:u32 LE][body] 头的完整 wire 字节）加密后返回；未武装原样返回。
     * 调用方须保证「seal + 写出」在同一把锁内原子完成（见 CastService.writeLock / MirrorClient.channel 监视器），
     * 否则计数器自增顺序与 wire 顺序错位会让对端 GCM 校验失败。
     * 任何加密异常都退化为透传并置 [broken]，绝不外抛。
     */
    fun seal(wire: ByteArray): ByteArray {
        val k = key ?: return wire
        return try {
            val type = wire[0]
            val bodyLen = leToInt(wire, 1)
            val plain = wire.copyOfRange(5, 5 + bodyLen)
            val cipher = Crypto.encrypt(k, sendDir, sendCounter, plain)
            val out = ByteArray(5 + cipher.size)
            out[0] = type
            putLeInt(out, 1, cipher.size)
            System.arraycopy(cipher, 0, out, 5, cipher.size)
            sendCounter++
            out
        } catch (e: Exception) {
            Log.w(TAG, "seal 失败，降级明文: ${e.message}")
            broken = true
            wire
        }
    }

    /** 解密一帧；未武装原样返回。任何解密异常退化为透传并置 [broken]，绝不外抛。 */
    fun open(frame: Frame): Frame {
        val k = key ?: return frame
        return try {
            val plain = Crypto.decrypt(k, sendDir xor 1, recvCounter, frame.body)
            recvCounter++
            Frame(frame.type, plain)
        } catch (e: Exception) {
            Log.w(TAG, "open 失败，降级明文: ${e.message}")
            broken = true
            frame
        }
    }

    private companion object {
        private const val TAG = "SecureChannel"
    }

    private fun leToInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putLeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
