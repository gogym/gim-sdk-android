package io.getbit.gim.sdk.rtc.group

import io.getbit.gim.sdk.protocol.ImProto
import io.livekit.android.room.track.Track
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/**
 * 远端成员媒体载体（屏蔽 Mesh / SFU 差异）
 *
 * - Mesh 模式：[meshVideoTrack] 为 org.webrtc 的 VideoTrack，
 *   UI 层用 SurfaceViewRenderer 绑定渲染；
 * - SFU 模式：[sfuVideoTrack] 为 LiveKit 的 VideoTrack，
 *   UI 层用 livekit 的 VideoView 渲染（音频轨道自动播放）。
 *
 * @property userId 成员 userId（Mesh 为信令 userId；SFU 为 LiveKit participant.identity，与服务端 userId 一致）
 * @property hasVideo 是否包含视频轨
 */
data class GroupRemoteMemberMedia(
    val userId: String,
    val meshVideoTrack: VideoTrack? = null,
    val sfuVideoTrack: Track? = null,
    val hasVideo: Boolean = true,
) {
    /** 是否为 SFU 载体（UI 层据此选择渲染组件） */
    val isSfu: Boolean get() = sfuVideoTrack != null
}

/**
 * 群通话媒体传输层回调
 *
 * 传输层只负责媒体（建连/发布/订阅/开关），生命周期信令由引擎统一收发。
 */
class GroupMediaTransportCallback(
    /** Mesh 模式：发送点对点媒体信令（cmd=50 RtcSignal，offer/answer/ICE），引擎包装为 Packet 发出 */
    val onSendMediaSignal: ((ImProto.RtcSignal) -> Unit)? = null,

    /** 远端成员媒体新增/更新（去重由引擎按 userId 覆盖） */
    val onRemoteMemberMedia: ((GroupRemoteMemberMedia) -> Unit)? = null,

    /** 远端成员媒体移除（成员离开/断连） */
    val onRemoteMemberRemoved: ((String) -> Unit)? = null,

    /** SFU 模式：本地视频轨就绪（UI 预览），音频通话回调 null */
    val onLocalVideoTrack: ((Track?) -> Unit)? = null,

    /** 媒体连接就绪（Mesh：首个对端连通；SFU：房间连接成功）→ 引擎启动计时 */
    val onMediaConnected: (() -> Unit)? = null,

    /** 传输层错误（不中断通话，仅上报供 UI 提示/日志） */
    val onError: ((String) -> Unit)? = null,

    /**
     * 传输层不可恢复故障（SFU 连接失败/房间断开等，媒体通道已不可用）
     *
     * 引擎收到后以 failed 主动 leave 并本地收口；
     * Mesh 模式单条对端连接失败不视为通话故障，不触发本回调。
     */
    val onTransportBroken: ((String) -> Unit)? = null,
)

/**
 * 群通话媒体传输层抽象
 *
 * 统一 Mesh（P2P 多路）与 SFU（LiveKit）两套实现的契约，
 * 引擎依据 roomState.mode 通过工厂选择实现。
 */
interface GroupMediaTransport {
    /**
     * 启动传输层（收到 roomState 快照后调用）
     *
     * Mesh：对快照中已 joined 的成员逐一建连（含 Offer 发起决策）；
     * SFU：用 sfuUrl + sfuToken 连接 LiveKit 房间并发布本地媒体。
     */
    suspend fun start(roomState: GroupRoomState)

    /**
     * 处理点对点媒体信令（Mesh 专用：offer/answer/ICE，引擎从 cmd=50 转发）
     *
     * SFU 实现为空操作（媒体由 LiveKit 通道承载）。
     */
    fun handleMediaSignal(signal: ImProto.RtcSignal)

    /**
     * 按服务端最新成员快照校准远端连接（关闭已离开成员的连接/订阅）
     *
     * [joinedUserIds] 当前仍在通话中的成员 userId 集合。
     */
    fun syncRemoteMembers(joinedUserIds: Set<String>)

    /** 开关摄像头（成功后状态以 [cameraEnabled] 为准） */
    suspend fun setCameraEnabled(enabled: Boolean)

    /** 开关麦克风 */
    suspend fun setMicrophoneEnabled(enabled: Boolean)

    /** 切换前后摄像头 */
    fun switchCamera()

    /** 当前摄像头开关状态 */
    val cameraEnabled: Boolean

    /** 当前麦克风开关状态 */
    val microphoneEnabled: Boolean

    /** 释放传输层资源（不释放引擎持有的本地媒体流） */
    fun dispose()
}

/** 构建 ICE 服务器配置（对标 Flutter buildGroupIceServers， TURN 优先，Google STUN 兜底） */
fun buildGroupIceServers(turnInfo: TurnCredentials?): List<PeerConnection.IceServer> {
    val servers = mutableListOf<PeerConnection.IceServer>()
    if (turnInfo != null && turnInfo.turnUrl.isNotEmpty()) {
        if (turnInfo.stunUrl.isNotEmpty()) {
            servers.add(PeerConnection.IceServer.builder(turnInfo.stunUrl).createIceServer())
        }
        servers.add(
            PeerConnection.IceServer.builder(turnInfo.turnUrl)
                .setUsername(turnInfo.username)
                .setPassword(turnInfo.credential)
                .createIceServer()
        )
        return servers
    }
    // 回退到 Google 公共 STUN（与 1:1 RtcEngine 行为一致）
    servers.add(
        PeerConnection.IceServer.builder(FALLBACK_STUN_URL).createIceServer()
    )
    return servers
}

/** Google 公共 STUN 兜底地址 */
const val FALLBACK_STUN_URL = "stun:stun.l.google.com:19302"

/**
 * 判断 Mesh 模式下由谁发起 Offer（确定性规则，避免双方同时 Offer 冲突）
 *
 * userId 字典序较小的一方发起，两端计算结果一致。
 */
fun isMeshOfferer(localUserId: String, peerUserId: String): Boolean {
    return localUserId.compareTo(peerUserId) < 0
}
