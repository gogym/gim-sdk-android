package io.getbit.gim.sdk.core

/**
 * Varint32 帧编解码器
 * 对齐服务端 Netty 的 ProtobufVarint32FrameDecoder/Encoder
 *
 * 协议格式：[length(varint32)][payload(length bytes)]
 * - length: 消息体长度（不含 length 本身）
 * - payload: protobuf 序列化的 Packet 字节
 */
object FrameCodec {

    /**
     * 将 payload 编码为带 Varint32 长度前缀的帧
     */
    fun encode(payload: ByteArray): ByteArray {
        val lengthBytes = encodeVarint32(payload.size)
        val frame = ByteArray(lengthBytes.size + payload.size)
        System.arraycopy(lengthBytes, 0, frame, 0, lengthBytes.size)
        System.arraycopy(payload, 0, frame, lengthBytes.size, payload.size)
        return frame
    }

    /**
     * 从缓冲区中解码帧（支持粘包/拆包）
     *
     * @return 成功解码返回 (payload, bytesConsumed)，数据不足返回 null
     */
    fun decode(buffer: ByteArray, offset: Int): FrameDecodeResult? {
        if (offset >= buffer.size) return null

        // 解码 varint32 长度
        var length = 0
        var shift = 0
        var pos = offset

        while (pos < buffer.size) {
            val byte = buffer[pos].toInt() and 0xFF
            length = length or ((byte and 0x7F) shl shift)
            pos++

            if (byte and 0x80 == 0) {
                break
            }

            shift += 7
            if (shift >= 32) {
                throw IllegalStateException("Varint32 too long")
            }
        }

        // 检查 varint 是否完整
        if (pos > buffer.size || (pos > 0 && buffer[pos - 1].toInt() and 0x80 != 0)) {
            return null // 数据不完整
        }

        // 检查 payload 是否完整
        if (pos + length > buffer.size) {
            return null // payload 数据不足
        }

        // 提取 payload
        val payload = buffer.copyOfRange(pos, pos + length)

        return FrameDecodeResult(
            payload = payload,
            bytesConsumed = pos - offset + length,
        )
    }

    /**
     * 从缓冲区中解码所有可用帧（处理粘包）
     */
    fun decodeAll(buffer: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < buffer.size) {
            val result = decode(buffer, offset) ?: break
            frames.add(result.payload)
            offset += result.bytesConsumed
        }

        return frames
    }

    /**
     * 编码 varint32（Little-Endian，最高位标记后续字节）
     */
    private fun encodeVarint32(value: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var v = value and 0xFFFFFFFF.toInt()

        while (v >= 0x80) {
            bytes.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        bytes.add(v.toByte())

        return bytes.toByteArray()
    }
}

/**
 * 帧解码结果
 */
data class FrameDecodeResult(
    /** 解码出的 payload */
    val payload: ByteArray,
    /** 消耗的字节数（varint长度字段 + payload） */
    val bytesConsumed: Int,
)
