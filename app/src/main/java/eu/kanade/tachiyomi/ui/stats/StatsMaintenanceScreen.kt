package eu.kanade.tachiyomi.ui.stats

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.feature.stats.anki.AnkiInventorySyncJob
import mihon.feature.stats.goals.ImmersionGoalReminderJob
import mihon.feature.stats.indexing.ImmersionIndexJob
import tachiyomi.domain.immersion.model.ImmersionStatsDeletionScope
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import java.text.NumberFormat

class StatsMaintenanceScreen(
    private val initialTitleId: String? = null,
    private val initialAction: StatsMaintenanceInitialAction? = null,
) : Screen() {

    @Composable
    override fun Content() {
        StatsRecentsPrivacy()
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val parsedTitleId = remember(initialTitleId) {
            initialTitleId?.let { value -> runCatching { TitleId(value) }.getOrNull() }
        }
        val screenModel = rememberScreenModel {
            StatsMaintenanceScreenModel(initialTitleId = parsedTitleId)
        }
        val state by screenModel.state.collectAsState()
        var showExportDialog by remember { mutableStateOf(false) }
        var showRawTextExportConfirmation by remember { mutableStateOf(false) }
        var showRawTextDeleteConfirmation by remember { mutableStateOf(false) }
        var showFullResetConfirmation by remember { mutableStateOf(false) }
        var showRetentionDialog by remember { mutableStateOf(false) }
        var showReaderIdleTimeoutDialog by remember { mutableStateOf(false) }
        var showScopedDeletionDialog by remember { mutableStateOf(false) }
        var showAnkiCacheDeletionDialog by remember { mutableStateOf(false) }
        var initialActionHandled by remember(initialAction) { mutableStateOf(false) }

        LaunchedEffect(initialAction, state.summary) {
            val initialScopeValid = initialTitleId == null || parsedTitleId != null
            if (!initialActionHandled && initialScopeValid) {
                when (initialAction) {
                    StatsMaintenanceInitialAction.DELETE_STATS -> {
                        showScopedDeletionDialog = true
                        initialActionHandled = true
                    }
                    StatsMaintenanceInitialAction.DELETE_RAW_TEXT -> if (state.summary != null) {
                        showRawTextDeleteConfirmation = true
                        initialActionHandled = true
                    }
                    null -> initialActionHandled = true
                }
            }
        }

        LaunchedEffect(screenModel) {
            screenModel.exportDocuments.collect { document ->
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "stats_exports").also(File::mkdirs)
                        .resolve(document.fileName)
                        .also { it.writeBytes(document.bytes) }
                }
                context.startActivity(file.getUriCompat(context).toShareIntent(context, document.mimeType))
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.stats_maintenance_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            StatsMaintenanceContent(
                state = state,
                paddingValues = paddingValues,
                onRefresh = screenModel::refresh,
                onExport = { showExportDialog = true },
                onRetention = { showRetentionDialog = true },
                onDeleteRawText = { showRawTextDeleteConfirmation = true },
                onDeleteScopedStats = { showScopedDeletionDialog = true },
                onDeleteAnkiCache = { showAnkiCacheDeletionDialog = true },
                onCaptureEnabledChange = screenModel::setCaptureEnabled,
                onReaderIdleTimeout = { showReaderIdleTimeoutDialog = true },
                onIndexingEnabledChange = {
                    screenModel.setIndexingEnabled(it)
                    ImmersionIndexJob.setEnabled(context, it)
                },
                onUiEnabledChange = screenModel::setUiEnabled,
                onAnkiSyncEnabledChange = {
                    screenModel.setAnkiSyncEnabled(it)
                    AnkiInventorySyncJob.setEnabled(context, it)
                },
                onGoalsEnabledChange = {
                    screenModel.setGoalsEnabled(it)
                    if (!it) ImmersionGoalReminderJob.setEnabled(context, false)
                },
                onGoalRemindersEnabledChange = {
                    screenModel.setGoalRemindersEnabled(it)
                    ImmersionGoalReminderJob.setEnabled(context, it)
                },
                onRebuildRollups = screenModel::rebuildRollups,
                onRebuildIndex = screenModel::rebuildIndex,
                onResolveConflicts = screenModel::resolveMergeConflicts,
                onResetAllStats = { showFullResetConfirmation = true },
            )
        }

        if (showExportDialog) {
            ExportDialog(
                onDismiss = { showExportDialog = false },
                onSelect = { kind ->
                    showExportDialog = false
                    if (kind == StatsExportKind.EVENT_JSON_WITH_RAW_TEXT) {
                        showRawTextExportConfirmation = true
                    } else {
                        screenModel.export(kind)
                    }
                },
            )
        }
        if (showRawTextExportConfirmation) {
            ConfirmationDialog(
                title = stringResource(KMR.strings.stats_export_raw_text),
                message = stringResource(KMR.strings.stats_export_raw_text_warning),
                onDismiss = { showRawTextExportConfirmation = false },
                onConfirm = {
                    showRawTextExportConfirmation = false
                    screenModel.export(StatsExportKind.EVENT_JSON_WITH_RAW_TEXT)
                },
            )
        }
        if (showRawTextDeleteConfirmation) {
            ConfirmationDialog(
                title = stringResource(KMR.strings.stats_delete_raw_text),
                message = stringResource(
                    KMR.strings.stats_delete_raw_text_warning,
                    NumberFormat.getIntegerInstance().format(state.rawTextDeletionPreview),
                ),
                onDismiss = { showRawTextDeleteConfirmation = false },
                onConfirm = {
                    showRawTextDeleteConfirmation = false
                    screenModel.deleteRawText()
                },
            )
        }
        if (showRetentionDialog) {
            RetentionDialog(
                selected = state.retention,
                onDismiss = { showRetentionDialog = false },
                onSelect = {
                    screenModel.setRetention(it)
                    mihon.feature.stats.retention.ImmersionRetentionJob.start(context)
                    showRetentionDialog = false
                },
            )
        }
        if (showReaderIdleTimeoutDialog) {
            ReaderIdleTimeoutDialog(
                selectedSeconds = state.readerIdleTimeoutSeconds,
                onDismiss = { showReaderIdleTimeoutDialog = false },
                onSelect = {
                    screenModel.setReaderIdleTimeoutSeconds(it)
                    showReaderIdleTimeoutDialog = false
                },
            )
        }
        if (showFullResetConfirmation) {
            val preview = state.deletionPreview
            ConfirmationDialog(
                title = stringResource(KMR.strings.stats_reset_all),
                message = stringResource(
                    KMR.strings.stats_reset_all_warning,
                    NumberFormat.getIntegerInstance().format(preview?.sessions ?: 0),
                    NumberFormat.getIntegerInstance().format(preview?.grossCharacters ?: 0),
                ),
                onDismiss = { showFullResetConfirmation = false },
                onConfirm = {
                    showFullResetConfirmation = false
                    screenModel.resetAllStats()
                },
            )
        }
        if (showScopedDeletionDialog) {
            ScopedDeletionDialog(
                initialTitleId = initialTitleId.orEmpty(),
                preview = state.scopedDeletionPreview,
                busy = state.busy,
                onDismiss = {
                    showScopedDeletionDialog = false
                    screenModel.clearScopedStatsDeletionPreview()
                },
                onPreview = screenModel::previewScopedStatsDeletion,
                onDelete = {
                    showScopedDeletionDialog = false
                    screenModel.deletePreviewedScopedStats()
                },
            )
        }
        if (showAnkiCacheDeletionDialog) {
            AnkiCacheDeletionDialog(
                onDismiss = { showAnkiCacheDeletionDialog = false },
                onDelete = {
                    showAnkiCacheDeletionDialog = false
                    screenModel.clearAnkiCache(it)
                },
            )
        }
    }
}

