package com.example.todooverlay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.todooverlay.TodoApp
import com.example.todooverlay.data.Todo
import com.example.todooverlay.data.Today
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodoViewModel(app: Application) : AndroidViewModel(app) {

    private val todoApp = app as TodoApp
    private val dao = todoApp.database.todoDao()

    val todos: StateFlow<List<Todo>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val overlayEnabled: StateFlow<Boolean?> = todoApp.settings.overlayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val durationSeconds: StateFlow<Int?> = todoApp.settings.durationSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(title: String, isDaily: Boolean) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            dao.insert(Todo(title = trimmed, isDaily = isDaily))
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

    fun setOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { todoApp.settings.setOverlayEnabled(enabled) }
    }

    fun setDurationSeconds(seconds: Int) {
        viewModelScope.launch { todoApp.settings.setDurationSeconds(seconds) }
    }
}
