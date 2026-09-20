package io.getbit.gim.sdk.rtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.getbit.gim.sdk.protocol.Cmd
import io.getbit.gim.sdk.protocol.PacketCodec
import io.getbit.gim.sdk.protocol.ImProto
import org.json.JSONObject
import org.webrtc.*
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.iterator

/**
 * RTC 引擎回调接口
 *
 * 项目层实现此接口以响应引擎事件：
 * - [onSendSignal] — 引擎需要发送信令时触发，项目层负责通过 IM 通道发出
 * - [onCallStateChanged] — 通话状态变更通知
 * - [onCallEnded] — 通话结束通知（含结束原因）
 * - [onRemoteStreamReceived] — 远端媒体流到达
 * - [onCallDurationTick] — 每秒通话计时
 * - [onIncomingCall] — 收到来电（被叫方，需展示来电 UI）
 */
interface RtcEngineCallback {
    fun onSendSignal(packet: ImProto.Packet)
    fun onCallStateChanged(state: RtcCallState)
    fun onCallEnded(reason: RtcCallEndReason)
    fun onRemoteVideoTrack(track: VideoTrack)
    fun onCallDurationTick(seconds: Int)
    fun onIncomingCall(senderId: String, callId: String)

    /**
     * 对端媒体开关状态变更（对端切换摄像头/麦克风时触发）
     *
     * null 表示该项未变化，非 null 为对端当前开关状态
     */
    fun onRemoteMediaStateChanged(cameraEnabled: Boolean?, micEnabled: Boolean?)
}

/**
 * RTC 引擎 — WebRTC 通话核心能力
 *
 * 对标 Flutter SDK RtcEngine：
 * 封装完整的 WebRTC 信令流程（标准 offer/answer 模型）：
 * 1. 主叫 → callRequest → 服务端 → callRequest(+callId) → 被叫
 *    （主叫 callRequest 未携带 callId 时，服务端生成后经 callAck(9) 回传主叫）
 * 2. 被叫 → callAccept(+callId) → 服务端 → callAccept(+callId) → 主叫
 * 3. 主叫创建 PeerConnection → createOffer → offer → 被叫
 * 4. 被叫 setRemoteDescription(offer) → createAnswer → answer → 主叫
 * 5. 主叫 setRemoteDescription(answer) → 连接建立
 * 6. 双方交换 ICE candidate
 */
