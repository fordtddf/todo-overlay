package com.example.todooverlay.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Todo::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun todoDao(): TodoDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2：引入「每日提醒」概念。
         *
         * 旧表的 `date` / `done` 两个字段被 is_daily / last_completed_date / is_completed 取代，
         * SQLite 3.35 以下不支持 DROP COLUMN（minSdk 26 覆盖到更老的 SQLite），
         * 所以走「建新表 → 搬数据 → 换名」。
         *
         * 旧数据全部视为单次任务，完成状态从 done 迁移到 is_completed。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `todos_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `is_daily` INTEGER NOT NULL,
                        `last_completed_date` TEXT,
                        `is_completed` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `todos_new`
                        (`id`, `title`, `is_daily`, `last_completed_date`, `is_completed`, `created_at`)
                    SELECT `id`, `title`, 0, NULL, `done`, `createdAt` FROM `todos`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `todos`")
                db.execSQL("ALTER TABLE `todos_new` RENAME TO `todos`")
            }
        }

        /**
         * v2 → v3：增加自定义排序权重 order_index。
         *
         * 老数据没有权重概念，直接拿 created_at 当初始值，
         * 排序结果与升级前保持一致（先建的排前面）。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `todos_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `is_daily` INTEGER NOT NULL,
                        `last_completed_date` TEXT,
                        `is_completed` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `order_index` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `todos_new`
                        (`id`, `title`, `is_daily`, `last_completed_date`,
                         `is_completed`, `created_at`, `order_index`)
                    SELECT `id`, `title`, `is_daily`, `last_completed_date`,
                           `is_completed`, `created_at`, `created_at`
                    FROM `todos`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `todos`")
                db.execSQL("ALTER TABLE `todos_new` RENAME TO `todos`")
            }
        }

        /**
         * v3 → v4：增加重要性 priority 与用户自选序号 custom_order。
         * 老任务一律视为「中」优先级、未填序号，排序行为保持不变。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `todos_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `is_daily` INTEGER NOT NULL,
                        `last_completed_date` TEXT,
                        `is_completed` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `order_index` INTEGER NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `custom_order` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `todos_new`
                        (`id`, `title`, `is_daily`, `last_completed_date`, `is_completed`,
                         `created_at`, `order_index`, `priority`, `custom_order`)
                    SELECT `id`, `title`, `is_daily`, `last_completed_date`, `is_completed`,
                           `created_at`, `order_index`, 1, NULL
                    FROM `todos`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `todos`")
                db.execSQL("ALTER TABLE `todos_new` RENAME TO `todos`")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "todo.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
