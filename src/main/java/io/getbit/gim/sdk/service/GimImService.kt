package io.getbit.gim.sdk.service

import android.util.Log
import io.getbit.gim.sdk.core.ImClient
import io.getbit.gim.sdk.core.ImConnectionState
import io.getbit.gim.sdk.model.ImConfig
import io.getbit.gim.sdk.protocol.Cmd
import io.getbit.gim.sdk.protocol.PacketCodec
import io.getbit.gim.sdk.spi.ImEventListener
import io.getbit.gim.sdk.protocol.ImProto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GIM IM SDK 核心服务
 *
 * 提供 IM 连接管理、消息发送、事件监听等核心能力
 * SDK 只负责通信层，不包含业务逻辑（DB、会话管理、UUID生成等）
 *
 * 使用示例：
 * ```kotlin
 * val service = GimImService()
 *
 * // 注册事件监听
 * service.addEventListener(myListener)
 *
 * // 连接
 * service.connect(ImConfig(
 *     host = "192.168.1.100",
 *     port = 3333,
 *     userId = "user_001",
 *     token = "jwt_token",
 *     device = "mobile",
 *     deviceId = "persisted_device_uuid",
 * ))
 *
 * // 发送 Packet
 * val packet = PacketCodec.buildSingleChatMsg(...)
 * service.send(packet)
 *
 * // 断开连接
 * service.disconnect()
 * ```
 */
class GimImService {

    companion object {
        private const val TAG = "GimImService"
    }

    /** 连接状态（StateFlow，UI/业务层监听） */
    private val _connectionState = MutableStateFlow(ImConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ImConnectionState> = _connectionState.asStateFlow()

    /** 已注册的事件监听器列表 */
    private val listeners = mutableListOf<ImEventListener>()

    /** 内部 ImClient 实例 */
    private var client: ImClient? = null

    /** 当前配置 */
    private var config: ImConfig? = null

    /** 添加事件监听器 */
    fun addEventListener(listener: ImEventListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /** 移除事件监听器 */
    fun removeEventListener(listener: ImEventListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /** 连接并绑定 */
    suspend fun connect(config: ImConfig) {
        this.config = config

        // 清理旧连接
        client?.disconnect()

        // 创建 ImClient
        client = ImClient(
            host = config.host,
            port = config.port,
            maxReconnectAttempts = config.maxReconnectAttempts,
            reconnectBaseDelayMs = config.reconnectBaseDelayMs,
            reconnectMaxDelayMs = config.reconnectMaxDelayMs,
            heartbeatIntervalMs = config.heartbeatIntervalMs,
            heartbeatTimeoutMs = config.heartbeatTimeoutMs,
            onStateChanged = ::onStateChanged,
            onPacket = ::onPacket,
            onBindFailed = ::onBindFailed,
            onKicked = ::onKicked,
        )

        // 连接并绑定
        client!!.connect(
            userId = config.userId,
            token = config.token,
            device = config.device,
            deviceId = config.deviceId,
        )
    }

    /** 断开连接 */
    suspend fun disconnect() {
        client?.disconnect()
    }

    /** 前台恢复时检查连接 */
    suspend fun reconnectIfNeeded() {
        val c = client
        if (c == null) {
            Log.w(TAG, "reconnectIfNeeded: client is null, skip")
            return
        }
        c.reconnectIfNeeded()
    }

    /** 进入后台时暂停重连 */
    fun pauseForBackground() {
        client?.pauseForBackground()
    }

    /**
     * 发送 Packet
     *
     * SDK 只暴露此方法用于发送数据，
     * 使用方通过 PacketCodec 构建 Packet 后调用此方法发送
     */
    fun send(packet: ImProto.Packet) {
        client?.send(packet)
    }

    /** 是否已连接并认证 */
    val isAuthenticated: Boolean get() = client?.isAuthenticated ?: false

    /** 当前用户 ID */
    val userId: String? get() = config?.userId

    /** 服务端节点 ID */
    val serverId: String? get() = client?.currentServerId

    /** 释放资源 */
    fun dispose() {
        client?.dispose()
        client = null
        synchronized(listeners) {
            listeners.clear()
        }
    }

    // ====================== 内部事件分发 ======================

    /** 连接状态变更 → 更新 StateFlow + 分发给 Listener */
    private fun onStateChanged(state: ImConnectionState) {
        _connectionState.value = state
        synchronized(listeners) {
            listeners.forEach { it.onConnectionStateChanged(state) }
        }
    }

    /** 收到 Packet → 分发给 Listener */
    private fun onPacket(packet: ImProto.Packet) {
        synchronized(listeners) {
            listeners.forEach { dispatchToListener(it, packet) }
        }
    }

    /** 根据 Packet 的 cmd 类型，调用 Listener 对应的回调方法 */
    private fun dispatchToListener(listener: ImEventListener, packet: ImProto.Packet) {
        when (packet.cmd) {
            // 聊天消息
            Cmd.SINGLE_CHAT_MSG, Cmd.GROUP_CHAT_MSG ->
                listener.onMessageReceived(packet)

            // 服务端 ACK
            Cmd.SERVER_ACK ->
                listener.onMessageSent(packet)

            // 送达 ACK
            Cmd.DELIVERY_ACK ->
                listener.onMessageDelivered(packet)

            // 已读回执
            Cmd.READ_RECEIPT ->
                listener.onMessageRead(packet)

            // 消息撤回通知
            Cmd.MSG_RECALL_NOTIFY ->
                listener.onMessageRecalled(packet)

            // 在线状态（status: 1=在线, 0=离线）
            Cmd.ONLINE_STATUS_NOTIFY -> {
                val notify = PacketCodec.parseOnlineStatusNotify(packet)
                if (notify.status == 1) {
                    listener.onUserOnline(packet)
                } else {
                    listener.onUserOffline(packet)
                }
            }

            // 好友通知
            Cmd.FRIEND_REQUEST_NOTIFY ->
                listener.onFriendRequest(packet)

            Cmd.FRIEND_STATUS_NOTIFY ->
                listener.onFriendStatusChanged(packet)

            // 群组通知
            Cmd.GROUP_MEMBER_NOTIFY ->
                listener.onGroupMemberChanged(packet)

            Cmd.GROUP_NOTIFY ->
                listener.onGroupNotify(packet)

            Cmd.GROUP_JOIN_REQUEST_NOTIFY ->
                listener.onGroupJoinRequest(packet)

            // WebRTC 信令
            Cmd.RTC_SIGNAL ->
                listener.onRtcSignal(packet)

            // WebRTC 群通话信令
            Cmd.RTC_GROUP ->
                listener.onRtcGroupSignal(packet)
        }
    }

    /** 被踢下线回调 */
    private fun onKicked(code: Int, message: String) {
        synchronized(listeners) {
            listeners.forEach { it.onKicked(code, message) }
        }
    }

    /** 绑定失败回调 */
    private fun onBindFailed(code: Int, message: String) {
        synchronized(listeners) {
            listeners.forEach { it.onBindFailed(code, message) }
        }
    }
}
