package com.qpm.mirror

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.random.Random

/** 发送端：申请录屏权限后拉起 CastService 开始编码推流。 */
class SenderActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPairCode: TextView
    private lateinit var tvWarn: TextView
    private lateinit var tvA11yHint: TextView
    private lateinit var pairCard: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var rgQuality: RadioGroup
    private lateinit var swKeepAwake: Switch
    private lateinit var btnFilePerm: Button
    private lateinit var tvReceiverStatus: TextView
    private lateinit var btnScanReceivers: Button
    private lateinit var llReceivers: LinearLayout

    /** 本次投屏使用的配对码，随录屏授权结果一起发给 CastService。 */
    private var pairCode: String = ""

    private val statusListener: (String) -> Unit = { s ->
        runOnUiThread {
            tvStatus.text = s
            refreshPairCodeView()
        }
    }

    /** 连接管理列表变化（连接/断开/HELLO/beacon）时刷新。 */
    private val receiverListener: () -> Unit = { runOnUiThread { refreshReceivers() } }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                toast(getString(R.string.sender_projection_denied))
                return@registerForActivityResult
            }
            pairCode = genPairCode()
            if (swKeepAwake.isChecked && !Settings.System.canWrite(this)) {
                toast(getString(R.string.sender_keepawake_ask))
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            ContextCompat.startForegroundService(
                this,
                Intent(this, CastService::class.java).apply {
                    putExtra(CastService.EXTRA_CODE, result.resultCode)
                    putExtra(CastService.EXTRA_DATA, result.data)
                    putExtra(CastService.EXTRA_PAIR_CODE, pairCode)
                    putExtra(CastService.EXTRA_BITRATE, selectedBitrate())
                    putExtra(CastService.EXTRA_KEEP_AWAKE, swKeepAwake.isChecked)
                }
            )
            // 配对码只在 pairCard 展示；tvStatus 不写码，避免两个真源不一致
            tvStatus.text = getString(R.string.sender_status_casting)
            // 服务是异步拉起的，此刻 currentPairCode 可能还没被赋值，
            // 必须用本地已生成的码直接进「投屏中」态，不能走查询式刷新
            showCastingUi(pairCode)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)
        // 发送端亮屏是投屏前提（MediaProjection 熄屏即停），Activity 期也保持常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tvStatus = findViewById(R.id.tvStatus)
        tvPairCode = findViewById(R.id.tvPairCode)
        tvWarn = findViewById(R.id.tvWarn)
        tvA11yHint = findViewById(R.id.tvA11yHint)
        pairCard = findViewById(R.id.pairCard)
        btnStart = findViewById(R.id.btnStart)
        rgQuality = findViewById(R.id.rgQuality)
        swKeepAwake = findViewById(R.id.swKeepAwake)
        btnFilePerm = findViewById(R.id.btnFilePerm)
        tvReceiverStatus = findViewById(R.id.tvReceiverStatus)
        btnScanReceivers = findViewById(R.id.btnScanReceivers)
        llReceivers = findViewById(R.id.llReceivers)

        btnFilePerm.setOnClickListener { requestFileAccess() }
        // 无障碍警告行整体可点，跳系统设置（替代原来的独立大按钮）
        tvWarn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnScanReceivers.setOnClickListener {
            tvReceiverStatus.text = getString(R.string.sender_scanning)
            CastService.discoverReceivers { list ->
                tvReceiverStatus.text =
                    if (list.isEmpty()) getString(R.string.sender_scan_none)
                    else getString(R.string.sender_scan_found, list.size)
            }
        }

        btnStart.setOnClickListener {
            if (CastService.currentPairCode != null) {
                // 投屏中 → 停止（CastService.stopAll 随服务停止清理全部资源）
                stopService(Intent(this, CastService::class.java))
                CastService.currentPairCode = null
                tvStatus.text = getString(R.string.sender_status_stopped)
                refreshCastingUi()
            } else {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(pm.createScreenCaptureIntent())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        CastService.addStatusListener(statusListener)
        CastService.addReceiverListener(receiverListener)
    }

    override fun onStop() {
        CastService.removeStatusListener(statusListener)
        CastService.removeReceiverListener(receiverListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshPairCodeView()
        refreshAccessibilityStatus()
        refreshFilePermStatus()
        refreshReceivers()
        // 前台期监听剪贴板：手机复制的内容自动同步给接收端（Android 10+ 后台读不了剪贴板）
        clipboardManager()?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onPause() {
        clipboardManager()?.removePrimaryClipChangedListener(clipListener)
        super.onPause()
    }

    private fun clipboardManager(): ClipboardManager? =
        getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /**
     * 剪贴板变化 → 广播 CLIPBOARD 帧给接收端。
     * 防回环：CastService 写入手机剪贴板（收到接收端同步来的文本）前置了
     * clipSyncSuppress 标志，这里看到标志就跳过本次变化并清除，避免死循环。
     */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (CastService.clipSyncSuppress) {
            CastService.clipSyncSuppress = false
            return@OnPrimaryClipChangedListener
        }
        if (CastService.currentPairCode == null) return@OnPrimaryClipChangedListener
        val text = clipboardManager()
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
        if (!text.isNullOrEmpty()) {
            CastService.broadcastClipboard(text)
        }
    }

    /** 重新进入页面时恢复显示服务当前使用的配对码。 */
    private fun refreshPairCodeView() {
        CastService.currentPairCode?.let {
            pairCode = it
            tvPairCode.text = it
        }
        refreshCastingUi()
    }

    /**
     * 按 CastService 运行状态刷新「投屏中」相关 UI。
     * currentPairCode 非空即服务在跑（stopAll 会置空），作为唯一状态判据。
     * 用于 onResume / 状态推送等「查询当前真相」的场景。
     */
    private fun refreshCastingUi() {
        val code = CastService.currentPairCode
        if (code != null) {
            showCastingUi(code)
        } else {
            pairCard.visibility = View.GONE
            btnStart.text = getString(R.string.sender_start)
            tvPairCode.text = "------"
        }
    }

    /** 无条件进入「投屏中」UI 态（本地已知配对码时用，不等服务）。 */
    private fun showCastingUi(code: String) {
        pairCard.visibility = View.VISIBLE
        btnStart.text = getString(R.string.sender_stop)
        pairCode = code
        tvPairCode.text = code
    }

    /** 文件访问权限状态（接收端「文件」功能前提）。 */
    private fun refreshFilePermStatus() {
        btnFilePerm.text =
            if (FileRelay.hasStorageAccess(this)) getString(R.string.sender_file_granted) else getString(R.string.sender_file_grant)
    }

    /**
     * 渲染连接管理列表：已连接设备排前，勾选框=白名单（空名单时全部默认勾选）。
     * 先 setChecked 再挂 OnCheckedChangeListener，避免渲染赋值触发回环改回白名单。
     */
    private fun refreshReceivers() {
        val list = CastService.receivers()
        if (list.isEmpty()) {
            tvReceiverStatus.text = getString(R.string.sender_scan_none2)
            llReceivers.removeAllViews()
            return
        }
        tvReceiverStatus.text = getString(R.string.sender_found, list.size)
        llReceivers.removeAllViews()
        val inflater = LayoutInflater.from(this)
        list.forEach { r ->
            val row = inflater.inflate(R.layout.item_receiver, llReceivers, false)
            val name = row.findViewById<TextView>(R.id.tvItemName)
            val cb = row.findViewById<CheckBox>(R.id.cbAllowed)
            name.text = buildString {
                append(r.displayName())
                append("  ")
                append(r.host)
                append(if (r.connected) getString(R.string.sender_item_connected) else getString(R.string.sender_item_online))
            }
            cb.isChecked = CastService.isReceiverAllowed(r.host)
            cb.setOnCheckedChangeListener { _, checked ->
                CastService.setReceiverAllowed(r.host, checked)
                toast(if (checked) getString(R.string.sender_kick_allow, r.displayName()) else getString(R.string.sender_kick_deny, r.displayName()))
            }
            llReceivers.addView(row)
        }
    }

    private fun requestFileAccess() {
        if (FileRelay.hasStorageAccess(this)) {
            toast(getString(R.string.sender_file_granted_toast))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 注意：MANAGE_ALL_FILES_ACCESS_PERMISSION 是全局列表页，AOSP 的 intent-filter
            // 不声明 <data>；给它附 package: URI 会解析不到 Activity 直接抛
            // ActivityNotFoundException 闪退（国产 ROM filter 写得松才碰巧不崩）。
            // 正确做法是用应用专属 action，并做三级回退兜底。
            val candidates = listOf(
                // 1. 应用专属「所有文件访问」开关页（API 30+，filter 带 package scheme）
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                // 2. 全局文件访问列表页（不带 data，让用户在列表里找到本应用）
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                // 3. 应用详情页兜底
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
            for (intent in candidates) {
                try {
                    startActivity(intent)
                    toast(getString(R.string.sender_file_perm_hint))
                    return
                } catch (e: ActivityNotFoundException) {
                    QpmLog.w(TAG, "file-access intent 不可用: ${intent.action}", e)
                }
            }
            toast(getString(R.string.sender_file_settings_fail))
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                REQ_READ_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_STORAGE) {
            refreshFilePermStatus()
            if (!FileRelay.hasStorageAccess(this)) toast(getString(R.string.sender_file_denied))
        }
    }

    private fun selectedBitrate(): Int = when (rgQuality.checkedRadioButtonId) {
        R.id.rbSmooth -> 4_000_000
        R.id.rbHigh -> 10_000_000
        else -> 6_000_000
    }

    private fun refreshAccessibilityStatus() {
        // 最权威的判断：系统真正启用无障碍服务时会回调 onServiceConnected 并置位 instance，
        // 这不受部分 ROM（如小米澎湃OS）对 getInstalledAccessibilityServiceList 的侧载过滤影响。
        val connected = QpmControlService.instance != null
        val enabledBySetting = isAccessibilityEnabled(this)
        // 警告只在未开启时出现，不覆写 tvStatus（状态条只承载投屏状态事件）
        val a11yOn = connected || enabledBySetting
        tvWarn.visibility = if (a11yOn) View.GONE else View.VISIBLE
        // 已开启时给一条提示：部分 ROM（小米/澎湃OS）的「屏幕共享防护」会把微信/QQ 等画面糊成毛玻璃，
        // 用户常误以为是投屏画质问题
        tvA11yHint.visibility = if (a11yOn) View.VISIBLE else View.GONE
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val pkg = ctx.packageName
        // 不同 ROM 的存储格式有差异：
        //  - 服务间分隔符可能是 ':'（标准）或 ';'（部分定制 ROM）
        //  - 类名可能是完整名(com.xxx/foo.Bar)或相对名(com.xxx/.Bar)
        val fullName = "$pkg/${QpmControlService::class.java.name}"
        val shortName = "$pkg/.${QpmControlService::class.java.simpleName}"
        return enabled.split(":", ";")
            .map { it.trim() }
            .any { it == fullName || it == shortName }
    }

    private fun genPairCode(): String =
        (1..6).joinToString("") { Random.nextInt(10).toString() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private companion object {
        private const val TAG = "SenderActivity"
        private const val REQ_READ_STORAGE = 1001
    }
}
