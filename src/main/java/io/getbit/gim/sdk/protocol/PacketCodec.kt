package io.getbit.gim.sdk.protocol

import com.google.protobuf.GeneratedMessage
import java.util.concurrent.atomic.AtomicLong

/**
 * Packet 编解码工具类（客户端）
 * 所有 protobuf 消息的创建和解析统一通过此类
 */
object PacketCodec {

    /** 自增序列号（客户端发出的请求） */
    private val sequence = AtomicLong(0L)

    /** 获取下一个序列号 */
    fun nextSequence(): Long = sequence.incrementAndGet()

    /** 重置序列号 */
    fun resetSequence() {
        sequence.set(0L)
    }

    // ====================== Packet 创建 ======================

    /**
     * 创建 Packet（带 body）
     *
     * @param chatType 聊天类型值（由服务端配置），仅当 body 为 ChatMessage 时生效
     */
    fun create(
        cmd: Int,
        seq: Long? = null,
        requestId: String? = null,
        body: GeneratedMessage? = null,
        chatType: Int? = null,
    ): ImProto.Packet {
        val builder = ImProto.Packet.newBuilder()
            .setCmd(cmd)
            .setSequence(seq ?: nextSequence())
            .setTimestamp(System.currentTimeMillis())

        if (requestId != null) {
            builder.requestId = requestId
        }
        if (body != null) {
            if (chatType != null && body is ImProto.ChatMessage) {
                // ChatMessage 的 chatType 需要在构建时设置
                // 由于 protobuf 生成的是不可变对象，chatType 在构建 body 时已设置
            }
            builder.body = body.toByteString()
        }
        return builder.build()
    }

    /** 创建无 body 的 Packet（如心跳） */
    fun createEmpty(cmd: Int): ImProto.Packet {
        return ImProto.Packet.newBuilder()
            .setCmd(cmd)
            .setSequence(nextSequence())
            .setTimestamp(System.currentTimeMillis())
            .build()
    }

    // ====================== 业务消息构建 ======================

    /** 构建绑定请求 Packet */
    fun buildBindReq(userId: String, token: String, device: String, deviceId: String): ImProto.Packet {
        val body = ImProto.BindRequest.newBuilder()
            .setUserId(userId)
            .setToken(token)
            .setDevice(device)
            .setDeviceId(deviceId)
            .build()
        return create(Cmd.BIND_REQ, seq = 0L, body = body)
    }

    /** 构建心跳请求 Packet */
    fun buildHeartbeatReq(): ImProto.Packet {
        val body = ImProto.Heartbeat.newBuilder()
            .setClientTime(System.currentTimeMillis())
            .build()
        return create(Cmd.HEARTBEAT_REQ, body = body)
    }

    /** 构建单聊消息 Packet */
    fun buildSingleChatMsg(
        requestId: String,
        senderId: String,
        receiverId: String,
        contentType: Int,
        content: String,
        conversationId: String? = null,
        ext: Map<String, String>? = null,
    ): ImProto.Packet {
        val bodyBuilder = ImProto.ChatMessage.newBuilder()
            .setSenderId(senderId)
            .setReceiverId(receiverId)
            .setContentType(contentType)
            .setContent(content)
            .setChatType(1) // 单聊

        if (conversationId != null) {
            bodyBuilder.conversationId = conversationId
        }
        if (ext != null && ext.isNotEmpty()) {
            bodyBuilder.putAllExt(ext)
        }

        return create(Cmd.SINGLE_CHAT_MSG, requestId = requestId, body = bodyBuilder.build())
    }

    /** 构建群聊消息 Packet */
    fun buildGroupChatMsg(
        requestId: String,
        senderId: String,
        receiverId: String,
        contentType: Int,
        content: String,
        conversationId: String? = null,
        ext: Map<String, String>? = null,
    ): ImProto.Packet {
        val bodyBuilder = ImProto.ChatMessage.newBuilder()
            .setSenderId(senderId)
            .setReceiverId(receiverId)
            .setContentType(contentType)
            .setContent(content)
            .setChatType(2) // 群聊

        if (conversationId != null) {
            bodyBuilder.conversationId = conversationId
        }
        if (ext != null && ext.isNotEmpty()) {
            bodyBuilder.putAllExt(ext)
        }

        return create(Cmd.GROUP_CHAT_MSG, requestId = requestId, body = bodyBuilder.build())
    }

    /** 构建送达 ACK Packet */
    fun buildDeliveryAck(msgId: String, senderId: String): ImProto.Packet {
        val body = ImProto.DeliveryAck.newBuilder()
            .setMsgId(msgId)
            .setSenderId(senderId)
            .build()
        return create(Cmd.DELIVERY_ACK, seq = 0L, body = body)
    }

    /** 构建已读回执 Packet */
    fun buildReadReceipt(conversationId: String, lastReadMsgId: String): ImProto.Packet {
        val body = ImProto.ReadReceipt.newBuilder()
            .setConversationId(conversationId)
            .setLastReadMsgId(lastReadMsgId)
            .build()
        return create(Cmd.READ_RECEIPT, seq = 0L, body = body)
    }

