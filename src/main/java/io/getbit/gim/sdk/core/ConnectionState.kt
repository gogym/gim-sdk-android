package io.getbit.gim.sdk.core

/**
 * 连接状态枚举
 *
 * 表示 IM 客户端的 TCP 连接生命周期各阶段
 */
enum class ImConnectionState {
    /** 未连接 */
    DISCONNECTED,

    /** 连接中 */
    CONNECTING,

    /** TCP 已连接 */
    CONNECTED,

    /** 绑定成功（认证通过） */
    AUTHENTICATED,

    /** 重连中 */
    RECONNECTING,
}
