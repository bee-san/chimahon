package eu.kanade.tachiyomi.ui.player.scene

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface SceneAuthenticatedMaterializationResult {
    data class Materialized(
        val input: SceneVideoInputSpec,
        val isFullyLocalHls: Boolean,
    ) : SceneAuthenticatedMaterializationResult

    data class Protected(
        val reason: SceneCaptureUnsupportedReason,
    ) : SceneAuthenticatedMaterializationResult

    data object Unavailable : SceneAuthenticatedMaterializationResult
}

internal fun interface SceneAuthenticatedInputMaterializer {
    suspend fun materialize(
        input: SceneVideoInputSpec,
        directory: File,
    ): SceneAuthenticatedMaterializationResult
}

/**
 * Adds authenticated remote acceptance without exposing request metadata to the native stack.
 */
internal class MaterializingSceneInputProtectionChecker(
    private val materializer: SceneAuthenticatedInputMaterializer,
    private val delegate: SceneInputProtectionChecker,
) : SceneInputProtectionChecker {
    override suspend fun check(
        input: SceneVideoInputSpec,
        workingDirectory: File,
    ): SceneInputProtectionResult {
        if (
            input.kind != SceneVideoInputKind.REMOTE_HTTP ||
            !SceneProtectionHeaderPolicy.hasUnsafeNativeRedirectMetadata(input)
        ) {
            return delegate.check(input, workingDirectory)
        }
        if (workingDirectory.exists() || !workingDirectory.mkdir()) {
            return SceneInputProtectionResult.Unavailable
        }
        if (!workingDirectory.isDirectory || Files.isSymbolicLink(workingDirectory.toPath())) {
            runCatching { workingDirectory.deleteRecursively() }
            return SceneInputProtectionResult.Unavailable
        }

        return try {
            when (
                val materialized = materializer.materialize(
                    input = input,
                    directory = File(workingDirectory, MATERIALIZED_DIRECTORY),
                )
            ) {
                is SceneAuthenticatedMaterializationResult.Materialized -> {
                    when (
                        val checked = delegate.check(
                            input = materialized.input,
                            workingDirectory = File(workingDirectory, VALIDATED_DIRECTORY),
                        )
                    ) {
                        is SceneInputProtectionResult.Clear -> {
                            SceneInputProtectionResult.Clear(
                                checked.input.withLocalOnlyRequestMetadata(
                                    isFullyLocalHls = materialized.isFullyLocalHls,
                                ),
                            )
                        }
                        is SceneInputProtectionResult.Protected -> {
                            runCatching { workingDirectory.deleteRecursively() }
                            checked
                        }
                        SceneInputProtectionResult.Unavailable -> {
                            runCatching { workingDirectory.deleteRecursively() }
                            SceneInputProtectionResult.Unavailable
                        }
                    }
                }
                is SceneAuthenticatedMaterializationResult.Protected -> {
                    runCatching { workingDirectory.deleteRecursively() }
                    SceneInputProtectionResult.Protected(materialized.reason)
                }
                SceneAuthenticatedMaterializationResult.Unavailable -> {
                    runCatching { workingDirectory.deleteRecursively() }
                    SceneInputProtectionResult.Unavailable
                }
            }
        } catch (e: CancellationException) {
            runCatching { workingDirectory.deleteRecursively() }
            throw e
        } catch (_: Exception) {
            runCatching { workingDirectory.deleteRecursively() }
            SceneInputProtectionResult.Unavailable
        }
    }

    private fun SceneVideoInputSpec.withLocalOnlyRequestMetadata(
        isFullyLocalHls: Boolean,
    ): SceneVideoInputSpec {
        val localOptions = inputOptions.filter {
            it.name.lowercase(Locale.ROOT) in FORMAT_NEUTRAL_OPTIONS
        }.toMutableList()
        if (isFullyLocalHls) {
            localOptions += SceneInputOption(
                name = PROTOCOL_WHITELIST_OPTION,
                value = LOCAL_ONLY_PROTOCOL_WHITELIST,
            )
        }
        return copy(
            headers = emptyList(),
            inputOptions = localOptions,
            externalAudioValue = null,
        )
    }

    private companion object {
        const val MATERIALIZED_DIRECTORY = "materialized"
        const val VALIDATED_DIRECTORY = "validated"
        const val PROTOCOL_WHITELIST_OPTION = "protocol_whitelist"
        const val LOCAL_ONLY_PROTOCOL_WHITELIST = "file"
        val FORMAT_NEUTRAL_OPTIONS = setOf("analyzeduration", "probesize")
    }
}

