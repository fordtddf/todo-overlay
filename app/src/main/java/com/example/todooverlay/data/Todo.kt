package com.example.todooverlay.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "todos")
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 归属日期，格式 yyyy-MM-dd，便于按“今天”筛选 */
    val date: String = LocalDate.now().toString(),
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
