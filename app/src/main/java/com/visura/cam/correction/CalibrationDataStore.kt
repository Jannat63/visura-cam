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
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "ajcam_calibration")

@Singleton
class CalibrationDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun save(lensId: String, r: Float, g: Float, b: Float) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[floatPreferencesKey("${lensId}_r")] = r
                prefs[floatPreferencesKey("${lensId}_g")] = g
                prefs[floatPreferencesKey("${lensId}_b")] = b
            }
        }
    }

    fun load(lensId: String): Triple<Float, Float, Float>? = try {
        runBlocking {
            val prefs = context.dataStore.data.first()
            val r = prefs[floatPreferencesKey("${lensId}_r")] ?: return@runBlocking null
            val g = prefs[floatPreferencesKey("${lensId}_g")] ?: return@runBlocking null
            val b = prefs[floatPreferencesKey("${lensId}_b")] ?: return@runBlocking null
            Triple(r, g, b)
        }
    } catch (e: Exception) { null }
}
