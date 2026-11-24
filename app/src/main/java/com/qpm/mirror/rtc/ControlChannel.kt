package com.qpm.mirror.rtc

import com.qpm.protocol.Decoders
import com.qpm.protocol.Proto
import com.qpm.protocol.Type

/**
 * WebRTC DataChannel 上的反向控制协议。
 * 帧格式直接复用现有 Proto（触摸/按键/文本），因此 TouchMapper 与
 * QpmControlService 一行都不用改——只把 TCP 的 send 闭包换成 send()。
 *
 * 接收端（ANSWER）→ 发送端（OFFER）方向：触摸事件；
 * 发送端收到后按 type 分发给 QpmControlService 注入手势。
 */
object ControlChannel {

    /** 接收端调用：把 Proto 帧投递到 DataChannel。返回是否成功。 */
    fun send(session: RtcSession, wire: ByteArray): Boolean = session.sendData(wire)

    /**
     * 发送端调用：把 DataChannel 收到的帧分发到本机无障碍注入。
     * 与 CastService.Client.readLoop 里的控制分支逻辑一致，但数据源换成 DataChannel。
     */
    fun dispatch(wire: ByteArray, sink: ControlSink) {
        if (wire.size < 5) return
        when (wire[0]) {
            Type.TAP -> {
                val (x, y) = Decoders.tap(wire.toFrame())
                sink.tap(x, y)
            }
            Type.SWIPE -> {
                val f = Decoders.swipe(wire.toFrame())
                sink.swipe(f[0], f[1], f[2], f[3], f[4].toInt())
            }
            Type.TEXT -> sink.text(Decoders.textOf(wire.toFrame()))
            Type.KEY -> sink.key(Decoders.keyOf(wire.toFrame()))
            Type.TOUCH_DOWN -> {
                val (x, y) = Decoders.tap(wire.toFrame())
                sink.touchDown(x, y)
            }
            Type.TOUCH_MOVE -> sink.touchMove(Decoders.touchPoints(wire.toFrame()))
            Type.TOUCH_UP -> sink.touchUp()
            Type.CLIPBOARD -> sink.clipboard(Decoders.clipboardOf(wire.toFrame()))
            else -> Unit
        }
    }

    interface ControlSink {
        fun tap(x: Float, y: Float)
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int)
        fun text(s: String)
        fun key(keycode: Int)
        fun touchDown(x: Float, y: Float)
        fun touchMove(points: FloatArray)
        fun touchUp()
        fun clipboard(text: String)
    }

    /** wire = [type][len:u32 LE][body]，包成 Frame 复用 Decoders。 */
    private fun ByteArray.toFrame(): com.qpm.protocol.Frame {
        val len = java.nio.ByteBuffer.wrap(this, 1, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        return com.qpm.protocol.Frame(this[0], copyOfRange(5, 5 + len.coerceAtMost(size - 5)))
    }
}
