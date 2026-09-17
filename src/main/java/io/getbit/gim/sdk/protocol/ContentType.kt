package io.getbit.gim.sdk.protocol

/**
 * 消息内容类型枚举（对应服务端 ContentType.java）
 *
 * 用于标识聊天消息的内容类型
 */
enum class ContentType(
    /** 数值编码（与服务端一致） */
    val value: Int,
    /** 可读标签 */
    val label: String,
    /** 推送显示文本 */
    val pushText: String,
) {
    TEXT(1, "文字", "[消息]"),
    IMAGE(2, "图片", "[图片]"),
    AUDIO(3, "语音", "[语音]"),
    VIDEO(4, "视频", "[视频]"),
    FILE(5, "文件", "[文件]"),
    LOCATION(6, "位置", "[位置]"),
    CUSTOM(7, "自定义", "[自定义消息]"),
    CALL_RECORD(8, "通话记录", "[通话记录]");

    companion object {
        /** 根据 value 获取枚举 */
        fun fromValue(value: Int): ContentType =
            entries.firstOrNull { it.value == value } ?: TEXT
    }
}
