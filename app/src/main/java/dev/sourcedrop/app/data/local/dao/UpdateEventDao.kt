package dev.sourcedrop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface UpdateEventDao {

    @Query("SELECT * FROM update_events WHERE trackedAppId = :appId ORDER BY detectedAt DESC")
    fun getEventsForApp(appId: Long): Flow<List<UpdateEvent>>

    @Query("SELECT * FROM update_events WHERE trackedAppId = :appId ORDER BY detectedAt DESC")
    suspend fun getEventsForAppOnce(appId: Long): List<UpdateEvent>

    @Query("SELECT * FROM update_events WHERE trackedAppId = :appId ORDER BY detectedAt DESC LIMIT 1")
    suspend fun getLatestEventForApp(appId: Long): UpdateEvent?

    @Query("SELECT * FROM update_events WHERE trackedAppId = :appId AND detectedVersion = :version LIMIT 1")
    suspend fun getEventByVersion(appId: Long, version: String): UpdateEvent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: UpdateEvent): Long

    @Update
    suspend fun update(event: UpdateEvent)

    @Query("SELECT * FROM update_events ORDER BY detectedAt DESC")
    fun getAllEvents(): Flow<List<UpdateEvent>>
}
