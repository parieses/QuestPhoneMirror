package com.qpm.mirror.rtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * 全进程唯一的一套屏幕采集管线（采集器 + VideoSource + VideoTrack）。
 *
 * 单例是硬约束，不是风格问题：
 * Android 14 起同一个 MediaProjection 授权只允许调用一次
 * `createVirtualDisplay`；而 [ScreenCapturerAndroid.initialize] 内部必然建一次虚拟显示。
 * 旧实现「每个接收端连接都 new 一个采集器」，第二个客户端接入时立刻抛
 * `Don't take multiple captures by invoking MediaProjection#createVirtualDisplay
 * multiple times on the same instance`，并连带把整个录屏授权作废——
 * 真机日志里「连上 3 秒就断 / 录屏授权被系统撤销 / acceptLoop Socket closed」全是它的下游反应。
 *
 * 正确形态：采集器只建一次、一直跑；每条 PeerConnection 只是把同一条 VideoTrack
 * `addTrack` 进去（libwebrtc 支持一条轨道被多个 PeerConnection 发送）。
 */
class ScreenCapture private constructor(
    private val capturer: ScreenCapturerAndroid,
    private val source: VideoSource,
    private val helper: SurfaceTextureHelper,
    val track: VideoTrack,
    width: Int,
    height: Int
) {
    /** 当前采集尺寸（旋转后会变，广播 SCREEN_INFO 与触摸映射都以它为准）。 */
    @Volatile
    var width: Int = width
        private set

    @Volatile
    var height: Int = height
        private set

    /**
     * 原地改采集尺寸（旋转/分屏用）。
     *
     * `ScreenCapturerAndroid.changeCaptureFormat` 在 SDK 31+ 的实现是
     * `VirtualDisplay.resize()` + `setSurface()`，**不会**再调 createVirtualDisplay，
     * 所以是安全的；真正炸掉录屏授权的是「第二个采集器实例」，与本方法无关。
     *
     * 必须在非采集线程调用：它内部用 invokeAtFrontUninterruptibly 把活派到
     * SurfaceTextureHelper 的 handler 上，若从那个 handler 里调用会自锁。
     */
    fun resizeTo(w: Int, h: Int, fps: Int) {
        if (w <= 0 || h <= 0 || (w == width && h == height)) return
        width = w
        height = h
        runCatching { capturer.changeCaptureFormat(w, h, fps) }
            .onFailure { QpmRtcLog.w("Capture", "改采集尺寸失败：${it.message}") }
        QpmRtcLog.i("Capture", "采集尺寸切换为 ${w}x$h@$fps")
    }

    companion object {
        /**
         * 建采集管线并开始出帧。抛异常说明授权 token 已被用掉/过期，
         * 调用方必须提示用户「停止后重新开始投屏」（拿不到新 token 就无法恢复）。
         *
         * @param onProjectionStop 系统撤销录屏授权（下拉通知栏停止、切到别的投屏等）时回调
         * @param onContentResize 采集内容尺寸变化（旋转/分屏）；**只用于上报尺寸**，
         *   绝不能在此调 changeCaptureFormat —— 它内部会再 createVirtualDisplay 一次
         */
        fun start(
            context: Context,
            projectionData: Intent,
            width: Int,
            height: Int,
            fps: Int,
            onProjectionStop: () -> Unit,
            onContentResize: (Int, Int) -> Unit
        ): ScreenCapture {
            val factory = RtcFactory.init(context).first
            val egl = RtcFactory.eglContext
            val capturer = ScreenCapturerAndroid(
                projectionData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        QpmRtcLog.w("Capture", "MediaProjection onStop：授权被系统撤销")
                        onProjectionStop()
                    }

                    override fun onCapturedContentResize(w: Int, h: Int) {
                        onContentResize(w, h)
                    }
                }
            )
            // isScreencast = true：告诉 libwebrtc 这是屏幕内容，不要按摄像头那套
            // 「优先保帧率、动态降分辨率」的策略处理，静止画面下不做无谓的缩放。
            val source = factory.createVideoSource(true)
            val track = factory.createVideoTrack("qpm-screen", source)
            val helper = SurfaceTextureHelper.create("qpm-capture", egl)
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(width, height, fps)
            QpmRtcLog.i("Capture", "屏幕采集已启动 ${width}x$height@$fps（进程内唯一实例）")
            return ScreenCapture(capturer, source, helper, track, width, height)
        }
    }

    fun dispose() {
        runCatching { capturer.stopCapture() }
        runCatching { capturer.dispose() }
        runCatching { helper.dispose() }
        runCatching { source.dispose() }
        runCatching { track.dispose() }
        QpmRtcLog.i("Capture", "屏幕采集已释放")
    }
}
