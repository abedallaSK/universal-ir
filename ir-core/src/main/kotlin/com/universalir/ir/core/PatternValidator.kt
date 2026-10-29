package com.universalir.ir.core

/**
 * Validates patterns before they are handed to the Android IR service.
 *
 * Limits are deliberately conservative:
 *  - `ConsumerIrManager.transmit` documents that a pattern must be shorter than 2 seconds.
 *  - Consumer IR carriers live between ~30 and ~60 kHz; anything outside 15..100 kHz is
 *    certainly a parse error rather than a real code.
 *  - Single durations above 0.5 s or below 5 us are not produced by any known remote.
 */
object PatternValidator {
    const val MAX_PATTERN_DURATION_US: Long = 2_000_000
    const val MAX_ENTRIES: Int = 2_048
    const val MIN_DURATION_US: Int = 5
    const val MAX_SINGLE_DURATION_US: Int = 500_000
    const val MIN_CARRIER_HZ: Int = 15_000
    const val MAX_CARRIER_HZ: Int = 100_000
    const val MAX_MESSAGES: Int = 8

    fun validate(pattern: IrPattern, supportedCarriers: List<IntRange>? = null): List<PatternIssue> {
        val issues = mutableListOf<PatternIssue>()
        if (pattern.carrierHz !in MIN_CARRIER_HZ..MAX_CARRIER_HZ) {
            issues += PatternIssue.CarrierOutOfRange(pattern.carrierHz)
        } else if (!supportedCarriers.isNullOrEmpty() && supportedCarriers.none { pattern.carrierHz in it }) {
            issues += PatternIssue.CarrierNotSupportedByHardware(pattern.carrierHz, supportedCarriers)
        }
        val timings = pattern.timings()
        if (timings.isEmpty()) issues += PatternIssue.Empty
        if (timings.size > MAX_ENTRIES) issues += PatternIssue.TooManyEntries(timings.size)
        timings.forEachIndexed { index, value ->
            if (value < MIN_DURATION_US || value > MAX_SINGLE_DURATION_US) {
                issues += PatternIssue.InvalidDuration(index, value)
                return@forEachIndexed
            }
        }
        if (pattern.totalDurationUs >= MAX_PATTERN_DURATION_US) {
            issues += PatternIssue.TooLong(pattern.totalDurationUs)
        }
        return issues
    }

    fun validate(transmission: IrTransmission, supportedCarriers: List<IntRange>? = null): List<PatternIssue> {
        val issues = mutableListOf<PatternIssue>()
        if (transmission.messages.size > MAX_MESSAGES) issues += PatternIssue.TooManyMessages(transmission.messages.size)
        transmission.messages.forEach { issues += validate(it.pattern, supportedCarriers) }
        return issues
    }
}

sealed interface PatternIssue {
    val message: String

    data object Empty : PatternIssue {
        override val message = "The pattern contains no timings."
    }

    data class CarrierOutOfRange(val hz: Int) : PatternIssue {
        override val message = "Carrier frequency $hz Hz is outside the plausible IR range."
    }

    data class CarrierNotSupportedByHardware(val hz: Int, val ranges: List<IntRange>) : PatternIssue {
        override val message = "This phone's IR emitter does not report support for $hz Hz " +
            "(supported: ${ranges.joinToString { "${it.first}-${it.last} Hz" }})."
    }

    data class TooManyEntries(val count: Int) : PatternIssue {
        override val message = "Pattern has $count entries; the limit is ${PatternValidator.MAX_ENTRIES}."
    }

    data class InvalidDuration(val index: Int, val value: Int) : PatternIssue {
        override val message = "Timing #$index ($value us) is outside ${PatternValidator.MIN_DURATION_US}.." +
            "${PatternValidator.MAX_SINGLE_DURATION_US} us."
    }

    data class TooLong(val durationUs: Long) : PatternIssue {
        override val message = "Pattern lasts ${durationUs / 1000} ms; Android requires less than 2000 ms " +
            "per transmit call and this message cannot be split safely."
    }

    data class TooManyMessages(val count: Int) : PatternIssue {
        override val message = "Transmission contains $count messages; the limit is ${PatternValidator.MAX_MESSAGES}."
    }
}
