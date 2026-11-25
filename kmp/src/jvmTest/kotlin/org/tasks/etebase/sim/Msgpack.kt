package org.tasks.etebase.sim

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The msgpack subset that etebase uses (rmp_serde on the client, msgpack-python on the server): nil, bool, integers,
 * str, bin, arrays and maps with string keys. Decoded maps are [LinkedHashMap]s, bin is [ByteArray], integers [Long].
 */
object Msgpack {
    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    fun decode(data: ByteArray): Any? = Reader(ByteBuffer.wrap(data)).read()

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xc0)
            is Boolean -> out.write(if (value) 0xc3 else 0xc2)
            is Int -> write(out, value.toLong())
            is Long -> when {
                value in 0..0x7f -> out.write(value.toInt())
                value in -32..-1 -> out.write(value.toInt() and 0xff)
                value in 0..0xff -> { out.write(0xcc); out.write(value.toInt()) }
                value in 0..0xffff -> { out.write(0xcd); be(out, value, 2) }
                value in 0..0xffffffffL -> { out.write(0xce); be(out, value, 4) }
                value >= 0 -> { out.write(0xcf); be(out, value, 8) }
                else -> { out.write(0xd3); be(out, value, 8) }
            }
            is String -> value.encodeToByteArray().let { bytes ->
                when {
                    bytes.size < 32 -> out.write(0xa0 or bytes.size)
                    bytes.size <= 0xff -> { out.write(0xd9); out.write(bytes.size) }
                    bytes.size <= 0xffff -> { out.write(0xda); be(out, bytes.size.toLong(), 2) }
                    else -> { out.write(0xdb); be(out, bytes.size.toLong(), 4) }
                }
                out.write(bytes)
            }
            is ByteArray -> {
                when {
                    value.size <= 0xff -> { out.write(0xc4); out.write(value.size) }
                    value.size <= 0xffff -> { out.write(0xc5); be(out, value.size.toLong(), 2) }
                    else -> { out.write(0xc6); be(out, value.size.toLong(), 4) }
                }
                out.write(value)
            }
            is List<*> -> {
                header(out, value.size, 0x90, 0xdc)
                value.forEach { write(out, it) }
            }
            is Map<*, *> -> {
                header(out, value.size, 0x80, 0xde)
                value.forEach { (k, v) -> write(out, k); write(out, v) }
            }
            else -> error("cannot encode ${value::class}")
        }
    }

    private fun header(out: ByteArrayOutputStream, size: Int, fix: Int, sized: Int) = when {
        size < 16 -> out.write(fix or size)
        size <= 0xffff -> { out.write(sized); be(out, size.toLong(), 2) }
        else -> { out.write(sized + 1); be(out, size.toLong(), 4) }
    }

    private fun be(out: ByteArrayOutputStream, value: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) out.write((value shr (8 * i)).toInt() and 0xff)
    }

    private class Reader(val buf: ByteBuffer) {
        fun read(): Any? {
            val b = buf.get().toInt() and 0xff
            return when {
                b <= 0x7f -> b.toLong()
                b in 0x80..0x8f -> map(b and 0x0f)
                b in 0x90..0x9f -> array(b and 0x0f)
                b in 0xa0..0xbf -> str(b and 0x1f)
                b >= 0xe0 -> (b - 0x100).toLong()
                else -> when (b) {
                    0xc0 -> null
                    0xc2 -> false
                    0xc3 -> true
                    0xc4 -> bin(u(1))
                    0xc5 -> bin(u(2))
                    0xc6 -> bin(u(4))
                    0xcc -> u(1).toLong()
                    0xcd -> u(2).toLong()
                    0xce -> buf.int.toLong() and 0xffffffffL
                    0xcf -> buf.long
                    0xd0 -> buf.get().toLong()
                    0xd1 -> buf.short.toLong()
                    0xd2 -> buf.int.toLong()
                    0xd3 -> buf.long
                    0xd9 -> str(u(1))
                    0xda -> str(u(2))
                    0xdb -> str(u(4))
                    0xdc -> array(u(2))
                    0xdd -> array(u(4))
                    0xde -> map(u(2))
                    0xdf -> map(u(4))
                    else -> error("unsupported msgpack type 0x${b.toString(16)}")
                }
            }
        }

        private fun u(bytes: Int): Int = when (bytes) {
            1 -> buf.get().toInt() and 0xff
            2 -> buf.short.toInt() and 0xffff
            else -> buf.int
        }

        private fun bin(size: Int) = ByteArray(size).also { buf.get(it) }
        private fun str(size: Int) = bin(size).decodeToString()
        private fun array(size: Int) = List(size) { read() }
        private fun map(size: Int): Map<String, Any?> =
            LinkedHashMap<String, Any?>().also { m -> repeat(size) { m[read() as String] = read() } }
    }
}
