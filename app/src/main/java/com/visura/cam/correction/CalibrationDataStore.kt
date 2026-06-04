package com.visura.cam.correction

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "visura_calibration")

/**
 * CalibrationDataStore — Persists per-lens color correction profiles.
 *
 * Stores R/G/B gain values for each camera lens so calibration
 * survives app restarts. Also stores whether the user has done
 * a white-reference calibration for each lens.
 */
@Singleton
class CalibrationDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun save(lensId: String, rGain: Float, gGain: Float, bGain: Float) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[floatPreferencesKey("lens_${lensId}_r")] = rGain
                prefs[floatPreferencesKey("lens_${lensId}_g")] = gGain
                prefs[floatPreferencesKey("lens_${lensId}_b")] = bGain
                prefs[floatPreferencesKey("lens_${lensId}_calibrated")] = 1f
            }
        }
    }

    suspend fun load(lensId: String): Triple<Float, Float, Float>? {
        val prefs = context.dataStore.data.first()
        val r = prefs[floatPreferencesKey("lens_${lensId}_r")] ?: return null
        val g = prefs[floatPreferencesKey("lens_${lensId}_g")] ?: return null
        val b = prefs[floatPreferencesKey("lens_${lensId}_b")] ?: return null
        return Triple(r, g, b)
    }

    suspend fun isCalibrated(lensId: String): Boolean {
        val prefs = context.dataStore.data.first()
        return (prefs[floatPreferencesKey("lens_${lensId}_calibrated")] ?: 0f) == 1f
    }

    fun resetToDefaults(lensId: String) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs.remove(floatPreferencesKey("lens_${lensId}_r"))
                prefs.remove(floatPreferencesKey("lens_${lensId}_g"))
                prefs.remove(floatPreferencesKey("lens_${lensId}_b"))
                prefs.remove(floatPreferencesKey("lens_${lensId}_calibrated"))
            }
        }
    }
}
