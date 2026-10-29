package com.example.todooverlay.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {

    @Query("SELECT * FROM todos WHERE date = :date ORDER BY done ASC, createdAt ASC")
    fun observeByDate(date: String): Flow<List<Todo>>

    /** 悬浮窗用：当天未完成的前 N 条，纯展示 */
    @Query("SELECT * FROM todos WHERE date = :date AND done = 0 ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pendingForDate(date: String, limit: Int): List<Todo>

    /** 当天未完成总数，用于卡片上提示“还有 N 项未显示” */
    @Query("SELECT COUNT(*) FROM todos WHERE date = :date AND done = 0")
    suspend fun pendingCount(date: String): Int

    @Insert
    suspend fun insert(todo: Todo): Long

    @Update
    suspend fun update(todo: Todo)

    @Delete
    suspend fun delete(todo: Todo)
}
