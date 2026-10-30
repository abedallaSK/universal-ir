package com.universalir.data.wifi

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wifiDataStore by preferencesDataStore(name = "wifi_devices")

/** The secret part of a [SavedWifiDevice], stored only as an AES-GCM blob ([SavedWifiDevice.sealedSecrets]). */
@Serializable
private data class WifiSecrets(
    val token: String? = null,
    val clientKey: String? = null,
    val greeKey: String? = null,
    val apiKey: String? = null,
    val cloudToken: String? = null,
    val cloudImei: String? = null,
) {
    val isEmpty get() = token == null && clientKey == null && greeKey == null && apiKey == null && cloudToken == null && cloudImei == null
}

/** This app's Android TV client identity (certificate + PKCS#8 private key, base64), stored sealed. */
@Serializable
data class AtvIdentityData(val certDer: String, val keyPkcs8: String)

/**
 * Saved Wi-Fi devices, kept as one JSON list in a dedicated DataStore. Pairing tokens, account tokens and API keys are
 * encrypted at rest with a KeyStore AES key ([SecretVault]); everything else (names, IPs) is plain.
 */
@Singleton
class WifiDeviceStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val key = stringPreferencesKey("devices_json")
    private val atvKey = stringPreferencesKey("atv_identity")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(SavedWifiDevice.serializer())

    val devices: Flow<List<SavedWifiDevice>> = context.wifiDataStore.data.map { decode(it[key]) }

    suspend fun device(id: String): SavedWifiDevice? = devices.first().firstOrNull { it.id == id }

    /**
     * Inserts or refreshes a device found by discovery; existing name and secrets are kept. A device that is
     * already saved under another id (same MAC, or same IP and protocol) is updated instead of added twice.
     * Returns the id the device is stored under.
     */
    suspend fun upsert(d: SavedWifiDevice): String {
        var storedId = d.id
        mutate { list ->
            val old = list.firstOrNull { sameDevice(it, d) }
            if (old == null) list + d
            else {
                storedId = old.id
                list.map { if (it.id == old.id) old.copy(ip = d.ip, port = d.port, mac = d.mac ?: old.mac, model = d.model ?: old.model, manufacturer = d.manufacturer ?: old.manufacturer) else it }
            }
        }
        return storedId
    }

    /**
     * Adds account devices (or devices added by IP). Existing entries keep their user-chosen name but get the new
     * address, ids and credentials.
     */
    suspend fun putAll(devices: List<SavedWifiDevice>) = mutate { list ->
        val updated = list.map { old ->
            val n = devices.firstOrNull { sameDevice(old, it) } ?: return@map old
            n.copy(id = old.id, name = old.name, addedAt = old.addedAt, paired = old.paired || n.paired)
        }
        updated + devices.filter { d -> list.none { sameDevice(it, d) } }
    }

    suspend fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    suspend fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun update(id: String, change: (SavedWifiDevice) -> SavedWifiDevice) = mutate { list -> list.map { if (it.id == id) change(it) else it } }

    // ---------------------------------------------------------------- Android TV identity

    suspend fun atvIdentity(): AtvIdentityData? {
        val raw = context.wifiDataStore.data.first()[atvKey] ?: return null
        val plain = SecretVault.open(raw) ?: return null
        return runCatching { json.decodeFromString(AtvIdentityData.serializer(), plain) }.getOrNull()
    }

    /** Throws if the KeyStore can't encrypt; the caller then keeps the identity in memory only. */
    suspend fun saveAtvIdentity(id: AtvIdentityData) {
        val blob = SecretVault.seal(json.encodeToString(AtvIdentityData.serializer(), id))
        context.wifiDataStore.edit { it[atvKey] = blob }
    }

    // ---------------------------------------------------------------- encoding

    private suspend fun mutate(change: (List<SavedWifiDevice>) -> List<SavedWifiDevice>) {
        context.wifiDataStore.edit { prefs -> prefs[key] = encode(change(decode(prefs[key]))) }
    }

    private fun encode(list: List<SavedWifiDevice>): String = json.encodeToString(serializer, list.map(::seal))

    private fun decode(raw: String?): List<SavedWifiDevice> = mergeDuplicates(decodeRaw(raw))

    private fun decodeRaw(raw: String?): List<SavedWifiDevice> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList()).map(::unseal)

    private fun seal(d: SavedWifiDevice): SavedWifiDevice {
        val s = WifiSecrets(d.token, d.clientKey, d.greeKey, d.apiKey, d.cloudToken, d.cloudImei)
        val stripped = d.copy(token = null, clientKey = null, greeKey = null, apiKey = null, cloudToken = null, cloudImei = null, sealedSecrets = null)
        if (s.isEmpty) return stripped
        return try {
            stripped.copy(sealedSecrets = SecretVault.seal(json.encodeToString(WifiSecrets.serializer(), s)))
        } catch (e: Exception) {
            // KeyStore unusable: never write account credentials in plain text. LAN pairing keys (which older versions
            // stored plainly and which only work on this network) are kept so the TV/AC doesn't need re-pairing.
            stripped.copy(token = d.token, clientKey = d.clientKey, greeKey = d.greeKey)
        }
    }

    private fun unseal(d: SavedWifiDevice): SavedWifiDevice {
        val blob = d.sealedSecrets ?: return d // legacy plain fields; sealed on the next write
        val s = SecretVault.open(blob)?.let { runCatching { json.decodeFromString(WifiSecrets.serializer(), it) }.getOrNull() }
            ?: return d.copy(sealedSecrets = null)
        return d.copy(
            token = d.token ?: s.token, clientKey = d.clientKey ?: s.clientKey, greeKey = d.greeKey ?: s.greeKey,
            apiKey = d.apiKey ?: s.apiKey, cloudToken = d.cloudToken ?: s.cloudToken, cloudImei = d.cloudImei ?: s.cloudImei,
            sealedSecrets = null,
        )
    }
}

/**
 * Whether two saved entries are the same physical device: same id, same MAC, or (for LAN devices)
 * the same IP and protocol. Cloud devices only match by id.
 */
internal fun sameDevice(a: SavedWifiDevice, b: SavedWifiDevice): Boolean = when {
    a.id == b.id -> true
    a.protocol.cloud || b.protocol.cloud -> false
    !a.mac.isNullOrBlank() && a.mac.equals(b.mac, ignoreCase = true) -> true
    else -> a.ip.isNotBlank() && a.ip == b.ip && a.protocol == b.protocol
}

/** Collapses duplicates already saved by older versions: the first entry wins, blanks are filled from later ones. */
internal fun mergeDuplicates(list: List<SavedWifiDevice>): List<SavedWifiDevice> {
    val out = mutableListOf<SavedWifiDevice>()
    for (d in list) {
        val i = out.indexOfFirst { sameDevice(it, d) }
        if (i < 0) out += d
        else {
            val o = out[i]
            out[i] = o.copy(mac = o.mac ?: d.mac, model = o.model ?: d.model, manufacturer = o.manufacturer ?: d.manufacturer,
                token = o.token ?: d.token, paired = o.paired || d.paired)
        }
    }
    return out
}
