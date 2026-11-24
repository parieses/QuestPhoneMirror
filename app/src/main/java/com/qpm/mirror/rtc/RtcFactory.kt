package com.qpm.mirror.rtc

import android.content.Context
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory

/**
 * WebRTC 全局单例：EGL 上下文 + PeerConnectionFactory 只能初始化一次。
 * 工厂内部持有 native 线程，重复创建会泄漏且导致 Surface 渲染黑屏。
 */
object RtcFactory {

    @Volatile
    private var egl: EglBase? = null

    @Volatile
    private var factory: PeerConnectionFactory? = null

    val eglContext: EglBase.Context?
        get() = egl?.eglBaseContext

    /**
     * 禁用 mDNS 混淆。Android WebRTC 默认把 host candidate 变成 uuid.local，
     * 纯局域网无 mDNS 解析器时两端互相找不到，必须关掉。
     * Field trial 语法在 144.x 仍有效（initializeFieldTrials 已验证存在）。
     */
    private const val FIELD_TRIALS = "WebRTC-HideLocalIpsWithMdns/Disabled/"

    @Synchronized
    fun init(context: Context): Pair<PeerConnectionFactory, EglBase.Context> {
        factory?.let { f ->
            eglContext?.let { return f to it }
        }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setFieldTrials(FIELD_TRIALS)
                .createInitializationOptions()
        )
        val newEgl = EglBase.create()
        val newFactory = PeerConnectionFactory.builder()
            // 先用纯软件编解码（VP8/VP9）定位问题：
            // DefaultVideoEncoderFactory 会走 HardwareVideoEncoderFactory → MediaCodec，
            // 这条路径是 native 线程经 JNI 回调 Java 的，MediaCodec configure/start 抛的任何
            // 异常都会冒进 native 被判为致命错误（jvm.cc:81 Check failed → SIGABRT）。
            // 真机现在正是「offer 设好、约 660ms 后进程 abort，日志里没有 gathering / candidate」，
            // 与硬件编码器初始化耗时吻合。软件编码先保证链路打通，再回头单独验证硬编。
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
        egl = newEgl
        factory = newFactory
        // 把 native 侧日志接进应用内日志页：SDP 解析失败这类根因只有 native 会说清楚
        // （宿主没有 adb 时，这是唯一能看到真实原因的地方）
        runCatching { org.webrtc.QpmWebRtcLogBridge.install() }
            .onFailure { QpmRtcLog.w("RtcFactory", "接原生日志失败：${it.message}") }
        QpmRtcLog.i("RtcFactory", "WebRTC 已初始化（mDNS 混淆已禁用）")
        return newFactory to newEgl.eglBaseContext
    }
}
