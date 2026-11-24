package com.qpm.mirror.rtc

import android.content.Context
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单条 WebRTC 会话。一个 APK 双角色：
 * - OFFER：发送端（手机），addTrack 本地屏幕流，创建 DataChannel 发控制指令；
 * - ANSWER：接收端（VR/手机），onTrack 拿到远端流交给渲染器，onDataChannel 收控制帧转给本机注入。
 *
 * 生命周期：与连接绑定，不与 Activity 绑定（由 MirrorConnectionService 持有）。
 * 断线恢复照搬 Spatial Phone 的 disconnectedMidWay 模式：单线程 + 防重入。
 */
class RtcSession(
    private val context: Context,
    val role: Role,
    private val events: Events
) {
    enum class Role { OFFER, ANSWER }

    interface Events {
        /** SDP/ICE 需要经辅助 TCP 通道发给对端（零服务端信令）。 */
        fun onSignalSdp(sdp: String)
        fun onSignalIce(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        /** 收到远端视频轨，接收端用它渲染。 */
        fun onRemoteTrack(track: VideoTrack)
        /** 收到 DataChannel 控制帧（接收端 -> 发送端方向由 ControlChannel 处理）。 */
        fun onDataMessage(data: ByteArray)
        /** ICE 状态变化，服务据此驱动重连与 UI 提示。 */
        fun onIceState(state: PeerConnection.IceConnectionState)
        /** 连接建立（ICE connected / completed）。 */
        fun onConnected()
        /** 需要重建整条会话（restartIce 后仍未恢复）。 */
        fun onNeedRebuild(reason: String)
        /** 协商过程中的硬失败（SDP 建不出来/设不下去），服务据此提示用户并断开重试。 */
        fun onError(reason: String)
    }

    @Volatile
    var peer: PeerConnection? = null
        private set

    @Volatile
    var dataChannel: DataChannel? = null
        private set

    private val factory: PeerConnectionFactory

    /** 待对端 answer 前积攒的 ICE candidate；answer 到位后逐条 add。 */
    private val pendingIce = ArrayList<IceCandidate>()

    private val rebuilding = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var remoteSdpSet = false

    /**
     * 本端真正发出去的 offer 原文（清洗后）。
     *
     * 收 answer 时要用它做 m 行对齐：libwebrtc 在收 answer 前会执行
     * `MediaSectionsHaveSameCount` + `MediaSectionsInSameOrder`（pc/sdp_offer_answer.cc），
     * 任一条不成立就直接以
     * 「The order of m-lines in answer doesn't match order in offer」拒收整条 answer。
     * 只有拿得到本地 offer 的 mid 序列，才能在必要时把 answer 的 m 段重排回去。
     */
    @Volatile private var localOfferText: String? = null
    /** 只把第一条 candidate 打进应用日志，避免 30 秒内刷出几百行。 */
    private val firstCandidateLogged = AtomicBoolean(false)
    /** 对端 candidate 同样只打第一条。 */
    private val firstRemoteIceLogged = AtomicBoolean(false)

    init {
        val (f, _) = RtcFactory.init(context)
        factory = f
        createPeer()
    }

    /**
     * 把 native→Java 回调里的异常就地吃掉。
     *
     * 必须这么做，不是防御式编程洁癖：libwebrtc 在 `sdk/android/src/jni/jvm.cc` 里对
     * 「回调返回后 JNIEnv 上仍挂着未处理异常」直接 `RTC_CHECK(false)` → SIGABRT。
     * 也就是说 Java 侧任何一次 NPE/IllegalState（哪怕只是我们业务代码里一行模板
     * 拼了个 null）都会把整个进程打死，堆栈里只剩一堆 libjingle 帧，Java 异常本身
     * 不会出现在 tombstone 里（真机崩溃记录里就是这个形态：SIGABRT + Check failed: false）。
     */
    private inline fun guard(where: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            QpmRtcLog.e("Session", "$where 回调抛异常（已拦截，绝不能漏进 native）: $t")
        }
    }

    /** SDP 信令与错误提示也走保护壳：这两个回调同样跑在 native 信令线程上。 */
    private fun emitSdp(text: String) = guard("onSignalSdp") { events.onSignalSdp(text) }

    private fun emitError(reason: String) = guard("onError") { events.onError(reason) }

    private fun createPeer() {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            // 纯局域网：只用 host candidate，不需要 STUN/TURN。
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) =
                guard("onSignalingChange") { QpmRtcLog.i("Session", "signaling=$state") }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) =
                guard("onIceConnectionChange") {
                    QpmRtcLog.i("Session", "ICE=$state role=$role")
                    events.onIceState(state)
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            flushPendingIce()
                            events.onConnected()
                        }
                        PeerConnection.IceConnectionState.FAILED -> scheduleRebuild("ICE FAILED")
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            // 不立刻重建：Wi-Fi 切换/瞬时丢包常自行恢复，等下一次状态回调。
                            // 超时未恢复由 MirrorConnectionService 的看门狗兜底。
                            QpmRtcLog.w("Session", "ICE DISCONNECTED，等待自动恢复")
                        }
                        else -> Unit
                    }
                }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) =
                guard("onIceGatheringChange") { QpmRtcLog.i("Session", "gathering=$state") }

            override fun onIceCandidate(candidate: IceCandidate) = guard("onIceCandidate") {
                // sdpMid 是 @Nullable 平台类型：真机上有机会为 null，直接塞进非空参数
                // 会抛 NPE —— 而这里正是 native 线程，抛出去就是 SIGABRT。
                val mid = candidate.sdpMid ?: "0"
                val cand = candidate.sdp ?: return@guard
                if (firstCandidateLogged.compareAndSet(false, true)) {
                    QpmRtcLog.i("Session", "首个 ICE candidate 已交给辅助通道：$cand")
                }
                events.onSignalIce(mid, candidate.sdpMLineIndex, cand)
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit

            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit

            override fun onDataChannel(channel: DataChannel) = guard("onDataChannel") {
                QpmRtcLog.i("Session", "onDataChannel label=${channel.label()}")
                dataChannel = channel
                bindDataChannel(channel)
            }

            override fun onRenegotiationNeeded() = guard("onRenegotiationNeeded") {
                // Unified Plan 下 addTrack 会触发；首次 offer 由 startOffer 显式创建，
                // 这里只处理后续变更（如重连后的重新协商）。
                if (role == Role.OFFER && remoteSdpSet && !closed) {
                    QpmRtcLog.i("Session", "renegotiation needed")
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) =
                guard("onAddTrack") {
                    receiver.track()?.let { t ->
                        if (t is VideoTrack) events.onRemoteTrack(t)
                    }
                }
        })
    }

    /** 绑定 DataChannel 收帧：控制帧原样上抛，由 ControlChannel 解码。 */
    private fun bindDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = guard("DataChannel.onStateChange") {
                QpmRtcLog.i("Session", "DataChannel state=${channel.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) = guard("DataChannel.onMessage") {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                events.onDataMessage(bytes)
            }
        })
    }

    // ---------- 发送端：挂视频轨 + offer ----------

    /** 由 CastService 持有的那条全局唯一屏幕轨；本会话只引用、不释放。 */
    @Volatile
    private var videoTrack: VideoTrack? = null

    /**
     * 把已存在的屏幕轨挂到本连接的 PeerConnection 上。
     * 轨道由 [ScreenCapture] 单例创建并一直运行，这里**绝不能**再建采集器/VideoSource：
     * 同一个 MediaProjection 只允许 createVirtualDisplay 一次，重建即崩。
     */
    fun attachVideoTrack(track: VideoTrack) {
        if (videoTrack === track) return
        videoTrack = track
        peer?.addTrack(track, listOf("qpm-screen"))
        QpmRtcLog.i("Session", "屏幕轨道已挂载到 PeerConnection")
    }

    /** 建可靠、有序的 DataChannel，用于触摸/按键（丢弃会不跟手）。 */
    fun ensureDataChannel() {
        if (dataChannel != null) return
        val init = DataChannel.Init().apply {
            ordered = true
            maxRetransmits = 3
        }
        val ch = peer?.createDataChannel("qpm-control", init)
        if (ch != null) {
            dataChannel = ch
            bindDataChannel(ch)
            QpmRtcLog.i("Session", "DataChannel qpm-control 已创建")
        } else {
            QpmRtcLog.w("Session", "DataChannel 创建失败：PeerConnection 尚未就绪")
        }
    }

    fun sendData(bytes: ByteArray): Boolean {
        val ch = dataChannel ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        return runCatching {
            ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
        }.getOrDefault(false)
    }

    // ---------- SDP 协商 ----------

    /**
     * SDP 兜底清洗：只删除「已知会让对端解析器整条报废」的行，不改任何语义。
     *
     * 背景：`SessionDescription is NULL` 在 native 侧的真实错误是
     * `Failed to parse: "…" Reason: Invalid SDP line` —— 也就是这段文本在
     * Java→native 回灌时被解析器拒了，整条 offer 根本没发出去。
     * 已知的元凶有两类：
     *   ① `a=extmap-allow-mixed`（Chrome 系默认开，老解析器不认）；
     *   ② mDNS 混淆出来的 `.local` candidate（纯局域网没有 mDNS 解析器，留着也没用）。
     * 另外统一按 CRLF 规整并丢掉空行，避免自定义拼接后出现 `\n\n` 这类非法空行。
     */
    private fun sanitizeSdp(text: String): String {
        val lines = text.replace("\r\n", "\n").split('\n')
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("a=extmap-allow-mixed") }
            .filterNot { it.startsWith("a=candidate:") && it.contains(".local") }
        return lines.joinToString("\r\n") + "\r\n"
    }

    /** 把 SDP 拆成「头部 + 每个 m 段」两块，用于按 mid 重排。 */
    private fun splitSdp(text: String): Pair<String, List<String>> {
        val pre = StringBuilder()
        val blocks = ArrayList<StringBuilder>()
        for (ln in text.replace("\r\n", "\n").split('\n')) {
            when {
                ln.startsWith("m=") -> blocks.add(StringBuilder().append(ln).append('\n'))
                blocks.isEmpty() -> pre.append(ln).append('\n')
                else -> blocks.last().append(ln).append('\n')
            }
        }
        return pre.toString() to blocks.map { it.toString() }
    }

    /** 每个 m 段的 (媒体类型, mid)，例如 [("video","0"), ("application","1")]。 */
    private fun sdpSections(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var kind: String? = null
        var mid = "?"
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val ln = raw.trim()
            when {
                ln.startsWith("m=") -> {
                    kind?.let { out.add(it to mid) }
                    kind = ln.removePrefix("m=").substringBefore(' ')
                    mid = "?"
                }
                ln.startsWith("a=mid:") -> mid = ln.removePrefix("a=mid:").trim()
            }
        }
        kind?.let { out.add(it to mid) }
        return out
    }

    private fun describeSections(text: String): String =
        sdpSections(text).mapIndexed { i, (k, m) -> "$i=$k(mid $m)" }.joinToString(" ")

    /**
     * 按本地 offer 的 mid 顺序重排 answer 的 m 段。
     *
     * 只在「两边 m 段数量相同、mid 集合完全相同、仅顺序不同」时才动手——这正是
     * `kMlineMismatchInAnswer` 里 can't-pass 的那一种；数量不同属于协商级问题，
     * 重排救不了，只能打日志暴露。
     */
    private fun alignAnswerToOffer(offer: String?, answer: String): String {
        if (offer.isNullOrBlank()) return answer
        val offerMids = sdpSections(offer).map { it.second }
        val ansSecs = sdpSections(answer)
        if (ansSecs.size != offerMids.size) {
            QpmRtcLog.e(
                "Session",
                "m 行数量不一致，answer 无法重排：offer=${offerMids.size} 段（${describeSections(offer)}）" +
                    " / answer=${ansSecs.size} 段（${describeSections(answer)}）"
            )
            return answer
        }
        val ansMids = ansSecs.map { it.second }
        if (ansMids == offerMids) return answer
        if (ansMids.toSet() != offerMids.toSet()) {
            QpmRtcLog.e("Session", "answer 的 mid 集合与 offer 不同：$ansMids vs $offerMids")
            return answer
        }
        val (pre, blocks) = splitSdp(answer)
        val byMid = blocks.associateBy { sdpSections(it).firstOrNull()?.second ?: "?" }
        val reordered = offerMids.mapNotNull { byMid[it] }
        if (reordered.size != blocks.size) {
            QpmRtcLog.e("Session", "answer 重排失败：mid 映射不全 ${byMid.keys}")
            return answer
        }
        QpmRtcLog.w("Session", "answer 的 m 行顺序与 offer 不一致，已按 offer 重排：$ansMids -> $offerMids")
        return pre + reordered.joinToString("")
    }

    fun startOffer() {
        val pc = peer ?: return
        // 刻意不在 offer 里带 DataChannel。
        //
        // 控制帧本来就全走辅助 TCP：MirrorClient.send() 是
        // `if (session?.sendData(bytes) != true) write(bytes)`，而 DataChannel 从未协商成功过，
        // 一直是 TCP 在扛。但多出来的 m=application 会让 offer 变成两条 m 行，
        // 对端（Chrome）回的 answer 只要与本端 m 行的数量或顺序有任何出入，
        // libwebrtc 就直接以「The order of m-lines in answer doesn't match order in offer」拒收，
        // 表现为 offer 发得出去、answer 永远设不进去。单条 m=video 时这个校验天然成立。
        // 要恢复 DataChannel：先确认 answer 的 m 行结构与本端一致，再打开下面这行。
        // ensureDataChannel()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val text = sdp.description?.let { sanitizeSdp(it) }
                QpmRtcLog.i("Session", "createOffer 成功（${text?.length ?: 0} 字节）")
                if (text.isNullOrBlank()) {
                    emitError("本地 SDP 为空，无法发起投屏")
                    return
                }
                localOfferText = text
                val secs = sdpSections(text)
                QpmRtcLog.i("Session", "本地 offer m 行：${describeSections(text)}")
                if (secs.size != 1) {
                    // 单条 m=video 是这次协商的硬前提：多一条 m 行，answer 的 m 行
                    // 数量/顺序校验就有机会不成立（见 alignAnswerToOffer 注释）。
                    QpmRtcLog.e("Session", "本地 offer 竟是 ${secs.size} 条 m 行（预期 1 条），协商大概率仍会被拒")
                }
                QpmRtcLog.i("Session", "本地 offer 全文：\n$text")
                applyLocalOffer(pc, text)
            }

            override fun onCreateFailure(msg: String) {
                QpmRtcLog.e("Session", "createOffer 失败: $msg")
                emitError("createOffer 失败：$msg")
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(msg: String) = Unit
        }, MediaConstraints())
    }

    /**
     * 设置本地 offer。
     *
     * 这里**显式传 SDP**，而不是 `setLocalDescription(observer)` 自动应用。原因是有实锤：
     * 自动应用回调报成功后，answer 回来时 libwebrtc 仍以
     * `kMlineMismatchInAnswer` 拒收，而此时本端 offer 与 answer 都是单条
     * `m=video`（mid 0）、媒体类型也一样——唯一还能对不上的，就是
     * `local_description()`（native 实际落地的那份）并不是 createOffer 里那份。
     * 显式传入后 `local_description()` 必然严格等于我们发出去的文本。
     *
     * 自动应用只在显式设置被拒时兜底（老版本/状态机不允许）。
     */
    private fun applyLocalOffer(pc: PeerConnection, text: String) {
        QpmRtcLog.i("Session", "设置本地 offer（显式 SDP，${text.length} 字节）")
        pc.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                logLocalDescription("本地 offer（显式）")
                QpmRtcLog.i("Session", "本地 offer 已设置（显式），发送信令")
                emitSdp(text)
                // 分界线日志：出现这一行说明 Java 回调已完整返回。
                QpmRtcLog.i("Session", "本地回调已返回，native 继续（此后再无日志即为 native 内部崩溃）")
            }

            override fun onSetFailure(msg: String) {
                QpmRtcLog.w("Session", "显式设置 offer 失败（$msg），回退自动应用")
                QpmRtcLog.w("Session", "被拒的本地 SDP 全文：\n$text")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        logLocalDescription("本地 offer（自动应用）")
                        QpmRtcLog.i("Session", "本地 offer 已设置（自动应用），发送信令")
                        emitSdp(text)
                    }

                    override fun onSetFailure(m: String) {
                        QpmRtcLog.e("Session", "offer set 失败: $m")
                        emitError("offer 设置失败：$m")
                    }

                    override fun onCreateSuccess(s: SessionDescription) = Unit
                    override fun onCreateFailure(s: String) = Unit
                })
            }

            override fun onCreateSuccess(s: SessionDescription) = Unit
            override fun onCreateFailure(s: String) = Unit
        }, SessionDescription(SessionDescription.Type.OFFER, text))
    }

    /**
     * 把 native 实际落地的本地描述打出来（mid/类型逐段）。
     *
     * 这是唯一能证明「本端 offer 与发出去的文本一致」的证据：收 answer 时
     * libwebrtc 就是拿这份 `local_description()` 与 answer 逐段比 mid 与媒体类型。
     */
    private fun logLocalDescription(where: String) {
        val d = peer?.localDescription
        if (d == null) {
            QpmRtcLog.e("Session", "$where 之后 localDescription 仍为 null（native 没落地）")
            return
        }
        val t = d.description ?: ""
        QpmRtcLog.i("Session", "$where 落地：${t.length} 字节 m 行 ${describeSections(t)}")
    }

    fun acceptOffer(sdpText: String) {
        val pc = peer ?: return
        val text = sanitizeSdp(sdpText)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSdpSet = true
                QpmRtcLog.i("Session", "远端 offer 已设置（${text.length} 字节）")
                flushPendingIce()
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        val answerText = answer.description?.let { sanitizeSdp(it) }
                        QpmRtcLog.i("Session", "createAnswer 成功（${answerText?.length ?: 0} 字节）")
                        if (answerText.isNullOrBlank()) {
                            emitError("本地 answer 为空，无法接通")
                            return
                        }
                        applyLocalAnswer(pc, answerText)
                    }

                    override fun onCreateFailure(msg: String) {
                        QpmRtcLog.e("Session", "createAnswer 失败: $msg")
                        emitError("createAnswer 失败：$msg")
                    }

                    override fun onSetSuccess() = Unit
                    override fun onSetFailure(msg: String) = Unit
                }, MediaConstraints())
            }

            override fun onSetFailure(msg: String) {
                QpmRtcLog.e("Session", "setRemote offer 失败: $msg")
                QpmRtcLog.e("Session", "远端 SDP 全文：\n$text")
                emitError("接收 offer 失败：$msg")
            }

            override fun onCreateSuccess(s: SessionDescription) = Unit
            override fun onCreateFailure(msg: String) = onSetFailure(msg)
        }, SessionDescription(SessionDescription.Type.OFFER, text))
    }

    private fun applyLocalAnswer(pc: PeerConnection, text: String) {
        pc.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                QpmRtcLog.i("Session", "本地 answer 已设置（自动应用），发送信令")
                emitSdp(text)
                flushPendingIce()
            }

            override fun onSetFailure(msg: String) {
                QpmRtcLog.w("Session", "自动应用 answer 失败（$msg），回退显式 SDP")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        QpmRtcLog.i("Session", "本地 answer 已设置（显式），发送信令")
                        emitSdp(text)
                        flushPendingIce()
                    }

                    override fun onSetFailure(m: String) {
                        QpmRtcLog.e("Session", "answer set 失败: $m")
                        emitError("answer 设置失败：$m")
                    }

                    override fun onCreateSuccess(s: SessionDescription) = Unit
                    override fun onCreateFailure(s: String) = Unit
                }, SessionDescription(SessionDescription.Type.ANSWER, text))
            }

            override fun onCreateSuccess(s: SessionDescription) = Unit
            override fun onCreateFailure(s: String) = Unit
        })
    }

    fun acceptAnswer(sdpText: String) {
        val pc = peer ?: return
        val raw = sanitizeSdp(sdpText)
        // 这一行是排查 kMlineMismatchInAnswer 的唯一抓手：
        // libwebrtc 用 local_description() 与 answer 逐段比 mid + 媒体类型，
        // 两边都打出来才能看出到底哪一段对不上。
        val localDesc = peer?.localDescription?.description
        QpmRtcLog.i(
            "Session",
            "收 answer 前比对：本端 localDescription=" +
                (localDesc?.let { "${it.length}B ${describeSections(it)}" } ?: "null") +
                " / answer=${raw.length}B ${describeSections(raw)}"
        )
        val text = alignAnswerToOffer(localOfferText, raw)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSdpSet = true
                QpmRtcLog.i("Session", "远端 answer 已设置（${text.length} 字节）")
                flushPendingIce()
            }

            override fun onSetFailure(msg: String) {
                QpmRtcLog.e("Session", "setRemote answer 失败: $msg")
                QpmRtcLog.e("Session", "远端 SDP 全文：\n$text")
                emitError("接收 answer 失败：$msg")
            }

            override fun onCreateSuccess(s: SessionDescription) = Unit
            override fun onCreateFailure(msg: String) = onSetFailure(msg)
        }, SessionDescription(SessionDescription.Type.ANSWER, text))
    }

    fun addIce(sdpMid: String, index: Int, candidate: String) {
        // 对端 candidate 一条都不能少：ICE 要连通，收发两侧都得有对方的 candidate。
        if (firstRemoteIceLogged.compareAndSet(false, true)) {
            QpmRtcLog.i("Session", "首条远端 ICE candidate：mid=$sdpMid idx=$index $candidate")
        }
        val c = IceCandidate(sdpMid, index, candidate)
        if (!remoteSdpSet) {
            pendingIce.add(c)
            return
        }
        peer?.addIceCandidate(c) ?: pendingIce.add(c)
    }

    private fun flushPendingIce() {
        if (!remoteSdpSet || pendingIce.isEmpty()) return
        val list = pendingIce.toList()
        pendingIce.clear()
        list.forEach { runCatching { peer?.addIceCandidate(it) } }
        QpmRtcLog.i("Session", "已 flush ${list.size} 条 ICE candidate")
    }

    /** ICE restart：重新造 offer（candidate 会重新收集），不需要重建 PeerConnection。 */
    fun restartIce() {
        if (closed) return
        // 只有发起端能造 offer；接收端等对端发新的 offer 即可（自己造会撞状态机）
        if (role != Role.OFFER) {
            QpmRtcLog.w("Session", "非发起端收到 restartIce 请求，交由对端重协商")
            return
        }
        QpmRtcLog.i("Session", "触发 restartIce")
        peer?.restartIce()
        startOffer()
    }

    private fun scheduleRebuild(reason: String) {
        if (!rebuilding.compareAndSet(false, true)) {
            QpmRtcLog.w("Session", "重建已在进行，忽略 $reason")
            return
        }
        QpmRtcLog.w("Session", "触发重建: $reason")
        events.onNeedRebuild(reason)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { dataChannel?.close() }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        // 屏幕轨/采集器是进程级共享资源，归 ScreenCapture 释放；这里释放会把正在给
        // 其他连接出帧的轨道一起打死（下一轮连接就黑屏）
        videoTrack = null
        peer = null
        dataChannel = null
        QpmRtcLog.i("Session", "会话已关闭")
    }

    fun peerState(): String = peer?.iceConnectionState()?.name ?: "NONE"
}
