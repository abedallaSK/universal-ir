package com.universalir.ir

import com.universalir.ir.core.IrPattern
import com.universalir.ir.format.BroadlinkCodec
import com.universalir.ir.format.DecodeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.fail
import kotlin.math.abs

object TestSupport {
    fun resource(path: String): String =
        TestSupport::class.java.getResource("/$path")?.readText() ?: error("Missing test resource $path")

    fun broadlink(b64: String): IrPattern = when (val r = BroadlinkCodec.decodeBase64(b64)) {
        is DecodeResult.Ok -> r.value
        is DecodeResult.Error -> error(r.reason)
    }

    /** Flattens a SmartIR climate file into key -> base64 (keys like "cool/low/24"). */
    fun smartIrCommands(file: String): Map<String, String> {
        val root = Json.parseToJsonElement(resource("smartir/$file")).jsonObject
        val out = LinkedHashMap<String, String>()
        fun walk(prefix: String, e: Any?) {
            when (e) {
                is JsonPrimitive -> out[prefix] = e.content
                is JsonObject -> e.forEach { (k, v) -> walk(if (prefix.isEmpty()) k else "$prefix/$k", v) }
            }
        }
        walk("", root["commands"])
        return out
    }

    /** Decodes pulse-distance bits (mark + variable space) starting at timing index [start]. */
    fun pulseDistanceBits(t: IntArray, start: Int, count: Int, threshold: Int = 1000): List<Boolean> =
        (0 until count).map { k -> t[start + 2 * k + 1] > threshold }

    /** Decodes pulse-width bits (variable mark) starting at [start]. */
    fun pulseWidthBits(t: IntArray, start: Int, count: Int, threshold: Int = 1000): List<Boolean> =
        (0 until count).map { k -> t[start + 2 * k] > threshold }

    fun lsbBytes(bits: List<Boolean>): IntArray =
        IntArray(bits.size / 8) { i -> (0 until 8).sumOf { j -> if (bits[i * 8 + j]) 1 shl j else 0 } }

    fun msbBytes(bits: List<Boolean>): IntArray =
        IntArray(bits.size / 8) { i -> (0 until 8).sumOf { j -> if (bits[i * 8 + j]) 1 shl (7 - j) else 0 } }

    fun hex(b: IntArray) = b.joinToString(" ") { "%02X".format(it) }

    /** Asserts two waveforms match entry-for-entry within a relative tolerance (IR jitter). */
    fun assertTimingsClose(expected: IntArray, actual: IntArray, tolerance: Double = 0.25, compareCount: Int = minOf(expected.size, actual.size)) {
        for (i in 0 until compareCount) {
            val e = expected[i]; val a = actual[i]
            if (abs(e - a) > e * tolerance + 60) fail("Timing #$i differs: expected ~$e us, got $a us")
        }
    }
}
