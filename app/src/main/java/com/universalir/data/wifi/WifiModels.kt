package com.universalir.data.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** [AC_GREE] is kept for saved data from older versions; every other air conditioner uses [AC]. */
enum class WifiDeviceKind { TV_SAMSUNG, TV_LG, TV_ROKU, TV_ANDROID, CAST, AC_GREE, AC, OTHER;
    val isTv get() = this == TV_SAMSUNG || this == TV_LG || this == TV_ROKU || this == TV_ANDROID
    val isAc get() = this == AC_GREE || this == AC
}

/**
 * How we talk to a device. [controllable] is false for things we can only list (Cast, HomeKit…).
 * [cloud] protocols go through the maker's service with the user's own account instead of the LAN (no IP).
 */
enum class WifiProtocol(val controllable: Boolean, val priority: Int, val cloud: Boolean = false) {
    SAMSUNG_TIZEN(true, 5), LG_WEBOS(true, 5), ROKU_ECP(true, 5), ANDROID_TV(true, 5), GREE(true, 5), DAIKIN(true, 5),
    ELECTRA(true, 5, cloud = true), SENSIBO(true, 5, cloud = true), NONE(false, 0);

    val isAc get() = this == GREE || this == DAIKIN || this == ELECTRA || this == SENSIBO
}

enum class DiscoverySource { SSDP, MDNS, GREE, DAIKIN }

data class DiscoveredDevice(
    val id: String,
    val ip: String,
    val name: String,
    val kind: WifiDeviceKind,
    val protocol: WifiProtocol,
    val port: Int = 0,
    val manufacturer: String? = null,
    val model: String? = null,
    val mac: String? = null,
    val sources: Set<DiscoverySource> = emptySet(),
) {
    /** Combines two sightings of the same IP: the controllable one wins, blanks are filled from the other. */
    fun mergeWith(o: DiscoveredDevice): DiscoveredDevice {
        val (a, b) = if (o.protocol.priority > protocol.priority) o to this else this to o
        return a.copy(
            name = if (a.name.isBlank() || a.name == a.ip) b.name else a.name,
            manufacturer = a.manufacturer ?: b.manufacturer,
            model = a.model ?: b.model,
            mac = a.mac ?: b.mac,
            kind = if (a.kind == WifiDeviceKind.OTHER) b.kind else a.kind,
            sources = a.sources + b.sources,
        )
    }
}

@Serializable
data class SavedWifiDevice(
    val id: String,
    val name: String,
    val kind: WifiDeviceKind,
    val protocol: WifiProtocol,
    val ip: String,
    val port: Int = 0,
    val mac: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    /** Samsung remote-control token. */
    val token: String? = null,
    /** LG webOS client-key. */
    val clientKey: String? = null,
    /** Gree per-device AES key from the bind handshake. */
    val greeKey: String? = null,
    /** Newer Gree firmware uses AES-GCM instead of ECB. */
    val greeGcm: Boolean = false,
    val addedAt: Long = 0,
    /** Android TV: paired with this app's client certificate (see [AtvKeys]). */
    val paired: Boolean = false,
    /** Cloud ACs: the device id in the maker's service (Electra device id, Sensibo pod id). */
    val remoteId: String? = null,
    /** Cloud ACs: the account the device belongs to (phone number / "Sensibo"), shown to the user; not a secret. */
    val account: String? = null,
    /** Sensibo API key the user created. */
    val apiKey: String? = null,
    /** Electra Smart long-lived token from the SMS login. */
    val cloudToken: String? = null,
    /** Electra Smart client id ("imei") the token was issued to. */
    val cloudImei: String? = null,
    /**
     * At rest, [token], [clientKey], [greeKey], [apiKey], [cloudToken] and [cloudImei] are moved into this AES-GCM blob
     * (key in the Android KeyStore) by [WifiDeviceStore]; in memory the plain fields are filled again.
     */
    val sealedSecrets: String? = null,
)

fun DiscoveredDevice.toSaved(now: Long = System.currentTimeMillis()) =
    SavedWifiDevice(id, name, kind, protocol, ip, port, mac, manufacturer, model, addedAt = now)

enum class TvKey {
    POWER, UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, MENU, INPUT, VOL_UP, VOL_DOWN, MUTE, CH_UP, CH_DOWN, PLAY, PAUSE, REWIND, FAST_FORWARD,
    PLAY_PAUSE, NEXT, PREVIOUS, SETTINGS, INFO, CAPTIONS, GUIDE, RED, GREEN, YELLOW, BLUE,
    NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9;

    companion object {
        val digits = listOf(NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9)
    }
}

/** Apps a TV controller can open directly (Android TV app links). */
enum class TvApp { YOUTUBE, NETFLIX, PRIME_VIDEO, DISNEY_PLUS }

/** What a TV reports about itself; null fields are unknown. */
data class TvStatus(
    val on: Boolean? = null,
    val volume: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean? = null,
    val app: String? = null,
)

/**
 * [AUTH]: wrong pairing code, rejected login or expired account token. [NOT_PAIRED]: the device needs pairing in this app first.
 */
enum class WifiError { TIMEOUT, UNREACHABLE, DENIED, PROTOCOL, UNSUPPORTED, AUTH, NOT_PAIRED }

sealed interface WifiResult<out T> {
    data class Ok<T>(val value: T) : WifiResult<T>
    data class Err(val error: WifiError, val detail: String? = null) : WifiResult<Nothing>
}

val WifiResult<*>.isOk get() = this is WifiResult.Ok

/** Runs a network block, mapping exceptions to [WifiResult.Err]; cancellation is always rethrown. */
inline fun <T> wifiCatching(block: () -> T): WifiResult<T> = try {
    WifiResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    WifiResult.Err(errorOf(e), e.message ?: e.javaClass.simpleName)
}

fun errorOf(e: Throwable): WifiError = when (e) {
    is WifiException -> e.error
    is SocketTimeoutException -> WifiError.TIMEOUT
    is ConnectException, is NoRouteToHostException, is UnknownHostException, is PortUnreachableException -> WifiError.UNREACHABLE
    else -> if (e.cause != null && e.cause !== e) errorOf(e.cause!!) else WifiError.PROTOCOL
}

class WifiException(val error: WifiError, message: String? = null) : Exception(message ?: error.name)

/** Gree AC state as reported by the unit. Mode 0 auto,1 cool,2 dry,3 fan,4 heat; fan 0 auto,1 low,3 medium,5 high. */
data class GreeState(
    val power: Boolean = false,
    val mode: Int = 1,
    val temperature: Int = 24,
    val fan: Int = 0,
    val swing: Boolean = false,
    val fahrenheitDisplay: Boolean = false,
)
