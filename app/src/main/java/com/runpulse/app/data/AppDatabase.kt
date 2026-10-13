package com.runpulse.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库单例。
 *
 * 版本历史：
 * - v1: 初始版本（track_points, sessions）
 * - v2: 新增 coach_messages 表
 * - v3: track_points 新增 cadence 字段（步频）
 * - v4: sessions 新增 isPaused / totalPausedMs 字段（暂停功能）
 *
 * 注意：已移除 fallbackToDestructiveMigration()，所有版本升级必须提供显式 Migration。
 */
@Database(
    entities = [TrackPointEntity::class, SessionEntity::class, CoachMessageEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackingDao(): TrackingDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v2 -> v3: 为 track_points 表添加 cadence 字段
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE track_points ADD COLUMN cadence INTEGER DEFAULT NULL"
                )
            }
        }

        /**
         * v3 -> v4: 为 sessions 表添加暂停功能字段
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE sessions ADD COLUMN isPaused INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE sessions ADD COLUMN totalPausedMs INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "runpulse.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }
        }
    }
}