enum class StatsMaintenanceInitialAction {
    DELETE_STATS,
    DELETE_RAW_TEXT,
}

@Composable
private fun StatsMaintenanceContent(
    state: StatsMaintenanceState,
    paddingValues: PaddingValues,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onRetention: () -> Unit,
    onDeleteRawText: () -> Unit,
    onDeleteScopedStats: () -> Unit,
    onDeleteAnkiCache: () -> Unit,
    onCaptureEnabledChange: (Boolean) -> Unit,
    onReaderIdleTimeout: () -> Unit,
    onIndexingEnabledChange: (Boolean) -> Unit,
    onUiEnabledChange: (Boolean) -> Unit,
    onAnkiSyncEnabledChange: (Boolean) -> Unit,
    onGoalsEnabledChange: (Boolean) -> Unit,
    onGoalRemindersEnabledChange: (Boolean) -> Unit,
    onRebuildRollups: () -> Unit,
    onRebuildIndex: () -> Unit,
    onResolveConflicts: () -> Unit,
    onResetAllStats: () -> Unit,
) {
    val context = LocalContext.current
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(KMR.strings.stats_storage_summary)) },
                supportingContent = {
                    val summary = state.summary
                    Text(
                        if (summary == null) {
                            stringResource(KMR.strings.stats_loading)
                        } else {
                            stringResource(
                                KMR.strings.stats_storage_summary_value,
                                Formatter.formatFileSize(context, summary.databaseBytes),
                                numberFormat.format(summary.sessions),
                                numberFormat.format(summary.events),
                                numberFormat.format(summary.sourceUnits),
                            )
                        },
                    )
                },
                trailingContent = {
                    TextButton(onClick = onRefresh, enabled = !state.busy) {
                        Text(stringResource(KMR.strings.stats_refresh))
                    }
                },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(KMR.strings.stats_raw_text_storage)) },
                supportingContent = {
                    val summary = state.summary
                    Text(
                        if (summary == null) {
                            stringResource(KMR.strings.stats_loading)
                        } else {
                            stringResource(
                                KMR.strings.stats_raw_text_storage_value,
                                numberFormat.format(summary.rawTextSourceUnits),
                                Formatter.formatFileSize(context, summary.rawTextBytes),
                            )
                        },
                    )
                },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(KMR.strings.stats_recents_privacy)) },
                supportingContent = {
                    Text(stringResource(KMR.strings.stats_recents_privacy_summary))
                },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(KMR.strings.stats_integrity)) },
                supportingContent = {
                    Text(
                        when (state.integrity?.isHealthy) {
                            true -> stringResource(KMR.strings.stats_integrity_healthy)
                            false -> stringResource(KMR.strings.stats_integrity_needs_repair)
                            null -> stringResource(KMR.strings.stats_loading)
                        },
                    )
                },
            )
        }
        item {
            Text(
                text = stringResource(KMR.strings.stats_maintenance_history),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            MaintenanceTimestamp(
                title = stringResource(KMR.strings.stats_last_stats_backup),
                epochMillis = state.timestamps.lastBackupAtEpochMillis,
            )
        }
        item {
            MaintenanceTimestamp(
                title = stringResource(KMR.strings.stats_last_repair),
                epochMillis = state.timestamps.lastRepairAtEpochMillis,
            )
        }
        item {
            MaintenanceTimestamp(
                title = stringResource(KMR.strings.stats_last_index),
                epochMillis = state.timestamps.lastIndexAtEpochMillis,
            )
        }
        item {
            MaintenanceTimestamp(
                title = stringResource(KMR.strings.stats_last_rollup),
                epochMillis = state.timestamps.lastRollupAtEpochMillis,
            )
        }
        item {
            MaintenanceTimestamp(
                title = stringResource(KMR.strings.stats_last_raw_text_cleanup),
                epochMillis = state.timestamps.lastCleanupAtEpochMillis,
            )
        }
        item { HorizontalDivider() }
        item {
            ListItem(
                headlineContent = { Text(stringResource(KMR.strings.stats_rollout_controls)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (state.legacyWritesEnabled) {
                                KMR.strings.stats_rollout_legacy_writes_active
                            } else {
                                KMR.strings.stats_rollout_legacy_writes_inactive
                            },
                        ),
                    )
                },
            )
        }
        item {
            SwitchPreferenceWidget(
                title = stringResource(KMR.strings.stats_capture_enabled),
                subtitle = stringResource(KMR.strings.stats_capture_enabled_summary),
                checked = state.captureEnabled,
                onCheckedChanged = onCaptureEnabledChange,
            )
        }
        item {
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_reader_idle_timeout),
                description = stringResource(
                    KMR.strings.stats_reader_idle_timeout_summary,
                    readerIdleTimeoutLabel(state.readerIdleTimeoutSeconds),
                ),
                enabled = !state.busy,
                onClick = onReaderIdleTimeout,
            )
        }
        item {
            SwitchPreferenceWidget(
                title = stringResource(KMR.strings.stats_indexing_enabled),
                subtitle = stringResource(KMR.strings.stats_indexing_enabled_summary),
                checked = state.indexingEnabled,
                onCheckedChanged = onIndexingEnabledChange,
            )
        }
        item {
            SwitchPreferenceWidget(
                title = stringResource(KMR.strings.stats_preview_toggle),
                subtitle = stringResource(KMR.strings.stats_preview_toggle_summary),
                checked = state.uiEnabled,
                onCheckedChanged = onUiEnabledChange,
            )
        }
        item {
            SwitchPreferenceWidget(
                title = stringResource(KMR.strings.stats_anki_sync_enabled),
                subtitle = stringResource(KMR.strings.stats_anki_sync_enabled_summary),
                checked = state.ankiSyncEnabled,
                onCheckedChanged = onAnkiSyncEnabledChange,
            )
        }
        item {
            SwitchPreferenceWidget(
                title = stringResource(KMR.strings.stats_goals_enabled),
                subtitle = stringResource(KMR.strings.stats_goals_enabled_summary),
                checked = state.goalsEnabled,
                onCheckedChanged = onGoalsEnabledChange,
            )
        }
        if (state.goalsEnabled) {
            item {
                SwitchPreferenceWidget(
                    title = stringResource(KMR.strings.stats_goal_reminders_enabled),
                    subtitle = stringResource(KMR.strings.stats_goal_reminders_enabled_summary),
                    checked = state.goalRemindersEnabled,
                    onCheckedChanged = onGoalRemindersEnabledChange,
                )
            }
        }
        state.summary?.quarantinedConflicts?.takeIf { it > 0 }?.let { conflicts ->
            item {
                MaintenanceAction(
                    title = stringResource(KMR.strings.stats_merge_conflicts),
                    description = stringResource(KMR.strings.stats_backup_conflicts, numberFormat.format(conflicts)),
                    enabled = !state.busy,
                    onClick = onResolveConflicts,
                )
            }
        }
        item { HorizontalDivider() }
        item {
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_export_data),
                description = stringResource(KMR.strings.stats_export_data_summary),
                enabled = !state.busy,
                onClick = onExport,
            )
        }
        item {
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_retention_policy),
                description = retentionLabel(state.retention),
                enabled = !state.busy,
                onClick = onRetention,
            )
        }
        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(KMR.strings.stats_vocabulary_exclusions))
                },
                supportingContent = {
                    Text(stringResource(KMR.strings.stats_vocabulary_exclusions_summary))
                },
            )
        }
        item {
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_delete_raw_text),
                description = stringResource(KMR.strings.stats_delete_raw_text_summary),
                enabled = !state.busy && state.rawTextDeletionPreview > 0,
                onClick = onDeleteRawText,
            )
        }
        item {
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_delete_scoped),
                description = stringResource(KMR.strings.stats_delete_scoped_summary),
                enabled = !state.busy,
                onClick = onDeleteScopedStats,
            )
        }
        item {
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_delete_anki_cache),
                description = stringResource(KMR.strings.stats_delete_anki_cache_summary),
                enabled = !state.busy,
                onClick = onDeleteAnkiCache,
            )
        }
        item {
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_rebuild_rollups),
                description = stringResource(KMR.strings.stats_rebuild_rollups_summary),
                enabled = !state.busy,
                onClick = onRebuildRollups,
            )
        }
        item {
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_rebuild_index),
                description = stringResource(KMR.strings.stats_rebuild_index_summary),
                enabled = !state.busy,
                onClick = onRebuildIndex,
            )
        }
        item {
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_reset_all),
                description = stringResource(KMR.strings.stats_reset_all_summary),
                enabled = !state.busy && (state.deletionPreview?.sessions ?: 0) > 0,
                onClick = onResetAllStats,
            )
        }
        if (state.busy) {
            item {
                Column(Modifier.padding(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(KMR.strings.stats_maintenance_working))
                }
            }
        }
        state.error?.let {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(KMR.strings.stats_maintenance_error)) },
                    supportingContent = {
                        Text(stringResource(KMR.strings.stats_maintenance_error_summary))
                    },
                )
            }
        }
        state.lastOperation?.let {
            item {
                Text(
                    text = stringResource(
                        KMR.strings.stats_maintenance_complete,
                        numberFormat.format(state.lastAffectedRows),
                    ),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MaintenanceTimestamp(
    title: String,
    epochMillis: Long?,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                if (epochMillis != null) {
                    relativeTimeSpanString(epochMillis)
                } else {
                    stringResource(KMR.strings.stats_unavailable)
                },
            )
        },
    )
}

