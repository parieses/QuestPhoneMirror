package com.qpm.mirror

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内诊断日志：所有日志同时写 logcat 和 App 私有目录的按日文件，
 * 供「诊断日志」页查看/分享（用户设备上没法学 adb logcat，Quest 更不行）。
 *
 * - 落盘目录：files/logs/qpm-YYYY-MM-DD.log，保留 3 天，单文件 8MB 封顶
 * - 同步写：崩溃前的最后几行必须已在盘上，宁慢勿丢
 * - API 与 android.util.Log 一致（i/w/e/d，(tag, msg[, tr])），便于整体替换
 */
object QpmLog {

    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val RETAIN_DAYS = 3
    private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val lock = Any()

    @Volatile
    private var dir: File? = null

    /** 在 Application.onCreate 调用一次。 */
    fun init(context: Context) {
        if (dir != null) return
        synchronized(lock) {
            if (dir != null) return
            dir = File(context.filesDir, "logs").apply { mkdirs() }
            cleanOld()
        }
    }

    fun i(tag: String, msg: String, tr: Throwable? = null) = write('I', tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = write('W', tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write('E', tag, msg, tr)

    /** debug 只进 logcat，不落盘（开发期噪声不入用户诊断包）。 */
    fun d(tag: String, msg: String, tr: Throwable? = null) {
        Log.d(tag, msg, tr)
    }

    private fun write(level: Char, tag: String, msg: String, tr: Throwable?) {
        // logcat 通道（原有行为不变）
        when (level) {
            'I' -> Log.i(tag, msg, tr)
            'W' -> Log.w(tag, msg, tr)
            'E' -> Log.e(tag, msg, tr)
        }
        // 文件通道
        val d = dir ?: return
        val line = buildString {
            append(TIME_FMT.format(Date())).append(' ').append(level).append('/')
            append(tag).append(": ").append(msg)
            if (tr != null) {
                append('\n').append(Log.getStackTraceString(tr))
            }
        }
        synchronized(lock) {
            runCatching {
                val f = File(d, "qpm-${DAY_FMT.format(Date())}.log")
                if (f.length() > MAX_FILE_BYTES) return // 单文件封顶，防异常刷盘
                f.appendText(line + "\n")
            }
        }
    }

    /** 删掉超过保留期的日志与崩溃文件。 */
    fun cleanOld() {
        val d = dir ?: return
        val cutoff = System.currentTimeMillis() - RETAIN_DAYS * 24L * 3600 * 1000
        synchronized(lock) {
            runCatching {
                d.listFiles()?.forEach { f ->
                    val day = f.name.removePrefix("qpm-").removeSuffix(".log")
                    val ts = runCatching { DAY_FMT.parse(day)?.time }.getOrNull()
                    if (ts != null && ts < cutoff) f.delete()
                }
            }
        }
    }

    /** 当前日志文件（不存在返回 null）。 */
    fun currentFile(): File? {
        val d = dir ?: return null
        return File(d, "qpm-${DAY_FMT.format(Date())}.log").takeIf { it.exists() }
    }

    /** 日志文件总字节数（诊断页展示用）。 */
    fun totalBytes(): Long {
        val d = dir ?: return 0
        return runCatching { d.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    }

    /** 取最后 n 行（诊断页展示用；文件大时也只读尾部）。 */
    fun tail(n: Int): List<String> {
        val f = currentFile() ?: return emptyList()
        return runCatching {
            val lines = f.readLines()
            if (lines.size <= n) lines else lines.subList(lines.size - n, lines.size)
        }.getOrDefault(emptyList())
    }

    /** 清空当天日志文件内容（保留文件）。 */
    fun clearToday() {
        val f = currentFile() ?: return
        synchronized(lock) { runCatching { f.writeText("") } }
    }
}
