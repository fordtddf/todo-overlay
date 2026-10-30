package com.example.todooverlay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.todooverlay.TodoApp
import com.example.todooverlay.data.ScrollMode
import com.example.todooverlay.data.SettingsStore
import com.example.todooverlay.data.Todo
import com.example.todooverlay.data.Today
import com.example.todooverlay.data.TodoOrdering
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodoViewModel(app: Application) : AndroidViewModel(app) {

    private val todoApp = app as TodoApp
    private val dao = todoApp.database.todoDao()
    private val settings = todoApp.settings

    /** 主界面列表：与解锁卡片共用 [TodoOrdering]，保证两处顺序一致 */
    val todos: StateFlow<List<Todo>> = dao.observeAll()
        .map { TodoOrdering.sorted(it, Today.key()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val overlayEnabled: StateFlow<Boolean?> = settings.overlayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val durationSeconds: StateFlow<Int?> = settings.durationSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val autoScrollEnabled: StateFlow<Boolean?> = settings.autoScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scrollMode: StateFlow<ScrollMode?> = settings.scrollMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scrollSpeed: StateFlow<Int?> = settings.scrollSpeedDpPerSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cardWidthPercent: StateFlow<Int?> = settings.cardWidthPercent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cardMaxHeightDp: StateFlow<Int?> = settings.cardMaxHeightDp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------------------------------------------------------------- 待办

    fun add(title: String, isDaily: Boolean) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.insert(Todo(title = trimmed, isDaily = isDaily, createdAt = now, orderIndex = now))
        }
    }

    fun update(todo: Todo, title: String, isDaily: Boolean) {
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
                    isCompleted = if (typeChanged) false else todo.isCompleted,
                    lastCompletedDate = if (typeChanged) null else todo.lastCompletedDate
                )
            )
        }
    }

    fun toggle(todo: Todo) {
        val today = Today.key()
        viewModelScope.launch { dao.update(todo.toggledOn(today)) }
    }

    fun delete(todo: Todo) {
        viewModelScope.launch { dao.delete(todo) }
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
