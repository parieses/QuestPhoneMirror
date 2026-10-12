package com.qpm.mirror

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/** 入口：选择本机角色。同一个 APK 装在手机和 Quest 上，各选一边。 */
class RoleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role)

        findViewById<View>(R.id.cardSender).setOnClickListener {
            startActivity(Intent(this, SenderActivity::class.java))
        }
        findViewById<View>(R.id.cardReceiver).setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
    }
}
