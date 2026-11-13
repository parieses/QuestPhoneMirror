package com.qpm.mirror

import android.os.SystemClock
import android.view.MotionEvent
import com.qpm.protocol.Proto
import kotlin.math.abs

/**
 * 把 SurfaceView 上的触摸/控制器点击映射成手机屏幕坐标。
 * 接收端可以是任意设备：触摸屏/鼠标天然走单指针；Quest 控制器在 2D 应用里
 * 也是单指针激光——三者都落在同一套单指点击与拖动逻辑里。
 *
 * 点击与拖动分流，避免把最高频的单击交给连续手势（willContinue 的 stroke 在
 * DOWN/UP 间隔极短时可能不产生有效点击）：
 * - 未越过 slop 就抬手 → 发旧的 TAP 帧，走 60ms 单击手势，行为与旧版一致；
 * - 越过 slop 才建手势：一条 TOUCH_DOWN + 按 32ms 窗口批量的 TOUCH_MOVE + TOUCH_UP，
 *   发送端把它们续成一条连续手势，取代逐条 SWIPE 的一串「按下抬起」。
 */
class TouchMapper(
    private val viewW: () -> Int,
    private val viewH: () -> Int,
    private val phoneW: () -> Int,
    private val phoneH: () -> Int,
    private val send: (ByteArray) -> Unit
) {
    /** 待发的坐标对，按 [x1,y1,x2,y2,...] 平铺。 */
    private val pending = ArrayList<Float>(16)
    private var lastFlushMs = 0L
    private var tracking = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    fun onTouch(event: MotionEvent): Boolean {
        if (phoneW() <= 0 || phoneH() <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                dragging = false
                pending.clear()
                downX = event.x
                downY = event.y
                // 先不发帧：等抬手（点击）或越过 slop（拖动）再决定走哪条路径
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                val now = SystemClock.elapsedRealtime()
                if (!dragging) {
                    // 手指轻微抖动不该把点击变成拖动
                    if (abs(event.x - downX) + abs(event.y - downY) < TOUCH_SLOP) return true
                    dragging = true
                    // 起点用 DOWN 的原始坐标，拖动路径才不会丢掉第一段
                    send(Proto.touchDown(mapX(downX), mapY(downY)))
                    lastFlushMs = now
                }
                pending.add(mapX(event.x))
                pending.add(mapY(event.y))
                // 高频触摸屏一帧能报多个点，加上硬上限防止单包过大
                if (now - lastFlushMs >= FLUSH_MS || pending.size >= MAX_PENDING) flush(now)
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return true
                if (dragging) {
                    flush(SystemClock.elapsedRealtime())
                    send(Proto.touchUp())
                } else {
                    // 纯点击：沿用旧的 TAP 帧，单击可靠性不受连续手势影响
                    send(Proto.tap(mapX(downX), mapY(downY)))
                }
                reset()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return true
                // 手势被系统取消：拖动要抬手，点击则什么都不发
                if (dragging) {
                    pending.clear()
                    send(Proto.touchUp())
                }
                reset()
            }
        }
        return true
    }

    private fun reset() {
        tracking = false
        dragging = false
        pending.clear()
        lastFlushMs = 0L
    }

    private fun flush(nowMs: Long) {
        if (pending.isEmpty()) {
            lastFlushMs = nowMs
            return
        }
        val points = FloatArray(pending.size)
        for (i in pending.indices) points[i] = pending[i]
        pending.clear()
        lastFlushMs = nowMs
        runCatching { send(Proto.touchMove(points)) }
            .onFailure { QpmLog.w(TAG, "批量触摸点发送失败: ${it.message}") }
    }

    private fun mapX(x: Float) = x / viewW().coerceAtLeast(1) * phoneW()
    private fun mapY(y: Float) = y / viewH().coerceAtLeast(1) * phoneH()

    private companion object {
        private const val TAG = "TouchMapper"
        /** 约 30Hz 批量刷新：再低会明显不跟手，再高又会退回逐点发手势。 */
        private const val FLUSH_MS = 32L
        private const val MAX_PENDING = 64
        private const val TOUCH_SLOP = 8f
    }
}
