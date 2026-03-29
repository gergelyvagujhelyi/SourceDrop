package dev.sourcedrop.app.ui.detail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sourcedrop.app.R
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.sourceadapters.ReleaseVersion
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    viewModel: AppDetailViewModel,
    onNavigateBack: () -> Unit,
    onEdit: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.checkError) {
        uiState.checkError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    LaunchedEffect(uiState.checkSuccess) {
        uiState.checkSuccess?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    if (uiState.showInstallPermissionDialog) {
        InstallPermissionDialog(
            onGrantPermission = {
                viewModel.dismissInstallPermissionDialog()
                context.startActivity(viewModel.getInstallPermissionIntent())
            },
            onDismiss = { viewModel.dismissInstallPermissionDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.app?.displayName ?: stringResource(R.string.app_details),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    uiState.app?.let { app ->
                        IconButton(onClick = { onEdit(app.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.app == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.app_not_found))
            }
        } else {
            val app = uiState.app!!
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { AppInfoCard(app) }
                item { CheckNowSection(uiState.isChecking) { viewModel.checkNow() } }

                if (uiState.hasUpdate && uiState.events.firstOrNull()?.apkUrl?.isNotBlank() == true) {
                    item {
                        FilledTonalButton(
                            onClick = { viewModel.installLatestUpdate() },
                            enabled = uiState.downloadingEventId == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.InstallMobile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    R.string.install_latest_version,
                                    uiState.app?.latestKnownVersion ?: ""
                                )
                            )
                        }
                    }
                }

                item {
                    var expanded by remember { mutableStateOf(false) }
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = !expanded
                                    if (expanded) viewModel.loadAllReleases()
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.all_versions),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                                    else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (expanded) "Collapse" else "Expand"
                            )
                        }
                        if (expanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            when {
                                uiState.isLoadingReleases -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.loading_versions),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                uiState.releasesError != null -> {
                                    Text(
                                        text = stringResource(R.string.failed_to_load_versions),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                uiState.allReleases.isEmpty() -> {
                                    Text(
                                        text = stringResource(R.string.no_versions_found),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                else -> {
                                    uiState.allReleases.forEach { release ->
                                        ReleaseVersionCard(
                                            release = release,
                                            onInstall = { viewModel.downloadRelease(release) }
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppInfoCard(app: TrackedApp) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (app.packageName.isNotBlank()) {
                InfoRow(stringResource(R.string.label_package), app.packageName)
            }
            InfoRow(stringResource(R.string.label_source), sourceTypeLabel(app.sourceType))
            InfoRow(stringResource(R.string.label_source_url), app.sourceUrl)
            if (app.apkUrl.isNotBlank()) {
                InfoRow(stringResource(R.string.label_apk_url), app.apkUrl)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                VersionBlock(stringResource(R.string.label_current), app.currentVersion.ifBlank { "\u2014" })
                VersionBlock(
                    stringResource(R.string.label_latest),
                    app.latestKnownVersion.ifBlank { "\u2014" },
                    isHighlighted = app.lastStatus == TrackedApp.STATUS_UPDATE_AVAILABLE
                )
            }

            StatusRow(app)

            if (app.lastCheckedAt > 0) {
                Text(
                    text = stringResource(R.string.last_checked, formatTimestamp(app.lastCheckedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VersionBlock(
    label: String,
    version: String,
    isHighlighted: Boolean = false
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = version,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
            color = if (isHighlighted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatusRow(app: TrackedApp) {
    val (icon, text, color) = when (app.lastStatus) {
        TrackedApp.STATUS_UPDATE_AVAILABLE -> Triple(
            Icons.Default.NewReleases, stringResource(R.string.update_available), MaterialTheme.colorScheme.primary
        )
        TrackedApp.STATUS_UP_TO_DATE -> Triple(
            Icons.Default.CheckCircle, stringResource(R.string.up_to_date), MaterialTheme.colorScheme.tertiary
        )
        TrackedApp.STATUS_ERROR -> Triple(
            Icons.Default.Error, stringResource(R.string.check_failed), MaterialTheme.colorScheme.error
        )
        else -> return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CheckNowSection(isChecking: Boolean, onCheck: () -> Unit) {
    Button(
        onClick = onCheck,
        enabled = !isChecking,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.checking))
        } else {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.check_now))
        }
    }
}

@Composable
private fun UpdateEventCard(
    event: UpdateEvent,
    isDownloading: Boolean,
    downloadProgress: Int,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "v${event.detectedVersion}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = formatTimestamp(event.detectedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (event.releaseNotes.isNotBlank()) {
                Text(
                    text = event.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Download progress bar
            if (isDownloading) {
                Column {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.downloading_percent, downloadProgress),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action buttons
            EventActionButtons(
                event = event,
                isDownloading = isDownloading,
                onDownload = onDownload,
                onInstall = onInstall,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun EventActionButtons(
    event: UpdateEvent,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            // Currently downloading
            isDownloading -> {
                Text(
                    text = stringResource(R.string.downloading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
            // Download complete — show Install + Delete
            event.downloadStatus == UpdateEvent.DOWNLOAD_COMPLETE &&
                event.localApkPath.isNotBlank() -> {
                FilledTonalButton(
                    onClick = onInstall,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.InstallMobile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        when (event.installStatus) {
                            UpdateEvent.INSTALL_STARTED -> stringResource(R.string.re_install)
                            UpdateEvent.INSTALL_COMPLETE -> stringResource(R.string.reinstall)
                            else -> stringResource(R.string.install)
                        }
                    )
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_apk),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            // APK available but not downloaded — show Download
            event.apkUrl.isNotBlank() -> {
                FilledTonalButton(
                    onClick = onDownload,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (event.downloadStatus == UpdateEvent.DOWNLOAD_FAILED) {
                            stringResource(R.string.retry_download)
                        } else {
                            stringResource(R.string.download_apk)
                        }
                    )
                }
            }
            // No APK URL
            else -> {
                Text(
                    text = stringResource(R.string.no_apk_link),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}

@Composable
private fun InstallPermissionDialog(
    onGrantPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permission_required)) },
        text = {
            Text(stringResource(R.string.install_permission_explanation))
        },
        confirmButton = {
            TextButton(onClick = onGrantPermission) {
                Text(stringResource(R.string.open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private val NIGHTLY_PATTERN = Regex(
    "nightly|dev-build|canary",
    RegexOption.IGNORE_CASE
)

@Composable
private fun ReleaseVersionCard(
    release: ReleaseVersion,
    onInstall: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isNightly = NIGHTLY_PATTERN.containsMatchIn(release.tagName)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "v${release.version}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isNightly) {
                    VersionBadge(
                        text = stringResource(R.string.nightly_suffix).trim(),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (release.isPreRelease) {
                    VersionBadge(
                        text = stringResource(R.string.pre_release_suffix).trim(),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (release.releaseNotes.isNotBlank()) {
                if (expanded) {
                    Text(
                        text = release.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = release.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (expanded && release.apkUrl.isNotBlank()) {
                FilledTonalButton(
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.install))
                }
            }
        }
    }
}

@Composable
private fun VersionBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(millis))
}

@Composable
private fun sourceTypeLabel(type: String): String = when (type) {
    TrackedApp.SOURCE_TYPE_GITHUB -> stringResource(R.string.source_type_github_release)
    TrackedApp.SOURCE_TYPE_GITLAB -> stringResource(R.string.source_type_gitlab_release)
    TrackedApp.SOURCE_TYPE_JSON -> stringResource(R.string.source_type_json_endpoint)
    TrackedApp.SOURCE_TYPE_DIRECT_APK -> stringResource(R.string.source_type_direct_apk_url)
    TrackedApp.SOURCE_TYPE_HTML -> stringResource(R.string.source_type_html_page)
    else -> type
}
