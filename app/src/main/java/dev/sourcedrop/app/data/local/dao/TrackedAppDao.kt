package dev.sourcedrop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.sourcedrop.app.data.local.entity.TrackedApp
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedAppDao {

    @Query("SELECT * FROM tracked_apps ORDER BY displayName ASC")
    fun getAll(): Flow<List<TrackedApp>>

    @Query("SELECT * FROM tracked_apps WHERE id = :id")
    fun getById(id: Long): Flow<TrackedApp?>

    @Query("SELECT * FROM tracked_apps WHERE id = :id")
    suspend fun getByIdOnce(id: Long): TrackedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: TrackedApp): Long

    @Update
    suspend fun update(app: TrackedApp)

    @Query("DELETE FROM tracked_apps WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tracked_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackageName(packageName: String): TrackedApp?

    @Query("SELECT COUNT(*) FROM tracked_apps")
    fun getCount(): Flow<Int>
}
