package com.qpm.mirror

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃/异常退出记录的读写入口。
 *
 * 文件有两类，都落在外部私有目录 crashes/：
 *  - crash-<ts>.log        Java 未捕获异常（全局 handler 写出，含完整堆栈）
 *  - exit-<REASON>-<ts>.log ApplicationExitInfo（native 崩溃 / ANR / 低内存 / 信号终止）
 *
 * 「正常退出」（滑动清理、用户主动停止）不产生 exit 文件——否则每次正常关闭都会被当成崩溃，
 * 用户就再也不会认真看这个提示了。
 */
object CrashStore {

    private const val DIR_NAME = "crashes"

    /**
     * 只有这几种退出原因才算「崩溃」，用白名单而不是黑名单。
     *
     * 黑名单漏一个就误报一次——实测装新包会留下 REASON_16（PACKAGE_UPDATED），
     * 被当成崩溃弹到首屏，用户下次就不信这个提示了。
     */
    private val ABNORMAL_REASONS = setOf(
        "SIGNALED",          // 被信号杀死（SIGSEGV / SIGABRT，多为 native 崩溃）
        "LOW_MEMORY",        // 内存不足被系统回收
        "CRASH_JAVA",        // Java 未捕获异常
        "CRASH_NATIVE",      // native 崩溃（硬编解码、WebRTC so）
        "ANR",               // 主线程卡死
        "INIT_FAILURE",      // 进程初始化失败
        "EXCESSIVE_RESOURCE",// 资源超限被杀
        "DEPENDENCY_DIED"    // 依赖进程死亡牵连
    )

    private val TIME_FMT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun dir(context: Context): File = File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }

    /** 是否值得作为崩溃提示（写入与展示共用同一判定，避免两边不一致）。 */
    fun isAbnormal(fileName: String): Boolean {
        if (fileName.startsWith("crash-")) return true
        if (!fileName.startsWith("exit-")) return false
        return reasonOf(fileName) in ABNORMAL_REASONS
    }

    /** exit-CRASH_NATIVE-1732...log → CRASH_NATIVE；解析不出时返回空串。 */
    fun reasonOf(fileName: String): String {
        val body = fileName.removePrefix("exit-").removeSuffix(".log")
        // 时间戳是纯数字，取最后一个 '-' 之前的部分作为原因名
        val idx = body.lastIndexOf('-')
        return if (idx > 0) body.substring(0, idx) else ""
    }

    /** 全部异常记录，最新在前。 */
    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") && isAbnormal(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun latest(context: Context): File? = list(context).firstOrNull()

    /** 列表展示用的一行摘要：原因 + 时间 + 大小。 */
    fun summary(f: File): String {
        val head = if (f.name.startsWith("crash-")) "JAVA_CRASH" else reasonOf(f.name)
        val time = TIME_FMT.format(Date(f.lastModified()))
        val kb = (f.length() / 1024).coerceAtLeast(1)
        return "$head · $time · ${kb}KB"
    }

    fun read(f: File): String = runCatching { f.readText() }.getOrDefault("")

    /** 删除单条记录（诊断页清理用）。 */
    fun delete(f: File) {
        runCatching { f.delete() }
    }

    /**
     * 清空 crashes/ 目录下所有 .log，返回实际删除条数。
     *
     * 不止删「崩溃记录」清单里那些：旧版本按黑名单落盘时留下的 exit-REASON_16
     * （装包更新）这类文件不在清单里、用户看不见也删不掉，会一直堆在目录里。
     */
    fun deleteAll(context: Context): Int {
        var n = 0
        dir(context).listFiles()?.forEach {
            if (it.isFile && it.name.endsWith(".log") && runCatching { it.delete() }.getOrDefault(false)) n++
        }
        return n
    }
}
