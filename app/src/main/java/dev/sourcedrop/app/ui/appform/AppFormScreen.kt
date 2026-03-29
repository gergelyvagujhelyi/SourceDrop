package dev.sourcedrop.app.ui.appform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sourcedrop.app.R
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

    if (uiState.isEditing) {
        EditFormScreen(viewModel = viewModel, uiState = uiState, onNavigateBack = onNavigateBack)
    } else {
        AddWizardScreen(viewModel = viewModel, uiState = uiState, onNavigateBack = onNavigateBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWizardScreen(
    viewModel: AppFormViewModel,
    uiState: AppFormUiState,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_app_step, uiState.step)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.step > 1) viewModel.previousStep() else onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        when (uiState.step) {
            1 -> StepUrl(
                uiState = uiState,
                onUrlChange = viewModel::updateSourceUrl,
                onNext = { viewModel.nextStep() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            )
            2 -> StepDetails(
                uiState = uiState,
                onNameChange = viewModel::updateDisplayName,
                onPackageChange = viewModel::updatePackageName,
                onVersionSelect = viewModel::selectVersion,
                onSave = viewModel::save,
                onSaveAndInstall = viewModel::saveAndInstall,
                onCheckInstall = viewModel::checkInstallComplete,
                onRetryInstall = viewModel::retryInstall,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun StepUrl(
    uiState: AppFormUiState,
    onUrlChange: (String) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.paste_repo_url),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.repo_url_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = uiState.sourceUrl,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.label_url)) },
            placeholder = { Text("https://github.com/owner/repo") },
            isError = uiState.errors.containsKey("sourceUrl"),
            supportingText = uiState.errors["sourceUrl"]?.let { { Text(it) } }
                ?: uiState.fetchError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onNext() }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNext,
            enabled = !uiState.isFetching,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isFetching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.next))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepDetails(
    uiState: AppFormUiState,
    onNameChange: (String) -> Unit,
    onPackageChange: (String) -> Unit,
    onVersionSelect: (Int) -> Unit,
    onSave: () -> Unit,
    onSaveAndInstall: () -> Unit,
    onCheckInstall: () -> Unit,
    onRetryInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val preReleaseSuffix = stringResource(R.string.pre_release_suffix)

    LifecycleResumeEffect(uiState.isInstalling) {
        onCheckInstall()
        onPauseOrDispose {}
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = uiState.displayName,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.label_app_name)) },
            isError = uiState.errors.containsKey("displayName"),
            supportingText = uiState.errors["displayName"]?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = uiState.packageName,
            onValueChange = onPackageChange,
            label = { Text(stringResource(R.string.label_package_name)) },
            placeholder = { Text("com.example.app") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (uiState.isAppInstalled) {
            OutlinedTextField(
                value = stringResource(R.string.version_already_installed, uiState.currentVersion),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.label_version)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (uiState.availableVersions.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            val selected = uiState.availableVersions.getOrNull(uiState.selectedVersionIndex)

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selected?.let { it.version + if (it.isPreRelease) preReleaseSuffix else "" } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_version)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    uiState.availableVersions.forEachIndexed { index, ver ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    ver.version + if (ver.isPreRelease) preReleaseSuffix else "",
                                    color = if (ver.isPreRelease) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                onVersionSelect(index)
                                expanded = false
                            }
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = uiState.currentVersion,
                onValueChange = {},
                label = { Text(stringResource(R.string.label_version)) },
                placeholder = { Text(stringResource(R.string.no_versions_found)) },
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val selectedVersion = uiState.availableVersions.getOrNull(uiState.selectedVersionIndex)
        val hasApk = selectedVersion?.apkUrl?.isNotBlank() == true
        val showInstall = !uiState.isAppInstalled && hasApk

        if (uiState.isInstalling) {
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = stringResource(R.string.installing),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onRetryInstall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.retry_install))
            }
        } else {
            Button(
                onClick = if (showInstall) onSaveAndInstall else onSave,
                enabled = !uiState.isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = stringResource(R.string.downloading_progress, uiState.downloadProgress),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Text(if (showInstall) stringResource(R.string.add_and_install) else stringResource(R.string.add_app_button))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditFormScreen(
    viewModel: AppFormViewModel,
    uiState: AppFormUiState,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_app)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.save() }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.save)
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
                    label = { Text(stringResource(R.string.label_app_name_required)) },
                    isError = uiState.errors.containsKey("displayName"),
                    supportingText = uiState.errors["displayName"]?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.packageName,
                    onValueChange = viewModel::updatePackageName,
                    label = { Text(stringResource(R.string.label_package_name)) },
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
                    label = { Text(stringResource(R.string.label_source_url_required)) },
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
                    label = { Text(stringResource(R.string.label_apk_url_optional)) },
                    placeholder = { Text(stringResource(R.string.placeholder_apk_url)) },
                    isError = uiState.errors.containsKey("apkUrl"),
                    supportingText = uiState.errors["apkUrl"]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.currentVersion,
                    onValueChange = viewModel::updateCurrentVersion,
                    label = { Text(stringResource(R.string.label_current_version)) },
                    placeholder = { Text("1.0.0") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.assetMatchPattern,
                    onValueChange = viewModel::updateAssetMatchPattern,
                    label = { Text(stringResource(R.string.label_apk_asset_pattern)) },
                    placeholder = { Text(".*universal.*\\.apk") },
                    supportingText = { Text(stringResource(R.string.hint_apk_asset_pattern)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.versionPattern,
                    onValueChange = viewModel::updateVersionPattern,
                    label = { Text(stringResource(R.string.label_version_pattern)) },
                    placeholder = { Text("v([\\d.]+)") },
                    supportingText = { Text(stringResource(R.string.hint_version_pattern)) },
                    singleLine = true,
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
                                text = stringResource(R.string.include_pre_releases),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.include_pre_releases_subtitle),
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
            label = { Text(stringResource(R.string.label_source_type)) },
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

@Composable
private fun sourceTypeDisplayName(type: String): String = when (type) {
    TrackedApp.SOURCE_TYPE_GITHUB -> stringResource(R.string.source_type_github_release)
    TrackedApp.SOURCE_TYPE_GITLAB -> stringResource(R.string.source_type_gitlab_release)
    TrackedApp.SOURCE_TYPE_JSON -> stringResource(R.string.source_type_json_endpoint)
    TrackedApp.SOURCE_TYPE_DIRECT_APK -> stringResource(R.string.source_type_direct_apk_url)
    TrackedApp.SOURCE_TYPE_HTML -> stringResource(R.string.source_type_html_page)
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
