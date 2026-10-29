package com.universalir.ir.format

import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.TimingBuilder
import java.util.Base64

sealed interface DecodeResult<out T> {
    data class Ok<T>(val value: T) : DecodeResult<T>
    data class Error(val reason: String) : DecodeResult<Nothing>
}

/**
 * Broadlink IR packets (as stored by SmartIR / python-broadlink, base64):
 * `0x26, repeat, len_lo, len_hi`, then durations in 2^-15 s ticks; a `0x00` byte introduces a
 * 16-bit big-endian value. Broadlink does not record the carrier, so 38 kHz is assumed and flagged.
 */
object BroadlinkCodec {
    const val ASSUMED_CARRIER_HZ = 38_000
    private const val IR_TYPE = 0x26
    const val MAX_PACKET_BYTES = 8_192

    fun ticksToMicros(ticks: Int): Int = Math.round(ticks * 8192.0 / 269.0).toInt()

    fun decodeBase64(b64: String): DecodeResult<IrPattern> {
        var cleaned = b64.trim().removePrefix("b64:").filterNot { it.isWhitespace() }.trimEnd('=')
        // Some published packets carry one stray trailing character; a 4n+1 length can never be valid.
        if (cleaned.length % 4 == 1) cleaned = cleaned.dropLast(1)
        if (cleaned.length > MAX_PACKET_BYTES * 2) return DecodeResult.Error("Broadlink packet too large")
        val bytes = try {
            Base64.getDecoder().decode(cleaned + "=".repeat((4 - cleaned.length % 4) % 4))
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Error("Invalid base64: ${e.message}")
        }
        return decode(bytes)
    }

    fun decode(raw: ByteArray): DecodeResult<IrPattern> {
        if (raw.size < 6) return DecodeResult.Error("Broadlink packet too short")
        val type = raw[0].toInt() and 0xFF
        if (type != IR_TYPE) return DecodeResult.Error("Not an IR packet (type 0x%02X, RF codes cannot be sent by a phone)".format(type))
        val len = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
        if (len <= 0 || 4 + len > raw.size) return DecodeResult.Error("Declared length $len exceeds packet size ${raw.size}")
        val builder = TimingBuilder()
        var i = 4
        var mark = true
        val end = 4 + len
        while (i < end) {
            var ticks = raw[i].toInt() and 0xFF
            if (ticks == 0) {
                if (i + 2 >= end) break
                ticks = ((raw[i + 1].toInt() and 0xFF) shl 8) or (raw[i + 2].toInt() and 0xFF)
                i += 3
            } else {
                i += 1
            }
            val us = ticksToMicros(ticks)
            if (mark) builder.mark(us) else builder.space(us)
            mark = !mark
        }
        val pattern = builder.build(ASSUMED_CARRIER_HZ)
        return if (pattern.size == 0) DecodeResult.Error("Packet contains no timings") else DecodeResult.Ok(pattern)
    }
}

/** Pronto Hex "learned" codes (0000 type). */
object ProntoCodec {
    fun decode(text: String): DecodeResult<IrPattern> {
        val words = text.trim().split(Regex("\\s+")).map {
            it.toIntOrNull(16) ?: return DecodeResult.Error("'$it' is not a hex word")
        }
        if (words.size < 6) return DecodeResult.Error("Pronto code too short")
        if (words[0] != 0) return DecodeResult.Error("Only learned (0000) Pronto codes are supported")
        val freqWord = words[1]
        if (freqWord == 0) return DecodeResult.Error("Pronto frequency word is zero")
        val carrier = Math.round(1_000_000.0 / (freqWord * 0.241246)).toInt()
        val once = words[2]
        val repeat = words[3]
        val pairs = once + repeat
        if (4 + pairs * 2 > words.size) return DecodeResult.Error("Pronto pair counts exceed data")
        val unit = freqWord * 0.241246
        val builder = TimingBuilder()
        val data = words.subList(4, 4 + pairs * 2)
        data.forEachIndexed { idx, w ->
            val us = Math.round(w * unit).toInt()
            if (idx % 2 == 0) builder.mark(us) else builder.space(us)
        }
        return DecodeResult.Ok(builder.build(carrier))
    }
}

/** Builds a pattern from a whitespace separated list of positive alternating durations. */
object RawTimingsParser {
    fun parse(text: String, carrierHz: Int): DecodeResult<IrPattern> {
        // Some SmartIR ESPHome files store the list as a string: "[8991, -4546, ...]".
        val tokens = text.trim().removePrefix("[").removeSuffix("]").split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return DecodeResult.Error("No timings")
        if (tokens.size > 4096) return DecodeResult.Error("Too many timings (${tokens.size})")
        val builder = TimingBuilder()
        tokens.forEachIndexed { i, t ->
            val v = t.toIntOrNull() ?: return DecodeResult.Error("'$t' is not an integer")
            // ESPHome style signed raw: positive = mark, negative = space.
            when {
                v < 0 -> builder.space(-v)
                tokens.any { it.startsWith("-") } -> builder.mark(v)
                i % 2 == 0 -> builder.mark(v)
                else -> builder.space(v)
            }
            if (v == 0) return DecodeResult.Error("Zero duration at index $i")
        }
        return DecodeResult.Ok(builder.build(carrierHz))
    }
}
