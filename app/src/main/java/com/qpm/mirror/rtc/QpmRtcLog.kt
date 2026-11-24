package com.qpm.mirror.rtc

import com.qpm.mirror.QpmLog

/** 桥接既有日志系统，避免 WebRTC 包反向依赖具体 UI。 */
object QpmRtcLog {
    fun i(tag: String, msg: String) = QpmLog.i("rtc/$tag", msg)
    fun w(tag: String, msg: String) = QpmLog.w("rtc/$tag", msg)
    fun e(tag: String, msg: String) = QpmLog.e("rtc/$tag", msg)
}
