package io.getbit.gim.sdk.protocol

/**
 * 命令类型常量定义
 * 与 Java 服务端 Cmd.java 保持一致
 *
 * 命令编号分配规则：
 * - 1~9:    连接管理（绑定、心跳等）
 * - 10~19:  聊天消息（发送、ACK、已读等）
 * - 20~29:  在线状态
 * - 30~39:  好友通知
 * - 40~49:  群组通知
 * - 50~59:  WebRTC 信令
 */
object Cmd {

    // ==================== 连接管理 (1-9) ====================

    /** 绑定请求（首包认证） */
    const val BIND_REQ = 1

    /** 绑定响应 */
    const val BIND_RESP = 2

    /** 心跳请求 */
    const val HEARTBEAT_REQ = 3

    /** 心跳响应 */
    const val HEARTBEAT_RESP = 4

    /** 踢人通知（服务端 → 客户端，被踢时发送） */
    const val KICK_NOTIFY = 5

    // ==================== 聊天消息 (10-19) ====================

    /** 单聊消息（客户端 → 服务端） */
    const val SINGLE_CHAT_MSG = 10

    /** 群聊消息（客户端 → 服务端） */
    const val GROUP_CHAT_MSG = 11

    /** 服务端 ACK（服务器确认收到） */
    const val SERVER_ACK = 12

    /** 送达 ACK（接收方确认收到） */
    const val DELIVERY_ACK = 13

    /** 已读回执 */
    const val READ_RECEIPT = 14

    /** 消息撤回请求 */
    const val MSG_RECALL_REQ = 15

    /** 消息撤回通知 */
    const val MSG_RECALL_NOTIFY = 16

    // ==================== 在线状态 (20-29) ====================

    /** 在线状态变更通知 */
    const val ONLINE_STATUS_NOTIFY = 20

    // ==================== 好友通知 (30-39) ====================

    /** 好友申请通知 */
    const val FRIEND_REQUEST_NOTIFY = 30

    /** 好友状态变更通知 */
    const val FRIEND_STATUS_NOTIFY = 31

    // ==================== 群组通知 (40-49) ====================

    /** 群成员变更通知 */
    const val GROUP_MEMBER_NOTIFY = 40

    /** 群信息/事件通知 */
    const val GROUP_NOTIFY = 41

    /** 入群申请通知 */
    const val GROUP_JOIN_REQUEST_NOTIFY = 42

    // ==================== WebRTC 信令 (50-59) ====================

    /** WebRTC 信令消息 */
    const val RTC_SIGNAL = 50

    /** 根据 cmd 返回可读名称（调试用） */
    fun nameOf(cmd: Int): String = when (cmd) {
        BIND_REQ -> "BIND_REQ"
        BIND_RESP -> "BIND_RESP"
        HEARTBEAT_REQ -> "HEARTBEAT_REQ"
        HEARTBEAT_RESP -> "HEARTBEAT_RESP"
        KICK_NOTIFY -> "KICK_NOTIFY"
        SINGLE_CHAT_MSG -> "SINGLE_CHAT_MSG"
        GROUP_CHAT_MSG -> "GROUP_CHAT_MSG"
        SERVER_ACK -> "SERVER_ACK"
        DELIVERY_ACK -> "DELIVERY_ACK"
        READ_RECEIPT -> "READ_RECEIPT"
        MSG_RECALL_REQ -> "MSG_RECALL_REQ"
        MSG_RECALL_NOTIFY -> "MSG_RECALL_NOTIFY"
        ONLINE_STATUS_NOTIFY -> "ONLINE_STATUS_NOTIFY"
        FRIEND_REQUEST_NOTIFY -> "FRIEND_REQUEST_NOTIFY"
        FRIEND_STATUS_NOTIFY -> "FRIEND_STATUS_NOTIFY"
        GROUP_MEMBER_NOTIFY -> "GROUP_MEMBER_NOTIFY"
        GROUP_NOTIFY -> "GROUP_NOTIFY"
        GROUP_JOIN_REQUEST_NOTIFY -> "GROUP_JOIN_REQUEST_NOTIFY"
        RTC_SIGNAL -> "RTC_SIGNAL"
        else -> "UNKNOWN($cmd)"
    }
}
