package com.qpm.mirror

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.qpm.mirror.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 更新下载与安装：DownloadManager 后台下载 APK（应用外部 files/Download 目录，
 * 无需存储权限），完成后经 FileProvider 分享给系统安装器。
 *
 * 设计取舍：
 * - 通知用 VISIBILITY_VISIBLE_NOTIFY_COMPLETED：曾用 VISIBILITY_HIDDEN(2)，但
 *   DownloadManager 服务端只接受 0/1，传 2 会抛 "invalid value for visibility 2"
 *   导致 enqueue 直接失败（这正是「无法启动下载任务」的根因）。系统通知点击后走
 *   系统安装器，与应用内安装提示是两条独立路径，都能装，不再追求「只弹一次」。
 * - 多源下载：候选链（Gitee 镜像 / 公共代理 / GitHub 直连）先在子线程做 HEAD 探测，
 *   选第一个可用的再入队——公共代理随时可能挂，链式回退比写死单源稳。
 * - downloadId / 待安装版本号落盘：用户下载途中离开页面，回来 onResume 能补提示安装；
 *   安装成功后版本号不再「更新」，自然不再打扰。
 * - 完成事件双通道：广播接收 + 800ms 轮询兜底（页面重建后旧广播接收器已反注册，
 *   靠轮询把终态补发给新的 Listener）。
 */
class AppUpdater(private val activity: AppCompatActivity) {

    interface Listener {
        /** @param downloaded 已下载字节；@param total 总字节，<0 表示服务端未给出 */
        fun onProgress(downloaded: Long, total: Long)

        /** 下载完成，[file] 可安装；[versionName] 为包对应的远端版本号。 */
        fun onReadyToInstall(file: File, versionName: String)

        /** 下载失败，[message] 可直接展示。 */
        fun onDownloadFailed(message: String)
    }

    var listener: Listener? = null

    private val prefs: SharedPreferences =
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var downloadId: Long = prefs.getLong(KEY_DOWNLOAD_ID, -1L)

    /** 待安装包对应的远端版本号，用于「装没装过」判断。 */
    private var pendingVersion: String = prefs.getString(KEY_PENDING_VERSION, "") ?: ""

    private var receiverRegistered = false