@Composable
private fun MaintenanceAction(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Button(onClick = onClick, enabled = enabled) {
                Text(stringResource(KMR.strings.stats_open))
            }
        },
    )
}

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onSelect: (StatsExportKind) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_export_data)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StatsExportKind.entries.forEach { kind ->
                    TextButton(onClick = { onSelect(kind) }) {
                        Text(exportLabel(kind))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(KMR.strings.stats_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun ScopedDeletionDialog(
    initialTitleId: String,
    preview: tachiyomi.domain.immersion.model.ImmersionDeletionPreview?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPreview: (ImmersionStatsDeletionScope) -> Unit,
    onDelete: () -> Unit,
) {
    var input by remember(initialTitleId) {
        mutableStateOf(StatsDeletionScopeInput(titleId = initialTitleId))
    }
    val parsedScope = remember(input) { input.parseDeletionScope() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_delete_scoped)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(KMR.strings.stats_delete_scoped_warning))
                OutlinedTextField(
                    value = input.startDate,
                    onValueChange = { input = input.copy(startDate = it) },
                    label = { Text(stringResource(KMR.strings.stats_delete_start_date)) },
                    supportingText = { Text(stringResource(KMR.strings.stats_date_format_hint)) },
                    singleLine = true,
                    enabled = preview == null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.endDate,
                    onValueChange = { input = input.copy(endDate = it) },
                    label = { Text(stringResource(KMR.strings.stats_delete_end_date)) },
                    supportingText = { Text(stringResource(KMR.strings.stats_date_format_hint)) },
                    singleLine = true,
                    enabled = preview == null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.titleId,
                    onValueChange = { input = input.copy(titleId = it) },
                    label = { Text(stringResource(KMR.strings.stats_delete_title_id)) },
                    singleLine = true,
                    enabled = preview == null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(KMR.strings.stats_delete_media_kind))
                Column {
                    (listOf<MediaKind?>(null) + MediaKind.entries).forEach { mediaKind ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = input.mediaKind == mediaKind,
                                enabled = preview == null && !busy,
                                onClick = { input = input.copy(mediaKind = mediaKind) },
                            )
                            Text(
                                mediaKind?.let { mediaKindLabel(it) }
                                    ?: stringResource(KMR.strings.stats_media_all),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input.profileId,
                    onValueChange = { input = input.copy(profileId = it) },
                    label = { Text(stringResource(KMR.strings.stats_delete_profile_id)) },
                    singleLine = true,
                    enabled = preview == null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.languageTag,
                    onValueChange = { input = input.copy(languageTag = it) },
                    label = { Text(stringResource(KMR.strings.stats_delete_language_tag)) },
                    supportingText = { Text(stringResource(KMR.strings.stats_language_tag_hint)) },
                    singleLine = true,
                    enabled = preview == null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                preview?.let {
                    Text(
                        stringResource(
                            KMR.strings.stats_delete_scoped_preview,
                            NumberFormat.getIntegerInstance().format(it.sessions),
                            formatMaintenanceDuration(it.activeDurationMillis),
                            NumberFormat.getIntegerInstance().format(it.grossCharacters),
                            NumberFormat.getIntegerInstance().format(it.sourceUnits),
                            NumberFormat.getIntegerInstance().format(it.characters),
                            NumberFormat.getIntegerInstance().format(it.goals),
                        ),
                    )
                }
            }
        },
        confirmButton = {
            if (preview == null) {
                TextButton(
                    enabled = parsedScope != null && !busy,
                    onClick = { parsedScope?.let(onPreview) },
                ) {
                    Text(stringResource(KMR.strings.stats_delete_preview))
                }
            } else {
                TextButton(
                    enabled = !busy,
                    onClick = onDelete,
                ) {
                    Text(stringResource(KMR.strings.stats_delete_matching))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun AnkiCacheDeletionDialog(
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit,
) {
    var profileId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_delete_anki_cache)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(KMR.strings.stats_delete_anki_cache_warning))
                OutlinedTextField(
                    value = profileId,
                    onValueChange = { profileId = it },
                    label = { Text(stringResource(KMR.strings.stats_delete_profile_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = profileId.isNotBlank(),
                onClick = { onDelete(profileId.trim()) },
            ) {
                Text(stringResource(KMR.strings.stats_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun RetentionDialog(
    selected: RawTextRetention,
    onDismiss: () -> Unit,
    onSelect: (RawTextRetention) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_retention_policy)) },
        text = {
            Column {
                RawTextRetention.entries.forEach { retention ->
                    TextButton(onClick = { onSelect(retention) }) {
                        RadioButton(
                            selected = retention == selected,
                            onClick = null,
                        )
                        Text(retentionLabel(retention))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun ReaderIdleTimeoutDialog(
    selectedSeconds: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_reader_idle_timeout)) },
        text = {
            Column {
                STATS_READER_IDLE_TIMEOUT_SECONDS.forEach { seconds ->
                    TextButton(onClick = { onSelect(seconds) }) {
                        RadioButton(
                            selected = seconds == selectedSeconds,
                            onClick = null,
                        )
                        Text(readerIdleTimeoutLabel(seconds))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun readerIdleTimeoutLabel(seconds: Int): String {
    val display = statsReaderIdleTimeoutDisplay(seconds)
    return pluralStringResource(
        when (display.unit) {
            StatsReaderIdleTimeoutUnit.SECONDS -> KMR.plurals.stats_duration_seconds
            StatsReaderIdleTimeoutUnit.MINUTES -> KMR.plurals.stats_duration_minutes
        },
        display.amount,
        display.amount,
    )
}

@Composable
private fun retentionLabel(retention: RawTextRetention): String =
    stringResource(
        when (retention) {
            RawTextRetention.NEVER -> KMR.strings.stats_retention_never
            RawTextRetention.THIRTY_DAYS -> KMR.strings.stats_retention_thirty_days
            RawTextRetention.ONE_YEAR -> KMR.strings.stats_retention_one_year
            RawTextRetention.UNTIL_DELETED -> KMR.strings.stats_retention_until_deleted
        },
    )

@Composable
private fun exportLabel(kind: StatsExportKind): String =
    stringResource(
        when (kind) {
            StatsExportKind.AGGREGATE_JSON -> KMR.strings.stats_export_aggregate_json
            StatsExportKind.AGGREGATE_CSV -> KMR.strings.stats_export_aggregate_csv
            StatsExportKind.EVENT_JSON -> KMR.strings.stats_export_events_json
            StatsExportKind.EVENT_JSON_WITH_RAW_TEXT -> KMR.strings.stats_export_raw_text
            StatsExportKind.CHARACTERS_CSV -> KMR.strings.stats_export_characters_csv
        },
    )

@Composable
private fun formatMaintenanceDuration(millis: Long): String {
    val parts = statsDurationParts(millis)
    if (parts.lessThanSecond) {
        return stringResource(KMR.strings.stats_duration_less_than_second)
    }
    if (parts.hours == 0L && parts.minutes == 0L && parts.seconds > 0L) {
        return pluralStringResource(
            KMR.plurals.stats_duration_seconds,
            parts.seconds.toInt(),
            parts.seconds,
        )
    }
    val minuteText = pluralStringResource(
        KMR.plurals.stats_duration_minutes,
        parts.minutes.toInt(),
        parts.minutes,
    )
    if (parts.hours == 0L) return minuteText
    return stringResource(
        KMR.strings.stats_duration_hours_minutes,
        pluralStringResource(KMR.plurals.stats_duration_hours, parts.hours.toInt(), parts.hours),
        minuteText,
    )
}

@Composable
private fun mediaKindLabel(mediaKind: MediaKind): String =
    stringResource(
        when (mediaKind) {
            MediaKind.NOVEL -> KMR.strings.stats_media_novel
            MediaKind.MANGA -> KMR.strings.stats_media_manga
            MediaKind.VIDEO -> KMR.strings.stats_media_video
        },
    )
