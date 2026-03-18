package dev.sourcedrop.app.ui.appform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sourcedrop.app.data.local.entity.TrackedApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFormScreen(
    viewModel: AppFormViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isEditing) "Edit App" else "Add App")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.save() }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save"
                        )
                    }
                }
            )
        }
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
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = uiState.displayName,
                    onValueChange = viewModel::updateDisplayName,
                    label = { Text("App Name *") },
                    isError = uiState.errors.containsKey("displayName"),
                    supportingText = uiState.errors["displayName"]?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.packageName,
                    onValueChange = viewModel::updatePackageName,
                    label = { Text("Package Name") },
                    placeholder = { Text("com.example.app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SourceTypeDropdown(
                    selectedType = uiState.sourceType,
                    onTypeSelected = viewModel::updateSourceType,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.sourceUrl,
                    onValueChange = viewModel::updateSourceUrl,
                    label = { Text("Source URL *") },
                    placeholder = { Text(sourceUrlPlaceholder(uiState.sourceType)) },
                    isError = uiState.errors.containsKey("sourceUrl"),
                    supportingText = uiState.errors["sourceUrl"]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.apkUrl,
                    onValueChange = viewModel::updateApkUrl,
                    label = { Text("APK URL (optional)") },
                    placeholder = { Text("Direct link to APK, if known") },
                    isError = uiState.errors.containsKey("apkUrl"),
                    supportingText = uiState.errors["apkUrl"]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.currentVersion,
                    onValueChange = viewModel::updateCurrentVersion,
                    label = { Text("Current Version") },
                    placeholder = { Text("1.0.0") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.assetMatchPattern,
                    onValueChange = viewModel::updateAssetMatchPattern,
                    label = { Text("APK Asset Pattern") },
                    placeholder = { Text(".*universal.*\\.apk") },
                    supportingText = { Text("Regex to match APK filename in release assets") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.versionPattern,
                    onValueChange = viewModel::updateVersionPattern,
                    label = { Text("Version Pattern") },
                    placeholder = { Text("v([\\d.]+)") },
                    supportingText = { Text("Regex to extract version from release tag or page") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.checkIntervalHours.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { viewModel.updateCheckIntervalHours(it) }
                    },
                    label = { Text("Check Interval (hours)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                if (uiState.sourceType in listOf(
                        TrackedApp.SOURCE_TYPE_GITHUB,
                        TrackedApp.SOURCE_TYPE_GITLAB
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Include pre-releases",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Also consider alpha, beta, and RC releases",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.includePreReleases,
                            onCheckedChange = viewModel::updateIncludePreReleases
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceTypeDropdown(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = sourceTypeDisplayName(selectedType),
            onValueChange = {},
            readOnly = true,
            label = { Text("Source Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TrackedApp.SOURCE_TYPES.forEach { type ->
                DropdownMenuItem(
                    text = { Text(sourceTypeDisplayName(type)) },
                    onClick = {
                        onTypeSelected(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun sourceTypeDisplayName(type: String): String = when (type) {
    TrackedApp.SOURCE_TYPE_GITHUB -> "GitHub Release"
    TrackedApp.SOURCE_TYPE_GITLAB -> "GitLab Release"
    TrackedApp.SOURCE_TYPE_JSON -> "JSON Endpoint"
    TrackedApp.SOURCE_TYPE_DIRECT_APK -> "Direct APK URL"
    TrackedApp.SOURCE_TYPE_HTML -> "HTML Page"
    else -> type
}

private fun sourceUrlPlaceholder(type: String): String = when (type) {
    TrackedApp.SOURCE_TYPE_GITHUB -> "https://github.com/owner/repo"
    TrackedApp.SOURCE_TYPE_GITLAB -> "https://gitlab.com/owner/repo"
    TrackedApp.SOURCE_TYPE_JSON -> "https://example.com/api/releases.json"
    TrackedApp.SOURCE_TYPE_DIRECT_APK -> "https://example.com/app.apk"
    TrackedApp.SOURCE_TYPE_HTML -> "https://example.com/releases"
    else -> "https://..."
}