internal data class SceneMaterializationLimits(
    val timeoutMillis: Long = 60_000L,
    val maxManifestBytes: Long = 1024L * 1024L,
    val maxResourceBytes: Long = 256L * 1024L * 1024L,
    val maxTotalBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxResourceCount: Int = 4_096,
    val maxPlaylistCount: Int = 32,
    val maxPlaylistDepth: Int = 4,
) {
    init {
        require(timeoutMillis > 0L)
        require(maxManifestBytes > 0L)
        require(maxResourceBytes >= maxManifestBytes)
        require(maxTotalBytes >= maxResourceBytes)
        require(maxResourceCount > 0)
        require(maxPlaylistCount > 0)
        require(maxPlaylistDepth >= 0)
    }
}

internal class EagerSceneAuthenticatedInputMaterializer(
    private val fetcher: SameOriginSceneRemoteFetcher,
    private val isoBmffInspector: IsoBmffProtectionInspector = IsoBmffProtectionInspector(),
    private val limits: SceneMaterializationLimits = SceneMaterializationLimits(),
) : SceneAuthenticatedInputMaterializer {
    override suspend fun materialize(
        input: SceneVideoInputSpec,
        directory: File,
    ): SceneAuthenticatedMaterializationResult {
        if (
            input.kind != SceneVideoInputKind.REMOTE_HTTP ||
            !SceneProtectionHeaderPolicy.isSafeRemoteValue(input.value)
        ) {
            return SceneAuthenticatedMaterializationResult.Unavailable
        }

        var keepDirectory = false
        return try {
            val result = withTimeoutOrNull(limits.timeoutMillis) {
                withContext(Dispatchers.IO) {
                    if (directory.exists() || !directory.mkdir()) {
                        return@withContext SceneAuthenticatedMaterializationResult.Unavailable
                    }
                    if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
                        return@withContext SceneAuthenticatedMaterializationResult.Unavailable
                    }
                    MaterializationState(
                        input = input,
                        directory = directory,
                        fetcher = fetcher,
                        inspector = isoBmffInspector,
                        limits = limits,
                    ).materialize()
                }
            } ?: SceneAuthenticatedMaterializationResult.Unavailable
            keepDirectory = result is SceneAuthenticatedMaterializationResult.Materialized
            result
        } catch (e: ProtectedMaterializedInputException) {
            SceneAuthenticatedMaterializationResult.Protected(e.reason)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SceneAuthenticatedMaterializationResult.Unavailable
        } finally {
            if (!keepDirectory) {
                runCatching { directory.deleteRecursively() }
            }
        }
    }

    private class MaterializationState(
        private val input: SceneVideoInputSpec,
        private val directory: File,
        private val fetcher: SameOriginSceneRemoteFetcher,
        private val inspector: IsoBmffProtectionInspector,
        private val limits: SceneMaterializationLimits,
    ) {
        private val playlistNodes = mutableListOf<LocalPlaylistNode>()
        private val playlistsByUrl = mutableMapOf<String, LocalPlaylistNode>()
        private val activePlaylistUrls = mutableSetOf<String>()
        private val mediaByUrl = mutableMapOf<String, LocalMediaResource>()
        private var playlistCount = 0
        private var resourceCount = 0
        private var mediaIndex = 0
        private var sourceIndex = 0
        private var totalBytes = 0L

        suspend fun materialize(): SceneAuthenticatedMaterializationResult {
            val rootDownload = fetch(
                value = input.value,
                maximumBytes = limits.maxTotalBytes,
                prefix = "root",
            )
            val rootDocument = rootDownload.toManifestDocument(limits.maxManifestBytes)
            if (
                SceneProtectionInspection.isMpd(
                    SceneProtectionResource(
                        value = input.value,
                        kind = SceneVideoInputKind.REMOTE_HTTP,
                        depth = 0,
                    ),
                    rootDocument,
                )
            ) {
                throw UnavailableMaterializedInputException()
            }

            val rootInspection = SceneProtectionInspection.parseHls(rootDocument)
            if (rootInspection == null) {
                if (
                    SceneProtectionInspection.isAdvertisedAsHls(
                        SceneProtectionResource(
                            value = input.value,
                            kind = SceneVideoInputKind.REMOTE_HTTP,
                            depth = 0,
                        ),
                        rootDocument,
                    )
                ) {
                    throw UnavailableMaterializedInputException()
                }
                if (rootDownload.file.length() <= 0L) {
                    throw UnavailableMaterializedInputException()
                }
                val directFile = rootDownload.moveToGeneratedMedia(
                    index = mediaIndex++,
                    directory = directory,
                )
                inspectIsoBmffIfNeeded(
                    file = directFile,
                    resolvedValue = rootDownload.resolvedValue,
                    contentType = rootDownload.contentType,
                )
                return SceneAuthenticatedMaterializationResult.Materialized(
                    input = input.toLocalInput(directFile),
                    isFullyLocalHls = false,
                )
            }
            if (!rootDocument.complete) throw UnavailableMaterializedInputException()

            val rootNode = materializePlaylist(
                requestedValue = input.value,
                depth = 0,
                suppliedDownload = rootDownload,
                suppliedInspection = rootInspection,
            )
            writePlaylists()
            return SceneAuthenticatedMaterializationResult.Materialized(
                input = input.toLocalInput(File(directory, rootNode.fileName)),
                isFullyLocalHls = true,
            )
        }

        private suspend fun materializePlaylist(
            requestedValue: String,
            depth: Int,
            suppliedDownload: SceneDownloadedRemoteResource? = null,
            suppliedInspection: SceneHlsProtectionInspection? = null,
        ): LocalPlaylistNode {
            if (depth > limits.maxPlaylistDepth) throw UnavailableMaterializedInputException()
            val requestedKey = normalizedRemoteKey(requestedValue)
                ?: throw UnavailableMaterializedInputException()
            if (requestedKey in activePlaylistUrls) throw UnavailableMaterializedInputException()
            playlistsByUrl[requestedKey]?.let { return it }
            if (playlistCount >= limits.maxPlaylistCount) throw UnavailableMaterializedInputException()
            playlistCount++
            activePlaylistUrls += requestedKey
            var activeResolvedKey: String? = null

            try {
                val download = suppliedDownload ?: fetch(
                    value = requestedValue,
                    maximumBytes = limits.maxManifestBytes,
                    prefix = "playlist-source",
                )
                val document = download.toManifestDocument(limits.maxManifestBytes)
                if (!document.complete) throw UnavailableMaterializedInputException()
                if (
                    SceneProtectionInspection.isMpd(
                        SceneProtectionResource(
                            value = requestedValue,
                            kind = SceneVideoInputKind.REMOTE_HTTP,
                            depth = depth,
                        ),
                        document,
                    )
                ) {
                    throw UnavailableMaterializedInputException()
                }
                val inspection = suppliedInspection ?: SceneProtectionInspection.parseHls(document)
                    ?: throw UnavailableMaterializedInputException()
                if (inspection.malformed) throw UnavailableMaterializedInputException()
                inspection.protectedReason?.let { throw ProtectedMaterializedInputException(it) }
                validateStaticPlaylist(inspection)

                val resolvedKey = normalizedRemoteKey(download.resolvedValue)
                    ?: throw UnavailableMaterializedInputException()
                if (resolvedKey != requestedKey && resolvedKey in activePlaylistUrls) {
                    throw UnavailableMaterializedInputException()
                }
                playlistsByUrl[resolvedKey]?.let { existing ->
                    if (!existing.sourceBytes.contentEquals(document.bytes)) {
                        throw UnavailableMaterializedInputException()
                    }
                    playlistsByUrl[requestedKey] = existing
                    runCatching { download.file.delete() }
                    return existing
                }

                val node = LocalPlaylistNode(
                    fileName = "playlist_${playlistNodes.size.toString().padStart(3, '0')}.m3u8",
                    sourceBytes = document.bytes,
                )
                playlistNodes += node
                playlistsByUrl[requestedKey] = node
                playlistsByUrl[resolvedKey] = node
                if (resolvedKey != requestedKey) {
                    activePlaylistUrls += resolvedKey
                    activeResolvedKey = resolvedKey
                }

                val replacements = mutableListOf<LocalHlsReplacement>()
                for (reference in inspection.references) {
                    currentCoroutineContext().ensureActive()
                    val resolved = resolveRemoteReference(
                        baseValue = download.resolvedValue,
                        reference = reference.value,
                    ) ?: throw UnavailableMaterializedInputException()
                    val localValue = when (reference.kind) {
                        SceneHlsReferenceKind.PLAYLIST -> {
                            materializePlaylist(
                                requestedValue = resolved,
                                depth = depth + 1,
                            ).fileName
                        }
                        SceneHlsReferenceKind.MEDIA -> {
                            materializeMedia(resolved).file.name
                        }
                        SceneHlsReferenceKind.UNSAFE -> throw UnavailableMaterializedInputException()
                    }
                    replacements += LocalHlsReplacement(reference, localValue)
                }
                node.contents = rewrite(inspection.lines, replacements)
                runCatching { download.file.delete() }
                return node
            } finally {
                activePlaylistUrls -= requestedKey
                activeResolvedKey?.let(activePlaylistUrls::remove)
            }
        }

        private suspend fun materializeMedia(
            requestedValue: String,
        ): LocalMediaResource {
            val requestedKey = normalizedRemoteKey(requestedValue)
                ?: throw UnavailableMaterializedInputException()
            mediaByUrl[requestedKey]?.let { return it }

            val download = fetch(
                value = requestedValue,
                maximumBytes = limits.maxResourceBytes,
                prefix = "media-source",
            )
            val resolvedKey = normalizedRemoteKey(download.resolvedValue)
                ?: throw UnavailableMaterializedInputException()
            mediaByUrl[resolvedKey]?.let { existing ->
                mediaByUrl[requestedKey] = existing
                runCatching { download.file.delete() }
                return existing
            }
            val localFile = download.moveToGeneratedMedia(
                index = mediaIndex++,
                directory = directory,
            )
            inspectIsoBmffIfNeeded(
                file = localFile,
                resolvedValue = download.resolvedValue,
                contentType = download.contentType,
            )
            val resource = LocalMediaResource(localFile)
            mediaByUrl[requestedKey] = resource
            mediaByUrl[resolvedKey] = resource
            return resource
        }

        private suspend fun inspectIsoBmffIfNeeded(
            file: File,
            resolvedValue: String,
            contentType: String?,
        ) {
            if (!isIsoBmffMedia(resolvedValue, contentType)) return
            val source = MaterializedFileByteSource.open(file)
                ?: throw UnavailableMaterializedInputException()
            val result = try {
                inspector.inspect(source)
            } finally {
                runCatching(source::close)
            }
            when (result) {
                IsoBmffProtectionInspection.Clear -> Unit
                is IsoBmffProtectionInspection.Protected -> {
                    throw ProtectedMaterializedInputException(SceneCaptureUnsupportedReason.DRM)
                }
                is IsoBmffProtectionInspection.Indeterminate -> {
                    throw UnavailableMaterializedInputException()
                }
            }
        }

        private suspend fun fetch(
            value: String,
            maximumBytes: Long,
            prefix: String,
        ): SceneDownloadedRemoteResource {
            if (resourceCount >= limits.maxResourceCount) {
                throw UnavailableMaterializedInputException()
            }
            val remaining = limits.maxTotalBytes - totalBytes
            if (remaining <= 0L) throw UnavailableMaterializedInputException()
            val allowed = minOf(maximumBytes, remaining)
            val destination = File(
                directory,
                ".${prefix}_${(sourceIndex++).toString().padStart(4, '0')}.download",
            )
            val downloaded = fetcher.download(
                input = input,
                initialValue = value,
                destination = destination,
                maxBytes = allowed,
            ) ?: throw UnavailableMaterializedInputException()
            resourceCount++
            totalBytes += downloaded.byteCount
            return downloaded
        }

        private fun validateStaticPlaylist(
            inspection: SceneHlsProtectionInspection,
        ) {
            val upperLines = inspection.lines.map { it.uppercase(Locale.ROOT) }
            if (
                upperLines.any { line ->
                    LL_HLS_TAGS.any(line::startsWith) ||
                        line.startsWith("#EXT-X-BYTERANGE") ||
                        "BYTERANGE=" in line
                }
            ) {
                throw UnavailableMaterializedInputException()
            }
            val hasMediaSegment = inspection.references.any { reference ->
                reference.kind == SceneHlsReferenceKind.MEDIA &&
                    inspection.lines.getOrNull(reference.lineIndex)?.startsWith("#") == false
            }
            if (hasMediaSegment && upperLines.none { it.startsWith("#EXT-X-ENDLIST") }) {
                throw UnavailableMaterializedInputException()
            }
        }

        private fun resolveRemoteReference(
            baseValue: String,
            reference: String,
        ): String? {
            if (
                reference.isBlank() ||
                reference.length > MAX_REFERENCE_LENGTH ||
                reference.contains("{$") ||
                '\\' in reference ||
                reference.any { it == '\u0000' || it == '\r' || it == '\n' || it.code < 0x20 }
            ) {
                return null
            }
            val base = runCatching { URI(baseValue) }.getOrNull() ?: return null
            val resolved = runCatching { base.resolve(reference).normalize() }.getOrNull()
                ?: return null
            val value = resolved.toASCIIString()
            if (
                resolved.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
                resolved.host.isNullOrBlank() ||
                resolved.userInfo != null ||
                resolved.fragment != null ||
                !SceneProtectionHeaderPolicy.hasSameOrigin(input.value, value) ||
                (
                    base.scheme.equals("https", ignoreCase = true) &&
                        resolved.scheme.equals("http", ignoreCase = true)
                    )
            ) {
                return null
            }
            return value
        }

        private fun rewrite(
            lines: List<String>,
            replacements: List<LocalHlsReplacement>,
        ): String {
            val rewritten = lines.toMutableList()
            replacements
                .groupBy { it.reference.lineIndex }
                .forEach { (lineIndex, replacementsForLine) ->
                    var line = rewritten.getOrNull(lineIndex)
                        ?: throw UnavailableMaterializedInputException()
                    var previousStart = line.length
                    for (replacement in replacementsForLine.sortedByDescending { it.reference.valueStart }) {
                        val reference = replacement.reference
                        if (
                            reference.valueStart < 0 ||
                            reference.valueEnd > previousStart ||
                            reference.valueStart >= reference.valueEnd ||
                            '"' in replacement.value ||
                            '\r' in replacement.value ||
                            '\n' in replacement.value
                        ) {
                            throw UnavailableMaterializedInputException()
                        }
                        line = line.replaceRange(
                            startIndex = reference.valueStart,
                            endIndex = reference.valueEnd,
                            replacement = replacement.value,
                        )
                        previousStart = reference.valueStart
                    }
                    rewritten[lineIndex] = line
                }
            return rewritten.joinToString(separator = "\n", postfix = "\n")
        }

        private suspend fun writePlaylists() {
            playlistNodes.forEach { node ->
                currentCoroutineContext().ensureActive()
                val target = File(directory, node.fileName)
                writeAtomically(target, node.contents.encodeToByteArray())
            }
        }

        private fun SceneDownloadedRemoteResource.toManifestDocument(
            maxBytes: Long,
        ): SceneProtectionDocument {
            val length = file.length()
            val readSize = minOf(length, maxBytes).toInt()
            val bytes = file.inputStream().use { stream ->
                ByteArray(readSize).also { buffer ->
                    var offset = 0
                    while (offset < buffer.size) {
                        val read = stream.read(buffer, offset, buffer.size - offset)
                        if (read < 0) throw UnavailableMaterializedInputException()
                        if (read > 0) offset += read
                    }
                }
            }
            return SceneProtectionDocument(
                resolvedValue = resolvedValue,
                bytes = bytes,
                complete = length <= maxBytes,
                contentType = contentType,
            )
        }

        private fun SceneDownloadedRemoteResource.moveToGeneratedMedia(
            index: Int,
            directory: File,
        ): File {
            val target = File(
                directory,
                "media_${index.toString().padStart(4, '0')}${mediaExtension(resolvedValue, contentType)}",
            )
            Files.move(
                file.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            return target
        }

        private fun SceneVideoInputSpec.toLocalInput(file: File): SceneVideoInputSpec {
            return copy(
                value = file.absolutePath,
                kind = SceneVideoInputKind.LOCAL_FILE,
                headers = emptyList(),
                inputOptions = inputOptions.filter {
                    it.name.lowercase(Locale.ROOT) in FORMAT_NEUTRAL_OPTIONS
                },
                externalAudioValue = null,
            )
        }

        private fun normalizedRemoteKey(value: String): String? {
            val uri = runCatching { URI(value).normalize() }.getOrNull() ?: return null
            if (
                uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null ||
                uri.fragment != null ||
                !SceneProtectionHeaderPolicy.hasSameOrigin(input.value, uri.toASCIIString())
            ) {
                return null
            }
            return uri.toASCIIString()
        }

        private fun isIsoBmffMedia(
            value: String,
            contentType: String?,
        ): Boolean {
            val normalizedType = contentType?.lowercase(Locale.ROOT).orEmpty()
            if (ISO_BMFF_CONTENT_TYPES.any { it in normalizedType }) return true
            val path = runCatching { URI(value).path.orEmpty() }.getOrDefault("")
                .lowercase(Locale.ROOT)
            return ISO_BMFF_EXTENSIONS.any(path::endsWith)
        }

        private fun mediaExtension(
            value: String,
            contentType: String?,
        ): String {
            val path = runCatching { URI(value).path.orEmpty() }.getOrDefault("")
            val extension = path.substringAfterLast('/', "")
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .takeIf { it in ALLOWED_MEDIA_EXTENSIONS }
            if (extension != null) return ".$extension"
            val normalizedType = contentType?.lowercase(Locale.ROOT).orEmpty()
            return when {
                "mpegurl" in normalizedType -> ".m3u8"
                "mp2t" in normalizedType -> ".ts"
                "mp4" in normalizedType -> ".mp4"
                "webm" in normalizedType -> ".webm"
                "vtt" in normalizedType -> ".vtt"
                "aac" in normalizedType -> ".aac"
                "mpeg" in normalizedType -> ".mp3"
                "ogg" in normalizedType -> ".ogg"
                else -> ".bin"
            }
        }

        private fun writeAtomically(
            target: File,
            bytes: ByteArray,
        ) {
            val temporary = File(directory, ".${target.name}.part")
            Files.newOutputStream(
                temporary.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output ->
                output.write(bytes)
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        }

        private data class LocalPlaylistNode(
            val fileName: String,
            val sourceBytes: ByteArray,
            var contents: String = "",
        )

        private data class LocalMediaResource(
            val file: File,
        )

        private data class LocalHlsReplacement(
            val reference: SceneHlsReference,
            val value: String,
        )

        private companion object {
            const val MAX_REFERENCE_LENGTH = 8_192
            val FORMAT_NEUTRAL_OPTIONS = setOf("analyzeduration", "probesize")
            val LL_HLS_TAGS = setOf(
                "#EXT-X-PART",
                "#EXT-X-PRELOAD-HINT",
                "#EXT-X-RENDITION-REPORT",
                "#EXT-X-SERVER-CONTROL",
                "#EXT-X-SKIP",
            )
            val ISO_BMFF_CONTENT_TYPES = setOf(
                "video/mp4",
                "audio/mp4",
                "application/mp4",
                "video/quicktime",
                "iso.segment",
            )
            val ISO_BMFF_EXTENSIONS = setOf(
                ".mp4",
                ".m4s",
                ".m4v",
                ".m4a",
                ".mov",
                ".cmfv",
                ".cmfa",
            )
            val ALLOWED_MEDIA_EXTENSIONS = setOf(
                "aac",
                "ac3",
                "avi",
                "bin",
                "cmfa",
                "cmfv",
                "eac3",
                "flac",
                "jpeg",
                "jpg",
                "json",
                "m4a",
                "m4s",
                "m4v",
                "mka",
                "mkv",
                "mov",
                "mp3",
                "mp4",
                "mpeg",
                "mpg",
                "ogg",
                "opus",
                "png",
                "ts",
                "vtt",
                "wav",
                "webm",
                "webp",
            )
        }
    }
}

internal data class SceneDownloadedRemoteResource(
    val file: File,
    val resolvedValue: String,
    val contentType: String?,
    val byteCount: Long,
)

internal class SameOriginSceneRemoteFetcher(
    private val exchangeFactory: SceneRemoteExchangeFactory,
    private val maxRedirectCount: Int = 5,
) {
    init {
        require(maxRedirectCount >= 0)
    }

    suspend fun download(
        input: SceneVideoInputSpec,
        initialValue: String,
        destination: File,
        maxBytes: Long,
    ): SceneDownloadedRemoteResource? {
        if (
            maxBytes <= 0L ||
            destination.exists() ||
            !SceneProtectionHeaderPolicy.isSafeRemoteValue(initialValue) ||
            !SceneProtectionHeaderPolicy.hasSameOrigin(input.value, initialValue)
        ) {
            return null
        }
        var currentValue = initialValue
        val visited = mutableSetOf<String>()

        repeat(maxRedirectCount + 1) { redirectIndex ->
            currentCoroutineContext().ensureActive()
            if (!visited.add(currentValue)) return null
            val request = buildRequest(input, currentValue) ?: return null
            val exchange = exchangeFactory.execute(request) ?: return null
            exchange.use {
                if (it.code in REDIRECT_STATUS_CODES) {
                    if (redirectIndex >= maxRedirectCount) return null
                    val redirected = resolveRedirect(
                        rootValue = input.value,
                        currentValue = currentValue,
                        location = it.location ?: return null,
                    ) ?: return null
                    currentValue = redirected
                    return@repeat
                }
                if (it.code !in 200..299 || it.code == HTTP_PARTIAL_CONTENT) return null
                if (
                    it.contentEncoding != null &&
                    !it.contentEncoding.equals("identity", ignoreCase = true)
                ) {
                    return null
                }
                if (it.contentLength != null && it.contentLength > maxBytes) return null
                val written = writeBounded(
                    input = it.body,
                    destination = destination,
                    maxBytes = maxBytes,
                ) ?: return null
                return SceneDownloadedRemoteResource(
                    file = destination,
                    resolvedValue = currentValue,
                    contentType = it.contentType,
                    byteCount = written,
                )
            }
        }
        return null
    }

    private fun buildRequest(
        input: SceneVideoInputSpec,
        value: String,
    ): Request? {
        if (
            !SceneProtectionHeaderPolicy.isSafeRemoteValue(value) ||
            !SceneProtectionHeaderPolicy.hasSameOrigin(input.value, value)
        ) {
            return null
        }
        val builder = runCatching {
            Request.Builder()
                .url(value)
                .get()
        }.getOrNull() ?: return null
        val forwardedNames = mutableSetOf<String>()
        input.headers.forEach { (name, headerValue) ->
            val normalized = name.lowercase(Locale.ROOT)
            if (!isReservedHeader(normalized)) {
                runCatching { builder.addHeader(name, headerValue) }.getOrNull()
                    ?: return null
                forwardedNames += normalized
            }
        }
        input.inputOptions.forEach { option ->
            when (option.name.lowercase(Locale.ROOT)) {
                "referer" -> if ("referer" !in forwardedNames) {
                    builder.header("Referer", option.value)
                    forwardedNames += "referer"
                }
                "user_agent" -> if ("user-agent" !in forwardedNames) {
                    builder.header("User-Agent", option.value)
                    forwardedNames += "user-agent"
                }
            }
        }
        builder.header("Accept-Encoding", "identity")
        return builder.build()
    }

    private fun isReservedHeader(name: String): Boolean {
        return name in RESERVED_HEADERS || name.startsWith("proxy-")
    }

    private fun resolveRedirect(
        rootValue: String,
        currentValue: String,
        location: String,
    ): String? {
        if (
            location.isBlank() ||
            location.length > MAX_REDIRECT_LOCATION_LENGTH ||
            location.any { it == '\u0000' || it == '\r' || it == '\n' || it.code < 0x20 }
        ) {
            return null
        }
        val current = runCatching { URI(currentValue) }.getOrNull() ?: return null
        val redirected = runCatching { current.resolve(location).normalize() }.getOrNull()
            ?: return null
        val value = redirected.toASCIIString()
        if (
            redirected.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            redirected.host.isNullOrBlank() ||
            redirected.userInfo != null ||
            redirected.fragment != null ||
            !SceneProtectionHeaderPolicy.hasSameOrigin(rootValue, value) ||
            (
                current.scheme.equals("https", ignoreCase = true) &&
                    redirected.scheme.equals("http", ignoreCase = true)
                )
        ) {
            return null
        }
        return value
    }

    private suspend fun writeBounded(
        input: InputStream,
        destination: File,
        maxBytes: Long,
    ): Long? {
        val temporary = File(destination.parentFile, ".${destination.name}.part")
        var completed = false
        return try {
            var total = 0L
            Files.newOutputStream(
                temporary.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (total > maxBytes - read) return null
                    output.write(buffer, 0, read)
                    total += read
                }
            }
            if (total <= 0L) return null
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            completed = true
            total
        } finally {
            if (!completed) {
                runCatching { temporary.delete() }
                runCatching { destination.delete() }
            }
        }
    }

    private companion object {
        const val HTTP_PARTIAL_CONTENT = 206
        const val MAX_REDIRECT_LOCATION_LENGTH = 8_192
        val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
        val RESERVED_HEADERS = setOf(
            "accept-encoding",
            "connection",
            "content-length",
            "host",
            "if-match",
            "if-modified-since",
            "if-none-match",
            "if-range",
            "if-unmodified-since",
            "range",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )
    }
}

