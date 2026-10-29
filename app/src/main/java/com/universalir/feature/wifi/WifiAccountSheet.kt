package com.universalir.feature.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.core.ui.AppCard
import com.universalir.core.ui.AppIcons
import com.universalir.data.wifi.ElectraApi
import com.universalir.data.wifi.ElectraCodec
import com.universalir.data.wifi.LanMath
import com.universalir.data.wifi.SavedWifiDevice
import com.universalir.data.wifi.SensiboApi
import com.universalir.data.wifi.WifiDeviceKind
import com.universalir.data.wifi.WifiDeviceStore
import com.universalir.data.wifi.WifiError
import com.universalir.data.wifi.WifiProtocol
import com.universalir.data.wifi.WifiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AcAccountBrand { ELECTRA, SENSIBO }

/** Login steps for "Add Wi-Fi AC by account". Credentials are typed by the user and stored encrypted. */
data class AccountUi(
    val brand: AcAccountBrand? = null,
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** Set when devices were saved; the sheet closes and the screen shows a message. */
    val addedCount: Int? = null,
)

@HiltViewModel
class WifiAccountViewModel @Inject constructor(private val store: WifiDeviceStore) : ViewModel() {
    private val _ui = MutableStateFlow(AccountUi())
    val ui = _ui.asStateFlow()
    private var imei: String? = null
    private var phone: String = ""

    fun reset() { imei = null; phone = ""; _ui.value = AccountUi() }
    fun pick(brand: AcAccountBrand?) { imei = null; _ui.value = AccountUi(brand = brand) }

    fun electraSendCode(rawPhone: String) {
        val p = rawPhone.filter { it.isDigit() }
        if (p.length < 9) return _ui.update { it.copy(error = WifiText.electraPhoneRejected) }
        phone = p
        val id = ElectraCodec.newImei().also { imei = it }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            when (val r = ElectraApi.sendOtp(id, p)) {
                is WifiResult.Ok -> _ui.update { it.copy(busy = false, codeSent = true, info = WifiText.electraCodeSent) }
                is WifiResult.Err -> _ui.update { it.copy(busy = false, error = if (r.error == WifiError.AUTH) WifiText.electraPhoneRejected else cloudError(r)) }
            }
        }
    }

    fun electraVerify(code: String) {
        val id = imei ?: return
        val c = code.trim()
        if (c.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            val token = when (val r = ElectraApi.checkOtp(id, phone, c)) {
                is WifiResult.Ok -> r.value
                is WifiResult.Err -> return@launch _ui.update { it.copy(busy = false, error = if (r.error == WifiError.AUTH) WifiText.smsWrongCode else cloudError(r)) }
            }
            when (val r = ElectraApi.devices(token, id)) {
                is WifiResult.Err -> _ui.update { it.copy(busy = false, error = cloudError(r)) }
                is WifiResult.Ok -> {
                    if (r.value.isEmpty()) return@launch _ui.update { it.copy(busy = false, error = WifiText.noAcsInAccount) }
                    val now = System.currentTimeMillis()
                    store.putAll(r.value.map { d ->
                        SavedWifiDevice(
                            id = "electra-${LanMath.safeId(d.id)}", name = d.name, kind = WifiDeviceKind.AC, protocol = WifiProtocol.ELECTRA,
                            ip = "", mac = d.mac, manufacturer = "Electra", model = d.model, addedAt = now,
                            remoteId = d.id, account = phone, cloudToken = token, cloudImei = id,
                        )
                    })
                    _ui.update { it.copy(busy = false, addedCount = r.value.size) }
                }
            }
        }
    }

    fun sensiboConnect(rawKey: String) {
        val key = rawKey.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            when (val r = SensiboApi.pods(key)) {
                is WifiResult.Err -> _ui.update { it.copy(busy = false, error = if (r.error == WifiError.AUTH) WifiText.keyRejected else cloudError(r)) }
                is WifiResult.Ok -> {
                    if (r.value.isEmpty()) return@launch _ui.update { it.copy(busy = false, error = WifiText.noAcsInAccount) }
                    val now = System.currentTimeMillis()
                    store.putAll(r.value.map { p ->
                        SavedWifiDevice(
                            id = "sensibo-${LanMath.safeId(p.id)}", name = p.name, kind = WifiDeviceKind.AC, protocol = WifiProtocol.SENSIBO,
                            ip = "", manufacturer = "Sensibo", addedAt = now, remoteId = p.id, apiKey = key,
                        )
                    })
                    _ui.update { it.copy(busy = false, addedCount = r.value.size) }
                }
            }
        }
    }

    private fun cloudError(e: WifiResult.Err) = when (e.error) {
        WifiError.UNREACHABLE, WifiError.TIMEOUT -> WifiText.cloudUnreachable
        else -> WifiText.error(e.error, e.detail)
    }
}

