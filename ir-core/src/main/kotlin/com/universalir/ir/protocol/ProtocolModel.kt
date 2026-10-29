package com.universalir.ir.protocol

import com.universalir.ir.core.IrTransmission

enum class DeviceCategory {
    AIR_CONDITIONER, TELEVISION, TV_BOX, AUDIO, PROJECTOR, FAN, LIGHT, OTHER,
}

/**
 * Evidence ladder used everywhere in the app. A level is only assigned when the evidence named
 * in [ProtocolInfo.evidence] exists.
 */
enum class VerificationLevel(val rank: Int) {
    /** A repository mentions the protocol or model. */
    SOURCE_DISCOVERED(0),

    /** The app can generate a waveform following the source implementation. */
    ENCODER_IMPLEMENTED(1),

    /** Generated output matches a reference vector or a real captured remote signal. */
    WAVEFORM_VALIDATED(2),

    /** The waveform controlled a physical device from this app. */
    HARDWARE_TESTED(3),

    /** Power, mode, temperature, fan and relevant options were verified on hardware. */
    FEATURE_TESTED(4),
}

data class SourceRef(
    val name: String,
    val url: String,
    val license: String,
    val note: String = "",
)

data class ProtocolInfo(
    val id: String,
    val displayName: String,
    val deviceCategories: Set<DeviceCategory>,
    val carrierHz: Int,
    /** Plain-language frame description shown in protocol diagnostics. */
    val frameDescription: String,
    val completeState: Boolean,
    val sources: List<SourceRef>,
    val verification: VerificationLevel,
    /** Concrete evidence backing [verification]. */
    val evidence: List<String>,
    val warnings: List<String> = emptyList(),
)

interface IrProtocol {
    val info: ProtocolInfo
}

sealed interface EncodeResult {
    data class Success(
        val transmission: IrTransmission,
        /** Payload per message in hex (protocol bytes or bits), for diagnostics. */
        val payloads: List<String>,
        val summary: String,
    ) : EncodeResult

    data class Failure(val issues: List<String>) : EncodeResult
}
