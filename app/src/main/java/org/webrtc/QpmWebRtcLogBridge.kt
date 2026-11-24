package org.webrtc

import com.qpm.mirror.QpmLog

/**
 * 把 libwebrtc 原生日志接进应用内日志面板。
 *
 * 为什么要放在 `org.webrtc` 包里：`Logging.injectLoggable` 是包级可见（package-private），
 * 只有同包的类才能调；这是官方留给宿主注入日志后端的唯一入口。
 *
 * 价值：像 `SessionDescription is NULL` 这类只在 native 侧才有根因的错误
 * （真实错误是 `Failed to parse: "..." Reason: Invalid SDP line`），
 * 以前只能在 adb logcat 里看到，接线后直接出现在 App 的「日志」页里，
 * 真机排查不用再拉电脑。
 */
object QpmWebRtcLogBridge {

    @Volatile
    private var installed = false

    /** 幂等；重复调用无副作用。 */
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            // 顺序不能反：enableLogToDebugOutput 是「装 native→Java 的日志水槽」，
            // 而它一旦发现已注入 Loggable 就会抛 IllegalStateException。
            // 装完水槽后再用 Loggable 把 Java 侧出口截走，日志就进了应用内日志页。
            Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
            Logging.injectLoggable(
                Loggable { message, severity, tag ->
                    // 这个回调跑在 libwebrtc 自己的线程上：任何异常漏进 native 都会触发
                    // jvm.cc 的 RTC_CHECK(false) → 整个进程 SIGABRT，所以必须整体兜住。
                    runCatching {
                        val line = "$tag: $message"
                        when (severity) {
                            Logging.Severity.LS_ERROR -> QpmLog.e("rtc/native", line)
                            Logging.Severity.LS_WARNING -> QpmLog.w("rtc/native", line)
                            else -> QpmLog.i("rtc/native", line)
                        }
                    }
                },
                Logging.Severity.LS_WARNING
            )
        }.onFailure {
            QpmLog.w("rtc/native", "接原生日志失败：${it.message}")
        }
    }
}
