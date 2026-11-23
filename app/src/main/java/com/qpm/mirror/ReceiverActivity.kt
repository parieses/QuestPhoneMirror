package com.qpm.mirror

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
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
    /**
     * 上一次已处理过的目标码流尺寸（-1 = 无）。SCREEN_INFO 重复到达时不再重建：
     * 反复「停旧解码器 → 建新解码器」既丢帧，失败的 configure 还可能把 Surface 弄坏。
     */
    private var lastTargetW = -1
    private var lastTargetH = -1
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
    /** [p50, p95, rttMs, jitter]，由 MirrorClient 每秒回报。 */
    @Volatile private var latestLatency: IntArray? = null
    private var prevDecoded = 0L
    private var prevDecodedTs = 0L

    /** 接收端 beacon 广播线程：让发送端「连接管理」在连接前就能扫描到本机。 */
    private var beaconThread: Thread? = null

    /** 最近一次配对使用的码（空串/未配对 = 免密直连），文件浏览器复用。 */
    private var lastPairCode: String? = null

    /** 画面停更看门狗：连上后连续几秒收不到视频帧 → 先要关键帧，再不行就强制重连。 */
    private var lastKeyReqAt = 0L
    private var lastStallReconnectAt = 0L
    private var stallHintShown = false

    /** 解码器出错（含 1101 资源被回收）后的重建重试计数与任务。 */
    private var decoderRecoveryAttempts = 0
    private val decoderRecoveryRunnable = Runnable { recoverDecoder() }
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            runCatching { checkVideoStall() }
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
        initPipeline(holder.surface)
    }

    /**
     * 建立「解码器 + 连接 + 看门狗」这条流水线。Surface 首次就绪时调用；
     * 从后台回来（见 onStart）也会复用，避免同一 Surface 上挂着两个 MediaCodec。
     */
    private fun initPipeline(surface: Surface) {
        // 切后台再回来会重建 Surface，旧解码器仍挂在旧 Surface 上：必须先释放。
        // 一个 Surface 的 BufferQueue 只有单 producer 槽位，两个 MediaCodec 同时
        // configure 同一个 Surface 会 configure 失败 / 旧解码器渲染时 native abort。
        val stale = decoder
        if (stale != null) {
            QpmLog.i(TAG, "初始化流水线：释放挂在旧 Surface 上的解码器 ${stale.videoW}x${stale.videoH}")
            client?.decoder = null
            decoder = null
            runCatching { stale.stop() }
        }
        lastTargetW = -1
        lastTargetH = -1
        // 用最近一次已知的真实尺寸配置（候选链兜底，见 buildDecoder）：
        // 写死 1920x1080 或直接拿超限尺寸硬配，都会在部分机型上出问题
        val built = buildDecoder(surface, lastVideoW, lastVideoH)
        if (built == null) {
            QpmLog.e(TAG, "解码器初始化失败（${lastVideoW}x$lastVideoH）")
            tvStage.text = getString(R.string.receiver_decoder_fail)
            tvStageHint.text = getString(R.string.receiver_decoder_fail_all, lastVideoW, lastVideoH)
            return
        }
        val (dec, used) = built
        if (used.first != lastVideoW || used.second != lastVideoH) {
            QpmLog.w(TAG, "初始解码器 ${lastVideoW}x$lastVideoH 超限，降级 ${used.first}x${used.second}")
            hint.text = getString(R.string.receiver_decoder_clamped, lastVideoW, lastVideoH, used.first, used.second)
        }
        QpmLog.i(TAG, "解码器初始化成功（${used.first}x${used.second}），开始连接")
        decoder = dec

        mapper = TouchMapper(
            { surfaceView.width },
            { surfaceView.height },
            { client?.phoneW() ?: 0 },
            { client?.phoneH() ?: 0 }
        ) { client?.send(it) }

        startClient(dec, null)
        showSearching()
        uiHandler.removeCallbacks(watchdogRunnable)
        uiHandler.postDelayed(watchdogRunnable, 2000)
    }

    /**
     * 「连上了但画面不动」的兜底恢复。
     *
     * 发送端在链路吃不住码率时会连续丢 P 帧（甚至整段停发），接收端的参考链断掉、
     * 画面就停在最后一帧，而 TCP 并没有断——用户看到的就是「连上显示一下就没了」。
     * 这里先按 3s 间隔要一个关键帧（发送端补 IDR 就能续上）；仍然不动就重连一次，
     * 重新走一遍握手并把码率降下来。一次卡顿只重连一次，避免无限循环。
     */
    private fun checkVideoStall() {
        if (destroyed || isFinishing || isDestroyed) return
        val c = client ?: return
        if (!paired || !c.hasVideo()) return
        val gap = c.videoGapMs()
        if (gap < STALL_HINT_MS) {
            // 画面恢复：把提示条收掉，并允许下一次卡顿再重连一次
            if (stallHintShown) {
                stallHintShown = false
                hint.text = ""
            }
            lastStallReconnectAt = 0L
            return
        }
        if (!stallHintShown) {
            stallHintShown = true
            hint.text = getString(R.string.receiver_stall_recovering)
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyReqAt > 3000) {
            lastKeyReqAt = now
            QpmLog.w(TAG, "画面停更 ${gap}ms，向发送端请求关键帧")
            c.requestKeyFrame()
        }
        if (gap > STALL_RECONNECT_MS && now - lastStallReconnectAt > 20_000) {
            lastStallReconnectAt = now
            val dec = decoder
            QpmLog.w(TAG, "画面停更 ${gap}ms 且请求关键帧无效，强制重连")
            if (dec != null) startClient(dec, null)
        }
    }

    /**
     * 解码器 onError（含 1101 = ERROR_RECLAIMED，资源被系统回收）触发的重建请求。
     * 不能直接在工作线程 stop/release 当前 codec（会与回调竞争，部分 ROM 直接死锁），
     * 故 post 到 UI 线程延迟执行；300ms 远小于系统回收后的宽限期，满足「尽快 release」要求。
     */
    private fun scheduleDecoderRecovery() {
        if (destroyed || isFinishing || isDestroyed) return
        uiHandler.removeCallbacks(decoderRecoveryRunnable)
        uiHandler.postDelayed(decoderRecoveryRunnable, 300)
    }

    /**
     * 重建解码器并重新走一遍握手拿关键帧。1101 是终止态：必须先 release 旧 codec，
     * 否则宽限期一到系统直接杀进程（无堆栈 SIGABRT）。H264Decoder.stop() 内含 release。
     * 连续失败有次数上限，避免 codec 持续被回收时陷入无限重建。
     */
    private fun recoverDecoder() {
        if (destroyed || isFinishing || isDestroyed) return
        if (decoderRecoveryAttempts >= MAX_DECODER_RECOVERY) {
            QpmLog.e(TAG, "解码器重建已连续失败 $decoderRecoveryAttempts 次，停止重试")
            runUi { tvStage.text = getString(R.string.receiver_decoder_fail) }
            return
        }
        decoderRecoveryAttempts++
        val surface = runCatching { surfaceView.holder.surface }.getOrNull()
        if (surface == null || !surface.isValid) {
            QpmLog.w(TAG, "解码器重建跳过：Surface 无效（等待 surfaceCreated）")
            return
        }
        val old = decoder
        QpmLog.w(TAG, "解码器重建第 $decoderRecoveryAttempts 次：停旧实例并按 ${lastVideoW}x$lastVideoH 重建")
        client?.decoder = null
        decoder = null
        runCatching { old?.stop() }
        lastTargetW = -1
        lastTargetH = -1
        val built = buildDecoder(surface, lastVideoW, lastVideoH)
        if (built == null) {
            QpmLog.e(TAG, "解码器重建失败：所有候选尺寸都建不起来")
            runUi { tvStage.text = getString(R.string.receiver_decoder_fail) }
            return
        }
        val (dec, used) = built
        decoder = dec
        QpmLog.i(TAG, "解码器重建成功 ${used.first}x${used.second}，重新连接获取关键帧")
        // 新解码器要重新拿 SPS/PPS 与关键帧，重连一次走完整握手
        startClient(dec, null)
    }

    /** 停掉旧连接并用同一套回调建新客户端；host 为 null 时走 UDP 自动发现。 */
    private fun startClient(dec: H264Decoder, host: String?) {
        QpmLog.i(TAG, "startClient：host=$host 加密=$useEncryption")
        client?.stop()
        paired = false
        stallHintShown = false
        lastStallReconnectAt = 0L
        // 任一连接入口都先尝试免密直连（autoPair 由 MirrorClient 内部按是否带码决定），
        // 故标记本次为「免密直连尝试」，便于被拒时给出正确提示
        lastAutoPairAttempt = true
        cryptoFailedThisConnection = false
        client = MirrorClient(
            decoder = dec,
            onScreenInfo = { w, h, _, vw, vh ->
                // 解码器重建必须在这条收帧线程上同步完成：MirrorClient 处理完 SCREEN_INFO
                // 才会读下一帧视频。若把重建 post 到主线程，网络线程会先把新尺寸码流喂给
                // 旧尺寸解码器，部分机型硬解在 native 层直接 SIGSEGV（Java 层拦不住，
                // 表现就是「配对成功/连接上立刻闪退，日志里没有堆栈」）。
                // 按码流尺寸 configure；真实屏幕尺寸只用于触摸映射（降采样后两者不同）。
                ensureDecoderSize(vw, vh)
                runUi {
                    fitSurface(w, h)
                    hint.text = ""
                }
            },
            onStatus = { s -> runUi { onStatus(s) } },
            onDisconnected = { runUi { onDisconnected() } },
            onPairResult = { ok -> runUi { onPairResult(ok) } },
            onMultipleSenders = { devices -> runUi { onMultipleSenders(devices) } },
            onRejected = { reason -> runUi { onRejected(reason) } },
            onClipboard = { text -> runUi { onClipboardText(text) } },
            onFilePushResult = { ok, msg -> runUi { onFilePushResult(ok, msg) } },
            onStats = { stats -> latestStats = stats },
            onLatency = { v -> latestLatency = v },
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
            // 全新握手成功，说明解码器链路健康，重置重建重试计数
            decoderRecoveryAttempts = 0
            lastKeyReqAt = 0L
            lastStallReconnectAt = 0L
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
        // onStop 里主动释放过硬解（避免被系统资源管理器回收）。若 Surface 还在，
        // 这里把流水线重新建起来；Surface 已销毁时 surfaceCreated 会接手。
        if (!destroyed && decoder == null) {
            val surface = runCatching { surfaceView.holder.surface }.getOrNull()
            if (surface != null && surface.isValid) {
                QpmLog.i(TAG, "onStart：回到前台，重建解码与连接")
                initPipeline(surface)
            }
        }
    }

    override fun onStop() {
        QpmLog.i(TAG, "onStop")
        beaconThread?.interrupt()
        beaconThread = null
        // 切后台必须主动释放硬解：Android 12+ 的 ResourceManagerService 会从后台应用
        // 回收 codec；回收后不 release（1101/ERROR_RECLAIMED 是终止态）系统会直接杀进程，
        // 日志里只剩一条没有堆栈的 SIGABRT。这也是「进出接收端几次后必崩」的来源。
        uiHandler.removeCallbacks(watchdogRunnable)
        uiHandler.removeCallbacks(decoderRecoveryRunnable)
        client?.stop()
        decoder?.stop()
        client = null
        decoder = null
        super.onStop()
    }

    override fun onDestroy() {
        QpmLog.i(TAG, "onDestroy：接收端界面销毁（isFinishing=$isFinishing）")
        destroyed = true
        uiHandler.removeCallbacks(pairTimeoutRunnable)
        uiHandler.removeCallbacks(watchdogRunnable)
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
        val base = getString(R.string.receiver_stats_line, w, h, fps, bps, dropped, decodedFps)
        val lat = latestLatency
        statsView.text = if (lat != null && lat.size >= 4) {
            base + "\n" + getString(R.string.receiver_stats_latency, lat[0], lat[1], lat[2], lat[3])
        } else base
    }

    /**
     * 发送端分辨率变化（首次连上时 SCREEN_INFO 到达、或手机旋转）时按真实尺寸重建解码器。
     *
     * 硬解在 configure 时拿到的尺寸与实际码流差别较大时，部分机型会在 native 层崩溃
     * （Java 层拦不住，表现为「连接成功就闪退回首页」「手机一转屏接收端就没了」）。
     * 发送端每次配对/转向都会先发 SCREEN_INFO 再发新码流，所以这里能赶在首帧前重建。
     *
     * 真实尺寸 configure 也可能直接失败：1440x3200 这类 20:9 竖屏的高度超出多数机型
     * AVC 硬解上限（c2 常见 2048），configure 抛 IllegalArgumentException。旧实现此时
     * 只记日志 return，留下 1080p 旧解码器去解 3200 高的码流——正是 v0.17 前「连上就
     * 闪退」的 native 崩溃场景。现按候选链逐个试创建，全部失败回退到上一个可用尺寸。
     *
     * **换解码器必须「先停旧、再建新」**：同一个 Surface 只能挂一个 MediaCodec，
     * 旧实例还在时 configure 新实例会失败，且旧实例继续渲染会 native abort（SIGABRT）。
     */
    private fun ensureDecoderSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // 可能从 MirrorClient 收帧线程调用：页面/Surface 已销毁时不再碰解码器
        if (destroyed || isFinishing || isDestroyed) return
        val cur = decoder
        // 回退尺寸要在覆盖 lastVideoW/H 之前取，否则回退目标会变成刚到达的新尺寸
        val prevW = cur?.videoW ?: DEFAULT_W
        val prevH = cur?.videoH ?: DEFAULT_H
        lastVideoW = w
        lastVideoH = h
        if (cur != null && cur.videoW == w && cur.videoH == h) return
        // 同一目标尺寸已经处理过（成功降级或失败回退）就直接复用当前解码器：
        // 重复重建只会白丢几帧，而每次失败的 configure 都在折腾同一个 Surface
        if (cur != null && lastTargetW == w && lastTargetH == h) return
        val surface = runCatching { surfaceView.holder.surface }.getOrNull()
        if (surface == null || !surface.isValid) {
            QpmLog.w(TAG, "按 ${w}x$h 重建解码器跳过：Surface 无效")
            return
        }
        lastTargetW = w
        lastTargetH = h

        // 关键顺序：先停旧解码器、空出 Surface，再建新解码器。
        //
        // 一个 Surface 的 BufferQueue 只有一个 producer 槽位。旧解码器还挂在上面时
        // 去 configure 新解码器，configure 直接失败（异常 message 常为空，真正原因在
        // diagnosticInfo），而且旧解码器随后的 releaseOutputBuffer(render=true) 会在
        // native 层 abort → SIGABRT，Java 层没有任何堆栈，进程当场消失
        // （crashes/exit-SIGNALED-*.log，status=6）。v0.22 及以前「一降到候选尺寸
        // 就闪退」正是这个顺序问题，不是尺寸本身不被支持。
        client?.decoder = null
        decoder = null
        runCatching { cur?.stop() }

        var built = buildDecoder(surface, w, h)
        if (built == null) {
            // 全部候选尺寸都建不起来：把「刚才那个已知能用」的尺寸重建回来，
            // 至少让画面继续，而不是留下 null 解码器永久黑屏
            QpmLog.e(TAG, "所有候选尺寸均无法创建解码器（目标 ${w}x$h），回退 ${prevW}x$prevH")
            built = buildDecoder(surface, prevW, prevH)
        }
        if (built == null) {
            // 回退也失败：允许下一次 SCREEN_INFO 再试一次（清掉去重标记）
            QpmLog.e(TAG, "解码器重建与回退均失败，等待后续 SCREEN_INFO 重试")
            lastTargetW = -1
            lastTargetH = -1
            runUi { hint.text = getString(R.string.receiver_decoder_fail_all, w, h) }
            return
        }
        val (dec, used) = built
        decoder = dec
        client?.decoder = dec
        if (used.first != w || used.second != h) {
            QpmLog.w(TAG, "按 ${w}x$h 重建解码器失败，已降级 ${used.first}x${used.second} 兼容投屏")
            runUi { hint.text = getString(R.string.receiver_decoder_clamped, w, h, used.first, used.second) }
        } else {
            QpmLog.i(TAG, "解码器已按 ${w}x$h 重建（旧 ${prevW}x$prevH）")
        }
    }

    /**
     * 按候选链创建解码器，返回 (解码器, 实际尺寸)；全部失败返回 null。
     * 候选链见 [decoderSizeCandidates]。
     */
    private fun buildDecoder(surface: Surface, w: Int, h: Int): Pair<H264Decoder, Pair<Int, Int>>? {
        for ((cw, ch) in decoderSizeCandidates(w, h)) {
            val dec = try {
                H264Decoder(
                    surface = surface,
                    onVideoSize = { vw, vh -> runUi { fitSurface(vw, vh) } },
                    onError = { msg ->
                        QpmLog.e(TAG, "解码器错误: $msg，准备重建解码器")
                        // 只提示不重建 = 永久黑屏；而且 1101（ERROR_RECLAIMED）是终止态，
                        // 文档要求立刻 release，否则宽限期一到系统直接杀进程（无堆栈 SIGABRT）
                        runUi { hint.text = getString(R.string.receiver_decoder_error, msg) }
                        scheduleDecoderRecovery()
                    },
                    videoW = cw,
                    videoH = ch
                )
            } catch (e: Exception) {
                QpmLog.w(TAG, "创建 ${cw}x$ch 解码器失败: ${describeCodecError(e)}")
                null
            } ?: continue
            return dec to (cw to ch)
        }
        return null
    }

    /**
     * MediaCodec 的失败原因大多不在 message 里（configure 失败时常见 message 为空），
     * 真正线索在 CodecException.diagnosticInfo 与 recoverable/transient 标志上。
     */
    private fun describeCodecError(e: Throwable): String {
        val base = "${e.javaClass.simpleName}: ${e.message}"
        if (e !is MediaCodec.CodecException) return base
        val diag = runCatching { e.diagnosticInfo }.getOrNull()
        val rec = runCatching { e.isRecoverable }.getOrNull()
        val tra = runCatching { e.isTransient }.getOrNull()
        return "$base | diag=$diag recoverable=$rec transient=$tra"
    }

    /**
     * 解码器尺寸候选链：真实尺寸 → 钳制到本机 AVC 硬解能力上限 → 保底 1080p。
     *
     * clamp 保持宽高比并取 16 对齐（AVC 要求）。configure 尺寸允许小于码流 SPS 尺寸：
     * 解码器按 SPS 输出真实尺寸（onOutputFormatChanged → fitSurface 等比布局），
     * 比让不支持大尺寸的机器硬吃超限码流导致 native 崩溃安全得多。
     */
    private fun decoderSizeCandidates(w: Int, h: Int): List<Pair<Int, Int>> {
        val list = mutableListOf(w to h)
        val (maxW, maxH) = avcDecoderMaxSize()
        val clamped = clampAligned(w, h, maxW, maxH)
        if (clamped != w to h && clamped !in list) list.add(clamped)
        val fallback = minOf(w, 1920) to minOf(h, 1080)
        if (fallback !in list) list.add(fallback)
        return list
    }

    /** 本机 AVC 硬解支持的最大宽/高（取所有硬解里最小的上限；查询失败按不限处理）。 */
    private fun avcDecoderMaxSize(): Pair<Int, Int> {
        avcMaxCache?.let { return it }
        val result = runCatching {
            var best = Int.MAX_VALUE to Int.MAX_VALUE
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in infos) {
                if (info.isEncoder) continue
                if (MediaFormat.MIMETYPE_VIDEO_AVC !in info.supportedTypes) continue
                val caps = runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.getOrNull() ?: continue
                val vc = caps.videoCapabilities ?: continue
                val mw = runCatching { vc.supportedWidths.upper }.getOrNull() ?: continue
                val mh = runCatching { vc.supportedHeights.upper }.getOrNull() ?: continue
                if (mw <= 0 || mh <= 0) continue
                best = minOf(best.first, mw) to minOf(best.second, mh)
            }
            best
        }.getOrDefault(Int.MAX_VALUE to Int.MAX_VALUE)
        avcMaxCache = result
        return result
    }

    /** 等比缩放到上限内并取 16 对齐；未超限原样返回。 */
    private fun clampAligned(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (maxW <= 0 || maxH <= 0) return w to h
        if (w <= maxW && h <= maxH) return w to h
        val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
        var nw = ((w * scale) / 16).toInt() * 16
        var nh = ((h * scale) / 16).toInt() * 16
        if (nw <= 0) nw = 16
        if (nh <= 0) nh = 16
        return nw to nh
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
        uiHandler.removeCallbacks(watchdogRunnable)
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

        /** 没拿到 SCREEN_INFO 时的默认 configure 尺寸（也是「已知能用」的回退目标）。 */
        private const val DEFAULT_W = 1920
        private const val DEFAULT_H = 1080

        /** 画面停更多久开始要关键帧 / 多久后强制重连。 */
        private const val STALL_HINT_MS = 3000L
        private const val STALL_RECONNECT_MS = 10_000L

        /** 解码器连续重建上限，超过后停止重试，避免 codec 被反复回收时无限重建。 */
        private const val MAX_DECODER_RECOVERY = 3

        /**
         * 最近一次已知的发送端画面尺寸：进程内记住，重建解码器/返回界面时直接复用，
         * 避免又用默认 1920x1080 去解一个分辨率完全不同的码流。
         */
        @Volatile
        private var lastVideoW = DEFAULT_W

        @Volatile
        private var lastVideoH = DEFAULT_H

        /** AVC 硬解最大宽高缓存（进程内查一次即可，能力不会变）。 */
        @Volatile
        private var avcMaxCache: Pair<Int, Int>? = null
    }
}
