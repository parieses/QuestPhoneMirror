package com.qpm.mirror

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.qpm.mirror.rtc.QpmRtcLog
import com.qpm.mirror.rtc.RtcFactory
import com.qpm.protocol.Discovery
import com.qpm.protocol.Proto
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.Timer
import java.util.TimerTask

/**
 * 接收端：UDP 扫描发现 -> TCP 配对 -> 收视频 / 发控制；也支持手动输 IP 连接。
 *
 * 界面分三层：底层 SurfaceView（视频），顶层手动连接栏 + 居中状态面板 + 底部提示条。
 * 状态面板按阶段切换文案：搜索中 -> 连接中 -> 等待配对 ->（配对成功）隐藏。
 * 发现多个发送端时弹选择框；唯一发送端自动免密直连。
 */
class ReceiverActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceViewRenderer
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
    private lateinit var btnClipboard: Button
    private lateinit var btnPush: Button
    private lateinit var btnStats: Button
    private lateinit var btnRefresh: Button
    private lateinit var statsView: TextView

    /** WebRTC 渲染器：内置抖动缓冲 + 硬解，替代自研 MediaCodec + SurfaceView。 */
    private var rendererInited = false
    private var remoteTrack: VideoTrack? = null
    private var lastVideoW = 0
    private var lastVideoH = 0

    private var client: MirrorClient? = null
    private var mapper: TouchMapper? = null

    /** 配对码配对（手动模式）成功后由 PAIR_RESULT 置真；用于判断配对超时是否仍在进行。 */
    @Volatile private var paired = false
    /** 本次连接是否以「免密直连」发起：用于区分 onPairResult(false) 是「多发送端被拒」还是「输错码」。 */
    private var lastAutoPairAttempt = false

    /** 销毁守卫：Surface/生命周期销毁后，所有 runOnUiThread 回调直接丢弃，避免访问已释放视图崩溃。 */
    @Volatile private var destroyed = false

    /** 配对超时：连接后 15s 内未收到 PAIR_RESULT 即判定为卡死/不通，提示用户刷新重试。 */
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pairTimeoutRunnable = Runnable { onPairTimeout() }
    private val PAIR_TIMEOUT_MS = 15_000L

    /** 统计浮窗开关与刷新定时器。 */
    private var statsOn = false
    private var statsTimer: Timer? = null
    private var latestStats: IntArray? = null
    /** [p50, p95, rttMs, jitter]，保留接口兼容统计浮窗，WebRTC 版暂以 0 填充。 */
    @Volatile private var latestLatency: IntArray? = null
    private var prevDecoded = 0L
    private var prevDecodedTs = 0L

    /** 接收端 beacon 广播线程：让发送端「连接管理」在连接前就能扫描到本机。 */
    private var beaconThread: Thread? = null

    /** 最近一次配对使用的码（空串/未配对 = 免密直连），文件浏览器复用。 */
    private var lastPairCode: String? = null

    /** ICE 断连看门狗：DISCONNECTED 超过 8s 仍未恢复则主动重建会话。 */
    @Volatile private var iceDisconnectedAt = 0L
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            runCatching { checkIceStall() }
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QpmLog.i(TAG, "onCreate：接收端界面创建（savedInstanceState=${savedInstanceState != null}）")
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
        btnClipboard = findViewById(R.id.btnClipboard)
        btnPush = findViewById(R.id.btnPush)
        btnStats = findViewById(R.id.btnStats)
        btnRefresh = findViewById(R.id.btnRefresh)
        statsView = findViewById(R.id.statsView)

        // WebRTC 渲染器初始化（EGL 由 RtcFactory 统一持有）
        val (_, eglCtx) = RtcFactory.init(this)
        surfaceView.init(eglCtx, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                runUi { hint.text = "" }
            }
            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                QpmRtcLog.i("Receiver", "渲染分辨率 ${w}x${h} rot=$rotation")
            }
        })
        surfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        surfaceView.setEnableHardwareScaler(true)
        rendererInited = true

        showSearching()

        btnConnect.setOnClickListener {
            val ip = edtIp.text.toString().trim()
            if (ip.isEmpty()) return@setOnClickListener
            if (!rendererInited) {
                hint.text = getString(R.string.receiver_surface_not_ready)
                return@setOnClickListener
            }
            startClient(ip)
            showConnecting()
        }

        btnPair.setOnClickListener {
            val code = edtPair.text.toString().trim()
            if (code.isEmpty()) {
                hint.text = getString(R.string.receiver_pair_empty)
                return@setOnClickListener
            }
            hint.text = getString(R.string.receiver_pairing)
            lastPairCode = code
            client?.pair(code)
        }

        btnFiles.setOnClickListener { openFileBrowser() }

        btnPush.setOnClickListener { pickFile() }

        btnStats.setOnClickListener { toggleStats() }

        btnClipboard.setOnClickListener { showClipboardDialog() }

        btnRefresh.setOnClickListener { refreshConnection() }

        surfaceView.setOnTouchListener { _, event ->
            mapper?.onTouch(event) ?: false
        }
    }

    /**
     * WebRTC 版连接入口：不再依赖解码器与 Surface 生命周期。
     * 渲染器在 onCreate 已初始化；这里只负责建 MirrorClient + RtcSession。
     */
    private fun startClient(host: String?) {
        QpmLog.i(TAG, "startClient(WebRTC)：host=$host")
        client?.stop()
        paired = false
        iceDisconnectedAt = 0L
        lastAutoPairAttempt = true
        client = MirrorClient(
            context = this,
            onStatus = { s -> runUi { onStatus(s) } },
            onDisconnected = { runUi { onDisconnected() } },
            onPairResult = { ok -> runUi { onPairResult(ok) } },
            onMultipleSenders = { devices -> runUi { onMultipleSenders(devices) } },
            onRejected = { reason -> runUi { onRejected(reason) } },
            onClipboard = { text -> runUi { onClipboardText(text) } },
            onFilePushResult = { ok, msg -> runUi { onFilePushResult(ok, msg) } },
            onRemoteTrack = { track -> attachTrack(track) },
            onScreenInfo = { w, h, _ ->
                lastVideoW = w
                lastVideoH = h
                runUi { hint.text = "" }
            },
            onConnected = { runUi { onRtcConnected() } },
            onIceState = { state -> runUi { onIceState(state) } },
            helloName = Build.MODEL ?: getString(R.string.receiver_device_name)
        )
        mapper = TouchMapper(
            { surfaceView.width },
            { surfaceView.height },
            { client?.phoneW() ?: lastVideoW },
            { client?.phoneH() ?: lastVideoH }
        ) { client?.send(it) }
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        if (host == null) client?.start() else client?.connect(host)
        uiHandler.removeCallbacks(watchdogRunnable)
        uiHandler.postDelayed(watchdogRunnable, 2000)
    }

    /** 远端视频轨到位：挂到 SurfaceViewRenderer，重复调用先摘旧轨。 */
    private fun attachTrack(track: VideoTrack) {
        if (remoteTrack === track) return
        remoteTrack?.removeSink(surfaceView)
        remoteTrack = track
        track.addSink(surfaceView)
        QpmRtcLog.i("Receiver", "远端视频轨已挂到渲染器")
    }

    private fun onRtcConnected() {
        statusPanel.visibility = View.GONE
        hint.text = getString(R.string.receiver_pair_ok)
        iceDisconnectedAt = 0L
    }

    private fun onIceState(state: String) {
        QpmRtcLog.i("Receiver", "ICE 状态=$state")
        when (state) {
            "DISCONNECTED" -> {
                iceDisconnectedAt = SystemClock.elapsedRealtime()
                hint.text = getString(R.string.receiver_stall_recovering)
            }
            "CONNECTED", "COMPLETED" -> {
                iceDisconnectedAt = 0L
                statusPanel.visibility = View.GONE
            }
            "FAILED" -> hint.text = getString(R.string.receiver_disconnected)
            else -> Unit
        }
    }

    /** ICE 长时间 DISCONNECTED 兜底：重建会话（WebRTC 自带恢复失败时）。 */
    private fun checkIceStall() {
        if (destroyed || isFinishing || isDestroyed) return
        val t = iceDisconnectedAt
        if (t == 0L) return
        if (SystemClock.elapsedRealtime() - t > ICE_STALL_REBUILD_MS) {
            QpmRtcLog.w("Receiver", "ICE DISCONNECTED 超时，重建连接")
            iceDisconnectedAt = 0L
            startClient(client?.currentHost())
        }
    }

    /** 重新搜索：停掉旧连接，回到 UDP 自动发现（沿用当前加密开关）。 */
    private fun refreshConnection() {
        if (destroyed) return
        hint.text = ""
        startClient(null)
    }

    /** 主线程安全地执行 UI 更新：页面已销毁则丢弃，避免访问释放中的视图崩溃。 */
    private fun runUi(block: () -> Unit) {
        if (destroyed || isFinishing || isDestroyed) return
        runOnUiThread(block)
    }

    private fun onPairTimeout() {
        if (destroyed || paired) return
        QpmLog.w(TAG, "配对超时（${PAIR_TIMEOUT_MS}ms 未收到 PAIR_RESULT）")
        statusPanel.visibility = View.VISIBLE
        tvStage.text = getString(R.string.receiver_disconnected)
        tvStageHint.text = ""
        hint.text = getString(R.string.receiver_pair_timeout)
        setPairUiVisible(false)
        ipBar.visibility = View.VISIBLE
    }

    // ---------- 剪贴板同步 ----------

    /** 手机复制了内容：写入本机剪贴板 + 提示。Quest 等无输入法设备可直接粘贴使用。 */
    private fun onClipboardText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { cm.setPrimaryClip(ClipData.newPlainText("QPM", text)) }
            .onFailure { QpmLog.w(TAG, "写剪贴板失败: ${it.message}") }
        hint.text = getString(R.string.receiver_clip_copied)
    }

    /** 输入框预填本机剪贴板内容，发送到手机剪贴板。 */
    private fun showClipboardDialog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val prefill = cm?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?: ""
        val input = EditText(this).apply {
            setText(prefill)
            setSelection(prefill.length)
            hint = getString(R.string.receiver_clip_dialog_hint)
            minLines = 2
            maxLines = 6
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.receiver_clip_dialog_title))
            .setView(box)
            .setPositiveButton(getString(R.string.receiver_clip_send)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    hint.text = getString(R.string.receiver_clip_empty)
                } else {
                    client?.sendClipboard(text)
                    hint.text = getString(R.string.receiver_clip_sent)
                }
            }
            .setNegativeButton(getString(R.string.receiver_clip_cancel), null)
            .show()
    }

    // ---------- 状态面板 ----------

    private fun showSearching() {
        statusPanel.visibility = View.VISIBLE
        tvStage.text = getString(R.string.receiver_searching)
        tvStageHint.text = getString(R.string.receiver_search_hint)
        setPairUiVisible(false)
        ipBar.visibility = View.VISIBLE
    }

    private fun showConnecting() {
        statusPanel.visibility = View.VISIBLE
        tvStage.text = getString(R.string.receiver_connecting)
        tvStageHint.text = ""
        setPairUiVisible(false)
        // 连接后隐藏顶部 IP 栏，避免与配对面板视觉重叠
        ipBar.visibility = View.GONE
    }

    private fun showPairing() {
        statusPanel.visibility = View.VISIBLE
        tvStage.text = getString(R.string.receiver_wait_pair)
        tvStageHint.text = getString(R.string.receiver_wait_pair_hint)
        setPairUiVisible(true)
        edtPair.requestFocus()
    }

    private fun setPairUiVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        edtPair.visibility = v
        btnPair.visibility = v
    }

    private fun onStatus(s: String) {
        QpmLog.i(TAG, "onStatus: $s")
        when {
            s.startsWith("正在搜索") -> showSearching()
            s.startsWith("连接 ") -> showConnecting()
            s.startsWith("正在免密直连") -> {
                statusPanel.visibility = View.VISIBLE
                tvStage.text = getString(R.string.receiver_autopair)
                tvStageHint.text = getString(R.string.receiver_autopair_hint)
                setPairUiVisible(false)
                ipBar.visibility = View.GONE
                // 仅免密直连等待期间启用配对超时（手动输码/多发送端选择不计时）
                uiHandler.removeCallbacks(pairTimeoutRunnable)
                uiHandler.postDelayed(pairTimeoutRunnable, PAIR_TIMEOUT_MS)
            }
            s.startsWith("已连接") -> {
                uiHandler.removeCallbacks(pairTimeoutRunnable)
                showPairing()
            }
            s.startsWith("发现 ") -> {
                uiHandler.removeCallbacks(pairTimeoutRunnable)
                statusPanel.visibility = View.VISIBLE
                // 内部分发信号（中文前缀）保留，显示层按数量本地化
                val count = s.removePrefix("发现 ").substringBefore(' ').toIntOrNull() ?: 0
                tvStage.text = getString(R.string.receiver_multi, count)
                tvStageHint.text = getString(R.string.receiver_multi_hint)
                setPairUiVisible(false)
            }
            s.startsWith("连接中断") -> {
                statusPanel.visibility = View.VISIBLE
                tvStage.text = getString(R.string.receiver_disconnected)
                // 冒号后是运行态诊断细节（异常原文），保留原文仅去前缀
                tvStageHint.text = s.substringAfter('：', missingDelimiterValue = "")
                setPairUiVisible(false)
                ipBar.visibility = View.VISIBLE
            }
            else -> hint.text = s
        }
    }

    private fun onPairResult(ok: Boolean) {
        QpmLog.i(TAG, "onPairResult: ok=$ok")
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        paired = ok
        if (ok) {
            // 配对成功：收起面板，露出视频画面
            statusPanel.visibility = View.GONE
            hint.text = getString(R.string.receiver_pair_ok)
        } else {
            showPairing()
            if (lastAutoPairAttempt) {
                // 免密直连被发送端拒绝：通常是局域网内有多个发送端，需要输码区分
                lastAutoPairAttempt = false
                tvStage.text = getString(R.string.receiver_need_code)
                hint.text = getString(R.string.receiver_need_code_hint)
            } else {
                tvStage.text = getString(R.string.receiver_pair_wrong)
                hint.text = getString(R.string.receiver_pair_wrong_hint)
            }
        }
    }

    private fun onMultipleSenders(devices: List<Discovery.Device>) {
        val items = devices.map { "${it.host}  (${it.w}x${it.h})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.receiver_multi, devices.size))
            .setItems(items) { _, which ->
                val d = devices[which]
                startClient(d.host)
                showConnecting()
            }
            .setOnCancelListener { showSearching() }
            .show()
    }

    private fun onDisconnected() {
        QpmLog.i(TAG, "onDisconnected：连接断开 destroyed=$destroyed")
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        statusPanel.visibility = View.VISIBLE
        tvStage.text = getString(R.string.receiver_disconnected)
        tvStageHint.text = getString(R.string.receiver_disconnected_hint)
        setPairUiVisible(false)
        ipBar.visibility = View.VISIBLE
    }

    /** 发送端在「连接管理」里取消了本机的勾选：明确告知用户原因。 */
    private fun onRejected(reason: String) {
        QpmLog.i(TAG, "rejected by sender: $reason")
        statusPanel.visibility = View.VISIBLE
        tvStage.text = getString(R.string.receiver_rejected)
        // 拒绝原因是发送端下发的内部信号，按白名单场景给出本地化引导，原文进日志
        tvStageHint.text = ""
        setPairUiVisible(false)
        ipBar.visibility = View.VISIBLE
        hint.text = getString(R.string.receiver_rejected_hint)
    }

    override fun onStart() {
        super.onStart()
        QpmLog.i(TAG, "onStart")
        // 页面在前台期间持续宣告本机存在，供发送端连接管理扫描
        beaconThread = Thread { Discovery.broadcastReceiverLoop(Build.MODEL ?: getString(R.string.receiver_device_name)) }.apply {
            start()
        }
        // WebRTC 渲染器跟随 Surface 自动恢复，无需重建连接
        // 回到前台必须重启 ICE 看门狗（onStop 里停掉的），否则切后台再回来断线无人兜底
        if (client != null) {
            uiHandler.removeCallbacks(watchdogRunnable)
            uiHandler.postDelayed(watchdogRunnable, 1000)
        }
    }

    override fun onStop() {
        QpmLog.i(TAG, "onStop：切后台不断连（WebRTC 链路继续存活）")
        beaconThread?.interrupt()
        beaconThread = null
        uiHandler.removeCallbacks(watchdogRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        QpmLog.i(TAG, "onDestroy：接收端界面销毁（isFinishing=$isFinishing）")
        destroyed = true
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        uiHandler.removeCallbacks(watchdogRunnable)
        statsTimer?.cancel()
        statsTimer = null
        remoteTrack?.removeSink(surfaceView)
        remoteTrack = null
        runCatching { surfaceView.release() }
        client?.stop()
        client = null
        super.onDestroy()
    }

    /** 打开文件浏览器：复用当前连接的发送端与配对码。 */
    private fun openFileBrowser() {
        val host = client?.currentHost()
        if (host == null) {
            hint.text = getString(R.string.receiver_files_no_conn)
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

    // ---------- 文件推送（接收端 -> 发送端存储） ----------

    /** 选本机文件，推送到发送端共享存储。 */
    private fun pickFile() {
        val host = client?.currentHost()
        if (host == null) {
            hint.text = getString(R.string.receiver_push_no_conn)
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_PUSH) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PUSH && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            pushFile(uri)
        }
    }

    /** 读取所选文件，按 64KB 分块发送 FILE_PUSH_REQ / FILE_PUSH_DATA。 */
    private fun pushFile(uri: Uri) {
        val c = client ?: return
        val resolver = contentResolver
        val name = runCatching {
            resolver.query(uri, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cur.moveToFirst() && i >= 0) cur.getString(i) else null
            }
        }.getOrNull() ?: "push_${System.currentTimeMillis()}.bin"
        val total = runCatching {
            resolver.query(uri, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cur.moveToFirst() && i >= 0) cur.getLong(i) else 0L
            }
        }.getOrNull() ?: 0L

        val buf = ByteArray(64 * 1024)
        try {
            resolver.openInputStream(uri)?.use { input ->
                c.send(Proto.filePushReq(total, name))
                var offset = 0L
                var sentEof = false
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val eof = n < buf.size
                    c.send(Proto.filePushData(offset, eof, if (n == buf.size) buf else buf.copyOf(n)))
                    offset += n
                    sentEof = eof
                    hint.text = getString(
                        R.string.receiver_push_progress,
                        (offset / 1024).toInt(),
                        (total / 1024).toInt()
                    )
                    if (eof) break
                }
                if (!sentEof) {
                    c.send(Proto.filePushData(offset, true, ByteArray(0)))
                }
            } ?: run {
                hint.text = getString(R.string.receiver_push_read_fail, "空文件或无法打开")
                return
            }
        } catch (e: Exception) {
            hint.text = getString(R.string.receiver_push_read_fail, e.message ?: "")
        }
    }

    private fun onFilePushResult(ok: Boolean, msg: String) {
        hint.text = if (ok) getString(R.string.receiver_push_ok, msg)
        else getString(R.string.receiver_push_fail, msg)
    }

    // ---------- 投屏统计浮窗 ----------

    private fun toggleStats() {
        statsOn = !statsOn
        if (statsOn) {
            statsView.visibility = View.VISIBLE
            statsTimer = Timer().apply {
                scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        if (destroyed || isFinishing || isDestroyed) {
                            cancel()
                            return
                        }
                        runOnUiThread { if (!destroyed) refreshStats() }
                    }
                }, 0, 1000)
            }
        } else {
            statsView.visibility = View.GONE
            statsTimer?.cancel()
            statsTimer = null
        }
    }

    private fun refreshStats() {
        val s = latestStats
        val w = s?.getOrNull(2) ?: client?.phoneW() ?: 0
        val h = s?.getOrNull(3) ?: client?.phoneH() ?: 0
        val fps = s?.getOrNull(0) ?: 0
        val bitrate = s?.getOrNull(1) ?: 0
        val dropped = s?.getOrNull(4) ?: 0
        // WebRTC 版：解码帧率由渲染器内部管理，统计浮窗显示 0 表示"由 WebRTC 自适应"
        val decodedFps = 0
        val bps = if (bitrate >= 1_000_000) {
            String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000.0)
        } else {
            "${bitrate / 1000} Kbps"
        }
        val base = getString(R.string.receiver_stats_line, w, h, fps, bps, dropped, decodedFps)
        val lat = latestLatency
        statsView.text = if (lat != null && lat.size >= 4) {
            base + "\n" + getString(R.string.receiver_stats_latency, lat[0], lat[1], lat[2], lat[3])
        } else base
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


    private companion object {
        private const val TAG = "ReceiverActivity"
        private const val REQ_PUSH = 1001
        /** ICE DISCONNECTED 超过此时长仍未恢复则重建连接。 */
        private const val ICE_STALL_REBUILD_MS = 8_000L
    }

}
