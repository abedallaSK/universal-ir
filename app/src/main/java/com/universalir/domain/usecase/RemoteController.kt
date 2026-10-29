package com.universalir.domain.usecase

import com.universalir.core.ir.IrTransmitter
import com.universalir.core.ir.TransmitResult
import com.universalir.domain.model.AcTable
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.ir.format.CanonicalRenderer
import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.ac.AcState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SendOutcome {
    data class Success(val summary: String, val payloads: List<String>, val result: TransmitResult.Sent) : SendOutcome
    data class EncodeError(val issues: List<String>) : SendOutcome
    data class Transmit(val result: TransmitResult) : SendOutcome
}

/** Last attempt, surfaced by the protocol-diagnostics page. */
data class TransmissionEvent(
    val timestamp: Long,
    val codeSetTitle: String,
    val command: String,
    val encode: EncodeResult,
    val transmit: TransmitResult?,
)

/**
 * The only path from UI intent to the IR LED:
 * UI state → encoder (protocol or captured table) → validation → transmitter → result.
 */
@Singleton
class RemoteController @Inject constructor(
    private val transmitter: IrTransmitter,
    private val settings: com.universalir.data.repository.SettingsRepository? = null,
) {

    private val _last = MutableStateFlow<TransmissionEvent?>(null)
    val lastEvent: StateFlow<TransmissionEvent?> = _last.asStateFlow()

    fun encodeCommand(set: LoadedCodeSet, commandName: String): EncodeResult = when (set) {
        is LoadedCodeSet.CommandSet -> set.codes.commands.firstOrNull { it.name == commandName }
            ?.let { CanonicalRenderer.render(it) }
            ?: EncodeResult.Failure(listOf("This code set has no '$commandName' command."))
        is LoadedCodeSet.AcTableSet -> set.codes.commands.firstOrNull { it.name == commandName }
            ?.let { CanonicalRenderer.render(it) }
            ?: EncodeResult.Failure(listOf("This captured code set has no code for '$commandName'."))
        is LoadedCodeSet.AcProtocolSet -> EncodeResult.Failure(listOf("Use the AC state controls for protocol-based code sets."))
    }

    fun encodeAc(set: LoadedCodeSet, state: AcState, previous: AcState?): EncodeResult = when (set) {
        is LoadedCodeSet.AcProtocolSet -> set.protocol.encodeCompleteState(state, previous)
        is LoadedCodeSet.AcTableSet -> encodeCommand(set, AcTable.key(state))
        is LoadedCodeSet.CommandSet -> EncodeResult.Failure(listOf("This code set is not an air-conditioner code set."))
    }

    /**
     * Sends a button command, repeated per the "IR repeat" setting for devices that miss single
     * presses. Stops at the first failure. AC states are never repeated (some power codes toggle).
     */
    suspend fun sendCommand(set: LoadedCodeSet, commandName: String): SendOutcome {
        val times = (settings?.current()?.irRepeat ?: 1).coerceIn(1, 5)
        val encoded = encodeCommand(set, commandName)
        var outcome = send(set.title, commandName, encoded)
        repeat(times - 1) {
            if (outcome !is SendOutcome.Success) return outcome
            kotlinx.coroutines.delay(REPEAT_GAP_MS)
            outcome = send(set.title, commandName, encoded)
        }
        return outcome
    }

    suspend fun sendAc(set: LoadedCodeSet, state: AcState, previous: AcState?): SendOutcome =
        send(set.title, AcTable.key(state), encodeAc(set, state, previous))

    suspend fun sendAcExtra(set: LoadedCodeSet.AcProtocolSet, commandId: String, state: AcState): SendOutcome =
        send(set.title, commandId, set.protocol.encodeExtra(commandId, state))

    suspend fun send(title: String, command: String, encoded: EncodeResult): SendOutcome {
        val now = System.currentTimeMillis()
        return when (encoded) {
            is EncodeResult.Failure -> {
                _last.value = TransmissionEvent(now, title, command, encoded, null)
                SendOutcome.EncodeError(encoded.issues)
            }
            is EncodeResult.Success -> {
                val result = transmitter.transmit(encoded.transmission)
                _last.value = TransmissionEvent(now, title, command, encoded, result)
                if (result is TransmitResult.Sent) SendOutcome.Success(encoded.summary, encoded.payloads, result)
                else SendOutcome.Transmit(result)
            }
        }
    }
}

private const val REPEAT_GAP_MS = 90L
