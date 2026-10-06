package com.example.livenotification

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


@Database(
    entities = [
        LiveNotificationRecord::class
    ],
    version = 2
)
abstract class LiveDatabase :
    RoomDatabase() {

    abstract fun dao(): LiveNotificationDao

    companion object {

        /** 版本 1 → 2：新增 payloadJson 列 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE LiveNotificationRecord " +
                            "ADD COLUMN payloadJson TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}