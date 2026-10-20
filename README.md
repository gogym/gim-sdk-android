# GIM IM SDK (Android)

[**中文**](README.md) | [English](README.en.md)

高性能、开箱即用的 **Android IM 客户端 SDK**，基于 **Kotlin + Protobuf + WebRTC** 构建，对接 [gim-sdk-java](https://github.com/gogym/gim-sdk-java) 服务端，覆盖长连接管理、单聊/群聊消息收发、心跳保活、自动重连、被踢下线处理、1:1 音视频通话与群视频通话（Mesh + SFU）等能力。

A high-performance, ready-to-use IM client SDK for Android built on **Kotlin + Protobuf + WebRTC**, paired with the [gim-sdk-java](https://github.com/gogym/gim-sdk-java) server. Covers long-connection management, 1:1 / group chat, heartbeat, auto-reconnect, kick handling, 1:1 calls and group calls (Mesh + SFU).

---

Copyright 2026 gogym

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

---

## 简介 / Brief Introduction

GIM Android SDK 是 GIM IM 体系的 **客户端通信层**：把 TCP 长连接、Protobuf 编解码、Varint32 拆包粘包、心跳调度、指数退避重连、事件分发、WebRTC 信令与媒体传输这些"IM 客户端通用底座"沉淀到 SDK 内部。业务方只需持有 `GimImService`，实现 `ImEventListener` 关心的回调即可，无需理解底层协议细节。

GIM Android SDK is the **client-side communication layer** of the GIM IM system. TCP long-connection, Protobuf codec, Varint32 framing, heartbeat scheduling, exponential-backoff reconnect, event dispatch, WebRTC signaling and media transport are all built-in; you only hold a `GimImService` and override the `ImEventListener` callbacks you care about.

- **开箱即用**：一个 `GimImService` 搞定连接、收发、心跳、重连
- **协议透明**：与服务端一致的 `Packet` + `Cmd` 统一信封，`PacketCodec` 封装全部构建/解析
- **事件驱动**：`ImEventListener` + `StateFlow<ImConnectionState>`，天然契合协程与 Jetpack
- **音视频内建**：1:1 通话状态机 + 群视频 Mesh/SFU 双模传输（SFU 对接 LiveKit）
- **移动端友好**：前后台切换感知、后台低频重连、单线程心跳杜绝定时器复活
- **完全开源**：基于 **Apache License 2.0** 协议

> 本 SDK 只负责**通信层**，不包含业务逻辑（本地数据库、会话管理、UUID 生成、UI 渲染等由使用方实现）。

---

## 整体架构 / Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                        使用方 App 代码                         │
│   GimImService.connect(...) / send(...)  ·  ImEventListener   │
├──────────────────────────────────────────────────────────────┤
│                     对外服务与事件层                           │
│   GimImService（连接入口 · StateFlow · 事件分发）             │
│   ImEventListener（消息/通知/信令/状态回调）                 │
├──────────────────────────────────────────────────────────────┤
│                       音视频能力层                             │
│   RtcEngine（1:1 通话状态机 + offer/answer/ICE）             │
│   GroupRtcEngine（群通话生命周期信令 cmd=51）                │
│   MeshGroupTransport（P2P 多路） · SfuGroupTransport（LiveKit）│
├──────────────────────────────────────────────────────────────┤
│                       连接与传输层                             │
│   ImClient（TCP Socket · 读线程 · 指数退避重连 · 顶号处理）  │
│   HeartbeatManager（单线程心跳 + 超时检测）                  │
│   FrameCodec（Varint32 拆包/粘包）                           │
├──────────────────────────────────────────────────────────────┤
│                       协议与模型层                             │
│   ImProto（Protobuf Lite 生成） · PacketCodec · Cmd           │
│   ContentType · DeviceType · ImConfig                        │
└──────────────────────────────────────────────────────────────┘
```

### 包组成 / Packages

| 包 | 说明 |
|----|------|
| **`core`** | 连接与传输：`ImClient`（TCP 长连接/重连/顶号）、`HeartbeatManager`、`FrameCodec`（Varint32 帧编解码）、`ImConnectionState` |
| **`protocol`** | 协议定义：`ImProto`（Protobuf 生成）、`PacketCodec`（Packet 构建/解析）、`Cmd`、`ContentType`、`DeviceType` |
| **`service`** | 对外核心服务：`GimImService`（连接入口、事件分发、`StateFlow`） |
| **`spi`** | 事件扩展点：`ImEventListener`（消息/在线/好友/群组/信令回调） |
| **`model`** | 配置模型：`ImConfig`（连接与心跳/重连参数） |
| **`rtc`** | 1:1 音视频：`RtcEngine`（WebRTC 信令与媒体）、`RtcCallType/State/SignalType/EndReason` |
| **`rtc.group`** | 群音视频：`GroupRtcEngine`、`GroupMediaTransport`（抽象）、`MeshGroupTransport`、`SfuGroupTransport`、群通话 DTO 与类型 |

---

## GIM 客户端的特点 / Features

### 一. 精简的统一信令信封（Packet + Cmd）

所有消息使用统一的 Protobuf 二进制信封 `Packet`：`cmd + sequence + requestId + timestamp + body`。业务语义放在 `body` 里，按 cmd 反序列化为对应的 message（`ChatMessage`、`ServerAck`、`RtcSignal`、`RtcGroup` 等）。`PacketCodec` 封装了全部构建与解析方法。

| cmd 号段 | 用途 |
|---------|------|
| 1 ~ 9 | 连接管理：`BIND_REQ` / `BIND_RESP` / `HEARTBEAT_REQ` / `HEARTBEAT_RESP` / `KICK_NOTIFY` |
| 10 ~ 19 | 聊天消息：单聊 / 群聊 / `SERVER_ACK` / `DELIVERY_ACK` / `READ_RECEIPT` / `MSG_RECALL` |
| 20 ~ 29 | 在线状态：`ONLINE_STATUS_NOTIFY` |
| 30 ~ 39 | 好友通知：`FRIEND_REQUEST_NOTIFY` / `FRIEND_STATUS_NOTIFY` |
| 40 ~ 49 | 群组通知：`GROUP_MEMBER_NOTIFY` / `GROUP_NOTIFY` / `GROUP_JOIN_REQUEST_NOTIFY` |
| 50 ~ 59 | WebRTC 信令：`RTC_SIGNAL`（1:1 / Mesh 媒体） / `RTC_GROUP`（群生命周期） |

### 二. 帧协议：对齐服务端 Netty 的 Varint32 拆包

`FrameCodec` 复刻了服务端 Netty 的 `ProtobufVarint32FrameDecoder/Encoder`：`[length(varint32)][payload]`。读线程对 TCP 流做粘包/拆包处理，一次可读多帧，不足一帧则保留残余等待下一次读取。

### 三. 可靠且省电的连接管理

- **首包认证**：连接建立后立即发送 `BindRequest`（userId + token + device + deviceId），服务端回 `BindResponse`，`code=0` 进入 `AUTHENTICATED`。
- **指数退避重连**：`reconnectBaseDelayMs * 2^attempts`，封顶 `reconnectMaxDelayMs`；`maxReconnectAttempts=null` 表示无限重连。
- **单线程心跳**：`HeartbeatManager` 用单线程 + `wait/notify` 串行驱动发送与超时检测，规避 `java.util.Timer` 的"任务复活/多定时器并存"缺陷；超时未收到 `HEARTBEAT_RESP` 判定断连并触发重连。
- **顶号与互踢**：收到 `KICK_NOTIFY` 置 `kicked`，停止自动重连；用户可主动"点击重连"再次尝试。
- **前后台感知**：`pauseForBackground()` 后重连切换为 5 分钟低频间隔，回前台 `reconnectIfNeeded()` 立即检查并恢复。
- **代次隔离**：`connectGeneration` 计数器防止旧的读线程/断连请求覆盖新连接，避免连接竞态与无限重连循环。

### 四. 事件驱动的收发

`GimImService` 收到 `Packet` 后按 `cmd` 分发到 `ImEventListener` 对应回调，业务层只面向语义方法编程：

```
onMessageReceived · onMessageSent · onMessageDelivered · onMessageRead · onMessageRecalled
onUserOnline · onUserOffline
onFriendRequest · onFriendStatusChanged
onGroupMemberChanged · onGroupNotify · onGroupJoinRequest
onRtcSignal · onRtcGroupSignal
onConnectionStateChanged · onKicked · onBindFailed
```

`connectionState: StateFlow<ImConnectionState>` 可直接被 Compose / Lifecycle 观测。

### 五. WebRTC 音视频：1:1 + 群视频（Mesh + SFU）

- **1:1 通话**：`RtcEngine` 封装标准 offer/answer/ICE 全流程、TURN 凭证解析、忙线/串线防护、重复信令幂等、`callAck(9)` 回填服务端 callId、通话计时与本地媒体开关。
- **群视频**：`GroupRtcEngine` 处理 `cmd=51` 生命周期信令（request/invite/join/reject/leave/end/participantNotify/roomState），媒体层由工厂依据 `roomState.mode` 选择：
  - **Mesh**（小群，默认 ≤8 人）：成员间 P2P 直连，`cmd=50` 点对点交换 offer/answer/ICE，字典序较小方发起 Offer，两端决策一致避免冲突。
  - **SFU**（大群，20+ 人）：`SfuGroupTransport` 用 `sfuUrl + sfuToken` 连接 LiveKit 收发媒体。
- **移动端健壮性**：UNIFIED_PLAN 语义、全局复用 `EglBase`/`PeerConnectionFactory`、硬件编解码工厂、连接超时兜底、媒体信令缓冲重放（对端 offer 早于本端 roomState 到达时不丢包）。

### 六. 消息能力

- **单聊**：`PacketCodec.buildSingleChatMsg(...)`，`chatType=1`。
- **群聊**：`PacketCodec.buildGroupChatMsg(...)`，`chatType=2`。
- **送达/已读/撤回**：`buildDeliveryAck` / `buildReadReceipt` / `buildMsgRecallReq`，对应收 `DELIVERY_ACK` / `READ_RECEIPT` / `MSG_RECALL_NOTIFY`。
- **内容类型**：`ContentType`（TEXT/IMAGE/AUDIO/VIDEO/FILE/LOCATION/CUSTOM/CALL_RECORD），与服务端编号一致。

### 七. 其他特性

- **多端类型**：`DeviceType`（mobile/desktop/web/pad），绑定时上报。
- **协程原生**：`connect` / `disconnect` / `reconnectIfNeeded` 为 `suspend`，Socket 阻塞 I/O 自动切 `Dispatchers.IO`。
- **依赖透明**：`api` 暴露 WebRTC / Coroutines / Protobuf-javalite / LiveKit，公开 API 类型无冲突。
- **纯通信层**：无本地持久化、无 UI，接入方完全掌控数据与界面。

---

## 快速开始 / Quick Start

### 1. 添加依赖

SDK 发布坐标为 `io.getbit.gim:gim-sdk-android:1.0.0`。发布到本机仓库后（`./gradlew publishToMavenLocal`），在 App 模块引入：

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation("io.getbit.gim:gim-sdk-android:1.0.0")
}
```

或在源码依赖场景下将本工程作为 module 引入 `settings.gradle.kts`。

### 2. 连接并监听事件

```kotlin
val service = GimImService()

// 注册事件监听（只覆盖关心的回调）
service.addEventListener(object : ImEventListener() {
    override fun onMessageReceived(packet: ImProto.Packet) {
        val msg = PacketCodec.parseChatMessage(packet)
        // TODO: 展示 / 落库
    }
    override fun onConnectionStateChanged(state: ImConnectionState) {
        // TODO: 更新连接状态 UI
    }
})

// 连接（首包认证）
lifecycleScope.launch {
    service.connect(ImConfig(
        host = "192.168.1.100",
        port = 3333,
        userId = "user_001",
        token = "jwt_token",
        device = DeviceType.MOBILE.value,
        deviceId = persistedDeviceUuid,   // 客户端持久化 UUID
    ))
}
```

### 3. 发送消息

```kotlin
val packet = PacketCodec.buildSingleChatMsg(
    requestId = UUID.randomUUID().toString(),
    senderId = "user_001",
    receiverId = "user_002",
    contentType = ContentType.TEXT.value,
    content = "Hello GIM!",
)
service.send(packet)
```

### 4. 前后台与断开

```kotlin
// Activity/Application 生命周期
onStop { service.pauseForBackground() }
onStart { lifecycleScope.launch { service.reconnectIfNeeded() } }

// 主动断开 / 释放
lifecycleScope.launch { service.disconnect() }
service.dispose()
```

### 5. 发起 1:1 通话

```kotlin
RtcEngine.initialize(appContext)   // 全局初始化一次

val rtc = RtcEngine(appContext, { currentUserId() }, object : RtcEngineCallback {
    override fun onSendSignal(packet: ImProto.Packet) = service.send(packet)
    // ...其余回调驱动通话 UI
})

lifecycleScope.launch { rtc.startCall("user_002", RtcCallType.VIDEO) }

// IM 事件里把信令转交引擎
override fun onRtcSignal(packet: ImProto.Packet) {
    rtc.handleSignal(PacketCodec.parseRtcSignal(packet))
}
```

> 群视频通话同理：`GroupRtcEngine` + `GroupRtcEngineCallback`，`onSendGroupSignal/onSendMediaSignal` 桥接到 `service.send(...)`，生命周期信令由 `handleGroupSignal`、点对点媒体信令由 `handleMediaSignal` 转交。

---

## 文档索引 / Documentation

完整文档已发布到 GitHub Wiki（[中文](https://github.com/gogym/gim-sdk-android/wiki/Home) | [English](https://github.com/gogym/gim-sdk-android/wiki/Home-EN)）：

- [Wiki 首页](https://github.com/gogym/gim-sdk-android/wiki/Home)
- [快速开始](https://github.com/gogym/gim-sdk-android/wiki/快速开始)
- [架构概览](https://github.com/gogym/gim-sdk-android/wiki/架构概览)
- [核心概念：Packet 与协议编解码](https://github.com/gogym/gim-sdk-android/wiki/核心概念：Packet与协议编解码)
- [核心概念：连接与认证](https://github.com/gogym/gim-sdk-android/wiki/核心概念：连接与认证)
- [核心概念：事件监听与消息收发](https://github.com/gogym/gim-sdk-android/wiki/核心概念：事件监听与消息收发)
- [消息能力：单聊与群聊](https://github.com/gogym/gim-sdk-android/wiki/消息能力：单聊与群聊)
- [消息能力：心跳与自动重连](https://github.com/gogym/gim-sdk-android/wiki/消息能力：心跳与自动重连)
- [WebRTC：1:1 通话](https://github.com/gogym/gim-sdk-android/wiki/WebRTC：1对1通话)
- [WebRTC：群视频通话（Mesh + SFU）](https://github.com/gogym/gim-sdk-android/wiki/WebRTC：群视频通话)
- [配置参考：ImConfig](https://github.com/gogym/gim-sdk-android/wiki/配置参考：ImConfig)
- [Android 集成与生命周期](https://github.com/gogym/gim-sdk-android/wiki/Android集成与生命周期)

---

## 环境要求 / Requirements

| 组件 | 版本要求 |
|------|---------|
| **minSdk** | 26（Android 8.0+） |
| **compileSdk** | 35 |
| **JDK** | 17 |
| **Kotlin** | 2.0.21 |
| **AGP** | 8.6.0 |
| **Coroutines** | 1.9.0 |
| **Protobuf（javalite）** | 4.36.0 |
| **WebRTC** | stream-webrtc-android 1.1.1 |
| **LiveKit** | livekit-android 2.28.2 |

---

## 构建 / Build

```bash
# 编译并跑单测（群通话 DTO / 协议编解码测试为纯 JVM 单测）
./gradlew clean test

# 构建 release AAR
./gradlew assembleRelease

# 发布到本机 mavenLocal（坐标 io.getbit.gim:gim-sdk-android:1.0.0）
./gradlew publishToMavenLocal
```

---

## 配套服务端 / Companion Server

客户端协议与服务端一一对应，服务端 SDK 见：

- [gim-sdk-java](https://github.com/gogym/gim-sdk-java)（Netty + Protobuf，含 1:1 与群通话房间管理、SFU/LiveKit 参考适配）

---

## 为什么写这个 SDK / Why GIM

1. **Android IM 客户端重复劳动多**：TCP 长连接、Protobuf 拆包、心跳重连、被踢顶号、前后台省电，每一项都要踩坑。
2. **市面方案要么黑盒要么过重**：本 SDK 走"通信层薄壳 + 事件回调"路线，协议透明、代码路径清晰，方便二开与教学。
3. **音视频信令难以自研**：1:1 与群通话的状态机、超时清理、Mesh/SFU 切换、ICE/TURN、信令缓冲高度模板化，交给 SDK 一次搞定。
4. **可读优先**：包按连接/协议/服务/ rtc / group 拆分，注释密度对齐主流开源项目，单文件职责单一。

---

## Bug 反馈与建议 / Issues

- [GitHub Issues](https://github.com/gogym/gim-sdk-android/issues)

---

## 许可证 / License

```
Copyright 2026 gogym

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

完整条款见项目根目录 [LICENSE](LICENSE)。

---

## 作者 / Author

**gogym**

- GitHub：<https://github.com/gogym>
- Email：<189155278@qq.com>
