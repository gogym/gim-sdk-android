package io.getbit.gim.sdk.rtc.group

import android.content.Context
import android.util.Log
import io.getbit.gim.sdk.protocol.ImProto
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SFU 群通话媒体传输层（LiveKit 承载媒体，适合大群）
 *
 * 职责：
 * - 用 roomState 下发的 sfuUrl + sfuToken 连接 LiveKit 房间
 * - 发布本地摄像头/麦克风（由 LiveKit 统一采集管理）
 * - 订阅远端成员轨道并通过 [GroupMediaTransportCallback] 上报
 * - 生命周期信令仍由引擎经 cmd=51 收发，媒体不经信令通道
 */
class SfuGroupTransport(
    private val context: Context,
    private val callback: GroupMediaTransportCallback,
) : GroupMediaTransport {

    companion object {
        private const val TAG = "SfuGroupTransport"
    }

    // ====================== 内部状态 ======================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var room: Room? = null

    private var eventsJob: Job? = null

    private var isCameraEnabled = false
    private var isMicEnabled = false

    @Volatile
    private var disposed = false

    // ====================== GroupMediaTransport ======================

    override suspend fun start(roomState: GroupRoomState) {
        if (roomState.sfuUrl.isEmpty() || roomState.sfuToken.isEmpty()) {
            callback.onTransportBroken?.invoke("SFU 接入信息缺失（sfuUrl/sfuToken 为空）")
            return
        }
        if (disposed) return

        val newRoom = LiveKit.create(
            context.applicationContext,
            options = io.livekit.android.RoomOptions(
                adaptiveStream = true,
                dynacast = true,
            ),
        )
        room = newRoom

        // 媒体事件：成员进出与轨道订阅变化（与服务端 participantNotify 双向校准）
        eventsJob = scope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        Log.d(TAG, "room connected")
                        callback.onMediaConnected?.invoke()
                    }
                    is RoomEvent.ParticipantConnected ->
                        publishRemoteMedia(event.participant)
                    is RoomEvent.ParticipantDisconnected -> {
                        val identity = event.participant.identity?.value ?: ""
                        Log.d(TAG, "participant disconnected: $identity")
                        callback.onRemoteMemberRemoved?.invoke(identity)
                    }
                    is RoomEvent.TrackSubscribed ->
                        publishRemoteMedia(event.participant)
                    is RoomEvent.TrackUnsubscribed ->
                        publishRemoteMedia(event.participant)
                    is RoomEvent.Disconnected -> {
                        Log.d(TAG, "room disconnected: ${event.error}")
                        callback.onError?.invoke("SFU 房间已断开: ${event.error}")
                        // 媒体通道不可用，通知引擎以 failed 主动 leave 收口
                        callback.onTransportBroken?.invoke("SFU 房间已断开: ${event.error}")
                    }
                    else -> {}
                }
            }
        }

        try {
            withContext(Dispatchers.IO) {
                newRoom.connect(roomState.sfuUrl, roomState.sfuToken)
            }
        } catch (e: Exception) {
            callback.onError?.invoke("SFU 连接失败: ${e.message}")
            // 媒体通道不可用，通知引擎以 failed 主动 leave 收口
            callback.onTransportBroken?.invoke("SFU 连接失败: ${e.message}")
            return
        }

        // 发布本地媒体（LiveKit 统一采集；音频通话不发布视频轨）
        try {
            val local = newRoom.localParticipant
            local.setMicrophoneEnabled(true)
            isMicEnabled = true
            if (roomState.isVideoCall) {
                setCameraEnabled(true)
                // 本地视频轨就绪，供 UI 预览
                callback.onLocalVideoTrack?.invoke(findLocalVideoTrack(local))
            } else {
                callback.onLocalVideoTrack?.invoke(null)
            }
        } catch (e: Exception) {
            callback.onError?.invoke("本地媒体发布失败: ${e.message}")
        }
    }

    override fun handleMediaSignal(signal: ImProto.RtcSignal) {
        // SFU 模式媒体由 LiveKit 通道承载，cmd=50 点对点媒体信令不适用
    }

    override fun syncRemoteMembers(joinedUserIds: Set<String>) {
        // LiveKit 的成员进出由房间事件驱动（source of truth），
        // 服务端快照仅用于引擎成员状态校准，此处无需处理
        Log.d(TAG, "syncRemoteMembers(server): ${joinedUserIds.size}")
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        val local = room?.localParticipant ?: return
        local.setCameraEnabled(enabled)
        isCameraEnabled = enabled
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        val local = room?.localParticipant ?: return
        local.setMicrophoneEnabled(enabled)
        isMicEnabled = enabled
    }

    override fun switchCamera() {
        try {
            val track = findLocalVideoTrack(room?.localParticipant ?: return)
                as? LocalVideoTrack ?: return
            track.switchCamera()
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera failed: ${e.message}")
        }
    }

    override val cameraEnabled: Boolean
        get() = isCameraEnabled

    override val microphoneEnabled: Boolean
        get() = isMicEnabled

    override fun dispose() {
        if (disposed) return
        disposed = true
        try {
            eventsJob?.cancel()
            room?.disconnect()
            room = null
        } catch (e: Exception) {
            Log.e(TAG, "dispose error: ${e.message}")
        }
        scope.cancel()
        Log.d(TAG, "disposed")
    }

    // ====================== 远端媒体上报 ======================

    /** 上报远端成员当前媒体状态（按订阅轨道有无视频刷新载体） */
    private fun publishRemoteMedia(participant: RemoteParticipant) {
        if (disposed) return
        val identity = participant.identity?.value ?: ""
        var videoTrack: RemoteVideoTrack? = null
        for (pub in participant.videoTrackPublications) {
            val (publication, track) = pub
            if (track is RemoteVideoTrack && publication.subscribed) {
                videoTrack = track
                break
            }
        }
        callback.onRemoteMemberMedia?.invoke(
            GroupRemoteMemberMedia(
                userId = identity,
                sfuVideoTrack = videoTrack,
                hasVideo = videoTrack != null,
            )
        )
    }

    /** 查找本地摄像头轨道（无发布时返回 null） */
    private fun findLocalVideoTrack(local: LocalParticipant): Track? {
        val pub = local.getTrackPublication(Track.Source.CAMERA) ?: return null
        return pub.track
    }
}
