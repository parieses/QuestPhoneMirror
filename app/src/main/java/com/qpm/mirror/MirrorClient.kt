package com.qpm.mirror

import com.qpm.protocol.Decoders
import com.qpm.protocol.Discovery
import com.qpm.protocol.FileChunk
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
    private val decoder: H264Decoder?,
    private val onScreenInfo: (Int, Int, Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onPairResult: (Boolean) -> Unit = {},
    private val onFileList: (List<String>) -> Unit = {},
    private val onFileData: (FileChunk) -> Unit = {},
    private val onFileError: (String) -> Unit = {},
    private val onMultipleSenders: (List<Discovery.Device>) -> Unit = {},
    /** 发送端拒绝连接（如未在白名单）时回调，参数为原因。 */
    private val onRejected: (String) -> Unit = {},
    /** 本机设备名，连接后通过 HELLO 帧报送发送端（连接管理列表展示用）。 */
    private val helloName: String = ""
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopped = false
    @Volatile private var host: String? = null
    @Volatile private var port: Int = CastService.TCP_PORT

    @Volatile private var phoneScreenW = 0
    @Volatile private var phoneScreenH = 0

    fun phoneW() = phoneScreenW
    fun phoneH() = phoneScreenH
    fun currentHost(): String? = host

    /** 自动发现：扫描 -> 唯一发送端免密直连 / 多发送端交回调。 */
    fun start() {
        Thread {
            try {
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
                onStatus("连接中断：${e.message}")
            } finally {
                onDisconnected()
            }
        }.start()
    }

    /** 手动连接：同机自测、广播被隔离、或多发送端时用户选定后使用。pairCode 非空则连接后自动报送。 */
    fun connect(host: String, port: Int = CastService.TCP_PORT, pairCode: String? = null) {
        Thread {
            try {
                onStatus("连接 $host:$port")
                connectTo(host, port, autoPair = false, pairCode = pairCode)
            } catch (e: Exception) {
                onStatus("连接中断：${e.message}")
            } finally {
                onDisconnected()
            }
        }.start()
    }

    private fun connectTo(host: String, port: Int, autoPair: Boolean, pairCode: String? = null) {
        this.host = host
        this.port = port
        val s = Socket(host, port).apply { tcpNoDelay = true }
        socket = s
        out = s.getOutputStream()
        // 先报送设备名，让发送端「连接管理」能显示是谁（无论是否配对）
        if (helloName.isNotBlank()) {
            runCatching { out?.write(Proto.hello(helloName)) }
        }
        if (autoPair) {
            // 唯一发送端：直接发空配对码走免密直连
            runCatching { out?.write(Proto.pair("")) }
        } else if (pairCode != null) {
            runCatching { out?.write(Proto.pair(pairCode)) }
        }
        onStatus(if (autoPair) "正在免密直连…" else "已连接，请输入发送端显示的配对码")

        val reader = FrameReader(s.getInputStream())
        while (true) {
            val frame = reader.read() ?: break
            when (frame.type) {
                Type.VIDEO_FRAME -> decoder?.feed(frame.body)
                Type.SCREEN_INFO -> {
                    val (w, h, dpi) = Decoders.screenInfo(frame)
                    phoneScreenW = w
                    phoneScreenH = h
                    onScreenInfo(w, h, dpi)
                }
                Type.PAIR_RESULT -> onPairResult(Decoders.pairResultOf(frame))
                Type.FILE_LIST_RES -> onFileList(Decoders.fileEntriesOf(frame))
                Type.FILE_DATA -> onFileData(Decoders.fileDataOf(frame))
                Type.FILE_ERROR -> onFileError(Decoders.pathOf(frame))
                Type.REJECT -> {
                    // 发送端主动拒绝（未在白名单），回调后等对端断开
                    onRejected(Decoders.textOf(frame))
                }
            }
        }
    }

    /** 发送配对码（空串 = 免密直连请求）。 */
    fun pair(code: String) {
        try {
            out?.write(Proto.pair(code))
        } catch (_: Exception) {
            // 连接已断
        }
    }

    /** 请求列出目录。 */
    fun fileList(path: String) {
        try {
            out?.write(Proto.fileListReq(path))
        } catch (_: Exception) {
            // 连接已断
        }
    }

    /** 请求下载文件。 */
    fun fileGet(path: String) {
        try {
            out?.write(Proto.fileGetReq(path))
        } catch (_: Exception) {
            // 连接已断
        }
    }

    fun send(bytes: ByteArray) {
        try {
            out?.write(bytes)
        } catch (_: Exception) {
            // 连接已断
        }
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
    }
}
