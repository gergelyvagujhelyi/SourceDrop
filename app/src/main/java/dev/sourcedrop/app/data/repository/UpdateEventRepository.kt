package dev.sourcedrop.app.data.repository

import dev.sourcedrop.app.data.local.dao.UpdateEventDao
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import kotlinx.coroutines.flow.Flow

class UpdateEventRepository(private val dao: UpdateEventDao) {

    fun getEventsForApp(appId: Long): Flow<List<UpdateEvent>> = dao.getEventsForApp(appId)

    suspend fun getEventsForAppOnce(appId: Long): List<UpdateEvent> = dao.getEventsForAppOnce(appId)

    suspend fun getLatestEventForApp(appId: Long): UpdateEvent? = dao.getLatestEventForApp(appId)

    suspend fun getEventByVersion(appId: Long, version: String): UpdateEvent? =
        dao.getEventByVersion(appId, version)

    suspend fun insertEvent(event: UpdateEvent): Long = dao.insert(event)

    suspend fun updateEvent(event: UpdateEvent) = dao.update(event)

    fun getAllEvents(): Flow<List<UpdateEvent>> = dao.getAllEvents()
}
