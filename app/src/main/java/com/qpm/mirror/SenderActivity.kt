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

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(pm.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("在列表里找到「QPM 控制服务」并打开，否则对方只能看不能操作")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
