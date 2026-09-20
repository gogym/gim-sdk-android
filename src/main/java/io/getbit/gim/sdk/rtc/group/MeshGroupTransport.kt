package io.getbit.gim.sdk.rtc.group

import android.util.Log
import io.getbit.gim.sdk.protocol.ImProto
import io.getbit.gim.sdk.rtc.RtcSignalType
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.CameraVideoCapturer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Mesh 群通话媒体传输层（成员间 P2P 直连，服务端只扇出信令）
 *
 * 职责：
 * - 收到 roomState 后对每个已 joined 成员建连（offer 由 userId 字典序较小方发起，
 *   见 [isMeshOfferer]，两端决策一致，避免同时 Offer 冲突）
 * - offer/answer/ICE 经 cmd=50 RtcSignal 点对点收发（与 1:1 信令类型一致）
 * - 成员离开/结束时关闭对应 PeerConnection
 *
 * UNIFIED_PLAN 语义：轨道直接 addTrack，远端媒体经 onTrack 到达（无 MediaStream）。
 *
 * @property factory PeerConnectionFactory（引擎创建并持有，与采集/渲染共用 EGL 上下文）
 * @property localAudioTrack 本地音频轨（引擎采集并持有，传输层只引用不释放）
 * @property localVideoTrack 本地视频轨（引擎采集并持有，传输层只引用不释放）
 */
