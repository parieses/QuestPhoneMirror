package com.qpm.mirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qpm.protocol.Decoders
import com.qpm.protocol.Discovery
import com.qpm.protocol.FilePushChunk
import com.qpm.protocol.Frame
import com.qpm.protocol.FrameReader
import com.qpm.protocol.Proto
import com.qpm.protocol.Type
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 发送端服务：MediaProjection 录屏 -> H.264 硬编 -> TCP 推流，
 * 同一条连接上反向接收控制消息交给 QpmControlService 执行，
 * 并响应文件浏览/下载请求（仅配对通过后可用）。
 *
 * 配对策略：
 * - 网络内存在多个发送端时，必须输入 6 位配对码；
 * - 仅本机一个发送端时（监听不到同类 beacon），允许空配对码免密直连；
 * - 连续配对失败 3 次断开。
 *
 * 连接管理（多接收端场景）：
 * - 接收端连接后发 HELLO 报送设备名；发送端维护白名单 allowedHosts；
 * - 白名单为空 = 全部允许（默认）；非空 = 只允许名单内 IP，其余发 REJECT 后断开；
 * - UI 取消勾选某台设备时自动把其余已知设备收进白名单，实现「只踢这一台」。
 */
class CastService : Service() {

    companion object {
        private const val TAG = "CastService"

        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_PAIR_CODE = "pairCode"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_KEEP_AWAKE = "keepAwake"
        const val TCP_PORT = 45556
        private const val CHANNEL_ID = "qpm_cast"
        private const val QUEUE_DEPTH = 3
        private const val MAX_PAIR_FAILS = 3
        private const val DEFAULT_BITRATE = 6_000_000

        /** 最近一次听到其他 QPM 发送端 beacon 的时间；0 表示从未听到。 */
        private val lastPeerSeen = AtomicLong(0L)

        /** 当前投屏使用的配对码（服务停止后置空），供 UI 重新进入时恢复显示。 */
        @Volatile
        var currentPairCode: String? = null

        /**
         * 剪贴板同步防回环标志：
         * CastService 把接收端同步来的文本写入手机剪贴板前置 true，
         * SenderActivity 的剪贴板监听器看到 true 时跳过本次变化并清除，
         * 避免「写入→触发监听→再发回接收端」的无限环。
         */
        @Volatile
        var clipSyncSuppress = false

        private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()

        fun addStatusListener(l: (String) -> Unit) { statusListeners.add(l) }
        fun removeStatusListener(l: (String) -> Unit) { statusListeners.remove(l) }

        /** 向 UI 广播状态/错误文本。调用方可能是编解码/网络线程，UI 侧需自行切主线程。 */
        fun postStatus(s: String) {
            Log.i(TAG, "status: $s")
            statusListeners.forEach { runCatching { it(s) } }
        }

        /** 连接管理列表变化（连接/断开/beacon 更新）时通知 UI 刷新。 */
        private val receiverListeners = CopyOnWriteArrayList<() -> Unit>()

        fun addReceiverListener(l: () -> Unit) { receiverListeners.add(l) }
        fun removeReceiverListener(l: () -> Unit) { receiverListeners.remove(l) }

        private fun notifyReceiversChanged() {
            receiverListeners.forEach { runCatching { it() } }
        }

        /** 网络内是否只有本机一个发送端（2.5s 内没听到同类 beacon）。 */
        fun autoPairAllowed(): Boolean =
            System.currentTimeMillis() - lastPeerSeen.get() > 2500

        /** 白名单：空集合 = 全部允许；非空 = 仅名单内 IP 可连接。 */
        private val allowedHosts = CopyOnWriteArraySet<String>()

        /** beacon 缓存：host -> 接收端设备名（未连接也可能出现在列表里）。 */
        private val receiverBeacons = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** UI 展示用：一台在网接收端的信息。 */
        class ReceiverInfo(
            val host: String,
            val name: String,
            val connected: Boolean
        ) {
            fun displayName(): String = name.ifBlank { host }
        }

        /**
         * 当前可见的接收端列表：已连接的排前面，其余来自 beacon 缓存。
         * [connectedHosts] 由运行中的服务实例提供（服务未运行时为空）。
         */
        fun receivers(): List<ReceiverInfo> {
            val connected = instanceRef?.connectedReceiverHosts() ?: emptySet()
            val hosts = (connected + receiverBeacons.keys).sortedWith(
                compareBy({ !connected.contains(it) }, { it })
            )
            return hosts.map { h ->
                ReceiverInfo(h, receiverBeacons[h] ?: "", connected.contains(h))
            }
        }

    /** 某台接收端当前是否被允许连接（白名单为空时全部允许）。 */
    fun isReceiverAllowed(host: String): Boolean =
        allowedHosts.isEmpty() || allowedHosts.contains(host)

    /**
     * 把剪贴板文本广播给全部已连接接收端（SenderActivity 监听复制变化时调用）。
     * 服务未运行时无接收端可发，静默跳过。
     */
    fun broadcastClipboard(text: String) {
        if (text.isEmpty()) return
        val frame = Proto.clipboard(text)
        instanceRef?.clients?.forEach { it.sendReliable(frame) }
    }

        /**
         * 允许/禁止某台接收端。
         * 禁止时：若白名单为空先将其余已知设备全部收进白名单（实现「只踢这一台」），
         * 再移除目标并立即断开其现有连接。
         */
        fun setReceiverAllowed(host: String, allowed: Boolean) {
            if (allowed) {
                allowedHosts.add(host)
            } else {
                if (allowedHosts.isEmpty()) {
                    receiverBeacons.keys.forEach { allowedHosts.add(it) }
                    instanceRef?.connectedReceiverHosts()?.forEach { allowedHosts.add(it) }
                }
                allowedHosts.remove(host)
                Log.i(TAG, "已禁止接收端 $host，白名单：$allowedHosts")
                instanceRef?.kickReceiver(host)
            }
            notifyReceiversChanged()
        }

        /** 后台扫描局域网接收端（约 1.5s），结果同时并入 beacon 缓存并回调。 */
        fun discoverReceivers(onResult: (List<ReceiverInfo>) -> Unit) {
            Thread {
                val (_, receivers) = runCatching { Discovery.scanAll(1500) }.getOrDefault(emptyList<Discovery.Device>() to emptyList())
                receivers.forEach {
                    if (it.name.isNotBlank()) receiverBeacons[it.host] = it.name
                }
                notifyReceiversChanged()
                onResult(receivers())
            }.start()
        }

        @Volatile
        private var instanceRef: CastService? = null
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: H264Encoder? = null
    private var server: ServerSocket? = null
    private var broadcastThread: Thread? = null
    private var peerWatchThread: Thread? = null
    private var running = false

    private var pairCode: String = ""
    private var keepAwake: Boolean = true
    private var currentBitrate: Int = DEFAULT_BITRATE
    private var prevScreenOffTimeout: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 投屏统计计数（每秒快照并清零，通过 STATS 帧广播给接收端）。 */
    private val statFrames = AtomicLong(0)
    private val statBytes = AtomicLong(0)
    private val statDropped = AtomicLong(0)
    private var statsThread: Thread? = null

    /** 当前虚拟显示/编码器尺寸（旋转时对比用，与真实屏幕保持同步）。 */
    private var videoW: Int = 0
    private var videoH: Int = 0
    private var orientationListener: OrientationEventListener? = null
    private var lastRotationBucket: Int = -1

    override fun onCreate() {
        super.onCreate()
        instanceRef = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.cast_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.cast_noti_title))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        pairCode = intent?.getStringExtra(EXTRA_PAIR_CODE) ?: ""
        keepAwake = intent?.getBooleanExtra(EXTRA_KEEP_AWAKE, true) ?: true
        val bitrate = intent?.getIntExtra(EXTRA_BITRATE, DEFAULT_BITRATE) ?: DEFAULT_BITRATE
        if (pairCode.isBlank()) {
            // 没有配对码属于编程错误：拒绝启动，避免开出无鉴权的控制通道
            postStatus("内部错误：缺少配对码，投屏未启动")
            stopSelf()
            return START_NOT_STICKY
        }
        if (code == Activity.RESULT_OK && data != null) start(code, data, bitrate) else stopSelf()
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent, bitrate: Int) {
        if (running) return
        running = true
        currentPairCode = pairCode
        currentBitrate = bitrate
        lastPeerSeen.set(0L)
        // 每次重新开始投屏都重置白名单与 beacon 缓存，避免上一轮的踢人名单残留
        allowedHosts.clear()
        receiverBeacons.clear()

        val metrics = realMetrics()
        videoW = metrics.widthPixels
        videoH = metrics.heightPixels

        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(code, data)
        if (projection == null) {
            postStatus("录屏会话创建失败（系统拒绝了授权），请重新开始投屏")
            stopAll()
            return
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection onStop：授权被系统撤销")
                postStatus("录屏授权被系统撤销，投屏已停止")
                stopAll()
            }
        }, null)

        val enc = try {
            newEncoder(videoW, videoH)
        } catch (e: Exception) {
            Log.e(TAG, "编码器初始化失败: ${e.message}")
            postStatus("编码器初始化失败：${e.message ?: "本机不支持 H.264 硬编"}，投屏已停止")
            stopAll()
            return
        }
        encoder = enc
        startStatsTicker()

        display = projection?.createVirtualDisplay(
            "qpm-cast",
            videoW,
            videoH,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            enc.inputSurface,
            null,
            null
        )
        if (display == null) {
            postStatus("虚拟显示创建失败，投屏已停止")
            stopAll()
            return
        }

        applyKeepAwake()
        postStatus("投屏中，配对码 $pairCode（仅本机在网时接收端可免码直连）")
        Thread { acceptLoop() }.start()
        broadcastThread = Thread { Discovery.broadcastLoop(TCP_PORT, videoW, videoH) }.apply { start() }
        peerWatchThread = Thread { peerWatchLoop() }.apply { start() }
        startRotationWatch()
    }

    /** 按当前尺寸建编码器：onFrame 把帧塞进全部客户端的发送队列。 */
    private fun newEncoder(w: Int, h: Int): H264Encoder =
        H264Encoder(w, h, currentBitrate,         onFrame = { buf, size, flags, pts ->
            val frame = Proto.videoFrame(pts, flags, buf, 0, size)
            statFrames.incrementAndGet()
            statBytes.addAndGet(size.toLong())
            var accepted = false
            clients.forEach { if (it.offer(frame)) accepted = true }
            if (!accepted) statDropped.incrementAndGet()
        }, onError = { msg ->
            Log.e(TAG, "编码器错误: $msg")
            postStatus("编码器错误：$msg，投屏已停止")
            // 不能在本线程直接调 codec.stop()（MediaCodec 回调内调用会崩溃），切主线程执行
            mainHandler.post { stopAll() }
        })

    /**
     * 屏幕旋转监听：横竖屏切换后 VirtualDisplay/编码器尺寸与真实屏幕不符
     * （AUTO_MIRROR 只镜像内容不缩放，尺寸不变画面会被压扁/裁剪），
     * 按新尺寸重建显示与编码器，并重发 SCREEN_INFO 让接收端重排画面与触摸映射。
     * 用 90° 分桶做防抖：小角度抖动不触发重建。
     */
    private fun startRotationWatch() {
        lastRotationBucket = -1
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (!running || orientation == ORIENTATION_UNKNOWN) return
                val bucket = ((orientation + 45) / 90) % 4
                if (bucket == lastRotationBucket) return
                lastRotationBucket = bucket
                mainHandler.post { reconfigureForRotation() }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    private fun stopRotationWatch() {
        orientationListener?.disable()
        orientationListener = null
    }

    /** 旋转后按新屏幕尺寸重建 VirtualDisplay + 编码器，并广播新的 SCREEN_INFO。 */
    private fun reconfigureForRotation() {
        if (!running) return
        val m = realMetrics()
        val w = m.widthPixels
        val h = m.heightPixels
        if (w == videoW && h == videoH) return
        Log.i(TAG, "屏幕旋转：${videoW}x$videoH -> ${w}x$h，重建虚拟显示与编码器")
        postStatus("屏幕方向变化，正在按新尺寸调整画面…")
        // 先停旧显示与编码器（顺序：显示不再喂帧 -> 编码器收尾），再按新尺寸重建
        runCatching { display?.release() }
        display = null
        runCatching { encoder?.stop() }
        encoder = null
        val enc = try {
            newEncoder(w, h)
        } catch (e: Exception) {
            Log.e(TAG, "旋转后编码器初始化失败: ${e.message}")
            postStatus("编码器初始化失败：${e.message ?: "本机不支持 H.264 硬编"}，投屏已停止")
            stopAll()
            return
        }
        encoder = enc
        videoW = w
        videoH = h
        display = projection?.createVirtualDisplay(
            "qpm-cast",
            w,
            h,
            m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            enc.inputSurface,
            null,
            null
        )
        if (display == null) {
            postStatus("虚拟显示创建失败，投屏已停止")
            stopAll()
            return
        }
        // 接收端靠 SCREEN_INFO 重排 SurfaceView 宽高比与触摸映射，必须重发
        clients.forEach { it.sendReliable(Proto.screenInfo(w, h, m.densityDpi)) }
        // beacon 里带着屏幕尺寸，换块线程重新广播
        broadcastThread?.interrupt()
        broadcastThread = Thread { Discovery.broadcastLoop(TCP_PORT, w, h) }.apply { start() }
    }

    /** 真实屏幕尺寸（随旋转实时更新；resources.displayMetrics 有配置更新延迟，不用）。 */
    private fun realMetrics(): android.util.DisplayMetrics {
        val m = android.util.DisplayMetrics()
        val disp = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        disp.getRealMetrics(m)
        return m
    }

    /** 每秒快照编码统计并通过 STATS 帧广播给全部已配对接收端。 */
    private fun startStatsTicker() {
        if (statsThread?.isAlive == true) return
        statsThread = Thread {
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                val frames = statFrames.getAndSet(0)
                val bytes = statBytes.getAndSet(0)
                val dropped = statDropped.getAndSet(0)
                val bitrate = (bytes * 8).toInt() // bits per second
                val m = realMetrics()
                val stats = Proto.stats(
                    frames.toInt(), bitrate, m.widthPixels, m.heightPixels, dropped.toInt()
                )
                clients.forEach { if (it.paired) it.sendReliable(stats) }
            }
        }.apply { start() }
    }

    /** 监听是否有其他 QPM 发送端/接收端在网：前者决定免密直连，后者供连接管理列表。 */
    private fun peerWatchLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            val (peers, receivers) = runCatching { Discovery.scanAll(1200) }.getOrDefault(emptyList<Discovery.Device>() to emptyList())
            receivers.forEach {
                if (it.name.isNotBlank()) receiverBeacons[it.host] = it.name
            }
            if (receivers.isNotEmpty()) notifyReceiversChanged()
            // 排除本机自身 beacon（部分设备 UDP 广播会回环），否则会误判「有其他发送端」而永久关闭免密直连
            val others = peers.filter { !isLocalAddress(it.host) }
            if (others.isNotEmpty()) {
                if (lastPeerSeen.get() == 0L) {
                    Log.i(TAG, "检测到 ${others.size} 个其他发送端在网，免密直连已关闭")
                    postStatus("检测到局域网内有其他 QPM 发送端，接收端需输入配对码")
                }
                lastPeerSeen.set(System.currentTimeMillis())
            }
        }
    }

    /** 判断 host 是否为本机某个网络接口的 IP（含回环）。用于 peerWatch 排除自身 beacon。 */
    private fun isLocalAddress(host: String): Boolean {
        if (host == "127.0.0.1" || host == "::1" || host == "localhost") return true
        return runCatching {
            val localhosts = java.util.HashSet<String>()
            val enums = java.net.NetworkInterface.getNetworkInterfaces()
            for (nif in enums) {
                val addrs = nif.inetAddresses
                for (a in addrs) localhosts.add(a.hostAddress)
            }
            localhosts.contains(host)
        }.getOrDefault(false)
    }

    private fun acceptLoop() {
        try {
            server = ServerSocket(TCP_PORT).apply { reuseAddress = true }
            while (running) {
                val socket = server!!.accept().apply { tcpNoDelay = true }
                val client = Client(socket, onPaired = { c ->
                    val m = realMetrics()
                    c.offer(Proto.screenInfo(m.widthPixels, m.heightPixels, m.densityDpi))
                    encoder?.requestKeyFrame()
                    postStatus("接收端 ${c.displayName()} 已配对并开始接收画面")
                    notifyReceiversChanged()
                }, onClose = { notifyReceiversChanged() })
                clients.add(client)
                Log.i(TAG, "新客户端接入 ${socket.inetAddress?.hostAddress}，等待配对")
            }
        } catch (e: Exception) {
            Log.e(TAG, "acceptLoop 退出: ${e.message}")
            postStatus("服务异常：${e.message ?: "TCP 监听失败（端口可能被占用）"}，请停止后重试")
        }
    }

    /** 投屏期间禁止系统自动息屏（MediaProjection 熄屏即停，详见 README）。 */
    private fun applyKeepAwake() {
        if (!keepAwake) return
        val cr = contentResolver
        if (!Settings.System.canWrite(this)) {
            postStatus("未授予「修改系统设置」权限，无法防止自动息屏")
            return
        }
        runCatching {
            prevScreenOffTimeout = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT)
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
            Log.i(TAG, "已临时禁用自动息屏（原值 ${prevScreenOffTimeout}s）")
        }.onFailure {
            postStatus("防息屏设置失败：${it.message}")
        }
    }

    private fun restoreScreenOffTimeout() {
        val t = prevScreenOffTimeout ?: return
        prevScreenOffTimeout = null
        runCatching {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, t)
            Log.i(TAG, "已恢复自动息屏时间：${t}s")
        }
    }

    private fun stopAll() {
        running = false
        currentPairCode = null
        lastPeerSeen.set(0L)
        stopRotationWatch()
        statsThread?.interrupt()
        statsThread = null
        clients.forEach { it.close() }
        clients.clear()
        broadcastThread?.interrupt()
        broadcastThread = null
        peerWatchThread?.interrupt()
        peerWatchThread = null
        runCatching { server?.close() }
        server = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
        encoder?.stop()
        encoder = null
        restoreScreenOffTimeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instanceRef = null
        stopAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 当前已配对接收端的 IP 集合。 */
    private fun connectedReceiverHosts(): Set<String> =
        clients.filter { it.paired }.mapNotNull { it.hostAddress }.toSet()

    /** 断开指定 IP 的接收端（连接管理中取消勾选用）。 */
    private fun kickReceiver(host: String) {
        clients.filter { it.hostAddress == host }.forEach { it.close() }
    }

    /**
     * 接收端同步剪贴板文本过来：写入手机剪贴板 + 置防回环标志。
     * 前台 Activity 的剪贴板监听器会因此被触发，靠 clipSyncSuppress 跳过。
     */
    private fun handleClipboardSync(text: String) {
        if (text.isEmpty()) return
        mainHandler.post {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipSyncSuppress = true
            runCatching { cm.setPrimaryClip(ClipData.newPlainText("QPM", text)) }
                .onFailure { Log.w(TAG, "写手机剪贴板失败: ${it.message}") }
            postStatus(getString(R.string.cast_clip_copied))
        }
    }

    /** 单个接收端连接：配对通过前视频/控制/文件全部门控。 */
    private inner class Client(
        private val socket: Socket,
        private val onPaired: (Client) -> Unit,
        private val onClose: (Client) -> Unit
    ) {
        private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_DEPTH)
        private val writeLock = Any()
        @Volatile private var closed = false

        /** 加密通道：本端为发送端，发出方向为 DIR_PHONE。握手前/失败回落时自动透传。 */
        private val channel = SecureChannel(Crypto.DIR_PHONE)

        /** 配对通过后才允许收视频/发控制/文件。 */
        @Volatile
        var paired = false
            private set

        /** 接收端设备名（HELLO 帧报送），未报送时为空。 */
        @Volatile
        var receiverName: String = ""
            private set

        val hostAddress: String?
            get() = socket.inetAddress?.hostAddress

        fun displayName(): String = receiverName.ifBlank { hostAddress ?: "未知设备" }

        private var pairFails = 0
        private var lastA11yNullLog = 0L

        /** 文件推送：当前正在接收写入的输出流与目标文件（每次 REQ 重置）。 */
        private var pushOs: java.io.OutputStream? = null
        private var pushTarget: File? = null

        private val writer = Thread { writeLoop() }.apply { start() }
        private val reader = Thread { readLoop() }.apply { start() }

        /** 队列满则丢最旧的帧，优先保延迟；未配对时直接丢弃。返回是否成功入队（用于统计丢帧）。 */
        fun offer(frame: ByteArray): Boolean {
            if (closed || !paired) return false
            return if (queue.offer(frame)) {
                true
            } else {
                queue.poll()
                queue.offer(frame)
            }
        }

        /** 可靠发送：控制结果/文件数据必须到达，武装后自动 AES-GCM 封装帧体。 */
        fun sendReliable(bytes: ByteArray) {
            if (closed) return
            try {
                synchronized(writeLock) { socket.getOutputStream().write(channel.seal(bytes)) }
            } catch (e: Exception) {
                Log.w(TAG, "sendReliable 失败: ${e.message}")
                close()
            }
        }

        private fun writeLoop() {
            try {
                val out = socket.getOutputStream()
                while (!closed) {
                    // 用 poll 而非 take：close() 后若队列已空，take() 会永久挂起导致线程泄漏
                    val frame = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    synchronized(writeLock) { out.write(channel.seal(frame)) }
                }
            } catch (_: Exception) {
                close()
            }
        }

        private fun readLoop() {
            try {
                val reader = FrameReader(socket.getInputStream())
                // 首帧来自接收端：期望 KEY_EXCHANGE；否则回落明文（兼容旧版接收端）。
                // 先发本方 KE（明文），再武装——保证 KEY_EXCHANGE 帧永不被加密。
                var pending: Frame? = null
                val first = reader.read() ?: return
                if (first.type == Type.KEY_EXCHANGE) {
                    val kp = runCatching { Crypto.newKeyPair() }.getOrNull()
                    val session = if (kp != null) runCatching { Crypto.deriveSessionKey(kp, first.body) }.getOrNull() else null
                    if (kp != null && session != null) {
                        // 先发本方 KE（明文），再武装——保证 KEY_EXCHANGE 帧永不被加密
                        synchronized(writeLock) {
                            socket.getOutputStream().write(Proto.keyExchange(kp.publicEncoded))
                        }
                        channel.arm(session)
                        Log.i(TAG, "加密通道已建立（AES-256-GCM）")
                    } else {
                        // 密钥交换失败：降级明文，不回发 KE，让对端超时后回落（不会互等死锁）
                        Log.w(TAG, "密钥派生失败，回退明文通道")
                        pending = null
                    }
                } else {
                    Log.w(TAG, "对端不支持加密，回退明文通道")
                    pending = first
                }
                while (!closed) {
                    val raw = pending ?: (reader.read() ?: break)
                    pending = null
                    val frame = channel.open(raw)
                    when (frame.type) {
                        Type.KEY_EXCHANGE -> { /* 已在校验握手处理，主循环不再出现 */ }
                        Type.HELLO -> {
                            receiverName = Decoders.textOf(frame)
                            receiverBeacons[hostAddress ?: ""] = receiverName
                            Log.i(TAG, "接收端 $hostAddress 宣告设备名：$receiverName")
                            notifyReceiversChanged()
                        }

                        Type.PAIR -> handlePair(Decoders.pairOf(frame))

                        Type.TAP -> if (paired) dispatchControl { svc ->
                            val (x, y) = Decoders.tap(frame)
                            svc.tap(x, y)
                        }

                        Type.SWIPE -> if (paired) dispatchControl { svc ->
                            val a = Decoders.swipe(frame)
                            svc.swipe(a[0], a[1], a[2], a[3], a[4].toInt())
                        }

                        Type.TEXT -> if (paired) dispatchControl { svc ->
                            svc.sendText(Decoders.textOf(frame))
                        }

                        Type.KEY -> if (paired) dispatchControl { svc ->
                            svc.key(Decoders.keyOf(frame))
                        }

                        Type.FILE_LIST_REQ -> if (paired) handleFileList(Decoders.pathOf(frame))
                        Type.FILE_GET_REQ -> if (paired) handleFileGet(Decoders.pathOf(frame))
                        Type.FILE_PUSH_REQ -> if (paired) {
                            val (total, path) = Decoders.filePushReqOf(frame)
                            handleFilePushReq(total, path)
                        }
                        Type.FILE_PUSH_DATA -> if (paired) handleFilePushData(Decoders.filePushDataOf(frame))

                        // 剪贴板同步：接收端 -> 手机（写入手机剪贴板）
                        Type.CLIPBOARD -> if (paired) handleClipboardSync(Decoders.clipboardOf(frame))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "readLoop 连接异常: ${e.message}")
                // 连接异常，走 finally
            } finally {
                close()
            }
        }

        private fun handlePair(code: String) {
            val host = socket.inetAddress?.hostAddress
            // 白名单门控：被禁止的设备直接拒绝并断开，不进入配对流程
            if (host != null && !isReceiverAllowed(host)) {
                Log.w(TAG, "接收端 $host 不在白名单，已拒绝连接")
                sendReliable(Proto.reject("发送端未允许本机连接（请在手机端「连接管理」中勾选）"))
                mainHandler.post { close() }
                return
            }
            if (code.isEmpty()) {
                // 免密直连请求：仅当网络内没有其他发送端时接受
                if (autoPairAllowed()) {
                    paired = true
                    sendReliable(Proto.pairResult(true))
                    onPaired(this)
                    Log.i(TAG, "免密直连已接受（网络内唯一发送端）")
                } else {
                    sendReliable(Proto.pairResult(false))
                    Log.w(TAG, "免密直连被拒绝：网络内存在多个发送端")
                }
                return
            }
            if (code == pairCode) {
                paired = true
                sendReliable(Proto.pairResult(true))
                onPaired(this)
            } else {
                pairFails++
                sendReliable(Proto.pairResult(false))
                Log.w(TAG, "配对失败（第 $pairFails 次）来自 ${socket.inetAddress?.hostAddress}")
                if (pairFails >= MAX_PAIR_FAILS) {
                    Log.w(TAG, "连续配对失败 $pairFails 次，断开连接")
                    close()
                }
            }
        }

        // ---------- 文件 ----------

        private fun handleFileList(path: String) {
            val dir = FileRelay.resolveDir(this@CastService, path)
            if (dir == null) {
                sendReliable(Proto.fileError("路径不可访问或未授权：$path"))
                return
            }
            val entries = FileRelay.listDir(dir)
            sendReliable(Proto.fileListRes(entries))
        }

        private fun handleFileGet(path: String) {
            val file = FileRelay.resolveFile(this@CastService, path)
            if (file == null) {
                sendReliable(Proto.fileError("文件不可访问或未授权：$path"))
                return
            }
            try {
                val total = file.length()
                FileInputStream(file).use { input ->
                    val buf = ByteArray(64 * 1024)
                    var offset = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) {
                            // 读毕补一个空 eof 分片，确保接收端能收尾
                            sendReliable(Proto.fileData(offset, total, true, ByteArray(0)))
                            break
                        }
                        val eof = offset + n >= total
                        sendReliable(Proto.fileData(offset, total, eof, buf.copyOf(n)))
                        offset += n
                        if (eof) break
                    }
                }
                Log.i(TAG, "文件发送完成: $path ($total bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "文件发送失败: $path - ${e.message}")
                runCatching { sendReliable(Proto.fileError("读取失败：${e.message}")) }
            }
        }

        // ---------- 文件推送（接收端 -> 本机存储） ----------

        private fun handleFilePushReq(total: Long, path: String) {
            runCatching { pushOs?.close() }
            pushOs = null
            pushTarget = null
            val target = FileRelay.resolveWritable(this@CastService, path)
            if (target == null) {
                sendReliable(Proto.filePushRes(false, "路径不可写或未授权：$path"))
                return
            }
            try {
                pushTarget = target
                pushOs = java.io.FileOutputStream(target)
                Log.i(TAG, "接收端推送文件到：${target.absolutePath}（共 $total 字节）")
            } catch (e: Exception) {
                pushOs = null
                pushTarget = null
                Log.e(TAG, "创建推送文件失败: $path - ${e.message}")
                sendReliable(Proto.filePushRes(false, "无法创建文件：${e.message}"))
            }
        }

        private fun handleFilePushData(chunk: FilePushChunk) {
            val os = pushOs ?: return // 没有进行中的推送请求，忽略
            try {
                os.write(chunk.chunk)
                if (chunk.eof) {
                    runCatching { os.close() }
                    pushOs = null
                    val path = pushTarget?.absolutePath ?: ""
                    pushTarget = null
                    Log.i(TAG, "接收端推送文件接收完成：$path")
                    sendReliable(Proto.filePushRes(true, path))
                }
            } catch (e: Exception) {
                runCatching { os.close() }
                pushOs = null
                pushTarget = null
                Log.e(TAG, "写入推送文件失败: ${e.message}")
                sendReliable(Proto.filePushRes(false, "写入失败：${e.message}"))
            }
        }

        /** 控制帧统一出口：无障碍服务未就绪时限流打印，避免接收端高频触摸刷爆日志。 */
        private inline fun dispatchControl(action: (QpmControlService) -> Unit) {
            val control = QpmControlService.instance
            if (control == null) {
                val now = System.currentTimeMillis()
                if (now - lastA11yNullLog > 5000) {
                    lastA11yNullLog = now
                    Log.w(TAG, "无障碍服务未连接，控制指令被丢弃（手机端未开启「QPM 控制服务」）")
                }
                return
            }
            runCatching { action(control) }.onFailure {
                Log.e(TAG, "控制指令执行失败: ${it.message}")
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { pushOs?.close() }
            runCatching { socket.close() }
            onClose(this)
        }
    }
}

