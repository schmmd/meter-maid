package com.schmitztech.metermaid.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One well-meter reading.
 *
 * [value] is the whole number as it appears on the register, painted trailing zero
 * included — so the 24 Oct 2021 photo (drums 324444, painted 0) is stored as 3244440.
 * The OCR only ever reads the rotating drums; the fixed zeros are appended by
 * [MeterConfig.toValue].
 *
 * [capturedAtUtc] is forensic and never edited. [readingDate] is editorial: you may
 * photograph the meter on Sunday and file it on Monday, or correct a mistyped date.
 */
@Entity(tableName = "readings")
data class Reading(
    @PrimaryKey val id: String,
    val readingDate: String,       // ISO local date, "2021-10-24"
    val capturedAtUtc: Long,
    val value: Long,
    val meterId: String,
    val corrected: Boolean,        // did the user change a prefilled digit?
    val photoPath: String,
    val note: String,
)