class MeshGroupTransport(
    private val localUserId: () -> String,
    private val callback: GroupMediaTransportCallback,
    private val factory: PeerConnectionFactory,
    private val localAudioTrack: AudioTrack?,
    private val localVideoTrack: VideoTrack?,
) : GroupMediaTransport {

    companion object {
        private const val TAG = "MeshGroupTransport"
    }

    // ====================== 内部状态 ======================

    /** 对端连接表（key=对端 userId） */
    private val peers = HashMap<String, MeshPeer>()

    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    /** 当前通话 callId（媒体信令携带，供对端过滤串线） */
    private var callId = ""

    @Volatile
    private var disposed = false

    /** 信令处理作用域（onOffer/onAnswer 为 suspend，需在协程中执行） */
    private val signalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ====================== GroupMediaTransport ======================

    override suspend fun start(roomState: GroupRoomState) {
        iceServers = buildGroupIceServers(roomState.turnInfo)
        callId = roomState.callId

        val me = localUserId()
        val joinedPeers = roomState.members
            .filter { it.userId != me && it.status == GroupMemberStatus.JOINED }
            .map { it.userId }

        Log.d(TAG, "start: peers=$joinedPeers, callId=$callId")
        for (peerId in joinedPeers) {
            connectPeer(peerId, makeOffer = isMeshOfferer(me, peerId))
        }
    }

    override fun handleMediaSignal(signal: ImProto.RtcSignal) {
        if (disposed) return
        signalScope.launch {
            when (signal.signalType) {
                RtcSignalType.OFFER -> onOffer(signal)
                RtcSignalType.ANSWER -> onAnswer(signal)
                RtcSignalType.ICE_CANDIDATE -> onRemoteCandidate(signal)
            }
        }
    }

    override fun syncRemoteMembers(joinedUserIds: Set<String>) {
        val stale = synchronized(peers) { peers.keys.filter { it !in joinedUserIds } }
        for (userId in stale) {
            Log.d(TAG, "peer left: $userId")
            closePeer(userId)
            callback.onRemoteMemberRemoved?.invoke(userId)
        }
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    override fun switchCamera() {
        // 摄像头捕获器由引擎持有，此处为引擎接入预留（引擎直接调用捕获器切换）
    }

    override val cameraEnabled: Boolean
        get() = localVideoTrack?.enabled() ?: false

    override val microphoneEnabled: Boolean
        get() = localAudioTrack?.enabled() ?: false

    override fun dispose() {
        disposed = true
        val userIds = synchronized(peers) { peers.keys.toList() }
        for (userId in userIds) {
            closePeer(userId)
        }
        synchronized(peers) { peers.clear() }
        Log.d(TAG, "disposed")
    }

    // ====================== 对端连接管理 ======================

    /** 建立与对端的 PeerConnection（已存在则跳过） */
    private suspend fun connectPeer(peerId: String, makeOffer: Boolean) {
        if (disposed) return
        val existing = synchronized(peers) { peers[peerId] }
        if (existing != null) {
            Log.d(TAG, "peer exists, skip: $peerId")
            return
        }

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = createPeerConnectionSuspend(config, peerId) ?: return
        val peer = MeshPeer(pc)
        synchronized(peers) { peers[peerId] = peer }
        Log.d(TAG, "peer created: $peerId (offerer=$makeOffer)")

        // 加入本地媒体轨（UNIFIED_PLAN，轨道直接加入，无需 MediaStream）
        addLocalTracks(pc)

        if (makeOffer) {
            createOffer(peerId)
        }
    }

    /** 将本地媒体轨加入 PeerConnection */
    private fun addLocalTracks(pc: PeerConnection) {
        localAudioTrack?.let { pc.addTrack(it) }
        localVideoTrack?.let { pc.addTrack(it) }
    }

    /** 发起 Offer（字典序较小方调用） */
    private suspend fun createOffer(peerId: String) {
        val peer = synchronized(peers) { peers[peerId] } ?: return
        val offer = peer.pc.createOfferSuspend(MediaConstraints()) ?: run {
            Log.e(TAG, "createOffer failed: $peerId")
            return
        }
        if (!peer.pc.setLocalDescriptionSuspend(offer)) {
            Log.e(TAG, "setLocalDescription(offer) failed: $peerId")
            return
        }
        sendMediaSignal(RtcSignalType.OFFER, peerId, JSONObject().put("sdp", offer.description))
        Log.d(TAG, "offer sent to $peerId")
    }

    /** 收到 Offer（Answer 方：不存在则先建连，再应答） */
    private suspend fun onOffer(signal: ImProto.RtcSignal) {
        val senderId = signal.senderId
        var peer = synchronized(peers) { peers[senderId] }
        if (peer == null) {
            connectPeer(senderId, makeOffer = false)
            peer = synchronized(peers) { peers[senderId] } ?: return
        }

        val payload = parsePayload(signal.payload)
        val sdp = payload.optString("sdp", "")
        if (sdp.isEmpty()) return
        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdp)
        if (!peer.pc.setRemoteDescriptionSuspend(remoteSdp)) {
            Log.e(TAG, "setRemoteDescription(offer) failed: $senderId")
            return
        }
        peer.remoteDescSet = true
        flushPendingCandidates(peer)

        val answer = peer.pc.createAnswerSuspend(MediaConstraints()) ?: run {
            Log.e(TAG, "createAnswer failed: $senderId")
            return
        }
        if (!peer.pc.setLocalDescriptionSuspend(answer)) {
            Log.e(TAG, "setLocalDescription(answer) failed: $senderId")
            return
        }
        sendMediaSignal(RtcSignalType.ANSWER, senderId, JSONObject().put("sdp", answer.description))
        Log.d(TAG, "answer sent to $senderId")
    }

    /** 收到 Answer（Offer 方：设置远端描述） */
    private suspend fun onAnswer(signal: ImProto.RtcSignal) {
        val peer = synchronized(peers) { peers[signal.senderId] } ?: run {
            Log.d(TAG, "answer from unknown peer: ${signal.senderId}")
            return
        }
        val payload = parsePayload(signal.payload)
        val sdp = payload.optString("sdp", "")
        if (sdp.isEmpty()) return
        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        if (!peer.pc.setRemoteDescriptionSuspend(remoteSdp)) {
            Log.e(TAG, "setRemoteDescription(answer) failed: ${signal.senderId}")
            return
        }
        peer.remoteDescSet = true
        flushPendingCandidates(peer)
        Log.d(TAG, "answer applied: ${signal.senderId}")
    }

    /** 收到远端 ICE Candidate（远端描述未就绪前缓冲） */
    private suspend fun onRemoteCandidate(signal: ImProto.RtcSignal) {
        val peer = synchronized(peers) { peers[signal.senderId] } ?: return
        val payload = parsePayload(signal.payload)
        val candidate = IceCandidate(
            payload.getString("sdpMid"),
            payload.getInt("sdpMLineIndex"),
            payload.getString("candidate"),
        )
        if (peer.remoteDescSet) {
            peer.pc.addIceCandidate(candidate)
        } else {
            peer.pendingCandidates.add(candidate)
        }
    }

    /** 关闭并移除对端连接 */
    private fun closePeer(userId: String) {
        val peer = synchronized(peers) { peers.remove(userId) } ?: return
        try {
            peer.pc.close()
        } catch (e: Exception) {
            Log.e(TAG, "close peer error: ${e.message}")
        }
    }

    // ====================== 信令与工具 ======================

    /** 发送点对点媒体信令（cmd=50 RtcSignal，由引擎包装为 Packet 发出） */
    private fun sendMediaSignal(signalType: Int, receiverId: String, payload: JSONObject) {
        val body = ImProto.RtcSignal.newBuilder()
            .setSignalType(signalType)
            .setSenderId(localUserId())
            .setReceiverId(receiverId)
            .setPayload(payload.toString())
            .setCallId(callId)
            .build()
        callback.onSendMediaSignal?.invoke(body)
    }

    /** 将缓冲的 ICE Candidate 应用到对端连接 */
    private fun flushPendingCandidates(peer: MeshPeer) {
        if (peer.pendingCandidates.isEmpty()) return
        val count = peer.pendingCandidates.size
        for (candidate in peer.pendingCandidates) {
            peer.pc.addIceCandidate(candidate)
        }
        peer.pendingCandidates.clear()
        Log.d(TAG, "flushed $count candidates")
    }

    /** 解析信令 payload JSON（异常时返回空对象） */
    private fun parsePayload(payload: String): JSONObject = try {
        JSONObject(payload)
    } catch (_: Exception) {
        JSONObject()
    }

    /** 创建 PeerConnection（suspend 包装，失败返回 null） */
    private suspend fun createPeerConnectionSuspend(
        config: PeerConnection.RTCConfiguration,
        peerId: String,
    ): PeerConnection? = suspendCoroutine { cont ->
        factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (disposed) return
                candidate ?: return
                sendMediaSignal(RtcSignalType.ICE_CANDIDATE, peerId, JSONObject().apply {
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                })
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                if (disposed) return
                val track = transceiver?.receiver?.track() ?: return
                if (track.kind() == "video") {
                    Log.d(TAG, "remote video track: peer=$peerId, id=${track.id()}")
                    callback.onRemoteMemberMedia?.invoke(
                        GroupRemoteMemberMedia(
                            userId = peerId,
                            meshVideoTrack = track as VideoTrack,
                            hasVideo = true,
                        )
                    )
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (disposed) return
                Log.d(TAG, "peer $peerId connection: $newState")
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    callback.onMediaConnected?.invoke()
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                if (disposed) return
                if (newState == PeerConnection.IceConnectionState.CONNECTED ||
                    newState == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    callback.onMediaConnected?.invoke()
                }
            }

            // 其他回调空实现（UNIFIED_PLAN 下远端媒体走 onTrack，onAddStream 不会触发）
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })?.let { cont.resume(it) } ?: cont.resume(null)
    }

    /** Mesh 对端连接条目 */
    private class MeshPeer(val pc: PeerConnection) {
        /** 远端描述就绪前缓冲的 ICE 候选 */
        val pendingCandidates = ArrayList<IceCandidate>()

        @Volatile
        var remoteDescSet = false
    }
}

