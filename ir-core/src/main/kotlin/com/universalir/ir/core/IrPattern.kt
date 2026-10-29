package com.universalir.ir.core

/**
 * One call to `ConsumerIrManager.transmit(carrierFrequency, pattern)`.
 *
 * [timingsUs] alternates mark (carrier on) and space (carrier off) durations in
 * microseconds and always starts with a mark, which is the format the Android API expects.
 */
class IrPattern(
    val carrierHz: Int,
    timingsUs: IntArray,
    /** Human readable frame boundaries (indices into [timingsUs] where a protocol frame starts). */
    val frameStarts: List<Int> = listOf(0),
) {
    private val timings: IntArray = timingsUs.copyOf()

    val size: Int get() = timings.size

    fun timings(): IntArray = timings.copyOf()

    val totalDurationUs: Long get() = timings.fold(0L) { acc, v -> acc + v }

    val markCount: Int get() = (timings.size + 1) / 2

    override fun equals(other: Any?): Boolean =
        other is IrPattern && other.carrierHz == carrierHz && other.timings.contentEquals(timings)

    override fun hashCode(): Int = 31 * carrierHz + timings.contentHashCode()

    override fun toString(): String =
        "IrPattern(carrier=$carrierHz, entries=${timings.size}, duration=${totalDurationUs}us)"
}

/**
 * A protocol-level message sequence. Each [IrMessage] is a complete, independently decodable IR
 * message; the transmitter sends them as separate `transmit()` calls with [IrMessage.pauseAfterMs]
 * between them. Splitting only ever happens at these protocol-defined boundaries; a single message
 * is never cut at an arbitrary offset.
 */
data class IrTransmission(
    val messages: List<IrMessage>,
) {
    init {
        require(messages.isNotEmpty()) { "A transmission needs at least one message" }
    }

    val totalAirTimeUs: Long get() = messages.sumOf { it.pattern.totalDurationUs }

    companion object {
        fun single(pattern: IrPattern, label: String = "frame") =
            IrTransmission(listOf(IrMessage(label, pattern, 0)))
    }
}

data class IrMessage(
    val label: String,
    val pattern: IrPattern,
    /** Silence to wait before the next message (ignored for the last message). */
    val pauseAfterMs: Int,
)

/**
 * Accumulates marks and spaces, merging consecutive entries of the same polarity (which is what
 * the real waveform looks like when e.g. a Manchester half-bit mark meets a header mark).
 */
class TimingBuilder {
    private val values = ArrayList<Int>(256)
    private var lastIsMark = false
    private val frameStarts = ArrayList<Int>()

    fun mark(us: Int): TimingBuilder = add(true, us)

    fun space(us: Int): TimingBuilder = add(false, us)

    /** Records that the next emitted entry begins a new protocol frame. */
    fun beginFrame(): TimingBuilder {
        frameStarts += if (values.isNotEmpty() && lastIsMark) values.size - 1 else values.size
        return this
    }

    /** Pulse-distance encoding: every bit is a fixed mark followed by a bit dependent space. */
    fun pulseDistanceBits(
        bits: Iterable<Boolean>,
        bitMark: Int,
        oneSpace: Int,
        zeroSpace: Int,
    ): TimingBuilder {
        for (bit in bits) {
            mark(bitMark)
            space(if (bit) oneSpace else zeroSpace)
        }
        return this
    }

    private fun add(isMark: Boolean, us: Int): TimingBuilder {
        require(us >= 0) { "Negative duration $us" }
        if (us == 0) return this
        if (values.isEmpty()) {
            // A waveform cannot start with silence; leading spaces carry no information.
            if (!isMark) return this
            values += us
            lastIsMark = true
            return this
        }
        if (isMark == lastIsMark) {
            values[values.size - 1] = values[values.size - 1] + us
        } else {
            values += us
            lastIsMark = isMark
        }
        return this
    }

    /** Builds the pattern, dropping a trailing space (silence after the last mark is not sent). */
    fun build(carrierHz: Int): IrPattern {
        val out = if (values.isNotEmpty() && !lastIsMark) values.subList(0, values.size - 1) else values
        val starts = if (frameStarts.isEmpty()) listOf(0) else frameStarts.filter { it < out.size }.distinct()
        return IrPattern(carrierHz, out.toIntArray(), starts)
    }
}

/** Bit helpers shared by the protocol encoders. */
object Bits {
    fun lsbFirst(bytes: IntArray): List<Boolean> = bytes.flatMap { b -> (0 until 8).map { (b shr it) and 1 == 1 } }

    fun lsbFirst(value: Long, count: Int): List<Boolean> = (0 until count).map { (value shr it) and 1L == 1L }

    fun msbFirst(value: Long, count: Int): List<Boolean> = (count - 1 downTo 0).map { (value shr it) and 1L == 1L }

    fun hex(bytes: IntArray): String = bytes.joinToString(" ") { "%02X".format(it and 0xFF) }
}
