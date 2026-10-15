# WebRTC：群视频通话（Mesh + SFU）

`GroupRtcEngine` 负责群通话的**生命周期信令**（`cmd=51 RTC_GROUP`），媒体传输层按 `roomState.mode` 由工厂选择 **Mesh（P2P 多路）** 或 **SFU（LiveKit）** 实现（`cmd=50` 仅用于 Mesh 点对点媒体信令）。

## 核心类型（GroupRtcTypes.kt）

```kotlin
object GroupSignalType {          // signalType（cmd=51）
    const val GROUP_CALL_REQUEST = 20   // 客户端→服务端 发起
    const val GROUP_CALL_INVITE  = 21   // 服务端→被邀
    const val GROUP_CALL_JOIN    = 22   // 客户端→服务端 加入
    const val GROUP_CALL_REJECT  = 23
    const val GROUP_CALL_LEAVE   = 24
    const val GROUP_CALL_END     = 25   // 仅发起人
    const val PARTICIPANT_NOTIFY = 26   // 服务端→成员 变更通知
    const val ROOM_STATE         = 27   // 服务端→发起人/加入者 房间快照
    const val MEDIA_STATE        = 100  // 媒体开关（与 1:1 共用编号）
}

enum class GroupCallMode { MESH, SFU }                 // RtcGroup.mode 0-Mesh 1-SFU
enum class GroupMemberStatus { INVITED, JOINED, LEFT, REJECTED }
enum class GroupParticipantAction { JOIN, LEAVE, REJECT, ENDED, MEDIA }
enum class GroupCallState { IDLE, CALLING, RINGING, CONNECTING, CONNECTED, ENDED }
enum class GroupCallEndReason { NONE, NORMAL, REJECTED, TIMEOUT, ENDED, FAILED }
```

## 创建引擎并桥接

```kotlin
val group = GroupRtcEngine(appContext, { currentUserId }, object : GroupRtcEngineCallback {
    override fun onSendGroupSignal(packet: ImProto.Packet) = service.send(packet)  // cmd=51
    override fun onSendMediaSignal(packet: ImProto.Packet) = service.send(packet)   // cmd=50（Mesh）
    override fun onCallStateChanged(state: GroupCallState) {}
    override fun onIncomingGroupCall(invite: GroupCallInvite, roomId: String) {}
    override fun onRoomStateChanged(roomState: GroupRoomState) {}
    override fun onLocalStreamReady(audio: AudioTrack?, video: VideoTrack?) {}   // Mesh 本地轨
    override fun onLocalVideoTrack(track: io.livekit.android.room.track.Track?) {} // SFU 本地轨
    override fun onRemoteMemberMedia(media: GroupRemoteMemberMedia) {}
    override fun onRemoteMemberRemoved(userId: String) {}
    override fun onMemberMediaStateChanged(userId: String, camera: Boolean?, mic: Boolean?) {}
    override fun onMembersUpdated() {}
    override fun onCallEnded(reason: GroupCallEndReason) {}
    override fun onCallDurationTick(seconds: Int) {}
    override fun onError(message: String) {}
})

// IM 事件转交
override fun onRtcGroupSignal(packet: ImProto.Packet) {
    group.handleGroupSignal(PacketCodec.parseRtcGroup(packet))
}
override fun onRtcSignal(packet: ImProto.Packet) {
    val s = PacketCodec.parseRtcSignal(packet)
    if (group.canHandleMediaCallId(s.callId)) group.handleMediaSignal(s)  // Mesh 媒体走 cmd=50
    else rtc?.handleSignal(s)                                            // 否则交 1:1 引擎
}
```

## 时序（与服务端 GroupCallService 对应）

```
1. 发起人 startGroupCall(groupId, type, inviteeIds?)  ──groupCallRequest(20)──▶ 服务端建房
      发起人 ◀──roomState(27)──  成员 ◀──invite(21)──
2. 被邀 acceptGroupCall() ──groupCallJoin(22)─▶ 服务端 ──roomState(27)──▶ 本人
      在房成员 ◀──participantNotify(26, join)──
3. 媒体：
      Mesh：成员按快照建连，offer/answer/ICE 走 cmd=50 点对点
      SFU ：客户端用 roomState.sfuUrl + sfuToken 连 LiveKit 收发媒体
4. mediaState(100) 上报开关 ─▶ 服务端广播 participantNotify(26, media)
5. leave(24) / end(25) ─▶ 服务端广播成员变更 / ended
```

