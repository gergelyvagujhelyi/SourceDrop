package dev.sourcedrop.app.data.repository

import dev.sourcedrop.app.data.local.dao.TrackedAppDao
import dev.sourcedrop.app.data.local.entity.TrackedApp
import kotlinx.coroutines.flow.Flow

class TrackedAppRepository(private val dao: TrackedAppDao) {

    fun getAllApps(): Flow<List<TrackedApp>> = dao.getAll()

    fun getAppById(id: Long): Flow<TrackedApp?> = dao.getById(id)

    suspend fun getAppByIdOnce(id: Long): TrackedApp? = dao.getByIdOnce(id)

    suspend fun insertApp(app: TrackedApp): Long = dao.insert(app)

    suspend fun updateApp(app: TrackedApp) = dao.update(app)

    suspend fun deleteApp(id: Long) = dao.deleteById(id)
}
