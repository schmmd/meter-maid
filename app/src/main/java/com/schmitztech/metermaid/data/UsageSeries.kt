package com.schmitztech.metermaid.data

import java.time.LocalDate

/**
 * One plotted day: the average draw since the previous reading on the same meter.
 *
 * A meter records a running total, not a rate, so usage is only ever known *between*
 * two readings. The value is placed on the later date, which is the day you can say
 * "as of here, the well has been averaging this".
 */
data class UsagePoint(
    val date: LocalDate,
    val gallonsPerDay: Double,
    /** Register reading this point was derived from, for the tap-to-inspect label. */
    val reading: Long,
    /** Days since the previous reading — a long gap is a coarser average. */
    val spanDays: Long,
)

object UsageSeries {

    /**
     * Turns the log into a usage-per-day series.
     *
     * Several readings on one day collapse to the last one taken: they are the same
     * register read twice, and the later one supersedes. Rates are computed per meter,
     * so replacing the meter starts a new run rather than producing one huge spike or
     * a negative.
     */
    fun from(readings: List<Reading>): List<UsagePoint> {
        val latestPerDay = readings
            .groupBy { it.meterId to it.readingDate }
            .mapValues { (_, sameDay) -> sameDay.maxBy { it.capturedAtUtc } }
            .values
            .toList()

        // perDay is null for a missing baseline, a zero-day gap (nothing to divide by)
        // and a backwards one — exactly the intervals that cannot be plotted.
        return latestPerDay.intervals().mapNotNull { interval ->
            val perDay = interval.perDay ?: return@mapNotNull null
            UsagePoint(
                date = LocalDate.parse(interval.reading.readingDate),
                gallonsPerDay = perDay,
                reading = interval.reading.value,
                spanDays = interval.days ?: return@mapNotNull null,
            )
        }
    }
}
