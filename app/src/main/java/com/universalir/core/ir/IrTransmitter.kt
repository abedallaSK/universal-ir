package com.universalir.core.ir

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.SystemClock
import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.IrTransmission
import com.universalir.ir.core.PatternIssue
import com.universalir.ir.core.PatternValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone reports about its IR emitter. Never inferred, only read from the system. */
sealed interface HardwareStatus {
    data class Available(val carrierRanges: List<IntRange>) : HardwareStatus

    /** PackageManager does not declare FEATURE_CONSUMER_IR. */
    data object NoFeature : HardwareStatus

    /** The system service could not be obtained. */
    data object ServiceUnavailable : HardwareStatus

    /** The service exists but `hasIrEmitter()` returned false. */
    data object NoEmitter : HardwareStatus

    data class Error(val message: String) : HardwareStatus
}

sealed interface TransmitResult {
    /** Every message was handed to the system service without an exception. */
    data class Sent(val messages: Int, val airTimeUs: Long, val elapsedMs: Long) : TransmitResult

    data class HardwareUnavailable(val status: HardwareStatus) : TransmitResult

    data class InvalidPattern(val issues: List<PatternIssue>) : TransmitResult

    /** The system service threw while transmitting; earlier messages may have been sent. */
    data class TransmitFailed(val message: String, val messagesSent: Int) : TransmitResult
}

interface IrTransmitter {
    fun checkHardwareSupport(): HardwareStatus
    fun getSupportedCarrierRanges(): List<IntRange>
    fun validatePattern(pattern: IrPattern): List<PatternIssue>
    suspend fun transmit(transmission: IrTransmission): TransmitResult
}

@Singleton
class AndroidIrTransmitter @Inject constructor(
    @ApplicationContext private val context: Context,
) : IrTransmitter {

    // Transmissions are serialised: the emitter is a single physical LED.
    private val mutex = Mutex()

    private fun manager(): ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    override fun checkHardwareSupport(): HardwareStatus = try {
        when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR) -> HardwareStatus.NoFeature
            else -> {
                val m = manager()
                when {
                    m == null -> HardwareStatus.ServiceUnavailable
                    !m.hasIrEmitter() -> HardwareStatus.NoEmitter
                    else -> HardwareStatus.Available(readRanges(m))
                }
            }
        }
    } catch (e: RuntimeException) {
        HardwareStatus.Error(e.message ?: e.javaClass.simpleName)
    }

    override fun getSupportedCarrierRanges(): List<IntRange> =
        (checkHardwareSupport() as? HardwareStatus.Available)?.carrierRanges ?: emptyList()

    private fun readRanges(m: ConsumerIrManager): List<IntRange> =
        try {
            m.carrierFrequencies?.map { it.minFrequency..it.maxFrequency } ?: emptyList()
        } catch (e: RuntimeException) {
            emptyList()
        }

    override fun validatePattern(pattern: IrPattern): List<PatternIssue> =
        PatternValidator.validate(pattern, getSupportedCarrierRanges().ifEmpty { null })

    override suspend fun transmit(transmission: IrTransmission): TransmitResult {
        val status = checkHardwareSupport()
        if (status !is HardwareStatus.Available) return TransmitResult.HardwareUnavailable(status)
        // An empty range list means the device did not report ranges; only the generic limits apply.
        val issues = PatternValidator.validate(transmission, status.carrierRanges.ifEmpty { null })
        if (issues.isNotEmpty()) return TransmitResult.InvalidPattern(issues)
        val m = manager() ?: return TransmitResult.HardwareUnavailable(HardwareStatus.ServiceUnavailable)

        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val start = SystemClock.elapsedRealtime()
                var sent = 0
                try {
                    transmission.messages.forEachIndexed { index, message ->
                        // transmit() blocks for the duration of the pattern on most implementations.
                        m.transmit(message.pattern.carrierHz, message.pattern.timings())
                        sent++
                        if (index < transmission.messages.lastIndex && message.pauseAfterMs > 0) delay(message.pauseAfterMs.toLong())
                    }
                    TransmitResult.Sent(sent, transmission.totalAirTimeUs, SystemClock.elapsedRealtime() - start)
                } catch (e: RuntimeException) {
                    TransmitResult.TransmitFailed(e.message ?: e.javaClass.simpleName, sent)
                }
            }
        }
    }

    companion object {
        fun deviceDescription(): String =
            "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
    }
}
