package com.qpm.mirror

import android.os.SystemClock
import com.qpm.protocol.Decoders
import com.qpm.protocol.Discovery
import com.qpm.protocol.FileChunk
import com.qpm.protocol.Frame
import com.qpm.protocol.FrameReader
import com.qpm.protocol.Proto
import com.qpm.protocol.Type
import java.io.OutputStream
import java.net.Socket

/**
 * 接收端网络层：UDP 扫描发现 -> TCP 配对 -> 收视频 / 发控制 / 文件浏览下载。
 *
 * 发现策略：每次扫描 1.5s。只发现 1 个发送端 -> 免密直连；
 * 发现多个 -> 回调 onMultipleSenders 由 UI 让用户选择；0 个 -> 继续扫描。
 */
class MirrorClient(
    decoder: H264Decoder?,
    /** (真实屏幕宽, 真实屏幕高, dpi, 码流宽, 码流高)：前者用于触摸映射，后者用于 configure 解码器。 */
    private val onScreenInfo: (Int, Int, Int, Int, Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onPairResult: (Boolean) -> Unit = {},
    private val onFileList: (List<String>) -> Unit = {},
    private val onFileData: (FileChunk) -> Unit = {},
    private val onFileError: (String) -> Unit = {},
    private val onMultipleSenders: (List<Discovery.Device>) -> Unit = {},
    /** 发送端拒绝连接（如未在白名单）时回调，参数为原因。 */
    private val onRejected: (String) -> Unit = {},
    /** 发送端同步剪贴板文本过来时回调（手机复制内容 → 接收端剪贴板）。 */
    private val onClipboard: (String) -> Unit = {},
    /** 文件推送结果回调（成功 + 保存路径 / 失败 + 原因）。 */
    private val onFilePushResult: (Boolean, String) -> Unit = { _, _ -> },
    /** 投屏统计回调：fps / bitrateBps / w / h / dropped。 */
    private val onStats: (IntArray) -> Unit = {},
    /** 端到端延迟回调：[p50, p95, rttMs, jitter]，相对基线值（无需两端时钟同步）。 */
    private val onLatency: (IntArray) -> Unit = {},
    /** 本机设备名，连接后通过 HELLO 帧报送发送端（连接管理列表展示用）。 */
    private val helloName: String = "",
    /** 是否启用加密通道（默认开；握手失败由调用方关闭后重连用明文）。 */
    private val cryptoEnabled: Boolean = true
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopped = false
    @Volatile private var host: String? = null
    @Volatile private var port: Int = CastService.TCP_PORT

    /**
     * 解码器可热替换：发送端分辨率一旦变化，接收端会按新尺寸重建解码器，
     * 网络线程直接喂给新实例（旧实例立即停掉）。用写死的尺寸配置解码器，
     * 部分机型的硬解在收到尺寸不符的码流时会在 native 层崩溃。
     */
    @Volatile
    var decoder: H264Decoder? = decoder

    @Volatile private var phoneScreenW = 0
    @Volatile private var phoneScreenH = 0

    /** 加密通道：本端为接收端，发出方向为 DIR_MIRROR。握手前/失败回落时自动透传。 */
    private val channel = SecureChannel(Crypto.DIR_MIRROR)

    /** 加密写：武装后自动 AES-GCM 封装帧体，未武装则原样发出。
     *  synchronize 在 channel 监视器上，保证「seal（计数器自增）+ 写出」原子完成，避免多线程下计数错位。 */
    private fun writeCipher(bytes: ByteArray) {
        synchronized(channel) {
            runCatching { out?.write(channel.seal(bytes)) }
        }
    }

    companion object {
        private const val TAG = "MirrorClient"
    }

    fun phoneW() = phoneScreenW
    fun phoneH() = phoneScreenH
    fun currentHost(): String? = host

    /** 自动发现：扫描 -> 唯一发送端免密直连 / 多发送端交回调。 */
    fun start() {
        Thread {
            try {
                QpmLog.i(TAG, "start：进入 UDP 自动发现循环")
                while (!stopped) {
                    onStatus("正在搜索发送端…")
                    val devices = Discovery.scan(1500)
                    if (devices.isEmpty()) continue
                    if (devices.size == 1) {
                        val d = devices[0]
                        onStatus("连接 ${d.host}:${d.port}")
                        connectTo(d.host, d.port, autoPair = true)
                        return@Thread
                    }
                    onStatus("发现 ${devices.size} 个发送端，请选择")
                    onMultipleSenders(devices)
                    return@Thread // 等用户选择后由 connect() 发起
                }
            } catch (e: Exception) {
                QpmLog.w(TAG, "start：发现/连接线程异常", e)
                onStatus("连接中断：${e.message}")
            } finally {
                QpmLog.i(TAG, "start：线程退出，回调 onDisconnected")
                onDisconnected()
            }
        }.start()
    }

    /** 手动连接：同机自测、广播被隔离、或多发送端时用户选定后使用。pairCode 非空则连接后自动报送。 */
    fun connect(host: String, port: Int = CastService.TCP_PORT, pairCode: String? = null) {
        Thread {
            try {
                onStatus("连接 $host:$port")
                // 手动 / IP 连接也先尝试免密直连（发空配对码）：唯一发送端会直接接受，无需输码；
                // 仅当网络内确有多个发送端（发送端拒绝空配对码）才回落到「输入配对码」界面。
                connectTo(host, port, autoPair = pairCode == null, pairCode = pairCode)
            } catch (e: Exception) {
                QpmLog.w(TAG, "connect：连接线程异常", e)
                onStatus("连接中断：${e.message}")
            } finally {
                QpmLog.i(TAG, "connect：线程退出，回调 onDisconnected")
                onDisconnected()
            }
        }.start()
    }

    private fun connectTo(host: String, port: Int, autoPair: Boolean, pairCode: String? = null) {
        QpmLog.i(TAG, "connectTo: $host:$port autoPair=$autoPair 带码=${pairCode != null} 加密=$cryptoEnabled")
        this.host = host
        this.port = port
        val s = Socket(host, port).apply { tcpNoDelay = true }
        QpmLog.i(TAG, "TCP 已连接: ${s.inetAddress?.hostAddress}:$port")
        socket = s
        out = s.getOutputStream()

        // reader 提到函数作用域，关闭加密（cryptoEnabled=false）时主循环仍需使用
        var pending: Frame? = null
        // 包一层缓冲：裸流每帧要两次 read syscall（5 字节头 + body）
        val reader = FrameReader(java.io.BufferedInputStream(s.getInputStream(), 64 * 1024))

        // 关键：设备名（HELLO）必须在密钥交换握手之前以明文发送。发送端在握手循环里
        // 内联消费它（不过 channel.open），双方加解密计数器从 0 起严格对齐；
        // 若把它放到握手后加密发送，它会占用计数器槽位导致后续帧 nonce 错位、
        // GCM 校验失败，表现为「卡在正在配对」。
        if (helloName.isNotBlank()) {
            writeCipher(Proto.hello(helloName))
            QpmLog.i(TAG, "已发送 HELLO（明文，设备名=$helloName）")
        }

        // 密钥交换握手：先发本方 EC(secp256r1) 临时公钥（明文，KEY_EXCHANGE 帧永不被加密）。
        // 任意一步失败都降级为明文，绝不抛异常卡死连接。
        if (cryptoEnabled) {
            val kp = runCatching { Crypto.newKeyPair() }.getOrNull()
            if (kp != null) {
                // KE 必须明文直写；与 writeCipher 共用 channel 监视器，保证帧边界完整
                synchronized(channel) {
                    runCatching { out?.write(Proto.keyExchange(kp.publicEncoded)) }
                }
                // 预读一帧：期望对端 KE；设短超时，超时/非 KE 即回落明文（兼容旧版发送端）。
                runCatching { s.soTimeout = 1500 }
                val first = runCatching { reader.read() }.getOrNull()
                runCatching { s.soTimeout = 0 }
                if (first == null) {
                    QpmLog.w(TAG, "未收到对端 KE（超时），回退明文通道")
                } else if (first.type == Type.KEY_EXCHANGE) {
                    val session = runCatching { Crypto.deriveSessionKey(kp, first.body) }.getOrNull()
                    if (session != null) {
                        channel.arm(session)
                        QpmLog.i(TAG, "加密通道已建立（AES-256-GCM）")
                    } else {
                        QpmLog.w(TAG, "密钥派生失败，回退明文通道")
                        onStatus("QPM_CRYPTO_OFF")
                        s.close()
                        return
                    }
                } else {
                    QpmLog.w(TAG, "对端不支持加密（首帧 type=${first.type}），回退明文通道")
                    pending = first
                }
            }
        }

        // 配对（已武装则自动被加密）

        if (autoPair) {
            // 唯一发送端：直接发空配对码走免密直连
            writeCipher(Proto.pair(""))
        } else if (pairCode != null) {
            writeCipher(Proto.pair(pairCode))
        }
        onStatus(if (autoPair) "正在免密直连…" else "已连接，请输入发送端显示的配对码")
        QpmLog.i(TAG, "进入主循环，等待首帧")

        // 帧计数：每 3s 汇总一次，用于判断「连接成功后到底有没有在收帧」
        var frameCount = 0
        var lastFrameLogAt = System.currentTimeMillis()
        var firstFrameLogged = false
        var firstVideoLogged = false
        // 收到 SCREEN_INFO 且解码器按码流尺寸重建完成之前绝不喂帧：初始解码器是
        // 1920x1080，直接吃降采样码流会在部分机型硬解 native 层 SIGSEGV。
        // 尺寸帧若因任何原因没到，宁可黑屏也不要让进程直接死掉。
        var streamReady = false

        // 延迟探针 / 反馈回环：都由主循环按时间驱动，不额外起线程
        val meter = LatencyMeter()
        var lastProbeAt = 0L
        var lastFeedbackAt = 0L
        var lastDecoded = 0L
        var lastDecodedAt = 0L

        while (true) {
            // 不用 run { break }：非内联 lambda 里的 break/continue 是实验性语言特性，
            // 本项目 Kotlin 版本下直接编译失败；EOF 与读帧异常统一走 null 判断退出
            val raw = try {
                pending ?: reader.read()
            } catch (e: Exception) {
                QpmLog.w(TAG, "读帧异常，连接断开（已收帧 $frameCount）", e)
                onStatus("连接中断：${e.message}")
                null
            }
            if (raw == null) {
                QpmLog.i(TAG, "对端关闭连接（EOF）或读帧失败，主循环退出，共收帧 $frameCount")
                break
            }
            pending = null
            val frame = channel.open(raw)
            if (!firstFrameLogged) {
                firstFrameLogged = true
                QpmLog.i(TAG, "收到首帧 type=${frame.type} size=${frame.body.size}")
            }

            // 每 2s 一次往返探针；每 1s 汇总延迟并回报发送端驱动自适应码率
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastProbeAt >= 2000) {
                lastProbeAt = nowMs
                writeCipher(Proto.ping(nowMs.toInt()))
            }
            if (nowMs - lastFeedbackAt >= 1000) {
                onLatency(meter.snapshot())
                val decoded = decoder?.framesDecoded() ?: 0L
                val decodedFps = if (lastDecodedAt > 0 && nowMs > lastDecodedAt) {
                    ((decoded - lastDecoded) * 1000 / (nowMs - lastDecodedAt)).toInt()
                } else 0
                lastDecoded = decoded
                lastDecodedAt = nowMs
                writeCipher(Proto.feedback(decodedFps, meter.backlogMs, meter.dropped, meter.rttMs))
                lastFeedbackAt = nowMs
            }
            // 单帧处理兜底：解码/回调异常只丢这一帧并记日志，
            // 绝不让异常逃逸到网络线程把整条连接打断（表现为「配对成功后立马断」）
            try {
                when (frame.type) {
                    Type.KEY_EXCHANGE -> { /* 已在校验握手处理，主循环不再出现 */ }
                    Type.VIDEO_FRAME -> {
                        // 到达时刻 - 发送时刻含固定时钟偏差，取窗口内差值即可消掉偏差
                        meter.onFrame(Decoders.videoSentAt(frame), SystemClock.elapsedRealtime().toInt())
                        if (streamReady) decoder?.feed(frame.body)
                        if (!firstVideoLogged) {
                            firstVideoLogged = true
                            QpmLog.i(TAG, "收到首个视频帧 size=${frame.body.size}（此后每 3s 汇总一次）")
                        }
                        frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastFrameLogAt >= 3000) {
                            QpmLog.i(TAG, "最近 3s 收视频帧 ${frameCount} 个")
                            frameCount = 0
                            lastFrameLogAt = now
                        }
                    }
                    Type.SCREEN_INFO -> {
                        val (w, h, dpi) = Decoders.screenInfo(frame)
                        val (vw, vh) = Decoders.videoSizeOf(frame)
                        phoneScreenW = w
                        phoneScreenH = h
                        // 同步回调：里面完成解码器重建后才置 streamReady
                        onScreenInfo(w, h, dpi, vw, vh)
                        streamReady = true
                    }
                    Type.PAIR_RESULT -> onPairResult(Decoders.pairResultOf(frame))
                    Type.FILE_LIST_RES -> onFileList(Decoders.fileEntriesOf(frame))
                    Type.FILE_DATA -> onFileData(Decoders.fileDataOf(frame))
                    Type.FILE_ERROR -> onFileError(Decoders.pathOf(frame))
                    Type.REJECT -> {
                        // 发送端主动拒绝（未在白名单），回调后等对端断开
                        onRejected(Decoders.textOf(frame))
                    }
                    Type.CLIPBOARD -> onClipboard(Decoders.clipboardOf(frame))
                    Type.FILE_PUSH_RES -> {
                        val (ok, msg) = Decoders.filePushResOf(frame)
                        onFilePushResult(ok, msg)
                    }
                    Type.STATS -> {
                        val s = Decoders.statsOf(frame)
                        meter.dropped = s[4]
                        onStats(s)
                    }
                    Type.PONG -> {
                        val (t1, _) = Decoders.pongOf(frame)
                        if (t1 != 0) {
                            meter.rttMs = (SystemClock.elapsedRealtime().toInt() - t1).coerceIn(0, 65535)
                        }
                    }
                }
            } catch (e: Exception) {
                QpmLog.w(TAG, "帧处理异常 type=${frame.type}: ${e.message}")
            }
        }
    }

    /** 发送配对码（空串 = 免密直连请求）。 */
    fun pair(code: String) {
        writeCipher(Proto.pair(code))
    }

    /** 发送剪贴板文本到手机（接收端 -> 发送端方向）。 */
    fun sendClipboard(text: String) {
        writeCipher(Proto.clipboard(text))
    }

    /** 请求列出目录。 */
    fun fileList(path: String) {
        writeCipher(Proto.fileListReq(path))
    }

    /** 请求下载文件。 */
    fun fileGet(path: String) {
        writeCipher(Proto.fileGetReq(path))
    }

    /** 发送原始帧（文件推送等），武装后自动加密。 */
    fun send(bytes: ByteArray) {
        writeCipher(bytes)
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
    }
}

