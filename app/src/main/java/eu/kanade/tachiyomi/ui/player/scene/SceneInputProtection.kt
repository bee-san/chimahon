package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class SceneProtectionDocument(
    val resolvedValue: String,
    val bytes: ByteArray,
    val complete: Boolean,
    val contentType: String?,
)

internal fun interface SceneProtectionDocumentReader {
    suspend fun read(
        resource: SceneProtectionResource,
        input: SceneVideoInputSpec,
    ): SceneProtectionDocument?

    suspend fun openIsoBmffSource(
        resource: SceneProtectionResource,
        input: SceneVideoInputSpec,
        document: SceneProtectionDocument,
    ): IsoBmffRandomAccessByteSource? = document.completeIsoBmffSourceOrNull()
}

internal data class SceneProtectionResource(
    val value: String,
    val kind: SceneVideoInputKind,
    val depth: Int,
)

/**
 * Freezes the complete, inspected HLS manifest graph before FFmpeg can open it.
 *
 * Playlist references are replaced by private local files. Segment, init,
 * subtitle-data, and other URI-bearing media references are resolved to their
 * absolute original locations. No key URI is ever fetched.
 */
internal class RecursiveSceneInputProtectionChecker(
    private val documentReader: SceneProtectionDocumentReader,
    private val isoBmffInspector: IsoBmffProtectionInspector = IsoBmffProtectionInspector(),
) : SceneInputProtectionChecker {
    override suspend fun check(
        input: SceneVideoInputSpec,
        workingDirectory: File,
    ): SceneInputProtectionResult {
        if (input.inputOptions.any { it.name.equals(PROTOCOL_WHITELIST_OPTION, ignoreCase = true) }) {
            return SceneInputProtectionResult.Unavailable
        }
        val root = SceneProtectionResource(
            value = input.value,
            kind = input.kind,
            depth = 0,
        )
        if (
            root.kind == SceneVideoInputKind.REMOTE_HTTP &&
            !SceneProtectionHeaderPolicy.isSafeRemoteValue(root.value)
        ) {
            return SceneInputProtectionResult.Unavailable
        }
        val document = readDocument(root, input)
            ?: return SceneInputProtectionResult.Unavailable
        if (
            root.kind == SceneVideoInputKind.REMOTE_HTTP &&
            !SceneProtectionHeaderPolicy.hasSameOrigin(root.value, document.resolvedValue)
        ) {
            return SceneInputProtectionResult.Unavailable
        }

        if (SceneProtectionInspection.isMpd(root, document)) {
            return SceneInputProtectionResult.Unavailable
        }

        val rootInspection = SceneProtectionInspection.parseHls(document)
        if (rootInspection == null) {
            if (SceneProtectionInspection.isAdvertisedAsHls(root, document)) {
                return SceneInputProtectionResult.Unavailable
            }
            if (
                root.kind == SceneVideoInputKind.REMOTE_HTTP &&
                SceneProtectionHeaderPolicy.hasUnsafeNativeRedirectMetadata(input)
            ) {
                return SceneInputProtectionResult.Unavailable
            }
            if (SceneProtectionInspection.isIsoBmff(root, document)) {
                val source = try {
                    documentReader.openIsoBmffSource(root, input, document)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: return SceneInputProtectionResult.Unavailable
                val inspection = try {
                    isoBmffInspector.inspect(source)
                } finally {
                    runCatching(source::close)
                }
                when (inspection) {
                    IsoBmffProtectionInspection.Clear -> Unit
                    is IsoBmffProtectionInspection.Protected -> {
                        return SceneInputProtectionResult.Protected(
                            SceneCaptureUnsupportedReason.DRM,
                        )
                    }
                    is IsoBmffProtectionInspection.Indeterminate -> {
                        return SceneInputProtectionResult.Unavailable
                    }
                }
            }
            return SceneInputProtectionResult.Clear(input)
        }
        if (SceneProtectionHeaderPolicy.hasUnsafeNativeRedirectMetadata(input)) {
            return SceneInputProtectionResult.Unavailable
        }
        if (rootInspection.malformed) {
            return SceneInputProtectionResult.Unavailable
        }
        rootInspection.protectedReason?.let {
            return SceneInputProtectionResult.Protected(it)
        }

        val freezer = HlsGraphFreezer(
            input = input,
            workingDirectory = workingDirectory,
        )
        return try {
            val rootNode = freezer.freeze(
                resource = root,
                suppliedDocument = document,
                suppliedInspection = rootInspection,
            )
            freezer.writeGraph()
            SceneInputProtectionResult.Clear(
                input.copy(
                    value = File(workingDirectory, rootNode.fileName).absolutePath,
                    kind = SceneVideoInputKind.LOCAL_FILE,
                    inputOptions = input.inputOptions + SceneInputOption(
                        name = PROTOCOL_WHITELIST_OPTION,
                        value = FROZEN_HLS_PROTOCOL_WHITELIST,
                    ),
                    externalAudioValue = null,
                ),
            )
        } catch (e: ProtectedInputException) {
            freezer.cleanUp()
            SceneInputProtectionResult.Protected(e.reason)
        } catch (e: CancellationException) {
            freezer.cleanUp()
            throw e
        } catch (_: Exception) {
            freezer.cleanUp()
            SceneInputProtectionResult.Unavailable
        }
    }

    private suspend fun readDocument(
        resource: SceneProtectionResource,
        input: SceneVideoInputSpec,
    ): SceneProtectionDocument? {
        return try {
            documentReader.read(resource, input)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private inner class HlsGraphFreezer(
        private val input: SceneVideoInputSpec,
        private val workingDirectory: File,
    ) {
        private val nodes = mutableListOf<FrozenPlaylistNode>()
        private val nodesByResource = mutableMapOf<String, FrozenPlaylistNode>()
        private var documentCount = 0
        private var totalManifestBytes = 0L
        private var ownsWorkingDirectory = false

        suspend fun freeze(
            resource: SceneProtectionResource,
            suppliedDocument: SceneProtectionDocument? = null,
            suppliedInspection: SceneHlsProtectionInspection? = null,
        ): FrozenPlaylistNode {
            if (resource.depth > MAX_PLAYLIST_DEPTH) throw UnavailableInputException()
            val requestedKey = resourceKey(resource.value, resource.kind)
                ?: throw UnavailableInputException()
            nodesByResource[requestedKey]?.let { return it }
            if (documentCount >= MAX_DOCUMENT_COUNT) throw UnavailableInputException()
            documentCount++

            val document = suppliedDocument ?: readDocument(resource, input)
                ?: throw UnavailableInputException()
            if (
                !document.complete ||
                document.bytes.size > MAX_MANIFEST_BYTES ||
                totalManifestBytes + document.bytes.size > MAX_TOTAL_MANIFEST_BYTES
            ) {
                throw UnavailableInputException()
            }
            totalManifestBytes += document.bytes.size

            if (!isSafeResolution(resource, document.resolvedValue)) {
                throw UnavailableInputException()
            }
            if (SceneProtectionInspection.isMpd(resource, document)) {
                throw UnavailableInputException()
            }
            val inspection = suppliedInspection ?: SceneProtectionInspection.parseHls(document)
                ?: throw UnavailableInputException()
            if (inspection.malformed) throw UnavailableInputException()
            inspection.protectedReason?.let { throw ProtectedInputException(it) }

            val resolvedKind = document.resolvedValue.sceneProtectionKind()
                ?: resource.kind.takeIf { it == SceneVideoInputKind.LOCAL_FILE }
                ?: throw UnavailableInputException()
            val resolvedKey = resourceKey(document.resolvedValue, resolvedKind)
                ?: throw UnavailableInputException()
            nodesByResource[resolvedKey]?.let { existing ->
                if (!existing.sourceBytes.contentEquals(document.bytes)) {
                    throw UnavailableInputException()
                }
                nodesByResource[requestedKey] = existing
                return existing
            }

            val node = FrozenPlaylistNode(
                fileName = "playlist_${nodes.size.toString().padStart(3, '0')}.m3u8",
                sourceBytes = document.bytes,
            )
            nodes += node
            nodesByResource[requestedKey] = node
            nodesByResource[resolvedKey] = node

            val replacements = mutableListOf<SceneHlsReplacement>()
            for (reference in inspection.references) {
                currentCoroutineContext().ensureActive()
                val resolved = resolveReference(
                    baseValue = document.resolvedValue,
                    baseKind = resolvedKind,
                    reference = reference.value,
                ) ?: throw UnavailableInputException()
                val replacement = when (reference.kind) {
                    SceneHlsReferenceKind.PLAYLIST -> {
                        if (resource.depth >= MAX_PLAYLIST_DEPTH) {
                            throw UnavailableInputException()
                        }
                        freeze(
                            SceneProtectionResource(
                                value = resolved.value,
                                kind = resolved.kind,
                                depth = resource.depth + 1,
                            ),
                        ).fileName
                    }
                    SceneHlsReferenceKind.MEDIA -> {
                        if (resolved.kind == SceneVideoInputKind.CONTENT_URI) {
                            // A content URI embedded inside a local rewritten
                            // playlist cannot acquire its own FFmpegKit SAF lease.
                            throw UnavailableInputException()
                        }
                        resolved.value
                    }
                    SceneHlsReferenceKind.UNSAFE -> throw UnavailableInputException()
                }
                replacements += SceneHlsReplacement(reference, replacement)
            }
            node.contents = rewrite(inspection.lines, replacements)
            return node
        }

        suspend fun writeGraph() {
            currentCoroutineContext().ensureActive()
            if (!workingDirectory.mkdir()) throw UnavailableInputException()
            ownsWorkingDirectory = true
            if (!workingDirectory.isDirectory || Files.isSymbolicLink(workingDirectory.toPath())) {
                throw UnavailableInputException()
            }
            for (node in nodes) {
                currentCoroutineContext().ensureActive()
                val target = File(workingDirectory, node.fileName)
                val temporary = File(workingDirectory, ".${node.fileName}.part")
                temporary.writeText(node.contents, Charsets.UTF_8)
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
        }

        fun cleanUp() {
            if (ownsWorkingDirectory) {
                runCatching { workingDirectory.deleteRecursively() }
                ownsWorkingDirectory = false
            }
        }

        private fun rewrite(
            lines: List<String>,
            replacements: List<SceneHlsReplacement>,
        ): String {
            val rewritten = lines.toMutableList()
            replacements
                .groupBy { it.reference.lineIndex }
                .forEach { (lineIndex, lineReplacements) ->
                    var line = rewritten.getOrNull(lineIndex)
                        ?: throw UnavailableInputException()
                    var previousStart = line.length
                    for (replacement in lineReplacements.sortedByDescending { it.reference.valueStart }) {
                        val reference = replacement.reference
                        if (
                            reference.valueStart < 0 ||
                            reference.valueEnd > previousStart ||
                            reference.valueStart >= reference.valueEnd ||
                            '"' in replacement.value ||
                            '\r' in replacement.value ||
                            '\n' in replacement.value
                        ) {
                            throw UnavailableInputException()
                        }
                        line = line.replaceRange(
                            reference.valueStart,
                            reference.valueEnd,
                            replacement.value,
                        )
                        previousStart = reference.valueStart
                    }
                    rewritten[lineIndex] = line
                }
            return rewritten.joinToString(separator = "\n", postfix = "\n")
        }

        private fun resolveReference(
            baseValue: String,
            baseKind: SceneVideoInputKind,
            reference: String,
        ): ResolvedReference? {
            if (!reference.isSafeHlsReference()) return null
            return when (baseKind) {
                SceneVideoInputKind.LOCAL_FILE -> {
                    val referenceUri = runCatching { URI(reference) }.getOrNull()
                        ?: return null
                    if (referenceUri.isAbsolute) {
                        normalizeResolvedUri(referenceUri, baseValue)
                    } else {
                        if (referenceUri.query != null || referenceUri.fragment != null) return null
                        val parent = File(baseValue).absoluteFile.parentFile ?: return null
                        val resolved = runCatching {
                            File(parent, reference).canonicalFile
                        }.getOrNull() ?: return null
                        ResolvedReference(
                            value = resolved.absolutePath,
                            kind = SceneVideoInputKind.LOCAL_FILE,
                        )
                    }
                }
                SceneVideoInputKind.CONTENT_URI,
                SceneVideoInputKind.REMOTE_HTTP,
                -> {
                    val base = runCatching { URI(baseValue) }.getOrNull() ?: return null
                    val resolved = runCatching { base.resolve(reference).normalize() }.getOrNull()
                        ?: return null
                    normalizeResolvedUri(resolved, baseValue)
                }
            }
        }

        private fun normalizeResolvedUri(
            uri: URI,
            baseValue: String,
        ): ResolvedReference? {
            if (uri.userInfo != null || uri.fragment != null) return null
            return when (uri.scheme?.lowercase(Locale.ROOT)) {
                "file" -> {
                    if (baseValue.startsWith("http", ignoreCase = true)) return null
                    val path = uri.path?.takeIf(String::isNotBlank) ?: return null
                    ResolvedReference(
                        value = File(path).canonicalFile.absolutePath,
                        kind = SceneVideoInputKind.LOCAL_FILE,
                    )
                }
                "content" -> {
                    if (baseValue.startsWith("http", ignoreCase = true)) return null
                    ResolvedReference(uri.toString(), SceneVideoInputKind.CONTENT_URI)
                }
                "http", "https" -> {
                    if (uri.host.isNullOrBlank()) return null
                    if (
                        input.kind != SceneVideoInputKind.REMOTE_HTTP ||
                        !SceneProtectionHeaderPolicy.hasSameOrigin(input.value, uri.toASCIIString())
                    ) {
                        return null
                    }
                    if (
                        baseValue.startsWith("https://", ignoreCase = true) &&
                        uri.scheme.equals("http", ignoreCase = true)
                    ) {
                        return null
                    }
                    ResolvedReference(uri.toASCIIString(), SceneVideoInputKind.REMOTE_HTTP)
                }
                else -> null
            }
        }

        private fun isSafeResolution(
            resource: SceneProtectionResource,
            resolvedValue: String,
        ): Boolean {
            val resolvedKind = resolvedValue.sceneProtectionKind() ?: return false
            if (resource.kind == SceneVideoInputKind.REMOTE_HTTP) {
                if (resolvedKind != SceneVideoInputKind.REMOTE_HTTP) return false
                if (!SceneProtectionHeaderPolicy.hasSameOrigin(input.value, resolvedValue)) return false
                if (
                    resource.value.startsWith("https://", ignoreCase = true) &&
                    resolvedValue.startsWith("http://", ignoreCase = true)
                ) {
                    return false
                }
                val uri = runCatching { URI(resolvedValue) }.getOrNull() ?: return false
                if (uri.userInfo != null || uri.fragment != null || uri.host.isNullOrBlank()) return false
            }
            return true
        }

        private fun resourceKey(
            value: String,
            kind: SceneVideoInputKind,
        ): String? {
            return when (kind) {
                SceneVideoInputKind.LOCAL_FILE -> runCatching {
                    File(value).canonicalFile.absolutePath
                }.getOrNull()
                SceneVideoInputKind.CONTENT_URI,
                SceneVideoInputKind.REMOTE_HTTP,
                -> runCatching { URI(value).normalize().toASCIIString() }.getOrNull()
            }
        }
    }

    private data class FrozenPlaylistNode(
        val fileName: String,
        val sourceBytes: ByteArray,
        var contents: String = "",
    )

    private data class ResolvedReference(
        val value: String,
        val kind: SceneVideoInputKind,
    )

    private data class SceneHlsReplacement(
        val reference: SceneHlsReference,
        val value: String,
    )

    private class ProtectedInputException(
        val reason: SceneCaptureUnsupportedReason,
    ) : Exception()

    private class UnavailableInputException : Exception()

    private fun String.isSafeHlsReference(): Boolean {
        return isNotBlank() &&
            length <= MAX_REFERENCE_LENGTH &&
            !contains("{$") &&
            '\\' !in this &&
            none { it == '\u0000' || it == '\r' || it == '\n' || it.code < 0x20 }
    }

    private fun String.sceneProtectionKind(): SceneVideoInputKind? {
        return when {
            startsWith("/") -> SceneVideoInputKind.LOCAL_FILE
            startsWith("file://", ignoreCase = true) -> SceneVideoInputKind.LOCAL_FILE
            startsWith("content://", ignoreCase = true) -> SceneVideoInputKind.CONTENT_URI
            startsWith("http://", ignoreCase = true) ||
                startsWith("https://", ignoreCase = true) -> SceneVideoInputKind.REMOTE_HTTP
            else -> null
        }
    }

    private companion object {
        const val MAX_DOCUMENT_COUNT = 32
        const val MAX_PLAYLIST_DEPTH = 4
        const val MAX_TOTAL_MANIFEST_BYTES = 4L * 1024L * 1024L
        const val MAX_REFERENCE_LENGTH = 8_192
        const val PROTOCOL_WHITELIST_OPTION = "protocol_whitelist"
        const val FROZEN_HLS_PROTOCOL_WHITELIST = "file,http,https,tcp,tls"
    }
}

internal object SceneProtectionHeaderPolicy {
    fun hasSensitiveHeaders(input: SceneVideoInputSpec): Boolean {
        return input.headers.any { (name, _) -> isSensitive(name) }
    }

    fun hasUnsafeNativeRedirectMetadata(input: SceneVideoInputSpec): Boolean {
        return hasSensitiveHeaders(input) ||
            input.headers.any { (name, _) ->
                name.equals("referer", ignoreCase = true) ||
                    name.equals("origin", ignoreCase = true)
            } ||
            SceneInputOriginPolicy.hasReferer(input.inputOptions)
    }

    fun headersForRequest(
        input: SceneVideoInputSpec,
        targetValue: String,
        redirectChainStayedOnRootOrigin: Boolean,
    ): List<Pair<String, String>> {
        val allowSensitive = redirectChainStayedOnRootOrigin &&
            hasSameOrigin(input.value, targetValue)
        return input.headers.filter { (name, _) ->
            allowSensitive || (!isSensitive(name) && !isCrossOriginRestricted(name))
        }
    }

    fun hasSameOrigin(
        firstValue: String,
        secondValue: String,
    ): Boolean {
        return SceneInputOriginPolicy.hasSameHttpOrigin(firstValue, secondValue)
    }

    fun isSafeRemoteValue(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    }

    private fun isSensitive(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return normalized in SENSITIVE_HEADER_NAMES ||
            "authorization" in normalized ||
            normalized.split('-', '_', '.').any { it in SENSITIVE_HEADER_NAME_SEGMENTS } ||
            "cookie" in normalized ||
            "credential" in normalized ||
            "secret" in normalized ||
            "signature" in normalized ||
            "token" in normalized ||
            normalized.endsWith("-api-key") ||
            normalized == "api-key"
    }

    private fun isCrossOriginRestricted(name: String): Boolean {
        return name.equals("origin", ignoreCase = true) ||
            name.equals("referer", ignoreCase = true)
    }

    private val SENSITIVE_HEADER_NAMES = setOf(
        "authorization",
        "cookie",
        "cookie2",
        "proxy-authorization",
        "x-api-key",
        "x-auth-token",
        "x-csrf-token",
    )
    private val SENSITIVE_HEADER_NAME_SEGMENTS = setOf(
        "auth",
        "jwt",
        "password",
        "session",
    )
}

internal class AndroidSceneInputProtectionChecker(
    context: Context,
    client: OkHttpClient = Injekt.get<NetworkHelper>().client,
) : SceneInputProtectionChecker {
    private val restrictedClient = client.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val delegate = MaterializingSceneInputProtectionChecker(
        materializer = EagerSceneAuthenticatedInputMaterializer(
            fetcher = SameOriginSceneRemoteFetcher(
                exchangeFactory = OkHttpSceneRemoteExchangeFactory(restrictedClient),
            ),
        ),
        delegate = RecursiveSceneInputProtectionChecker(
            AndroidSceneProtectionDocumentReader(
                context = context.applicationContext,
                client = restrictedClient,
            ),
        ),
    )

    override suspend fun check(
        input: SceneVideoInputSpec,
        workingDirectory: File,
    ): SceneInputProtectionResult {
        return delegate.check(input, workingDirectory)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val CALL_TIMEOUT_SECONDS = 60L
    }
}

private class AndroidSceneProtectionDocumentReader(
    private val context: Context,
    private val client: OkHttpClient,
) : SceneProtectionDocumentReader {
    private val isoBmffSourceFactory = AndroidIsoBmffSourceFactory(context, client)

    override suspend fun read(
        resource: SceneProtectionResource,
        input: SceneVideoInputSpec,
    ): SceneProtectionDocument? {
        return when (resource.kind) {
            SceneVideoInputKind.LOCAL_FILE -> withContext(Dispatchers.IO) {
                val file = File(resource.value)
                if (!file.isFile || !file.canRead()) return@withContext null
                file.inputStream().use { stream ->
                    stream.readProtectionDocument(
                        resolvedValue = file.canonicalFile.absolutePath,
                        contentType = null,
                    )
                }
            }
            SceneVideoInputKind.CONTENT_URI -> withContext(Dispatchers.IO) {
                val uri = Uri.parse(resource.value)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readProtectionDocument(
                        resolvedValue = resource.value,
                        contentType = context.contentResolver.getType(uri),
                    )
                }
            }
            SceneVideoInputKind.REMOTE_HTTP -> readRemote(resource.value, input)
        }
    }

    override suspend fun openIsoBmffSource(
        resource: SceneProtectionResource,
        input: SceneVideoInputSpec,
        document: SceneProtectionDocument,
    ): IsoBmffRandomAccessByteSource? {
        return isoBmffSourceFactory.open(resource, input, document)
    }

    private suspend fun readRemote(
        initialValue: String,
        input: SceneVideoInputSpec,
    ): SceneProtectionDocument? = withContext(Dispatchers.IO) {
        var currentValue = initialValue
        var redirectChainStayedOnRootOrigin =
            SceneProtectionHeaderPolicy.hasSameOrigin(input.value, initialValue)

        repeat(MAX_REDIRECT_COUNT + 1) { redirectIndex ->
            currentCoroutineContext().ensureActive()
            val request = buildRequest(
                value = currentValue,
                input = input,
                redirectChainStayedOnRootOrigin = redirectChainStayedOnRootOrigin,
            ) ?: return@withContext null
            val response = client.newCall(request).await()
            response.use {
                if (it.code in REDIRECT_STATUS_CODES) {
                    if (redirectIndex >= MAX_REDIRECT_COUNT) return@withContext null
                    val location = it.header("Location") ?: return@withContext null
                    val redirected = resolveRedirect(currentValue, location) ?: return@withContext null
                    if (!SceneProtectionHeaderPolicy.hasSameOrigin(input.value, redirected)) {
                        return@withContext null
                    }
                    redirectChainStayedOnRootOrigin =
                        redirectChainStayedOnRootOrigin &&
                        SceneProtectionHeaderPolicy.hasSameOrigin(input.value, redirected)
                    currentValue = redirected
                    return@repeat
                }
                if (!it.isSuccessful) return@withContext null
                val body = it.body
                val read = body.byteStream().use { stream -> stream.readBounded() }
                val complete = read.complete && it.isCompleteResponse()
                return@withContext SceneProtectionDocument(
                    resolvedValue = currentValue,
                    bytes = read.bytes,
                    complete = complete,
                    contentType = body.contentType()?.toString(),
                )
            }
        }
        null
    }

    private fun buildRequest(
        value: String,
        input: SceneVideoInputSpec,
        redirectChainStayedOnRootOrigin: Boolean,
    ): Request? {
        val builder = runCatching {
            Request.Builder()
                .url(value)
                .get()
                .header("Range", "bytes=0-${MAX_MANIFEST_BYTES - 1}")
                .header("Accept-Encoding", "identity")
        }.getOrNull() ?: return null
        val forwardedHeaders = SceneProtectionHeaderPolicy.headersForRequest(
            input = input,
            targetValue = value,
            redirectChainStayedOnRootOrigin = redirectChainStayedOnRootOrigin,
        )
        forwardedHeaders.forEach { (name, headerValue) ->
            if (
                !name.equals("range", ignoreCase = true) &&
                !name.equals("accept-encoding", ignoreCase = true)
            ) {
                builder.addHeader(name, headerValue)
            }
        }
        val forwardedNames = forwardedHeaders
            .mapTo(mutableSetOf()) { (name, _) -> name.lowercase(Locale.ROOT) }
        input.inputOptions.forEach { option ->
            when (option.name) {
                "referer" -> if (
                    "referer" !in forwardedNames &&
                    redirectChainStayedOnRootOrigin &&
                    SceneProtectionHeaderPolicy.hasSameOrigin(input.value, value)
                ) {
                    builder.header("Referer", option.value)
                }
                "user_agent" -> if ("user-agent" !in forwardedNames) {
                    builder.header("User-Agent", option.value)
                }
            }
        }
        return builder.build()
    }

    private fun resolveRedirect(
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
        if (
            redirected.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            redirected.host.isNullOrBlank() ||
            redirected.userInfo != null ||
            redirected.fragment != null ||
            (
                current.scheme.equals("https", ignoreCase = true) &&
                    redirected.scheme.equals("http", ignoreCase = true)
                )
        ) {
            return null
        }
        return redirected.toASCIIString()
    }

    private suspend fun InputStream.readProtectionDocument(
        resolvedValue: String,
        contentType: String?,
    ): SceneProtectionDocument {
        val read = readBounded()
        return SceneProtectionDocument(
            resolvedValue = resolvedValue,
            bytes = read.bytes,
            complete = read.complete,
            contentType = contentType,
        )
    }

    private suspend fun InputStream.readBounded(): BoundedRead {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = MAX_MANIFEST_BYTES + 1
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) {
                return BoundedRead(output.toByteArray(), complete = true)
            }
            if (read > 0) {
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        val allBytes = output.toByteArray()
        return BoundedRead(
            bytes = allBytes.copyOf(MAX_MANIFEST_BYTES),
            complete = false,
        )
    }

    private data class BoundedRead(
        val bytes: ByteArray,
        val complete: Boolean,
    )

    private fun okhttp3.Response.isCompleteResponse(): Boolean {
        if (code != HTTP_PARTIAL_CONTENT) return true
        val contentRange = header("Content-Range") ?: return false
        val match = CONTENT_RANGE.matchEntire(contentRange.trim()) ?: return false
        val end = match.groupValues[1].toLongOrNull() ?: return false
        val total = match.groupValues[2].toLongOrNull() ?: return false
        return total > 0L && end + 1L >= total
    }

    private companion object {
        const val HTTP_PARTIAL_CONTENT = 206
        const val MAX_REDIRECT_COUNT = 5
        const val MAX_REDIRECT_LOCATION_LENGTH = 8_192
        val CONTENT_RANGE = Regex("""bytes\s+\d+-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

internal enum class SceneHlsReferenceKind {
    PLAYLIST,
    MEDIA,
    UNSAFE,
}

internal data class SceneHlsReference(
    val lineIndex: Int,
    val valueStart: Int,
    val valueEnd: Int,
    val value: String,
    val kind: SceneHlsReferenceKind,
)

internal data class SceneHlsProtectionInspection(
    val protectedReason: SceneCaptureUnsupportedReason?,
    val nestedPlaylistReferences: List<String>,
    val malformed: Boolean,
    internal val lines: List<String>,
    internal val references: List<SceneHlsReference>,
)

internal object SceneProtectionInspection {
    fun parseHls(document: SceneProtectionDocument): SceneHlsProtectionInspection? {
        val text = document.bytes.toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
        val advertisedAsHls = document.contentType.isHlsContentType()
        if (
            '\uFFFD' in text ||
            '\u0000' in text ||
            text.lineSequence().any { it.length > MAX_HLS_LINE_LENGTH }
        ) {
            return malformedHlsIfAdvertised(
                advertisedAsHls || text.trimStart().startsWith("#EXTM3U", ignoreCase = true),
            )
        }
        val lines = text.lineSequence()
            .map { it.removeSuffix("\r").trim() }
            .toList()
        if (lines.firstOrNull { it.isNotEmpty() }?.uppercase(Locale.ROOT) != "#EXTM3U") {
            return malformedHlsIfAdvertised(advertisedAsHls)
        }

        val references = mutableListOf<SceneHlsReference>()
        var expectsVariantUri = false
        for ((lineIndex, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            if (!line.startsWith("#")) {
                references += SceneHlsReference(
                    lineIndex = lineIndex,
                    valueStart = 0,
                    valueEnd = line.length,
                    value = line,
                    kind = if (expectsVariantUri) {
                        SceneHlsReferenceKind.PLAYLIST
                    } else {
                        SceneHlsReferenceKind.MEDIA
                    },
                )
                expectsVariantUri = false
                continue
            }
            if (expectsVariantUri) {
                return malformedHls(lines)
            }

            val tag = line.substringBefore(':').uppercase(Locale.ROOT)
            if (tag == "#EXT-X-KEY" || tag == "#EXT-X-SESSION-KEY") {
                val attributes = parseAttributes(line) ?: return malformedHls(lines)
                val method = attributes
                    .firstOrNull { it.name.equals("METHOD", ignoreCase = true) }
                    ?.value
                    ?.uppercase(Locale.ROOT)
                    ?: return protectedHls(SceneCaptureUnsupportedReason.ENCRYPTED, lines)
                if (tag == "#EXT-X-KEY" && method == "NONE") {
                    if (attributes.any { it.name.equals("URI", ignoreCase = true) }) {
                        return malformedHls(lines)
                    }
                    continue
                }
                val keyFormat = attributes
                    .firstOrNull { it.name.equals("KEYFORMAT", ignoreCase = true) }
                    ?.value
                    ?.lowercase(Locale.ROOT)
                val reason = if (
                    method.startsWith("SAMPLE-AES") ||
                    (keyFormat != null && keyFormat != "identity")
                ) {
                    SceneCaptureUnsupportedReason.DRM
                } else {
                    SceneCaptureUnsupportedReason.ENCRYPTED
                }
                return protectedHls(reason, lines)
            }
            if (tag.startsWith("#EXT-X-DRM")) {
                return protectedHls(SceneCaptureUnsupportedReason.DRM, lines)
            }
            if (tag == "#EXT-X-CONTENT-STEERING") {
                return malformedHls(lines)
            }
            if (tag == "#EXT-X-STREAM-INF") {
                expectsVariantUri = true
                continue
            }

            val containsUriAttributeName = URI_ATTRIBUTE_NAME_MARKER.containsMatchIn(line)
            val mayContainUri = URI_ATTRIBUTE_MARKER.containsMatchIn(line)
            if (containsUriAttributeName && !mayContainUri) {
                return malformedHls(lines)
            }
            if (!mayContainUri) {
                if (tag in REQUIRED_URI_TAGS) return malformedHls(lines)
                continue
            }
            val attributes = parseAttributes(line) ?: return malformedHls(lines)
            val uriAttributes = attributes.filter(HlsAttribute::isUriAttribute)
            if (uriAttributes.isEmpty()) {
                if (tag in REQUIRED_URI_TAGS) return malformedHls(lines)
                continue
            }
            val kind = if (tag in PLAYLIST_URI_TAGS) {
                SceneHlsReferenceKind.PLAYLIST
            } else {
                SceneHlsReferenceKind.MEDIA
            }
            uriAttributes.forEach { attribute ->
                references += SceneHlsReference(
                    lineIndex = lineIndex,
                    valueStart = attribute.valueStart,
                    valueEnd = attribute.valueEnd,
                    value = attribute.value,
                    kind = kind,
                )
            }
        }
        if (expectsVariantUri) return malformedHls(lines)
        return SceneHlsProtectionInspection(
            protectedReason = null,
            nestedPlaylistReferences = references
                .filter { it.kind == SceneHlsReferenceKind.PLAYLIST }
                .map(SceneHlsReference::value),
            malformed = false,
            lines = lines,
            references = references,
        )
    }

    fun isAdvertisedAsHls(
        resource: SceneProtectionResource,
        document: SceneProtectionDocument,
    ): Boolean {
        return document.contentType.isHlsContentType() ||
            resource.value.pathWithoutQuery().endsWith(".m3u8", ignoreCase = true) ||
            document.resolvedValue.pathWithoutQuery().endsWith(".m3u8", ignoreCase = true)
    }

    fun isMpd(
        resource: SceneProtectionResource,
        document: SceneProtectionDocument,
    ): Boolean {
        val contentType = document.contentType?.lowercase(Locale.ROOT).orEmpty()
        if ("dash+xml" in contentType || "application/mpd" in contentType) return true
        if (
            resource.value.pathWithoutQuery().endsWith(".mpd", ignoreCase = true) ||
            document.resolvedValue.pathWithoutQuery().endsWith(".mpd", ignoreCase = true)
        ) {
            return true
        }
        val prefix = document.bytes
            .take(MPD_PREFIX_SCAN_BYTES)
            .toByteArray()
            .toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .removePrefix("<?xml")
        return MPD_ELEMENT.containsMatchIn(prefix)
    }

    fun isIsoBmff(
        resource: SceneProtectionResource,
        document: SceneProtectionDocument,
    ): Boolean {
        val contentType = document.contentType?.lowercase(Locale.ROOT).orEmpty()
        if (ISO_BMFF_CONTENT_TYPES.any { it in contentType }) return true
        if (
            resource.value.pathWithoutQuery().endsWith(".mp4", ignoreCase = true) ||
            resource.value.pathWithoutQuery().endsWith(".m4v", ignoreCase = true) ||
            resource.value.pathWithoutQuery().endsWith(".mov", ignoreCase = true) ||
            document.resolvedValue.pathWithoutQuery().endsWith(".mp4", ignoreCase = true) ||
            document.resolvedValue.pathWithoutQuery().endsWith(".m4v", ignoreCase = true) ||
            document.resolvedValue.pathWithoutQuery().endsWith(".mov", ignoreCase = true)
        ) {
            return true
        }
        if (document.bytes.size < 8) return false
        val firstBoxType = document.bytes
            .copyOfRange(4, 8)
            .toString(Charsets.ISO_8859_1)
        return firstBoxType in ISO_BMFF_ROOT_BOX_TYPES
    }

    private fun parseAttributes(line: String): List<HlsAttribute>? {
        val colon = line.indexOf(':')
        if (colon < 0 || colon + 1 >= line.length) return null
        val attributes = mutableListOf<HlsAttribute>()
        var index = colon + 1
        while (index < line.length) {
            while (index < line.length && (line[index] == ',' || line[index].isWhitespace())) {
                index++
            }
            if (index >= line.length) break
            val nameStart = index
            while (index < line.length && line[index] != '=' && line[index] != ',') {
                index++
            }
            if (index >= line.length || line[index] != '=') return null
            val name = line.substring(nameStart, index).trim()
            if (!HLS_ATTRIBUTE_NAME.matches(name)) return null
            index++
            while (index < line.length && line[index].isWhitespace()) index++
            if (index >= line.length) return null

            val quoted = line[index] == '"'
            val valueStart: Int
            val valueEnd: Int
            val value: String
            if (quoted) {
                index++
                valueStart = index
                val end = line.indexOf('"', startIndex = index)
                if (end < 0) return null
                valueEnd = end
                value = line.substring(valueStart, valueEnd)
                index = end + 1
                while (index < line.length && line[index].isWhitespace()) index++
                if (index < line.length && line[index] != ',') return null
            } else {
                val rawStart = index
                val end = line.indexOf(',', startIndex = index)
                    .takeIf { it >= 0 }
                    ?: line.length
                val raw = line.substring(rawStart, end)
                val leading = raw.indexOfFirst { !it.isWhitespace() }
                val trailing = raw.indexOfLast { !it.isWhitespace() }
                if (leading < 0 || trailing < leading) return null
                valueStart = rawStart + leading
                valueEnd = rawStart + trailing + 1
                value = line.substring(valueStart, valueEnd)
                index = end
            }
            attributes += HlsAttribute(
                name = name,
                value = value,
                valueStart = valueStart,
                valueEnd = valueEnd,
            )
        }
        return attributes
    }

    private fun malformedHlsIfAdvertised(advertised: Boolean): SceneHlsProtectionInspection? {
        return if (advertised) malformedHls(emptyList()) else null
    }

    private fun malformedHls(lines: List<String>): SceneHlsProtectionInspection {
        return SceneHlsProtectionInspection(
            protectedReason = null,
            nestedPlaylistReferences = emptyList(),
            malformed = true,
            lines = lines,
            references = emptyList(),
        )
    }

    private fun protectedHls(
        reason: SceneCaptureUnsupportedReason,
        lines: List<String>,
    ): SceneHlsProtectionInspection {
        return SceneHlsProtectionInspection(
            protectedReason = reason,
            nestedPlaylistReferences = emptyList(),
            malformed = false,
            lines = lines,
            references = emptyList(),
        )
    }

    private data class HlsAttribute(
        val name: String,
        val value: String,
        val valueStart: Int,
        val valueEnd: Int,
    ) {
        fun isUriAttribute(): Boolean {
            val normalized = name.uppercase(Locale.ROOT)
            return normalized == "URI" ||
                normalized == "SERVER-URI" ||
                normalized.endsWith("-URI")
        }
    }

    private fun String?.isHlsContentType(): Boolean {
        val normalized = this?.lowercase(Locale.ROOT) ?: return false
        return "mpegurl" in normalized || "vnd.apple.mpegurl" in normalized
    }

    private fun String.pathWithoutQuery(): String {
        return substringBefore('#').substringBefore('?')
    }

    private const val MAX_HLS_LINE_LENGTH = 64 * 1024
    private const val MPD_PREFIX_SCAN_BYTES = 16 * 1024
    private val HLS_ATTRIBUTE_NAME = Regex("[A-Za-z0-9-]+")
    private val URI_ATTRIBUTE_MARKER =
        Regex("""(?:^|[:,])\s*[A-Za-z0-9-]*URI\s*=""", RegexOption.IGNORE_CASE)
    private val URI_ATTRIBUTE_NAME_MARKER =
        Regex("""(?:^|[:,])\s*[A-Za-z0-9-]*URI\b""", RegexOption.IGNORE_CASE)
    private val MPD_ELEMENT = Regex("""<\s*MPD(?:\s|>)""", RegexOption.IGNORE_CASE)
    private val PLAYLIST_URI_TAGS = setOf(
        "#EXT-X-I-FRAME-STREAM-INF",
        "#EXT-X-IMAGE-STREAM-INF",
        "#EXT-X-MEDIA",
        "#EXT-X-RENDITION-REPORT",
    )
    private val REQUIRED_URI_TAGS = setOf(
        "#EXT-X-I-FRAME-STREAM-INF",
        "#EXT-X-IMAGE-STREAM-INF",
        "#EXT-X-MAP",
        "#EXT-X-PART",
        "#EXT-X-PRELOAD-HINT",
        "#EXT-X-RENDITION-REPORT",
    )
    private val ISO_BMFF_CONTENT_TYPES = setOf(
        "application/mp4",
        "application/x-quicktime",
        "audio/mp4",
        "video/mp4",
        "video/quicktime",
    )
    private val ISO_BMFF_ROOT_BOX_TYPES = setOf(
        "free",
        "ftyp",
        "mdat",
        "moof",
        "moov",
        "skip",
        "styp",
        "uuid",
        "wide",
    )
}

private const val MAX_MANIFEST_BYTES = 1024 * 1024
