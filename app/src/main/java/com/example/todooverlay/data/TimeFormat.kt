package com.example.todooverlay.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待办存在时长的友好格式化。
 *
 * 规则：
 * - 1 小时内：刚刚 / X分钟前
 * - 24 小时内：X小时前
 * - 超过 1 天：每日任务「已坚持 X 天」，单次任务「已搁置 X 天」
 * - 超过 7 天：调用方应加警示色（[isStale]）
 */
object TimeFormat {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** 超过这个天数算长期未完成，UI 上给弱警示色 */
    const val STALE_DAYS = 7

    /** 卡片 / 列表里的短标签 */
    fun existsLabel(createdAt: Long, isDaily: Boolean, now: Long = System.currentTimeMillis()): String {
        val elapsed = (now - createdAt).coerceAtLeast(0)
        return when {
            elapsed < MINUTE -> "刚刚"
            elapsed < HOUR -> "${elapsed / MINUTE} 分钟前"
            elapsed < DAY -> "${elapsed / HOUR} 小时前"
            else -> {
                val days = (elapsed / DAY).toInt()
                if (isDaily) "已坚持 $days 天" else "已搁置 $days 天"
            }
        }
    }

    /** 是否已经拖了很久，需要提醒 */
    fun isStale(createdAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - createdAt >= STALE_DAYS * DAY

    /** 编辑页展示的精确时间：创建于 10月27日 14:30 */
    fun exactCreatedAt(createdAt: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(createdAt))
}
