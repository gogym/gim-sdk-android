package io.getbit.gim.sdk.rtc

/**
 * RTC 通话相关类型定义
 *
 * 对标 Flutter SDK rtc_types.dart：
 * 通话类型、通话状态、信令类型、通话结束原因等枚举
 */

/** 通话类型 */
enum class RtcCallType {
    AUDIO,
    VIDEO,
}

/** 通话状态 */
enum class RtcCallState {
    IDLE,        // 空闲
    CALLING,     // 正在呼叫（发起方）
    RINGING,     // 来电响铃（接收方）
    CONNECTING,  // 连接中（WebRTC 协商中）
    CONNECTED,   // 通话中
    ENDED,       // 已结束
}

/**
 * RTC 信令类型（signalType 字段值）
 *
 * 与 ImProto.proto 中 RtcSignal 注释保持一致：
 * 1=offer, 2=answer, 3=iceCandidate,
 * 4=callRequest, 5=callAccept, 6=callReject, 7=callCancel, 8=callHangup,
 * 9=callAck（服务端→主叫：回传服务端生成的 callId）
 * 10~19 预留；20~27 群通话生命周期；28~99 预留；100=mediaState（媒体开关，独立高位、与群通话共用）
 */
object RtcSignalType {
    const val OFFER = 1
    const val ANSWER = 2
    const val ICE_CANDIDATE = 3
    const val CALL_REQUEST = 4
    const val CALL_ACCEPT = 5
    const val CALL_REJECT = 6
    const val CALL_CANCEL = 7
    const val CALL_HANGUP = 8

    /** 呼叫确认（9；服务端→主叫，回传服务端生成的权威 callId） */
    const val CALL_ACK = 9

    /** 媒体开关状态（100；独立高位编号，1:1 与群通话共用，与两侧生命周期号段隔离） */
    const val MEDIA_STATE = 100
}

/** 通话结束原因 */
enum class RtcCallEndReason {
    NONE,       // 无（通话未结束）
    NORMAL,     // 正常挂断
    REJECTED,   // 对方拒绝
    CANCELLED,  // 对方取消
    BUSY,       // 对方忙线
    TIMEOUT,    // 无人接听
    FAILED,     // 连接失败
}
