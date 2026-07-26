// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.stats.StatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import mihon.feature.stats.retention.ImmersionRetentionJob
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

class StatsScreen(
    private val titleId: String? = null,
    private val isNovel: Boolean = false,
    private val titleName: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            StatsScreenModel(titleId = titleId)
        }
        val state by screenModel.state.collectAsState()
        var showRawTextDisclosure by remember(screenModel) {
            mutableStateOf(screenModel.rawTextDisclosureRequired())
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = titleName ?: stringResource(KMR.strings.stats_immersion_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(KMR.strings.stats_refresh),
                                    icon = Icons.Outlined.Refresh,
                                    onClick = screenModel::refresh,
                                ),
                                AppBar.Action(
                                    title = stringResource(KMR.strings.stats_maintenance_title),
                                    icon = Icons.Outlined.Settings,
                                    onClick = { navigator.push(StatsMaintenanceScreen()) },
                                ),
                            ),
                        )
                    },
                )
            },
        ) { paddingValues ->
            val success = state as? StatsScreenState.Success
            if (success == null) {
                LoadingScreen()
                return@Scaffold
            }
            StatsScreenContent(
                state = success,
                paddingValues = paddingValues,
                onTabSelect = screenModel::selectTab,
                onRangeSelect = screenModel::selectRange,
                onPeriodMove = screenModel::movePeriod,
                onCustomRange = screenModel::setCustomRange,
                onMediaSelect = screenModel::selectMedia,
                onProfileSelect = screenModel::selectProfile,
                onCharacterMetricSelect = screenModel::selectCharacterMetric,
                onIncludeLegacyChange = screenModel::setIncludeLegacy,
                onIncludeRereadsChange = screenModel::setIncludeRereads,
                onTrendScaleSelect = screenModel::selectTrendScale,
                onTitleSortSelect = screenModel::selectTitleSort,
                onVocabularySortSelect = screenModel::selectVocabularySort,
                onCharacterSortSelect = screenModel::selectCharacterSort,
                onTitleSearch = screenModel::searchTitles,
                onVocabularySearch = screenModel::searchVocabulary,
                onCharacterSearch = screenModel::searchCharacters,
                onSourceSearch = screenModel::searchSources,
                onTitleSelect = screenModel::selectTitle,
                onWordSelect = screenModel::selectWord,
                onCharacterSelect = screenModel::selectCharacter,
                onSessionSelect = screenModel::selectSession,
                onSessionDelete = screenModel::deleteSession,
                onLoadMoreVocabulary = screenModel::loadMoreVocabulary,
                onLoadMoreCharacters = screenModel::loadMoreCharacters,
                onLoadMoreSessions = screenModel::loadMoreSessions,
                onCreateGoal = screenModel::createGoal,
                onArchiveGoal = screenModel::archiveGoal,
                onCheckInGoal = screenModel::checkInGoal,
            )
        }
        if (showRawTextDisclosure) {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(stringResource(KMR.strings.stats_raw_text_disclosure_title))
                },
                text = {
                    Text(stringResource(KMR.strings.stats_raw_text_disclosure_message))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            screenModel.acknowledgeRawTextDisclosure(
                                RawTextRetention.UNTIL_DELETED,
                            )
                            showRawTextDisclosure = false
                        },
                    ) {
                        Text(stringResource(KMR.strings.stats_retention_until_deleted))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            screenModel.acknowledgeRawTextDisclosure(RawTextRetention.NEVER)
                            ImmersionRetentionJob.start(context)
                            showRawTextDisclosure = false
                        },
                    ) {
                        Text(stringResource(KMR.strings.stats_retention_never))
                    }
                },
            )
        }
    }
}
