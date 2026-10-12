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
            toast("在列表里找到「QPM 控制服务」并打开；若列表里看不到，用 ADB 命令直接启用")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }

    private fun refreshAccessibilityStatus() {
        val on = isAccessibilityEnabled(this)
        btnAccessibility.text = if (on) "无障碍：已开启 ✓" else "开启无障碍（点击前往设置）"
        if (!on) {
            tvStatus.text = "接收端能看但不能操作本机，请先开启「QPM 控制服务」无障碍"
        }
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = ctx.packageName + "/" + ControlService::class.java.name
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").contains(expected)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
