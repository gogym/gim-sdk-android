package io.getbit.gim.sdk.core

import android.util.Log
import io.getbit.gim.sdk.protocol.PacketCodec
import io.getbit.gim.sdk.protocol.ImProto

/**
 * 心跳管理器
 * 负责定时发送心跳 + 超时检测
 *
 * 采用单线程循环 + wait/notify 实现，避免 java.util.Timer 的缺陷：
 * Timer.cancel() 无法取消已被 Timer 线程取出、正在等待执行的 TimerTask，
 * 导致旧定时器复活、多个超时定时器并存，最终误报心跳超时触发断连。
 */
class HeartbeatManager(
    /** 心跳间隔（默认 15 秒） */
    private val intervalMs: Long = 15_000L,
    /** 超时时间（默认 20 秒，超过此时间未收到心跳响应则判定超时） */
    private val timeoutMs: Long = 20_000L,
    /** 发送回调 */
    private val onSend: (ImProto.Packet) -> Unit,
    /** 超时回调 */
    private val onTimeout: () -> Unit,
) {
    companion object {
        private const val TAG = "HeartbeatManager"
    }

    /** 心跳线程（单线程驱动发送与超时检测，杜绝多定时器并存） */
    private var thread: Thread? = null
    private var disposed = false
    private val lock = Object()

    /** 是否已收到最近一次心跳的响应（读线程写，心跳线程读） */
    @Volatile
    private var respReceived = false

    /** 启动心跳 */
    fun start() {
        stop()
        disposed = false
        thread = Thread({
            try {
                while (!disposed && !Thread.currentThread().isInterrupted) {
                    // 发送心跳并等待响应；超时检测与发送在同一条线程上串行执行，
                    // 保证同一时刻只存在一个"待响应"窗口，不会出现旧定时器误触发
                    val ok = synchronized(lock) {
                        if (disposed) {
                            false
                        } else {
                            respReceived = false
                            lastSendTs = System.currentTimeMillis()
                            onSend(PacketCodec.buildHeartbeatReq())
                            Log.d(TAG, "sent heartbeat")
                            lock.wait(timeoutMs)
                            respReceived
                        }
                    }
                    if (!ok) {
                        if (!disposed) {
                            Log.w(TAG, "timeout!")
                            onTimeout()
                        }
                        break
                    }

                    // 收到响应：休眠到下一次计划发送时刻，保持固定心跳间隔
                    val remain = lastSendTs + intervalMs - System.currentTimeMillis()
                    if (remain > 0) {
                        Thread.sleep(remain)
                    }
                }
            } catch (_: InterruptedException) {
                // 被 stop()/dispose() 中断，正常退出
            }
        }, "ImClient-Heartbeat")
        thread!!.isDaemon = true
        thread!!.start()
        Log.d(TAG, "started, interval=${intervalMs / 1000}s, timeout=${timeoutMs / 1000}s")
    }

    /** 最近一次心跳发送时间 */
    @Volatile
    private var lastSendTs = 0L

    /** 收到心跳响应（唤醒心跳线程，重置超时判定） */
    fun onHeartbeatResponse() {
        respReceived = true
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    /** 停止心跳 */
    fun stop() {
        disposed = true
        thread?.interrupt()
        thread = null
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    /** 释放资源 */
    fun dispose() {
        disposed = true
        stop()
    }
}
