package dev.sourcedrop.app.ui.appform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.repository.TrackedAppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppFormViewModel(
    private val repository: TrackedAppRepository,
    private val appId: Long?
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppFormUiState())
    val uiState: StateFlow<AppFormUiState> = _uiState.asStateFlow()

    private var existingApp: TrackedApp? = null

    init {
        if (appId != null && appId > 0) {
            loadApp(appId)
        }
    }

    private fun loadApp(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val app = repository.getAppByIdOnce(id)
            if (app != null) {
                existingApp = app
                _uiState.update {
                    it.copy(
                        displayName = app.displayName,
                        packageName = app.packageName,
                        sourceType = app.sourceType,
                        sourceUrl = app.sourceUrl,
                        apkUrl = app.apkUrl,
                        versionPattern = app.versionPattern,
                        assetMatchPattern = app.assetMatchPattern,
                        currentVersion = app.currentVersion,
                        checkIntervalHours = app.checkIntervalHours,
                        includePreReleases = app.includePreReleases,
                        isEditing = true,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun updateDisplayName(value: String) {
        _uiState.update { it.copy(displayName = value, errors = it.errors - "displayName") }
    }

    fun updatePackageName(value: String) {
        _uiState.update { it.copy(packageName = value) }
    }

    fun updateSourceType(value: String) {
        _uiState.update { it.copy(sourceType = value) }
    }

    fun updateSourceUrl(value: String) {
        _uiState.update { it.copy(sourceUrl = value, errors = it.errors - "sourceUrl") }
    }

    fun updateApkUrl(value: String) {
        _uiState.update { it.copy(apkUrl = value) }
    }

    fun updateVersionPattern(value: String) {
        _uiState.update { it.copy(versionPattern = value) }
    }

    fun updateAssetMatchPattern(value: String) {
        _uiState.update { it.copy(assetMatchPattern = value) }
    }

    fun updateCurrentVersion(value: String) {
        _uiState.update { it.copy(currentVersion = value) }
    }

    fun updateCheckIntervalHours(value: Int) {
        _uiState.update { it.copy(checkIntervalHours = value.coerceIn(1, 168)) }
    }

    fun updateIncludePreReleases(value: Boolean) {
        _uiState.update { it.copy(includePreReleases = value) }
    }

    fun save() {
        val state = _uiState.value
        val errors = validate(state)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val app = existingApp?.copy(
                displayName = state.displayName.trim(),
                packageName = state.packageName.trim(),
                sourceType = state.sourceType,
                sourceUrl = state.sourceUrl.trim(),
                apkUrl = state.apkUrl.trim(),
                versionPattern = state.versionPattern.trim(),
                assetMatchPattern = state.assetMatchPattern.trim(),
                currentVersion = state.currentVersion.trim(),
                checkIntervalHours = state.checkIntervalHours,
                includePreReleases = state.includePreReleases,
                updatedAt = now
            ) ?: TrackedApp(
                displayName = state.displayName.trim(),
                packageName = state.packageName.trim(),
                sourceType = state.sourceType,
                sourceUrl = state.sourceUrl.trim(),
                apkUrl = state.apkUrl.trim(),
                versionPattern = state.versionPattern.trim(),
                assetMatchPattern = state.assetMatchPattern.trim(),
                currentVersion = state.currentVersion.trim(),
                checkIntervalHours = state.checkIntervalHours,
                includePreReleases = state.includePreReleases,
                createdAt = now,
                updatedAt = now
            )

            if (existingApp != null) {
                repository.updateApp(app)
            } else {
                repository.insertApp(app)
            }
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    private fun validate(state: AppFormUiState): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        if (state.displayName.isBlank()) {
            errors["displayName"] = "App name is required"
        }
        if (state.sourceUrl.isBlank()) {
            errors["sourceUrl"] = "Source URL is required"
        } else if (!isValidUrl(state.sourceUrl.trim())) {
            errors["sourceUrl"] = "Invalid URL format"
        }
        if (state.apkUrl.isNotBlank() && !isValidUrl(state.apkUrl.trim())) {
            errors["apkUrl"] = "Invalid URL format"
        }
        return errors
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            uri.scheme in listOf("http", "https") && uri.host != null
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun factory(repository: TrackedAppRepository, appId: Long?): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppFormViewModel(repository, appId) as T
                }
            }
        }
    }
}
