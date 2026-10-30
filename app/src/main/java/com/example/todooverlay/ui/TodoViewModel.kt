package com.example.todooverlay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.todooverlay.TodoApp
import com.example.todooverlay.data.LoopExitMode
import com.example.todooverlay.data.ScrollMode
import com.example.todooverlay.data.SettingsStore
import com.example.todooverlay.data.SortMode
import com.example.todooverlay.data.Todo
import com.example.todooverlay.data.Today
import com.example.todooverlay.data.TodoOrdering
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodoViewModel(app: Application) : AndroidViewModel(app) {

    private val todoApp = app as TodoApp
    private val dao = todoApp.database.todoDao()
    private val settings = todoApp.settings

    /** 主界面列表：与解锁卡片共用 [TodoOrdering]，保证两处顺序一致 */
    val todos: StateFlow<List<Todo>> = combine(
        dao.observeAll(),
        settings.sortMode
    ) { list, mode -> TodoOrdering.sorted(list, Today.key(), mode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sortMode: StateFlow<SortMode?> = settings.sortMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val showExistsTime: StateFlow<Boolean?> = settings.showExistsTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val vocabEnabled: StateFlow<Boolean?> = settings.vocabEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setVocabEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setVocabEnabled(enabled) }
    }

    fun resetSeenWords() {
        viewModelScope.launch { settings.resetSeenWords() }
    }

    val overlayEnabled: StateFlow<Boolean?> = settings.overlayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val durationSeconds: StateFlow<Int?> = settings.durationSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val autoScrollEnabled: StateFlow<Boolean?> = settings.autoScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scrollMode: StateFlow<ScrollMode?> = settings.scrollMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val loopExitMode: StateFlow<LoopExitMode?> = settings.loopExitMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val loopMaxTimeSeconds: StateFlow<Int?> = settings.loopMaxTimeSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val loopMaxCount: StateFlow<Int?> = settings.loopMaxCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scrollSpeed: StateFlow<Int?> = settings.scrollSpeedDpPerSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cardOpacityPercent: StateFlow<Int?> = settings.cardOpacityPercent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cardWidthPercent: StateFlow<Int?> = settings.cardWidthPercent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cardMaxHeightDp: StateFlow<Int?> = settings.cardMaxHeightDp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------------------------------------------------------------- 待办

    fun add(title: String, isDaily: Boolean, priority: Int, customOrder: Int?) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.insert(
                Todo(
                    title = trimmed,
                    isDaily = isDaily,
                    createdAt = now,
                    orderIndex = now,
                    priority = priority,
                    customOrder = customOrder
                )
            )
        }
    }

    fun update(todo: Todo, title: String, isDaily: Boolean, priority: Int, customOrder: Int?) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // 从每日改成单次（或反过来）时，两种完成状态互相矛盾，
            // 统一清空，避免出现「已完成但今天又出现」的怪状态
            val typeChanged = todo.isDaily != isDaily
            dao.update(
                todo.copy(
                    title = trimmed,
                    isDaily = isDaily,
                    priority = priority,
                    customOrder = customOrder,
                    isCompleted = if (typeChanged) false else todo.isCompleted,
                    lastCompletedDate = if (typeChanged) null else todo.lastCompletedDate
                )
            )
        }
    }

    fun setSortMode(mode: SortMode) {
        viewModelScope.launch { settings.setSortMode(mode) }
    }

    fun setShowExistsTime(enabled: Boolean) {
        viewModelScope.launch { settings.setShowExistsTime(enabled) }
    }

    fun toggle(todo: Todo) {
        val today = Today.key()
        viewModelScope.launch { dao.update(todo.toggledOn(today)) }
    }

    fun delete(todo: Todo) {
        viewModelScope.launch { dao.delete(todo) }
    }

    // ---------------------------------------------------------------- 进阶开关

    val flingToDismiss: StateFlow<Boolean?> = settings.flingToDismiss
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cooldownEnabled: StateFlow<Boolean?> = settings.cooldownEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cooldownMinutes: StateFlow<Int?> = settings.cooldownMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val quickAddEnabled: StateFlow<Boolean?> = settings.quickAddEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val showProgress: StateFlow<Boolean?> = settings.showProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val celebrateEnabled: StateFlow<Boolean?> = settings.celebrateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val groupOverdue: StateFlow<Boolean?> = settings.groupOverdue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lowBatteryMode: StateFlow<Boolean?> = settings.lowBatteryMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val landscapeMute: StateFlow<Boolean?> = settings.landscapeMute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFlingToDismiss(enabled: Boolean) {
        viewModelScope.launch { settings.setFlingToDismiss(enabled) }
    }
    fun setCooldownEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setCooldownEnabled(enabled) }
    }
    fun setCooldownMinutes(minutes: Int) {
        viewModelScope.launch { settings.setCooldownMinutes(minutes) }
    }
    fun setQuickAddEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setQuickAddEnabled(enabled) }
    }
    fun setShowProgress(enabled: Boolean) {
        viewModelScope.launch { settings.setShowProgress(enabled) }
    }
    fun setCelebrateEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setCelebrateEnabled(enabled) }
    }
    fun setGroupOverdue(enabled: Boolean) {
        viewModelScope.launch { settings.setGroupOverdue(enabled) }
    }
    fun setLowBatteryMode(enabled: Boolean) {
        viewModelScope.launch { settings.setLowBatteryMode(enabled) }
    }
    fun setLandscapeMute(enabled: Boolean) {
        viewModelScope.launch { settings.setLandscapeMute(enabled) }
    }

    // ---------------------------------------------------------------- 设置

    fun setOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setOverlayEnabled(enabled) }
    }

    fun setDurationSeconds(seconds: Int) {
        viewModelScope.launch { settings.setDurationSeconds(seconds) }
    }

    fun setAutoScrollEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoScrollEnabled(enabled) }
    }

    fun setScrollMode(mode: ScrollMode) {
        viewModelScope.launch { settings.setScrollMode(mode) }
    }

    fun setScrollSpeed(speedDpPerSec: Int) {
        viewModelScope.launch { settings.setScrollSpeedDpPerSec(speedDpPerSec) }
    }

    fun setLoopExitMode(mode: LoopExitMode) {
        viewModelScope.launch { settings.setLoopExitMode(mode) }
    }

    fun setLoopMaxTimeSeconds(seconds: Int) {
        viewModelScope.launch { settings.setLoopMaxTimeSeconds(seconds) }
    }

    fun setLoopMaxCount(count: Int) {
        viewModelScope.launch { settings.setLoopMaxCount(count) }
    }

    fun setCardOpacityPercent(percent: Int) {
        viewModelScope.launch { settings.setCardOpacityPercent(percent) }
    }

    fun setCardWidthPercent(percent: Int) {
        viewModelScope.launch { settings.setCardWidthPercent(percent) }
    }

    fun setCardMaxHeightDp(dp: Int) {
        viewModelScope.launch { settings.setCardMaxHeightDp(dp) }
    }

    /** 预设档位一次性写入宽高 */
    fun applyCardPreset(widthPercent: Int, heightDp: Int) {
        viewModelScope.launch {
            settings.setCardWidthPercent(widthPercent)
            settings.setCardMaxHeightDp(heightDp)
        }
    }

    fun resetCardSize() {
        applyCardPreset(SettingsStore.DEFAULT_WIDTH_PERCENT, SettingsStore.DEFAULT_HEIGHT_DP)
    }
}
