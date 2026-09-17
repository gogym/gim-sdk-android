package io.getbit.gim.sdk.model

/**
 * SDK 配置
 *
 * 包含连接 IM 服务器所需的全部参数
 */
data class ImConfig(
    /** IM 服务器地址 */
    val host: String,
    /** IM 服务器端口 */
    val port: Int,
    /** 当前用户 ID */
    val userId: String,
    /** 认证 Token */
    val token: String,
    /** 设备类型（mobile/desktop/web/pad） */
    val device: String,
    /** 设备唯一标识（客户端持久化 UUID，区分同设备重连与异设备顶号） */
    val deviceId: String,
    /** 心跳间隔（默认 15 秒，需 ≤ 服务端读空闲超时的一半，留足网络抖动余量） */
    val heartbeatIntervalMs: Long = 15_000L,
    /** 心跳超时（默认 20 秒，超过此时间未收到心跳响应则判定超时） */
    val heartbeatTimeoutMs: Long = 20_000L,
    /** 重连基础间隔（默认 2 秒） */
    val reconnectBaseDelayMs: Long = 2_000L,
    /** 最大重连间隔（默认 60 秒） */
    val reconnectMaxDelayMs: Long = 60_000L,
    /** 最大重连次数（null=无限） */
    val maxReconnectAttempts: Int? = null,
)
