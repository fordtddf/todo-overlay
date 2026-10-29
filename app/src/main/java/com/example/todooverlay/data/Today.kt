package com.example.todooverlay.data

import java.time.LocalDate

/** 统一的“今天”取值，避免各调用点各写各的格式 */
object Today {
    fun key(): String = LocalDate.now().toString()
}