    /** 候选源 HEAD 探测中（防止重复点「下载并安装」发起多轮探测）。 */
    private var probing = false

    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val probeHandler = Handler(Looper.getMainLooper())

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            val id = downloadId
            if (id < 0) return
            when (val status = queryStatus(id)) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            listener?.onProgress(downloaded, total)
                        }
                    }
                    progressHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
                else -> handleTerminalStatus(id, status)
            }
        }
    }

    /** 下载完成广播（系统发出，NOT_EXPORTED 即可收到）。 */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id != downloadId) return
            progressHandler.removeCallbacks(progressTick)
            handleTerminalStatus(id, queryStatus(id))
        }
    }

    /** 广播与轮询共用的终态分发，两条通道都只触发一次有效动作。 */
    private fun handleTerminalStatus(id: Long, status: Int) {
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                val file = apkFile()
                if (file.exists() && file.length() > 0 &&
                    UpdateChecker.isNewer(pendingVersion, BuildConfig.VERSION_NAME)
                ) {
                    listener?.onReadyToInstall(file, pendingVersion)
                } else {
                    // 已安装过或包已丢失：清理残留，不再打扰
                    clearDownloadState()
                }
            }
            DownloadManager.STATUS_FAILED -> {
                clearDownloadState()
                listener?.onDownloadFailed("下载失败（${failReason(id)}），请检查网络后重试")
            }
            else -> {
                // 记录被系统清掉（-1）等异常：包还在且版本更新则照常提示，否则清理
                val file = apkFile()
                if (file.exists() && file.length() > 0 &&
                    UpdateChecker.isNewer(pendingVersion, BuildConfig.VERSION_NAME)
                ) {
                    listener?.onReadyToInstall(file, pendingVersion)
                } else {
                    clearDownloadState()
                }
            }
        }
    }

    private fun queryStatus(id: Long): Int {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) return -1
            return it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }

    private fun failReason(id: Long): String {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) return "未知错误"
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (reason) {
                DownloadManager.ERROR_CANNOT_RESUME -> "连接中断且无法续传"
                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "存储不可用"
                DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
                DownloadManager.ERROR_FILE_ERROR -> "本地文件写入错误"
                DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络数据错误"
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载链接重定向过多"
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器返回异常"
                DownloadManager.ERROR_UNKNOWN -> "未知错误"
                else -> "错误码 $reason"
            }
        }
    }

    /** APK 落地路径：应用外部私有 Download 目录，file_paths.xml 已声明可分享。 */
    private fun apkFile(): File {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        return File(dir, UpdateChecker.APK_FILE_NAME)
    }

    /**
     * 开始下载。调用方需先确保安装权限已放行（[hasInstallPermission]）。
     *
     * 候选链按顺序做 HEAD 探测（Gitee 镜像 / 公共代理 / GitHub 直连），选第一个可用的入队；
     * 全部不可达时用最后一条（GitHub 官方直链）硬试，让 DownloadManager 报明确的错。
     *
     * @return false 表示已有下载在进行或请求被忽略
     */
    fun startDownload(apkUrls: List<String>, versionName: String): Boolean {
        if (isDownloading() || probing) return false
        if (apkUrls.isEmpty()) {
            listener?.onDownloadFailed("没有可用的下载地址，请前往 GitHub Releases 手动下载")
            return false
        }
        probing = true
        probeExecutor.execute {
            val chosen = apkUrls.firstOrNull { probeUrl(it) } ?: apkUrls.last()
            probeHandler.post {
                probing = false
                if (activity.isDestroyed) return@post
                enqueueDownload(chosen, versionName)
            }
        }
        return true
    }

    /** HEAD 探测单个候选源：2xx/3xx 可用；405 表示服务器活着但不支持 HEAD，交给 DownloadManager 判断。 */
    private fun probeUrl(url: String): Boolean = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "QPM-Android")
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399 || code == 405
    } catch (e: Exception) {
        Log.d(TAG, "probe failed for $url: ${e.message}")
        false
    }

    /** 探测完成后真正入队（原 startDownload 主体）。 */
    private fun enqueueDownload(apkUrl: String, versionName: String) {
        clearDownloadState()
        // 清掉上一次的残留包，防止 FileProvider 把旧包递给安装器
        apkFile().delete()

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = try {
            DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("QPM 手机镜像 $versionName")
                setDescription("正在下载更新…")
                setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    UpdateChecker.APK_FILE_NAME
                )
                // 注意：不能用 VISIBILITY_HIDDEN（值 2）——DownloadManager 服务端
                // 只接受 0/1，传 2 会在 enqueue 时抛 SecurityException:
                // "invalid value for visibility 2"（部分系统版本直接静默失败）。
                // 系统通知点击后会打开系统安装器，与应用内安装提示属两条独立路径，
                // 用户走哪条都能装，不再是「重复弹两次」的问题。
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "request build failed: ${e.message}")
            listener?.onDownloadFailed("下载链接无效，请前往 GitHub Releases 手动下载")
            return
        }

        val id = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            Log.w(TAG, "enqueue error: ${e.message}")
            listener?.onDownloadFailed("无法启动下载任务：${e.message}")
            return
        }
        if (id <= 0) {
            listener?.onDownloadFailed("无法启动下载任务，请前往 GitHub Releases 手动下载")
            return
        }
        downloadId = id
        pendingVersion = versionName
        prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_PENDING_VERSION, versionName)
            .apply()
        registerReceiverSafely()
        progressHandler.removeCallbacks(progressTick)
        progressHandler.post(progressTick)
    }

    private fun registerReceiverSafely() {
        if (receiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                activity,
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed: ${e.message}")
        }
    }

    /** 页面回到前台：恢复进度轮询 / 补提示安装（下载途中离开页面再回来）。 */
    fun onResume() {
        val id = downloadId
        if (id < 0) return
        val status = queryStatus(id)
        when (status) {
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                progressHandler.removeCallbacks(progressTick)
                progressHandler.post(progressTick)
            }
            else -> handleTerminalStatus(id, status)
        }
    }

    fun onDestroy() {
        progressHandler.removeCallbacks(progressTick)
        probeExecutor.shutdownNow()
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(downloadReceiver)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "receiver not registered: ${e.message}")
            }
            receiverRegistered = false
        }
    }

    /** 拉起系统安装器。权限不足时先跳系统设置引导用户放行，包留着下次回来再提示。 */
    fun promptInstall(file: File) {
        if (!hasInstallPermission()) {
            toast("请先允许「QPM 手机镜像」安装未知应用")
            activity.startActivity(installPermissionIntent())
            return
        }
        val uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "fileprovider uri failed: ${e.message}")
            toast("安装包路径异常，请重新下载")
            return
        }
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    fun hasInstallPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )

    fun isDownloading(): Boolean =
        downloadId >= 0 && queryStatus(downloadId) == DownloadManager.STATUS_RUNNING

    private fun clearDownloadState() {
        downloadId = -1L
        pendingVersion = ""
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_PENDING_VERSION).apply()
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "AppUpdater"
        private const val PREFS = "qpm_update"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_PENDING_VERSION = "pending_version"
        private const val POLL_INTERVAL_MS = 800L
    }
}
