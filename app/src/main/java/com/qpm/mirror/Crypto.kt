package com.qpm.mirror

import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.MessageDigest
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.Cipher

/**
 * 传输层加密（#25 加密通道）：
 *
 * - 双方各生成 EC(secp256r1) 临时密钥对，用 X.509 编码的公钥通过 KEY_EXCHANGE 帧互换；
 * - ECDH 求出共享密钥后过一遍 SHA-256，得到 AES-256 会话密钥；
 * - 帧体用 AES/GCM/NoPadding（128-bit tag）加密，nonce 隐式生成：
 *   高 4 字节为方向（0=发送方->接收方，1=接收方->发送方），低 8 字节为同方向帧序号。
 *   TCP 有序且双方向各自独立计数，收发两端计数天然同步，无需随帧传输 nonce；
 *   方向位保证同一份密钥材料两个方向的 nonce 不重叠（GCM 严禁 nonce 重用）。
 *
 * 握手竞态约定（由调用方保证，见 SecureChannel 注释）：
 * - 发送端 accept 连接后第一帧必须是本方 KE；
 * - 接收端连上后第一帧必须是本方 KE，随后阻塞预读一帧等对端 KE。
 * 这样双方「发出的第一帧」必晚于「收到的第一帧」，不会出现各自等对方 KE 的死锁。
 */
internal object Crypto {
    private const val EC = "EC"
    private const val CURVE = "secp256r1"
    private const val KA = "ECDH"
    private const val XFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val NONCE_LEN = 12

    /** 方向常量：参与 nonce 构成，防跨向重用。 */
    const val DIR_PHONE = 0 // 发送端（手机） -> 接收端
    const val DIR_MIRROR = 1 // 接收端 -> 发送端（手机）

    /** 一方临时密钥对。 */
    class KeyPair(val publicEncoded: ByteArray, val privateKey: PrivateKey)

    fun newKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(EC)
        kpg.initialize(256)
        val kp = kpg.generateKeyPair()
        return KeyPair(kp.public.encoded, kp.private)
    }

    /** 解析对端 X.509 公钥。 */
    fun decodePublic(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance(EC).generatePublic(X509EncodedKeySpec(encoded))

    /** ECDH 共享密钥 -> SHA-256 -> AES-256 会话密钥。 */
    fun deriveSessionKey(self: KeyPair, peerEncoded: ByteArray): SecretKey {
        val peer = decodePublic(peerEncoded)
        val ka = KeyAgreement.getInstance(KA)
        ka.init(self.privateKey)
        ka.doPhase(peer, true)
        val shared = ka.generateSecret()
        val digest = MessageDigest.getInstance("SHA-256").digest(shared)
        return javax.crypto.spec.SecretKeySpec(digest, "AES")
    }

    /** nonce = [dir:u32][counter:u64]，均大端。 */
    fun nonce(dir: Int, counter: Long): ByteArray {
        val n = ByteArray(NONCE_LEN)
        n[0] = (dir ushr 24).toByte()
        n[1] = (dir ushr 16).toByte()
        n[2] = (dir ushr 8).toByte()
        n[3] = dir.toByte()
        for (i in 0 until 8) {
            n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        }
        return n
    }

    /** 加密：返回密文（含 16 字节 GCM tag）。 */
    fun encrypt(key: SecretKey, dir: Int, counter: Long, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(XFORM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, nonce(dir, counter))
        )
        return cipher.doFinal(plain)
    }

    /** 解密：tag 校验失败抛 AEADBadTagException。 */
    fun decrypt(key: SecretKey, dir: Int, counter: Long, cipherText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(XFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, nonce(dir, counter))
        )
        return cipher.doFinal(cipherText)
    }
}
