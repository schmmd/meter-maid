package com.schmmd.drumroll.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
            .sortedWith(compareBy({ it.readingDate }, { it.capturedAtUtc }))

        val points = ArrayList<UsagePoint>()
        val previousByMeter = HashMap<String, Reading>()
        for (reading in latestPerDay) {
            val previous = previousByMeter.put(reading.meterId, reading) ?: continue
            val from = LocalDate.parse(previous.readingDate)
            val to = LocalDate.parse(reading.readingDate)
            val days = ChronoUnit.DAYS.between(from, to)
            // A zero-day gap would divide by nothing; a backwards one means the log was
            // edited into an order the meter cannot have run in.
            if (days <= 0) continue
            points += UsagePoint(
                date = to,
                gallonsPerDay = (reading.value - previous.value).toDouble() / days,
                reading = reading.value,
                spanDays = days,
            )
        }
        return points
    }
}
