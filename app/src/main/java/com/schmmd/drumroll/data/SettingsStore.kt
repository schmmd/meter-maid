package com.schmmd.drumroll.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("drumroll")

/** The guide rectangle, in fractions of the preview, plus its rotation. */
data class Roi(
    val left: Float = 0.10f,
    val top: Float = 0.44f,
    val width: Float = 0.80f,
    val height: Float = 0.12f,
    val rotationDegrees: Int = 0,
)

data class Settings(
    val endpoint: String = "",
    val secret: String = "",
    val meter: MeterConfig = MeterConfig(),
    val roi: Roi = Roi(),
    val ocrEnabled: Boolean = true,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun setEndpoint(url: String, secret: String) = context.dataStore.edit {
        it[K_ENDPOINT] = url
        it[K_SECRET] = secret
    }

    suspend fun setRoi(roi: Roi) = context.dataStore.edit {
        it[K_ROI_L] = roi.left
        it[K_ROI_T] = roi.top
        it[K_ROI_W] = roi.width
        it[K_ROI_H] = roi.height
        it[K_ROI_ROT] = roi.rotationDegrees
    }

    suspend fun setMeter(config: MeterConfig) = context.dataStore.edit {
        it[K_METER_ID] = config.meterId
        it[K_DRUMS] = config.drumCount
        it[K_ZEROS] = config.trailingZeros
        it[K_MIN_DAY] = config.minPerDay
        it[K_MAX_DAY] = config.maxPerDay
    }

    suspend fun setOcrEnabled(enabled: Boolean) = context.dataStore.edit {
        it[K_OCR] = if (enabled) 1 else 0
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = MeterConfig()
        return Settings(
            endpoint = this[K_ENDPOINT].orEmpty(),
            secret = this[K_SECRET].orEmpty(),
            meter = MeterConfig(
                meterId = this[K_METER_ID] ?: defaults.meterId,
                drumCount = this[K_DRUMS] ?: defaults.drumCount,
                trailingZeros = this[K_ZEROS] ?: defaults.trailingZeros,
                minPerDay = this[K_MIN_DAY] ?: defaults.minPerDay,
                maxPerDay = this[K_MAX_DAY] ?: defaults.maxPerDay,
            ),
            roi = Roi(
                left = this[K_ROI_L] ?: Roi().left,
                top = this[K_ROI_T] ?: Roi().top,
                width = this[K_ROI_W] ?: Roi().width,
                height = this[K_ROI_H] ?: Roi().height,
                rotationDegrees = this[K_ROI_ROT] ?: Roi().rotationDegrees,
            ),
            ocrEnabled = (this[K_OCR] ?: 1) == 1,
        )
    }

    private companion object {
        val K_ENDPOINT = stringPreferencesKey("endpoint")
        val K_SECRET = stringPreferencesKey("secret")
        val K_METER_ID = stringPreferencesKey("meter_id")
        val K_DRUMS = intPreferencesKey("drums")
        val K_ZEROS = intPreferencesKey("zeros")
        val K_MIN_DAY = doublePreferencesKey("min_per_day")
        val K_MAX_DAY = doublePreferencesKey("max_per_day")
        val K_ROI_L = floatPreferencesKey("roi_l")
        val K_ROI_T = floatPreferencesKey("roi_t")
        val K_ROI_W = floatPreferencesKey("roi_w")
        val K_ROI_H = floatPreferencesKey("roi_h")
        val K_ROI_ROT = intPreferencesKey("roi_rot")
        val K_OCR = intPreferencesKey("ocr_enabled")
    }
}