## 公开 API

```kotlin
group.startGroupCall(groupId, RtcCallType.VIDEO, inviteeIds = listOf("u2","u3")) // 邀请为空则服务端邀全体活跃成员
group.acceptGroupCall()     // 被邀方接受
group.rejectGroupCall()     // 被邀方拒绝
group.leaveGroupCall()      // 非发起人退出
group.endGroupCall()        // 发起人结束全员（非发起人降级为 leave）
group.toggleCamera(); group.toggleMicrophone(); group.switchCamera()

group.getMembers(): List<GroupMemberInfo>   // 当前成员快照（按 userId 排序）
group.getMember(userId): GroupMemberInfo?
group.joinedMemberIds(): Set<String>
group.eglBaseContext                        // Mesh 渲染共享 EGL
group.meshLocalVideoTrack                   // Mesh 本地预览轨
```

## Mesh 传输层（MeshGroupTransport）

- 成员间 P2P 直连，服务端只扇出信令；适合小群（默认 ≤8 人）。
- **Offer 发起规则**：`isMeshOfferer(local, peer)` 取 userId 字典序较小方发起，两端计算一致，避免同时 Offer 冲突。
- offer/answer/ICE 经 `cmd=50 RtcSignal` 点对点，`callId` 携带用于对端过滤串线。
- `syncRemoteMembers` 依据服务端最新快照补建/关闭连接：新加入成员补建（含发起人后加入者场景），离开成员关连接；**排除自己**避免建空连接。
- UNIFIED_PLAN 语义，远端媒体经 `onTrack` 到达。

## SFU 传输层（SfuGroupTransport）

- 媒体由 LiveKit 承载，适合大群（20+ 人）；`cmd=50` 媒体信令在此模式为空操作。
- `start` 用 `sfuUrl + sfuToken` 连接房间，`setMicrophoneEnabled/setCameraEnabled` 发布本地媒体（音频通话不发布视频轨）。
- 成员进出以**房间事件为准**（`ParticipantConnected/Disconnected`、`TrackSubscribed/Unsubscribed`），上报 `onRemoteMemberMedia`（`sfuVideoTrack`）。
- 房间断开/连接失败 → `onTransportBroken` → 引擎以 `FAILED` 主动 leave 收口。

## 统一媒体载体 GroupRemoteMemberMedia

```kotlin
data class GroupRemoteMemberMedia(
    val userId: String,
    val meshVideoTrack: org.webrtc.VideoTrack? = null,   // Mesh：SurfaceViewRenderer.addSink
    val sfuVideoTrack: io.livekit.android.room.track.Track? = null, // SFU：LiveKit VideoView
    val hasVideo: Boolean = true,
) { val isSfu get() = sfuVideoTrack != null }            // UI 据此选择渲染组件
```

## 健壮性设计

| 场景 | 处理 |
|------|------|
| transport 未就绪时收到 offer | `pendingMediaSignals` 缓冲（上限 50），`startTransport` 后重放，防"双方互等" |
| 建连超时 | `connectTimeoutMs`（默认 30s）到点仍无媒体就绪 → `FAILED` 主动 leave |
| 忙线 | 非 IDLE 态收到 `invite` 忽略 |
| TURN/STUN | Mesh 用 `roomState.turnInfo`，缺失回退 Google STUN |
| 成员状态 | `roomState`/`participantNotify` 合并快照，保留未上报的媒体开关 |

## TURN 凭据与 payload DTO（GroupRtcDto.kt）

客户端解析/构建的 JSON payload（字段与服务端 `gim-im-webrtc` dto 一一对应，使用 `org.json`）：`TurnCredentials`、`GroupMemberInfo`、`GroupRoomState`、`GroupCallInvite`、`GroupParticipant`、`GroupCallRequestPayload`、`GroupMediaStatePayload`、`GroupReasonPayload`。均有 `fromJson`/`toJson`，并有纯 JVM 单测覆盖。

## 相关文档

- [WebRTC：1:1 通话](WebRTC：1对1通话)
- [核心概念：Packet 与协议编解码](核心概念：Packet与协议编解码)
- [Android 集成与生命周期](Android集成与生命周期)