class RtcEngine(
    private val context: Context,
    private val localUserId: () -> String,
    private val callback: RtcEngineCallback,
) {
    companion object {
        private const val TAG = "RtcEngine"
        private var isInitialized = false

        /** 初始化 WebRTC（全局只需一次，需传入 ApplicationContext） */
        fun initialize(context: Context) {
            if (isInitialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            isInitialized = true
        }

        /** 格式化通话时长 */
        fun formatDuration(seconds: Int): String {
            val m = (seconds / 60).toString().padStart(2, '0')
            val s = (seconds % 60).toString().padStart(2, '0')
            return "$m:$s"
        }
    }

    // ====================== 内部状态 ======================

    private var peerConnection: PeerConnection? = null

    /** 本地音频轨道（UNIFIED_PLAN 下无需 MediaStream，轨道直接加入 PeerConnection） */
    private var _localAudioTrack: AudioTrack? = null

    /** 本地视频轨道（供本地预览渲染） */
    private var _localVideoTrack: VideoTrack? = null

    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null

    /** PeerConnectionFactory（全局复用，对标 Flutter 只创建一次） */
    private var factory: PeerConnectionFactory? = null

    /** EGL 上下文（WebRTC 视频渲染需要，全局复用，通话清理时不释放） */
    private var eglBase: EglBase? = EglBase.create()

    /** 摄像头捕获器（用于 switchCamera） */
    private var cameraCapturer: CameraVideoCapturer? = null

    @Volatile
    private var _state = RtcCallState.IDLE
    private var _endReason = RtcCallEndReason.NONE
    private var _remoteUserId = ""
    private var _callId = ""
    private var _isInitiator = false
    private var _callType = RtcCallType.VIDEO
    private var _mediaReady = false

    /** 远端描述是否已设置（WebRTC 回调线程写入，主线程读取） */
    @Volatile
    private var _remoteDescSet = false

    /** 是否正在 SDP 协商（防重复 OFFER/ACCEPT 触发并发 setRemoteDescription/createOffer） */
    @Volatile
    private var negotiating = false

    /** 引擎是否已清理（清理后仅放行新来电，防 dispose 后继续操作 PeerConnection） */
    @Volatile
    private var disposed = false

    /** 最近一次来电的 callId（跨通话保留，用于拦截服务端重复投递的来电信令） */
    private var lastCallId = ""

    /** ICE 候选缓冲（在远端描述设置前暂存；主线程与 WebRTC 回调线程并发读写） */
    private val pendingCandidates = CopyOnWriteArrayList<IceCandidate>()

    /** 服务端下发的 TURN 凭证 */
    private var serverTurnInfo: JSONObject? = null

    /** 通话计时 */
    private var callTimer: Timer? = null
    private var _callDuration = 0

    /** 视频收发统计监控（诊断用） */
    private var statsTimer: Timer? = null

    // ====================== Getters ======================

    val state: RtcCallState get() = _state
    val endReason: RtcCallEndReason get() = _endReason
    val remoteUserId: String get() = _remoteUserId
    val callId: String get() = _callId
    val isInitiator: Boolean get() = _isInitiator
    val callType: RtcCallType get() = _callType
    val callDuration: Int get() = _callDuration
    val localVideoTrack: VideoTrack? get() = _localVideoTrack

    /** 共享 EGL 上下文（供所有视频渲染器使用，避免多上下文割裂） */
    val eglBaseContext: EglBase.Context? get() = eglBase?.eglBaseContext

    val cameraEnabled: Boolean
        get() = _localVideoTrack?.enabled() ?: false

    val microphoneEnabled: Boolean
        get() = _localAudioTrack?.enabled() ?: false

    // ====================== 公开 API ======================

    /** 发起通话（主叫方入口） */
    suspend fun startCall(targetUserId: String, type: RtcCallType) {
        // 上一通通话清理完成后允许立即发起新通话
        disposed = false
        negotiating = false

        _remoteUserId = targetUserId
        _callType = type
        _isInitiator = true
        _endReason = RtcCallEndReason.NONE
        // callId 由服务端生成，经 CALL_ACK(9) 回传；未到达前取消由服务端按占用会话兜底
        _callId = ""

        updateState(RtcCallState.CALLING)

        // 获取本地媒体流
        acquireLocalMedia(type)

        // 发送呼叫请求信令
        sendSignal(RtcSignalType.CALL_REQUEST, targetUserId, JSONObject().apply {
            put("callType", if (type == RtcCallType.VIDEO) "video" else "audio")
        })
    }

    /** 接听通话（被叫方入口） */
    suspend fun acceptCall() {
        // 上一通通话清理完成后允许接听新来电
        disposed = false
        negotiating = false

        sendSignal(RtcSignalType.CALL_ACCEPT, _remoteUserId, JSONObject(), callId = _callId)
        updateState(RtcCallState.CONNECTING)

        if (!_mediaReady) {
            acquireLocalMedia(_callType)
        }
    }

    /** 拒绝通话 */
    fun rejectCall() {
        _endReason = RtcCallEndReason.REJECTED
        sendSignal(RtcSignalType.CALL_REJECT, _remoteUserId, JSONObject().apply {
            put("reason", "reject")
        }, callId = _callId)
        endCall()
    }

    /** 取消呼叫 */
    fun cancelCall() {
        _endReason = RtcCallEndReason.CANCELLED
        sendSignal(RtcSignalType.CALL_CANCEL, _remoteUserId, JSONObject().apply {
            put("reason", "cancel")
        }, callId = _callId)
        endCall()
    }

    /** 挂断通话 */
    fun hangup() {
        _endReason = RtcCallEndReason.NORMAL
        sendSignal(RtcSignalType.CALL_HANGUP, _remoteUserId, JSONObject().apply {
            put("reason", "normal")
        }, callId = _callId)
        endCall()
    }

    /** 切换摄像头开关 */
    fun toggleCamera() {
        val track = _localVideoTrack ?: return
        val enabled = !track.enabled()
        track.setEnabled(enabled)
        // 通知对端（对端界面显示禁用图标）
        sendMediaState(camera = enabled)
    }

    /** 切换麦克风开关 */
    fun toggleMicrophone() {
        val track = _localAudioTrack ?: return
        val enabled = !track.enabled()
        track.setEnabled(enabled)
        // 通知对端（对端界面显示禁用图标）
        sendMediaState(mic = enabled)
    }

    /** 切换前后摄像头（对标 Flutter Helper.switchCamera） */
    fun switchCamera() {
        try {
            cameraCapturer?.switchCamera(null)
            Log.d(TAG, "switchCamera: camera switched")
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera failed: ${e.message}")
        }
    }

    // ====================== 信令分发入口 ======================

    /** 处理收到的 RTC 信令（由项目层从 IM 事件中转调） */
    fun handleSignal(signal: ImProto.RtcSignal) {
        Log.d(TAG, "signal type=${signal.signalType} from=${signal.senderId} callId=${signal.callId}, state=$_state")

        // 引擎已清理（通话刚结束）时，仅接受新来电信令，防止对已释放的
        // PeerConnection 继续操作导致 native 崩溃（SIGSEGV）
        if (disposed && signal.signalType != RtcSignalType.CALL_REQUEST) {
            Log.d(TAG, "signal dropped (engine disposed), type=${signal.signalType}")
            return
        }

        // 空闲状态下仅响应来电请求，其余视为过期信令
        if (_state == RtcCallState.IDLE && signal.signalType != RtcSignalType.CALL_REQUEST) {
            Log.d(TAG, "signal dropped (idle), type=${signal.signalType}")
            return
        }

        when (signal.signalType) {
            RtcSignalType.CALL_REQUEST -> onCallRequest(signal)
            RtcSignalType.CALL_ACK -> onCallAck(signal)
            RtcSignalType.CALL_ACCEPT -> onCallAccept(signal)
            RtcSignalType.CALL_REJECT -> onCallReject(signal)
            RtcSignalType.CALL_CANCEL -> onCallCancel(signal)
            RtcSignalType.CALL_HANGUP -> onCallHangup(signal)
            RtcSignalType.OFFER -> onOffer(signal)
            RtcSignalType.ANSWER -> onAnswer(signal)
            RtcSignalType.ICE_CANDIDATE -> onIceCandidate(signal)
            RtcSignalType.MEDIA_STATE -> onMediaState(signal)
        }
    }

    // ====================== 信令事件处理 ======================

    /** 收到呼叫请求（被叫方） */
    private fun onCallRequest(signal: ImProto.RtcSignal) {
        // 同一通来电的重复信令（服务端重投）→ 丢弃
        if (signal.callId.isNotEmpty() && signal.callId == lastCallId && _state != RtcCallState.IDLE) {
            Log.d(TAG, "duplicate CALL_REQUEST ignored, callId=${signal.callId}")
            return
        }
        // 通话进行中收到新来电 → 忽略，防止串线
        if (_state == RtcCallState.CALLING ||
            _state == RtcCallState.CONNECTING ||
            _state == RtcCallState.CONNECTED
        ) {
            Log.d(TAG, "CALL_REQUEST ignored (busy), state=$_state")
            return
        }

        lastCallId = signal.callId
        // 新通话开始，重置清理/协商标志
        disposed = false
        negotiating = false

        _remoteUserId = signal.senderId
        _callId = signal.callId
        _isInitiator = false
        _endReason = RtcCallEndReason.NONE

        if (signal.payload.isNotEmpty()) {
            try {
                val payload = JSONObject(signal.payload)
                // 解析服务端下发的 TURN 凭证
                parseTurnFromPayload(payload)
                // 通话类型由主叫 payload 指定（此前硬编码 VIDEO，音频来电会被误判为视频）
                _callType = if (payload.optString("callType", "video") == "audio") {
                    RtcCallType.AUDIO
                } else {
                    RtcCallType.VIDEO
                }
            } catch (e: Exception) {
                Log.e(TAG, "parseTurn error: ${e.message}")
            }
        }

        updateState(RtcCallState.RINGING)
        callback.onIncomingCall(signal.senderId, signal.callId)
    }

    /** 收到服务端回传的 callId（主叫方）→ 记录权威 callId，用于后续 cancel/hangup 等信令关联 */
    private fun onCallAck(signal: ImProto.RtcSignal) {
        if (signal.callId.isNotEmpty()) {
            _callId = signal.callId
            Log.d(TAG, "CALL_ACK received, callId=$_callId")
        }
    }

    /** 收到接听（主叫方）→ 创建 PeerConnection 并发送 Offer */
    private fun onCallAccept(signal: ImProto.RtcSignal) {
        // SDP 协商已在进行或已完成 → 重复 accept（信令重投），丢弃
        if (negotiating || _remoteDescSet) {
            Log.d(TAG, "duplicate CALL_ACCEPT ignored, state=$_state")
            return
        }

        if (signal.callId.isNotEmpty()) {
            _callId = signal.callId
        }

        if (signal.payload.isNotEmpty()) {
            try {
                parseTurnFromPayload(JSONObject(signal.payload))
            } catch (e: Exception) {
                Log.e(TAG, "parseTurn error: ${e.message}")
            }
        }

        updateState(RtcCallState.CONNECTING)
        createAndSendOffer()
    }

    /** 收到拒绝 */
    private fun onCallReject(signal: ImProto.RtcSignal) {
        // 过期拒绝信令（callId 不匹配）→ 丢弃，防止误伤新通话
        if (signal.callId.isNotEmpty() && signal.callId != _callId) {
            Log.d(TAG, "stale CALL_REJECT ignored, callId=${signal.callId} != $_callId")
            return
        }

        val reason = if (signal.payload.isNotEmpty()) {
            try {
                JSONObject(signal.payload).optString("reason", "")
            } catch (_: Exception) { "" }
        } else ""
        _endReason = if (reason == "busy") RtcCallEndReason.BUSY else RtcCallEndReason.REJECTED
        endCall()
    }

    /** 收到取消 */
    private fun onCallCancel(signal: ImProto.RtcSignal) {
        // 过期取消信令（callId 不匹配）→ 丢弃，防止误伤新通话
        if (signal.callId.isNotEmpty() && signal.callId != _callId) {
            Log.d(TAG, "stale CALL_CANCEL ignored, callId=${signal.callId} != $_callId")
            return
        }
        _endReason = RtcCallEndReason.CANCELLED
        endCall()
    }

    /** 收到挂断 */
    private fun onCallHangup(signal: ImProto.RtcSignal) {
        // 过期挂断信令（callId 不匹配）→ 丢弃，防止误伤新通话
        if (signal.callId.isNotEmpty() && signal.callId != _callId) {
            Log.d(TAG, "stale CALL_HANGUP ignored, callId=${signal.callId} != $_callId")
            return
        }
        _endReason = RtcCallEndReason.NORMAL
        endCall()
    }

    /** 通知对端本端媒体开关状态（payload 只带变化项） */
    private fun sendMediaState(camera: Boolean? = null, mic: Boolean? = null) {
        if (camera == null && mic == null) return
        val payload = JSONObject()
        camera?.let { payload.put("camera", it) }
        mic?.let { payload.put("mic", it) }
        sendSignal(RtcSignalType.MEDIA_STATE, _remoteUserId, payload, callId = _callId)
        Log.d(TAG, "Media state sent: camera=$camera, mic=$mic")
    }

    /** 收到对端媒体开关状态（通话中对端切换摄像头/麦克风） */
    private fun onMediaState(signal: ImProto.RtcSignal) {
        if (signal.callId.isNotEmpty() && signal.callId != _callId) {
            Log.d(TAG, "stale MEDIA_STATE ignored, callId=${signal.callId} != $_callId")
            return
        }
        if (signal.payload.isEmpty()) return
        try {
            val payload = JSONObject(signal.payload)
            val hasCamera = payload.has("camera")
            val hasMic = payload.has("mic")
            if (!hasCamera && !hasMic) return
            val camera = if (hasCamera) payload.getBoolean("camera") else null
            val mic = if (hasMic) payload.getBoolean("mic") else null
            Log.d(TAG, "Remote media state: camera=$camera, mic=$mic")
            callback.onRemoteMediaStateChanged(camera, mic)
        } catch (e: Exception) {
            Log.e(TAG, "parse media state error: ${e.message}")
        }
    }

    // ====================== WebRTC 核心流程 ======================

    /** 主叫方：创建 Offer 并发送 */
    private fun createAndSendOffer() {
        initPeerConnection()

        // 标记协商进行中，防止重复 CALL_ACCEPT 触发并发 createOffer
        negotiating = true

        val mediaConstraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (disposed) return
                sdp ?: return
                logSdpDirections("Local offer", sdp.description)
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        if (disposed) return
                        sendSignal(RtcSignalType.OFFER, _remoteUserId, JSONObject().apply {
                            put("sdp", sdp.description)
                        }, callId = _callId)
                        Log.d(TAG, "Offer sent to $_remoteUserId")
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {
                        if (disposed) return
                        negotiating = false
                        Log.e(TAG, "setLocalDescription(offer) failed: $p0")
                    }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                if (disposed) return
                negotiating = false
                Log.e(TAG, "createOffer failed: $error")
            }
            override fun onSetFailure(p0: String?) {}
        }, mediaConstraints)
    }

    /** 被叫方：收到 Offer，创建 Answer */
    private fun onOffer(signal: ImProto.RtcSignal) {
        // SDP 协商已在进行或已完成 → 重复 offer（信令重投），丢弃
        if (negotiating || _remoteDescSet) {
            Log.d(TAG, "duplicate OFFER ignored")
            return
        }
        // 标记协商进行中，防止重复 OFFER 触发并发 setRemoteDescription
        negotiating = true

        initPeerConnection()

        val payload = JSONObject(signal.payload)
        val sdpStr = payload.getString("sdp")
        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdpStr)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                if (disposed) return
                Log.d(TAG, "Remote description set (offer)")
                flushPendingCandidates()

                val mediaConstraints = MediaConstraints()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (disposed) return
                        sdp ?: return
                        logSdpDirections("Local answer", sdp.description)
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                if (disposed) return
                                sendSignal(RtcSignalType.ANSWER, signal.senderId, JSONObject().apply {
                                    put("sdp", sdp.description)
                                }, callId = _callId)
                                // 协商完成（answer 已发出）
                                negotiating = false
                                Log.d(TAG, "Answer sent to ${signal.senderId}")
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {
                                if (disposed) return
                                negotiating = false
                                Log.e(TAG, "setLocalDescription(answer) failed: $p0")
                            }
                        }, sdp)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        if (disposed) return
                        negotiating = false
                        Log.e(TAG, "createAnswer failed: $error")
                    }
                    override fun onSetFailure(p0: String?) {}
                }, mediaConstraints)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                if (disposed) return
                negotiating = false
                Log.e(TAG, "setRemoteDescription(offer) failed: $error")
            }
        }, remoteSdp)
    }

    /** 主叫方：收到 Answer，设置远端描述 */
    private fun onAnswer(signal: ImProto.RtcSignal) {
        // 远端描述已设置 → 重复 answer（信令重投），丢弃
        if (_remoteDescSet) {
            Log.d(TAG, "duplicate ANSWER ignored")
            return
        }

        val payload = JSONObject(signal.payload)
        val sdpStr = payload.getString("sdp")
        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                if (disposed) return
                Log.d(TAG, "Remote description set (answer)")
                flushPendingCandidates()
                // 协商完成（answer 已设置）
                negotiating = false
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                if (disposed) return
                negotiating = false
                Log.e(TAG, "setRemoteDescription(answer) failed: $error")
            }
        }, remoteSdp)
    }

    /** 处理远端 ICE Candidate */
    private fun onIceCandidate(signal: ImProto.RtcSignal) {
        // 引擎已清理 → 丢弃，防止对已释放对象操作
        // PeerConnection 未创建（被叫在收到 offer 前）→ 仍需缓冲：主叫的候选 gathering
        // 早于 offer 发出，大量候选会先于 OFFER 到达，丢弃会导致 ICE 无候选对、
        // 双方卡在连接中（对标 Flutter：PC 未就绪时统一缓冲，远端描述就绪后回放）
        if (disposed) {
            Log.d(TAG, "ICE candidate dropped (engine disposed)")
            return
        }

        val candidate = try {
            val payload = JSONObject(signal.payload)
            IceCandidate(
                payload.getString("sdpMid"),
                payload.getInt("sdpMLineIndex"),
                payload.getString("candidate"),
            )
        } catch (e: Exception) {
            Log.e(TAG, "parse ICE candidate error: ${e.message}")
            return
        }

        if (peerConnection != null && _remoteDescSet) {
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "Remote ICE candidate added immediately")
        } else {
            pendingCandidates.add(candidate)
            Log.d(TAG, "Remote ICE candidate buffered (pending: ${pendingCandidates.size})")
        }
    }

    // ====================== PeerConnection 管理 ======================

    /** 初始化 PeerConnection */
    private fun initPeerConnection() {
        // 引擎已清理后禁止重建，防止与清理竞态
        if (disposed) {
            Log.d(TAG, "initPeerConnection skipped (disposed)")
            return
        }

        if (peerConnection != null && _mediaReady) {
            Log.d(TAG, "PeerConnection already exists, reusing")
            return
        }

        val iceServers = buildIceServers()
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        // 确保 factory 已创建
        val fac = getOrCreateFactory()

        peerConnection = fac.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (disposed) return
                candidate ?: return
                Log.d(TAG, "Local ICE candidate generated")
                sendSignal(RtcSignalType.ICE_CANDIDATE, _remoteUserId, JSONObject().apply {
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }, callId = _callId)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                if (disposed) return
                val track = transceiver?.receiver?.track() ?: return
                Log.d(TAG, "onTrack kind=${track.kind()} direction=${transceiver.direction} id=${track.id()}")
                // UNIFIED_PLAN 下远端媒体通过 onTrack 到达（onAddStream 不会触发）
                if (track.kind() == "video") {
                    Log.d(TAG, "Remote video track received")
                    callback.onRemoteVideoTrack(track as VideoTrack)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (disposed) return
                Log.d(TAG, "PeerConnection state: $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> onPeerConnected()
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.DISCONNECTED -> endCall()
                    else -> {}
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                if (disposed) return
                Log.d(TAG, "ICE connection state: $newState")
                if (newState == PeerConnection.IceConnectionState.CONNECTED ||
                    newState == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    onPeerConnected()
                }
            }

            // 其他回调空实现（UNIFIED_PLAN 下远端媒体走 onTrack，onAddStream 不会触发）
            override fun onAddStream(stream: MediaStream?) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })

        _remoteDescSet = false
        // 注意：此处不清空 pendingCandidates —— 被叫在收到 offer 前缓冲的主叫候选
        // 依赖它存活到 setRemoteDescription 后回放；上一通话的残留由 cleanup() 清理

        // 将本地媒体轨道添加到 PeerConnection（UNIFIED_PLAN，无需 MediaStream）
        _localAudioTrack?.let { peerConnection?.addTrack(it) }
        _localVideoTrack?.let { peerConnection?.addTrack(it) }
        Log.d(TAG, "Local tracks added to PeerConnection")
    }

    /** 连接成功回调（去重） */
    private fun onPeerConnected() {
        if (_state == RtcCallState.CONNECTED) return
        updateState(RtcCallState.CONNECTED)
        startTimer()
        startStatsMonitor()
        Log.d(TAG, "Call connected, timer started")
    }

    /**
     * 获取或创建 PeerConnectionFactory
     *
     * 必须挂载 DefaultVideoEncoderFactory / DefaultVideoDecoderFactory（含硬件编解码），
     * 否则无任何视频编解码器，对端 offer 中含 H264 时 setRemoteDescription 会报
     * "Failed to set remote video description send parameters" 导致协商失败。
     * 编解码器复用全局 EGL 上下文，与采集/渲染保持一致。
     */
    private fun getOrCreateFactory(): PeerConnectionFactory {
        factory?.let { return it }
        val egl = eglBase ?: EglBase.create().also { eglBase = it }
        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    /** 将缓冲的 ICE 候选添加到 PeerConnection */
    private fun flushPendingCandidates() {
        if (disposed) return
        _remoteDescSet = true
        if (pendingCandidates.isEmpty()) return
        Log.d(TAG, "Flushing ${pendingCandidates.size} pending ICE candidates")
        for (candidate in pendingCandidates) {
            peerConnection?.addIceCandidate(candidate)
        }
        pendingCandidates.clear()
    }

    // ====================== 媒体管理 ======================

    /** 获取本地媒体轨道 */
    private fun acquireLocalMedia(type: RtcCallType) {
        if (disposed || _mediaReady) return

        // 复用全局 factory（对标 Flutter 只创建一次）
        val fac = getOrCreateFactory()

        // 音频轨道
        audioSource = fac.createAudioSource(MediaConstraints())
        _localAudioTrack = fac.createAudioTrack("audio_track", audioSource)

        // 视频轨道（仅视频通话）
        if (type == RtcCallType.VIDEO) {
            val capturer = createCameraCapturer()
            if (capturer != null) {
                cameraCapturer = capturer
                videoSource = fac.createVideoSource(false)
                val surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread", eglBase?.eglBaseContext
                )
                capturer.initialize(surfaceTextureHelper, context.applicationContext, videoSource!!.capturerObserver)
                capturer.startCapture(640, 480, 30)
                _localVideoTrack = fac.createVideoTrack("video_track", videoSource)
            }
        }

        _mediaReady = true
        Log.d(TAG, "Local media acquired: audio=${_localAudioTrack != null}, video=${_localVideoTrack != null}")
    }

    /** 创建摄像头捕获器（使用 Context 初始化 Camera2Enumerator） */
    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context.applicationContext)
        val deviceNames = enumerator.deviceNames

        // 优先前置摄像头（事件回调用于诊断摄像头静默失败：开流异常、首帧等）
        val eventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String?) {
                Log.e(TAG, "Camera error: $errorDescription")
            }

            override fun onCameraDisconnected() {
                Log.w(TAG, "Camera disconnected")
            }

            override fun onCameraFreezed(errorDescription: String?) {
                Log.e(TAG, "Camera freezed: $errorDescription")
            }

            override fun onCameraOpening(cameraDeviceName: String?) {
                Log.d(TAG, "Camera opening: $cameraDeviceName")
            }

            override fun onFirstFrameAvailable() {
                Log.d(TAG, "Camera first frame available")
            }

            override fun onCameraClosed() {
                Log.d(TAG, "Camera closed")
            }
        }

        for (name in deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, eventsHandler)
            }
        }
        // 回退到后置
        for (name in deviceNames) {
            if (enumerator.isBackFacing(name)) {
                return enumerator.createCapturer(name, eventsHandler)
            }
        }
        Log.e(TAG, "No camera device found (devices=$deviceNames)")
        return null
    }

    // ====================== 内部工具 ======================

    /** 发送信令 */
    private fun sendSignal(signalType: Int, receiverId: String, payload: JSONObject, callId: String? = null) {
        val body = ImProto.RtcSignal.newBuilder()
            .setSignalType(signalType)
            .setSenderId(localUserId())
            .setReceiverId(receiverId)
            .setPayload(payload.toString())
            .setCallId(callId ?: _callId)
            .build()

        val packet = PacketCodec.create(Cmd.RTC_SIGNAL, body = body)
        callback.onSendSignal(packet)
    }

    /** 更新状态并通知 */
    private fun updateState(newState: RtcCallState) {
        _state = newState
        callback.onCallStateChanged(newState)
    }

    /** 结束通话 */
    private fun endCall() {
        // 幂等：重复挂断/取消信令只结束一次
        if (_state == RtcCallState.ENDED) return

        callTimer?.cancel()
        statsTimer?.cancel()
        statsTimer = null

        if (_endReason == RtcCallEndReason.NONE) {
            _endReason = RtcCallEndReason.FAILED
        }

        updateState(RtcCallState.ENDED)
        callback.onCallEnded(_endReason)
        cleanup()

        // 延迟恢复空闲状态
        Handler(Looper.getMainLooper()).postDelayed({
            if (_state == RtcCallState.ENDED) {
                // 清理完成，重置标志以接收下一通来电
                disposed = false
                negotiating = false
                updateState(RtcCallState.IDLE)
            }
        }, 1000)
    }

    /** 清理 WebRTC 资源 */
    private fun cleanup() {
        // 幂等：防止 endCall 与 WebRTC 回调重复触发清理
        if (disposed) return
        disposed = true
        negotiating = false

        try {
            // 停止摄像头捕获
            try {
                cameraCapturer?.stopCapture()
            } catch (_: Exception) {}
            cameraCapturer?.dispose()
            cameraCapturer = null

            // UNIFIED_PLAN 下无 MediaStream，直接释放轨道与媒体源
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            _localVideoTrack?.dispose()
            _localVideoTrack = null
            _localAudioTrack?.dispose()
            _localAudioTrack = null
            videoSource?.dispose()
            videoSource = null
            audioSource?.dispose()
            audioSource = null
            // eglBase 全局复用：不在单次通话清理中释放，
            // 否则下次通话 SurfaceTextureHelper 将失去 EGL 上下文
            _mediaReady = false
            _remoteDescSet = false
            pendingCandidates.clear()
            serverTurnInfo = null
            _callId = ""
        } catch (e: Exception) {
            Log.e(TAG, "cleanup error: ${e.message}")
        }
    }

    /** 释放所有资源 */
    fun dispose() {
        callTimer?.cancel()
        cleanup()
        eglBase?.release()
        eglBase = null
    }

    /** 启动通话计时 */
    private fun startTimer() {
        _callDuration = 0
        callTimer?.cancel()
        callTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    _callDuration += 1
                    callback.onCallDurationTick(_callDuration)
                }
            }, 1000, 1000)
        }
    }

    /** 启动视频收发统计监控（诊断"对端看不到我"：观察帧是否真的在编码/发送） */
    private fun startStatsMonitor() {
        statsTimer?.cancel()
        statsTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    val pc = peerConnection ?: return
                    pc.getStats { report ->
                        if (disposed) return@getStats
                        for ((_, stats) in report.statsMap) {
                            if (stats.type == "outbound-rtp" && stats.members["kind"] == "video") {
                                Log.d(TAG, "Video SEND: bytesSent=${stats.members["bytesSent"]}, " +
                                        "framesEncoded=${stats.members["framesEncoded"]}, " +
                                        "framesSent=${stats.members["framesSent"]}")
                            }
                            if (stats.type == "inbound-rtp" && stats.members["kind"] == "video") {
                                Log.d(TAG, "Video RECV: bytesReceived=${stats.members["bytesReceived"]}, " +
                                        "framesDecoded=${stats.members["framesDecoded"]}")
                            }
                        }
                    }
                }
            }, 3000, 5000)
        }
    }

    /** 记录 SDP 各 m-line 的收发方向（定位"对端看不到我"时的方向协商问题） */
    private fun logSdpDirections(where: String, sdp: String) {
        try {
            val summary = sdp.split(Regex("(?=m=)"))
                .filter { it.startsWith("m=") }
                .map { sec ->
                    val kind = sec.lineSequence().first().split(" ").getOrNull(1) ?: "?"
                    val dir = listOf("sendrecv", "sendonly", "recvonly", "inactive")
                        .firstOrNull { sec.contains("a=$it") } ?: "none"
                    "$kind=$dir"
                }.joinToString(", ")
            Log.d(TAG, "$where directions: $summary")
        } catch (_: Exception) {}
    }

    // ====================== ICE / TURN 配置 ======================

    /** 构建 ICE 服务器配置 */
    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()

        val turnInfo = serverTurnInfo
        if (turnInfo != null && turnInfo.has("turnUrl")) {
            if (turnInfo.has("stunUrl")) {
                servers.add(
                    PeerConnection.IceServer.builder(turnInfo.getString("stunUrl"))
                        .createIceServer()
                )
            }
            servers.add(
                PeerConnection.IceServer.builder(turnInfo.getString("turnUrl"))
                    .setUsername(turnInfo.optString("username", ""))
                    .setPassword(turnInfo.optString("credential", ""))
                    .createIceServer()
            )
            Log.d(TAG, "ICE servers: STUN+TURN from server payload")
            return servers
        }

        // 回退到 Google 公共 STUN
        servers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )
        Log.d(TAG, "ICE servers: fallback to Google STUN")
        return servers
    }

    /** 从信令 payload 中解析服务端下发的 TURN 凭证 */
    private fun parseTurnFromPayload(payload: JSONObject) {
        val turn = payload.optJSONObject("turn") ?: return
        serverTurnInfo = turn
        Log.d(TAG, "TURN info parsed: turnUrl=${turn.optString("turnUrl", "")}")
    }
}
