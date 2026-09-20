package io.getbit.gim.sdk.protocol

import io.getbit.gim.sdk.rtc.RtcSignalType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 群通话协议编解码测试
 *
 * 重点验证 RtcGroup 的 roomId(6)/mode(7) 字段（服务端 join/reject/leave/end/mediaState
 * 均按 signal.roomId 查房间，客户端必须能正确写入与读出）。
 */
class GroupRtcProtoTest {

    @Test
    fun `rtcGroup build parse roundtrip keeps all fields`() {
        val packet = PacketCodec.buildRtcGroup(
            signalType = 22,
            senderId = "user-a",
            groupId = "group-1",
            payload = """{"callType":"video"}""",
            callId = "call-1",
            roomId = "room-1",
            mode = 0,
        )
        assertEquals(Cmd.RTC_GROUP, packet.cmd)

        val signal = PacketCodec.parseRtcGroup(packet)
        assertEquals(22, signal.signalType)
        assertEquals("user-a", signal.senderId)
        assertEquals("group-1", signal.groupId)
        assertEquals("""{"callType":"video"}""", signal.payload)
        assertEquals("call-1", signal.callId)
        // 本次修复的核心：roomId / mode 字段
        assertEquals("room-1", signal.roomId)
        assertEquals(0, signal.mode)
    }

    @Test
    fun `rtcGroup sfu mode roundtrip`() {
        val packet = PacketCodec.buildRtcGroup(
            signalType = 20,
            senderId = "user-b",
            groupId = "group-2",
            payload = "",
            callId = "call-2",
            roomId = "room-2",
            mode = 1,
        )
        val signal = PacketCodec.parseRtcGroup(packet)
        assertEquals(20, signal.signalType)
        assertEquals("room-2", signal.roomId)
        assertEquals(1, signal.mode)
    }

    @Test
    fun `rtcGroup byte roundtrip survives network encode decode`() {
        val packet = PacketCodec.buildRtcGroup(
            signalType = 24,
            senderId = "u1",
            groupId = "g1",
            payload = """{"reason":"leave"}""",
            callId = "c1",
            roomId = "r1",
            mode = 0,
        )
        // 模拟网络传输：字节编解码后再解析
        val decoded = PacketCodec.decode(packet.toByteArray())
        val signal = PacketCodec.parseRtcGroup(decoded)
        assertEquals("r1", signal.roomId)
        assertEquals(0, signal.mode)
        assertEquals(24, signal.signalType)
        assertEquals("g1", signal.groupId)
        assertEquals("c1", signal.callId)
    }

    @Test
    fun `rtcSignal media signal roundtrip for mesh transport`() {
        val packet = PacketCodec.create(
            Cmd.RTC_SIGNAL,
            body = ImProto.RtcSignal.newBuilder()
                .setSignalType(RtcSignalType.OFFER)
                .setSenderId("caller")
                .setReceiverId("callee")
                .setPayload("""{"sdp":"v=0"}""")
                .setCallId("call-9")
                .build(),
        )
        val signal = PacketCodec.parseRtcSignal(packet)
        assertEquals(RtcSignalType.OFFER, signal.signalType)
        assertEquals("caller", signal.senderId)
        assertEquals("callee", signal.receiverId)
        assertEquals("call-9", signal.callId)
    }
}
