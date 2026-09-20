package io.getbit.gim.sdk.rtc.group

import org.json.JSONArray
import org.json.JSONObject

/**
 * 群通话信令 payload DTO
 *
 * 与服务端 gim-im-webrtc 的 dto 包字段一一对应（JSON 序列化，序列化使用 org.json
 * 与 1:1 RtcEngine 保持一致，避免引入额外依赖），
 * 客户端负责解析 roomState/participantNotify/invite 等 payload，
 * 并构建 groupCallRequest/mediaState 等 payload。
 */

// ====================== TURN/STUN 凭据（对应 TurnCredentialsDto） ======================

/**
 * TURN/STUN 临时凭据（RESTTURN 协议，HMAC-SHA1）
 *
 * Mesh 模式下随 roomState(27) 下发，用于构建 ICE Server。
 */
data class TurnCredentials(
    val stunUrl: String = "",
    val turnUrl: String = "",
    val username: String = "",
    val credential: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stunUrl", stunUrl)
        put("turnUrl", turnUrl)
        put("username", username)
        put("credential", credential)
    }

    companion object {
        fun fromJson(json: JSONObject?): TurnCredentials? {
            json ?: return null
            return TurnCredentials(
                stunUrl = json.optString("stunUrl", ""),
                turnUrl = json.optString("turnUrl", ""),
                username = json.optString("username", ""),
                credential = json.optString("credential", ""),
            )
        }
    }
}

// ====================== 成员快照（对应 GroupMemberInfoDto） ======================

/**
 * 群通话成员快照信息
 *
 * @property status 成员状态：invited / joined / left / rejected
 * @property camera 摄像头开关（null 表示未上报，沿用上次状态）
 * @property mic 麦克风开关（null 表示未上报，沿用上次状态）
 */
data class GroupMemberInfo(
    val userId: String = "",
    val status: GroupMemberStatus = GroupMemberStatus.INVITED,
    val camera: Boolean? = null,
    val mic: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("userId", userId)
        put("status", status.value)
        camera?.let { put("camera", it) }
        mic?.let { put("mic", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): GroupMemberInfo = GroupMemberInfo(
            userId = json.optString("userId", ""),
            status = GroupMemberStatus.fromName(json.optString("status")),
            camera = if (json.has("camera") && !json.isNull("camera")) json.getBoolean("camera") else null,
            mic = if (json.has("mic") && !json.isNull("mic")) json.getBoolean("mic") else null,
        )

        fun fromJsonArray(json: JSONArray?): List<GroupMemberInfo> {
            json ?: return emptyList()
            return (0 until json.length())
                .map { fromJson(json.getJSONObject(it)) }
        }
    }
}

// ====================== 房间状态快照（对应 GroupRoomStateDto） ======================

/**
 * 房间状态快照（signalType=27 roomState，服务端 → 发起人/加入者）
 *
 * 加入者据此初始化房间视图：Mesh 模式对已 joined 成员逐一建连，
 * SFU 模式用 sfuToken/sfuUrl 连接 LiveKit。
 */
data class GroupRoomState(
    val roomId: String = "",
    val callId: String = "",
    val groupId: String = "",

    /** 媒体模式：mesh / sfu */
    val mode: GroupCallMode = GroupCallMode.MESH,

    /** 通话类型：audio / video */
    val callType: String = "video",

    /** 发起人 userId */
    val initiatorId: String = "",

    /** 房间状态：ringing / talking */
    val status: String = "",

    /** 成员快照（含状态与媒体开关） */
    val members: List<GroupMemberInfo> = emptyList(),

    /** SFU 接入 token（仅 SFU 模式） */
    val sfuToken: String = "",

    /** SFU 连接地址（仅 SFU 模式） */
    val sfuUrl: String = "",

    /** TURN/STUN 凭据（仅 Mesh 模式） */
    val turnInfo: TurnCredentials? = null,
) {
    val isVideoCall: Boolean get() = callType == "video"

    companion object {
        fun fromJson(json: JSONObject): GroupRoomState = GroupRoomState(
            roomId = json.optString("roomId", ""),
            callId = json.optString("callId", ""),
            groupId = json.optString("groupId", ""),
            mode = GroupCallMode.from(stringValue = json.optString("mode")),
            callType = json.optString("callType", "video"),
            initiatorId = json.optString("initiatorId", ""),
            status = json.optString("status", ""),
            members = GroupMemberInfo.fromJsonArray(json.optJSONArray("members")),
            sfuToken = json.optString("sfuToken", ""),
            sfuUrl = json.optString("sfuUrl", ""),
            turnInfo = TurnCredentials.fromJson(json.optJSONObject("turnInfo")),
        )
    }
}

// ====================== 通话邀请（对应 GroupCallInviteDto） ======================

/** 群通话邀请（signalType=21 groupCallInvite，服务端 → 被邀成员） */
data class GroupCallInvite(
    val callType: String = "video",
    val groupId: String = "",
    val initiatorId: String = "",

    /** 媒体模式：mesh / sfu */
    val mode: GroupCallMode = GroupCallMode.MESH,
) {
    val isVideoCall: Boolean get() = callType == "video"

    companion object {
        fun fromJson(json: JSONObject): GroupCallInvite = GroupCallInvite(
            callType = json.optString("callType", "video"),
            groupId = json.optString("groupId", ""),
            initiatorId = json.optString("initiatorId", ""),
            mode = GroupCallMode.from(stringValue = json.optString("mode")),
        )
    }
}

// ====================== 成员变更通知（对应 GroupCallParticipantDto） ======================

/** 成员变更通知（signalType=26 participantNotify，服务端 → 成员） */
data class GroupParticipant(
    /** 变更动作：join / leave / reject / ended / media */
    val action: GroupParticipantAction = GroupParticipantAction.MEDIA,

    /** 触发变更的成员 userId */
    val userId: String = "",

    /** 变更原因（leave/reject 时可携带，如 timeout、busy） */
    val reason: String = "",

    /** 当前仍在通话中的成员数 */
    val memberCount: Int = 0,

    /** 仍在通话中的成员快照（携带最新媒体开关） */
    val members: List<GroupMemberInfo> = emptyList(),
) {
    companion object {
        fun fromJson(json: JSONObject): GroupParticipant = GroupParticipant(
            action = GroupParticipantAction.fromName(json.optString("action"))
                ?: GroupParticipantAction.MEDIA,
            userId = json.optString("userId", ""),
            reason = json.optString("reason", ""),
            memberCount = json.optInt("memberCount", 0),
            members = GroupMemberInfo.fromJsonArray(json.optJSONArray("members")),
        )
    }
}

// ====================== 客户端上行 payload ======================

/**
 * 发起群通话请求 payload（对应 GroupCallRequestDto）
 *
 * @property inviteeIds 受邀成员列表（可选，为空时服务端邀请群内全部活跃成员）
 */
data class GroupCallRequestPayload(
    val callType: String,
    val inviteeIds: List<String>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("callType", callType)
        if (!inviteeIds.isNullOrEmpty()) {
            put("inviteeIds", JSONArray(inviteeIds))
        }
    }
}

/** 媒体开关状态 payload（对应 GroupMediaStateDto，camera/mic 至少一项非 null） */
data class GroupMediaStatePayload(
    val camera: Boolean? = null,
    val mic: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        camera?.let { put("camera", it) }
        mic?.let { put("mic", it) }
    }
}

/** 拒绝/退出原因 payload（对应 SingleCallRejectDto，仅携带 reason） */
data class GroupReasonPayload(val reason: String) {
    fun toJson(): JSONObject = JSONObject().put("reason", reason)
}
