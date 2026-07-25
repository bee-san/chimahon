package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig

internal class AndroidSceneInputAcquirer(
    context: Context,
) : SceneInputAcquirer {
    private val applicationContext = context.applicationContext

    override suspend fun acquire(input: SceneVideoInputSpec): SceneInputAcquisition {
        val value = when (input.kind) {
            SceneVideoInputKind.CONTENT_URI -> {
                FFmpegKitConfig.getSafParameterForRead(
                    applicationContext,
                    Uri.parse(input.value),
                )?.takeIf(String::isNotBlank)
                    ?: return SceneInputAcquisition.Unsupported(
                        SceneCaptureUnsupportedReason.CONTENT_URI_UNAVAILABLE,
                    )
            }
            SceneVideoInputKind.LOCAL_FILE,
            SceneVideoInputKind.REMOTE_HTTP,
            -> input.value
        }
        return SceneInputAcquisition.Acquired(
            object : SceneInputLease {
                override val ffmpegValue = value

                override fun close() {
                    // FFmpegKit exposes no public SAF-token removal API. Its
                    // native SAF protocol owns the descriptor and removes the
                    // mapping when FFmpeg closes the input. Non-SAF inputs have
                    // no resource to release.
                }
            },
        )
    }
}
