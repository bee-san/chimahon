// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.ImmersionTitleMutation
import tachiyomi.domain.immersion.model.ImmersionTitleMutationBlocker
import tachiyomi.domain.immersion.model.ImmersionTitleMutationPreview
import tachiyomi.domain.immersion.model.ImmersionTitleMutationType
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

class StatsTitleMaintenanceScreen(
    private val titleId: String,
) : Screen() {
    @Composable
    override fun Content() {
        StatsRecentsPrivacy()
        val navigator = LocalNavigator.currentOrThrow
        val parsedTitleId = remember(titleId) { TitleId(titleId) }
        val screenModel = rememberScreenModel {
            StatsTitleMaintenanceScreenModel(parsedTitleId)
        }
        val state by screenModel.state.collectAsState()
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.stats_title_maintenance),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            StatsTitleMaintenanceContent(
                state = state,
                paddingValues = paddingValues,
                onRefresh = screenModel::refresh,
                onPreviewRename = screenModel::previewRename,
                onPreviewMerge = screenModel::previewMerge,
                onPreviewSplit = screenModel::previewSplit,
                onClearPreview = screenModel::clearPreview,
                onApplyPreview = screenModel::applyPreview,
                onRollback = screenModel::rollback,
            )
        }
    }
}

@Composable
private fun StatsTitleMaintenanceContent(
    state: StatsTitleMaintenanceState,
    paddingValues: PaddingValues,
    onRefresh: () -> Unit,
    onPreviewRename: (String) -> Unit,
    onPreviewMerge: (TitleId?) -> Unit,
    onPreviewSplit: (String, String, String) -> Unit,
    onClearPreview: () -> Unit,
    onApplyPreview: () -> Unit,
    onRollback: (String) -> Unit,
) {
    var renameTitle by remember { mutableStateOf("") }
    var splitTitle by remember { mutableStateOf("") }
    var splitStart by remember { mutableStateOf("") }
    var splitEnd by remember { mutableStateOf("") }
    var targetQuery by remember { mutableStateOf("") }
    var mergeTarget by remember { mutableStateOf<AnalyticsTitleRow?>(null) }
    var rollbackOperation by remember { mutableStateOf<ImmersionTitleMutation?>(null) }
    LaunchedEffect(state.title?.titleId) {
        state.title?.let { title ->
            if (renameTitle.isBlank()) renameTitle = title.displayTitle
            if (splitTitle.isBlank()) splitTitle = title.displayTitle
            if (splitStart.isBlank()) splitStart = title.firstActiveDate.toString()
            if (splitEnd.isBlank()) splitEnd = title.lastActiveDate.toString()
        }
    }
    val matchingTargets = remember(state.targets, targetQuery) {
        state.targets.asSequence()
            .filter {
                targetQuery.isBlank() ||
                    it.displayTitle.contains(targetQuery, ignoreCase = true) ||
                    it.sourceKey.contains(targetQuery, ignoreCase = true)
            }
            .take(TARGET_SEARCH_LIMIT)
            .toList()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ListItem(
                headlineContent = {
                    Text(
                        state.title?.displayTitle
                            ?: stringResource(KMR.strings.stats_loading),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                supportingContent = { state.title?.let { Text(it.sourceKey) } },
                trailingContent = {
                    TextButton(onClick = onRefresh, enabled = !state.busy) {
                        Text(stringResource(KMR.strings.stats_refresh))
                    }
                },
            )
        }
        item { HorizontalDivider() }
        item {
            EditorHeading(Icons.Outlined.Edit, stringResource(KMR.strings.stats_title_rename))
        }
        item {
            MaintenanceField(
                value = renameTitle,
                onValueChange = { renameTitle = it },
                label = stringResource(KMR.strings.stats_title_display_name),
            )
        }
        item {
            PreviewButton(!state.busy) { onPreviewRename(renameTitle) }
        }
        item { HorizontalDivider() }
        item {
            EditorHeading(
                Icons.AutoMirrored.Outlined.MergeType,
                stringResource(KMR.strings.stats_title_merge),
            )
        }
        item {
            MaintenanceField(
                value = targetQuery,
                onValueChange = {
                    targetQuery = it
                    mergeTarget = null
                },
                label = stringResource(KMR.strings.stats_title_merge_target),
            )
        }
        if (mergeTarget == null && targetQuery.isNotBlank()) {
            items(matchingTargets, key = { "target:${it.titleId.value}" }) { target ->
                TextButton(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    onClick = {
                        mergeTarget = target
                        targetQuery = target.displayTitle
                    },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            target.displayTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            target.sourceKey,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            PreviewButton(!state.busy) { onPreviewMerge(mergeTarget?.titleId) }
        }
        item { HorizontalDivider() }
        item {
            EditorHeading(
                Icons.AutoMirrored.Outlined.CallSplit,
                stringResource(KMR.strings.stats_title_split),
            )
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = splitTitle,
                    onValueChange = { splitTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(KMR.strings.stats_title_split_name)) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = splitStart,
                        onValueChange = { splitStart = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(KMR.strings.stats_start_date)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = splitEnd,
                        onValueChange = { splitEnd = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(KMR.strings.stats_end_date)) },
                        singleLine = true,
                    )
                }
            }
        }
        item {
            PreviewButton(!state.busy) {
                onPreviewSplit(splitTitle, splitStart, splitEnd)
            }
        }
        state.preview?.let { preview ->
            item { HorizontalDivider() }
            item {
                TitleMutationPreviewContent(
                    preview = preview,
                    busy = state.busy,
                    onDismiss = onClearPreview,
                    onApply = onApplyPreview,
                )
            }
        }
        state.error?.let { error ->
            item {
                Text(
                    stringResource(
                        when (error) {
                            StatsTitleMaintenanceError.INVALID_INPUT ->
                                KMR.strings.stats_title_mutation_invalid
                            StatsTitleMaintenanceError.OPERATION_FAILED ->
                                KMR.strings.stats_title_mutation_failed
                        },
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (state.busy) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    CircularProgressIndicator()
                }
            }
        }
        item { HorizontalDivider() }
        item {
            Text(
                stringResource(KMR.strings.stats_title_mutation_history),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (state.mutations.isEmpty()) {
            item {
                Text(
                    stringResource(KMR.strings.stats_title_no_mutation_history),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.mutations, key = { it.id }) { mutation ->
                MutationHistoryRow(
                    mutation = mutation,
                    busy = state.busy,
                    onRollback = { rollbackOperation = mutation },
                )
            }
        }
    }

    rollbackOperation?.let { operation ->
        AlertDialog(
            onDismissRequest = { rollbackOperation = null },
            title = { Text(stringResource(KMR.strings.stats_title_rollback)) },
            text = { Text(stringResource(KMR.strings.stats_title_mutation_rebuild_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        rollbackOperation = null
                        onRollback(operation.id)
                    },
                ) {
                    Text(stringResource(KMR.strings.stats_title_rollback))
                }
            },
            dismissButton = {
                TextButton(onClick = { rollbackOperation = null }) {
                    Text(stringResource(KMR.strings.stats_close))
                }
            },
        )
    }
}

@Composable
private fun EditorHeading(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null)
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MaintenanceField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        label = { Text(label) },
        singleLine = true,
    )
}

@Composable
private fun PreviewButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Button(enabled = enabled, onClick = onClick) {
            Text(stringResource(KMR.strings.stats_title_preview_change))
        }
    }
}

