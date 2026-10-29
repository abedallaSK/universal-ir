package com.universalir.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.universalir.BuildConfig
import com.universalir.R
import com.universalir.core.ui.AppCard
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.SectionHeader
import com.universalir.core.ui.VerificationBadge
import com.universalir.ir.protocol.ProtocolRegistry

private data class Notice(val name: String, val url: String, val license: String, val use: Int)

private val notices = listOf(
    Notice("IRremoteESP8266 — David Conran et al.", "https://github.com/crankyoldgit/IRremoteESP8266", "LGPL-2.1", R.string.about_use_esp),
    Notice("kelon168-ir-homeassistant — Aikidyst", "https://github.com/Aikidyst/kelon168-ir-homeassistant", "MIT", R.string.about_use_kelon),
    Notice("IRTadiran — Arik Felzenstien", "https://github.com/arikfe/IRTadiran", "MIT", R.string.about_use_tadiran),
    Notice("Electra-AC-Remote — nryhbhue", "https://github.com/nryhbhue/Electra-AC-Remote", "Unlicense", R.string.about_use_electra34),
    Notice("electra-central-ac-ir-codes — Daniel Rosehill", "https://github.com/danielrosehill/electra-central-ac-ir-codes", "MIT", R.string.about_use_central),
    Notice("SmartIR — Vassilis Panos (zephyrx47 fork)", "https://github.com/zephyrx47/SmartIR", "MIT", R.string.about_use_smartir),
    Notice("Arduino-IRremote", "https://github.com/Arduino-IRremote/Arduino-IRremote", "MIT", R.string.about_use_reference),
    Notice("Flipper-IRDB", "https://github.com/Lucaslhm/Flipper-IRDB", "CC0-1.0 (newer commits)", R.string.about_use_flipper),
    Notice("IRDB — Simon Peter and contributors", "https://github.com/probonopd/irdb", "irdb license", R.string.about_use_irdb),
    Notice("AndroidX, Jetpack Compose, Material Components, Hilt, Room, Kotlin", "https://developer.android.com/jetpack", "Apache-2.0", R.string.about_use_android),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    AppScaffold(stringResource(R.string.nav_about), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    LtrText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { uri.openUri(com.universalir.data.repository.UpdateChecker.DEVELOPER_URL) }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.about_made_by) + " " + com.universalir.feature.settings.DEVELOPER_NAME, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.about_protocols)) }
            items(ProtocolRegistry.all, key = { it.info.id }) { p ->
                AppCard(Modifier.fillMaxWidth()) {
                    Text(p.info.displayName, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    VerificationBadge(p.info.verification)
                }
            }
            item { SectionHeader(stringResource(R.string.about_limitations)) }
            item {
                AppCard(Modifier.fillMaxWidth()) { Text(stringResource(R.string.about_limitations_body), style = MaterialTheme.typography.bodySmall) }
            }
            item { SectionHeader(stringResource(R.string.about_notices)) }
            items(notices) { n ->
                AppCard(Modifier.fillMaxWidth()) {
                    Text(n.name, style = MaterialTheme.typography.titleSmall)
                    LtrText(n.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(n.use), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { uri.openUri(n.url) }) { LtrText(n.url, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.about_report), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.about_report_body), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
