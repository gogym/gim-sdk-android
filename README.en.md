# GIM IM SDK (Android)

[中文](README.md) | **English**

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

## Introduction

GIM Android SDK is the **client-side communication layer** of the GIM IM system: TCP long-connection, Protobuf codec, Varint32 coalescing/splitting, heartbeat scheduling, exponential-backoff reconnect, event dispatch, WebRTC signaling and media transport are all sunk into the SDK. Business code only needs a `GimImService` and the `ImEventListener` callbacks it cares about — no knowledge of low-level protocol details.

- **Ready to use**: one `GimImService` handles connection, send/receive, heartbeat, reconnect
- **Transparent protocol**: the same `Packet` + `Cmd` unified envelope as the server; `PacketCodec` wraps all building/parsing
- **Event-driven**: `ImEventListener` + `StateFlow<ImConnectionState>` — a natural fit for coroutines and Jetpack
- **Built-in A/V**: 1:1 call state machine + group video Mesh/SFU dual-mode transport (SFU integrates LiveKit)
- **Mobile-friendly**: foreground/background awareness, low-frequency background reconnect, single-threaded heartbeat with no timer resurrection
- **Fully open source**: under the **Apache License 2.0**

> This SDK only owns the **communication layer** — no business logic (local database, conversation management, UUID generation, UI rendering are implemented by the app).

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    Integrating App's Code                     │
│   GimImService.connect(...) / send(...)  ·  ImEventListener   │
├──────────────────────────────────────────────────────────────┤
│               Public Service & Event Layer                    │
│   GimImService (connect entry · StateFlow · dispatch)         │
│   ImEventListener (message/notification/signal/state cbs)    │
├──────────────────────────────────────────────────────────────┤
│               A/V Capability Layer                            │
│   RtcEngine (1:1 call state machine + offer/answer/ICE)      │
│   GroupRtcEngine (group lifecycle signaling cmd=51)          │
│   MeshGroupTransport (multi P2P) · SfuGroupTransport (LiveKit)│
├──────────────────────────────────────────────────────────────┤
│               Connection & Transport Layer                    │
│   ImClient (TCP Socket · read thread · backoff reconnect ·   │
│             device takeover)                                 │
│   HeartbeatManager (single-thread heartbeat + timeout)       │
│   FrameCodec (Varint32 coalescing/splitting)                 │
├──────────────────────────────────────────────────────────────┤
│               Protocol & Model Layer                          │
│   ImProto (Protobuf Lite generated) · PacketCodec · Cmd      │
│   ContentType · DeviceType · ImConfig                        │
└──────────────────────────────────────────────────────────────┘
```

### Packages

| Package | Description |
|----|------|
| **`core`** | Connection & transport: `ImClient` (TCP long connection/reconnect/takeover), `HeartbeatManager`, `FrameCodec` (Varint32 frame codec), `ImConnectionState` |
| **`protocol`** | Protocol definitions: `ImProto` (Protobuf generated), `PacketCodec` (Packet build/parse), `Cmd`, `ContentType`, `DeviceType` |
| **`service`** | Core public service: `GimImService` (connect entry, event dispatch, `StateFlow`) |
| **`spi`** | Event extension point: `ImEventListener` (message/online/friend/group/signal callbacks) |
| **`model`** | Config model: `ImConfig` (connection & heartbeat/reconnect parameters) |
| **`rtc`** | 1:1 A/V: `RtcEngine` (WebRTC signaling & media), `RtcCallType/State/SignalType/EndReason` |
| **`rtc.group`** | Group A/V: `GroupRtcEngine`, `GroupMediaTransport` (abstraction), `MeshGroupTransport`, `SfuGroupTransport`, group-call DTOs and types |

---

## Features

### 1. Lean unified signaling envelope (Packet + Cmd)

All messages use a unified Protobuf binary envelope `Packet`: `cmd + sequence + requestId + timestamp + body`. Business semantics live in `body`, deserialized per cmd into the corresponding message (`ChatMessage`, `ServerAck`, `RtcSignal`, `RtcGroup`, etc.). `PacketCodec` wraps all building and parsing methods.

| cmd range | Purpose |
|---------|------|
| 1 ~ 9 | Connection: `BIND_REQ` / `BIND_RESP` / `HEARTBEAT_REQ` / `HEARTBEAT_RESP` / `KICK_NOTIFY` |
| 10 ~ 19 | Chat: 1:1 / group / `SERVER_ACK` / `DELIVERY_ACK` / `READ_RECEIPT` / `MSG_RECALL` |
| 20 ~ 29 | Presence: `ONLINE_STATUS_NOTIFY` |
| 30 ~ 39 | Friend notifications: `FRIEND_REQUEST_NOTIFY` / `FRIEND_STATUS_NOTIFY` |
| 40 ~ 49 | Group notifications: `GROUP_MEMBER_NOTIFY` / `GROUP_NOTIFY` / `GROUP_JOIN_REQUEST_NOTIFY` |
| 50 ~ 59 | WebRTC signaling: `RTC_SIGNAL` (1:1 / Mesh media) / `RTC_GROUP` (group lifecycle) |

### 2. Frame protocol: matching the server's Netty Varint32 framing

`FrameCodec` reproduces the server's Netty `ProtobufVarint32FrameDecoder/Encoder`: `[length(varint32)][payload]`. The read thread handles TCP coalescing/splitting — one read may contain multiple frames; an incomplete remainder is kept for the next read.

### 3. Reliable and battery-friendly connection management

- **First-packet auth**: right after connecting a `BindRequest` (userId + token + device + deviceId) is sent; the server replies `BindResponse`, and `code=0` moves to `AUTHENTICATED`.
- **Exponential-backoff reconnect**: `reconnectBaseDelayMs * 2^attempts`, capped at `reconnectMaxDelayMs`; `maxReconnectAttempts=null` means unlimited.
- **Single-threaded heartbeat**: `HeartbeatManager` drives sending and timeout detection serially on one thread with `wait/notify`, avoiding `java.util.Timer`'s "task resurrection / coexisting timers" flaws; a missing `HEARTBEAT_RESP` within the timeout means disconnect and triggers reconnect.
- **Takeover & kick**: on `KICK_NOTIFY` the `kicked` flag stops auto-reconnect; users can manually "tap to reconnect" to try again.
- **Foreground/background awareness**: after `pauseForBackground()` reconnecting switches to a 5-minute low-frequency interval; `reconnectIfNeeded()` on resume checks and recovers immediately.
- **Generation isolation**: the `connectGeneration` counter prevents stale read threads / disconnect requests from clobbering a new connection, avoiding connection races and infinite reconnect loops.

### 4. Event-driven send/receive

`GimImService` dispatches received `Packet`s by `cmd` into `ImEventListener` callbacks — business code only programs against semantic methods:

```
onMessageReceived · onMessageSent · onMessageDelivered · onMessageRead · onMessageRecalled
onUserOnline · onUserOffline
onFriendRequest · onFriendStatusChanged
onGroupMemberChanged · onGroupNotify · onGroupJoinRequest
onRtcSignal · onRtcGroupSignal
onConnectionStateChanged · onKicked · onBindFailed
```

`connectionState: StateFlow<ImConnectionState>` can be observed directly by Compose / Lifecycle.

### 5. WebRTC A/V: 1:1 + group video (Mesh + SFU)

- **1:1 calls**: `RtcEngine` encapsulates the full standard offer/answer/ICE flow, TURN credential parsing, busy/cross-talk protection, duplicate-signal idempotency, `callAck(9)` server callId backfill, call timing, and local media toggles.
- **Group video**: `GroupRtcEngine` handles `cmd=51` lifecycle signaling (request/invite/join/reject/leave/end/participantNotify/roomState); the media layer is chosen by a factory from `roomState.mode`:
  - **Mesh** (small groups, default ≤8): P2P direct between members, offer/answer/ICE exchanged point-to-point over `cmd=50`; the lexicographically smaller userId sends the Offer — both ends decide identically, avoiding conflicts.
  - **SFU** (large groups, 20+): `SfuGroupTransport` connects to LiveKit with `sfuUrl + sfuToken` for media.
- **Mobile robustness**: UNIFIED_PLAN semantics, globally shared `EglBase`/`PeerConnectionFactory`, hardware codec factories, connect-timeout fallback, media-signal buffering & replay (a peer offer arriving before the local roomState is not lost).

### 6. Messaging

- **1:1 chat**: `PacketCodec.buildSingleChatMsg(...)`, `chatType=1`.
- **Group chat**: `PacketCodec.buildGroupChatMsg(...)`, `chatType=2`.
- **Delivery/read/recall**: `buildDeliveryAck` / `buildReadReceipt` / `buildMsgRecallReq`, receiving `DELIVERY_ACK` / `READ_RECEIPT` / `MSG_RECALL_NOTIFY`.
- **Content types**: `ContentType` (TEXT/IMAGE/AUDIO/VIDEO/FILE/LOCATION/CUSTOM/CALL_RECORD), numbered identically to the server.

### 7. Misc

- **Multi-device types**: `DeviceType` (mobile/desktop/web/pad), reported at bind time.
- **Coroutine-native**: `connect` / `disconnect` / `reconnectIfNeeded` are `suspend`; blocking socket I/O automatically runs on `Dispatchers.IO`.
- **Transparent dependencies**: `api` exposes WebRTC / Coroutines / Protobuf-javalite / LiveKit; no conflicts in public API types.
- **Pure communication layer**: no local persistence, no UI — the integrator fully owns data and screens.

---

## Quick Start

### 1. Add the dependency

The SDK publishes as `io.getbit.gim:gim-sdk-android:1.0.0`. After publishing to your local repository (`./gradlew publishToMavenLocal`), add it to your app module:

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation("io.getbit.gim:gim-sdk-android:1.0.0")
}
```

