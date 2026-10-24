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
import com.qpm.protocol.Proto
import java.util.Timer
import java.util.TimerTask

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
    private lateinit var btnClipboard: Button
    private lateinit var btnPush: Button
    private lateinit var btnStats: Button
    private lateinit var btnRefresh: Button
    private lateinit var statsView: TextView

    private var decoder: H264Decoder? = null
    private var client: MirrorClient? = null
    private var mapper: TouchMapper? = null

    /** 配对码配对（手动模式）成功后由 PAIR_RESULT 置真；用于判断配对超时是否仍在进行。 */
    @Volatile private var paired = false
    /** 本次连接是否以「免密直连」发起：用于区分 onPairResult(false) 是「多发送端被拒」还是「输错码」。 */
    private var lastAutoPairAttempt = false

    /** 销毁守卫：Surface/生命周期销毁后，所有 runOnUiThread 回调直接丢弃，避免访问已释放视图崩溃。 */
    @Volatile private var destroyed = false

    /**
     * 加密通道开关。默认开启；一旦握手失败（含发送端派生异常、对端不支持等）即降级为明文，
     * 且本次会话不再重试加密——保证即使加密在任何设备/网络下出问题，用户也最多体验一次失败即自动转明文可用。
     */
    @Volatile private var useEncryption = true
    @Volatile private var cryptoDisabled = false
    /** 本次连接是否因加密失败而断开（用于断开后自动转明文重连一次）。 */
    @Volatile private var cryptoFailedThisConnection = false

    /** 配对超时：连接后 15s 内未收到 PAIR_RESULT 即判定为卡死/不通，提示用户刷新重试。 */
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pairTimeoutRunnable = Runnable { onPairTimeout() }
    private val PAIR_TIMEOUT_MS = 15_000L

    /** 统计浮窗开关与刷新定时器。 */
    private var statsOn = false
    private var statsTimer: Timer? = null
    private var latestStats: IntArray? = null
    private var prevDecoded = 0L
    private var prevDecodedTs = 0L

    /** 接收端 beacon 广播线程：让发送端「连接管理」在连接前就能扫描到本机。 */
    private var beaconThread: Thread? = null

    /** 最近一次配对使用的码（空串/未配对 = 免密直连），文件浏览器复用。 */
    private var lastPairCode: String? = null

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
        surfaceView.holder.addCallback(this)

        showSearching()

        btnConnect.setOnClickListener {
            val ip = edtIp.text.toString().trim()
            val dec = decoder
            if (ip.isEmpty()) return@setOnClickListener
            if (dec == null) {
                hint.text = getString(R.string.receiver_surface_not_ready)
                return@setOnClickListener
            }
            startClient(dec, ip)
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        QpmLog.i(TAG, "surfaceCreated：Surface 就绪，初始化解码器")
        destroyed = false
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        val dec = try {
            H264Decoder(
                holder.surface,
                { w, h -> runUi { fitSurface(w, h) } }
            ) { msg ->
                QpmLog.e(TAG, "解码器错误: $msg")
                runUi { hint.text = getString(R.string.receiver_decoder_error, msg) }
            }
        } catch (e: Exception) {
            QpmLog.e(TAG, "解码器初始化失败: ${e.message}", e)
            tvStage.text = getString(R.string.receiver_decoder_fail)
            tvStageHint.text = e.message ?: getString(R.string.receiver_h264_unsupported)
            return
        }
        QpmLog.i(TAG, "解码器初始化成功，开始连接")
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
        QpmLog.i(TAG, "startClient：host=$host 加密=$useEncryption")
        client?.stop()
        paired = false
        // 任一连接入口都先尝试免密直连（autoPair 由 MirrorClient 内部按是否带码决定），
        // 故标记本次为「免密直连尝试」，便于被拒时给出正确提示
        lastAutoPairAttempt = true
        cryptoFailedThisConnection = false
        client = MirrorClient(
            decoder = dec,
            onScreenInfo = { w, h, _ -> runUi { fitSurface(w, h); hint.text = "" } },
            onStatus = { s -> runUi { onStatus(s) } },
            onDisconnected = { runUi { onDisconnected() } },
            onPairResult = { ok -> runUi { onPairResult(ok) } },
            onMultipleSenders = { devices -> runUi { onMultipleSenders(devices) } },
            onRejected = { reason -> runUi { onRejected(reason) } },
            onClipboard = { text -> runUi { onClipboardText(text) } },
            onFilePushResult = { ok, msg -> runUi { onFilePushResult(ok, msg) } },
            onStats = { stats -> latestStats = stats },
            helloName = Build.MODEL ?: getString(R.string.receiver_device_name),
            cryptoEnabled = useEncryption
        )
        // 免密直连的配对超时在 onStatus("正在免密直连…") 时启动；手动输码/多发送端选择不计时
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        if (host == null) client?.start() else client?.connect(host)
    }

    /** 重新搜索：停掉旧连接，回到 UDP 自动发现（沿用当前加密开关）。 */
    private fun refreshConnection() {
        if (destroyed) return
        val dec = decoder ?: return
        hint.text = ""
        startClient(dec, null)
    }

    /** 主线程安全地执行 UI 更新：页面已销毁则丢弃，避免访问释放中的视图崩溃。 */
    private fun runUi(block: () -> Unit) {
        if (destroyed || isFinishing || isDestroyed) return
        runOnUiThread(block)
    }

    private fun onPairTimeout() {
        if (destroyed || paired) return
        QpmLog.w(TAG, "配对超时（${PAIR_TIMEOUT_MS}ms 未收到 PAIR_RESULT）")
        // 加密若一直握手卡住，自动降级为明文并在下次刷新时生效
        if (useEncryption && !cryptoDisabled) {
            useEncryption = false
            cryptoDisabled = true
            QpmLog.w(TAG, "配对超时，禁用加密通道，建议点刷新以明文重连")
        }
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
        // 内部信号：加密握手失败。不展示给用户，仅降级为明文并标记本次连接失败，便于断开后自动转明文重连
        if (s == "QPM_CRYPTO_OFF") {
            if (useEncryption && !cryptoDisabled) {
                useEncryption = false
                cryptoDisabled = true
                cryptoFailedThisConnection = true
                QpmLog.w(TAG, "加密握手失败，已禁用加密，断开后将自动以明文重连")
            }
            return
        }
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
        val dec = decoder ?: return
        val items = devices.map { "${it.host}  (${it.w}x${it.h})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.receiver_multi, devices.size))
            .setItems(items) { _, which ->
                val d = devices[which]
                startClient(dec, d.host)
                showConnecting()
            }
            .setOnCancelListener { showSearching() }
            .show()
    }

    private fun onDisconnected() {
        QpmLog.i(TAG, "onDisconnected：连接断开（cryptoFailed=$cryptoFailedThisConnection destroyed=$destroyed）")
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        // 若本次连接因加密握手失败断开，自动以明文重连一次（明文通道已验证可用），避免用户卡在配对
        if (cryptoFailedThisConnection && !destroyed && decoder != null) {
            QpmLog.i(TAG, "加密失败断开，自动以明文重连")
            hint.text = getString(R.string.receiver_crypto_off)
            startClient(decoder!!, null)
            return
        }
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
    }

    override fun onStop() {
        QpmLog.i(TAG, "onStop")
        beaconThread?.interrupt()
        beaconThread = null
        super.onStop()
    }

    override fun onDestroy() {
        QpmLog.i(TAG, "onDestroy：接收端界面销毁（isFinishing=$isFinishing）")
        destroyed = true
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        statsTimer?.cancel()
        statsTimer = null
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
        val now = System.currentTimeMillis()
        val cur = decoder?.framesDecoded() ?: 0L
        val decodedFps = if (prevDecodedTs > 0 && now > prevDecodedTs) {
            ((cur - prevDecoded) * 1000 / (now - prevDecodedTs)).toInt()
        } else 0
        prevDecoded = cur
        prevDecodedTs = now
        val bps = if (bitrate >= 1_000_000) {
            String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000.0)
        } else {
            "${bitrate / 1000} Kbps"
        }
        statsView.text = getString(R.string.receiver_stats_line, w, h, fps, bps, dropped, decodedFps)
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
        QpmLog.i(TAG, "surfaceDestroyed：Surface 销毁，停止解码与连接")
        destroyed = true
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        statsTimer?.cancel()
        statsTimer = null
        client?.stop()
        decoder?.stop()
        client = null
        decoder = null
    }

    private companion object {
        private const val TAG = "ReceiverActivity"
        private const val REQ_PUSH = 1001
    }
}
