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

/** 循环模式下什么时候收工 */
enum class LoopExitMode {
    /** 按总时长：到点就关 */
    TIME,

    /** 按轮数：滚够指定轮数后关 */
    COUNT;

    companion object {
        fun from(value: String?): LoopExitMode =
            entries.firstOrNull { it.name == value } ?: TIME
    }
}

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

    val loopExitMode: Flow<LoopExitMode> = prefs.map {
        LoopExitMode.from(it[KEY_LOOP_EXIT_MODE])
    }

    val loopMaxTimeSeconds: Flow<Int> = prefs.map {
        (it[KEY_LOOP_MAX_TIME] ?: DEFAULT_LOOP_TIME).coerceIn(MIN_LOOP_TIME, MAX_LOOP_TIME)
    }

    val loopMaxCount: Flow<Int> = prefs.map {
        (it[KEY_LOOP_MAX_COUNT] ?: DEFAULT_LOOP_COUNT).coerceIn(MIN_LOOP_COUNT, MAX_LOOP_COUNT)
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

    // ---------------------------------------------------------------- 进阶开关

    val flingToDismiss: Flow<Boolean> = prefs.map { it[KEY_FLING_DISMISS] ?: false }

    val cooldownEnabled: Flow<Boolean> = prefs.map { it[KEY_COOLDOWN] ?: false }

    val cooldownMinutes: Flow<Int> = prefs.map {
        (it[KEY_COOLDOWN_MINUTES] ?: DEFAULT_COOLDOWN_MINUTES)
            .coerceIn(MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES)
    }

    val quickAddEnabled: Flow<Boolean> = prefs.map { it[KEY_QUICK_ADD] ?: false }

    val showProgress: Flow<Boolean> = prefs.map { it[KEY_SHOW_PROGRESS] ?: true }

    val celebrateEnabled: Flow<Boolean> = prefs.map { it[KEY_CELEBRATE] ?: true }

    val groupOverdue: Flow<Boolean> = prefs.map { it[KEY_GROUP_OVERDUE] ?: true }

    val sortMode: Flow<SortMode> = prefs.map { SortMode.from(it[KEY_SORT_MODE]) }

    val showExistsTime: Flow<Boolean> = prefs.map { it[KEY_SHOW_EXISTS] ?: true }

    val lowBatteryMode: Flow<Boolean> = prefs.map { it[KEY_LOW_BATTERY] ?: true }

    val landscapeMute: Flow<Boolean> = prefs.map { it[KEY_LANDSCAPE_MUTE] ?: true }

    /** 上次弹窗时间，用于冷却判断。存在 DataStore 里，进程被杀也有效 */
    suspend fun lastShownAt(): Long =
        context.dataStore.data.first()[KEY_LAST_SHOWN] ?: 0L

    suspend fun setLastShownAt(timestamp: Long) =
        context.dataStore.edit { it[KEY_LAST_SHOWN] = timestamp }

    suspend fun setFlingToDismiss(enabled: Boolean) =
        context.dataStore.edit { it[KEY_FLING_DISMISS] = enabled }

    suspend fun setCooldownEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_COOLDOWN] = enabled }

    suspend fun setCooldownMinutes(minutes: Int) =
        context.dataStore.edit {
            it[KEY_COOLDOWN_MINUTES] = minutes.coerceIn(MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES)
        }

    suspend fun setQuickAddEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_QUICK_ADD] = enabled }

    suspend fun setShowProgress(enabled: Boolean) =
        context.dataStore.edit { it[KEY_SHOW_PROGRESS] = enabled }

    suspend fun setCelebrateEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_CELEBRATE] = enabled }

    suspend fun setGroupOverdue(enabled: Boolean) =
        context.dataStore.edit { it[KEY_GROUP_OVERDUE] = enabled }

    suspend fun setSortMode(mode: SortMode) =
        context.dataStore.edit { it[KEY_SORT_MODE] = mode.name }

    suspend fun setShowExistsTime(enabled: Boolean) =
        context.dataStore.edit { it[KEY_SHOW_EXISTS] = enabled }

    suspend fun setLowBatteryMode(enabled: Boolean) =
        context.dataStore.edit { it[KEY_LOW_BATTERY] = enabled }

    suspend fun setLandscapeMute(enabled: Boolean) =
        context.dataStore.edit { it[KEY_LANDSCAPE_MUTE] = enabled }

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

    suspend fun setLoopExitMode(mode: LoopExitMode) =
        context.dataStore.edit { it[KEY_LOOP_EXIT_MODE] = mode.name }

    suspend fun setLoopMaxTimeSeconds(seconds: Int) =
        context.dataStore.edit {
            it[KEY_LOOP_MAX_TIME] = seconds.coerceIn(MIN_LOOP_TIME, MAX_LOOP_TIME)
        }

    suspend fun setLoopMaxCount(count: Int) =
        context.dataStore.edit {
            it[KEY_LOOP_MAX_COUNT] = count.coerceIn(MIN_LOOP_COUNT, MAX_LOOP_COUNT)
        }

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
            loopExitMode = LoopExitMode.from(p[KEY_LOOP_EXIT_MODE]),
            loopMaxTimeSeconds = (p[KEY_LOOP_MAX_TIME] ?: DEFAULT_LOOP_TIME)
                .coerceIn(MIN_LOOP_TIME, MAX_LOOP_TIME),
            loopMaxCount = (p[KEY_LOOP_MAX_COUNT] ?: DEFAULT_LOOP_COUNT)
                .coerceIn(MIN_LOOP_COUNT, MAX_LOOP_COUNT),
            scrollSpeedDpPerSec = (p[KEY_SCROLL_SPEED] ?: DEFAULT_SPEED)
                .coerceIn(SPEED_MIN, SPEED_MAX),
            cardOpacityPercent = (p[KEY_CARD_OPACITY] ?: DEFAULT_OPACITY_PERCENT)
                .coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT),
            cardWidthPercent = (p[KEY_CARD_WIDTH] ?: DEFAULT_WIDTH_PERCENT)
                .coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT),
            cardMaxHeightDp = (p[KEY_CARD_HEIGHT] ?: DEFAULT_HEIGHT_DP)
                .coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP),
            flingToDismiss = p[KEY_FLING_DISMISS] ?: false,
            cooldownEnabled = p[KEY_COOLDOWN] ?: false,
            cooldownMinutes = (p[KEY_COOLDOWN_MINUTES] ?: DEFAULT_COOLDOWN_MINUTES)
                .coerceIn(MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES),
            quickAddEnabled = p[KEY_QUICK_ADD] ?: false,
            showProgress = p[KEY_SHOW_PROGRESS] ?: true,
            celebrateEnabled = p[KEY_CELEBRATE] ?: true,
            groupOverdue = p[KEY_GROUP_OVERDUE] ?: true,
            lowBatteryMode = p[KEY_LOW_BATTERY] ?: true,
            landscapeMute = p[KEY_LANDSCAPE_MUTE] ?: true,
            sortMode = SortMode.from(p[KEY_SORT_MODE]),
            showExistsTime = p[KEY_SHOW_EXISTS] ?: true
        )
    }

    data class Snapshot(
        val enabled: Boolean,
        val durationSeconds: Int,
        val autoScroll: Boolean,
        val scrollMode: ScrollMode,
        val loopExitMode: LoopExitMode,
        val loopMaxTimeSeconds: Int,
        val loopMaxCount: Int,
        val scrollSpeedDpPerSec: Int,
        val cardOpacityPercent: Int,
        val cardWidthPercent: Int,
        val cardMaxHeightDp: Int,
        val flingToDismiss: Boolean,
        val cooldownEnabled: Boolean,
        val cooldownMinutes: Int,
        val quickAddEnabled: Boolean,
        val showProgress: Boolean,
        val celebrateEnabled: Boolean,
        val groupOverdue: Boolean,
        val lowBatteryMode: Boolean,
        val landscapeMute: Boolean,
        val sortMode: SortMode,
        val showExistsTime: Boolean
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

        /** 循环模式：按时间退出的取值范围 */
        const val MIN_LOOP_TIME = 10
        const val MAX_LOOP_TIME = 60
        const val DEFAULT_LOOP_TIME = 20

        /** 循环模式：按轮数退出的可选项 */
        const val MIN_LOOP_COUNT = 2
        const val MAX_LOOP_COUNT = 5
        const val DEFAULT_LOOP_COUNT = 3
        val LOOP_COUNT_PRESETS = listOf(2, 3, 5)

        /** 高频解锁冷却：1~10 分钟，默认 3 分钟 */
        const val MIN_COOLDOWN_MINUTES = 1
        const val MAX_COOLDOWN_MINUTES = 10
        const val DEFAULT_COOLDOWN_MINUTES = 3

        /** 低电量阈值 */
        const val LOW_BATTERY_THRESHOLD = 20

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
        private val KEY_LOOP_EXIT_MODE = stringPreferencesKey("loop_exit_mode")
        private val KEY_LOOP_MAX_TIME = intPreferencesKey("loop_max_time_seconds")
        private val KEY_LOOP_MAX_COUNT = intPreferencesKey("loop_max_count")
        private val KEY_SCROLL_SPEED = intPreferencesKey("overlay_scroll_speed")
        private val KEY_CARD_OPACITY = intPreferencesKey("overlay_card_opacity_percent")
        private val KEY_CARD_WIDTH = intPreferencesKey("overlay_card_width_percent")
        private val KEY_CARD_HEIGHT = intPreferencesKey("overlay_card_max_height_dp")

        private val KEY_FLING_DISMISS = booleanPreferencesKey("overlay_fling_dismiss")
        private val KEY_COOLDOWN = booleanPreferencesKey("overlay_cooldown_enabled")
        private val KEY_COOLDOWN_MINUTES = intPreferencesKey("overlay_cooldown_minutes")
        private val KEY_QUICK_ADD = booleanPreferencesKey("overlay_quick_add")
        private val KEY_SHOW_PROGRESS = booleanPreferencesKey("overlay_show_progress")
        private val KEY_CELEBRATE = booleanPreferencesKey("overlay_celebrate")
        private val KEY_GROUP_OVERDUE = booleanPreferencesKey("overlay_group_overdue")
        private val KEY_LOW_BATTERY = booleanPreferencesKey("overlay_low_battery_mode")
        private val KEY_LANDSCAPE_MUTE = booleanPreferencesKey("overlay_landscape_mute")
        private val KEY_SORT_MODE = stringPreferencesKey("todo_sort_mode")
        private val KEY_SHOW_EXISTS = booleanPreferencesKey("show_exists_time")
        private val KEY_LAST_SHOWN = androidx.datastore.preferences.core.longPreferencesKey(
            "overlay_last_shown_at"
        )
    }
}