Or for source-dependency scenarios, include this project as a module in `settings.gradle.kts`.

### 2. Connect and listen to events

```kotlin
val service = GimImService()

// Register a listener (override only what you care about)
service.addEventListener(object : ImEventListener() {
    override fun onMessageReceived(packet: ImProto.Packet) {
        val msg = PacketCodec.parseChatMessage(packet)
        // TODO: display / persist
    }
    override fun onConnectionStateChanged(state: ImConnectionState) {
        // TODO: update connection status UI
    }
})

// Connect (first-packet auth)
lifecycleScope.launch {
    service.connect(ImConfig(
        host = "192.168.1.100",
        port = 3333,
        userId = "user_001",
        token = "jwt_token",
        device = DeviceType.MOBILE.value,
        deviceId = persistedDeviceUuid,   // client-persisted UUID
    ))
}
```

### 3. Send a message

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

### 4. Lifecycle and disconnect

```kotlin
// Activity/Application lifecycle
onStop { service.pauseForBackground() }
onStart { lifecycleScope.launch { service.reconnectIfNeeded() } }

// Active disconnect / release
lifecycleScope.launch { service.disconnect() }
service.dispose()
```

### 5. Start a 1:1 call

```kotlin
RtcEngine.initialize(appContext)   // global, one-time

val rtc = RtcEngine(appContext, { currentUserId() }, object : RtcEngineCallback {
    override fun onSendSignal(packet: ImProto.Packet) = service.send(packet)
    // ...remaining callbacks drive the call UI
})

lifecycleScope.launch { rtc.startCall("user_002", RtcCallType.VIDEO) }

// Forward signaling from IM events to the engine
override fun onRtcSignal(packet: ImProto.Packet) {
    rtc.handleSignal(PacketCodec.parseRtcSignal(packet))
}
```

