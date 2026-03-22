package dev.sourcedrop.app.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sourcedrop.app.R
import dev.sourcedrop.app.data.local.entity.UpdateEvent
import dev.sourcedrop.app.ui.components.EmptyState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (uiState.items.any { it.event.localApkPath.isNotBlank() }) {
                        IconButton(onClick = { viewModel.cleanupAll() }) {
                            Icon(
                                Icons.Default.CleaningServices,
                                contentDescription = stringResource(R.string.clean_up_all_apks)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.isEmpty -> {
                EmptyState(
                    title = stringResource(R.string.empty_no_downloads_title),
                    subtitle = stringResource(R.string.empty_no_downloads_subtitle),
                    modifier = Modifier.padding(innerPadding)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.items, key = { it.event.id }) { item ->
                        DownloadItemCard(
                            item = item,
                            onInstall = { viewModel.installApk(item.event) },
                            onDelete = { viewModel.deleteApk(item.event) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    onInstall: () -> Unit,
    onDelete: () -> Unit
) {
    val event = item.event

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.appName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "v${event.detectedVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = formatTimestamp(event.detectedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusChip(event)

            // Action buttons for downloaded APKs
            if (event.downloadStatus == UpdateEvent.DOWNLOAD_COMPLETE &&
                event.localApkPath.isNotBlank()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onInstall, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Default.InstallMobile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.install))
                    }
                    OutlinedButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(event: UpdateEvent) {
    val (icon, label, color) = when {
        event.installStatus == UpdateEvent.INSTALL_COMPLETE -> Triple(
            Icons.Default.InstallMobile, stringResource(R.string.status_installed),
            MaterialTheme.colorScheme.tertiary
        )
        event.installStatus == UpdateEvent.INSTALL_STARTED -> Triple(
            Icons.Default.InstallMobile, stringResource(R.string.status_install_started),
            MaterialTheme.colorScheme.secondary
        )
        event.downloadStatus == UpdateEvent.DOWNLOAD_COMPLETE -> Triple(
            Icons.Default.Download, stringResource(R.string.status_downloaded),
            MaterialTheme.colorScheme.primary
        )
        event.downloadStatus == UpdateEvent.DOWNLOAD_IN_PROGRESS -> Triple(
            Icons.Default.Download, stringResource(R.string.status_downloading),
            MaterialTheme.colorScheme.secondary
        )
        event.downloadStatus == UpdateEvent.DOWNLOAD_FAILED -> Triple(
            Icons.Default.Download, stringResource(R.string.status_download_failed),
            MaterialTheme.colorScheme.error
        )
        else -> Triple(
            Icons.Default.Download, stringResource(R.string.status_not_downloaded),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(millis))
}
