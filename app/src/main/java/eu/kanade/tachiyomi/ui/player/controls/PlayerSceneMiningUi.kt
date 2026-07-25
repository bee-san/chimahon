package eu.kanade.tachiyomi.ui.player.controls

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import chimahon.anki.AnkiMediaWarning
import eu.kanade.tachiyomi.ui.player.scene.PlayerSceneMiningProgress
import eu.kanade.tachiyomi.ui.player.scene.SceneCaptureProgress
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.core.common.i18n.stringResource as contextStringResource

@Composable
internal fun PlayerSceneMiningProgressDialog(
    progress: PlayerSceneMiningProgress,
    onCancel: () -> Unit,
) {
    if (!progress.isBusy) return

    val status = when (progress) {
        PlayerSceneMiningProgress.Idle -> return
        PlayerSceneMiningProgress.Committing -> stringResource(KMR.strings.anki_scene_committing)
        is PlayerSceneMiningProgress.GeneratingScene -> {
            when (val phase = progress.phase) {
                SceneCaptureProgress.Preparing -> stringResource(KMR.strings.anki_scene_preparing)
                SceneCaptureProgress.Extracting -> stringResource(KMR.strings.anki_scene_extracting)
                is SceneCaptureProgress.Encoding -> {
                    stringResource(
                        KMR.strings.anki_scene_encoding,
                        phase.frameIndex,
                        phase.frameCount,
                    )
                }
                SceneCaptureProgress.Muxing -> stringResource(KMR.strings.anki_scene_muxing)
                SceneCaptureProgress.Hashing -> stringResource(KMR.strings.anki_scene_hashing)
            }
        }
        PlayerSceneMiningProgress.CheckingDuplicate,
        PlayerSceneMiningProgress.PreparingStill,
        PlayerSceneMiningProgress.PreparingSentenceAudio,
        PlayerSceneMiningProgress.WaitingForCommit,
        -> stringResource(KMR.strings.anki_scene_preparing)
    }

    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            if (progress.canCancel) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(KMR.strings.anki_scene_cancel))
                }
            }
        },
        text = { Text(status) },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}

internal fun Context.showPlayerAnkiMediaWarnings(warnings: List<AnkiMediaWarning>) {
    warnings.distinct().forEach { warning ->
        when (warning) {
            is AnkiMediaWarning.UnsupportedVideo -> {
                toast(KMR.strings.anki_scene_fallback_unsupported)
            }
            AnkiMediaWarning.SceneGenerationFailed -> {
                toast(KMR.strings.anki_scene_fallback_generation)
            }
            AnkiMediaWarning.AnimatedStorageFailed -> {
                toast(KMR.strings.anki_scene_fallback_storage)
            }
            AnkiMediaWarning.StillStorageFailed -> {
                toast(KMR.strings.anki_scene_still_storage_failed)
            }
            is AnkiMediaWarning.PossibleOrphanedMedia -> {
                toast(
                    contextStringResource(
                        KMR.strings.anki_scene_possible_orphaned_media,
                        warning.count,
                    ),
                )
            }
        }
    }
}
