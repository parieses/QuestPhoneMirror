package com.qpm.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * 局域网发现：
 * - 手机每 1s 向 45555 广播 "QPM1|<tcpPort>|<w>x<h>"（发送端宣告）
 * - 接收端搜索期间每 1s 广播 "QPMR|<设备名>"（接收端宣告，供发送端扫描列表）
 * 两类 beacon 同端口，按前缀区分。接收端可以是任意设备（头显/手机/电脑），协议平台无关。
 */
object Discovery {
    const val PORT = 45555
    private const val PREFIX = "QPM1|"
    private const val RECV_PREFIX = "QPMR|"

    fun beacon(port: Int, w: Int, h: Int): String = "$PREFIX$port|${w}x$h"

    fun receiverBeacon(name: String): String = "$RECV_PREFIX$name"

    /**
     * 手机端调用，阻塞循环直到线程被中断。
     * 被 interrupt() 时 Thread.sleep 抛 InterruptedException 退出，DatagramSocket 随 use{} 关闭。
     * 中断与网络异常都必须在这里消化：Runnable 里逃逸的未捕获异常会直接杀进程。
     */
    fun broadcastLoop(port: Int, w: Int, h: Int) {
        val payload = beacon(port, w, h).toByteArray(StandardCharsets.UTF_8)
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                val addr = InetAddress.getByName("255.255.255.255")
                while (!Thread.currentThread().isInterrupted) {
                    s.send(DatagramPacket(payload, payload.size, addr, PORT))
                    Thread.sleep(1000)
                }
            }
        } catch (_: InterruptedException) {
            // 调用方 interrupt() 要求退出：正常路径
        } catch (_: Exception) {
            // 网络异常（如 Wi-Fi 断开）：静默退出，调用方按无 beacon 处理。
            // 注意：未捕获异常会逃逸出 Runnable 直接杀进程，这里必须消化。
        }
    }

    /**
     * 接收端调用，阻塞循环直到线程被中断。
     * 让发送端「连接管理」能扫描到本机（即使尚未连接）。
     * 中断与网络异常都必须在这里消化，理由同 [broadcastLoop]。
     */
    fun broadcastReceiverLoop(name: String) {
        val payload = receiverBeacon(name).toByteArray(StandardCharsets.UTF_8)
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                val addr = InetAddress.getByName("255.255.255.255")
                while (!Thread.currentThread().isInterrupted) {
                    s.send(DatagramPacket(payload, payload.size, addr, PORT))
                    Thread.sleep(1000)
                }
            }
        } catch (_: InterruptedException) {
            // 离开接收端界面时 interrupt()：正常路径，不再崩溃
        } catch (_: Exception) {
            // 网络异常：静默退出，理由同上
        }
    }

    class Device(val host: String, val port: Int, val w: Int, val h: Int)

    /** 扫描到的接收端（未连接也可能出现在列表里）。 */
    class Receiver(val host: String, val name: String) {
        /** 没有设备名时退回 IP 展示。 */
        fun displayName(): String = name.ifBlank { host }
    }

    /**
     * 在 [durationMs] 窗口内监听 beacon，返回去重后的发送端列表（按 host 去重）。
     * 用于：接收端数一下局域网里有几个发送端；发送端监听是否有同类设备。
     * 端口被占用或出错时返回空列表。
     */
    fun scan(durationMs: Long): List<Device> = scanAll(durationMs).first

    /**
     * 在 [durationMs] 窗口内同时收集发送端与接收端 beacon（单 socket，避免同端口二次 bind 失败）。
     * 返回 (发送端列表, 接收端列表)，均按 host 去重。
     */
    fun scanAll(durationMs: Long): Pair<List<Device>, List<Receiver>> {
        val senders = LinkedHashMap<String, Device>()
        val receivers = LinkedHashMap<String, Receiver>()
        val socket = try {
            DatagramSocket(PORT)
        } catch (_: Exception) {
            return emptyList<Device>() to emptyList()
        }
        try {
            socket.soTimeout = 400
            val end = System.currentTimeMillis() + durationMs
            val buf = ByteArray(256)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val msg = String(p.data, p.offset, p.length, StandardCharsets.UTF_8)
                    val host = p.address.hostAddress ?: continue
                    when {
                        msg.startsWith(PREFIX) -> {
                            val parts = msg.split("|")
                            if (parts.size < 3) continue
                            val wh = parts[2].split("x")
                            if (wh.size != 2) continue
                            senders[host] = Device(host, parts[1].toInt(), wh[0].toInt(), wh[1].toInt())
                        }
                        msg.startsWith(RECV_PREFIX) -> {
                            val name = msg.removePrefix(RECV_PREFIX).trim()
                            receivers[host] = Receiver(host, name)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // 超时继续等
                } catch (_: Exception) {
                    // 单包解析失败不影响其他包
                }
            }
        } finally {
            runCatching { socket.close() }
        }
        return senders.values.toList() to receivers.values.toList()
    }

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
