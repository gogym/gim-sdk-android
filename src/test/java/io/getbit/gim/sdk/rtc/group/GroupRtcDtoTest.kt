package io.getbit.gim.sdk.rtc.group

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群通话 DTO JSON 序列化/反序列化测试（字段名对齐服务端 gim-im-webrtc dto）
 */
class GroupRtcDtoTest {

    @Test
    fun `roomState fromJson parses server payload`() {
        val json = JSONObject(
            """
            {
              "roomId": "room-1",
              "callId": "call-1",
              "groupId": "group-1",
              "mode": "mesh",
              "callType": "video",
              "initiatorId": "user-a",
              "status": "talking",
              "members": [
                {"userId": "user-a", "status": "joined", "camera": true, "mic": true},
                {"userId": "user-b", "status": "invited"}
              ],
              "turnInfo": {"stunUrl": "stun:s1", "turnUrl": "turn:t1", "username": "u", "credential": "c"}
            }
            """.trimIndent()
        )
        val state = GroupRoomState.fromJson(json)
        assertEquals("room-1", state.roomId)
        assertEquals("call-1", state.callId)
        assertEquals("group-1", state.groupId)
        assertEquals(GroupCallMode.MESH, state.mode)
        assertEquals("video", state.callType)
        assertTrue(state.isVideoCall)
        assertEquals("user-a", state.initiatorId)
        assertEquals("talking", state.status)
        assertEquals(2, state.members.size)
        assertEquals(GroupMemberStatus.JOINED, state.members[0].status)
        assertEquals(true, state.members[0].camera)
        assertNull(state.members[1].camera)
        assertEquals("turn:t1", state.turnInfo?.turnUrl)
    }

    @Test
    fun `roomState mode sfu and audio call`() {
        val json = JSONObject(
            """{"roomId":"r","callId":"c","mode":"sfu","callType":"audio","sfuUrl":"wss://sfu","sfuToken":"tk"}"""
        )
        val state = GroupRoomState.fromJson(json)
        assertEquals(GroupCallMode.SFU, state.mode)
        assertFalse(state.isVideoCall)
        assertEquals("wss://sfu", state.sfuUrl)
        assertEquals("tk", state.sfuToken)
        assertTrue(state.members.isEmpty())
    }

    @Test
    fun `memberInfo toJson fromJson roundtrip keeps nullable flags`() {
        val withFlags = GroupMemberInfo(userId = "u1", status = GroupMemberStatus.JOINED, camera = false, mic = true)
        val parsed1 = GroupMemberInfo.fromJson(withFlags.toJson())
        assertEquals(withFlags, parsed1)

        val withoutFlags = GroupMemberInfo(userId = "u2", status = GroupMemberStatus.REJECTED)
        val parsed2 = GroupMemberInfo.fromJson(withoutFlags.toJson())
        assertEquals(withoutFlags, parsed2)
        assertNull(parsed2.camera)
        assertNull(parsed2.mic)
    }

    @Test
    fun `invite parses mode string`() {
        val invite = GroupCallInvite.fromJson(JSONObject("""{"callType":"video","groupId":"g1","initiatorId":"ia","mode":"mesh"}"""))
        assertEquals(GroupCallMode.MESH, invite.mode)
        assertEquals("g1", invite.groupId)
        assertEquals("ia", invite.initiatorId)
        assertTrue(invite.isVideoCall)
    }

    @Test
    fun `participant notify parses actions`() {
        val join = GroupParticipant.fromJson(JSONObject("""{"action":"join","userId":"u1","memberCount":3}"""))
        assertEquals(GroupParticipantAction.JOIN, join.action)
        assertEquals("u1", join.userId)
        assertEquals(3, join.memberCount)

        val leave = GroupParticipant.fromJson(JSONObject("""{"action":"leave","userId":"u2","reason":"timeout","members":[{"userId":"u1","status":"joined"}]}"""))
        assertEquals(GroupParticipantAction.LEAVE, leave.action)
        assertEquals("timeout", leave.reason)
        assertEquals(1, leave.members.size)

        // 未知 action 回退 MEDIA（由调用方忽略）
        val unknown = GroupParticipant.fromJson(JSONObject("""{"action":"whatever"}"""))
        assertEquals(GroupParticipantAction.MEDIA, unknown.action)
    }

    @Test
    fun `request payload serializes server fields`() {
        val json = GroupCallRequestPayload(callType = "video", inviteeIds = listOf("u1", "u2")).toJson()
        assertEquals("video", json.getString("callType"))
        assertEquals(listOf("u1", "u2"), json.getJSONArray("inviteeIds").map { it.toString() })

        val noInvitees = GroupCallRequestPayload(callType = "audio").toJson()
        assertEquals("audio", noInvitees.getString("callType"))
        assertFalse(noInvitees.has("inviteeIds"))
    }

    @Test
    fun `mediaState payload only carries changed fields`() {
        val cameraOnly = GroupMediaStatePayload(camera = false).toJson()
        assertTrue(cameraOnly.has("camera"))
        assertFalse(cameraOnly.getBoolean("camera"))
        assertFalse(cameraOnly.has("mic"))

        val both = GroupMediaStatePayload(camera = true, mic = false).toJson()
        assertTrue(both.getBoolean("camera"))
        assertFalse(both.getBoolean("mic"))
    }

    @Test
    fun `reason payload carries reason`() {
        assertEquals("busy", GroupReasonPayload("busy").toJson().getString("reason"))
    }

    @Test
    fun `enums resolve server strings and ints`() {
        assertEquals(GroupCallMode.SFU, GroupCallMode.from(intValue = 1))
        assertEquals(GroupCallMode.MESH, GroupCallMode.from(intValue = 0))
        assertEquals(GroupCallMode.SFU, GroupCallMode.from(stringValue = "sfu"))
        assertEquals(GroupCallMode.MESH, GroupCallMode.from(stringValue = "mesh"))
        assertEquals(0, GroupCallMode.MESH.intValue)
        assertEquals("mesh", GroupCallMode.MESH.value)
        assertEquals("sfu", GroupCallMode.SFU.value)

        assertEquals(GroupMemberStatus.JOINED, GroupMemberStatus.fromName("joined"))
        assertEquals(GroupMemberStatus.LEFT, GroupMemberStatus.fromName("left"))
        assertEquals(GroupMemberStatus.REJECTED, GroupMemberStatus.fromName("rejected"))
        assertEquals(GroupMemberStatus.INVITED, GroupMemberStatus.fromName("invited"))
        assertEquals(GroupMemberStatus.INVITED, GroupMemberStatus.fromName(null))
        assertEquals("joined", GroupMemberStatus.JOINED.value)
    }

    @Test
    fun `mesh offerer decided by lexicographic order`() {
        assertTrue(isMeshOfferer("a", "b"))
        assertFalse(isMeshOfferer("b", "a"))
        assertFalse(isMeshOfferer("u1", "u1"))
    }
}
