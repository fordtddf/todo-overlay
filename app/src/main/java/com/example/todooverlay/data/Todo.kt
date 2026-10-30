package com.example.todooverlay.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条待办。分两类：
 *
 * - **每日提醒**（[isDaily] = true）：每天都要做，跨日自动回到未完成。
 *   完成状态由 [lastCompletedDate] 是否等于今天决定，不需要任何定时任务去重置。
 * - **单次任务**（[isDaily] = false）：完成状态由 [isCompleted] 决定，
 *   跨日不会自动清除，一直留在列表里提醒，直到用户主动勾选。
 */
@Entity(tableName = "todos")
data class Todo(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,

    @ColumnInfo(name = "is_daily")
    val isDaily: Boolean = false,

    /** 最近一次标记完成的日期，格式 yyyy-MM-dd；仅每日提醒使用 */
    @ColumnInfo(name = "last_completed_date")
    val lastCompletedDate: String? = null,

    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** 自定义权重，值越小越靠前。默认取创建时刻，等于「先建先显示」 */
    @ColumnInfo(name = "order_index")
    val orderIndex: Long = System.currentTimeMillis(),

    /** 重要性：0 低 / 1 中（默认）/ 2 高 / 3 紧急 */
    @ColumnInfo(name = "priority")
    val priority: Int = PRIORITY_MEDIUM,

    /** 用户自填序号，null 表示没填 */
    @ColumnInfo(name = "custom_order")
    val customOrder: Int? = null
) {

    /**
     * 惰性跨日判断：不存「今天完成了没」这种会过期的状态，
     * 每次读取时拿当前日期现算，日期一变每日任务自然就变回未完成。
     */
    fun isDoneOn(today: String): Boolean =
        if (isDaily) lastCompletedDate == today else isCompleted

    /** 返回勾选/取消勾选后的新对象 */
    fun toggledOn(today: String): Todo =
        if (isDaily) {
            copy(lastCompletedDate = if (lastCompletedDate == today) null else today)
        } else {
            copy(isCompleted = !isCompleted)
        }

    companion object {
        const val PRIORITY_LOW = 0
        const val PRIORITY_MEDIUM = 1
        const val PRIORITY_HIGH = 2
        const val PRIORITY_URGENT = 3

        val PRIORITY_RANGE = PRIORITY_LOW..PRIORITY_URGENT
    }
}
