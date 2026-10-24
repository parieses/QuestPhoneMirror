package com.qpm.mirror

import android.app.Application
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
 * 用途：用户描述的「接收端连接成功后返回主界面」本质是**进程崩溃后重开落到
 * RoleActivity**——没有堆栈就无法区分是 Java 未捕获异常还是 native 崩溃。
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

    private companion object {
        private const val TAG = "QpmApp"
        private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