/** Bottom sheet: pick a brand, then log in with the user's own account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAcAccountSheet(onDismiss: () -> Unit, onAdded: (Int) -> Unit, vm: WifiAccountViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { vm.reset() }
    LaunchedEffect(ui.addedCount) { ui.addedCount?.let { onAdded(it); vm.reset() } }

    ModalBottomSheet(onDismissRequest = { vm.reset(); onDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(WifiText.addByAccount, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            when (ui.brand) {
                null -> BrandPicker(vm::pick)
                AcAccountBrand.ELECTRA -> ElectraForm(ui, vm)
                AcAccountBrand.SENSIBO -> SensiboForm(ui, vm)
            }
        }
    }
}

@Composable
private fun BrandPicker(onPick: (AcAccountBrand) -> Unit) {
    Text(WifiText.chooseBrand, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    BrandRow("Electra Smart", 4) { onPick(AcAccountBrand.ELECTRA) }
    BrandRow("Sensibo", 5) { onPick(AcAccountBrand.SENSIBO) }
    Spacer(Modifier.height(12.dp))
    Text(WifiText.greeNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BrandRow(name: String, gradient: Int, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.universalir.core.ui.DeviceAvatar(com.universalir.core.ui.DeviceLook(AppIcons.AirConditioner, com.universalir.core.ui.deviceGradients[gradient], "ac", gradient), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FormButtons(busy: Boolean, label: String, enabled: Boolean, onBack: () -> Unit, onGo: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, enabled = !busy) { Text(WifiText.back) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onGo, enabled = enabled && !busy) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
            Text(label)
        }
    }
}

@Composable
private fun Messages(ui: AccountUi) {
    ui.info?.takeIf { ui.error == null }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
    ui.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun ElectraForm(ui: AccountUi, vm: WifiAccountViewModel) {
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Text("Electra Smart", style = MaterialTheme.typography.titleMedium)
    Text(WifiText.electraPhoneHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    // Phone numbers and codes are LTR in every locale.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) }, label = { Text(WifiText.phoneNumber) }, singleLine = true,
            enabled = !ui.codeSent && !ui.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        if (ui.codeSent) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(code, { code = it.filter { c -> c.isDigit() }.take(8) }, label = { Text(WifiText.smsCode) }, singleLine = true,
                enabled = !ui.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        }
    }
    Messages(ui)
    if (!ui.codeSent) FormButtons(ui.busy, WifiText.sendCode, phone.count { it.isDigit() } >= 9, onBack = { vm.pick(null) }) { vm.electraSendCode(phone) }
    else FormButtons(ui.busy, WifiText.verify, code.length >= 4, onBack = { code = ""; vm.pick(AcAccountBrand.ELECTRA) }) { vm.electraVerify(code) }
}

@Composable
private fun SensiboForm(ui: AccountUi, vm: WifiAccountViewModel) {
    var key by rememberSaveable { mutableStateOf("") }
    Text("Sensibo", style = MaterialTheme.typography.titleMedium)
    Text(WifiText.sensiboKeyHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(key, { key = it.trim() }, label = { Text(WifiText.apiKey) }, singleLine = true, enabled = !ui.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
    }
    Messages(ui)
    FormButtons(ui.busy, WifiText.connect, key.isNotBlank(), onBack = { vm.pick(null) }) { vm.sensiboConnect(key) }
}