    /** 构建消息撤回请求 Packet */
    fun buildMsgRecallReq(
        msgId: String,
        conversationId: String,
        chatType: Int,
        requestId: String? = null,
    ): ImProto.Packet {
        val body = ImProto.MsgRecallRequest.newBuilder()
            .setMsgId(msgId)
            .setConversationId(conversationId)
            .setChatType(chatType)
            .build()
        return create(Cmd.MSG_RECALL_REQ, seq = 0L, requestId = requestId, body = body)
    }

    // ====================== Body 解析 ======================

    /** 解析 Packet body 为 BindResponse */
    fun parseBindResponse(packet: ImProto.Packet): ImProto.BindResponse =
        ImProto.BindResponse.parseFrom(packet.body)

    /** 解析 Packet body 为 HeartbeatResponse */
    fun parseHeartbeatResponse(packet: ImProto.Packet): ImProto.HeartbeatResponse =
        ImProto.HeartbeatResponse.parseFrom(packet.body)

    /** 解析 Packet body 为 ChatMessage */
    fun parseChatMessage(packet: ImProto.Packet): ImProto.ChatMessage =
        ImProto.ChatMessage.parseFrom(packet.body)

    /** 解析 Packet body 为 ServerAck */
    fun parseServerAck(packet: ImProto.Packet): ImProto.ServerAck =
        ImProto.ServerAck.parseFrom(packet.body)

    /** 解析 Packet body 为 DeliveryAck */
    fun parseDeliveryAck(packet: ImProto.Packet): ImProto.DeliveryAck =
        ImProto.DeliveryAck.parseFrom(packet.body)

    /** 解析 Packet body 为 ReadReceipt */
    fun parseReadReceipt(packet: ImProto.Packet): ImProto.ReadReceipt =
        ImProto.ReadReceipt.parseFrom(packet.body)

    /** 解析 Packet body 为 MsgRecallNotify */
    fun parseMsgRecallNotify(packet: ImProto.Packet): ImProto.MsgRecallNotify =
        ImProto.MsgRecallNotify.parseFrom(packet.body)

    /** 解析 Packet body 为 OnlineStatusNotify */
    fun parseOnlineStatusNotify(packet: ImProto.Packet): ImProto.OnlineStatusNotify =
        ImProto.OnlineStatusNotify.parseFrom(packet.body)

    /** 解析 Packet body 为 FriendRequestNotify */
    fun parseFriendRequestNotify(packet: ImProto.Packet): ImProto.FriendRequestNotify =
        ImProto.FriendRequestNotify.parseFrom(packet.body)

    /** 解析 Packet body 为 FriendStatusNotify */
    fun parseFriendStatusNotify(packet: ImProto.Packet): ImProto.FriendStatusNotify =
        ImProto.FriendStatusNotify.parseFrom(packet.body)

    /** 解析 Packet body 为 GroupMemberNotify */
    fun parseGroupMemberNotify(packet: ImProto.Packet): ImProto.GroupMemberNotify =
        ImProto.GroupMemberNotify.parseFrom(packet.body)

    /** 解析 Packet body 为 GroupNotify */
    fun parseGroupNotify(packet: ImProto.Packet): ImProto.GroupNotify =
        ImProto.GroupNotify.parseFrom(packet.body)

    /** 解析 Packet body 为 GroupJoinRequestNotify */
    fun parseGroupJoinRequestNotify(packet: ImProto.Packet): ImProto.GroupJoinRequestNotify =
        ImProto.GroupJoinRequestNotify.parseFrom(packet.body)

    /** 解析 Packet body 为 RtcSignal */
    fun parseRtcSignal(packet: ImProto.Packet): ImProto.RtcSignal =
        ImProto.RtcSignal.parseFrom(packet.body)

    /** 解析 Packet body 为 KickNotify */
    fun parseKickNotify(packet: ImProto.Packet): ImProto.KickNotify =
        ImProto.KickNotify.parseFrom(packet.body)

    // ====================== 群通话信令 ======================

    /**
     * 构建群通话信令 Packet（cmd=51 RTC_GROUP，对标 Flutter PacketCodec.buildRtcGroup）
     *
     * @param mode 会话模式（0-Mesh 1-SFU），仅发起请求时由调用方决定，其余场景服务端回填
     */
    fun buildRtcGroup(
        signalType: Int,
        senderId: String,
        groupId: String,
        payload: String = "",
        callId: String = "",
        roomId: String = "",
        mode: Int = 0,
    ): ImProto.Packet {
        val body = ImProto.RtcGroup.newBuilder()
            .setSignalType(signalType)
            .setSenderId(senderId)
            .setGroupId(groupId)
            .setPayload(payload)
            .setCallId(callId)
            .setRoomId(roomId)
            .setMode(mode)
            .build()
        return create(Cmd.RTC_GROUP, body = body)
    }

    /** 解析 Packet body 为 RtcGroup */
    fun parseRtcGroup(packet: ImProto.Packet): ImProto.RtcGroup =
        ImProto.RtcGroup.parseFrom(packet.body)

    // ====================== 序列化 ======================

    /** 将 Packet 序列化为字节数组（用于网络传输） */
    fun encode(packet: ImProto.Packet): ByteArray = packet.toByteArray()

    /** 从字节数组反序列化为 Packet */
    fun decode(data: ByteArray): ImProto.Packet = ImProto.Packet.parseFrom(data)
}
