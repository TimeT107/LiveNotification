package com.example.livenotification

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update


@Dao
interface LiveNotificationDao {

    @Insert
    suspend fun insert(record: LiveNotificationRecord)

    @Insert
    suspend fun insertAll(records: List<LiveNotificationRecord>)

    @Query("SELECT * FROM LiveNotificationRecord ORDER BY createTime DESC")
    suspend fun getAll(): List<LiveNotificationRecord>

    @Query("DELETE FROM LiveNotificationRecord")
    suspend fun deleteAll()

    @Update
    suspend fun update(record: LiveNotificationRecord)

    @Delete
    suspend fun delete(record: LiveNotificationRecord)
}