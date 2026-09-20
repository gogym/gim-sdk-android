package io.getbit.gim.sdk.rtc.group

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.getbit.gim.sdk.protocol.Cmd
import io.getbit.gim.sdk.protocol.ImProto
import io.getbit.gim.sdk.protocol.PacketCodec
import io.getbit.gim.sdk.rtc.RtcCallType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

/**
 * 群通话引擎回调
 *
 * 使用方实现此接口以响应引擎事件：
 * - [onSendGroupSignal] — 引擎需要发送群通话信令（cmd=51 Packet），项目层负责发出
 * - [onSendMediaSignal] — Mesh 模式点对点媒体信令（cmd=50 Packet），项目层负责发出
 * - [onIncomingGroupCall] — 收到群通话邀请（被邀方，需展示来电 UI）
 * - [onRoomStateChanged] — 房间快照到达（发起人/加入者都会收到）
 * - [onLocalStreamReady] — 本地媒体就绪（Mesh 模式：本地轨道对）
 * - [onLocalVideoTrack] — SFU 模式本地视频轨就绪（null 表示音频通话）
 * - [onRemoteMemberMedia] / [onRemoteMemberRemoved] — 远端成员媒体变化
 * - [onMemberMediaStateChanged] — 成员摄像头/麦克风开关变化
 * - [onMembersUpdated] — 成员列表变化（UI 重建）
 * - [onCallEnded] / [onCallDurationTick] — 通话结束与计时
 * - [onError] — 非致命错误上报
 */
interface GroupRtcEngineCallback {
    fun onSendGroupSignal(packet: ImProto.Packet)
    fun onSendMediaSignal(packet: ImProto.Packet)
    fun onCallStateChanged(state: GroupCallState)
    fun onIncomingGroupCall(invite: GroupCallInvite, roomId: String)
    fun onRoomStateChanged(roomState: GroupRoomState)
    fun onLocalStreamReady(audioTrack: AudioTrack?, videoTrack: VideoTrack?)
    fun onLocalVideoTrack(track: io.livekit.android.room.track.Track?)
    fun onRemoteMemberMedia(media: GroupRemoteMemberMedia)
    fun onRemoteMemberRemoved(userId: String)
    fun onMemberMediaStateChanged(userId: String, camera: Boolean?, mic: Boolean?)
    fun onMembersUpdated()
    fun onCallEnded(reason: GroupCallEndReason)
    fun onCallDurationTick(seconds: Int)
    fun onError(message: String)
}

/**
 * 群通话引擎 — 生命周期信令（cmd=51）+ 媒体传输层（Mesh/SFU 策略选择）
 *
 * 信令时序（与服务端 GroupCallService 对应）：
 * 1. 发起人 groupCallRequest(20) → 服务端建房 → 发起人收 roomState(27)、成员收 invite(21)
 * 2. 被邀方 groupCallJoin(22) → 收 roomState(27)，在房成员收 participantNotify(26, join)
 * 3. Mesh：成员间按快照建连，offer/answer/ICE 走 cmd=50 点对点
 *    SFU：客户端用 roomState.sfuToken 连接 LiveKit 收发媒体
 * 4. mediaState(100) 上报开关 → 服务端广播 participantNotify(26, media)
 * 5. leave(24)/end(25) → 广播成员变更/通话结束
 */
