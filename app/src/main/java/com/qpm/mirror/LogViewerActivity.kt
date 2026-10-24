package com.qpm.mirror

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * 诊断日志页：查看/分享/清空应用内日志与崩溃记录。
 * 用户设备上无法使用 adb logcat（Quest 更不行），排「连接成功后闪退」类问题全靠它。
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var tvInfo: TextView
    private lateinit var btnShare: Button
    private lateinit var btnCrashes: Button
    private lateinit var btnClear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        tvLog = findViewById(R.id.tvLog)
        tvInfo = findViewById(R.id.tvInfo)
        btnShare = findViewById(R.id.btnShare)
        btnCrashes = findViewById(R.id.btnCrashes)
        btnClear = findViewById(R.id.btnClear)

        refresh()

        btnShare.setOnClickListener { shareCurrent() }
        btnCrashes.setOnClickListener { showCrashes() }
        btnClear.setOnClickListener {
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
        // 滚到底部（最新在最后）
        tvLog.post {
            val scroll = findViewById<android.widget.ScrollView>(R.id.scrollLog)
            scroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun shareCurrent() {
        val f = QpmLog.currentFile()
        if (f == null || f.length() == 0L) {
            toast(getString(R.string.log_share_empty))
            return
        }
        shareFile(f, "text/plain")
    }

    private fun showCrashes() {
        val dir = File(getExternalFilesDir(null), "crashes")
        val files = dir.listFiles()?.filter { it.name.endsWith(".log") }?.sortedByDescending { it.name }
        if (files.isNullOrEmpty()) {
            toast(getString(R.string.log_crash_none))
            return
        }
        val items = files.map { "${it.name}  (${it.length() / 1024}KB)" }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_QPM_Dialog)
            .setTitle(getString(R.string.log_crash_title, files.size))
            .setItems(items) { _, which -> shareFile(files[which], "text/plain") }
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

    private companion object {
        private const val TAIL_LINES = 1500
    }
}
