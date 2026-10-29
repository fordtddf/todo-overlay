package com.example.todooverlay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 解锁弹窗设置。默认开启，时长 5 秒（可调 3~15 秒）。
 */
class SettingsStore(private val context: Context) {

    val overlayEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_ENABLED] ?: true }

    val durationSeconds: Flow<Int> = context.dataStore.data
        .map { (it[KEY_DURATION] ?: DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS) }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setDurationSeconds(seconds: Int) {
        context.dataStore.edit { it[KEY_DURATION] = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS) }
    }

    /** 一次性读出当前设置，避免在解锁瞬间多次触发 DataStore 读取 */
    suspend fun snapshot(): Snapshot {
        val prefs = context.dataStore.data.first()
        return Snapshot(
            enabled = prefs[KEY_ENABLED] ?: true,
            durationSeconds = (prefs[KEY_DURATION] ?: DEFAULT_SECONDS)
                .coerceIn(MIN_SECONDS, MAX_SECONDS)
        )
    }

    data class Snapshot(val enabled: Boolean, val durationSeconds: Int)

    companion object {
        const val MIN_SECONDS = 3
        const val MAX_SECONDS = 15
        const val DEFAULT_SECONDS = 5

        private val KEY_ENABLED = booleanPreferencesKey("unlock_overlay_enabled")
        private val KEY_DURATION = intPreferencesKey("unlock_overlay_seconds")
    }
}
