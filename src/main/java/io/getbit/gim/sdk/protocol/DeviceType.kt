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
