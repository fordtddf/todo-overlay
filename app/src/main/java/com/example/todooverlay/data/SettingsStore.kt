package com.example.todooverlay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 卡片自动滚动模式 */
enum class ScrollMode {
    /** 滚到底部停一会儿就淡出关闭 */
    SINGLE,

    /** 到底部后平滑回滚到顶部继续循环，靠最长保护时间兜底关闭 */
    LOOP;

    companion object {
        fun from(value: String?): ScrollMode =
            entries.firstOrNull { it.name == value } ?: SINGLE
    }
}

/**
 * 解锁弹窗设置。全部走 DataStore，卸载前一直有效。
 */
class SettingsStore(private val context: Context) {

    private val prefs = context.dataStore.data

    val overlayEnabled: Flow<Boolean> = prefs.map { it[KEY_ENABLED] ?: true }

    val durationSeconds: Flow<Int> = prefs.map {
        (it[KEY_DURATION] ?: DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    val autoScrollEnabled: Flow<Boolean> = prefs.map {
        it[KEY_AUTO_SCROLL] ?: DEFAULT_AUTO_SCROLL
    }

    val scrollMode: Flow<ScrollMode> = prefs.map {
        ScrollMode.from(it[KEY_SCROLL_MODE])
    }

    val scrollSpeedDpPerSec: Flow<Int> = prefs.map {
        (it[KEY_SCROLL_SPEED] ?: DEFAULT_SPEED).coerceIn(SPEED_MIN, SPEED_MAX)
    }

    val cardOpacityPercent: Flow<Int> = prefs.map {
        (it[KEY_CARD_OPACITY] ?: DEFAULT_OPACITY_PERCENT)
            .coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
    }

    val cardWidthPercent: Flow<Int> = prefs.map {
        (it[KEY_CARD_WIDTH] ?: DEFAULT_WIDTH_PERCENT).coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT)
    }

    val cardMaxHeightDp: Flow<Int> = prefs.map {
        (it[KEY_CARD_HEIGHT] ?: DEFAULT_HEIGHT_DP).coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
    }

    suspend fun setOverlayEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_ENABLED] = enabled }

    suspend fun setDurationSeconds(seconds: Int) =
        context.dataStore.edit {
            it[KEY_DURATION] = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        }

    suspend fun setAutoScrollEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_AUTO_SCROLL] = enabled }

    suspend fun setScrollMode(mode: ScrollMode) =
        context.dataStore.edit { it[KEY_SCROLL_MODE] = mode.name }

    suspend fun setScrollSpeedDpPerSec(speed: Int) =
        context.dataStore.edit { it[KEY_SCROLL_SPEED] = speed.coerceIn(SPEED_MIN, SPEED_MAX) }

    suspend fun setCardOpacityPercent(percent: Int) =
        context.dataStore.edit {
            it[KEY_CARD_OPACITY] = percent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
        }

    suspend fun setCardWidthPercent(percent: Int) =
        context.dataStore.edit {
            it[KEY_CARD_WIDTH] = percent.coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT)
        }

    suspend fun setCardMaxHeightDp(dp: Int) =
        context.dataStore.edit { it[KEY_CARD_HEIGHT] = dp.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP) }

    /** 一次性读出全部设置，避免解锁瞬间反复触发 DataStore 读取 */
    suspend fun snapshot(): Snapshot {
        val p = context.dataStore.data.first()
        return Snapshot(
            enabled = p[KEY_ENABLED] ?: true,
            durationSeconds = (p[KEY_DURATION] ?: DEFAULT_SECONDS)
                .coerceIn(MIN_SECONDS, MAX_SECONDS),
            autoScroll = p[KEY_AUTO_SCROLL] ?: DEFAULT_AUTO_SCROLL,
            scrollMode = ScrollMode.from(p[KEY_SCROLL_MODE]),
            scrollSpeedDpPerSec = (p[KEY_SCROLL_SPEED] ?: DEFAULT_SPEED)
                .coerceIn(SPEED_MIN, SPEED_MAX),
            cardOpacityPercent = (p[KEY_CARD_OPACITY] ?: DEFAULT_OPACITY_PERCENT)
                .coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT),
            cardWidthPercent = (p[KEY_CARD_WIDTH] ?: DEFAULT_WIDTH_PERCENT)
                .coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT),
            cardMaxHeightDp = (p[KEY_CARD_HEIGHT] ?: DEFAULT_HEIGHT_DP)
                .coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
        )
    }

    data class Snapshot(
        val enabled: Boolean,
        val durationSeconds: Int,
        val autoScroll: Boolean,
        val scrollMode: ScrollMode,
        val scrollSpeedDpPerSec: Int,
        val cardOpacityPercent: Int,
        val cardWidthPercent: Int,
        val cardMaxHeightDp: Int
    )

    companion object {
        const val MIN_SECONDS = 3
        const val MAX_SECONDS = 15
        const val DEFAULT_SECONDS = 5

        const val DEFAULT_AUTO_SCROLL = true
        val DEFAULT_SCROLL_MODE = ScrollMode.SINGLE

        /** 滚动速度：20 dp/s（超慢）~ 150 dp/s（极速） */
        const val SPEED_MIN = 20
        const val SPEED_MAX = 150
        const val SPEED_SLOW = 30
        const val SPEED_NORMAL = 50
        const val SPEED_FAST = 80
        const val SPEED_ULTRA = 120
        const val DEFAULT_SPEED = 40

        /** 卡片背景不透明度。低于 50% 会明显影响文字辨识度，所以卡在这个下限 */
        const val MIN_OPACITY_PERCENT = 50
        const val MAX_OPACITY_PERCENT = 100
        const val DEFAULT_OPACITY_PERCENT = 100

        const val MIN_WIDTH_PERCENT = 70
        const val MAX_WIDTH_PERCENT = 95
        const val DEFAULT_WIDTH_PERCENT = 90

        const val MIN_HEIGHT_DP = 180
        const val MAX_HEIGHT_DP = 420
        const val DEFAULT_HEIGHT_DP = 260

        /** 尺寸预设：紧凑 / 默认 / 宽大 */
        const val PRESET_S_WIDTH = 78
        const val PRESET_S_HEIGHT = 200
        const val PRESET_M_WIDTH = DEFAULT_WIDTH_PERCENT
        const val PRESET_M_HEIGHT = DEFAULT_HEIGHT_DP
        const val PRESET_L_WIDTH = 95
        const val PRESET_L_HEIGHT = 360

        private val KEY_ENABLED = booleanPreferencesKey("unlock_overlay_enabled")
        private val KEY_DURATION = intPreferencesKey("unlock_overlay_seconds")
        private val KEY_AUTO_SCROLL = booleanPreferencesKey("overlay_auto_scroll")
        private val KEY_SCROLL_MODE = stringPreferencesKey("overlay_scroll_mode")
        private val KEY_SCROLL_SPEED = intPreferencesKey("overlay_scroll_speed")
        private val KEY_CARD_OPACITY = intPreferencesKey("overlay_card_opacity_percent")
        private val KEY_CARD_WIDTH = intPreferencesKey("overlay_card_width_percent")
        private val KEY_CARD_HEIGHT = intPreferencesKey("overlay_card_max_height_dp")
    }
}
