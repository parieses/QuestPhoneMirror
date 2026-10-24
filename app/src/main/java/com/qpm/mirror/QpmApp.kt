package com.qpm.mirror

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局诊断入口：捕获所有线程上未捕获的异常，把完整堆栈写进 logcat + 本地 crash 文件，
 * 再交回系统默认处理器（进程照常死亡，但诊断信息不丢）。
 *
 * 光有 Java 层捕获还不够：**Native 崩溃（SIGSEGV）、ANR 被杀、系统低内存回收都不会走
 * Java 未捕获异常处理器**，日志里只表现为「进程重启、没有任何堆栈」——正是「接收端连接
 * 成功后闪退回首页」这种故障的形态。因此启动时还会读取系统记录的进程退出历史
 * （ApplicationExitInfo），拿到原因码与 native tombstone。
 *
 * 复现后查看：入口页 → 诊断日志（页内有分享按钮），或
 * logcat `adb logcat -s QpmApp QpmLog ReceiverActivity MirrorClient H264Decoder CastService`
 */
class QpmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        QpmLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            QpmLog.e(TAG, "！！！未捕获异常，进程即将死亡 thread=${thread.name}", throwable)
            runCatching { writeCrashFile(thread, throwable) }
                .onFailure { QpmLog.w(TAG, "崩溃文件写入失败: ${it.message}") }
            // 交回原处理器，保留系统默认行为（弹「已停止」/生成 tombstone）
            previous?.uncaughtException(thread, throwable)
        }
        QpmLog.i(TAG, "QpmApp 启动，全局崩溃捕获已安装")
        runCatching { reportPreviousExits() }
            .onFailure { QpmLog.w(TAG, "读取进程退出历史失败: ${it.message}") }
    }

    private fun writeCrashFile(thread: Thread, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append("time: ").append(FMT.format(Date()))
            append("\nthread: ").append(thread.name)
            append("\napp: ").append(packageName)
            append("\n\n").append(sw)
        }
        val dir = File(getExternalFilesDir(null), "crashes")
        dir.mkdirs()
        File(dir, "crash-${System.currentTimeMillis()}.log").writeText(text)
        QpmLog.i(TAG, "崩溃堆栈已写入 ${dir.absolutePath}")
    }

    /**
     * 上报上次进程的死亡原因（Android 11+）。
     *
     * 覆盖 Java 处理器抓不到的三类无痕退出：
     *   CRASH_NATIVE —— 硬解/硬编等 native 层 SIGSEGV（Android 12+ 还能拿到 tombstone 全文）
     *   ANR          —— 主线程卡死被系统杀掉
     *   LOW_MEMORY   —— 内存不足被回收
     * 只记录最近 10 分钟内发生的；文件名带原因与时间戳，重复启动会覆盖同一份。
     */
    private fun reportPreviousExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val exits = runCatching { am.getHistoricalProcessExitReports() }.getOrNull() ?: return
        if (exits.isEmpty()) return
        val now = System.currentTimeMillis()
        val dir = File(getExternalFilesDir(null), "crashes")
        dir.mkdirs()
        for (e in exits) {
            if (now - e.timestamp > 10 * 60 * 1000L) continue
            val reason = reasonName(e.reason)
            val sb = StringBuilder()
            sb.append("time: ").append(FMT.format(Date(e.timestamp))).append('\n')
            sb.append("reason: ").append(reason).append(" (").append(e.reason).append(")\n")
            sb.append("status: ").append(e.status).append('\n')
            sb.append("importance: ").append(e.importance).append('\n')
            sb.append("process: ").append(e.processName ?: "").append('\n')
            sb.append("description: ").append(e.description ?: "").append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    e.traceInputStream?.use { input ->
                        sb.append("\n--- trace ---\n")
                        sb.append(input.readBytes().toString(Charsets.UTF_8))
                    }
                }
            }
            val f = File(dir, "exit-$reason-${e.timestamp}.log")
            runCatching {
                if (!f.exists()) {
                    f.writeText(sb.toString())
                    QpmLog.e(TAG, "上次进程异常退出：$reason（进程 ${e.processName}），详情见 crashes/${f.name}")
                }
            }
        }
    }

    /** ApplicationExitInfo 原因码 → 可读名（用字面量，避免引用高版本常量）。 */
    private fun reasonName(reason: Int): String = when (reason) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH_JAVA"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INIT_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        else -> "REASON_$reason"
    }

    private companion object {
        private const val TAG = "QpmApp"
        private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
