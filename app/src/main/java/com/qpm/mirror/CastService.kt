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
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
import com.qpm.mirror.rtc.QpmRtcLog
import com.qpm.mirror.rtc.RtcSession
import com.qpm.mirror.rtc.ScreenCapture
import java.io.BufferedInputStream
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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

        /**
         * 编码长边上限：全采全编会吃满 VENC/GPU 并热降频，所以降采样；但 1280 对
         * 1440×3200 只有 0.4×（576×1280），手机 UI 小字在接收端明显发糊。
         * 1920 是清晰度与编码压力的折中（竖屏 864×1920，0.6×）。
         */
        private const val MAX_VIDEO_EDGE = 1920
        /** 视频帧在队列里超过这个时长就不再写入 socket（TCP 写进去就撤不回来，只会堆延迟）。 */
        private const val MAX_FRAME_AGE_MS = 150L

        /** 控制帧队列深度：STATS/PONG 是每秒级，64 条足够吸收一次大帧写阻塞。 */
        private const val CTRL_QUEUE_DEPTH = 64

        /**
         * socket 发送缓冲上限。默认值常有几百 KB，拥塞时内核里排队的旧帧全部「撤不回」，
         * 延迟会单调累积；限到 64KB 相当于给不可撤回的延迟加一道硬上限。
         */
        private const val SOCKET_SEND_BUFFER = 64 * 1024

        /** 发送端 wire 帧池槽数：足够覆盖队列深度 3 × 常见接收端数量，耗尽时退回普通分配。 */
        private const val FRAME_POOL_SLOTS = 8

        /**
         * 自适应码率档位（bps）；上限由用户在画质档里选定，只在此范围内升降。
         * 低位几档是给「Wi-Fi 实际吞吐只有几百 kbps」准备的：1920 长边 + 6Mbps 目标
         * 一旦遇到这种链路，一个 IDR 就要几 MB 秒才能发完，必须能降到 0.6Mbps 才活得下来。
         */
        private val BITRATE_STEPS = intArrayOf(600_000, 1_000_000, 2_000_000, 4_000_000, 6_000_000, 10_000_000)

        /** 防息屏原值的持久化键与兜底值（原值未知时恢复成 1 分钟）。 */
        private const val KEY_ORIGINAL_TIMEOUT = "original_screen_off_timeout"
        private const val DEFAULT_TIMEOUT_MS = 60_000

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
            QpmLog.i(TAG, "status: $s")
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
        // 只发给已配对接收端：未配对连接不应收到剪贴板内容
        instanceRef?.clients?.forEach { if (it.paired) it.sendControl(frame) }
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
                QpmLog.i(TAG, "已禁止接收端 $host，白名单：$allowedHosts")
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

        /**
         * 编码尺寸：长边钳到 [MAX_VIDEO_EDGE]、取偶数，dpi 同比例缩放，保持逻辑 dp 不变。
         * 不做 16 对齐：短边独立对齐会改变宽高比（1080x2340 会偏 2.5%），触摸映射随之
         * 系统性偏移；MediaCodec 支持非 16 倍数尺寸（旧版本来就按 1080 宽编码），内部
         * 会自行 pad + crop。SCREEN_INFO 仍上报真实屏幕尺寸，等比缩放后点击不会错位。
         * 未超限时原样返回。
         */
        private fun scaledVideoSize(w: Int, h: Int, dpi: Int): Triple<Int, Int, Int> {
            val scale = minOf(1f, MAX_VIDEO_EDGE.toFloat() / maxOf(w, h))
            if (scale >= 1f) return Triple(w, h, dpi)
            val sw = ((w * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val sh = ((h * scale).toInt() / 2 * 2).coerceAtLeast(2)
            return Triple(sw, sh, (dpi * scale).toInt().coerceAtLeast(1))
        }

        @Volatile
        private var instanceRef: CastService? = null
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: H264Encoder? = null
    /**
     * WebRTC 版全进程唯一的屏幕采集管线（采集器 + VideoSource + VideoTrack）。
     * 服务启动时建一次并一直跑，所有接收端连接共用同一条轨道——同一个 MediaProjection
     * 授权只允许 createVirtualDisplay 一次，第二个连接再建采集器必崩（详见 ScreenCapture）。
     */
    private var capture: ScreenCapture? = null
    private var server: ServerSocket? = null
    private var broadcastThread: Thread? = null
    private var peerWatchThread: Thread? = null
    private var running = false

    private var pairCode: String = ""
    private var keepAwake: Boolean = true
    private var currentBitrate: Int = DEFAULT_BITRATE
    /** 用户选定的画质档位，作为自适应码率的上限；currentBitrate 是运行时动态值。 */
    private var baseBitrate: Int = DEFAULT_BITRATE
    private var adaptiveIndex: Int = 2
    private var goodStreak: Int = 0
    private var lastAdjustAt: Long = 0L

    /** 写线程因帧过期直接降档的节流时间戳（见 stepDownBitrate）。 */
    private var lastStepDownAt: Long = 0L
    private val framePool = WireFramePool(FRAME_POOL_SLOTS)
    private var prevScreenOffTimeout: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 投屏统计计数（每秒快照并清零，通过 STATS 帧广播给接收端）。 */
    private val statFrames = AtomicLong(0)
    private val statBytes = AtomicLong(0)
    private val statDropped = AtomicLong(0)
    private var statsThread: Thread? = null

    /** 当前虚拟显示/编码器尺寸（降采样后的实际码流尺寸，旋转时对比用）。 */
    @Volatile private var videoW: Int = 0
    @Volatile private var videoH: Int = 0
    @Volatile private var videoDpi: Int = 0

    /** 文件下载走独立单线程池：读盘/分片发送不再卡住 readLoop 的控制帧，且全局串行避免分片交错。 */
    private val fileExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "QpmFileRelay").apply { isDaemon = true }
    }
    private var orientationListener: OrientationEventListener? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastRotationBucket: Int = -1

    /** 重建进行中标记：显示监听与传感器可能在同一瞬间各触发一次，避免并行建出两套编码器。 */
    @Volatile
    private var reconfiguring: Boolean = false

    /** 上次重建失败的目标尺寸（w*10000+h）：同一尺寸不再反复尝试，避免每次显示事件都闪一下。 */
    private var lastFailedSize: Int = 0

    /**
     * 编码器代际计数器：每新建一个编码器分配一个唯一值。
     *
     * 旋转切换时旧编码器的异步回调（onError/onFrame）可能晚于新编码器建成才触发——
     * 一个迟到的 onError 会 post stopAll() 把刚建好的新流水线拆掉。因此回调先比对
     * 自己的代际是否等于 [activeGen]，不等于就丢弃。
     */
    private val encoderGen = AtomicLong(0)

    /**
     * 当前「在职」的编码器代际，-1 表示没有。
     *
     * 与 [encoderGen] 分开是必须的：新编码器建好但换 surface 失败回滚时，
     * 旧编码器仍然有效——如果只用一个自增计数器，旧编码器的帧会因代际不匹配被
     * 永久丢弃（表现为回滚后黑屏）。
     */
    @Volatile
    private var activeGen: Long = -1

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
        reconfiguring = false
        lastFailedSize = 0
        currentPairCode = pairCode
        currentBitrate = bitrate
        baseBitrate = bitrate
        adaptiveIndex = BITRATE_STEPS.indexOfLast { it <= bitrate }.coerceAtLeast(0)
        goodStreak = 0
        lastAdjustAt = 0L
        lastPeerSeen.set(0L)
        // 每次重新开始投屏都重置白名单与 beacon 缓存，避免上一轮的踢人名单残留
        allowedHosts.clear()
        receiverBeacons.clear()

        val metrics = realMetrics()
        val (vw, vh, vdpi) = scaledVideoSize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        videoW = vw
        videoH = vh
        videoDpi = vdpi
        QpmLog.i(TAG, "屏幕 ${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}dpi -> 编码 ${vw}x${vh}@${vdpi}dpi")

        // WebRTC 采集：采集器**只建这一次**（Android 14+ 同一个授权 token 只能
        // createVirtualDisplay 一次）。启动时就建、一直出帧，后续每个接收端连接只是把
        // 同一条 VideoTrack 挂到自己的 PeerConnection 上，永远不会二次建虚拟显示。
        try {
            capture = ScreenCapture.start(
                context = this,
                projectionData = data,
                width = videoW,
                height = videoH,
                fps = 30,
                onProjectionStop = {
                    // 系统撤销授权（通知栏停止/被别的投屏抢占）：投屏已无法继续
                    QpmLog.w(TAG, "MediaProjection onStop：授权被系统撤销")
                    postStatus("录屏授权被系统撤销，投屏已停止")
                    mainHandler.post { stopAll() }
                },
                onContentResize = { w, h ->
                    // 必须切到主线程再改采集格式：ScreenCapturerAndroid 内部把 resize 派到
                    // 采集线程执行，从采集线程回调里再调会自锁。
                    val (sw, sh, _) = scaledVideoSize(w, h, metrics.densityDpi)
                    mainHandler.post {
                        val cap = capture
                        if (cap != null && sw > 0 && sh > 0 && (sw != cap.width || sh != cap.height)) {
                            cap.resizeTo(sw, sh, 30)
                            QpmLog.i(TAG, "采集内容 ${w}x$h，采集尺寸切到 ${sw}x$sh")
                        }
                        broadcastScreenInfo()
                        broadcastThread?.interrupt()
                        broadcastThread = Thread { Discovery.broadcastLoop(TCP_PORT, w, h) }.apply { start() }
                    }
                }
            )
        } catch (e: Throwable) {
            QpmLog.e(TAG, "屏幕采集启动失败：${e.message}", e)
            postStatus("录屏采集启动失败（授权已失效），请停止后重新开始投屏")
            stopAll()
            return
        }
        QpmLog.i(TAG, "WebRTC 采集已就绪（${videoW}x${videoH}），等待接收端配对")

        applyKeepAwake()
        postStatus("投屏中，配对码 $pairCode（仅本机在网时接收端可免码直连）")
        Thread { acceptLoop() }.start()
        // beacon 里报真实屏幕尺寸（接收端列表展示用），不是降采样后的码流尺寸
        broadcastThread = Thread {
            Discovery.broadcastLoop(TCP_PORT, metrics.widthPixels, metrics.heightPixels)
        }.apply { start() }
        peerWatchThread = Thread { peerWatchLoop() }.apply { start() }
        startRotationWatch()
    }

    /**
     * 按当前尺寸建编码器：onFrame 把帧塞进全部客户端的发送队列。
     * [gen] 由调用方分配、成功接管后写进 [activeGen]——只有在职代际的帧/错误才处理。
     */
    private fun newEncoder(w: Int, h: Int, gen: Long): H264Encoder {
        return H264Encoder(w, h, currentBitrate, onFrame = { buf, size, flags, pts ->
            if (gen == activeGen) {
                // 没有已配对接收端时连帧都不组：省掉每帧的大数组分配，手机等人配对时不再空转发热
                val targets = clients.filter { it.paired && !it.isClosed }
                if (targets.isNotEmpty()) {
                    statFrames.incrementAndGet()
                    statBytes.addAndGet(size.toLong())
                    // SPS/PPS 与 IDR 丢了会花屏整整一个 GOP（1s），标记成不可丢
                    val critical = flags and (H264Encoder.FLAG_KEY or H264Encoder.FLAG_CONFIG) != 0
                    val sentAt = SystemClock.elapsedRealtime().toInt()
                    val need = Proto.videoFrameSize(size)
                    val slot = framePool.acquire(need)
                    if (slot != null) {
                        // 编码器输出缓冲在 onFrame 返回后会被复用，这里同步写进池槽；
                        // 槽内引用计数由 offer()/writeLoop() 维护，回调返回时释放自己那份。
                        // 用 finally 保证即使组帧异常也不把槽永久占住（否则池会慢慢耗尽）。
                        try {
                            val written = Proto.videoFrameInto(slot.bytes, pts, flags, sentAt, buf, 0, size)
                            if (written == need) {
                                targets.forEach {
                                    if (!it.offer(slot.bytes, written, critical, slot)) statDropped.incrementAndGet()
                                }
                            }
                        } finally {
                            slot.release()
                        }
                    } else {
                        // 池耗尽（多接收端 + 写阻塞）：退回普通分配，宁可多一次 GC 也不丢帧
                        val wire = Proto.videoFrame(pts, flags, sentAt, buf, 0, size)
                        targets.forEach {
                            if (!it.offer(wire, wire.size, critical, null)) statDropped.incrementAndGet()
                        }
                    }
                }
            }
        }, onError = { msg ->
            if (gen == activeGen) {
                QpmLog.e(TAG, "编码器错误: $msg")
                postStatus("编码器错误：$msg，投屏已停止")
                // 不能在本线程直接调 codec.stop()（MediaCodec 回调内调用会崩溃），切主线程执行
                mainHandler.post { stopAll() }
            } else {
                // 旧编码器（已被重建停掉）的迟到错误：只记日志，绝不能 stopAll 掉新流水线
                QpmLog.w(TAG, "旧编码器（代际 $gen）错误忽略：$msg")
            }
        })
    }

    /**
     * 屏幕方向监听：横竖屏切换后 VirtualDisplay/编码器尺寸与真实屏幕不符
     * （AUTO_MIRROR 只镜像内容不缩放，尺寸不变画面会被挤成中间一条/压扁），
     * 按新尺寸重建显示与编码器，并重发 SCREEN_INFO 让接收端重排画面与触摸映射。
     *
     * 两路信号都要挂，任一路到就核对一次尺寸：
     *   ① DisplayManager.DisplayListener —— 显示配置真的变了（旋转、分辨率变化）才回调，最权威。
     *      视频/游戏 App 用 setRequestedOrientation 强制横屏、手机本身没转（或开着旋转锁定、
     *      竖着拿手机看横屏视频）时，只有它能感知到 —— 只挂传感器会漏掉这种最常见的场景。
     *   ② OrientationEventListener —— 传感器角度跨过 90° 分桶时触发，覆盖个别 ROM 的
     *      DisplayListener 不回调的情况；用分桶防抖，小角度抖动不触发。
     * 两路都只是「提醒」，真正的判断是 realMetrics() 与当前编码尺寸比对，避免空转重建。
     */
    private fun startRotationWatch() {
        stopRotationWatch()
        lastRotationBucket = -1

        val dm = getSystemService(DisplayManager::class.java)
        val dl = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                scheduleReconfigure()
            }
        }
        runCatching { dm.registerDisplayListener(dl, mainHandler) }
            .onFailure { QpmLog.w(TAG, "注册显示监听失败：${it.message}") }
        displayListener = dl

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (!running || orientation == ORIENTATION_UNKNOWN) return
                val bucket = ((orientation + 45) / 90) % 4
                if (bucket == lastRotationBucket) return
                lastRotationBucket = bucket
                scheduleReconfigure()
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    /** 显示配置变化后度量值可能晚一拍才更新，延迟 120ms 再核对（多次触发合并成一次）。 */
    private fun scheduleReconfigure() {
        mainHandler.removeCallbacks(reconfigTask)
        mainHandler.postDelayed(reconfigTask, 120)
    }

    private val reconfigTask = Runnable { reconfigureForRotation() }

    private fun stopRotationWatch() {
        runCatching {
            displayListener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        }
        displayListener = null
        runCatching { orientationListener?.disable() }
        orientationListener = null
        mainHandler.removeCallbacks(reconfigTask)
    }

    /**
     * 核对真实屏幕尺寸，不一致就把画面切到新尺寸（复用虚拟显示，见 rebuildPipeline），
     * 并广播新的 SCREEN_INFO 让接收端重排画面与触摸映射。
     */
    private fun reconfigureForRotation() {
        if (!running || reconfiguring) return
        // WebRTC 版：旋转由 ScreenCapturerAndroid 的 onCapturedContentResize 处理
        // （changeCaptureFormat + 重发 SCREEN_INFO）。旧的「重建编码器 + VirtualDisplay」
        // 路径依赖 encoder/display，二者在 WebRTC 版恒为 null，走进去只会 fail 并弹错误提示。
        return
    }

    @Suppress("unused")
    private fun reconfigureForRotationLegacy() {
        val m = realMetrics()
        val w = m.widthPixels
        val h = m.heightPixels
        if (w <= 0 || h <= 0) return
        // 比对降采样后的编码尺寸，不是真实屏幕尺寸（两者现在不相等，直接比会每次都误触发重建）
        val (tw, th, tdpi) = scaledVideoSize(w, h, m.densityDpi)
        if (tw == videoW && th == videoH) return
        // 同一个尺寸刚失败过就别再拆了重试一次了，否则每次显示事件都会黑一下
        val sizeKey = tw * 10000 + th
        if (sizeKey == lastFailedSize) return
        reconfiguring = true
        val oldW = videoW
        val oldH = videoH
        val oldDpi = videoDpi
        try {
            QpmLog.i(TAG, "屏幕尺寸变化：${oldW}x$oldH -> ${tw}x$th，切换编码器尺寸")
            postStatus("屏幕方向变化，正在按 ${w}x$h 调整画面…")
            if (rebuildPipeline(tw, th, tdpi)) {
                lastFailedSize = 0
                postStatus(if (w > h) "已切换为横屏 ${w}x$h" else "已切换为竖屏 ${w}x$h")
                // SCREEN_INFO 已由 rebuildPipeline 在放行新码流之前播报（顺序敏感，勿在此重发）
                // beacon 里带着屏幕尺寸，换块线程重新广播
                broadcastThread?.interrupt()
                broadcastThread = Thread { Discovery.broadcastLoop(TCP_PORT, w, h) }.apply { start() }
                return
            }
            lastFailedSize = sizeKey
            // 新实现里失败只可能发生在「新编码器建不出来」或「换 surface 失败且已回滚」，
            // 此时旧流水线仍然完好——直接继续投屏，不要再去重建（旧实现这里会因重复
            // createVirtualDisplay 把整个录屏授权搞挂，最终必然 stopAll 断流）
            if (oldW == videoW && oldH == videoH && encoder != null && display != null) {
                QpmLog.w(TAG, "按 ${w}x$h 切换失败，旧流水线仍在，继续以 ${oldW}x$oldH 投屏")
                postStatus("按 ${w}x$h 调整失败（该尺寸可能不受本机编码器支持），继续以 ${oldW}x$oldH 投屏")
                return
            }
            // 旧流水线也坏了：按旧尺寸再试一次，仍不行才停
            QpmLog.w(TAG, "旧流水线已不可用，尝试按 ${oldW}x$oldH 恢复")
            if (oldW > 0 && oldH > 0 && rebuildPipeline(oldW, oldH, oldDpi)) {
                postStatus("按 ${w}x$h 调整失败，已恢复 ${oldW}x$oldH 继续投屏")
                return
            }
            postStatus("画面重建失败，投屏已停止")
            stopAll()
        } finally {
            reconfiguring = false
        }
    }

    /**
     * 把画面切到新尺寸：**复用同一个 VirtualDisplay**，只换编码器输入 surface 并 resize。
     *
     * 关键约束：MediaProjection 的令牌一生只能 createVirtualDisplay 一次。Android 14 起
     * 第二次调用直接抛「Don't take multiple captures by invoking MediaProjection#
     * createVirtualDisplay multiple times on the same instance」，并连带撤销整个录屏授权
     * （日志里紧随其后的「Cannot create VirtualDisplay with non-current MediaProjection」
     * 就是授权已被系统作废）。旧实现「release 旧显示 → 再建新的」正是「一转屏投屏就断」
     * 的真正根因，与编码器重建成败无关。
     *
     * 正确做法（也是 Android 14 官方推荐）：
     *   ① 新建编码器（编码器的输入 surface 尺寸在 configure 时固定，无法原地改）；
     *   ② 把新 surface 挂到既有虚拟显示上（setSurface），再改采集分辨率（resize）；
     *   ③ 全部成功后才停旧编码器，失败则把旧 surface 挂回去保住旧流水线。
     * 旧编码器的输入 surface 被摘下时可能回调 onError，由 encoderGen 代际守卫丢弃。
     */
    private fun rebuildPipeline(w: Int, h: Int, dpi: Int): Boolean {
        val vd = display
        val old = encoder
        if (vd == null || old == null) {
            QpmLog.e(TAG, "切换 ${w}x$h 失败：虚拟显示或编码器不存在")
            return false
        }

        // ① 新编码器：其输入 surface 就绪后才能挂到虚拟显示上
        val gen = encoderGen.incrementAndGet()
        val enc = try {
            newEncoder(w, h, gen)
        } catch (e: Exception) {
            QpmLog.e(TAG, "创建 ${w}x$h 编码器失败: ${e.message}")
            return false
        }

        // ② 复用虚拟显示：换 surface + resize（绝不再 createVirtualDisplay）
        val switched = runCatching {
            vd.setSurface(enc.inputSurface)
            vd.resize(w, h, dpi)
        }.onFailure { QpmLog.e(TAG, "复用虚拟显示失败（${w}x$h）: ${it.message}") }.isSuccess
        if (!switched) {
            runCatching { enc.stop() }
            // 尽力把旧编码器的 surface 挂回去，宁可比例不对也别断流
            runCatching { vd.setSurface(old.inputSurface) }
                .onFailure { QpmLog.w(TAG, "旧 surface 回挂失败: ${it.message}") }
            return false
        }

        // ③ 先播报新尺寸、再放行新码流：接收端收到 SCREEN_INFO 会按新尺寸重建解码器，
        //    若让新尺寸的帧抢先到达，旧尺寸解码器会吃到尺寸不符的码流（部分机型硬解直接崩）。
        //    activeGen 在这之后才更新，保证「SCREEN_INFO 一定先于新尺寸帧」写入 socket。
        // 前三个字段是真实屏幕尺寸（接收端 TouchMapper 按它换算点击坐标），后两个是
        // 降采样后的码流尺寸（接收端按它 configure 解码器）——两者现在不再相等。
        val m = realMetrics()
        val info = Proto.screenInfo(m.widthPixels, m.heightPixels, m.densityDpi, w, h)
        clients.forEach { it.sendControl(info) }

        // ④ 新流水线已接管，停旧编码器（迟到的回调由代际守卫丢弃）
        runCatching { old.stop() }
        encoder = enc
        activeGen = gen
        videoW = w
        videoH = h
        videoDpi = dpi
        QpmLog.i(TAG, "画面已切换到 ${w}x$h（复用同一虚拟显示）")
        return true
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
                // 快照与广播整体兜底：任何异常只记日志，绝不让统计线程裸奔杀进程
                try {
                    val frames = statFrames.getAndSet(0)
                    val bytes = statBytes.getAndSet(0)
                    val dropped = statDropped.getAndSet(0)
                    val bitrate = (bytes * 8).toInt() // bits per second
                    // 统计浮窗显示实际推流尺寸（降采样后），不再是手机物理分辨率
                    val stats = Proto.stats(frames.toInt(), bitrate, videoW, videoH, dropped.toInt())
                    clients.forEach { if (it.paired) it.sendControl(stats) }
                } catch (e: Exception) {
                    QpmLog.w(TAG, "统计广播异常: ${e.message}")
                }
            }
        }.apply { start() }
    }

    /** 监听是否有其他 QPM 发送端/接收端在网：前者决定免密直连，后者供连接管理列表。 */
    private fun peerWatchLoop() {
        var lastSig = ""
        while (running && !Thread.currentThread().isInterrupted) {
            val (peers, receivers) = runCatching { Discovery.scanAll(1200) }.getOrDefault(emptyList<Discovery.Device>() to emptyList())
            receivers.forEach {
                if (it.name.isNotBlank()) receiverBeacons[it.host] = it.name
            }
            // 只在列表真的变化时才刷 UI：旧实现只要 beacon 在就每 1.2s 全量
            // removeAllViews + inflate + 挂监听，主线程周期性掉帧
            val sig = receivers().joinToString(";") { "${it.host}:${it.name}:${it.connected}" }
            if (sig != lastSig) {
                lastSig = sig
                notifyReceiversChanged()
            }
            // 排除本机自身 beacon（部分设备 UDP 广播会回环），否则会误判「有其他发送端」而永久关闭免密直连
            val others = peers.filter { !isLocalAddress(it.host) }
            if (others.isNotEmpty()) {
                if (lastPeerSeen.get() == 0L) {
                    QpmLog.i(TAG, "检测到 ${others.size} 个其他发送端在网，免密直连已关闭")
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
                val socket = server!!.accept().apply {
                    tcpNoDelay = true
                    // 限制内核里排队、已不可撤回的字节量：拥塞时不再把几百 KB 旧帧堆进 TCP
                    runCatching { sendBufferSize = SOCKET_SEND_BUFFER }
                }
                val client = Client(socket, onPaired = { c ->
                    val m = realMetrics()
                    // 尺寸帧必须走可靠通道同步写出，不能进深度 3 的有界视频队列：
                    // 配对后 requestKeyFrame 立刻产出 SPS/PPS+IDR（都是 critical），
                    // 会把队列里的 SCREEN_INFO 挤掉。接收端拿不到码流尺寸就会拿
                    // 初始 1920x1080 解码器去解降采样码流，部分机型 native 直接崩。
                    // 这里同步写完再请求关键帧，保证 SCREEN_INFO 一定先于首帧视频。
                    c.sendReliable(
                        Proto.screenInfo(m.widthPixels, m.heightPixels, m.densityDpi, videoW, videoH)
                    )
                    encoder?.requestKeyFrame()
                    postStatus("接收端 ${c.displayName()} 已配对并开始接收画面")
                    notifyReceiversChanged()
                }, onClose = { notifyReceiversChanged() })
                clients.add(client)
                QpmLog.i(TAG, "新客户端接入 ${socket.inetAddress?.hostAddress}，等待配对")
            }
        } catch (e: Exception) {
            QpmLog.e(TAG, "acceptLoop 退出: ${e.message}")
            postStatus("服务异常：${e.message ?: "TCP 监听失败（端口可能被占用）"}，请停止后重试")
        }
    }

    /**
     * 原始息屏超时要单独持久化：曾出现上次异常退出没恢复、系统值一直停在哨兵
     * Int.MAX_VALUE，下次启动把哨兵当「用户原值」记录下来并原样写回，用户的手机
     * 从此永不自动熄屏（日志表现为「已恢复自动息屏时间：2147483647s」）。
     */
    private val timeoutPrefs by lazy {
        getSharedPreferences("qpm_screen_timeout", Context.MODE_PRIVATE)
    }

    /** 向所有已配对接收端广播屏幕尺寸（旋转/分屏后调用）。 */
    private fun broadcastScreenInfo() {
        val m = realMetrics()
        val cap = capture
        val cw = cap?.width ?: videoW
        val ch = cap?.height ?: videoH
        videoW = cw
        videoH = ch
        val info = Proto.screenInfo(m.widthPixels, m.heightPixels, m.densityDpi, cw, ch)
        // 走可丢队列：本方法常在主线程调用（旋转回调），sendReliable 在文件传输把队列占满时会阻塞主线程
        clients.forEach { if (it.paired) it.sendControl(info) }
        QpmLog.i(TAG, "已广播 SCREEN_INFO：屏幕 ${m.widthPixels}x${m.heightPixels} 采集 ${cw}x$ch")
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
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT)
            // 只有读到正常值才记录为原值；读到哨兵说明是历史遗留，保留之前存下的原值
            if (cur != Int.MAX_VALUE) {
                timeoutPrefs.edit().putInt(KEY_ORIGINAL_TIMEOUT, cur).apply()
            }
            prevScreenOffTimeout = timeoutPrefs.getInt(KEY_ORIGINAL_TIMEOUT, -1).takeIf { it > 0 }
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
            QpmLog.i(TAG, "已临时禁用自动息屏（原值 ${prevScreenOffTimeout ?: "未知"}ms）")
        }.onFailure {
            postStatus("防息屏设置失败：${it.message}")
        }
    }

    private fun restoreScreenOffTimeout() {
        val saved = prevScreenOffTimeout
            ?: timeoutPrefs.getInt(KEY_ORIGINAL_TIMEOUT, -1).takeIf { it > 0 }
        prevScreenOffTimeout = null
        val cur = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrDefault(-1)
        // 原值未知且系统仍停在哨兵值：历史版本异常退出留下的，恢复成 1 分钟兜底，
        // 否则用户手机会永远不自动熄屏
        val target = saved ?: if (cur == Int.MAX_VALUE) DEFAULT_TIMEOUT_MS else null
        if (target == null || target == cur) return
        runCatching {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, target)
            timeoutPrefs.edit().remove(KEY_ORIGINAL_TIMEOUT).apply()
            QpmLog.i(TAG, "已恢复自动息屏时间：${target}ms")
        }
    }

    private fun stopAll() {
        running = false
        reconfiguring = false
        // 作废在职代际：停止过程中旧编码器迟到的 onError/onFrame 不再触发任何动作
        activeGen = -1
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
        // 先停编码器再释放显示（同 rebuildPipeline：避免 abandon 输入 surface 触发 onError）
        encoder?.stop()
        encoder = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
        // 屏幕采集是进程级唯一资源，必须最后释放（等所有会话先拆完）
        runCatching { capture?.dispose() }
        capture = null
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

    /**
     * 接收端每秒回报一次 [decodedFps, backlogMs, dropped, rttMs]。
     * 拥塞就立刻降一档，连续 3 次良好才升一档，避免在阈值附近来回震荡；
     * 多接收端共用一条编码流，由 1s 节流兜住，任一接收端拥塞都会让整体降档。
     */
    private fun onReceiverFeedback(decodedFps: Int, backlogMs: Int, dropped: Int, rttMs: Int) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastAdjustAt < 1000) return
            lastAdjustAt = now
            val maxIndex = BITRATE_STEPS.indexOfLast { it <= baseBitrate }.coerceAtLeast(0)
            // decodedFps 低本身不等于拥塞：画面静止时编码器几乎不出帧，接收端自然只解出
            // 1~2 fps（码率也跌到几十 kb/s）。把它单独当拥塞信号会在静止画面里把码率
            // 一路降到地板，表现为「越看越糊」。真正能说明链路吃紧的是接收端上报的
            // 积压 / RTT / 丢帧，这三项在静止画面下都接近 0。
            val congested = backlogMs > 80 || rttMs > 120 || dropped > 4
            if (congested) {
                goodStreak = 0
                if (adaptiveIndex > 0) {
                    adaptiveIndex--
                    applyAdaptiveBitrate()
                }
            } else {
                goodStreak++
                if (goodStreak >= 3 && adaptiveIndex < maxIndex) {
                    adaptiveIndex++
                    goodStreak = 0
                    applyAdaptiveBitrate()
                }
            }
        }
    }

    /**
     * 写线程发现「帧在队列里放过期了」时直接降档：这类拥塞不需要等接收端回报，
     * 而且 FEEDBACK 本身也可能正被堵在大帧后面。节流 700ms，避免一次拥塞连降好几档。
     */
    private fun stepDownBitrate() {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastStepDownAt < 700) return
            lastStepDownAt = now
            goodStreak = 0
            if (adaptiveIndex > 0) {
                adaptiveIndex--
                applyAdaptiveBitrate()
                QpmLog.w(TAG, "链路拥塞，码率降到 ${currentBitrate / 1000}kbps")
            }
        }
    }

    private fun applyAdaptiveBitrate() {
        val bps = BITRATE_STEPS[adaptiveIndex]
        currentBitrate = bps
        // 运行时改 VENC 目标码率，不重建编码器（避免旋转重建那条高崩溃风险路径）
        runCatching { encoder?.setBitrate(bps) }
            .onFailure { QpmLog.w(TAG, "动态码率异常: ${it.message}") }
        QpmLog.i(TAG, "自适应码率 -> ${bps / 1_000_000}Mbps")
    }

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
                .onFailure { QpmLog.w(TAG, "写手机剪贴板失败: ${it.message}") }
            postStatus(getString(R.string.cast_clip_copied))
        }
    }

    /** 单个接收端连接：配对通过前视频/控制/文件全部门控。 */
    private inner class Client(
        private val socket: Socket,
        private val onPaired: (Client) -> Unit,
        private val onClose: (Client) -> Unit
    ) {
        private val queue = LinkedBlockingQueue<PendingFrame>(QUEUE_DEPTH)
        /**
         * 控制帧（PONG/STATS/配对结果/尺寸帧）专用队列：小、时效性强、必须优先于视频发出。
         * **绝不能直接在 readLoop 里写 socket**：一帧 200KB+ 的 IDR 卡在 64KB 发送缓冲里时，
         * write() 会阻塞数秒，readLoop 被一起拖住 → 收不到接收端的 FEEDBACK → 自适应码率
         * 永远不降档 → 大帧持续堵死链路，画面永久停在第一帧（v0.23 前的实际故障）。
         *
         * 分两条：`mustSend` 必须送达（配对结果/文件数据），满了就阻塞调用方（文件线程可接受）；
         * `droppable` 可丢（STATS/PONG/尺寸帧），满了丢最旧的，绝不拖住读线程。
         */
        private val mustSend = java.util.concurrent.LinkedBlockingQueue<ByteArray>(CTRL_QUEUE_DEPTH)
        private val droppable = java.util.concurrent.LinkedBlockingQueue<ByteArray>(CTRL_QUEUE_DEPTH)
        /**
         * 公平锁：控制帧与视频帧共用一把锁串行写 socket（加密计数器顺序必须与 wire 顺序一致），
         * 公平模式下等待中的控制帧不会被连续视频写无限插队。
         */
        private val writeLock = ReentrantLock(true)
        @Volatile private var closed = false

        /** 加密通道：本端为发送端，发出方向为 DIR_PHONE。握手前/失败回落时自动透传。 */
        private val channel = SecureChannel(Crypto.DIR_PHONE)

        /** 配对通过后才允许收视频/发控制/文件。 */
        @Volatile
        var paired = false
            private set

        val isClosed: Boolean get() = closed

        /** WebRTC 会话：配对成功后创建，SDP/ICE 经这条 TCP 辅助通道交换。 */
        @Volatile private var session: RtcSession? = null
        /** 采集器引用：旋转时调 changeCaptureFormat 改采集尺寸（会话关闭时由 RtcSession 一并 dispose）。 */

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

        /**
         * 队列满则丢最旧的**非关键帧**——SPS/PPS/IDR/尺寸帧丢了要花屏或错位一整个 GOP。
         * 返回是否无损入队；false 表示本次牺牲了一帧，供统计丢帧。
         * 池帧入队时引用 +1（由 writeLoop 写出后释放），被挤掉的旧帧引用 -1。
         */
        fun offer(wire: ByteArray, len: Int, critical: Boolean, slot: WireFramePool.Slot?): Boolean {
            if (closed || !paired) return false
            // 先 retain 再入队：writeLoop 若在 retain 之前就把这帧写完并 release 到 0，
            // 编码线程会立刻把槽当空闲复用、覆盖数组内容，写出去的就是半新半旧的拼接数据
            // （损坏的 H.264 NAL 会让接收端硬解在 native 层直接 abort，日志里只有 SIGABRT）。
            slot?.retain()
            val f = PendingFrame(wire, len, SystemClock.elapsedRealtime(), critical, slot)
            var lost = false
            if (!queue.offer(f)) {
                val victim = queue.firstOrNull { !it.critical } ?: queue.poll()
                // 只有真的从队列里摘掉才释放：writeLoop 可能已经 poll 走并释放过这一帧，
                // 重复释放会让仍在使用的槽被误判为空闲、写出中的帧被覆盖。
                if (victim != null && queue.remove(victim)) {
                    victim.slot?.release()
                }
                if (!queue.offer(f)) {
                    // 挤掉一帧后仍塞不进（writeLoop 正在取）：这一帧放弃，归还自己那份引用
                    slot?.release()
                    return false
                }
                lost = true
            }
            return !lost
        }

        /**
         * 可靠发送：配对结果 / 文件数据 / 错误提示等必须送达的帧。只入队不写 socket，
         * 队列满时阻塞调用方（文件下发线程可接受背压），绝不阻塞 readLoop。
         * 加密放在写线程做——计数器自增顺序必须与实际 wire 顺序一致。
         */
        fun sendReliable(bytes: ByteArray) {
            if (closed) return
            runCatching { mustSend.put(bytes) }
        }

        /**
         * 可丢的控制帧：STATS / PONG / 尺寸帧。队列满就丢掉最旧的，
         * 保证读线程和统计线程在任何拥塞下都不会被卡住（旧帧本来也没用了）。
         */
        fun sendControl(bytes: ByteArray) {
            if (closed) return
            if (!droppable.offer(bytes)) {
                droppable.poll()
                if (!droppable.offer(bytes)) return
            }
        }

        private fun writeLoop() {
            try {
                val out = socket.getOutputStream()
                while (!closed) {
                    // 先清可靠帧 / 控制帧：配对结果与尺寸帧必须抢在首帧视频之前到达，
                    // 而且它们小、时效性强，不能排在几百 KB 的视频帧后面等
                    val must = mustSend.poll()
                    if (must != null) {
                        writeLock.withLock { out.write(channel.seal(must)) }
                        continue
                    }
                    val ctrl = droppable.poll()
                    if (ctrl != null) {
                        writeLock.withLock { out.write(channel.seal(ctrl)) }
                        continue
                    }
                    // 用 poll 而非 take：close() 后若队列已空，take() 会永久挂起导致线程泄漏
                    val frame = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    // TCP 一旦写入就撤不回来：过期帧再发出去只会把延迟堆到后面每一帧上。
                    // 关键帧/尺寸帧例外——丢了要等下一个 IDR，花屏比多几十 ms 更难受。
                    val age = SystemClock.elapsedRealtime() - frame.atMs
                    if (age > MAX_FRAME_AGE_MS && !frame.critical) {
                        statDropped.incrementAndGet()
                        frame.slot?.release()
                        // 帧过期 = 链路带不动当前码率。这里绝不能去要关键帧：IDR 比 P 帧大得多，
                        // 只会把 64KB 发送缓冲堵得更死。旧版正是「丢帧 → 要 IDR → 更堵 → 再丢」
                        // 的死循环，表现为连上后画面停在第一帧、几秒后进程被 native 打死。
                        // 正确反应是立刻降一档码率，让下一个 GOP 真正来得及发出去。
                        stepDownBitrate()
                        continue
                    }
                    try {
                        writeLock.withLock {
                            // 视频帧按 type 明文透传，seal 原样返回同一数组；必须按 len 写，
                            // 否则会把池槽里未使用的尾部字节也发到对端，破坏帧边界。
                            val wire = channel.seal(frame.wire)
                            out.write(wire, 0, frame.len)
                        }
                    } finally {
                        frame.slot?.release()
                    }
                }
            } catch (e: Exception) {
                // 之前这里静默 close：对端崩溃导致的断流在日志里完全看不出原因
                if (!closed) QpmLog.w(TAG, "发送线程异常退出: ${e.javaClass.simpleName}: ${e.message}")
                close()
            }
        }

        private fun readLoop() {
            try {
                // 包一层缓冲：裸流每帧要两次 read syscall（5 字节头 + body）
                val reader = FrameReader(BufferedInputStream(socket.getInputStream(), 64 * 1024))
                // 握手阶段：v0.13 接收端连上后先明文发 HELLO（设备名），紧接着明文发 KEY_EXCHANGE
                // （临时公钥）；旧版/无加密对端则不发 KE。这里循环消费握手帧：
                // - HELLO：握手前必为明文，立即记录设备名（主循环不再见到它，避免武装后误解密）；
                // - KEY_EXCHANGE：回发本方 KE（明文）后武装，之后帧体全部 AES-GCM；
                // - 超时 / EOF / 协议外帧：回落明文，pending 帧由主循环按明文分发。
                // 握手前的帧一律不过 channel.open，保证加解密计数器从 0 严格对齐。
                var pending: Frame? = null
                runCatching { socket.soTimeout = 1500 }
                while (true) {
                    val f = runCatching { reader.read() }.getOrNull()
                    if (f == null) {
                        QpmLog.w(TAG, "握手阶段未收到 KEY_EXCHANGE（超时或断流），回退明文通道")
                        break
                    }
                    if (f.type == Type.KEY_EXCHANGE) {
                        val kp = runCatching { Crypto.newKeyPair() }.getOrNull()
                        val session = if (kp != null) runCatching { Crypto.deriveSessionKey(kp, f.body) }.getOrNull() else null
                        if (kp != null && session != null) {
                            // 先发本方 KE（明文），再武装——保证 KEY_EXCHANGE 帧永不被加密
                            writeLock.withLock {
                                socket.getOutputStream().write(Proto.keyExchange(kp.publicEncoded))
                            }
                            channel.arm(session)
                            QpmLog.i(TAG, "加密通道已建立（AES-256-GCM）")
                        } else {
                            // 密钥交换失败：降级明文，不回发 KE，让对端超时后回落（不会互等死锁）
                            QpmLog.w(TAG, "密钥派生失败，回退明文通道")
                        }
                        break
                    }
                    if (f.type == Type.HELLO) {
                        receiverName = Decoders.textOf(f)
                        receiverBeacons[hostAddress ?: ""] = receiverName
                        QpmLog.i(TAG, "接收端 $hostAddress 宣告设备名：$receiverName")
                        notifyReceiversChanged()
                        continue
                    }
                    // 协议外的握手帧（旧版对端 HELLO 后直接发业务帧）：暂存并回落明文
                    QpmLog.w(TAG, "握手阶段收到非预期帧 type=${f.type}，回退明文通道")
                    pending = f
                    break
                }
                runCatching { socket.soTimeout = 0 }
                while (!closed) {
                    val raw = pending ?: (reader.read() ?: break)
                    pending = null
                    val frame = channel.open(raw)
                    when (frame.type) {
                        Type.KEY_EXCHANGE -> { /* 已在校验握手处理，主循环不再出现 */ }
                        Type.HELLO -> {
                            receiverName = Decoders.textOf(frame)
                            receiverBeacons[hostAddress ?: ""] = receiverName
                            QpmLog.i(TAG, "接收端 $hostAddress 宣告设备名：$receiverName")
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
                        // 读盘 + 64KB 分片发送不能占着 readLoop，否则下载期间控制帧全部停摆
                        Type.FILE_GET_REQ -> if (paired) {
                            val path = Decoders.pathOf(frame)
                            fileExecutor.execute { handleFileGet(path) }
                        }
                        Type.FILE_PUSH_REQ -> if (paired) {
                            val (total, path) = Decoders.filePushReqOf(frame)
                            handleFilePushReq(total, path)
                        }
                        Type.FILE_PUSH_DATA -> if (paired) handleFilePushData(Decoders.filePushDataOf(frame))

                        // 剪贴板同步：接收端 -> 手机（写入手机剪贴板）
                        Type.CLIPBOARD -> if (paired) handleClipboardSync(Decoders.clipboardOf(frame))

                        // WebRTC 信令：SDP/ICE 转发给本连接的 RtcSession
                        Type.SDP -> session?.acceptAnswer(Decoders.sdpOf(frame))
                        Type.ICE -> {
                            val p = Decoders.iceOf(frame)
                            session?.addIce(p[0], p[1].toIntOrNull() ?: 0, p[2])
                        }
                        Type.RTC_RESTART -> {
                            QpmRtcLog.i("Cast", "对端请求 restartIce")
                            session?.restartIce()
                        }

                        // 延迟探针：原样回带 t1，接收端用本机时钟算 RTT（不要求时钟同步）
                        Type.PING -> sendControl(
                            Proto.pong(Decoders.pingOf(frame), SystemClock.elapsedRealtime().toInt())
                        )
                        // 接收端发现画面停更，主动要一个 IDR：只补关键帧，不动码率
                        Type.KEY_REQ -> if (paired) runCatching { encoder?.requestKeyFrame() }
                        // 接收端反馈：驱动自适应码率
                        Type.FEEDBACK -> if (paired) {
                            val fb = Decoders.feedbackOf(frame)
                            this@CastService.onReceiverFeedback(fb[0], fb[1], fb[2], fb[3])
                        }

                        // 连续手势：DOWN 建手势、MOVE 批量续接、UP 抬手
                        Type.TOUCH_DOWN -> if (paired) dispatchControl { svc ->
                            val (x, y) = Decoders.tap(frame)
                            svc.touchDown(x, y)
                        }
                        Type.TOUCH_MOVE -> if (paired) dispatchControl { svc ->
                            svc.touchMove(Decoders.touchPoints(frame))
                        }
                        Type.TOUCH_UP -> if (paired) dispatchControl { svc -> svc.touchUp() }
                    }
                }
            } catch (e: Exception) {
                QpmLog.w(TAG, "readLoop 连接异常: ${e.message}")
                // 连接异常，走 finally
            } finally {
                close()
            }
        }

        /** 配对成功后建 WebRTC offer 会话：把共享屏幕轨挂上，走 SDP 协商。 */
        private fun startRtc() {
            val shared = capture
            if (shared == null) {
                QpmLog.e(TAG, "startRtc：屏幕采集未就绪，无法建立 WebRTC 会话")
                postStatus("屏幕采集未就绪，请停止后重新开始投屏")
                mainHandler.post { close() }
                return
            }
            // 同一台设备重连（或 ICE 重建）时先拆掉旧会话，避免两条 PeerConnection 抢同一条轨
            runCatching { session?.close() }
            session = RtcSession(this@CastService, RtcSession.Role.OFFER, object : RtcSession.Events {
                override fun onSignalSdp(sdp: String) = sendReliable(Proto.sdp(sdp))
                override fun onSignalIce(mid: String, index: Int, candidate: String) =
                    sendReliable(Proto.ice(mid, index, candidate))
                override fun onRemoteTrack(track: org.webrtc.VideoTrack) = Unit
                override fun onDataMessage(data: ByteArray) {
                    // 控制帧从 DataChannel 来：分发到无障碍注入（TouchMapper 帧格式不变）
                    com.qpm.mirror.rtc.ControlChannel.dispatch(data, controlSink)
                }
                override fun onIceState(state: org.webrtc.PeerConnection.IceConnectionState) {
                    QpmRtcLog.i("Cast", "ICE=${state.name} host=${hostAddress}")
                    if (state == org.webrtc.PeerConnection.IceConnectionState.CONNECTED) {
                        sendReliable(Proto.rtcReady())
                        postStatus("接收端 ${displayName()} WebRTC 已连通")
                    }
                }
                override fun onConnected() = Unit
                override fun onNeedRebuild(reason: String) {
                    QpmRtcLog.w("Cast", "需要重建: $reason，重新协商")
                    session?.restartIce()
                }
                override fun onError(reason: String) {
                    // 协商硬失败：这条连接已经没救了，断开让接收端重连（重连走全新的 PeerConnection）
                    QpmRtcLog.e("Cast", "会话失败：$reason")
                    postStatus("画面协商失败：$reason")
                    mainHandler.post { close() }
                }
            })
            session?.attachVideoTrack(shared.track)
            // 触摸映射的前提：接收端必须先拿到手机真实屏幕尺寸（TouchMapper 为 0 时直接丢弃触摸）。
            syncScreenInfo()
            session?.startOffer()
            QpmRtcLog.i("Cast", "WebRTC offer 会话已建立 host=${hostAddress}（复用共享采集 ${shared.width}x${shared.height}）")
        }

        /** 上报手机真实屏幕尺寸 + 当前采集尺寸，驱动接收端触摸坐标映射。 */
        private fun syncScreenInfo() {
            val m = realMetrics()
            val cap = capture
            val cw = cap?.width ?: videoW
            val ch = cap?.height ?: videoH
            sendReliable(Proto.screenInfo(m.widthPixels, m.heightPixels, m.densityDpi, cw, ch))
            QpmLog.i(TAG, "已下发 SCREEN_INFO：屏幕 ${m.widthPixels}x${m.heightPixels} 采集 ${cw}x$ch")
        }

        /** ControlChannel 的注入目标，复用 QpmControlService（无需改无障碍服务）。 */
        private val controlSink = object : com.qpm.mirror.rtc.ControlChannel.ControlSink {
            override fun tap(x: Float, y: Float) = dispatchControl { it.tap(x, y) }
            override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) =
                dispatchControl { it.swipe(x1, y1, x2, y2, durationMs) }
            override fun text(s: String) = dispatchControl { it.sendText(s) }
            override fun key(keycode: Int) = dispatchControl { it.key(keycode) }
            override fun touchDown(x: Float, y: Float) = dispatchControl { it.touchDown(x, y) }
            override fun touchMove(points: FloatArray) = dispatchControl { it.touchMove(points) }
            override fun touchUp() = dispatchControl { it.touchUp() }
            override fun clipboard(text: String) = handleClipboardSync(text)
        }

        private fun handlePair(code: String) {
            val host = socket.inetAddress?.hostAddress
            // 白名单门控：被禁止的设备直接拒绝并断开，不进入配对流程
            if (host != null && !isReceiverAllowed(host)) {
                QpmLog.w(TAG, "接收端 $host 不在白名单，已拒绝连接")
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
                    startRtc()
                    QpmLog.i(TAG, "免密直连已接受（网络内唯一发送端）")
                } else {
                    sendReliable(Proto.pairResult(false))
                    QpmLog.w(TAG, "免密直连被拒绝：网络内存在多个发送端")
                }
                return
            }
            if (code == pairCode) {
                paired = true
                sendReliable(Proto.pairResult(true))
                onPaired(this)
                startRtc()
            } else {
                pairFails++
                sendReliable(Proto.pairResult(false))
                QpmLog.w(TAG, "配对失败（第 $pairFails 次）来自 ${socket.inetAddress?.hostAddress}")
                if (pairFails >= MAX_PAIR_FAILS) {
                    QpmLog.w(TAG, "连续配对失败 $pairFails 次，断开连接")
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
                    while (!closed) {
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
                QpmLog.i(TAG, "文件发送完成: $path ($total bytes)")
            } catch (e: Exception) {
                QpmLog.e(TAG, "文件发送失败: $path - ${e.message}")
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
                QpmLog.i(TAG, "接收端推送文件到：${target.absolutePath}（共 $total 字节）")
            } catch (e: Exception) {
                pushOs = null
                pushTarget = null
                QpmLog.e(TAG, "创建推送文件失败: $path - ${e.message}")
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
                    QpmLog.i(TAG, "接收端推送文件接收完成：$path")
                    sendReliable(Proto.filePushRes(true, path))
                }
            } catch (e: Exception) {
                runCatching { os.close() }
                pushOs = null
                pushTarget = null
                QpmLog.e(TAG, "写入推送文件失败: ${e.message}")
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
                    QpmLog.w(TAG, "无障碍服务未连接，控制指令被丢弃（手机端未开启「QPM 控制服务」）")
                }
                return
            }
            runCatching { action(control) }.onFailure {
                QpmLog.e(TAG, "控制指令执行失败: ${it.message}")
            }
        }

        fun close() {
            if (closed) return
            closed = true
            // 队列里未写出的池帧必须归还引用，否则槽会永久占用、池逐渐耗尽
            queue.forEach { it.slot?.release() }
            queue.clear()
            runCatching { session?.close() }
            session = null
            runCatching { pushOs?.close() }
            runCatching { socket.close() }
            onClose(this)
        }
    }
}

