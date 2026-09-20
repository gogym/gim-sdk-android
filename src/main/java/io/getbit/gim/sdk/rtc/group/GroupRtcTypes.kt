package io.getbit.gim.sdk.rtc.group

/**
 * 群通话相关类型定义
 *
 * 与服务端 gim-im-webrtc 的 GroupSignalType / GroupCallMode /
 * GroupCallMemberStatus / GroupCallParticipantDto 保持一致。
 * 禁止使用魔法值，客户端一律引用此处的常量与枚举。
 */

/**
 * 群通话生命周期信令类型（signalType 字段值，cmd=51 RTC_GROUP）
 *
 * 与 ImProto.proto 中 RtcGroup 注释保持一致：
 * 1~8 为媒体信令（offer/answer/ICE 等，Mesh 模式成员间复用，走 cmd=50 点对点）；
 * 20~27 为群通话生命周期信令；100=mediaState 媒体开关（跨场景共用）。
 */
object GroupSignalType {
    /** 发起群通话（客户端 → 服务端） */
    const val GROUP_CALL_REQUEST = 20

    /** 群通话邀请（服务端下发） */
    const val GROUP_CALL_INVITE = 21

    /** 加入群通话（客户端 → 服务端） */
    const val GROUP_CALL_JOIN = 22

    /** 拒绝邀请（客户端 → 服务端） */
    const val GROUP_CALL_REJECT = 23

    /** 退出群通话（客户端 → 服务端） */
    const val GROUP_CALL_LEAVE = 24

    /** 结束全员通话（仅发起人） */
    const val GROUP_CALL_END = 25

    /** 成员变更通知（服务端下发） */
    const val PARTICIPANT_NOTIFY = 26

    /** 房间状态快照（服务端下发） */
    const val ROOM_STATE = 27

    /**
     * 媒体开关状态（与 1:1 RtcSignalType.MEDIA_STATE 共用同一编号）
     *
     * payload 只携带发生变化的项：`{"camera": bool}` / `{"mic": bool}`。
     */
    const val MEDIA_STATE = 100
}

/** 群通话媒体架构模式（对应服务端 GroupCallMode，RtcGroup.mode 字段） */
enum class GroupCallMode {
    /** Mesh：成员间 P2P 直连，服务端只做信令扇出（小群，默认 ≤8 人） */
    MESH,

    /** SFU：媒体流由外部 SFU（如 LiveKit）承载（大群，20+ 人） */
    SFU;

    companion object {
        /**
         * 按 RtcGroup.mode / payload mode 字符串解析
         *
         * [intValue] 为 RtcGroup.mode 字段（0-Mesh 1-SFU）；
         * [stringValue] 为 payload 中的 mode 字符串（mesh / sfu），优先级低于 [intValue]。
         */
        fun from(intValue: Int? = null, stringValue: String? = null): GroupCallMode {
            if (intValue != null && intValue != 0) return SFU
            if (intValue != null) return MESH
            if (stringValue == "sfu") return SFU
            return MESH
        }
    }

    /** 按 RtcGroup.mode 字段取值 */
    val intValue: Int get() = if (this == SFU) 1 else 0

    /** 按 payload mode 字符串取值 */
    val value: String get() = if (this == SFU) "sfu" else "mesh"
}

/** 群通话成员状态（对应服务端 GroupCallMemberStatus） */
enum class GroupMemberStatus {
    /** 已邀请未响应 */
    INVITED,

    /** 已加入通话 */
    JOINED,

    /** 已退出 */
    LEFT,

    /** 已拒绝 */
    REJECTED;

    companion object {
        /** 按服务端下发的状态字符串解析（小写），未知值回退 INVITED */
        fun fromName(name: String?): GroupMemberStatus = when (name) {
            "joined" -> JOINED
            "left" -> LEFT
            "rejected" -> REJECTED
            else -> INVITED
        }
    }

    /** 按服务端约定的小写字符串取值 */
    val value: String
        get() = when (this) {
            INVITED -> "invited"
            JOINED -> "joined"
            LEFT -> "left"
            REJECTED -> "rejected"
        }
}

/** 成员变更通知动作（participantNotify payload action 字段） */
enum class GroupParticipantAction {
    /** 成员加入 */
    JOIN,

    /** 成员退出 */
    LEAVE,

    /** 成员拒绝 */
    REJECT,

    /** 通话结束 */
    ENDED,

    /** 媒体开关变更 */
    MEDIA;

    companion object {
        /** 按 payload action 字符串解析，未知值返回 null（由调用方忽略） */
        fun fromName(name: String?): GroupParticipantAction? = when (name) {
            "join" -> JOIN
            "leave" -> LEAVE
            "reject" -> REJECT
            "ended" -> ENDED
            "media" -> MEDIA
            else -> null
        }
    }
}

/** 群通话结束原因 */
enum class GroupCallEndReason {
    /** 无（通话未结束） */
    NONE,

    /** 正常结束（发起人结束/主动离开） */
    NORMAL,

    /** 被拒绝 */
    REJECTED,

    /** 邀请超时 */
    TIMEOUT,

    /** 发起人结束全员通话 */
    ENDED,

    /** 连接失败 */
    FAILED,
}

/** 群通话状态（引擎内部状态机，与 1:1 RtcCallState 对应） */
enum class GroupCallState {
    /** 空闲 */
    IDLE,

    /** 正在呼叫（发起方，等待成员加入） */
    CALLING,

    /** 来电响铃（被邀方） */
    RINGING,

    /** 已接受/收到房间快照，媒体建连中 */
    CONNECTING,

    /** 通话中 */
    CONNECTED,

    /** 已结束 */
    ENDED,
}
