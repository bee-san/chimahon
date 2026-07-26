// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import java.text.NumberFormat

class StatsMaintenanceScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { StatsMaintenanceScreenModel() }
        val state by screenModel.state.collectAsState()
        var showExportDialog by remember { mutableStateOf(false) }
        var showRawTextExportConfirmation by remember { mutableStateOf(false) }
        var showRawTextDeleteConfirmation by remember { mutableStateOf(false) }
        var showFullResetConfirmation by remember { mutableStateOf(false) }
        var showRetentionDialog by remember { mutableStateOf(false) }

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
    }
}

@Composable
private fun StatsMaintenanceContent(
    state: StatsMaintenanceState,
    paddingValues: PaddingValues,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onRetention: () -> Unit,
    onDeleteRawText: () -> Unit,
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
            MaintenanceAction(
                title = stringResource(KMR.strings.stats_delete_raw_text),
                description = stringResource(KMR.strings.stats_delete_raw_text_summary),
                enabled = !state.busy && state.rawTextDeletionPreview > 0,
                onClick = onDeleteRawText,
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
        state.error?.let { error ->
            item {
                ListItem(
                    headlineContent = { Text(stringResource(KMR.strings.stats_maintenance_error)) },
                    supportingContent = { Text(error) },
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
            Column {
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
            StatsExportKind.VOCABULARY_CSV -> KMR.strings.stats_export_vocabulary_csv
            StatsExportKind.CHARACTERS_CSV -> KMR.strings.stats_export_characters_csv
        },
    )
