package app.airmusic.modern.sinks.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Self-contained binary Protobuf wire serializer for Google Cast v2 CastMessage
 * (cast_channel.proto), eliminating external protoc build plugins.
 *
 * Protobuf Schema:
 * message CastMessage {
 *   required ProtocolVersion protocol_version = 1; // 0 = CASTV2_1_0
 *   required string source_id = 2;
 *   required string destination_id = 3;
 *   required string namespace = 4;
 *   required PayloadType payload_type = 5;         // 0 = STRING, 1 = BINARY
 *   optional string payload_utf8 = 6;
 * }
 */
data class CastChannelMessage(
    val sourceId: String = "sender-0",
    val destinationId: String = "receiver-0",
    val namespace: String,
    val payloadUtf8: String
)

object CastV2ProtobufCodec {

    fun writeMessage(output: DataOutputStream, msg: CastChannelMessage) {
        val body = encodeProtobufBody(msg)
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(body.size).array()
        synchronized(output) {
            output.write(header)
            output.write(body)
            output.flush()
        }
    }

    fun readMessage(input: DataInputStream): CastChannelMessage? {
        val length = input.readInt()
        if (length <= 0 || length > 1024 * 1024) return null
        val buf = ByteArray(length)
        input.readFully(buf)
        return decodeProtobufBody(buf)
    }

    private fun encodeProtobufBody(msg: CastChannelMessage): ByteArray {
        val out = ByteArrayOutputStream(256)

        // Field 1: protocol_version = 0 (varint tag = (1 << 3) | 0 = 0x08)
        out.write(0x08)
        writeVarint(out, 0)

        // Field 2: source_id (length-delimited tag = (2 << 3) | 2 = 0x12)
        writeStringField(out, tag = 0x12, value = msg.sourceId)

        // Field 3: destination_id (tag = (3 << 3) | 2 = 0x1A)
        writeStringField(out, tag = 0x1A, value = msg.destinationId)

        // Field 4: namespace (tag = (4 << 3) | 2 = 0x22)
        writeStringField(out, tag = 0x22, value = msg.namespace)

        // Field 5: payload_type = 0 (STRING) (varint tag = (5 << 3) | 0 = 0x28)
        out.write(0x28)
        writeVarint(out, 0)

        // Field 6: payload_utf8 (tag = (6 << 3) | 2 = 0x32)
        writeStringField(out, tag = 0x32, value = msg.payloadUtf8)

        return out.toByteArray()
    }

    private fun decodeProtobufBody(bytes: ByteArray): CastChannelMessage {
        var idx = 0
        var sourceId = "receiver-0"
        var destinationId = "sender-0"
        var namespace = ""
        var payloadUtf8 = ""

        while (idx < bytes.size) {
            val (tag, nextIdx1) = readVarint(bytes, idx)
            idx = nextIdx1
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (wireType) {
                0 -> { // Varint
                    val (_, nextIdx2) = readVarint(bytes, idx)
                    idx = nextIdx2
                }
                2 -> { // Length-delimited
                    val (len, nextIdx2) = readVarint(bytes, idx)
                    idx = nextIdx2
                    if (len >= 0 && idx + len <= bytes.size) {
                        val str = String(bytes, idx, len, StandardCharsets.UTF_8)
                        when (fieldNumber) {
                            2 -> sourceId = str
                            3 -> destinationId = str
                            4 -> namespace = str
                            6 -> payloadUtf8 = str
                        }
                        idx += len
                    } else {
                        break
                    }
                }
                else -> break
            }
        }
        return CastChannelMessage(sourceId, destinationId, namespace, payloadUtf8)
    }

    private fun writeStringField(out: ByteArrayOutputStream, tag: Int, value: String) {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        out.write(tag)
        writeVarint(out, utf8.size)
        out.write(utf8)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun readVarint(bytes: ByteArray, startIdx: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var idx = startIdx
        while (idx < bytes.size) {
            val b = bytes[idx].toInt() and 0xFF
            idx++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result to idx
    }
}