/**
 * 队列项带入队时刻、有效长度与关键帧标记：过期帧不再写入 socket，关键帧永不牺牲。
 * slot 非空表示 wire 来自共享帧池，长度可能小于数组容量，写出必须用 [len]。
 * 必须放在顶层：Kotlin 不允许在 inner class 内部声明嵌套类。
 */
private data class PendingFrame(
    val wire: ByteArray,
    val len: Int,
    val atMs: Long,
    val critical: Boolean,
    val slot: WireFramePool.Slot?
)

/**
 * 发送端 wire 帧池：固定槽 + 引用计数，砍掉每帧一次 25KB 级大数组分配。
 * 编码器回调只同步组帧，真正持有帧的是各客户端的有界队列，所以每成功入队一次
 * 引用 +1，writeLoop 写出（或丢帧 / 连接关闭）时 -1。槽耗尽时调用方退回普通分配，
 * 最坏情况只是多几次 GC，不会丢帧也不会卡死。
 * 注意槽内数组可能被扩容复用，长度往往大于实际帧长，写出必须用记录的 len。
 */
internal class WireFramePool(size: Int) {
    class Slot(var bytes: ByteArray) {
        private val refs = AtomicInteger(0)
        fun retain() { refs.incrementAndGet() }
        /** CAS 幂等释放：计数到 0 后重复调用不再递减，避免跨线程竞态把在用的槽判成空闲。 */
        fun release() {
            while (true) {
                val cur = refs.get()
                if (cur <= 0) return
                if (refs.compareAndSet(cur, cur - 1)) return
            }
        }
        fun isFree(): Boolean = refs.get() <= 0
    }

    private val slots = Array(size) { Slot(ByteArray(INITIAL_BYTES)) }
    private val lock = Any()

    /** 取一个空闲槽并把引用置 1（调用方持有）；池满返回 null。 */
    fun acquire(need: Int): Slot? {
        synchronized(lock) {
            for (s in slots) {
                if (!s.isFree()) continue
                if (s.bytes.size < need) s.bytes = ByteArray(maxOf(need, s.bytes.size * 2))
                s.retain()
                return s
            }
        }
        return null
    }

    private companion object {
        private const val INITIAL_BYTES = 64 * 1024
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