@Composable
private fun TitleMutationPreviewContent(
    preview: ImmersionTitleMutationPreview,
    busy: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val number = remember { NumberFormat.getIntegerInstance() }
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(KMR.strings.stats_title_mutation_preview),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        PreviewMetric(stringResource(KMR.strings.stats_sessions), number.format(preview.sessions))
        PreviewMetric(stringResource(KMR.strings.stats_title_mutation_events), number.format(preview.events))
        PreviewMetric(stringResource(KMR.strings.stats_source_units), number.format(preview.sourceUnits))
        PreviewMetric(stringResource(KMR.strings.stats_lookups), number.format(preview.lookups))
        PreviewMetric(
            stringResource(KMR.strings.stats_title_mutation_anki_operations),
            number.format(preview.ankiOperations),
        )
        PreviewMetric(stringResource(KMR.strings.stats_title_mutation_goals), number.format(preview.goals))
        PreviewMetric(
            stringResource(KMR.strings.stats_title_mutation_conflicts),
            number.format(preview.conflictingSourceUnits),
        )
        Text(
            stringResource(
                if (preview.canApply) {
                    KMR.strings.stats_title_mutation_ready
                } else {
                    KMR.strings.stats_title_mutation_blocked
                },
            ),
            color = if (preview.canApply) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        preview.blockers.forEach { blocker ->
            Text(titleMutationBlockerLabel(blocker), color = MaterialTheme.colorScheme.error)
        }
        Text(
            stringResource(KMR.strings.stats_title_mutation_rebuild_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(KMR.strings.stats_close))
            }
            Button(onClick = onApply, enabled = preview.canApply && !busy) {
                Text(stringResource(KMR.strings.stats_title_apply_change))
            }
        }
    }
}

