package io.getbit.gim.sdk.core

import android.util.Log
import io.getbit.gim.sdk.protocol.Cmd
import io.getbit.gim.sdk.protocol.PacketCodec
import io.getbit.gim.sdk.protocol.ImProto
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IM 客户端 TCP 连接管理
 *
 * 负责：
 * - TCP Socket 连接/断开/重连
 * - 帧编解码（Varint32）
 * - 心跳调度
 * - 消息收发
 */
class ImClient(
    /** 服务器地址 */
    val host: String,
    /** 服务器端口 */
    val port: Int,
    /** 最大重连次数（null=无限重连） */
    private val maxReconnectAttempts: Int? = null,
    /** 重连基础间隔 */
    private val reconnectBaseDelayMs: Long = 2_000L,
    /** 最大重连间隔 */
    private val reconnectMaxDelayMs: Long = 60_000L,
    /** 心跳间隔（默认 15 秒，需 ≤ 服务端读空闲超时的一半） */
    private val heartbeatIntervalMs: Long = 15_000L,
    /** 心跳超时（默认 20 秒，超过此时间未收到心跳响应则判定超时） */
    private val heartbeatTimeoutMs: Long = 20_000L,
    /** 连接状态变更回调 */
    private val onStateChanged: ((ImConnectionState) -> Unit)? = null,
    /** 消息分发回调 */
    private val onPacket: ((ImProto.Packet) -> Unit)? = null,
    /** 绑定失败回调 */
    private val onBindFailed: ((Int, String) -> Unit)? = null,
    /** 被踢下线回调（收到 KickNotify 时触发） */
    private val onKicked: ((Int, String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "ImClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_BUFFER_SIZE = 8192
    }

    private var socket: Socket? = null
    private var state = ImConnectionState.DISCONNECTED
    private var heartbeatManager: HeartbeatManager? = null
    private var reconnectAttempts = 0
    private var disposed = false
    private var kicked = false
    private var backgrounded = false
    /** 连接代次计数器（每次 connect() 递增，防止旧 disconnect 覆盖新 connect） */
    private var connectGeneration = 0
    /** 是否已有排队的重连任务（防重入：避免多个重连线程并发建立连接，触发服务端同设备互踢形成重连循环） */
    private var reconnectScheduled = false
    /** 是否正在建立连接（doConnect/doConnectSync 互斥，保证同一时刻只有一个连接建立过程） */
    private var connecting = false
    private var userId: String? = null
    private var token: String? = null
    private var device: String? = null
    /** 设备唯一标识（持久化 UUID，用于区分同设备重连与异设备顶号） */
    private var deviceId: String? = null
    private var serverId: String? = null

    /** 读取线程 */
    private var readThread: Thread? = null

    /** 当前连接状态 */
    val currentState: ImConnectionState get() = state

    /** 服务端节点 ID */
    val currentServerId: String? get() = serverId

    /** 是否已认证 */
    val isAuthenticated: Boolean get() = state == ImConnectionState.AUTHENTICATED

    // ====================== 连接管理 ======================

    /** 连接并绑定（首包认证） */
    suspend fun connect(
        userId: String,
        token: String,
        device: String,
        deviceId: String,
    ) {
        this.userId = userId
        this.token = token
        this.device = device
        this.deviceId = deviceId
        reconnectAttempts = 0
        disposed = false
        kicked = false
        reconnectScheduled = false
        connectGeneration++

        // Socket 连接为阻塞 I/O（最长 CONNECT_TIMEOUT_MS），必须切到 IO 线程，
        // 否则会阻塞调用方协程（如 Main dispatcher）导致 UI 冻结
        withContext(Dispatchers.IO) { doConnect() }
    }

    /** 主动断开连接 */
    suspend fun disconnect() {
        val gen = connectGeneration
        disposed = true
        heartbeatManager?.dispose()
        heartbeatManager = null
        PacketCodec.resetSequence()

        try {
            readThread?.interrupt()
            socket?.close()
        } catch (_: Exception) {}

        if (gen != connectGeneration) {
            Log.d(TAG, "disconnect skipped (generation mismatch: $gen != $connectGeneration)")
            return
        }

        socket = null
        readThread = null
        setState(ImConnectionState.DISCONNECTED)
        Log.d(TAG, "disconnected")
    }

    /** 释放资源 */
    fun dispose() {
        disposed = true
        heartbeatManager?.dispose()
        try {
            readThread?.interrupt()
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }

    /** 前台恢复时检查连接状态，若已断开则立即重连 */
    suspend fun reconnectIfNeeded() {
        if (disposed) return

        // 被踢下线后自动重连被禁止（handleDisconnect/scheduleReconnect 均检查 kicked），
        // 但用户主动点击"点击重连"时应允许再次尝试：重置 kicked 并重新连接，
        // 服务器基于 token 校验决定是否接受，失败时状态回到 DISCONNECTED 可被 UI 感知
        if (kicked) {
            Log.d(TAG, "reconnectIfNeeded: kicked before, allow manual reconnect")
            kicked = false
        }

        backgrounded = false

        if (state == ImConnectionState.AUTHENTICATED || state == ImConnectionState.CONNECTED) {
            Log.d(TAG, "reconnectIfNeeded: connection is alive, skip")
            return
        }
        if (state == ImConnectionState.CONNECTING) {
            Log.d(TAG, "reconnectIfNeeded: connection in progress, skip")
            return
        }

        Log.d(TAG, "reconnectIfNeeded: state=$state, forcing reconnect")

        // 取消重连，重置计数，立即重连
        reconnectAttempts = 0
        PacketCodec.resetSequence()

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        // Socket 连接为阻塞 I/O（最长 CONNECT_TIMEOUT_MS），必须切到 IO 线程，
        // 否则会阻塞调用方协程（如 Main dispatcher）导致 UI 冻结
        withContext(Dispatchers.IO) { doConnect() }
    }

    /** App 进入后台时调用，暂停重连尝试 */
    fun pauseForBackground() {
        backgrounded = true
        Log.d(TAG, "paused for background, reconnect suspended")
    }

    // ====================== 发送 ======================

    /** 发送 Packet（线程安全，对齐 Flutter 端 Socket.add 非阻塞行为） */
    fun send(packet: ImProto.Packet) {
        synchronized(this) {
            val sock = socket
            if (sock == null || sock.isClosed) {
                Log.w(TAG, "send failed: not connected, cmd=${Cmd.nameOf(packet.cmd)}")
                return
            }

            try {
                val payload = PacketCodec.encode(packet)
                val frame = FrameCodec.encode(payload)
                sock.getOutputStream().write(frame)
                sock.getOutputStream().flush()
                Log.d(TAG, "sent cmd=${Cmd.nameOf(packet.cmd)}, seq=${packet.sequence}")
            } catch (e: Exception) {
                // 不主动关闭 socket，由读线程自然检测断连后触发重连
                // 对齐 Flutter 端 Socket.add() 不抛异常的行为
                Log.e(TAG, "send error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ====================== 内部连接逻辑 ======================

    private suspend fun doConnect() {
        synchronized(this) {
            if (disposed || connecting) {
                Log.d(TAG, "doConnect skipped (disposed=$disposed, connecting=$connecting)")
                return
            }
            // 已有活跃连接时放弃，避免并发连接触发服务端同设备互踢
            if (state == ImConnectionState.AUTHENTICATED || state == ImConnectionState.CONNECTED) {
                Log.d(TAG, "doConnect skipped (already connected: $state)")
                return
            }
            connecting = true
        }

        setState(ImConnectionState.CONNECTING)

        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = sock

            setState(ImConnectionState.CONNECTED)
            reconnectAttempts = 0
            Log.d(TAG, "TCP connected to $host:$port")

            // 启动读取线程
            startReadThread()

            // 发送绑定请求
            val bindReq = PacketCodec.buildBindReq(userId!!, token!!, device!!, deviceId!!)
            send(bindReq)
            Log.d(TAG, "sent bind request, userId=$userId")
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}")
            handleDisconnect()
        } finally {
            synchronized(this) { connecting = false }
        }
    }

    private fun startReadThread() {
        readThread?.interrupt()
        // 捕获当前 generation，防止旧读线程退出时触发多余的重连
        val threadGeneration = connectGeneration
        readThread = Thread({
            val buffer = ByteArray(READ_BUFFER_SIZE)
            val readBuffer = ByteArrayOutputStream()

            try {
                val inputStream = socket!!.getInputStream()
                while (!disposed && !Thread.currentThread().isInterrupted) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) {
                        Log.d(TAG, "socket read EOF")
                        break
                    }
                    if (bytesRead > 0) {
                        readBuffer.write(buffer, 0, bytesRead)
                        val data = readBuffer.toByteArray()
                        readBuffer.reset()

                        var offset = 0
                        // 解码所有可用帧
                        while (offset < data.size) {
                            val result = FrameCodec.decode(data, offset)
                            if (result == null) {
                                // 保留未消费的数据
                                if (offset < data.size) {
                                    readBuffer.write(data, offset, data.size - offset)
                                }
                                break
                            }

                            try {
                                val packet = PacketCodec.decode(result.payload)
                                handlePacket(packet)
                            } catch (e: Exception) {
                                Log.e(TAG, "decode error: ${e.message}")
                            }

                            offset += result.bytesConsumed
                        }
                    }
                }
            } catch (e: Exception) {
                if (!disposed) {
                    Log.e(TAG, "read thread error: ${e.message}")
                }
            }

            // 读取结束，仅当 generation 匹配时才触发断连重连
            // 防止旧连接的读线程在新连接已建立后误触发 handleDisconnect
            if (!disposed && threadGeneration == connectGeneration) {
                handleDisconnect()
            } else if (threadGeneration != connectGeneration) {
                Log.d(TAG, "read thread exit skipped (generation mismatch: $threadGeneration != $connectGeneration)")
            }
        }, "ImClient-ReadThread")
        readThread!!.isDaemon = true
        readThread!!.start()
    }

    private fun handlePacket(packet: ImProto.Packet) {
        Log.d(TAG, "received cmd=${Cmd.nameOf(packet.cmd)}, seq=${packet.sequence}")

        when (packet.cmd) {
            Cmd.BIND_RESP -> handleBindResponse(packet)
            Cmd.KICK_NOTIFY -> handleKickNotify(packet)
            Cmd.HEARTBEAT_RESP -> {
                heartbeatManager?.onHeartbeatResponse()
                onPacket?.invoke(packet)
            }
            else -> onPacket?.invoke(packet)
        }
    }

    private fun handleBindResponse(packet: ImProto.Packet) {
        val resp = PacketCodec.parseBindResponse(packet)

        if (resp.code == 0) {
            serverId = resp.serverId
            setState(ImConnectionState.AUTHENTICATED)
            Log.d(TAG, "bind success, serverId=$serverId")

            // 启动心跳
            heartbeatManager?.dispose()
            heartbeatManager = HeartbeatManager(
                intervalMs = heartbeatIntervalMs,
                timeoutMs = heartbeatTimeoutMs,
                onSend = { send(it) },
                onTimeout = { onHeartbeatTimeout() },
            )
            heartbeatManager!!.start()
        } else {
            Log.w(TAG, "bind failed: code=${resp.code}, msg=${resp.message}")
            onBindFailed?.invoke(resp.code, resp.message)
            setState(ImConnectionState.DISCONNECTED)
        }
    }

    /** 处理踢人通知（服务端发送后会立即关闭连接） */
    private fun handleKickNotify(packet: ImProto.Packet) {
        val notify = PacketCodec.parseKickNotify(packet)
        Log.w(TAG, "kicked: code=${notify.code}, msg=${notify.message}")

        kicked = true
        heartbeatManager?.stop()

        onKicked?.invoke(notify.code, notify.message)

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        setState(ImConnectionState.DISCONNECTED)
    }

    private fun onHeartbeatTimeout() {
        Log.w(TAG, "heartbeat timeout, reconnecting...")
        heartbeatManager?.stop()

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        handleDisconnect()
    }

    private fun handleDisconnect() {
        if (disposed) return
        if (kicked) return

        heartbeatManager?.stop()
        socket = null

        if (state == ImConnectionState.DISCONNECTED) return

        // 使用 generation 防止过时的断连请求触发多余重连
        scheduleReconnect(connectGeneration)
    }

    private fun scheduleReconnect(expectedGeneration: Int) {
        synchronized(this) {
            if (disposed) return

            // generation 不匹配说明已有新的 connect() 调用，跳过
            if (expectedGeneration != connectGeneration) {
                Log.d(TAG, "scheduleReconnect skipped (generation mismatch: $expectedGeneration != $connectGeneration)")
                return
            }

            if (maxReconnectAttempts != null && reconnectAttempts >= maxReconnectAttempts) {
                Log.w(TAG, "max reconnect attempts reached")
                setState(ImConnectionState.DISCONNECTED)
                return
            }

            // 已有排队的重连任务时跳过，防止多个重连线程并发建立连接
            // （多个连接会触发服务端同设备互踢，被替换的连接又触发重连，形成无限循环）
            if (reconnectScheduled) {
                Log.d(TAG, "scheduleReconnect skipped (reconnect already scheduled)")
                return
            }
            reconnectScheduled = true

            setState(ImConnectionState.RECONNECTING)

            // 后台时使用更长的固定间隔（5分钟），减少电池消耗
            val delayMs: Long = if (backgrounded) {
                Log.d(TAG, "backgrounded, reconnecting in 5min (attempt ${reconnectAttempts + 1})")
                5 * 60 * 1000L
            } else {
                val delay = (reconnectBaseDelayMs * 2.0.pow(reconnectAttempts).toLong())
                    .coerceAtMost(reconnectMaxDelayMs)
                Log.d(TAG, "reconnecting in ${delay / 1000}s (attempt ${reconnectAttempts + 1})")
                delay
            }

            Thread({
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    // 被中断（disconnect）时直接结束
                }

                var shouldReconnect = false
                synchronized(this) {
                    // 醒来后先清除标志，连接失败时允许再次调度重连
                    reconnectScheduled = false
                    if (!disposed && !kicked && expectedGeneration == connectGeneration
                        && state != ImConnectionState.AUTHENTICATED
                        && state != ImConnectionState.CONNECTED
                    ) {
                        reconnectAttempts++
                        PacketCodec.resetSequence()
                        shouldReconnect = true
                    }
                }

                if (shouldReconnect) {
                    try {
                        doConnectSync()
                    } catch (e: Exception) {
                        Log.e(TAG, "reconnect failed: ${e.message}")
                        handleDisconnect()
                    }
                }
            }, "ImClient-Reconnect").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /** 同步版本的连接逻辑（用于重连线程） */
    private fun doConnectSync() {
        synchronized(this) {
            if (disposed || connecting) {
                Log.d(TAG, "doConnectSync skipped (disposed=$disposed, connecting=$connecting)")
                return
            }
            // 已有活跃连接（如手动重连已成功）时放弃本次重连，避免并发连接触发服务端同设备互踢
            if (state == ImConnectionState.AUTHENTICATED || state == ImConnectionState.CONNECTED) {
                Log.d(TAG, "doConnectSync skipped (already connected: $state)")
                return
            }
            connecting = true
        }

        setState(ImConnectionState.CONNECTING)

        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = sock

            setState(ImConnectionState.CONNECTED)
            reconnectAttempts = 0
            Log.d(TAG, "TCP connected to $host:$port")

            startReadThread()

            val bindReq = PacketCodec.buildBindReq(userId!!, token!!, device!!, deviceId!!)
            send(bindReq)
            Log.d(TAG, "sent bind request, userId=$userId")
        } catch (e: Exception) {
            Log.e(TAG, "reconnect failed: ${e.message}")
            handleDisconnect()
        } finally {
            synchronized(this) { connecting = false }
        }
    }

    private fun setState(newState: ImConnectionState) {
        if (state == newState) return
        state = newState
        onStateChanged?.invoke(newState)
    }
}
