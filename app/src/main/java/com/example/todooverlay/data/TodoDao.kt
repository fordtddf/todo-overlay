package com.example.todooverlay.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {

    /** 列表页：全部任务，不按日期过滤 —— 单次任务跨日必须留着 */
    @Query("SELECT * FROM todos ORDER BY created_at ASC")
    fun observeAll(): Flow<List<Todo>>

    /**
     * 悬浮窗用：今日未完成的每日任务 + 所有未完成的单次任务。
     * 不设上限，条目多时由卡片内部滚动展示。
     *
     * 每日任务的完成判定写在这里（last_completed_date != today），
     * 所以跨日之后不需要任何重置动作，查询结果自然就回到未完成。
     */
    @Query(
        """
        SELECT * FROM todos
        WHERE (is_daily = 1 AND (last_completed_date IS NULL OR last_completed_date != :today))
           OR (is_daily = 0 AND is_completed = 0)
        ORDER BY is_daily ASC, created_at ASC
        """
    )
    suspend fun pendingForOverlay(today: String): List<Todo>

    @Insert
    suspend fun insert(todo: Todo): Long

    @Update
    suspend fun update(todo: Todo)

    @Delete
    suspend fun delete(todo: Todo)

    /** 单次任务：直接改完成位 */
    @Query("UPDATE todos SET is_completed = :completed WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean)

    /** 每日任务：只记录完成日期，传 null 表示撤销 */
    @Query("UPDATE todos SET last_completed_date = :date WHERE id = :id")
    suspend fun setLastCompletedDate(id: Long, date: String?)
}
