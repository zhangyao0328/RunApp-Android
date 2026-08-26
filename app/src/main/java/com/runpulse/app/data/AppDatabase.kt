package com.runpulse.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room 数据库单例。
 *
 * v2 变更：新增 coach_messages 表。
 * 注意：当前使用 fallbackToDestructiveMigration()——1.0 阶段允许升级时清库重建，
 * 正式发布前必须为每次 schema 变更编写显式 Migration，避免用户轨迹数据丢失。
 */
@Database(
    entities = [TrackPointEntity::class, SessionEntity::class, CoachMessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackingDao(): TrackingDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "runpulse.db"
                )
                    // TODO(发布前): 删除此行并补齐 Migration，1.0 阶段允许清库
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
