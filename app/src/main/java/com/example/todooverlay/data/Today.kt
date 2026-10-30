package com.example.todooverlay.data

import java.time.LocalDate
import java.time.ZoneId

/** 统一的“今天”取值，避免各调用点各写各的格式 */
object Today {
    fun key(): String = LocalDate.now().toString()

    /** 今天 00:00 的毫秒时间戳，用于判断任务是否属于今天 */
    fun startMillis(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 单次任务是否属于今天（按创建时间判定） */
    fun isToday(createdAt: Long): Boolean = createdAt >= startMillis()
}
