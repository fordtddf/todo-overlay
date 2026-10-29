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

    val todayTodos: StateFlow<List<Todo>> = dao.observeByDate(Today.key())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val overlayEnabled: StateFlow<Boolean?> = todoApp.settings.overlayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val durationSeconds: StateFlow<Int?> = todoApp.settings.durationSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { dao.insert(Todo(title = trimmed)) }
    }

    fun toggle(todo: Todo) {
        viewModelScope.launch { dao.update(todo.copy(done = !todo.done)) }
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
