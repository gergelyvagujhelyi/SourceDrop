package dev.sourcedrop.app.ui.applist

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sourcedrop.app.R
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.util.VersionComparator

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
            title = { Text(stringResource(R.string.delete_app_title, app.displayName)) },
            text = {
                Text(
                    when {
                        app.packageName.isNotBlank() && isInstalled ->
                            stringResource(R.string.delete_app_installed, app.packageName)
                        app.packageName.isNotBlank() && !isInstalled ->
                            stringResource(R.string.delete_app_not_installed, app.packageName)
                        else ->
                            stringResource(R.string.delete_app_no_package)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text(
                        if (isInstalled) stringResource(R.string.uninstall_and_delete) else stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon — refresh on activity resume so icons appear after install
            val context = LocalContext.current
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            var iconRefreshKey by remember { mutableStateOf(0) }
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) iconRefreshKey++
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val appIconBitmap = remember(app.packageName, iconRefreshKey) {
                if (app.packageName.isNotBlank()) {
                    try {
                        val drawable = context.packageManager.getApplicationIcon(app.packageName)
                        if (drawable is BitmapDrawable) {
                            drawable.bitmap
                        } else {
                            val bmp = android.graphics.Bitmap.createBitmap(
                                drawable.intrinsicWidth, drawable.intrinsicHeight,
                                android.graphics.Bitmap.Config.ARGB_8888
                            )
                            val canvas = android.graphics.Canvas(bmp)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                            bmp
                        }
                    } catch (_: Exception) { null }
                } else null
            }
            if (appIconBitmap != null) {
                Image(
                    painter = BitmapPainter(appIconBitmap.asImageBitmap()),
                    contentDescription = app.displayName,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.displayName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name, package, versions, and source
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.packageName.isNotBlank()) {
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = app.currentVersion.ifBlank { "\u2014" } + " \u2192 " + app.latestKnownVersion.ifBlank { "\u2014" },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasUpdate) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = sourceTypeLabel(app.sourceType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status icons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasUpdate) {
                    Icon(
                        imageVector = Icons.Default.NewReleases,
                        contentDescription = stringResource(R.string.update_available),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (app.latestKnownVersion.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.up_to_date),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { showDeleteDialog = true }
                )
            }
        }
    }
}

@Composable
private fun sourceTypeLabel(type: String): String = when (type) {
    TrackedApp.SOURCE_TYPE_GITHUB -> stringResource(R.string.source_type_github)
    TrackedApp.SOURCE_TYPE_GITLAB -> stringResource(R.string.source_type_gitlab)
    TrackedApp.SOURCE_TYPE_JSON -> stringResource(R.string.source_type_json)
    TrackedApp.SOURCE_TYPE_DIRECT_APK -> stringResource(R.string.source_type_direct_apk)
    TrackedApp.SOURCE_TYPE_HTML -> stringResource(R.string.source_type_html)
    else -> type
}
