package dev.sourcedrop.app.ui.applist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.util.VersionComparator
import java.text.DateFormat
import java.util.Date

@Composable
fun AppCard(
    app: TrackedApp,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    isInstalled: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val hasUpdate = app.latestKnownVersion.isNotBlank() &&
        app.currentVersion.isNotBlank() &&
        VersionComparator.isNewer(app.currentVersion, app.latestKnownVersion)

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${app.displayName}?") },
            text = {
                Text(
                    when {
                        app.packageName.isNotBlank() && isInstalled ->
                            "This will uninstall ${app.packageName} from your device, remove downloaded APKs, and delete all update history."
                        app.packageName.isNotBlank() && !isInstalled ->
                            "${app.packageName} is not installed on this device. This will remove downloaded APKs and delete all update history."
                        else ->
                            "This will only remove the tracking record and downloaded APKs. To also uninstall the app from your device, edit it first and set the package name."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text(
                        if (isInstalled) "Uninstall & Delete" else "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (hasUpdate) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.NewReleases,
                            contentDescription = "Update available",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (app.packageName.isNotBlank()) {
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    VersionLabel(
                        label = "Current",
                        version = app.currentVersion.ifBlank { "—" }
                    )
                    VersionLabel(
                        label = "Latest",
                        version = app.latestKnownVersion.ifBlank { "—" },
                        isHighlighted = hasUpdate
                    )
                }

                if (app.lastCheckedAt > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Checked: ${formatTimestamp(app.lastCheckedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = sourceTypeLabel(app.sourceType),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VersionLabel(
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
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
            color = if (isHighlighted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(millis))
}

private fun sourceTypeLabel(type: String): String = when (type) {
    TrackedApp.SOURCE_TYPE_GITHUB -> "GitHub"
    TrackedApp.SOURCE_TYPE_GITLAB -> "GitLab"
    TrackedApp.SOURCE_TYPE_JSON -> "JSON"
    TrackedApp.SOURCE_TYPE_DIRECT_APK -> "Direct APK"
    TrackedApp.SOURCE_TYPE_HTML -> "HTML"
    else -> type
}
