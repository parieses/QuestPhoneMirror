package com.qpm.mirror

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.qpm.protocol.Discovery

/**
 * 接收端：UDP 扫描发现 -> TCP 配对 -> 收视频 / 发控制；也支持手动输 IP 连接。
 *
 * 界面分三层：底层 SurfaceView（视频），顶层手动连接栏 + 居中状态面板 + 底部提示条。
 * 状态面板按阶段切换文案：搜索中 -> 连接中 -> 等待配对 ->（配对成功）隐藏。
 * 发现多个发送端时弹选择框；唯一发送端自动免密直连。
 */
class ReceiverActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var hint: TextView
    private lateinit var edtIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var ipBar: LinearLayout
    private lateinit var statusPanel: LinearLayout
    private lateinit var tvStage: TextView
    private lateinit var tvStageHint: TextView
    private lateinit var edtPair: EditText
    private lateinit var btnPair: Button
    private lateinit var btnFiles: Button

    private var decoder: H264Decoder? = null
    private var client: MirrorClient? = null
    private var mapper: TouchMapper? = null

    /** 接收端 beacon 广播线程：让发送端「连接管理」在连接前就能扫描到本机。 */
    private var beaconThread: Thread? = null

    /** 最近一次配对使用的码（空串/未配对 = 免密直连），文件浏览器复用。 */
    private var lastPairCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        // 接收端屏幕/2D 面板空闲会变暗，观看期间保持常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.surface)
        hint = findViewById(R.id.hint)
        edtIp = findViewById(R.id.edtIp)
        btnConnect = findViewById(R.id.btnConnect)
        ipBar = findViewById(R.id.ipBar)
        statusPanel = findViewById(R.id.statusPanel)
        tvStage = findViewById(R.id.tvStage)
        tvStageHint = findViewById(R.id.tvStageHint)
        edtPair = findViewById(R.id.edtPair)
        btnPair = findViewById(R.id.btnPair)
        btnFiles = findViewById(R.id.btnFiles)
        surfaceView.holder.addCallback(this)

        showSearching()

        btnConnect.setOnClickListener {
            val ip = edtIp.text.toString().trim()
            val dec = decoder
            if (ip.isEmpty()) return@setOnClickListener
            if (dec == null) {
                hint.text = "画面尚未就绪，稍候再试"
                return@setOnClickListener
            }
            startClient(dec, ip)
            showConnecting()
        }

        btnPair.setOnClickListener {
            val code = edtPair.text.toString().trim()
            if (code.isEmpty()) {
                hint.text = "请输入发送端显示的配对码"
                return@setOnClickListener
            }
            hint.text = "正在配对…"
            lastPairCode = code
            client?.pair(code)
        }

        btnFiles.setOnClickListener { openFileBrowser() }

        surfaceView.setOnTouchListener { _, event ->
            mapper?.onTouch(event) ?: false
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val dec = try {
            H264Decoder(
                holder.surface,
                { w, h -> runOnUiThread { fitSurface(w, h) } }
            ) { msg ->
                Log.e(TAG, "解码器错误: $msg")
                runOnUiThread { hint.text = "解码错误：$msg，返回重进重试" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解码器初始化失败: ${e.message}")
            tvStage.text = "解码器初始化失败"
            tvStageHint.text = e.message ?: "本机不支持 H.264 硬解"
            return
        }
        decoder = dec

        mapper = TouchMapper(
            { surfaceView.width },
            { surfaceView.height },
            { client?.phoneW() ?: 0 },
            { client?.phoneH() ?: 0 }
        ) { client?.send(it) }

        startClient(dec, null)
        showSearching()
    }

    /** 停掉旧连接并用同一套回调建新客户端；host 为 null 时走 UDP 自动发现。 */
    private fun startClient(dec: H264Decoder, host: String?) {
        client?.stop()
        client = MirrorClient(
            decoder = dec,
            onScreenInfo = { w, h, _ -> runOnUiThread { fitSurface(w, h); hint.text = "" } },
            onStatus = { s -> runOnUiThread { onStatus(s) } },
            onDisconnected = { runOnUiThread { onDisconnected() } },
            onPairResult = { ok -> runOnUiThread { onPairResult(ok) } },
            onMultipleSenders = { devices -> runOnUiThread { onMultipleSenders(devices) } },
            onRejected = { reason -> runOnUiThread { onRejected(reason) } },
            helloName = Build.MODEL ?: "接收端"
        )
        if (host == null) client?.start() else client?.connect(host)
    }

    // ---------- 状态面板 ----------

    private fun showSearching() {
        statusPanel.visibility = View.VISIBLE
        tvStage.text = "正在搜索发送端…"
        tvStageHint.text = "请先在手机上打开「我是发送端」并点开始投屏\n两端需连接同一个 Wi-Fi"
        setPairUiVisible(false)
        ipBar.visibility = View.VISIBLE
    }

    private fun showConnecting() {
        statusPanel.visibility = View.VISIBLE
        tvStage.text = "正在连接…"
        tvStageHint.text = ""
        setPairUiVisible(false)
        // 连接后隐藏顶部 IP 栏，避免与配对面板视觉重叠
        ipBar.visibility = View.GONE
    }

    private fun showPairing() {
        statusPanel.visibility = View.VISIBLE
        tvStage.text = "等待配对"
        tvStageHint.text = "输入手机发送端页面显示的 6 位配对码\n配对成功后自动开始播放"
        setPairUiVisible(true)
        edtPair.requestFocus()
    }

    private fun setPairUiVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        edtPair.visibility = v
        btnPair.visibility = v
    }

    private fun onStatus(s: String) {
        when {
            s.startsWith("正在搜索") -> showSearching()
            s.startsWith("连接 ") -> showConnecting()
            s.startsWith("正在免密直连") -> {
                statusPanel.visibility = View.VISIBLE
                tvStage.text = "正在免密直连…"
                tvStageHint.text = "局域网内只检测到一个发送端，无需配对码"
                setPairUiVisible(false)
                ipBar.visibility = View.GONE
            }
            s.startsWith("已连接") -> showPairing()
            s.startsWith("发现 ") -> {
                statusPanel.visibility = View.VISIBLE
                tvStage.text = s
                tvStageHint.text = "请从列表选择要连接的发送端"
                setPairUiVisible(false)
            }
            s.startsWith("连接中断") -> {
                statusPanel.visibility = View.VISIBLE
                tvStage.text = "连接中断"
                tvStageHint.text = s
                setPairUiVisible(false)
                ipBar.visibility = View.VISIBLE
            }
            else -> hint.text = s
        }
    }

    private fun onPairResult(ok: Boolean) {
        if (ok) {
            // 配对成功：收起面板，露出视频画面
            statusPanel.visibility = View.GONE
            hint.text = "配对成功，等待画面…"
        } else {
            showPairing()
            tvStage.text = "配对码错误"
            hint.text = "请重试（连续输错 3 次发送端会断开）"
        }
    }

    private fun onMultipleSenders(devices: List<Discovery.Device>) {
        val dec = decoder ?: return
        val items = devices.map { "${it.host}  (${it.w}x${it.h})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("发现 ${devices.size} 个发送端")
            .setItems(items) { _, which ->
                val d = devices[which]
                startClient(dec, d.host)
                showConnecting()
            }
            .setOnCancelListener { showSearching() }
            .show()
    }

    private fun onDisconnected() {
        statusPanel.visibility = View.VISIBLE
        tvStage.text = "已断开"
        tvStageHint.text = "返回重进接收端即可重新搜索连接"
        setPairUiVisible(false)
        ipBar.visibility = View.VISIBLE
    }

    /** 发送端在「连接管理」里取消了本机的勾选：明确告知用户原因。 */
    private fun onRejected(reason: String) {
        statusPanel.visibility = View.VISIBLE
        tvStage.text = "发送端已拒绝本机连接"
        tvStageHint.text = reason
        setPairUiVisible(false)
        ipBar.visibility = View.VISIBLE
        hint.text = "如需连接，请在手机端「连接管理」中重新勾选本设备"
    }

    override fun onStart() {
        super.onStart()
        // 页面在前台期间持续宣告本机存在，供发送端连接管理扫描
        beaconThread = Thread { Discovery.broadcastReceiverLoop(Build.MODEL ?: "接收端") }.apply {
            start()
        }
    }

    override fun onStop() {
        beaconThread?.interrupt()
        beaconThread = null
        super.onStop()
    }

    /** 打开文件浏览器：复用当前连接的发送端与配对码。 */
    private fun openFileBrowser() {
        val host = client?.currentHost()
        if (host == null) {
            hint.text = "请先连接发送端再浏览文件"
            return
        }
        startActivity(
            Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_HOST, host)
                putExtra(FileBrowserActivity.EXTRA_PORT, CastService.TCP_PORT)
                putExtra(FileBrowserActivity.EXTRA_PAIR_CODE, lastPairCode ?: "")
            }
        )
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

    private companion object {
        private const val TAG = "ReceiverActivity"
    }
}
