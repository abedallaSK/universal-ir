package com.universalir.domain.model

import com.universalir.ir.format.CanonicalCodeSet
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.VerificationLevel
import com.universalir.ir.protocol.ac.AcMode
import com.universalir.ir.protocol.ac.AcProtocol
import com.universalir.ir.protocol.ac.AcState
import com.universalir.ir.protocol.ac.FanSpeed
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

/** Per-device user customisation, stored as JSON in SavedDeviceEntity.customSettings. */
@Serializable
data class DeviceSettings(
    val buttonOrder: List<String> = emptyList(),
    val hiddenButtons: Set<String> = emptySet(),
    val acState: AcState? = null,
    /** Last state actually transmitted successfully; used by toggle-power protocols. */
    val lastSentAcState: AcState? = null,
    val customButtons: List<CustomButton> = emptyList(),
    /** Key into deviceIconChoices; null means the category default. */
    val icon: String? = null,
    /** Index into deviceGradients; null means the category default. */
    val color: Int? = null,
    val showOnHome: Boolean = true,
    val tileSize: TileSize = TileSize.MEDIUM,
    /** Column count of the custom button grid (3..5). */
    val layoutColumns: Int = 4,
    /** Button style for this remote; null follows the app-wide default. */
    val remoteStyle: RemoteStyle? = null,
    /** AC off-timer due time (epoch ms); null when no timer is set. */
    val acOffAt: Long? = null,
    /** Show the D-pad as a swipe touchpad. */
    val touchpad: Boolean = false,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(text: String?): DeviceSettings? =
            if (text.isNullOrBlank()) DeviceSettings()
            else runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** Home tile size: SMALL/MEDIUM take one grid column, WIDE/LARGE take two. */
@Serializable
enum class TileSize { SMALL, MEDIUM, WIDE, LARGE }

@Serializable
data class CustomButton(
    val id: String,
    val label: String,
    val icon: String,
    /** Command name inside the device's code set. */
    val commandName: String,
    /** Grid cell of the top-left corner; -1 = not placed yet (packed in order when drawn). */
    val row: Int = -1,
    val col: Int = -1,
    /** Size in grid cells (1..2 each way). */
    val w: Int = 1,
    val h: Int = 1,
    /** Index into keyColors; null = neutral key. */
    val color: Int? = null,
    val shape: KeyShape = KeyShape.ROUND,
    val showLabel: Boolean = false,
    /** Exact ARGB colour (LED colour keys); wins over [color]. */
    val rgb: Long? = null,
)

@Serializable
enum class KeyShape { ROUND, SQUARE, PILL }

/** Visual style of remote buttons: shapes, fills, D-pad and rocker look. */
@Serializable
enum class RemoteStyle { CLASSIC, MODERN, MINIMAL, COMPACT, VIVID }

/** A code set ready to drive a remote screen. */
sealed interface LoadedCodeSet {
    val id: String
    val title: String

    data class AcProtocolSet(override val id: String, override val title: String, val protocol: AcProtocol) : LoadedCodeSet

    /** Captured AC codes keyed by "off" or "mode/fan/temp". */
    data class AcTableSet(override val id: String, override val title: String, val codes: CanonicalCodeSet) : LoadedCodeSet {
        val table = AcTable(codes.commands.map { it.name }.toSet())
    }

    data class CommandSet(override val id: String, override val title: String, val codes: CanonicalCodeSet) : LoadedCodeSet {
        val commandNames: Set<String> = codes.commands.map { it.name }.toSet()
    }
}

/** Describes what a captured AC lookup table can express. */
class AcTable(keys: Set<String>) {
    private val entries: List<Triple<AcMode, FanSpeed, Int>> = keys.mapNotNull { parse(it) }
    val hasOff: Boolean = "off" in keys
    val modes: Set<AcMode> = entries.map { it.first }.toSet()
    fun fans(mode: AcMode): Set<FanSpeed> = entries.filter { it.first == mode }.map { it.second }.toSet()
    fun temps(mode: AcMode, fan: FanSpeed): List<Int> = entries.filter { it.first == mode && it.second == fan }.map { it.third }.sorted()

    companion object {
        fun key(state: AcState): String =
            if (!state.power) "off"
            else "${modeName(state.mode)}/${fanName(state.fanSpeed)}/${state.temperatureCelsius}"

        fun modeName(m: AcMode) = when (m) {
            AcMode.AUTO -> "auto"; AcMode.COOL -> "cool"; AcMode.HEAT -> "heat"; AcMode.DRY -> "dry"; AcMode.FAN_ONLY -> "fan_only"
        }

        fun fanName(f: FanSpeed) = when (f) {
            FanSpeed.AUTO -> "auto"; FanSpeed.LOW -> "low"; FanSpeed.MEDIUM -> "medium"; FanSpeed.HIGH -> "high"
        }

        fun parse(key: String): Triple<AcMode, FanSpeed, Int>? {
            val p = key.split('/')
            if (p.size != 3) return null
            val mode = when (p[0]) { "auto" -> AcMode.AUTO; "cool" -> AcMode.COOL; "heat" -> AcMode.HEAT; "dry" -> AcMode.DRY; "fan_only" -> AcMode.FAN_ONLY; else -> return null }
            val fan = when (p[1]) { "auto" -> FanSpeed.AUTO; "low" -> FanSpeed.LOW; "medium" -> FanSpeed.MEDIUM; "high" -> FanSpeed.HIGH; else -> return null }
            return Triple(mode, fan, p[2].toIntOrNull() ?: return null)
        }
    }
}

data class CodeSetSummary(
    val codeSetId: String,
    val title: String,
    val brandName: String,
    val modelNumber: String,
    val modelId: String,
    val category: DeviceCategory,
    val protocolId: String,
    val payloadKind: String,
    val origin: String,
    val commandCount: Int,
    val remoteModel: String?,
    val sourceRepository: String,
    val sourceUrl: String,
    val license: String,
    val verification: VerificationLevel,
    val evidence: String?,
)

object TextNormalizer {
    /** Lower-cases, strips accents and punctuation; keeps letters of every script and digits. */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        return buildString {
            for (c in decomposed) {
                if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
                if (Character.isLetterOrDigit(c)) append(c)
            }
        }
    }
}

fun categoryOf(name: String?): DeviceCategory =
    DeviceCategory.entries.firstOrNull { it.name == name } ?: DeviceCategory.OTHER

fun verificationOf(name: String?): VerificationLevel =
    VerificationLevel.entries.firstOrNull { it.name == name } ?: VerificationLevel.SOURCE_DISCOVERED

/** Licenses that permit redistribution / export of the code data. */
object LicensePolicy {
    private val exportable = setOf("MIT", "CC0-1.0", "Unlicense", "Apache-2.0", "BSD-3-Clause", "USER", "LGPL-2.1", "GPL-2.0")
    fun allowsExport(license: String) = exportable.any { license.startsWith(it) }
}
