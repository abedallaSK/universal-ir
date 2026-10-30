package com.universalir.feature.devicebrowser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.universalir.R
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.AppIcons
import com.universalir.core.ui.describeOutcome
import com.universalir.domain.usecase.SendOutcome
import com.universalir.ir.format.CommandNames

/**
 * Shared "send a test, then say whether it worked" panel used by the code wizard and Test before adding.
 * [keys] are (command, label) pairs the user can switch between; empty for AC tests.
 */
@Composable
fun TestPanel(
    keyLabel: String?, keys: List<Pair<String, String>>, selectedKey: String?, onPickKey: (String) -> Unit,
    canSend: Boolean, sending: Boolean, awaiting: Boolean, outcome: SendOutcome?,
    onSend: () -> Unit, onYes: () -> Unit, onNo: () -> Unit, yesLabel: String, noLabel: String,
) {
    val context = LocalContext.current
    val status = LocalStatusColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Big send button; a ring pulses while the code goes out.
        val pulse = rememberInfiniteTransition(label = "send").animateFloat(1f, 1.18f,
            infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "ring")
        val sendLabel = stringResource(R.string.wizard_send_test)
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            if (sending) Box(Modifier.size(112.dp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }
                .clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
            Box(
                Modifier.size(104.dp).clip(CircleShape)
                    .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = canSend && !sending, role = Role.Button, onClick = onSend)
                    .semantics { contentDescription = sendLabel },
                contentAlignment = Alignment.Center,
            ) {
                val power = selectedKey == null || selectedKey.startsWith(CommandNames.POWER)
                Icon(if (power) AppIcons.Power else AppIcons.Remote, null, Modifier.size(44.dp),
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        keyLabel?.let { Text(it, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
        Text(stringResource(R.string.test_point), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))

        if (keys.size > 1) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.test_other_key), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.forEach { (cmd, label) ->
                    FilterChip(selected = cmd == selectedKey, onClick = { onPickKey(cmd) }, label = { Text(label) })
                }
            }
        }

        if (outcome != null && outcome !is SendOutcome.Success) {
            Spacer(Modifier.height(10.dp))
            Text(describeOutcome(context, outcome), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }

        AnimatedVisibility(awaiting, enter = fadeIn() + expandVertically()) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.wizard_question), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onYes, Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = status.success, contentColor = androidx.compose.ui.graphics.Color.White)) {
                        Icon(AppIcons.Ok, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(yesLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onNo, Modifier.fillMaxWidth().height(54.dp)) {
                        Icon(AppIcons.Next, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(noLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    TextButton(onClick = onSend, enabled = !sending, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(AppIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.test_again))
                    }
                }
            }
        }
    }
}

/** Keys worth offering as a test, in order, limited to what the set has. */
fun testKeyChoices(names: Collection<String>, labelOf: (String) -> String): List<Pair<String, String>> =
    listOf(CommandNames.POWER, CommandNames.POWER_ON, CommandNames.MUTE, CommandNames.VOL_UP, CommandNames.CH_UP, CommandNames.HOME, CommandNames.OK)
        .filter { it in names }.take(5).map { it to labelOf(it) }
