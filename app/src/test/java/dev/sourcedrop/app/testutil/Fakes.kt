package dev.sourcedrop.app.testutil

import dev.sourcedrop.app.data.local.dao.TrackedAppDao
import dev.sourcedrop.app.data.local.dao.UpdateEventDao
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.updates.GitHubRateLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeTrackedAppDao : TrackedAppDao {
    private var autoId = 1L
    private val apps = MutableStateFlow<List<TrackedApp>>(emptyList())

    override fun getAll(): Flow<List<TrackedApp>> = apps.map { it.sortedBy { a -> a.displayName } }

    override fun getById(id: Long): Flow<TrackedApp?> = apps.map { list -> list.find { it.id == id } }

    override suspend fun getByIdOnce(id: Long): TrackedApp? = apps.value.find { it.id == id }

    override suspend fun insert(app: TrackedApp): Long {
        val id = autoId++
        val newApp = app.copy(id = id)
        apps.value = apps.value + newApp
        return id
    }

    override suspend fun update(app: TrackedApp) {
        apps.value = apps.value.map { if (it.id == app.id) app else it }
    }

    override suspend fun deleteById(id: Long) {
        apps.value = apps.value.filter { it.id != id }
    }

    override suspend fun getByPackageName(packageName: String): TrackedApp? =
        apps.value.find { it.packageName == packageName }

    override fun getCount(): Flow<Int> = apps.map { it.size }
}

class FakeUpdateEventDao : UpdateEventDao {
    private var autoId = 1L
    private val events = MutableStateFlow<List<UpdateEvent>>(emptyList())

    override fun getEventsForApp(appId: Long): Flow<List<UpdateEvent>> =
        events.map { list -> list.filter { it.trackedAppId == appId }.sortedByDescending { it.detectedAt } }

    override suspend fun getEventsForAppOnce(appId: Long): List<UpdateEvent> =
        events.value.filter { it.trackedAppId == appId }.sortedByDescending { it.detectedAt }

    override suspend fun getLatestEventForApp(appId: Long): UpdateEvent? =
        events.value.filter { it.trackedAppId == appId }.maxByOrNull { it.detectedAt }

    override suspend fun getEventByVersion(appId: Long, version: String): UpdateEvent? =
        events.value.find { it.trackedAppId == appId && it.detectedVersion == version }

    override suspend fun insert(event: UpdateEvent): Long {
        val id = autoId++
        events.value = events.value + event.copy(id = id)
        return id
    }

    override suspend fun update(event: UpdateEvent) {
        events.value = events.value.map { if (it.id == event.id) event else it }
    }

    override fun getAllEvents(): Flow<List<UpdateEvent>> =
        events.map { it.sortedByDescending { e -> e.detectedAt } }
}

class InMemoryRateLimitStore : GitHubRateLimit.Store {
    var state: GitHubRateLimit.State? = null
    override fun load(): GitHubRateLimit.State? = state
    override fun save(state: GitHubRateLimit.State) {
        this.state = state
    }
}