/** 发送端文件访问：路径校验 + 目录列表。仅允许共享存储根目录内的路径。 */
internal object FileRelay {
    private const val TAG = "FileRelay"
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** 允许访问的根目录（canonical 路径前缀）。 */
    private fun allowedRoots(): List<String> {
        val roots = mutableListOf<String>()
        runCatching {
            roots.add(Environment.getExternalStorageDirectory().canonicalPath)
        }
        // 部分机型 /sdcard 与上面不同，补一个软链目标
        runCatching {
            roots.add(File("/sdcard").canonicalPath)
        }
        return roots.distinct()
    }

    fun hasStorageAccess(ctx: android.content.Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** 把请求路径解析成真实目录；越权/不存在/未授权返回 null。 */
    fun resolveDir(ctx: android.content.Context, path: String): File? {
        if (!hasStorageAccess(ctx)) return null
        val f = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!f.isDirectory) return null
        val cp = f.canonicalPath
        return if (allowedRoots().any { cp == it || cp.startsWith("$it/") }) f else null
    }

    fun resolveFile(ctx: android.content.Context, path: String): File? {
        if (!hasStorageAccess(ctx)) return null
        val f = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!f.isFile) return null
        val cp = f.canonicalPath
        return if (allowedRoots().any { cp == it || cp.startsWith("$it/") }) f else null
    }

    /**
     * 文件推送写入目标：把接收端给的路径解析成本机共享存储内的可写文件。
     * - 路径落在根目录内且父目录可建 -> 直接用；
     * - 否则退化为「根目录 + 原文件名」，避免越权写出到系统目录。
     */
    fun resolveWritable(ctx: android.content.Context, path: String): File? {
        if (!hasStorageAccess(ctx)) return null
        val name = path.substringAfterLast('/').ifBlank { "qpm_push.bin" }
        val rooted = runCatching {
            val f = File(path).canonicalFile
            val cp = f.canonicalPath
            if (allowedRoots().any { cp == it || cp.startsWith("$it/") }) f else null
        }.getOrNull()
        val target = rooted ?: File(Environment.getExternalStorageDirectory(), name)
        return runCatching {
            target.parentFile?.mkdirs()
            target
        }.getOrNull()
    }

    /** 返回条目行：name\tisDir\tsize\tmtime；目录在前按名排序。 */
    fun listDir(dir: File): List<String> {
        val children = dir.listFiles() ?: return emptyList()
        val fmt = { f: File ->
            val size = if (f.isDirectory) 0L else f.length()
            "${f.name}\t${if (f.isDirectory) 1 else 0}\t$size\t${dateFmt.format(Date(f.lastModified()))}"
        }
        return children
            .filter { it.canRead() }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map(fmt)
    }
}
