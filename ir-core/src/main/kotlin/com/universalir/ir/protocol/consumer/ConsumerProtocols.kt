package com.universalir.ir.protocol.consumer

import com.universalir.ir.core.Bits
import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.IrTransmission
import com.universalir.ir.core.TimingBuilder
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.IrProtocol
import com.universalir.ir.protocol.ProtocolInfo
import com.universalir.ir.protocol.SourceRef
import com.universalir.ir.protocol.VerificationLevel

/**
 * Discrete-command protocols used by TVs, set-top boxes, audio gear, projectors and fans.
 * Address/command semantics follow the Flipper Zero `.ir` "parsed" convention so imported files
 * map 1:1; IRDB's (device, subdevice, function) is converted in the IRDB importer.
 */
interface ConsumerProtocol : IrProtocol {
    val addressBits: Int
    val commandBits: Int
    fun encode(address: Long, command: Long): EncodeResult

    fun checkRange(address: Long, command: Long): List<String> {
        val issues = mutableListOf<String>()
        if (address < 0 || address >= (1L shl addressBits)) issues += "Address 0x${address.toString(16)} exceeds $addressBits bits."
        if (command < 0 || command >= (1L shl commandBits)) issues += "Command 0x${command.toString(16)} exceeds $commandBits bits."
        return issues
    }
}

private val consumerCategories = setOf(
    DeviceCategory.TELEVISION, DeviceCategory.TV_BOX, DeviceCategory.AUDIO,
    DeviceCategory.PROJECTOR, DeviceCategory.FAN, DeviceCategory.LIGHT, DeviceCategory.OTHER,
)

private val arduinoIrRemote = SourceRef("Arduino-IRremote", "https://github.com/Arduino-IRremote/Arduino-IRremote", "MIT", "Protocol timing reference")
private val irEsp = SourceRef("IRremoteESP8266", "https://github.com/crankyoldgit/IRremoteESP8266", "LGPL-2.1", "Protocol timing reference")

private fun consumerInfo(id: String, name: String, carrier: Int, frame: String, evidence: String) = ProtocolInfo(
    id = id, displayName = name, deviceCategories = consumerCategories, carrierHz = carrier,
    frameDescription = frame, completeState = false, sources = listOf(arduinoIrRemote, irEsp),
    verification = VerificationLevel.WAVEFORM_VALIDATED, evidence = listOf(evidence),
)

private fun success(pattern: IrPattern, payload: String, summary: String) =
    EncodeResult.Success(IrTransmission.single(pattern, summary), listOf(payload), summary)

/** NEC family: 9000/4500 header, 560 us marks, LSB first, 32 data bits. */
abstract class NecLike(
    final override val addressBits: Int,
    final override val commandBits: Int,
    private val hdrMark: Int,
    private val hdrSpace: Int,
) : ConsumerProtocol {
    protected abstract fun dataBits(address: Long, command: Long): Long

    override fun encode(address: Long, command: Long): EncodeResult {
        val issues = checkRange(address, command)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues)
        val data = dataBits(address, command)
        val b = TimingBuilder().beginFrame().mark(hdrMark).space(hdrSpace)
        b.pulseDistanceBits(Bits.lsbFirst(data, 32), 560, 1690, 560)
        b.mark(560)
        return success(b.build(38_000), "%08X".format(data), "${info.id} a=0x${address.toString(16)} c=0x${command.toString(16)}")
    }
}

/** NEC: 8-bit address and command, each followed by its inverse. */
object NecProtocol : NecLike(8, 8, 9000, 4500) {
    override val info = consumerInfo("NEC", "NEC", 38_000, "9000/4500, addr, ~addr, cmd, ~cmd, LSB first", "Matches NEC reference timings (Arduino-IRremote / IRremoteESP8266 sendNEC).")
    override fun dataBits(address: Long, command: Long) =
        address or ((address xor 0xFF) shl 8) or (command shl 16) or ((command xor 0xFF) shl 24)
}

/** NEC extended: 16-bit address; 16-bit command carried verbatim (Flipper stores cmd and ~cmd). */
object NecExtProtocol : NecLike(16, 16, 9000, 4500) {
    override val info = consumerInfo("NECext", "NEC extended", 38_000, "9000/4500, 16-bit address, 16-bit command, LSB first", "Same timings as NEC.")
    override fun dataBits(address: Long, command: Long) = address or (command shl 16)
}

