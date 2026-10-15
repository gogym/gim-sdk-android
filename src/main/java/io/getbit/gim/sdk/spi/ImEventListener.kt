/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author gogym
 */
package io.getbit.gim.sdk.spi

import io.getbit.gim.sdk.core.ImConnectionState
import io.getbit.gim.sdk.protocol.ImProto

/**
 * IM 事件监听器（对标服务端 ImEventListener）
 *
 * 使用方继承此类来接收 IM 各类事件通知
 * 所有方法提供默认空实现，使用方只需覆盖关心的方法
 */
open class ImEventListener {

    /** 收到新消息（单聊/群聊） */
    open fun onMessageReceived(packet: ImProto.Packet) {}

    /** 消息发送成功（收到 ServerAck 且 code=0） */
    open fun onMessageSent(packet: ImProto.Packet) {}

    /** 消息送达（对方已收到，DeliveryAck） */
    open fun onMessageDelivered(packet: ImProto.Packet) {}

    /** 消息已读（ReadReceipt） */
    open fun onMessageRead(packet: ImProto.Packet) {}

    /** 消息被撤回 */
    open fun onMessageRecalled(packet: ImProto.Packet) {}

    /** 用户上线 */
    open fun onUserOnline(packet: ImProto.Packet) {}

    /** 用户下线 */
    open fun onUserOffline(packet: ImProto.Packet) {}

    /** 好友申请通知 */
    open fun onFriendRequest(packet: ImProto.Packet) {}

    /** 好友状态变更 */
    open fun onFriendStatusChanged(packet: ImProto.Packet) {}

    /** 群成员变更 */
    open fun onGroupMemberChanged(packet: ImProto.Packet) {}

    /** 群信息/事件通知 */
    open fun onGroupNotify(packet: ImProto.Packet) {}

    /** 入群申请 */
    open fun onGroupJoinRequest(packet: ImProto.Packet) {}

    /** WebRTC 信令 */
    open fun onRtcSignal(packet: ImProto.Packet) {}

    /** WebRTC 群通话信令 */
    open fun onRtcGroupSignal(packet: ImProto.Packet) {}

    /** 被踢下线 */
    open fun onKicked(code: Int, message: String) {}

    /** 连接状态变更 */
    open fun onConnectionStateChanged(state: ImConnectionState) {}

    /** 绑定失败 */
    open fun onBindFailed(code: Int, message: String) {}
}
