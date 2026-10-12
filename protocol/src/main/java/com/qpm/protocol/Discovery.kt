package com.qpm.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * 局域网发现：手机每 1s 向 45555 广播 "QPM1|<tcpPort>|<w>x<h>"，
 * 头显监听即可拿到手机 IP，无需手动输入。
 */
object Discovery {
    const val PORT = 45555
    private const val PREFIX = "QPM1|"

    fun beacon(port: Int, w: Int, h: Int): String = "$PREFIX$port|${w}x$h"

    /**
     * 手机端调用，阻塞循环直到线程被中断。
     * 被 interrupt() 时 Thread.sleep 抛 InterruptedException 退出，DatagramSocket 随 use{} 关闭。
     */
    fun broadcastLoop(port: Int, w: Int, h: Int) {
        val payload = beacon(port, w, h).toByteArray(StandardCharsets.UTF_8)
        DatagramSocket().use { s ->
            s.broadcast = true
            val addr = InetAddress.getByName("255.255.255.255")
            while (!Thread.currentThread().isInterrupted) {
                s.send(DatagramPacket(payload, payload.size, addr, PORT))
                Thread.sleep(1000)
            }
        }
    }

    class Device(val host: String, val port: Int, val w: Int, val h: Int)

    /**
     * 可取消的发现监听。调用 [stop] 即可中断阻塞中的 receive()，避免离开页面后线程泄漏。
     */
    class Listener {
        private val socket = DatagramSocket(PORT)
        @Volatile private var stopped = false

        init {
            socket.soTimeout = 1000
        }

        /** 阻塞直到收到一条合法 beacon；被 [stop] 或出错时返回 null。 */
        fun listen(): Device? {
            val buf = ByteArray(128)
            while (!stopped) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val msg = String(p.data, p.offset, p.length, StandardCharsets.UTF_8)
                    if (!msg.startsWith(PREFIX)) continue
                    val parts = msg.split("|")
                    if (parts.size < 3) continue
                    val wh = parts[2].split("x")
                    if (wh.size != 2) continue
                    val host = p.address.hostAddress ?: continue
                    return Device(host, parts[1].toInt(), wh[0].toInt(), wh[1].toInt())
                } catch (_: SocketTimeoutException) {
                    // 超时，回到循环重新检查 stopped
                } catch (_: Exception) {
                    if (stopped) return null
                }
            }
            return null
        }

        fun stop() {
            stopped = true
            runCatching { socket.close() }
        }
    }
}
