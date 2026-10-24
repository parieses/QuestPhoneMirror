package com.qpm.mirror

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.qpm.mirror.BuildConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * 入口：选择本机角色。同一个 APK 装在两台设备上（手机/头显/平板均可），各选一边。
 * 同时承担「检查更新」职责：查询 GitHub Releases 最新版本，弹窗引导下载并拉起系统安装。
 */
class RoleActivity : AppCompatActivity(), AppUpdater.Listener {

    private lateinit var cardUpdate: View
    private lateinit var tvVersion: TextView
    private lateinit var tvUpdateBadge: TextView
    private lateinit var updater: AppUpdater

    private val checkExecutor = Executors.newSingleThreadExecutor()
    private val checkPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_CHECK, Context.MODE_PRIVATE)
    }

    private var checking = false
    private var activityResumed = false
    private var updateDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var tvProgress: TextView? = null

    /** 点了「下载并安装」但「允许安装未知应用」尚未放行的 Release，授权回来后继续。 */
    private var pendingDownload: UpdateChecker.Release? = null

    /** 从系统「安装未知应用」设置页返回的结果。 */
    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (updater.hasInstallPermission()) {
                startPendingDownload()
            } else {
                pendingDownload = null
                toast("未允许安装未知应用，暂时无法下载安装更新")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role)

        cardUpdate = findViewById(R.id.cardUpdate)
        tvVersion = findViewById(R.id.tvVersion)
        tvUpdateBadge = findViewById(R.id.tvUpdateBadge)
        updater = AppUpdater(this).also { it.listener = this }

        tvVersion.text = "当前版本 ${BuildConfig.VERSION_NAME} · 点此检查 GitHub 更新"

        findViewById<View>(R.id.cardSender).setOnClickListener {
            startActivity(Intent(this, SenderActivity::class.java))
        }
        findViewById<View>(R.id.cardReceiver).setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
        cardUpdate.setOnClickListener { checkUpdate(manual = true) }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        // 先补下载中的状态（可能下载完离开过页面），再做节流后的自动检查
        updater.onResume()
        maybeAutoCheck()
    }

    override fun onPause() {
        super.onPause()
        activityResumed = false
    }

    override fun onDestroy() {
        updater.onDestroy()
        checkExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---------- 检查更新 ----------

    /** GitHub 未认证接口限流 60 次/小时/IP，自动检查压到 12 小时一次；手动点不受限。 */
    private fun maybeAutoCheck() {
        val last = checkPrefs.getLong(KEY_LAST_AUTO_CHECK, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
        checkPrefs.edit().putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis()).apply()
        checkUpdate(manual = false)
    }

    private fun checkUpdate(manual: Boolean) {
        if (checking) return
        checking = true
        if (manual) toast("正在检查 GitHub 更新…")
        val current = BuildConfig.VERSION_NAME
        checkExecutor.execute {
            val result = try {
                UpdateChecker.check(current)
            } catch (e: Exception) {
                UpdateChecker.Result.Failed("检查更新时出错：${e.message}")
            }
            runOnUiThread {
                checking = false
                when (result) {
                    is UpdateChecker.Result.Failed ->
                        if (manual) toast(result.message)

                    is UpdateChecker.Result.UpToDate -> {
                        tvUpdateBadge.visibility = View.GONE
                        if (manual) toast("已是最新版本 $current")
                    }

                    is UpdateChecker.Result.Available -> {
                        tvUpdateBadge.visibility = View.VISIBLE
                        // 自动检查尊重「不再提示此版本」；手动检查永远弹窗
                        val skipped = checkPrefs.getString(KEY_SKIPPED_VERSION, "") ?: ""
                        if (manual || result.release.versionName != skipped) {
                            showUpdateDialog(result.release, auto = !manual)
                        }
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(release: UpdateChecker.Release, auto: Boolean) {
        updateDialog?.dismiss()
        val view = layoutInflater.inflate(R.layout.dialog_update_available, null)
        view.findViewById<TextView>(R.id.tvNewVersion).text = release.versionName
        view.findViewById<TextView>(R.id.tvNotes).text = release.notes
        view.findViewById<Button>(R.id.btnDownload).setOnClickListener { onDownloadClicked(release) }
        view.findViewById<Button>(R.id.btnLater).apply {
            // 自动弹窗时可「不再提示此版本」（落 skipped_version 免打扰）；手动检查只能「稍后再说」
            text = if (auto) "不再提示此版本" else "稍后再说"
            setOnClickListener {
                if (auto) {
                    checkPrefs.edit().putString(KEY_SKIPPED_VERSION, release.versionName).apply()
                }
                updateDialog?.dismiss()
            }
        }
        view.findViewById<TextView>(R.id.tvGithub).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
                .onFailure { toast("无法打开浏览器，请手动访问 GitHub Releases") }
        }
        updateDialog = AlertDialog.Builder(this, R.style.Theme_QPM_Dialog)
            .setView(view)
            .setOnDismissListener { updateDialog = null }
            .show()
    }

    private fun onDownloadClicked(release: UpdateChecker.Release) {
        if (updater.isDownloading()) {
            toast("更新正在下载中…")
            return
        }
        pendingDownload = release
        // 先解决「允许安装未知应用」再下载：等下载完再跳设置，体验是断裂的
        if (!updater.hasInstallPermission()) {
            toast("请允许「QPM 手机镜像」安装未知应用")
            installPermissionLauncher.launch(updater.installPermissionIntent())
            return
        }
        startPendingDownload()
    }

    private fun startPendingDownload() {
        val release = pendingDownload ?: return
        pendingDownload = null
        // 传入候选链（Gitee 镜像 / 公共代理 / GitHub 直连），由 AppUpdater 探测选源
        if (!updater.startDownload(release.downloadUrls, release.versionName)) {
            toast("更新已在下载中")
            return
        }
        updateDialog?.dismiss()
        showProgressDialog(release.versionName)
    }

    private fun showProgressDialog(versionName: String) {
        val view = layoutInflater.inflate(R.layout.dialog_update_progress, null)
        progressBar = view.findViewById(R.id.progressBar)
        tvProgress = view.findViewById(R.id.tvProgress)
        view.findViewById<TextView>(R.id.tvProgressPhase).text = "$versionName · 正在选择最快下载源…"
        view.findViewById<TextView>(R.id.btnBackground).setOnClickListener { progressDialog?.dismiss() }
        progressDialog = AlertDialog.Builder(this, R.style.Theme_QPM_Dialog)
            .setView(view)
            .setOnDismissListener {
                progressDialog = null
                progressBar = null
                tvProgress = null
            }
            .show()
    }

    // ---------- AppUpdater.Listener ----------

    override fun onProgress(downloaded: Long, total: Long) {
        val text = if (total > 0) {
            val pct = (downloaded * 100 / total).toInt()
            "已下载 $pct%  ${fmtSize(downloaded)} / ${fmtSize(total)}"
        } else {
            "已下载 ${fmtSize(downloaded)}"
        }
        tvProgress?.text = text
        if (total > 0) progressBar?.progress = (downloaded * 100 / total).toInt()
    }

    override fun onReadyToInstall(file: File, versionName: String) {
        progressDialog?.dismiss()
        updateDialog?.dismiss()
        if (!activityResumed) {
            // 用户选了「后台下载」并离开了入口页：不抢跳安装器，回来时再提示
            toast("更新包 $versionName 已下载完成，回到本页即可安装")
            return
        }
        if (updater.hasInstallPermission()) {
            updater.promptInstall(file)
        } else {
            toast("请允许「QPM 手机镜像」安装未知应用后安装 $versionName")
            startActivity(updater.installPermissionIntent())
            // 从设置页返回时 onResume -> updater.onResume() 会补发安装提示
        }
    }

    override fun onDownloadFailed(message: String) {
        progressDialog?.dismiss()
        toast(message)
    }

    private fun fmtSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format("%.1f MB", bytes / 1048576.0)
        else String.format("%.0f KB", bytes / 1024.0)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private companion object {
        private const val PREFS_CHECK = "qpm_update_check"
        private const val KEY_LAST_AUTO_CHECK = "last_auto_check"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
        private const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
}
