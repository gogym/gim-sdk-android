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
