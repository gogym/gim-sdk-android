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
package io.getbit.gim.sdk.protocol

/**
 * 设备类型枚举（对应服务端 DeviceType.java）
 *
 * 用于标识客户端设备类型，绑定时上报服务端
 */
enum class DeviceType(
    /** 设备类型编码字符串 */
    val value: String,
) {
    MOBILE("mobile"),
    DESKTOP("desktop"),
    WEB("web"),
    PAD("pad");

    companion object {
        /** 根据 code 获取设备类型 */
        fun fromCode(code: String): DeviceType =
            entries.firstOrNull { it.value == code } ?: MOBILE
    }
}
