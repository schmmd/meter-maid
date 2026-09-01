package com.schmmd.drumroll.util

import com.schmmd.drumroll.data.MeterConfig
import com.schmmd.drumroll.data.Reading
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class Severity { BLOCK, WARN }

data class Issue(val severity: Severity, val message: String)

/**
 * Checks run live on the confirm screen. Only an incomplete reading blocks saving —
 * everything else warns and lets the user proceed, because a real meter occasionally
 * does something surprising and the app should never be the reason a true reading
 * goes unlogged.
 */
object Validation {

    fun check(
        drums: String,
        readingDate: LocalDate,
        /** When the photo was taken, or null for a reading typed in by hand. */
        capturedAt: LocalDate?,
        previous: Reading?,
        config: MeterConfig,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (drums.length != config.drumCount || !drums.all(Char::isDigit)) {
            issues += Issue(Severity.BLOCK, "Fill in all ${config.drumCount} drums.")
            return issues // nothing else is meaningful yet
        }

        val value = config.toValue(drums)

        if (previous != null) {
            val days = ChronoUnit.DAYS.between(LocalDate.parse(previous.readingDate), readingDate)

            if (value < previous.value) {
                issues += Issue(
                    Severity.WARN,
                    "Meters don't run backwards. Previous was ${config.display(previous.value)} — " +
                        "check the leading digits, or record a meter change in Settings."
                )
            } else if (days > 0) {
                val perDay = (value - previous.value).toDouble() / days
                if (perDay < config.minPerDay || perDay > config.maxPerDay) {
                    issues += Issue(
                        Severity.WARN,
                        "That works out to ${perDay.toInt()} gal/day over $days days — " +
                            "outside your usual ${config.minPerDay.toInt()}–${config.maxPerDay.toInt()}."
                    )
                }
            }
        }

        // Only meaningful against a photo. A reading entered by hand is *expected* to
        // be about a day the phone was nowhere near the meter, so measuring its date
        // against today and calling that drift is telling the user off for doing the
        // thing the screen exists for.
        if (capturedAt != null) {
            val drift = abs(ChronoUnit.DAYS.between(capturedAt, readingDate))
            if (drift > 7) {
                issues += Issue(Severity.WARN, "The date is $drift days from when the photo was taken.")
            }
        }

        return issues
    }

    fun blocking(issues: List<Issue>): Boolean = issues.any { it.severity == Severity.BLOCK }
}
