package com.qpm.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }

    private fun refreshAccessibilityStatus() {
        // 最权威的判断：系统真正启用无障碍服务时会回调 onServiceConnected 并置位 instance，
        // 这不受部分 ROM（如小米澎湃OS）对 getInstalledAccessibilityServiceList 的侧载过滤影响。
        val connected = SelectToSpeakService.instance != null
        val enabledBySetting = isAccessibilityEnabled(this)
        when {
            connected -> {
                btnAccessibility.text = "无障碍：已启用 ✓"
                tvStatus.text = "接收端可操作本机（点击可重新进入设置）"
            }
            enabledBySetting -> {
                btnAccessibility.text = "无障碍：已开启 ✓"
                tvStatus.text = "系统已记录本服务为开启，可直接使用"
            }
            else -> {
                btnAccessibility.text = "开启控制权限（无障碍）"
                tvStatus.text = "未在系统开启无障碍。若设置列表看不到「QPM 控制服务」，请用 ADB 直接启用（启用后上方变 ✓）"
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
        val fullName = "$pkg/${SelectToSpeakService::class.java.name}"
        val shortName = "$pkg/.${SelectToSpeakService::class.java.simpleName}"
        return enabled.split(":", ";")
            .map { it.trim() }
            .any { it == fullName || it == shortName }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
