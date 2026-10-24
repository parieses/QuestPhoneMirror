package com.qpm.mirror

import android.view.MotionEvent
import com.qpm.protocol.Proto

/**
 * 把 SurfaceView 上的触摸/控制器点击映射成手机屏幕坐标。
 * 接收端可以是任意设备：触摸屏/鼠标天然走单指针；Quest 控制器在 2D 应用里
 * 也是单指针激光——三者都落在同一套单指点击与拖动逻辑里。
 */
class TouchMapper(
    private val viewW: () -> Int,
    private val viewH: () -> Int,
    private val phoneW: () -> Int,
    private val phoneH: () -> Int,
    private val send: (ByteArray) -> Unit
) {
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false

    fun onTouch(event: MotionEvent): Boolean {
        if (phoneW() <= 0 || phoneH() <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                tracking = true
                send(Proto.tap(mapX(event.x), mapY(event.y)))
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                send(
                    Proto.swipe(
                        mapX(lastX), mapY(lastY),
                        mapX(event.x), mapY(event.y),
                        40
                    )
                )
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }

    private fun mapX(x: Float) = x / viewW().coerceAtLeast(1) * phoneW()
    private fun mapY(y: Float) = y / viewH().coerceAtLeast(1) * phoneH()
}
