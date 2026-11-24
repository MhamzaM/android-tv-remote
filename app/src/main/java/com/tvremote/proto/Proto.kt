package com.tvremote.proto

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A tiny protobuf encoder/decoder. The Android TV remote protocol only needs varints,
 * strings, bytes and nested messages, so this avoids pulling in protoc + a Gradle plugin.
 */
class ProtoWriter {
    private val buf = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            buf.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        buf.write(v.toInt())
    }

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    fun int(field: Int, value: Int): ProtoWriter = apply {
        tag(field, 0)
        varint(value.toLong()) // negative int32 values are sign-extended, as protobuf requires
    }

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, 2)
        varint(value.size.toLong())
        buf.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = buf.toByteArray()
}

/** A decoded protobuf message: field number -> raw values (Long for varints, ByteArray for length-delimited). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)
    fun int(field: Int): Int = (fields[field]?.lastOrNull() as? Long)?.toInt() ?: 0
    fun bool(field: Int): Boolean = int(field) != 0
    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray
    fun string(field: Int): String = bytes(field)?.toString(Charsets.UTF_8) ?: ""
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }
    fun messages(field: Int): List<ProtoMessage> =
        fields[field].orEmpty().filterIsInstance<ByteArray>().map { parse(it) }

    override fun toString(): String = fields.keys.sorted().joinToString(prefix = "ProtoMessage(", postfix = ")")

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = HashMap<Int, MutableList<Any>>()
            var pos = 0

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 64) throw IOException("Varint too long")
                }
            }

            while (pos < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                val value: Any = when (val wire = (key and 7).toInt()) {
                    0 -> readVarint()
                    1 -> { pos += 8; continue }
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > data.size) throw IOException("Bad length $len")
                        data.copyOfRange(pos, pos + len).also { pos += len }
                    }
                    5 -> { pos += 4; continue }
                    else -> throw IOException("Unsupported wire type $wire")
                }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}

/** Messages on the wire are prefixed with their length as a varint. */
object ProtoFraming {
    fun write(out: OutputStream, payload: ByteArray) {
        val frame = ByteArrayOutputStream(payload.size + 5)
        var v = payload.size
        while (v and 0x7F.inv() != 0) {
            frame.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        frame.write(v)
        frame.write(payload)
        out.write(frame.toByteArray()) // one write -> one TLS record
        out.flush()
    }

    fun read(input: InputStream): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed by the TV")
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IOException("Bad message length")
        }
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("Connection closed by the TV")
            off += n
        }
        return buf
    }
}
