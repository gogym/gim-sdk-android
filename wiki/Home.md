# GIM IM SDK (Android) 使用文档

[**中文**](Home) | [English](Home-EN)

欢迎使用 **GIM IM SDK (Android)** —— 基于 Kotlin + Protobuf + WebRTC 构建、开箱即用的 Android IM **客户端** SDK，对接 [gim-sdk-java](https://github.com/gogym/gim-sdk-java) 服务端。

## 简介

GIM Android SDK 把 IM 客户端的通用通信能力沉淀到 SDK 内部，业务方只需持有 `GimImService` 并覆盖 `ImEventListener` 关心的回调，即可获得：

- 长连接管理（TCP Socket、首包认证、多端顶号）
- 心跳保活与指数退避自动重连（单线程心跳、前后台省电）
- 消息收发（单聊、群聊、送达确认、已读、撤回）
- 在线状态、好友/群组事件通知
- WebRTC 音视频（1:1 通话 + 群视频，Mesh / SFU 双模）

> SDK 只负责**通信层**，不包含业务逻辑（本地数据库、会话管理、UUID 生成、UI 渲染等由使用方实现）。

### 包说明

| 包 | 说明 |
|----|------|
| **`core`** | `ImClient`（连接/重连/顶号）、`HeartbeatManager`、`FrameCodec`（Varint32）、`ImConnectionState` |
| **`protocol`** | `ImProto`（Protobuf 生成）、`PacketCodec`、`Cmd`、`ContentType`、`DeviceType` |
| **`service`** | `GimImService`（连接入口、事件分发、`StateFlow`） |
| **`spi`** | `ImEventListener`（事件回调扩展点） |
| **`model`** | `ImConfig`（连接与心跳/重连参数） |
| **`rtc`** | `RtcEngine`（1:1 通话）、`RtcCallType/State/SignalType/EndReason` |
| **`rtc.group`** | `GroupRtcEngine`、`MeshGroupTransport`、`SfuGroupTransport`、群通话 DTO/类型 |

## 文档目录

- [快速开始](快速开始)
- [架构概览](架构概览)
- [核心概念：Packet 与协议编解码](核心概念：Packet与协议编解码)
- [核心概念：连接与认证](核心概念：连接与认证)
- [核心概念：事件监听与消息收发](核心概念：事件监听与消息收发)
- [消息能力：单聊与群聊](消息能力：单聊与群聊)
- [消息能力：心跳与自动重连](消息能力：心跳与自动重连)
- [配置参考：ImConfig](配置参考：ImConfig)
- [Android 集成与生命周期](Android集成与生命周期)
- [WebRTC：1:1 通话](WebRTC：1对1通话)
- [WebRTC：群视频通话（Mesh + SFU）](WebRTC：群视频通话)

## 环境要求

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

## Maven 引入

SDK 发布坐标：`io.getbit.gim:gim-sdk-android:1.0.0`。

```bash
# 发布到本机 mavenLocal
./gradlew publishToMavenLocal
```

```kotlin
// App 模块 build.gradle.kts
dependencies {
    implementation("io.getbit.gim:gim-sdk-android:1.0.0")
}
```

## 最简示例

```kotlin
val service = GimImService()

service.addEventListener(object : ImEventListener() {
    override fun onMessageReceived(packet: ImProto.Packet) {
        val msg = PacketCodec.parseChatMessage(packet)
        // TODO: 展示 / 落库
    }
})

lifecycleScope.launch {
    service.connect(ImConfig(
        host = "192.168.1.100", port = 3333,
        userId = "user_001", token = "jwt_token",
        device = "mobile", deviceId = persistedUuid,
    ))
}

val packet = PacketCodec.buildSingleChatMsg(
    requestId = UUID.randomUUID().toString(),
    senderId = "user_001", receiverId = "user_002",
    contentType = ContentType.TEXT.value, content = "Hello GIM!",
)
service.send(packet)
```

## 许可证

Apache License 2.0，详见项目根目录 [LICENSE](https://github.com/gogym/gim-sdk-android/blob/main/LICENSE)。

## 作者

**gogym** · <https://github.com/gogym> · <189155278@qq.com>