@Composable
private fun PreviewMetric(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MutationHistoryRow(
    mutation: ImmersionTitleMutation,
    busy: Boolean,
    onRollback: () -> Unit,
) {
    val timestamp = mutation.rolledBackAtEpochMillis ?: mutation.appliedAtEpochMillis
    val date = remember(timestamp) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    }
    ListItem(
        headlineContent = { Text(titleMutationTypeLabel(mutation.type)) },
        supportingContent = {
            Text(
                stringResource(
                    if (mutation.canRollback) {
                        KMR.strings.stats_title_mutation_applied
                    } else {
                        KMR.strings.stats_title_mutation_rolled_back
                    },
                    date,
                ),
            )
        },
        trailingContent = if (mutation.canRollback) {
            {
                TextButton(onClick = onRollback, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null)
                    Text(stringResource(KMR.strings.stats_title_rollback))
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun titleMutationTypeLabel(type: ImmersionTitleMutationType): String = when (type) {
    ImmersionTitleMutationType.RENAME -> stringResource(KMR.strings.stats_title_operation_rename)
    ImmersionTitleMutationType.MERGE -> stringResource(KMR.strings.stats_title_operation_merge)
    ImmersionTitleMutationType.RELINK -> stringResource(KMR.strings.stats_title_operation_relink)
    ImmersionTitleMutationType.SPLIT -> stringResource(KMR.strings.stats_title_operation_split)
}

@Composable
internal fun titleMutationBlockerLabel(blocker: ImmersionTitleMutationBlocker): String =
    stringResource(
        when (blocker) {
            ImmersionTitleMutationBlocker.SOURCE_NOT_FOUND ->
                KMR.strings.stats_title_block_source_missing
            ImmersionTitleMutationBlocker.TARGET_NOT_FOUND ->
                KMR.strings.stats_title_block_target_missing
            ImmersionTitleMutationBlocker.TARGET_ALREADY_EXISTS ->
                KMR.strings.stats_title_block_target_exists
            ImmersionTitleMutationBlocker.SAME_TITLE ->
                KMR.strings.stats_title_block_same_title
            ImmersionTitleMutationBlocker.INCOMPATIBLE_MEDIA ->
                KMR.strings.stats_title_block_media
            ImmersionTitleMutationBlocker.INCOMPATIBLE_PROFILE ->
                KMR.strings.stats_title_block_profile
            ImmersionTitleMutationBlocker.INCOMPATIBLE_LANGUAGE ->
                KMR.strings.stats_title_block_language
            ImmersionTitleMutationBlocker.SOURCE_IDENTITY_CONFLICT ->
                KMR.strings.stats_title_block_source_conflict
            ImmersionTitleMutationBlocker.SHARED_SOURCE_UNITS ->
                KMR.strings.stats_title_block_shared_source
            ImmersionTitleMutationBlocker.ACTIVE_ALIAS ->
                KMR.strings.stats_title_block_alias
            ImmersionTitleMutationBlocker.ACTIVE_SESSION ->
                KMR.strings.stats_title_block_active_session
            ImmersionTitleMutationBlocker.EMPTY_SELECTION ->
                KMR.strings.stats_title_block_empty_selection
            ImmersionTitleMutationBlocker.SESSION_NOT_FOUND ->
                KMR.strings.stats_title_block_session_missing
        },
    )

private const val TARGET_SEARCH_LIMIT = 20
