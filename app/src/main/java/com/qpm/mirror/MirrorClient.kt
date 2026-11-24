package com.qpm.mirror

import android.content.Context
import com.qpm.mirror.rtc.QpmRtcLog
import com.qpm.mirror.rtc.RtcSession
import com.qpm.protocol.Decoders
import com.qpm.protocol.Discovery
import com.qpm.protocol.FileChunk
import com.qpm.protocol.Frame
import com.qpm.protocol.FrameReader
import com.qpm.protocol.Proto
import com.qpm.protocol.Type
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket

/**
 * 接收端网络层（WebRTC 版）：
 * - UDP 45555 发现 + TCP 45556 辅助通道：配对、文件、剪贴板、SDP/ICE 信令；
 * - 视频/控制走 WebRTC：RtcSession 持有 PeerConnection，控制帧走 DataChannel。
 *
 * 辅助 TCP 不再承载视频帧，只做低频控制。这样即使 WebRTC 链路抖动，
 * 通道本身也不拥塞，SDP/ICE 能及时送达完成重协商。
 */
class MirrorClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onPairResult: (Boolean) -> Unit = {},
    private val onFileList: (List<String>) -> Unit = {},
    private val onFileData: (FileChunk) -> Unit = {},
    private val onFileError: (String) -> Unit = {},
    private val onMultipleSenders: (List<Discovery.Device>) -> Unit = {},
    private val onRejected: (String) -> Unit = {},
    private val onClipboard: (String) -> Unit = {},
    private val onFilePushResult: (Boolean, String) -> Unit = { _, _ -> },
    private val onRemoteTrack: (org.webrtc.VideoTrack) -> Unit = {},
    private val onScreenInfo: (Int, Int, Int) -> Unit = { _, _, _ -> },
    private val onConnected: () -> Unit = {},
    private val onIceState: (String) -> Unit = {},
    private val helloName: String = ""
) {
    // 构造参数与 RtcSession.Events 方法同名，这里建别名避免方法内递归调用自身。
    private val onRemoteTrackCallback: (org.webrtc.VideoTrack) -> Unit = onRemoteTrack
    private val onIceStateCallback: (String) -> Unit = onIceState
    private val onConnectedCallback: () -> Unit = onConnected

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopped = false
    @Volatile private var host: String? = null
    @Volatile private var port: Int = CastService.TCP_PORT

    /** 辅助 TCP 通道加密：与 CastService.Client 的 SecureChannel 对齐，避免握手等待与信令明文。 */
    private val channel = SecureChannel(Crypto.DIR_MIRROR)

    @Volatile var session: RtcSession? = null
        private set

    @Volatile private var phoneScreenW = 0
    @Volatile private var phoneScreenH = 0

    fun phoneW() = phoneScreenW
    fun phoneH() = phoneScreenH
    fun currentHost(): String? = host

    private fun write(bytes: ByteArray) {
        synchronized(channel) {
            runCatching { out?.write(channel.seal(bytes)); out?.flush() }
        }
    }

    // ---------- 发现与连接 ----------

    fun start() {
        Thread({
            try {
                QpmLog.i(TAG, "start：进入 UDP 发现循环")
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
                    return@Thread
                }
            } catch (e: Exception) {
                QpmLog.w(TAG, "start 异常", e)
                onStatus("连接中断：${e.message}")
            } finally {
                onDisconnected()
            }
        }, "qpm-discover").apply { isDaemon = true }.start()
    }

    fun connect(address: String, port: Int = CastService.TCP_PORT, pairCode: String? = null) {
        Thread({
            try {
                onStatus("连接 $address:$port")
                connectTo(address, port, autoPair = pairCode == null, pairCode)
            } catch (e: Exception) {
                QpmLog.w(TAG, "connect 异常", e)
                onStatus("连接中断：${e.message}")
            } finally {
                onDisconnected()
            }
        }, "qpm-connect").apply { isDaemon = true }.start()
    }

    private fun connectTo(address: String, port: Int, autoPair: Boolean, pairCode: String? = null) {
        this.host = address
        this.port = port
        val s = Socket(address, port).apply { tcpNoDelay = true }
        socket = s
        out = s.getOutputStream()
        var reader = FrameReader(BufferedInputStream(s.getInputStream(), 64 * 1024))

        if (helloName.isNotBlank()) write(Proto.hello(helloName))
        // 与发送端对齐的 ECDH 握手：先明文发本方 KE，短超时预读对端 KE。
        val kp = runCatching { Crypto.newKeyPair() }.getOrNull()
        var pendingFrame: Frame? = null
        if (kp != null) {
            synchronized(channel) { runCatching { out?.write(Proto.keyExchange(kp.publicEncoded)) } }
            runCatching { s.soTimeout = 1500 }
            val first = runCatching { reader.read() }.getOrNull()
            runCatching { s.soTimeout = 0 }
            when {
                first == null -> QpmLog.w(TAG, "未收到对端 KE，回落明文通道")
                first.type == Type.KEY_EXCHANGE -> {
                    val sessionKey = runCatching { Crypto.deriveSessionKey(kp, first.body) }.getOrNull()
                    if (sessionKey != null) {
                        channel.arm(sessionKey)
                        QpmLog.i(TAG, "加密通道已建立（AES-256-GCM）")
                    } else {
                        pendingFrame = first
                        QpmLog.w(TAG, "密钥派生失败，回落明文通道")
                    }
                }
                else -> {
                    pendingFrame = first
                    QpmLog.w(TAG, "对端未启用加密，回落明文通道")
                }
            }
        }

        if (autoPair) write(Proto.pair("")) else if (pairCode != null) write(Proto.pair(pairCode))
        onStatus(if (autoPair) "正在免密直连…" else "已连接，请输入配对码")

        // 接收端建 ANSWER 会话；SDP/ICE 由主循环的 onSignal* 写回 TCP。
        session = RtcSession(context, RtcSession.Role.ANSWER, object : RtcSession.Events {
            override fun onSignalSdp(sdp: String) = write(Proto.sdp(sdp))
            override fun onSignalIce(mid: String, index: Int, candidate: String) =
                write(Proto.ice(mid, index, candidate))
            override fun onRemoteTrack(track: org.webrtc.VideoTrack) {
                onRemoteTrackCallback(track)
            }
            override fun onDataMessage(data: ByteArray) = handleControlFrame(data)
            override fun onIceState(state: org.webrtc.PeerConnection.IceConnectionState) {
                onIceStateCallback(state.name)
                if (state == org.webrtc.PeerConnection.IceConnectionState.FAILED) {
                    QpmRtcLog.w("Client", "ICE FAILED，请求对端重协商")
                    write(Proto.rtcRestart())
                }
            }
            override fun onConnected() = onConnectedCallback()
            override fun onNeedRebuild(reason: String) {
                QpmRtcLog.w("Client", "需要重建会话: $reason")
                stop()
            }
            override fun onError(reason: String) {
                // SDP 协商失败：这条会话已不可用，断开让上层重连（重连会建全新 PeerConnection）
                QpmRtcLog.e("Client", "会话失败：$reason")
                onStatus("画面协商失败：$reason")
                stop()
            }
        })

        while (!stopped) {
            val frame = try {
                (pendingFrame ?: reader.read())?.let { channel.open(it) }
            } catch (e: Exception) {
                QpmLog.w(TAG, "读帧异常，连接断开", e)
                onStatus("连接中断：${e.message}")
                null
            } ?: break
            pendingFrame = null

            when (frame.type) {
                Type.PAIR_RESULT -> onPairResult(Decoders.pairResultOf(frame))
                Type.SDP -> session?.acceptOffer(Decoders.sdpOf(frame))
                Type.ICE -> {
                    val p = Decoders.iceOf(frame)
                    session?.addIce(p[0], p[1].toIntOrNull() ?: 0, p[2])
                }
                Type.RTC_RESTART -> QpmRtcLog.i("Client", "收到 RTC_RESTART，等待对端新 offer")
                Type.RTC_READY -> onConnected()
                Type.SCREEN_INFO -> {
                    val (w, h, dpi) = Decoders.screenInfo(frame)
                    phoneScreenW = w
                    phoneScreenH = h
                    onScreenInfo(w, h, dpi)
                }
                Type.FILE_LIST_RES -> onFileList(Decoders.fileEntriesOf(frame))
                Type.FILE_DATA -> onFileData(Decoders.fileDataOf(frame))
                Type.FILE_ERROR -> onFileError(Decoders.pathOf(frame))
                Type.REJECT -> onRejected(Decoders.textOf(frame))
                Type.CLIPBOARD -> onClipboard(Decoders.clipboardOf(frame))
                Type.FILE_PUSH_RES -> {
                    val p = Decoders.filePushResOf(frame)
                    onFilePushResult(p.first, p.second)
                }
                else -> Unit
            }
        }
        QpmLog.i(TAG, "辅助 TCP 主循环退出 host=$host")
    }

    /** DataChannel 收到的信息帧：发送端下发的屏幕尺寸等。 */
    private fun handleControlFrame(data: ByteArray) {
        if (data.size < 5) return
        when (data[0]) {
            Type.SCREEN_INFO -> {
                val f = Frame(data[0], data.copyOfRange(5, data.size))
                val (w, h, dpi) = Decoders.screenInfo(f)
                phoneScreenW = w
                phoneScreenH = h
                onScreenInfo(w, h, dpi)
            }
            else -> Unit
        }
    }

    // ---------- 对外操作 ----------

    fun pair(code: String) = write(Proto.pair(code))
    fun sendClipboard(text: String) = write(Proto.clipboard(text))
    fun fileList(path: String) = write(Proto.fileListReq(path))
    fun fileGet(path: String) = write(Proto.fileGetReq(path))

    fun send(bytes: ByteArray) {
        // 控制帧优先走 DataChannel（低延迟），未连通时回落辅助 TCP。
        if (session?.sendData(bytes) != true) write(bytes)
    }

    fun stop() {
        stopped = true
        runCatching { session?.close() }
        session = null
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    private companion object {
        const val TAG = "MirrorClient"
    }
}
