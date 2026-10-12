package com.qpm.mirror

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 接收端：UDP 发现发送端 -> TCP 收视频 / 发控制；也支持手动输 IP 连接。 */
class ReceiverActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var hint: TextView
    private lateinit var edtIp: EditText
    private lateinit var btnConnect: Button

    private var decoder: H264Decoder? = null
    private var client: MirrorClient? = null
    private var mapper: TouchMapper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)

        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.surface)
        hint = findViewById(R.id.hint)
        edtIp = findViewById(R.id.edtIp)
        btnConnect = findViewById(R.id.btnConnect)
        surfaceView.holder.addCallback(this)

        btnConnect.setOnClickListener {
            val ip = edtIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                // 先彻底停掉旧的（含 UDP 发现监听），再建新连接，避免两个客户端同时喂同一个解码器
                client?.stop()
                client = null
                client = MirrorClient(
                    decoder!!,
                    onScreenInfo = { w, h, _ -> runOnUiThread { fitSurface(w, h) } },
                    onStatus = { s -> runOnUiThread { hint.text = s } },
                    onDisconnected = { runOnUiThread { hint.text = "已断开，返回重进重连" } }
                ).also { it.connect(ip) }
            }
        }

        surfaceView.setOnTouchListener { _, event ->
            mapper?.onTouch(event) ?: false
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        decoder = H264Decoder(holder.surface) { w, h -> runOnUiThread { fitSurface(w, h) } }

        mapper = TouchMapper(
            { surfaceView.width },
            { surfaceView.height },
            { client?.phoneW() ?: 0 },
            { client?.phoneH() ?: 0 }
        ) { client?.send(it) }

        client = MirrorClient(
            decoder!!,
            onScreenInfo = { w, h, _ -> runOnUiThread { fitSurface(w, h) } },
            onStatus = { s -> runOnUiThread { hint.text = s } },
            onDisconnected = { runOnUiThread { hint.text = "已断开，返回重进重连" } }
        ).also { it.start() }
    }

    /** 让 SurfaceView 保持手机屏幕宽高比，居中显示。 */
    private fun fitSurface(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        val vw = root.width.toFloat()
        val vh = root.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val scale = minOf(vw / videoW, vh / videoH)
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        lp.width = (videoW * scale).toInt()
        lp.height = (videoH * scale).toInt()
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        client?.stop()
        decoder?.stop()
        client = null
        decoder = null
    }
}
