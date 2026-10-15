# WebRTC：1:1 通话

`RtcEngine` 封装 1:1 音视频通话的完整 WebRTC 信令流程（标准 offer/answer/ICE 模型）与本地媒体管理，纯信令转发、P2P 直连。与 1:1 生命周期/忙线/超时清理由服务端（gim-sdk-java）协同。

## 核心类型（RtcTypes.kt）

```kotlin
enum class RtcCallType { AUDIO, VIDEO }

enum class RtcCallState {
    IDLE, CALLING, RINGING, CONNECTING, CONNECTED, ENDED
}

object RtcSignalType {           // signalType 字段值（cmd=50 RTC_SIGNAL）
    const val OFFER = 1;  const val ANSWER = 2;  const val ICE_CANDIDATE = 3
    const val CALL_REQUEST = 4; const val CALL_ACCEPT = 5
    const val CALL_REJECT = 6;  const val CALL_CANCEL = 7; const val CALL_HANGUP = 8
    const val CALL_ACK = 9       // 服务端→主叫：回传服务端生成的权威 callId
    const val MEDIA_STATE = 100  // 摄像头/麦克风开关
}

enum class RtcCallEndReason { NONE, NORMAL, REJECTED, CANCELLED, BUSY, TIMEOUT, FAILED }
```

## 初始化

```kotlin
// 全局仅需一次（用 ApplicationContext）
RtcEngine.initialize(appContext)
```

## 创建引擎并桥接信令

`RtcEngine` 通过 `RtcEngineCallback` 与外界交互——它自己不发网络包，需要项目层把信令 `Packet` 经 IM 通道发出、把收到的信令转交引擎。

```kotlin
val rtc = RtcEngine(appContext, { currentUserId }, object : RtcEngineCallback {
    override fun onSendSignal(packet: ImProto.Packet) = service.send(packet)   // 信令上行

    override fun onCallStateChanged(state: RtcCallState) { /* 驱动 UI */ }
    override fun onCallEnded(reason: RtcCallEndReason) { /* 结束页 */ }
    override fun onRemoteVideoTrack(track: VideoTrack) { /* 绑定渲染 */ }
    override fun onCallDurationTick(seconds: Int) { /* 计时 */ }
    override fun onIncomingCall(senderId: String, callId: String) { /* 来电 UI */ }
    override fun onRemoteMediaStateChanged(camera: Boolean?, mic: Boolean?) { /* 图标 */ }
})

// IM 事件里把 RTC 信令转交引擎
override fun onRtcSignal(packet: ImProto.Packet) {
    rtc.handleSignal(PacketCodec.parseRtcSignal(packet))
}
```

## 通话时序

```
主叫 startCall(target, VIDEO)
  → CALL_REQUEST ───────────────────────▶ 服务端 ──CALL_REQUEST(+callId)──▶ 被叫（RINGING）
  ◀── CALL_ACK(9) 回填权威 callId ─────── 服务端
                                           被叫 acceptCall() ──CALL_ACCEPT──▶ 服务端 ──▶ 主叫
主叫 onCallAccept → initPeerConnection → createOffer → OFFER ─▶ 被叫
被叫 onOffer → setRemote → createAnswer → ANSWER ─▶ 主叫
主叫 onAnswer → setRemoteDescription
双方交换 ICE_CANDIDATE → 媒体连通 → CONNECTED → 开始计时
```

- **主叫**：`suspend fun startCall(targetUserId, type)`，`callId` 留空由服务端生成，经 `CALL_ACK(9)` 回填。
- **被叫**：收到 `CALL_REQUEST` 触发 `onIncomingCall`；`suspend fun acceptCall()` 接听，`rejectCall()` 拒绝。
- **取消/挂断**：`cancelCall()`（呼叫中取消）、`hangup()`（通话中挂断）。

## 媒体控制

```kotlin
rtc.toggleCamera()       // 开关摄像头（发 MEDIA_STATE 通知对端）
rtc.toggleMicrophone()   // 开关麦克风
rtc.switchCamera()       // 前后摄像头切换
val local: VideoTrack? = rtc.localVideoTrack        // 本地预览
val egl = rtc.eglBaseContext                        // 共享 EGL，供 SurfaceViewRenderer
```

## 渲染远端视频

```kotlin
override fun onRemoteVideoTrack(track: VideoTrack) {
    track.setEnabled(true)
    // 用共享 eglBaseContext 初始化 SurfaceViewRenderer 后：
    remoteRenderer.let { track.addSink(it) }
}
```

## TURN/STUN 与 ICE

- 服务端可随 `CALL_REQUEST`/`CALL_ACCEPT` 的 payload 下发 `turnUrl/stunUrl/username/credential`，引擎解析后构建 ICE Server。
- 无服务端凭证时回退 Google 公共 STUN（`stun:stun.l.google.com:19302`）。
- **必须挂载硬件编解码工厂**：`getOrCreateFactory` 使用 `DefaultVideoEncoderFactory/DefaultVideoDecoderFactory`，否则 `setRemoteDescription` 会因无可用编解码失败。

## 健壮性设计

| 场景 | 处理 |
|------|------|
| 信令重投 | `negotiating` / `_remoteDescSet` / `lastCallId` 幂等拦截重复 offer/accept/request |
| 通话中来电 | `CALL_REQUEST` 在 busy 态被忽略，防串线 |
| 过期信令 | `callId` 不匹配的 reject/cancel/hangup/mediaState 丢弃 |
| 引擎已清理 | `disposed` 后仅放行新来电信令，防止操作已释放的 PeerConnection 导致 native 崩溃 |
| ICE 早于描述 | `pendingCandidates` 缓冲，远端描述设置后 `flushPendingCandidates` 回放 |
| SDP 方向诊断 | `logSdpDirections` + 视频收发统计定时输出 |

## 结束与释放

通话结束 `endCall()` 触发 `onCallEnded(reason)`，`cleanup()` 释放捕获器/轨道/源（`EglBase` 与 `factory` 全局复用不释放），1 秒后回 `IDLE`。Activity 销毁调用 `rtc.dispose()`。

## 相关文档

- [WebRTC：群视频通话（Mesh + SFU）](WebRTC：群视频通话)
- [核心概念：Packet 与协议编解码](核心概念：Packet与协议编解码)