internal fun interface SceneRemoteExchangeFactory {
    suspend fun execute(request: Request): SceneRemoteExchange?
}

internal class OkHttpSceneRemoteExchangeFactory(
    private val client: OkHttpClient,
) : SceneRemoteExchangeFactory {
    override suspend fun execute(request: Request): SceneRemoteExchange? {
        val response = client.newCall(request).await()
        return try {
            val body = response.body
            SceneRemoteExchange(
                code = response.code,
                location = response.header("Location"),
                contentType = body.contentType()?.toString(),
                contentEncoding = response.header("Content-Encoding"),
                contentLength = body.contentLength().takeIf { it >= 0L },
                body = body.byteStream(),
                closeAction = response::close,
            )
        } catch (e: Exception) {
            response.close()
            throw e
        }
    }
}

internal class SceneRemoteExchange(
    val code: Int,
    val location: String?,
    val contentType: String?,
    val contentEncoding: String?,
    val contentLength: Long?,
    val body: InputStream,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching(body::close)
            runCatching(closeAction)
        }
    }
}

private class MaterializedFileByteSource private constructor(
    private val channel: FileChannel,
    override val length: Long,
) : IsoBmffRandomAccessByteSource {
    private val closed = AtomicBoolean(false)

    override suspend fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        byteCount: Int,
    ): Int = withContext(Dispatchers.IO) {
        if (
            closed.get() ||
            offset < 0L ||
            byteCount < 0 ||
            destinationOffset < 0 ||
            destinationOffset > destination.size - byteCount ||
            offset >= length
        ) {
            return@withContext -1
        }
        val boundedCount = minOf(byteCount.toLong(), length - offset).toInt()
        channel.read(
            ByteBuffer.wrap(destination, destinationOffset, boundedCount),
            offset,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            channel.close()
        }
    }

    companion object {
        fun open(file: File): MaterializedFileByteSource? {
            val channel = runCatching {
                FileChannel.open(file.toPath(), StandardOpenOption.READ)
            }.getOrNull() ?: return null
            val length = runCatching(channel::size).getOrNull()
            if (length == null || length < 0L) {
                channel.close()
                return null
            }
            return MaterializedFileByteSource(channel, length)
        }
    }
}

private class ProtectedMaterializedInputException(
    val reason: SceneCaptureUnsupportedReason,
) : Exception()

private class UnavailableMaterializedInputException : Exception()