> Group video works the same way: `GroupRtcEngine` + `GroupRtcEngineCallback`, bridging `onSendGroupSignal/onSendMediaSignal` to `service.send(...)`; lifecycle signals go into `handleGroupSignal`, Mesh point-to-point media signals into `handleMediaSignal`.

---

## Documentation

Full documentation is published on the GitHub Wiki ([中文](https://github.com/gogym/gim-sdk-android/wiki/Home) | [English](https://github.com/gogym/gim-sdk-android/wiki/Home-EN)):

| 中文 | English |
|------|---------|
| [Wiki 首页](https://github.com/gogym/gim-sdk-android/wiki/Home) | [Wiki Home](https://github.com/gogym/gim-sdk-android/wiki/Home-EN) |
| [快速开始](https://github.com/gogym/gim-sdk-android/wiki/快速开始) | [Quick Start](https://github.com/gogym/gim-sdk-android/wiki/Quick-Start) |
| [架构概览](https://github.com/gogym/gim-sdk-android/wiki/架构概览) | [Architecture Overview](https://github.com/gogym/gim-sdk-android/wiki/Architecture-Overview) |
| [核心概念：Packet 与协议编解码](https://github.com/gogym/gim-sdk-android/wiki/核心概念：Packet与协议编解码) | [Core Concept: Packet & Codec](https://github.com/gogym/gim-sdk-android/wiki/Core-Concept-Packet-and-Codec) |
| [核心概念：连接与认证](https://github.com/gogym/gim-sdk-android/wiki/核心概念：连接与认证) | [Core Concept: Connection & Auth](https://github.com/gogym/gim-sdk-android/wiki/Core-Concept-Connection-and-Auth) |
| [核心概念：事件监听与消息收发](https://github.com/gogym/gim-sdk-android/wiki/核心概念：事件监听与消息收发) | [Core Concept: Events & Messaging](https://github.com/gogym/gim-sdk-android/wiki/Core-Concept-Event-Listener-and-Messaging) |
| [消息能力：单聊与群聊](https://github.com/gogym/gim-sdk-android/wiki/消息能力：单聊与群聊) | [Messaging: 1:1 & Group Chat](https://github.com/gogym/gim-sdk-android/wiki/Messaging-Single-and-Group-Chat) |
| [消息能力：心跳与自动重连](https://github.com/gogym/gim-sdk-android/wiki/消息能力：心跳与自动重连) | [Messaging: Heartbeat & Auto-Reconnect](https://github.com/gogym/gim-sdk-android/wiki/Messaging-Heartbeat-and-Auto-Reconnect) |
| [配置参考：ImConfig](https://github.com/gogym/gim-sdk-android/wiki/配置参考：ImConfig) | [Config Reference: ImConfig](https://github.com/gogym/gim-sdk-android/wiki/Configuration-Reference-ImConfig) |
| [Android 集成与生命周期](https://github.com/gogym/gim-sdk-android/wiki/Android集成与生命周期) | [Android Integration & Lifecycle](https://github.com/gogym/gim-sdk-android/wiki/Android-Integration-and-Lifecycle) |
| [WebRTC：1:1 通话](https://github.com/gogym/gim-sdk-android/wiki/WebRTC：1对1通话) | [WebRTC: 1:1 Call](https://github.com/gogym/gim-sdk-android/wiki/WebRTC-One-to-One-Call) |
| [WebRTC：群视频通话](https://github.com/gogym/gim-sdk-android/wiki/WebRTC：群视频通话) | [WebRTC: Group Video Call](https://github.com/gogym/gim-sdk-android/wiki/WebRTC-Group-Video-Call) |

---

## Requirements

| Component | Version |
|------|---------|
| **minSdk** | 26 (Android 8.0+) |
| **compileSdk** | 35 |
| **JDK** | 17 |
| **Kotlin** | 2.0.21 |
| **AGP** | 8.6.0 |
| **Coroutines** | 1.9.0 |
| **Protobuf (javalite)** | 4.36.0 |
| **WebRTC** | stream-webrtc-android 1.1.1 |
| **LiveKit** | livekit-android 2.28.2 |

---

## Build

```bash
# Compile and run unit tests (group-call DTO / protocol codec tests are pure JVM)
./gradlew clean test

# Build the release AAR
./gradlew assembleRelease

# Publish to local mavenLocal (coordinate io.getbit.gim:gim-sdk-android:1.0.0)
./gradlew publishToMavenLocal
```

---

## Companion Server

The client protocol maps one-to-one to the server. See:

- [gim-sdk-java](https://github.com/gogym/gim-sdk-java) (Netty + Protobuf, with 1:1 and group-call room management, SFU/LiveKit reference adaptation)

---

## Why This SDK

1. **Android IM clients repeat a lot of work**: TCP long connections, Protobuf framing, heartbeat/reconnect, kick/takeover, background battery — every item is a trap.
2. **Existing options are black boxes or too heavy**: this SDK takes the "thin communication shell + event callbacks" route — transparent protocol, clear code paths, easy to extend and to teach from.
3. **A/V signaling is hard to build from scratch**: 1:1 and group-call state machines, timeout cleanup, Mesh/SFU switching, ICE/TURN, signal buffering are highly templated — let the SDK handle them once.
4. **Readability first**: packages split by connection/protocol/service/rtc/group, comment density on par with mainstream open-source projects, single-responsibility files.

---

## Bug Reports & Suggestions

- [GitHub Issues](https://github.com/gogym/gim-sdk-android/issues)

---

## License

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

Full terms in the project root [LICENSE](LICENSE).

---

## Author

**gogym**

- GitHub: <https://github.com/gogym>
- Email: <189155278@qq.com>
