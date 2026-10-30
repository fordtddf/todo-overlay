package com.example.todooverlay.data

/** 主界面与卡片的排序模式 */
enum class SortMode {
    /** 重要性优先：紧急 > 高 > 中 > 低，同级再按自选序号 */
    PRIORITY,

    /** 纯自选数字优先：填了序号的按数字排，没填的按重要性 */
    CUSTOM_ORDER;

    companion object {
        fun from(value: String?): SortMode =
            entries.firstOrNull { it.name == value } ?: PRIORITY
    }
}

/**
 * 待办排序的唯一来源。主界面列表和解锁卡片都走这里，避免两处规则不一致。
 *
 * 共同的第一优先级永远是完成状态：未完成在前，已完成沉底。
 * 之后的规则由 [SortMode] 决定：
 *
 * - [SortMode.PRIORITY]：priority 降序 → 有序号的小到大（无序号靠后）→ 创建时间
 * - [SortMode.CUSTOM_ORDER]：有序号的小到大（无序号靠后）→ priority 降序 → 创建时间
 */
object TodoOrdering {

    fun comparator(todayStr: String, mode: SortMode): Comparator<Todo> {
        // 未填序号统一排到有序号的后面
        val hasOrder: (Todo) -> Boolean = { it.customOrder != null }
        val customOrder: (Todo) -> Int = { it.customOrder ?: Int.MAX_VALUE }
        val byPriorityDesc: (Todo) -> Int = { -it.priority }

        return when (mode) {
            SortMode.PRIORITY -> compareBy<Todo>(
                { it.isDoneOn(todayStr) },
                byPriorityDesc,
                { !hasOrder(it) },
                customOrder,
                { it.createdAt }
            )

            SortMode.CUSTOM_ORDER -> compareBy<Todo>(
                { it.isDoneOn(todayStr) },
                { !hasOrder(it) },
                customOrder,
                byPriorityDesc,
                { it.createdAt }
            )
        }
    }

    fun sorted(todos: List<Todo>, todayStr: String, mode: SortMode): List<Todo> =
        todos.sortedWith(comparator(todayStr, mode))
}
