package com.example.todooverlay

import android.app.Application
import com.example.todooverlay.data.AppDatabase
import com.example.todooverlay.data.SettingsStore

class TodoApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
}
