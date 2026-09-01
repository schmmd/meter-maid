package com.schmitztech.metermaid.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A reading paired with its chronological predecessor on the same meter.
 *
 * A meter records a running total, so everything the app says about usage — the CSV's
 * change/days/gal-day columns, the chart, the history subtitles — is a statement about
 * the gap between two readings. This is the one place that gap is computed; the
 * surfaces differ only in how they render a missing baseline or a same-day pair.
 * (The confirm screen prices a reading that is still being typed and does its own
 * arithmetic against a database lookup instead.)
 */
data class Interval(val previous: Reading?, val reading: Reading) {
    val days: Long? = previous?.let {
        ChronoUnit.DAYS.between(LocalDate.parse(it.readingDate), LocalDate.parse(reading.readingDate))
    }
    val delta: Long? = previous?.let { reading.value - it.value }

    /**
     * Average draw since the previous reading. Null without a baseline, and null for a
     * gap of zero days (nothing to divide by) or a negative one (the log was edited
     * into an order the meter cannot have run in).
     */
    val perDay: Double? =
        if (days != null && days > 0 && delta != null) delta.toDouble() / days else null
}

/**
 * Every reading with its predecessor, in chronological order. Ties on the same day
 * fall back to when the photo was taken. Per meter, so swapping the meter out starts
 * a fresh series instead of one enormous negative jump.
 */
fun List<Reading>.intervals(): List<Interval> {
    val ordered = sortedWith(compareBy({ it.readingDate }, { it.capturedAtUtc }))
    val previousByMeter = HashMap<String, Reading>()
    return ordered.map { Interval(previousByMeter.put(it.meterId, it), it) }
}
