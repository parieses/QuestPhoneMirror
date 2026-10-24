package com.qpm.mirror

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qpm.protocol.FileChunk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 文件浏览器（接收端）：浏览发送端共享存储，点击目录进入，
 * 点击文件下载——图片/文本直接在应用内预览，其他类型保存到本机
 * Android/data/com.qpm.mirror/files/Download/ 并提示路径。
 *
 * 入口：ReceiverActivity 的「文件」按钮，需要 host/port/pairCode
 * （pairCode 为空串表示走免密直连）。
 */
class FileBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PAIR_CODE = "pairCode"
        private const val TAG = "FileBrowserActivity"
        private const val MAX_PREVIEW_IMAGE = 8L * 1024 * 1024
        private const val MAX_PREVIEW_TEXT = 512L * 1024
        private const val CHUNK = 64 * 1024

        // 下载模式：预览图片 / 预览文本 / 落盘保存
        private const val MODE_NONE = 0
        private const val MODE_IMAGE = 1
        private const val MODE_TEXT = 2
        private const val MODE_SAVE = 3

        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        private val TEXT_EXT = setOf(
            "txt", "md", "json", "log", "xml", "csv", "htm", "html",
            "java", "kt", "kts", "yml", "yaml", "properties", "ini"
        )
    }

    private data class Entry(val name: String, val isDir: Boolean, val size: Long, val mtime: String)

    private lateinit var btnUp: Button
    private lateinit var tvPath: TextView
    private lateinit var lvFiles: ListView
    private lateinit var ivPreview: ImageView
    private lateinit var tvPreview: TextView
    private lateinit var tvFileStatus: TextView

    private var client: MirrorClient? = null
    private var entries: List<Entry> = emptyList()
    private var currentPath: String = "/sdcard"

    // 下载状态
    private var dlPath: String? = null
    private var dlTotal: Long = 0
    private var dlReceived: Long = 0
    private var dlMode: Int = MODE_NONE
    private var dlBuffer: ByteArrayOutputStream? = null
    private var dlStream: FileOutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.file_browser)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        btnUp = findViewById(R.id.btnUp)
        tvPath = findViewById(R.id.tvPath)
        lvFiles = findViewById(R.id.lvFiles)
        ivPreview = findViewById(R.id.ivPreview)
        tvPreview = findViewById(R.id.tvPreview)
        tvFileStatus = findViewById(R.id.tvFileStatus)

        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, CastService.TCP_PORT)
        val pairCode = intent.getStringExtra(EXTRA_PAIR_CODE) ?: ""
        if (host.isNullOrEmpty()) {
            toast("缺少发送端地址")
            finish()
            return
        }

        btnUp.setOnClickListener { navigateToParent() }

        lvFiles.setOnItemClickListener { _, _, position, _ ->
            val e = entries.getOrNull(position) ?: return@setOnItemClickListener
            if (e.isDir) {
                currentPath = joinPath(currentPath, e.name)
                requestList()
            } else {
                startDownload(joinPath(currentPath, e.name), e.size)
            }
        }

        client = MirrorClient(
            decoder = null,
            onScreenInfo = { _, _, _ -> },
            onStatus = { s -> runOnUiThread { tvFileStatus.text = s } },
            onDisconnected = { runOnUiThread { tvFileStatus.text = "连接已断开，返回重进" } },
            onPairResult = { ok -> runOnUiThread {
                if (ok) requestList() else tvFileStatus.text = "配对失败，返回重进重试"
            } },
            onFileList = { lines -> runOnUiThread { onFileList(lines) } },
            onFileData = { chunk -> runOnUiThread { onFileData(chunk) } },
            onFileError = { msg -> runOnUiThread { onFileError(msg) } },
            onRejected = { reason -> runOnUiThread { tvFileStatus.text = "发送端拒绝了连接：$reason" } },
            helloName = Build.MODEL ?: "接收端"
        ).also { it.connect(host, port, pairCode) }
    }

    // ---------- 目录浏览 ----------

    private fun requestList() {
        tvPath.text = currentPath
        tvFileStatus.text = "加载中…"
        client?.fileList(currentPath)
    }

    private fun onFileList(lines: List<String>) {
        val parsed = lines.mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 4) null
            else Entry(p[0], p[1] == "1", p[2].toLongOrNull() ?: 0L, p[3])
        }
        val withParent = if (parentPath() != null) {
            listOf(Entry("..", true, 0L, "")) + parsed
        } else parsed
        entries = withParent
        val labels = withParent.map { e ->
            if (e.isDir) "📁 ${e.name}"
            else "${e.name}  ·  ${fmtSize(e.size)}  ·  ${e.mtime}"
        }
        lvFiles.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        tvFileStatus.text = "${parsed.size} 项"
    }

    private fun onFileError(msg: String) {
        tvFileStatus.text = msg
        resetDownload()
    }

    private fun navigateToParent() {
        val p = parentPath() ?: return
        currentPath = p
        requestList()
    }

    private fun parentPath(): String? {
        if (currentPath == "/sdcard" || currentPath == "/storage/emulated/0") return null
        val f = File(currentPath)
        val parent = f.parent ?: return null
        // 不越过共享存储根
        if (parent == "/" || parent == "/storage" || parent == "/storage/emulated") return "/sdcard"
        return parent
    }

    private fun joinPath(base: String, name: String): String =
        if (base.endsWith("/")) "$base$name" else "$base/$name"

    // ---------- 下载 / 预览 ----------

    private fun startDownload(path: String, sizeHint: Long) {
        resetDownload()
        dlPath = path
        val ext = path.substringAfterLast('.', "").lowercase()
        dlMode = when {
            ext in IMAGE_EXT && sizeHint <= MAX_PREVIEW_IMAGE -> MODE_IMAGE
            ext in TEXT_EXT && sizeHint <= MAX_PREVIEW_TEXT -> MODE_TEXT
            else -> MODE_SAVE
        }
        if (dlMode == MODE_SAVE) {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: run {
                toast("无法访问本机存储"); return
            }
            val out = File(dir, File(path).name)
            dlStream = runCatching { FileOutputStream(out) }.getOrNull()
            if (dlStream == null) {
                toast("无法创建本地文件"); return
            }
            tvFileStatus.text = "下载并保存到 ${out.absolutePath}"
        } else {
            dlBuffer = ByteArrayOutputStream()
            tvFileStatus.text = "下载中… 0%"
        }
        client?.fileGet(path)
    }

    private fun onFileData(chunk: FileChunk) {
        if (chunk.offset == 0L) {
            dlTotal = chunk.total
            dlReceived = 0
        }
        dlReceived += chunk.chunk.size
        runCatching {
            when (dlMode) {
                MODE_SAVE -> dlStream?.write(chunk.chunk)
                else -> dlBuffer?.write(chunk.chunk)
            }
        }.onFailure { Log.e(TAG, "写入失败: ${it.message}") }
        if (dlTotal > 0 && dlMode != MODE_SAVE) {
            tvFileStatus.text = "下载中… ${dlReceived * 100 / dlTotal}%"
        }
        if (chunk.eof) finishDownload()
    }

    private fun finishDownload() {
        val path = dlPath ?: ""
        when (dlMode) {
            MODE_IMAGE -> {
                val bytes = dlBuffer?.toByteArray() ?: ByteArray(0)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    lvFiles.visibility = View.GONE
                    ivPreview.visibility = View.VISIBLE
                    ivPreview.setImageBitmap(bmp)
                    tvFileStatus.text = "图片预览：${File(path).name}（返回键继续浏览）"
                } else {
                    tvFileStatus.text = "图片解码失败"
                }
            }
            MODE_TEXT -> {
                lvFiles.visibility = View.GONE
                tvPreview.visibility = View.VISIBLE
                tvPreview.text = String(dlBuffer?.toByteArray() ?: ByteArray(0), Charsets.UTF_8)
                tvFileStatus.text = "文本预览：${File(path).name}（返回键继续浏览）"
            }
            MODE_SAVE -> {
                runCatching { dlStream?.flush() }
                val out = File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return,
                    File(path).name
                )
                toast("已保存：${out.absolutePath}")
                tvFileStatus.text = "已保存到 ${out.absolutePath}"
            }
        }
        resetDownload()
    }

    private fun resetDownload() {
        runCatching { dlStream?.close() }
        dlStream = null
        dlBuffer = null
        dlPath = null
        dlTotal = 0
        dlReceived = 0
        dlMode = MODE_NONE
    }

    override fun onBackPressed() {
        // 预览态：返回键先回到列表
        if (ivPreview.visibility == View.VISIBLE || tvPreview.visibility == View.VISIBLE) {
            ivPreview.visibility = View.GONE
            tvPreview.visibility = View.GONE
            lvFiles.visibility = View.VISIBLE
            tvFileStatus.text = "${entries.size} 项"
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        client?.stop()
        resetDownload()
        super.onDestroy()
    }

    private fun fmtSize(size: Long): String = when {
        size <= 0 -> "0 B"
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
        size < 1024L * 1024 * 1024 -> "%.1f MB".format(size / 1024.0 / 1024.0)
        else -> "%.2f GB".format(size / 1024.0 / 1024.0 / 1024.0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
