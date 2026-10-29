package com.universalir.core.ui

import android.content.Context
import com.universalir.R
import com.universalir.core.ir.HardwareStatus
import com.universalir.core.ir.TransmitResult
import com.universalir.domain.usecase.SendOutcome

/** Turns a send outcome into a user-facing sentence. Never reports success unless sent. */
fun describeOutcome(context: Context, outcome: SendOutcome): String = when (outcome) {
    is SendOutcome.Success -> context.getString(R.string.msg_sent, outcome.result.messages, outcome.result.airTimeUs / 1000)
    is SendOutcome.EncodeError -> context.getString(R.string.msg_encode_error, outcome.issues.joinToString(" "))
    is SendOutcome.Transmit -> describeTransmit(context, outcome.result)
}

fun describeTransmit(context: Context, result: TransmitResult): String = when (result) {
    is TransmitResult.Sent -> context.getString(R.string.msg_sent, result.messages, result.airTimeUs / 1000)
    is TransmitResult.HardwareUnavailable -> describeHardware(context, result.status)
    is TransmitResult.InvalidPattern -> context.getString(R.string.msg_invalid_pattern, result.issues.joinToString(" ") { it.message })
    is TransmitResult.TransmitFailed -> context.getString(R.string.msg_transmit_failed, result.message)
}

fun describeHardware(context: Context, status: HardwareStatus): String = when (status) {
    is HardwareStatus.Available -> if (status.carrierRanges.isEmpty()) context.getString(R.string.hw_available_no_ranges)
    else context.getString(R.string.hw_available, formatRanges(status.carrierRanges))
    HardwareStatus.NoFeature -> context.getString(R.string.hw_no_feature)
    HardwareStatus.ServiceUnavailable -> context.getString(R.string.hw_service_unavailable)
    HardwareStatus.NoEmitter -> context.getString(R.string.hw_no_emitter)
    is HardwareStatus.Error -> context.getString(R.string.hw_error, status.message)
}

fun formatRanges(ranges: List<IntRange>): String =
    ranges.joinToString(", ") { if (it.first == it.last) "${it.first / 1000.0} kHz" else "${it.first / 1000.0}–${it.last / 1000.0} kHz" }

fun SendOutcome.isSuccess() = this is SendOutcome.Success
