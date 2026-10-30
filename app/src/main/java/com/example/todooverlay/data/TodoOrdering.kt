package com.example.todooverlay.data

/**
 * 待办排序的唯一来源。主界面列表和解锁卡片都走这里，避免两处规则不一致。
 *
 * 排序优先级：
 * 1. 完成状态 —— 未完成在前，已完成沉底
 * 2. 任务类型 —— 单次任务（时效紧迫）在每日提醒（循环）之前
 * 3. 权重     —— order_index 升序
 * 4. 创建时间 —— 兜底，保证结果稳定
 *
 * 「是否完成」对每日任务依赖当天日期，所以比较器必须带上 todayStr，
 * 不能写成无参的静态比较器。
 */
object TodoOrdering {

    fun comparator(todayStr: String): Comparator<Todo> =
        compareBy<Todo>(
            { it.isDoneOn(todayStr) },
            { it.isDaily },
            { it.orderIndex },
            { it.createdAt }
        )

    fun sorted(todos: List<Todo>, todayStr: String): List<Todo> =
        todos.sortedWith(comparator(todayStr))
}