/** Samsung32: 4500/4500 header, address sent twice, command and its inverse. */
object Samsung32Protocol : NecLike(8, 8, 4500, 4500) {
    override val info = consumerInfo("Samsung32", "Samsung 32-bit", 38_000, "4500/4500, addr, addr, cmd, ~cmd, LSB first", "Matches IRremoteESP8266 sendSAMSUNG timing.")
    override fun dataBits(address: Long, command: Long) =
        address or (address shl 8) or (command shl 16) or ((command xor 0xFF) shl 24)
}

/** Sony SIRC 12/15/20: 40 kHz, 2400/600 header, LSB first, frame repeated 3× every 45 ms. */
class SircProtocol(private val variant: Int) : ConsumerProtocol {
    override val addressBits = when (variant) { 12 -> 5; 15 -> 8; else -> 13 }
    override val commandBits = 7
    override val info = consumerInfo(
        if (variant == 12) "SIRC" else "SIRC$variant", "Sony SIRC $variant-bit", 40_000,
        "2400/600 header, 1 = 1200 mark, 0 = 600 mark, 600 space, 7-bit command then address, 3 frames / 45 ms",
        "Matches Sony SIRC reference timings.",
    )

    override fun encode(address: Long, command: Long): EncodeResult {
        val issues = checkRange(address, command)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues)
        val b = TimingBuilder()
        repeat(3) { r ->
            b.beginFrame()
            var frameUs = 0
            fun mark(us: Int) { b.mark(us); frameUs += us }
            fun space(us: Int) { b.space(us); frameUs += us }
            mark(2400); space(600)
            val bits = Bits.lsbFirst(command, 7) + Bits.lsbFirst(address, addressBits)
            bits.forEachIndexed { i, bit ->
                mark(if (bit) 1200 else 600)
                if (i < bits.lastIndex) space(600)
            }
            if (r < 2) space(45_000 - frameUs)
        }
        return success(b.build(40_000), "cmd=%02X addr=%X".format(command, address), "${info.id} a=0x${address.toString(16)} c=0x${command.toString(16)}")
    }
}

/** Philips RC5 / RC5X: 36 kHz, Manchester, 889 us half bits, 1 = space→mark. */
class Rc5Protocol(private val extended: Boolean) : ConsumerProtocol {
    override val addressBits = 5
    override val commandBits = if (extended) 7 else 6
    override val info = consumerInfo(
        if (extended) "RC5X" else "RC5", if (extended) "Philips RC5X" else "Philips RC5", 36_000,
        "2 start bits, toggle, 5 address, 6 command bits, Manchester 889 us", "Matches RC5 reference timings.",
    )

    override fun encode(address: Long, command: Long): EncodeResult {
        val issues = checkRange(address, command)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues)
        val field = if (command >= 64) 0 else 1
        val bits = listOf(true, field == 1, false) + Bits.msbFirst(address, 5) + Bits.msbFirst(command and 0x3F, 6)
        val b = TimingBuilder().beginFrame()
        for (bit in bits) if (bit) b.space(889).mark(889) else b.mark(889).space(889)
        return success(b.build(36_000), bits.joinToString("") { if (it) "1" else "0" }, "${info.id} a=$address c=$command")
    }
}

/** Philips RC6 mode 0: 36 kHz, 444 us unit, leader 2666/889, 1 = mark→space. */
object Rc6Protocol : ConsumerProtocol {
    override val addressBits = 8
    override val commandBits = 8
    override val info = consumerInfo("RC6", "Philips RC6 mode 0", 36_000, "Leader 2666/889, start 1, mode 000, double-width toggle, 8 address, 8 command", "Matches RC6 reference timings.")

    override fun encode(address: Long, command: Long): EncodeResult {
        val issues = checkRange(address, command)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues)
        val b = TimingBuilder().beginFrame().mark(2666).space(889)
        fun bit(v: Boolean, unit: Int) { if (v) b.mark(unit).space(unit) else b.space(unit).mark(unit) }
        bit(true, 444)
        repeat(3) { bit(false, 444) }
        bit(false, 889)
        (Bits.msbFirst(address, 8) + Bits.msbFirst(command, 8)).forEach { bit(it, 444) }
        return success(b.build(36_000), "a=%02X c=%02X".format(address, command), "RC6 a=$address c=$command")
    }
}

object ConsumerProtocols {
    val all: List<ConsumerProtocol> = listOf(
        NecProtocol, NecExtProtocol, Samsung32Protocol,
        SircProtocol(12), SircProtocol(15), SircProtocol(20),
        Rc5Protocol(false), Rc5Protocol(true), Rc6Protocol,
    )

    fun byId(id: String): ConsumerProtocol? = all.firstOrNull { it.info.id.equals(id, ignoreCase = true) }
}