class GroupRtcEngine(
    private val context: Context,
    private val localUserId: () -> String,
    private val callback: GroupRtcEngineCallback,
) {

    companion object {
        private const val TAG = "GroupRtcEngine"

        /**
         * 进入 connecting 后未建联的连接超时（毫秒）
         *
         * 超时仍未收到任何媒体连接就绪事件，则以 failed 主动 leave 并本地收口，
         * 避免本端永远停留在连接中且其他成员无感知（服务端无法感知 Mesh 媒体层，
         * 生命周期信令是唯一通知渠道）。
         * 可变静态字段仅为单元测试可注入短超时。
         */
        @Volatile
        var connectTimeoutMs = 30_000L

        /** transport 未就绪期间媒体信令缓冲上限（防异常场景无限增长） */
        private const val MAX_BUFFERED_MEDIA_SIGNALS = 50
    }

    // ====================== 内部状态 ======================

    /** 引擎内部协程域（传输层启动等异步操作） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 主线程 Handler（连接超时与延迟恢复空闲） */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var state = GroupCallState.IDLE
    private var endReason = GroupCallEndReason.NONE

    private var callId = ""
    private var roomId = ""
    private var groupId = ""
    private var callType = RtcCallType.VIDEO
    private var mode = GroupCallMode.MESH
    private var isInitiator = false

    /** 成员快照（key=userId，含邀请/加入/离开/拒绝状态与媒体开关） */
    private val members = LinkedHashMap<String, GroupMemberInfo>()

    /** 媒体传输层（Mesh/SFU，roomState 到达后创建） */
    private var transport: GroupMediaTransport? = null

    /** transport 未就绪期间缓冲的媒体信令（callId 匹配），transport 启动后重放 */
    private val pendingMediaSignals = mutableListOf<ImProto.RtcSignal>()

    // ---- Mesh 本地媒体（引擎获取并持有；SFU 由 LiveKit 管理） ----
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = EglBase.create()
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var mediaReady = false

    private var cameraEnabled = true
    private var micEnabled = true

    private var callTimer: Timer? = null
    private var callDuration = 0

    /** 连接超时计时器（connecting 态启动，媒体就绪或结束后取消） */
    private val connectTimeoutRunnable = Runnable {
        if (state == GroupCallState.CONNECTING) {
            Log.d(TAG, "connect timeout ($connectTimeoutMs ms)")
            endLocalCallWithNotify(GroupCallEndReason.FAILED)
        }
    }

    // ====================== Getters ======================

    val currentState: GroupCallState get() = state
    val currentEndReason: GroupCallEndReason get() = endReason
    val currentCallId: String get() = callId
    val currentRoomId: String get() = roomId
    val currentGroupId: String get() = groupId
    val currentMode: GroupCallMode get() = mode
    val currentCallType: RtcCallType get() = callType
    val currentIsInitiator: Boolean get() = isInitiator
    val currentCallDuration: Int get() = callDuration

    /** 共享 EGL 上下文（供所有视频渲染器使用，避免多上下文割裂） */
    val eglBaseContext: EglBase.Context? get() = eglBase?.eglBaseContext

    /** Mesh 模式本地视频轨（本地预览渲染用） */
    val meshLocalVideoTrack: VideoTrack? get() = localVideoTrack

    val cameraEnabledState: Boolean
        get() = transport?.cameraEnabled ?: cameraEnabled

    val microphoneEnabledState: Boolean
        get() = transport?.microphoneEnabled ?: micEnabled

    /** 当前成员快照（按 userId 排序，UI 直接展示） */
    fun getMembers(): List<GroupMemberInfo> =
        members.values.sortedBy { it.userId }

    /** 查询单个成员 */
    fun getMember(userId: String): GroupMemberInfo? = members[userId]

    /** 仍在通话中的成员 ID 集合 */
    fun joinedMemberIds(): Set<String> = members
        .filterValues { it.status == GroupMemberStatus.JOINED }
        .keys

    // ====================== 公开 API（UI 层调用） ======================

    /** 发起群通话（主叫方入口） */
    fun startGroupCall(groupId: String, callType: RtcCallType, inviteeIds: List<String>? = null) {
        resetSession()
        this.groupId = groupId
        this.callType = callType
        this.isInitiator = true
        this.callId = UUID.randomUUID().toString() // 客户端生成，服务端沿用

        updateState(GroupCallState.CALLING)

        sendGroupSignal(
            GroupSignalType.GROUP_CALL_REQUEST,
            payload = GroupCallRequestPayload(
                callType = if (callType == RtcCallType.VIDEO) "video" else "audio",
                inviteeIds = inviteeIds,
            ).toJson().toString(),
        )
        Log.d(TAG, "group call requested: group=$groupId, callId=$callId")
    }

    /** 接受群通话邀请（被邀方入口） */
    fun acceptGroupCall() {
        if (roomId.isEmpty()) {
            callback.onError("无待接受的群通话邀请")
            return
        }
        updateState(GroupCallState.CONNECTING)
        startConnectTimer()
        sendGroupSignal(GroupSignalType.GROUP_CALL_JOIN)
        Log.d(TAG, "group call joined: room=$roomId")
    }

    /** 拒绝群通话邀请 */
    fun rejectGroupCall() {
        endReason = GroupCallEndReason.REJECTED
        sendGroupSignal(
            GroupSignalType.GROUP_CALL_REJECT,
            payload = GroupReasonPayload("reject").toJson().toString(),
        )
        endLocalCall()
    }

    /** 退出群通话（非发起人） */
    fun leaveGroupCall() {
        endReason = GroupCallEndReason.NORMAL
        sendGroupSignal(
            GroupSignalType.GROUP_CALL_LEAVE,
            payload = GroupReasonPayload("hangup").toJson().toString(),
        )
        endLocalCall()
    }

    /** 结束全员群通话（仅发起人，服务端校验） */
    fun endGroupCall() {
        if (!isInitiator) {
            // 非发起人降级为退出
            leaveGroupCall()
            return
        }
        endReason = GroupCallEndReason.ENDED
        sendGroupSignal(GroupSignalType.GROUP_CALL_END)
        endLocalCall()
    }

    /** 切换摄像头开关（切换后经 cmd=51 mediaState 上报，服务端广播给其他成员） */
    fun toggleCamera() {
        val next = !cameraEnabledState
        scope.launch {
            applyCameraEnabled(next)
            sendMediaState(camera = next)
        }
    }

    /** 切换麦克风开关 */
    fun toggleMicrophone() {
        val next = !microphoneEnabledState
        scope.launch {
            applyMicrophoneEnabled(next)
            sendMediaState(mic = next)
        }
    }

    /** 切换前后摄像头 */
    fun switchCamera() {
        try {
            cameraCapturer?.switchCamera(null)
            transport?.switchCamera()
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera failed: ${e.message}")
        }
    }

    // ====================== 信令分发入口 ======================

    /** 处理群通话信令（cmd=51，由项目层从 IM 事件中转调） */
    fun handleGroupSignal(signal: ImProto.RtcGroup) {
        Log.d(
            TAG, "group signal type=${signal.signalType} " +
                "from=${signal.senderId} room=${signal.roomId}"
        )
        when (signal.signalType) {
            GroupSignalType.GROUP_CALL_INVITE -> onInvite(signal)
            GroupSignalType.ROOM_STATE -> onRoomState(signal)
            GroupSignalType.PARTICIPANT_NOTIFY -> onParticipantNotify(signal)
            GroupSignalType.MEDIA_STATE ->
                // 兜底：mediaState 若以 cmd=51 直发（正常情况服务端聚合为 participantNotify）
                onDirectMediaState(signal)
            else -> Log.d(TAG, "ignore group signal: ${signal.signalType}")
        }
    }

    /**
     * 处理点对点媒体信令（cmd=50，Mesh 模式专用）
     *
     * transport 未创建时（roomState 处理中/本地媒体采集中）缓冲当前通话信令，
     * 就绪后重放：对端可能先于本端 roomState 到达即发来 offer —— 若直接丢弃，
     * 对端已发 offer 等 answer，双方互等永远无法建联（与 Flutter SDK 同款防御）
     */
    fun handleMediaSignal(signal: ImProto.RtcSignal) {
        val current = transport
        if (current != null) {
            current.handleMediaSignal(signal)
            return
        }
        if (callId.isNotEmpty() && signal.callId == callId) {
            if (pendingMediaSignals.size >= MAX_BUFFERED_MEDIA_SIGNALS) {
                pendingMediaSignals.removeAt(0)
            }
            pendingMediaSignals.add(signal)
            Log.d(
                TAG, "transport not ready, buffer media signal: " +
                    "type=${signal.signalType} from=${signal.senderId} " +
                    "(${pendingMediaSignals.size} pending)"
            )
        }
    }

    /** 当前群通话是否可处理该 callId 的点对点媒体信令 */
    fun canHandleMediaCallId(mediaCallId: String): Boolean {
        return state != GroupCallState.IDLE &&
            state != GroupCallState.ENDED &&
            callId.isNotEmpty() &&
            callId == mediaCallId
    }

    // ====================== 信令事件处理 ======================

    /** 收到群通话邀请（被邀方） */
    private fun onInvite(signal: ImProto.RtcGroup) {
        if (state != GroupCallState.IDLE) {
            Log.d(TAG, "busy, ignore invite from ${signal.senderId}")
            return
        }
        if (signal.payload.isEmpty()) {
            Log.d(TAG, "invite payload is empty, ignore")
            return
        }
        val invite = try {
            GroupCallInvite.fromJson(JSONObject(signal.payload))
        } catch (e: Exception) {
            Log.e(TAG, "parse invite error: ${e.message}")
            return
        }

        resetSession()
        groupId = invite.groupId.ifEmpty { signal.groupId }
        callId = signal.callId
        roomId = signal.roomId
        callType = if (invite.isVideoCall) RtcCallType.VIDEO else RtcCallType.AUDIO
        mode = invite.mode
        isInitiator = false
        // 发起人默认已在房
        if (invite.initiatorId.isNotEmpty()) {
            members[invite.initiatorId] = GroupMemberInfo(
                userId = invite.initiatorId,
                status = GroupMemberStatus.JOINED,
            )
        }

        updateState(GroupCallState.RINGING)
        callback.onIncomingGroupCall(invite, signal.roomId)
    }

    /** 收到房间快照（发起人与加入者都会收到） */
    private fun onRoomState(signal: ImProto.RtcGroup) {
        if (signal.payload.isEmpty()) return
        val roomState = try {
            GroupRoomState.fromJson(JSONObject(signal.payload))
        } catch (e: Exception) {
            Log.e(TAG, "parse roomState error: ${e.message}")
            return
        }

        roomId = roomState.roomId.ifEmpty { signal.roomId }
        callId = roomState.callId
        groupId = roomState.groupId
        mode = roomState.mode
        callType = if (roomState.isVideoCall) RtcCallType.VIDEO else RtcCallType.AUDIO
        isInitiator = roomState.initiatorId == localUserId()

        // 重建成员快照（保留已有媒体开关）
        mergeMembers(roomState.members)

        updateState(GroupCallState.CONNECTING)
        startConnectTimer()
        callback.onRoomStateChanged(roomState)
        callback.onMembersUpdated()

        scope.launch { startTransport(roomState) }
    }

    /** 收到成员变更通知 */
    private fun onParticipantNotify(signal: ImProto.RtcGroup) {
        if (signal.payload.isEmpty()) return
        val participant = try {
            GroupParticipant.fromJson(JSONObject(signal.payload))
        } catch (e: Exception) {
            Log.e(TAG, "parse participant error: ${e.message}")
            return
        }

        when (participant.action) {
            GroupParticipantAction.JOIN,
            GroupParticipantAction.REJECT,
            GroupParticipantAction.LEAVE -> applyMemberChange(participant)

            GroupParticipantAction.MEDIA -> applyMediaSnapshot(participant)

            GroupParticipantAction.ENDED -> {
                // 服务端广播通话结束（含邀请超时 reason=timeout）
                endReason = if (participant.reason == "timeout") {
                    GroupCallEndReason.TIMEOUT
                } else {
                    GroupCallEndReason.ENDED
                }
                endLocalCall()
            }
        }
    }

    /** 加入/拒绝/离开：更新成员状态并校准媒体连接 */
    private fun applyMemberChange(participant: GroupParticipant) {
        val userId = participant.userId
        val existing = members[userId]
        val status = when (participant.action) {
            GroupParticipantAction.JOIN -> GroupMemberStatus.JOINED
            GroupParticipantAction.REJECT -> GroupMemberStatus.REJECTED
            else -> GroupMemberStatus.LEFT
        }

        // 从成员快照中取该成员的最新媒体开关
        var snapshot: GroupMemberInfo? = null
        for (m in participant.members) {
            if (m.userId == userId) snapshot = m
        }

        members[userId] = GroupMemberInfo(
            userId = userId,
            status = status,
            camera = snapshot?.camera ?: existing?.camera,
            mic = snapshot?.mic ?: existing?.mic,
        )

        // 校准媒体连接（Mesh 关闭/新建对应 PeerConnection；SFU 由房间事件驱动）
        transport?.syncRemoteMembers(joinedMemberIds())
        if (participant.action == GroupParticipantAction.LEAVE) {
            callback.onRemoteMemberRemoved(userId)
        }
        if (snapshot?.camera != null || snapshot?.mic != null) {
            callback.onMemberMediaStateChanged(userId, snapshot?.camera, snapshot?.mic)
        }
        callback.onMembersUpdated()
        Log.d(TAG, "member ${participant.action}: $userId, joined=${joinedMemberIds().size}")
    }

    /** 媒体开关广播：用成员快照刷新本地缓存 */
    private fun applyMediaSnapshot(participant: GroupParticipant) {
        for (m in participant.members) {
            val existing = members[m.userId]
            members[m.userId] = GroupMemberInfo(
                userId = m.userId,
                status = existing?.status ?: m.status,
                camera = m.camera ?: existing?.camera,
                mic = m.mic ?: existing?.mic,
            )
            if (m.camera != null || m.mic != null) {
                callback.onMemberMediaStateChanged(m.userId, m.camera, m.mic)
            }
        }
        callback.onMembersUpdated()
    }

    /** cmd=51 直发 mediaState 兜底处理 */
    private fun onDirectMediaState(signal: ImProto.RtcGroup) {
        if (signal.payload.isEmpty()) return
        try {
            val payload = JSONObject(signal.payload)
            val camera = if (payload.has("camera") && !payload.isNull("camera")) {
                payload.getBoolean("camera")
            } else null
            val mic = if (payload.has("mic") && !payload.isNull("mic")) {
                payload.getBoolean("mic")
            } else null
            if (camera == null && mic == null) return
            val userId = signal.senderId
            val existing = members[userId]
            if (existing != null) {
                members[userId] = GroupMemberInfo(
                    userId = userId,
                    status = existing.status,
                    camera = camera ?: existing.camera,
                    mic = mic ?: existing.mic,
                )
            }
            callback.onMemberMediaStateChanged(userId, camera, mic)
            callback.onMembersUpdated()
        } catch (e: Exception) {
            Log.e(TAG, "parse mediaState error: ${e.message}")
        }
    }

    // ====================== 媒体与传输层 ======================

    /** 依据 roomState.mode 创建并启动传输层（工厂/策略模式） */
    private suspend fun startTransport(roomState: GroupRoomState) {
        transport?.dispose()

        val transportCallback = GroupMediaTransportCallback(
            onSendMediaSignal = { signal ->
                // Mesh 点对点媒体信令包装为 cmd=50 Packet
                callback.onSendMediaSignal(PacketCodec.create(Cmd.RTC_SIGNAL, body = signal))
            },
            onRemoteMemberMedia = callback::onRemoteMemberMedia,
            onRemoteMemberRemoved = callback::onRemoteMemberRemoved,
            onLocalVideoTrack = callback::onLocalVideoTrack,
            onMediaConnected = ::onMediaConnected,
            onTransportBroken = ::onTransportBroken,
            onError = callback::onError,
        )

        if (roomState.mode == GroupCallMode.SFU) {
            transport = SfuGroupTransport(context, transportCallback)
            Log.d(TAG, "transport: SFU (${roomState.sfuUrl})")
        } else {
            // Mesh：先获取本地媒体流（传输层建连时加入轨道）
            acquireLocalMedia()
            transport = MeshGroupTransport(
                localUserId = localUserId,
                callback = transportCallback,
                factory = getOrCreateFactory(),
                localAudioTrack = localAudioTrack,
                localVideoTrack = localVideoTrack,
            )
            Log.d(TAG, "transport: Mesh")
        }

        transport?.start(roomState)

        // 重放 transport 就绪前缓冲的媒体信令（offer/answer/ICE）
        replayBufferedMediaSignals()
    }

    /** 重放缓冲的媒体信令（transport 创建并 start 后调用） */
    private fun replayBufferedMediaSignals() {
        val current = transport ?: return
        if (pendingMediaSignals.isEmpty()) return
        val buffered = pendingMediaSignals.toList()
        pendingMediaSignals.clear()
        Log.d(TAG, "replay ${buffered.size} buffered media signals")
        for (signal in buffered) {
            current.handleMediaSignal(signal)
        }
    }

    /** 媒体连接就绪（首个对端连通 / SFU 房间连接成功）→ 启动计时 */
    private fun onMediaConnected() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        if (state == GroupCallState.CONNECTED) return
        updateState(GroupCallState.CONNECTED)
        startTimer()
        Log.d(TAG, "media connected, timer started")
    }

    /** 传输层不可恢复故障（SFU 连接失败/房间断开） */
    private fun onTransportBroken(message: String) {
        if (state == GroupCallState.ENDED || state == GroupCallState.IDLE) return
        Log.d(TAG, "transport broken: $message")
        endLocalCallWithNotify(GroupCallEndReason.FAILED)
    }

    /** 获取本地媒体轨道（Mesh 专用） */
    private fun acquireLocalMedia() {
        if (mediaReady) return
        try {
            val fac = getOrCreateFactory()

            // 音频轨道
            audioSource = fac.createAudioSource(org.webrtc.MediaConstraints())
            localAudioTrack = fac.createAudioTrack("group_audio_track", audioSource)

            // 视频轨道（仅视频通话）
            if (callType == RtcCallType.VIDEO) {
                val capturer = createCameraCapturer()
                if (capturer != null) {
                    cameraCapturer = capturer
                    videoSource = fac.createVideoSource(false)
                    val surfaceTextureHelper = SurfaceTextureHelper.create(
                        "GroupCaptureThread",
                        eglBase?.eglBaseContext,
                    )
                    capturer.initialize(
                        surfaceTextureHelper,
                        context.applicationContext,
                        videoSource!!.capturerObserver,
                    )
                    capturer.startCapture(640, 480, 30)
                    localVideoTrack = fac.createVideoTrack("group_video_track", videoSource)
                }
            }

            cameraEnabled = localVideoTrack?.enabled() ?: true
            micEnabled = localAudioTrack?.enabled() ?: true
            mediaReady = true
            callback.onLocalStreamReady(localAudioTrack, localVideoTrack)
            Log.d(
                TAG, "local media acquired: audio=${localAudioTrack != null}, " +
                    "video=${localVideoTrack != null}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "acquire local media error: ${e.message}")
            callback.onError("本地媒体获取失败: ${e.message}")
        }
    }

    /** 应用摄像头开关（Mesh 切轨道 / SFU 切发布） */
    private suspend fun applyCameraEnabled(enabled: Boolean) {
        if (mode == GroupCallMode.MESH) {
            localVideoTrack?.setEnabled(enabled)
            cameraEnabled = enabled
        } else {
            transport?.setCameraEnabled(enabled)
        }
    }

    /** 应用麦克风开关 */
    private suspend fun applyMicrophoneEnabled(enabled: Boolean) {
        if (mode == GroupCallMode.MESH) {
            localAudioTrack?.setEnabled(enabled)
            micEnabled = enabled
        } else {
            transport?.setMicrophoneEnabled(enabled)
        }
    }

    // ====================== 信令发送 ======================

    /** 发送群通话生命周期信令（cmd=51） */
    private fun sendGroupSignal(signalType: Int, payload: String = "") {
        val packet = PacketCodec.buildRtcGroup(
            signalType = signalType,
            senderId = localUserId(),
            groupId = groupId,
            payload = payload,
            callId = callId,
            roomId = roomId,
        )
        callback.onSendGroupSignal(packet)
    }

    /** 上报媒体开关状态（cmd=51 mediaState，payload 只带变化项） */
    private fun sendMediaState(camera: Boolean? = null, mic: Boolean? = null) {
        if (camera == null && mic == null) return
        val payload = GroupMediaStatePayload(camera = camera, mic = mic)
        if (payload.toJson().length() == 0) return
        sendGroupSignal(GroupSignalType.MEDIA_STATE, payload = payload.toJson().toString())
        Log.d(TAG, "media state sent: camera=$camera, mic=$mic")
    }

    // ====================== 会话生命周期 ======================

    /** 本地结束通话（发出 leave/end 或收到 ended 后调用） */
    private fun endLocalCall() {
        callTimer?.cancel()
        mainHandler.removeCallbacks(connectTimeoutRunnable)

        if (endReason == GroupCallEndReason.NONE) {
            endReason = GroupCallEndReason.FAILED
        }

        callback.onCallEnded(endReason)
        updateState(GroupCallState.ENDED)
        cleanup()

        // 延迟恢复空闲，避免 ended 状态闪过
        mainHandler.postDelayed({
            if (state == GroupCallState.ENDED) {
                updateState(GroupCallState.IDLE)
            }
        }, 1000)
    }

    /** 以指定原因结束通话并通知服务端（本地异常终止统一入口） */
    private fun endLocalCallWithNotify(reason: GroupCallEndReason) {
        if (state == GroupCallState.ENDED || state == GroupCallState.IDLE) return
        endReason = reason
        sendGroupSignal(
            GroupSignalType.GROUP_CALL_LEAVE,
            payload = GroupReasonPayload("failed").toJson().toString(),
        )
        endLocalCall()
    }

    /** 启动连接超时计时（connecting 态调用，媒体就绪或结束后取消） */
    private fun startConnectTimer() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.postDelayed(connectTimeoutRunnable, connectTimeoutMs)
    }

    /** 清理会话资源 */
    private fun cleanup() {
        try {
            transport?.dispose()
            transport = null
            pendingMediaSignals.clear()
            // 停止摄像头捕获
            try {
                cameraCapturer?.stopCapture()
            } catch (_: Exception) {
            }
            cameraCapturer?.dispose()
            cameraCapturer = null
            localVideoTrack?.dispose()
            localVideoTrack = null
            localAudioTrack?.dispose()
            localAudioTrack = null
            videoSource?.dispose()
            videoSource = null
            audioSource?.dispose()
            audioSource = null
            // eglBase 全局复用：不在单次通话清理中释放
            mediaReady = false
            members.clear()
            roomId = ""
            callId = ""
        } catch (e: Exception) {
            Log.e(TAG, "cleanup error: ${e.message}")
        }
    }

    /** 重置会话字段（不清理媒体，媒体在 cleanup 中处理） */
    private fun resetSession() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        endReason = GroupCallEndReason.NONE
        callId = ""
        roomId = ""
        groupId = ""
        isInitiator = false
        mode = GroupCallMode.MESH
        members.clear()
        cameraEnabled = true
        micEnabled = true
        callDuration = 0
    }

    /** 启动通话计时 */
    private fun startTimer() {
        callDuration = 0
        callTimer?.cancel()
        callTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    callDuration += 1
                    callback.onCallDurationTick(callDuration)
                }
            }, 1000, 1000)
        }
    }

    /** 合并成员快照（保留已有媒体开关） */
    private fun mergeMembers(incoming: List<GroupMemberInfo>) {
        for (m in incoming) {
            val existing = members[m.userId]
            members[m.userId] = GroupMemberInfo(
                userId = m.userId,
                status = m.status,
                camera = m.camera ?: existing?.camera,
                mic = m.mic ?: existing?.mic,
            )
        }
    }

    /** 更新状态并通知 */
    private fun updateState(newState: GroupCallState) {
        state = newState
        callback.onCallStateChanged(newState)
    }

    /** 创建摄像头捕获器（优先前置，回退后置；与 1:1 RtcEngine 一致） */
    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context.applicationContext)
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }
        for (name in enumerator.deviceNames) {
            if (enumerator.isBackFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }
        Log.e(TAG, "No camera device found (devices=${enumerator.deviceNames.contentToString()})")
        return null
    }

    /**
     * 获取或创建 PeerConnectionFactory
     *
     * 必须挂载 Default 编解码工厂（含硬件编解码），复用全局 EGL 上下文，
     * 与 1:1 RtcEngine 保持一致（详见 RtcEngine.getOrCreateFactory 注释）。
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

    /** 释放所有资源 */
    fun dispose() {
        callTimer?.cancel()
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        scope.launch { transport?.dispose() }
        transport = null
        cleanup()
        eglBase?.release()
        eglBase = null
    }
}
