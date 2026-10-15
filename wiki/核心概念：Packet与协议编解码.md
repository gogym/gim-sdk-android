# 核心概念：Packet 与协议编解码

GIM 客户端与服务端共用一套统一的 Protobuf 二进制信封 `Packet`。本文说明信封结构、命令号约定、帧格式，以及 `PacketCodec` / `FrameCodec` 的用法。

## 统一信封 Packet

所有消息外层都是 `ImProto.Packet`（由 `ImProto.proto` 生成，见 `protocol/ImProto.java`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `cmd` | int32 | 命令类型（见 `Cmd` 常量） |
| `sequence` | int64 | 客户端消息序号（去重 + ACK 关联），由 `PacketCodec.nextSequence()` 自增 |
| `requestId` | string | 请求唯一 ID（客户端 UUID，幂等匹配） |
| `timestamp` | int64 | 时间戳（毫秒） |
| `body` | bytes | 业务消息体，按 `cmd` 反序列化为对应 message |

**为什么用统一信封？** 路由与协议层只关心 `cmd`，无需理解业务 `body`；扩展新语义只需增一个 cmd + message，客户端内核零改动。

## 命令号约定 Cmd

```
1 ~ 9     连接管理：BIND_REQ(1) BIND_RESP(2) HEARTBEAT_REQ(3) HEARTBEAT_RESP(4) KICK_NOTIFY(5)
10 ~ 19   聊天：SINGLE_CHAT_MSG(10) GROUP_CHAT_MSG(11) SERVER_ACK(12) DELIVERY_ACK(13)
          READ_RECEIPT(14) MSG_RECALL_REQ(15) MSG_RECALL_NOTIFY(16)
20 ~ 29   在线状态：ONLINE_STATUS_NOTIFY(20)
30 ~ 39   好友：FRIEND_REQUEST_NOTIFY(30) FRIEND_STATUS_NOTIFY(31)
40 ~ 49   群组：GROUP_MEMBER_NOTIFY(40) GROUP_NOTIFY(41) GROUP_JOIN_REQUEST_NOTIFY(42)
50 ~ 59   WebRTC：RTC_SIGNAL(50) RTC_GROUP(51)
```

调试可用 `Cmd.nameOf(cmd)` 获取可读名称。

## 帧格式：Varint32（FrameCodec）

TCP 是字节流，需要长度前缀区分消息边界。`FrameCodec` 复刻服务端 Netty 的 `ProtobufVarint32FrameDecoder/Encoder`：

```
[ length (varint32) ][ payload (length bytes) ]
```

- `length`：payload 字节数（不含 length 本身），protobuf varint32 编码
- `payload`：`Packet` 的 protobuf 序列化字节

`FrameCodec.decode(buffer, offset)` 支持粘包/拆包：数据不足一帧返回 `null`（保留残余等待下次读取），成功返回 `FrameDecodeResult(payload, bytesConsumed)`。`ImClient` 读线程循环解码，一次可读入的字节可能包含多帧。

## PacketCodec：构建

```kotlin
// 连接与心跳（一般由 ImClient 内部调用）
PacketCodec.buildBindReq(userId, token, device, deviceId)
PacketCodec.buildHeartbeatReq()

// 消息
PacketCodec.buildSingleChatMsg(requestId, senderId, receiverId, contentType, content, conversationId?, ext?)
PacketCodec.buildGroupChatMsg(requestId, senderId, receiverId, contentType, content, conversationId?, ext?)

// 投递反馈
PacketCodec.buildDeliveryAck(msgId, senderId)
PacketCodec.buildReadReceipt(conversationId, lastReadMsgId, receiverId = "")   // 单聊必传 receiverId
PacketCodec.buildMsgRecallReq(msgId, conversationId, chatType, requestId?, receiverId = "")

// 群通话生命周期信令（cmd=51）
PacketCodec.buildRtcGroup(signalType, senderId, groupId, payload, callId, roomId, mode)

// 通用：任意 cmd + body 构建 Packet
PacketCodec.create(cmd, seq?, requestId?, body?, chatType?)
```

`ChatMessage` 的 `chatType` 在构建时固定：单聊 `1`、群聊 `2`。

## PacketCodec：解析

收到 `Packet` 后按 `cmd` 选择对应的 `parseXxx`：

```kotlin
PacketCodec.parseBindResponse(packet)
PacketCodec.parseChatMessage(packet)
PacketCodec.parseServerAck(packet)
PacketCodec.parseDeliveryAck(packet)
PacketCodec.parseReadReceipt(packet)
PacketCodec.parseMsgRecallNotify(packet)
PacketCodec.parseOnlineStatusNotify(packet)
PacketCodec.parseFriendRequestNotify(packet)
PacketCodec.parseFriendStatusNotify(packet)
PacketCodec.parseGroupMemberNotify(packet)
PacketCodec.parseGroupNotify(packet)
PacketCodec.parseGroupJoinRequestNotify(packet)
PacketCodec.parseRtcSignal(packet)
PacketCodec.parseRtcGroup(packet)
PacketCodec.parseKickNotify(packet)
```

## 内容类型与设备类型

```kotlin
enum class ContentType(val value: Int, val label: String, val pushText: String) {
    TEXT(1)  IMAGE(2)  AUDIO(3)  VIDEO(4)  FILE(5)  LOCATION(6)  CUSTOM(7)  CALL_RECORD(8)
}

enum class DeviceType(val value: String) {
    MOBILE("mobile")  DESKTOP("desktop")  WEB("web")  PAD("pad")
}
```

编号与服务端 `ContentType.java` / `DeviceType.java` 保持一致。

## 序列化入口

```kotlin
PacketCodec.encode(packet): ByteArray   // Packet → 字节（写入 Socket 前）
PacketCodec.decode(data): ImProto.Packet // 字节 → Packet（读出后）
```

网络传输时先 `encode`，再 `FrameCodec.encode` 加长度前缀；接收先 `FrameCodec.decode` 拆帧，再 `PacketCodec.decode` 还原 Packet。

## 相关文档

- [核心概念：连接与认证](核心概念：连接与认证)
- [消息能力：单聊与群聊](消息能力：单聊与群聊)
