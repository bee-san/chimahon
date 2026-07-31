package eu.kanade.tachiyomi.ui.player.controls

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chimahon.anki.AnkiMediaWarning
import eu.kanade.tachiyomi.ui.player.scene.PlayerSceneMiningProgress
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun PlayerSceneMiningProgressOverlay(
    progress: PlayerSceneMiningProgress,
    onCancel: () -> Unit,
) {
    if (!progress.isBusy) return
    val status = when (progress) {
        PlayerSceneMiningProgress.Idle -> return
        PlayerSceneMiningProgress.Preparing -> stringResource(KMR.strings.anki_scene_preparing)
        PlayerSceneMiningProgress.Committing -> stringResource(KMR.strings.anki_scene_committing)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(8.dp),
                )
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                }
                .padding(start = 12.dp, end = if (progress.canCancel) 0.dp else 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Text(
                text = status,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress.canCancel) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(KMR.strings.anki_scene_cancel),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

internal fun Context.showPlayerAnkiMediaWarnings(warnings: List<AnkiMediaWarning>) {
    warnings.distinct().forEach { warning ->
        toast(
            when (warning) {
                AnkiMediaWarning.SceneGenerationFailed -> KMR.strings.anki_scene_fallback_generation
                AnkiMediaWarning.AnimatedStorageFailed -> KMR.strings.anki_scene_fallback_storage
                AnkiMediaWarning.StillStorageFailed -> KMR.strings.anki_scene_still_storage_failed
            },
        )
    }
}
