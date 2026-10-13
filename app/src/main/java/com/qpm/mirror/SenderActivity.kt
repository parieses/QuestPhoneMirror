package com.qpm.mirror

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** 发送端：申请录屏权限后拉起 CastService 开始编码推流。 */
class SenderActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnAccessibility: Button

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                toast("录屏权限被拒绝")
                return@registerForActivityResult
            }
            ContextCompat.startForegroundService(
                this,
                Intent(this, CastService::class.java).apply {
                    putExtra(CastService.EXTRA_CODE, result.resultCode)
                    putExtra(CastService.EXTRA_DATA, result.data)
                }
            )
            tvStatus.text = "投屏中，去 Quest 上打开「我是接收端」"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)
        tvStatus = findViewById(R.id.tvStatus)
        btnAccessibility = findViewById(R.id.btnAccessibility)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(pm.createScreenCaptureIntent())
        }

        btnAccessibility.setOnClickListener {
            if (!isServiceInstalled(this)) {
                toast("系统未识别本应用的无障碍服务：请确认装在第一空间（非双开），并彻底卸载后重装")
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }

    private fun refreshAccessibilityStatus() {
        val installed = isServiceInstalled(this)
        val enabled = isAccessibilityEnabled(this)
        when {
            enabled -> {
                btnAccessibility.text = "无障碍：已开启 ✓"
                tvStatus.text = "接收端可操作本机（点击可重新进入设置）"
            }
            installed -> {
                btnAccessibility.text = "开启无障碍（点击前往设置）"
                tvStatus.text = "系统已识别「QPM 控制服务」，去设置里把它打开即可"
            }
            else -> {
                btnAccessibility.text = "无障碍：系统未识别此服务"
                tvStatus.text = "系统服务列表里没有本应用的无障碍服务——多半是装错空间/残包，请卸载后重装，或用 ADB 诊断"
            }
        }
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
        val fullName = "$pkg/${ControlService::class.java.name}"
        val shortName = "$pkg/.${ControlService::class.java.simpleName}"
        return enabled.split(":", ";")
            .map { it.trim() }
            .any { it == fullName || it == shortName }
    }

    /**
     * 通过系统 API 查询本服务是否已被系统识别并登记为"已安装的无障碍服务"。
     * 与 isAccessibilityEnabled 不同：后者看"是否开启"，本方法看"系统有没有在
     * 已安装服务列表里看到它"。若返回 false，说明不是 GUI 隐藏，而是 APK 没有把
     * 服务注册进当前用户（装错空间/双开副本/残包/构建未打包 manifest）。
     */
    private fun isServiceInstalled(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val cn = ComponentName(ctx, ControlService::class.java)
        return am.getInstalledAccessibilityServiceList()
            .map { it.resolveInfo.serviceInfo }
            .any { ComponentName(it.packageName, it.name) == cn }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
