package com.qpm.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 局域网发现：手机每 1s 向 45555 广播 "QPM1|<tcpPort>|<w>x<h>"，
 * 头显监听即可拿到手机 IP，无需手动输入。
 */
object Discovery {
    const val PORT = 45555
    private const val PREFIX = "QPM1|"

    fun beacon(port: Int, w: Int, h: Int): String = "$PREFIX$port|${w}x$h"

    /** 手机端调用，阻塞循环直到线程被中断。 */
    fun broadcastLoop(port: Int, w: Int, h: Int) {
        val payload = beacon(port, w, h).toByteArray()
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

    /** 头显端调用，阻塞直到收到一条合法 beacon。 */
    fun awaitDevice(): Device {
        val buf = ByteArray(128)
        DatagramSocket(PORT).use { s ->
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val msg = String(p.data, p.offset, p.length)
                if (!msg.startsWith(PREFIX)) continue
                val parts = msg.split("|")
                if (parts.size < 3) continue
                val wh = parts[2].split("x")
                if (wh.size != 2) continue
                val host = p.address.hostAddress
                if (host == null) continue
                return Device(host, parts[1].toInt(), wh[0].toInt(), wh[1].toInt())
            }
        }
    }
}
