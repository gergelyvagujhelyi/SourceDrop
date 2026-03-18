package dev.sourcedrop.app.sourceadapters

import dev.sourcedrop.app.data.local.entity.TrackedApp

interface SourceAdapter {
    suspend fun checkForUpdate(app: TrackedApp): AdapterResult
}
