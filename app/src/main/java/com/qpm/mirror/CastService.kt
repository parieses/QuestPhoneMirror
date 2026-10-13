package com.qpm.mirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.qpm.protocol.Decoders
import com.qpm.protocol.Discovery
import com.qpm.protocol.FrameReader
import com.qpm.protocol.Proto
import com.qpm.protocol.Type
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

/**
 * 发送端服务：MediaProjection 录屏 -> H.264 硬编 -> TCP 推流，
 * 同一条连接上反向接收控制消息交给 SelectToSpeakService 执行。
 */
class CastService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val TCP_PORT = 45556
        private const val CHANNEL_ID = "qpm_cast"
        private const val QUEUE_DEPTH = 3
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: H264Encoder? = null
    private var server: ServerSocket? = null
    private var broadcastThread: Thread? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QPM 正在投屏")
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
        if (code == Activity.RESULT_OK && data != null) start(code, data) else stopSelf()
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent) {
        if (running) return
        running = true

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopAll()
        }, null)

        encoder = H264Encoder(width, height) { buf, size, flags, pts ->
            val frame = Proto.videoFrame(pts, flags, buf, 0, size)
            clients.forEach { it.offer(frame) }
        }

        display = projection?.createVirtualDisplay(
            "qpm-cast",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoder!!.inputSurface,
            null,
            null
        )

        Thread { acceptLoop() }.start()
        broadcastThread = Thread { Discovery.broadcastLoop(TCP_PORT, width, height) }.apply { start() }
    }

    private fun acceptLoop() {
        try {
            server = ServerSocket(TCP_PORT).apply { reuseAddress = true }
            while (running) {
                val socket = server!!.accept().apply { tcpNoDelay = true }
                val client = Client(socket) { clients.remove(it) }
                clients.add(client)
                client.offer(
                    Proto.screenInfo(
                        resources.displayMetrics.widthPixels,
                        resources.displayMetrics.heightPixels,
                        resources.displayMetrics.densityDpi
                    )
                )
                encoder?.requestKeyFrame()
            }
        } catch (_: Exception) {
            // 端口被占用或服务已停
        }
    }

    private fun stopAll() {
        running = false
        clients.forEach { it.close() }
        clients.clear()
        broadcastThread?.interrupt()
        broadcastThread = null
        runCatching { server?.close() }
        runCatching { display?.release() }
        runCatching { projection?.stop() }
        encoder?.stop()
        encoder = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 单个接收端连接：视频出、控制进。 */
    private class Client(
        private val socket: Socket,
        private val onClose: (Client) -> Unit
    ) {
        private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_DEPTH)
        @Volatile private var closed = false

        private val writer = Thread { writeLoop() }.apply { start() }
        private val reader = Thread { readLoop() }.apply { start() }

        /** 队列满则丢最旧的帧，优先保延迟。 */
        fun offer(frame: ByteArray) {
            if (closed) return
            if (!queue.offer(frame)) {
                queue.poll()
                queue.offer(frame)
            }
        }

        private fun writeLoop() {
            try {
                val out = socket.getOutputStream()
                while (!closed) {
                    // 用 poll 而非 take：close() 后若队列已空，take() 会永久挂起导致线程泄漏
                    val frame = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    out.write(frame)
                }
            } catch (_: Exception) {
                close()
            }
        }

        private fun readLoop() {
            try {
                val reader = FrameReader(socket.getInputStream())
                while (!closed) {
                    val frame = reader.read() ?: break
                    val control = SelectToSpeakService.instance ?: continue
                    when (frame.type) {
                        Type.TAP -> {
                            val (x, y) = Decoders.tap(frame)
                            control.tap(x, y)
                        }
                        Type.SWIPE -> {
                            val a = Decoders.swipe(frame)
                            control.swipe(a[0], a[1], a[2], a[3], a[4].toInt())
                        }
                        Type.TEXT -> control.sendText(Decoders.textOf(frame))
                        Type.KEY -> control.key(Decoders.keyOf(frame))
                    }
                }
            } catch (_: Exception) {
                // 连接异常，走 finally
            } finally {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            onClose(this)
        }
    }
}
