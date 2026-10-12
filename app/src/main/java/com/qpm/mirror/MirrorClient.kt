package com.qpm.mirror

import com.qpm.protocol.Decoders
import com.qpm.protocol.Discovery
import com.qpm.protocol.FrameReader
import com.qpm.protocol.Type
import java.io.OutputStream
import java.net.Socket

/** 接收端网络层：UDP 发现 -> TCP 收视频 / 发控制。 */
class MirrorClient(
    private val decoder: H264Decoder,
    private val onScreenInfo: (Int, Int, Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onDisconnected: () -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var listener: Discovery.Listener? = null

    @Volatile private var phoneScreenW = 0
    @Volatile private var phoneScreenH = 0

    fun phoneW() = phoneScreenW
    fun phoneH() = phoneScreenH

    /** 自动发现：等 UDP 广播。 */
    fun start() {
        Thread {
            try {
                onStatus("正在搜索发送端…")
                listener = Discovery.Listener()
                val device = listener?.listen() ?: return@Thread
                onStatus("连接 ${device.host}:${device.port}")
                connectTo(device.host, device.port)
            } catch (e: Exception) {
                onStatus("连接中断：${e.message}")
            } finally {
                onDisconnected()
            }
        }.start()
    }

    /** 手动连接：同机自测或广播被路由隔离时用。 */
    fun connect(host: String, port: Int = CastService.TCP_PORT) {
        Thread {
            try {
                onStatus("连接 $host:$port")
                connectTo(host, port)
            } catch (e: Exception) {
                onStatus("连接中断：${e.message}")
            } finally {
                onDisconnected()
            }
        }.start()
    }

    private fun connectTo(host: String, port: Int) {
        val s = Socket(host, port).apply { tcpNoDelay = true }
        socket = s
        out = s.getOutputStream()
        onStatus("已连接，等待画面…")

        val reader = FrameReader(s.getInputStream())
        while (true) {
            val frame = reader.read() ?: break
            when (frame.type) {
                Type.VIDEO_FRAME -> decoder.feed(frame.body)
                Type.SCREEN_INFO -> {
                    val (w, h, dpi) = Decoders.screenInfo(frame)
                    phoneScreenW = w
                    phoneScreenH = h
                    onScreenInfo(w, h, dpi)
                }
            }
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
        listener?.stop()
        listener = null
        runCatching { socket?.close() }
    }
}