// ====================== WebRTC suspend 工具 ======================

/** createOffer 的 suspend 包装（失败返回 null） */
suspend fun PeerConnection.createOfferSuspend(constraints: MediaConstraints): SessionDescription? =
    suspendCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) = cont.resume(sdp)
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG_GLOBAL, "createOffer failed: $error")
                cont.resume(null)
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

/** createAnswer 的 suspend 包装（失败返回 null） */
suspend fun PeerConnection.createAnswerSuspend(constraints: MediaConstraints): SessionDescription? =
    suspendCoroutine { cont ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) = cont.resume(sdp)
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG_GLOBAL, "createAnswer failed: $error")
                cont.resume(null)
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

/** setLocalDescription 的 suspend 包装（失败返回 false） */
suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription): Boolean =
    suspendCoroutine { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() = cont.resume(true)
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG_GLOBAL, "setLocalDescription failed: $error")
                cont.resume(false)
            }
        }, sdp)
    }

/** setRemoteDescription 的 suspend 包装（失败返回 false） */
suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription): Boolean =
    suspendCoroutine { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() = cont.resume(true)
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG_GLOBAL, "setRemoteDescription failed: $error")
                cont.resume(false)
            }
        }, sdp)
    }

private const val TAG_GLOBAL = "GroupRtcWebRTC"
