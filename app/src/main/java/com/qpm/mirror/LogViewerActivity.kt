package com.qpm.mirror

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志页：查看/分享/清空应用内日志与崩溃记录。
 * 用户设备上无法使用 adb logcat（Quest 更不行），排「连接成功后闪退」类问题全靠它。
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var tvInfo: TextView
    private lateinit var btnShare: Button
    private lateinit var btnCopy: Button
    private lateinit var btnCrashes: Button
    private lateinit var btnDelete: Button
    private lateinit var btnClear: Button

    /** 非空表示当前正在看某条崩溃记录（而不是当天的运行日志）。 */
    private var crashFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        tvLog = findViewById(R.id.tvLog)
        tvInfo = findViewById(R.id.tvInfo)
        btnShare = findViewById(R.id.btnShare)
        btnCopy = findViewById(R.id.btnCopy)
        btnCrashes = findViewById(R.id.btnCrashes)
        btnDelete = findViewById(R.id.btnDelete)
        btnClear = findViewById(R.id.btnClear)

        refresh()

        btnShare.setOnClickListener { shareCurrent() }
        btnCopy.setOnClickListener { copyAll() }
        btnCrashes.setOnClickListener {
            if (crashFile != null) backToLog() else showCrashes()
        }
        btnDelete.setOnClickListener { confirmDeleteCurrentCrash() }
        btnClear.setOnClickListener {
            if (crashFile != null) {
                exportCurrentCrash()
                return@setOnClickListener
            }
            AlertDialog.Builder(this, R.style.Theme_QPM_Dialog)
                .setTitle(getString(R.string.log_clear_title))
                .setMessage(getString(R.string.log_clear_msg))
                .setPositiveButton(getString(R.string.log_clear_ok)) { _, _ ->
                    QpmLog.clearToday()
                    refresh()
                    toast(getString(R.string.log_cleared))
                }
                .setNegativeButton(getString(R.string.log_cancel), null)
                .show()
        }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }

        // 从入口页的「上次异常退出」提示直接进来：不用再点一次，打开就是那条崩溃
        if (intent.getBooleanExtra(EXTRA_OPEN_CRASH, false)) {
            CrashStore.latest(this)?.let { showCrash(it) }
        }
    }

    private fun refresh() {
        val lines = QpmLog.tail(TAIL_LINES)
        tvLog.text = if (lines.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            lines.joinToString("\n")
        }
        val kb = QpmLog.totalBytes() / 1024
        tvInfo.text = getString(R.string.log_info, lines.size, kb)
        scrollToBottom()
    }

    /**
     * 在页内直接阅读一条崩溃记录。
     *
     * 旧实现点崩溃列表只能「分享」——Quest 上通常没有可接收分享的应用，点了等于没反应，
     * 用户就永远看不到堆栈。现在改成正文直接铺在日志区，配合「复制」就能粘出来。
     */
    private fun showCrash(f: File) {
        crashFile = f
        val text = CrashStore.read(f)
        tvLog.text = if (text.isBlank()) getString(R.string.log_crash_empty) else text
        tvInfo.text = getString(R.string.log_crash_info, CrashStore.summary(f))
        btnCrashes.setText(R.string.log_back_to_log)
        btnShare.setText(R.string.log_share_crash)
        btnClear.setText(R.string.log_export)
        btnDelete.visibility = View.VISIBLE
        scrollToBottom()
    }

    private fun backToLog() {
        crashFile = null
        btnCrashes.setText(R.string.log_crashes)
        btnShare.setText(R.string.log_share)
        btnClear.setText(R.string.log_clear)
        btnDelete.visibility = View.GONE
        refresh()
    }

    /**
     * 删除当前正在看的那条崩溃记录。
     *
     * 之前在页内只能看/导出，记录会一直堆在 crashes/ 目录里，首屏「上次异常退出」
     * 提示也只能等它自然过期。删完直接退回运行日志，不再回到列表（列表可能已空）。
     */
    private fun confirmDeleteCurrentCrash() {
        val f = crashFile ?: return
        AlertDialog.Builder(this, R.style.Theme_QPM_Dialog)
            .setTitle(getString(R.string.log_delete_title))
            .setMessage(getString(R.string.log_delete_msg))
            .setPositiveButton(getString(R.string.log_delete_ok)) { _, _ ->
                CrashStore.delete(f)
                backToLog()
                toast(getString(R.string.log_deleted))
            }
            .setNegativeButton(getString(R.string.log_cancel), null)
            .show()
    }

    /**
     * 把当前这条崩溃写成 Download/qpm-crash-<ts>.txt。
     *
     * 头显上「复制到剪贴板」常常取不出来，分享面板也可能没有目标应用；
     * 落一个文件到 Download 就能用文件管理器或数据线直接取走。
     */
    private fun exportCurrentCrash() {
        val f = crashFile ?: return
        val text = CrashStore.read(f)
        if (text.isBlank()) {
            toast(getString(R.string.log_crash_empty))
            return
        }
        val name = "qpm-crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        val where = runCatching { saveToDownloads(name, text) }.getOrNull()
        toast(
            if (where != null) getString(R.string.log_export_ok, where)
            else getString(R.string.log_export_fail)
        )
    }

    private fun saveToDownloads(name: String, text: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
            return "Download/$name"
        }
        // Android 9 及以下没有分区存储：退到应用自己的 Download 目录，路径对用户可见
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        dir.mkdirs()
        val out = File(dir, name)
        out.writeText(text)
        return out.absolutePath
    }

    private fun scrollToBottom() {
        tvLog.post {
            findViewById<android.widget.ScrollView>(R.id.scrollLog)
                .fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    /** 一键复制屏幕上显示的内容（所见即所得），便于直接粘贴到聊天工具发出来。 */
    private fun copyAll() {
        val text = tvLog.text?.toString()
        if (text.isNullOrEmpty()) {
            toast(getString(R.string.log_share_empty))
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            toast(getString(R.string.log_copy_fail))
            return
        }
        val ok = runCatching {
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_title), text))
        }.isSuccess
        toast(getString(if (ok) R.string.log_copied else R.string.log_copy_fail))
    }

    private fun shareCurrent() {
        val f = crashFile ?: QpmLog.currentFile()
        if (f == null || f.length() == 0L) {
            toast(getString(R.string.log_share_empty))
            return
        }
        shareFile(f, "text/plain")
    }

    private fun showCrashes() {
        val files = CrashStore.list(this)
        if (files.isEmpty()) {
            toast(getString(R.string.log_crash_none))
            return
        }
        val items = files.map { CrashStore.summary(it) }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_QPM_Dialog)
            .setTitle(getString(R.string.log_crash_title, files.size))
            .setItems(items) { _, which -> showCrash(files[which]) }
            // 列表里支持一键清空：堆了几十上百条时逐条删不现实
            .setNeutralButton(getString(R.string.log_crash_clear_all)) { _, _ ->
                confirmDeleteAllCrashes(files.size)
            }
            .setNegativeButton(getString(R.string.log_cancel), null)
            .show()
    }

    private fun confirmDeleteAllCrashes(count: Int) {
        AlertDialog.Builder(this, R.style.Theme_QPM_Dialog)
            .setTitle(getString(R.string.log_crash_clear_all_title))
            .setMessage(getString(R.string.log_crash_clear_all_msg, count))
            .setPositiveButton(getString(R.string.log_delete_ok)) { _, _ ->
                val n = CrashStore.deleteAll(this)
                if (crashFile != null) backToLog() else refresh()
                toast(getString(R.string.log_crash_cleared, n))
            }
            .setNegativeButton(getString(R.string.log_cancel), null)
            .show()
    }

    private fun shareFile(f: File, mime: String) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "QPM 诊断日志 ${f.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.log_share_title))) }
            .onFailure { toast(getString(R.string.log_share_fail)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAIL_LINES = 1500

        /** 入口页带此标记进入时，直接打开最近一条崩溃记录。 */
        const val EXTRA_OPEN_CRASH = "open_crash"
    }
}