/**
 * 端到端延迟测量。两端时钟不同源，不能用「到达时刻 - 发送时刻」当绝对延迟；
 * 取最近 120 帧里该差值的最小值作基线，当前值减基线就是排队积压。
 * 这样无需 NTP 对时也能看出「越来越卡」，P50/P95 直接反映抖动分布。
 * RTT 由 PING/PONG 单独测（同一台设备的时钟，无同步问题）。
 */
private class LatencyMeter {
    private val samples = IntArray(WINDOW)
    private var count = 0
    private var idx = 0
    /** 首帧的「到达-发送」差值，作为固定基准，避免 32 位回绕让有符号排序失真。 */
    private var baseDelta: Int? = null

    @Volatile var rttMs = 0
    @Volatile var dropped = 0
    @Volatile var backlogMs = 0

    fun onFrame(sentAt: Int, arriveAt: Int) {
        if (sentAt == 0) return // 旧版发送端的 9 字节头没有时刻字段
        val delta = arriveAt - sentAt
        val base = baseDelta
        if (base == null) baseDelta = delta
        samples[idx] = delta - (baseDelta ?: delta)
        idx = (idx + 1) % WINDOW
        if (count < WINDOW) count++
    }

    /** [p50, p95, rttMs, jitter]；样本不足时返回全 0。 */
    fun snapshot(): IntArray {
        if (count < 3) {
            backlogMs = 0
            return intArrayOf(0, 0, rttMs, 0)
        }
        val win = IntArray(count)
        for (i in 0 until count) win[i] = samples[(idx - count + i + WINDOW) % WINDOW]
        val sorted = win.sorted()
        val base = sorted[0]
        val p50 = (sorted[count / 2] - base).coerceIn(0, MAX_LATENCY_MS)
        val p95 = (sorted[minOf(count - 1, count * 95 / 100)] - base).coerceIn(0, MAX_LATENCY_MS)
        backlogMs = (sorted.last() - base).coerceIn(0, MAX_LATENCY_MS)
        return intArrayOf(p50, p95, rttMs, (p95 - p50).coerceAtLeast(0))
    }

    private companion object {
        private const val WINDOW = 120
        /** 超过 2s 视作异常样本，钳住显示与反馈，避免一次抖动把码率打到最低。 */
        private const val MAX_LATENCY_MS = 2000
    }
}
