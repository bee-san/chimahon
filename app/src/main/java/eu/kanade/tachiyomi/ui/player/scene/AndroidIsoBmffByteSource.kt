package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal fun SceneProtectionDocument.completeIsoBmffSourceOrNull(): IsoBmffRandomAccessByteSource? {
    if (!complete) return null
    return ByteArrayIsoBmffSource(bytes)
}

internal class AndroidIsoBmffSourceFactory(
    context: Context,
    private val client: OkHttpClient,
) {
    private val applicationContext = context.applicationContext

    suspend fun open(
        resource: SceneProtectionResource,
        input: SceneVideoInputSpec,
        document: SceneProtectionDocument,
    ): IsoBmffRandomAccessByteSource? {
        return when (resource.kind) {
            SceneVideoInputKind.LOCAL_FILE -> openLocal(document.resolvedValue)
            SceneVideoInputKind.CONTENT_URI -> openContent(document.resolvedValue)
            SceneVideoInputKind.REMOTE_HTTP -> {
                document.completeIsoBmffSourceOrNull()
                    ?: RemoteIsoBmffSource.open(
                        initialValue = document.resolvedValue,
                        input = input,
                        client = client,
                    )
            }
        }
    }

    private suspend fun openLocal(value: String): IsoBmffRandomAccessByteSource? {
        return withContext(Dispatchers.IO) {
            val file = File(value)
            if (!file.isFile || !file.canRead()) return@withContext null
            val stream = runCatching { FileInputStream(file) }.getOrNull()
                ?: return@withContext null
            val channel = stream.channel
            val length = runCatching(channel::size).getOrNull()
            if (length == null || length < 0L) {
                stream.close()
                return@withContext null
            }
            FileChannelIsoBmffSource(
                channel = channel,
                baseOffset = 0L,
                length = length,
                closeAction = stream::close,
            )
        }
    }

    private suspend fun openContent(value: String): IsoBmffRandomAccessByteSource? {
        return withContext(Dispatchers.IO) {
            val descriptor = runCatching {
                applicationContext.contentResolver.openAssetFileDescriptor(
                    Uri.parse(value),
                    "r",
                )
            }.getOrNull() ?: return@withContext null
            val startOffset = descriptor.startOffset
            val length = descriptor.length
                .takeIf { it >= 0L }
                ?: descriptor.parcelFileDescriptor.statSize
                    .takeIf { it >= startOffset }
                    ?.minus(startOffset)
            if (length == null || length < 0L) {
                descriptor.close()
                return@withContext null
            }
            val stream = runCatching { FileInputStream(descriptor.fileDescriptor) }
                .getOrElse {
                    descriptor.close()
                    return@withContext null
                }
            FileChannelIsoBmffSource(
                channel = stream.channel,
                baseOffset = startOffset,
                length = length,
                closeAction = {
                    runCatching(stream::close)
                    runCatching(descriptor::close)
                },
            )
        }
    }
}

private class ByteArrayIsoBmffSource(
    private val bytes: ByteArray,
) : IsoBmffRandomAccessByteSource {
    override val length = bytes.size.toLong()

    override suspend fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        byteCount: Int,
    ): Int {
        if (offset < 0L || offset >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - offset.toInt())
        bytes.copyInto(
            destination = destination,
            destinationOffset = destinationOffset,
            startIndex = offset.toInt(),
            endIndex = offset.toInt() + count,
        )
        return count
    }
}

private class FileChannelIsoBmffSource(
    private val channel: FileChannel,
    private val baseOffset: Long,
    override val length: Long,
    private val closeAction: () -> Unit,
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
        if (baseOffset > Long.MAX_VALUE - offset) return@withContext -1
        channel.read(
            ByteBuffer.wrap(destination, destinationOffset, boundedCount),
            baseOffset + offset,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeAction()
        }
    }
}

internal class RemoteIsoBmffSource private constructor(
    override val length: Long,
    private val input: SceneVideoInputSpec,
    private val client: OkHttpClient,
    firstRange: RemoteRange,
) : IsoBmffRandomAccessByteSource {
    private val lock = Mutex()
    private val closed = AtomicBoolean(false)
    private var currentValue = firstRange.resolvedValue
    private var cachedOffset = 0L
    private var cachedBytes = firstRange.bytes

    override suspend fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        byteCount: Int,
    ): Int = lock.withLock {
        if (
            closed.get() ||
            offset < 0L ||
            byteCount < 0 ||
            destinationOffset < 0 ||
            destinationOffset > destination.size - byteCount ||
            offset >= length
        ) {
            return@withLock -1
        }
        if (byteCount == 0) return@withLock 0

        val requestedCount = minOf(byteCount.toLong(), length - offset).toInt()
        var copied = 0
        while (copied < requestedCount) {
            val position = offset + copied
            if (
                position < cachedOffset ||
                position - cachedOffset >= cachedBytes.size
            ) {
                val cacheEnd = if (position > Long.MAX_VALUE - REMOTE_CACHE_BYTES) {
                    Long.MAX_VALUE
                } else {
                    position + REMOTE_CACHE_BYTES - 1L
                }
                val end = minOf(length - 1L, cacheEnd)
                val range = fetchRange(
                    client = client,
                    initialValue = currentValue,
                    input = input,
                    start = position,
                    end = end,
                    expectedLength = length,
                ) ?: throw IOException("Remote ISO-BMFF range request failed")
                currentValue = range.resolvedValue
                cachedOffset = position
                cachedBytes = range.bytes
            }
            val cacheIndex = (position - cachedOffset).toInt()
            val available = minOf(
                requestedCount - copied,
                cachedBytes.size - cacheIndex,
            )
            if (available <= 0) {
                throw IOException("Remote ISO-BMFF range response was incomplete")
            }
            cachedBytes.copyInto(
                destination = destination,
                destinationOffset = destinationOffset + copied,
                startIndex = cacheIndex,
                endIndex = cacheIndex + available,
            )
            copied += available
        }
        copied
    }

    override fun close() {
        closed.set(true)
        cachedBytes = ByteArray(0)
    }

    companion object {
        suspend fun open(
            initialValue: String,
            input: SceneVideoInputSpec,
            client: OkHttpClient,
        ): IsoBmffRandomAccessByteSource? {
            val firstRange = fetchRange(
                client = client,
                initialValue = initialValue,
                input = input,
                start = 0L,
                end = 0L,
                expectedLength = null,
            ) ?: return null
            if (firstRange.totalLength <= 0L) return null
            return RemoteIsoBmffSource(
                length = firstRange.totalLength,
                input = input,
                client = client,
                firstRange = firstRange,
            )
        }
    }
}

private data class RemoteRange(
    val resolvedValue: String,
    val totalLength: Long,
    val bytes: ByteArray,
)

private suspend fun fetchRange(
    client: OkHttpClient,
    initialValue: String,
    input: SceneVideoInputSpec,
    start: Long,
    end: Long,
    expectedLength: Long?,
): RemoteRange? {
    if (start < 0L || end < start || end - start >= REMOTE_CACHE_BYTES) return null
    var currentValue = initialValue
    repeat(MAX_REMOTE_REDIRECT_COUNT + 1) { redirectIndex ->
        val request = buildRangeRequest(
            value = currentValue,
            input = input,
            start = start,
            end = end,
        ) ?: return null
        val response = client.newCall(request).await()
        response.use {
            if (it.code in REMOTE_REDIRECT_STATUS_CODES) {
                if (redirectIndex >= MAX_REMOTE_REDIRECT_COUNT) return null
                val location = it.header("Location") ?: return null
                currentValue = resolveSafeRangeRedirect(
                    rootValue = input.value,
                    currentValue = currentValue,
                    location = location,
                ) ?: return null
                return@repeat
            }
            if (it.code != HTTP_PARTIAL_CONTENT) return null
            val contentRange = it.header("Content-Range")
                ?.trim()
                ?.let(REMOTE_CONTENT_RANGE::matchEntire)
                ?: return null
            val responseStart = contentRange.groupValues[1].toLongOrNull() ?: return null
            val responseEnd = contentRange.groupValues[2].toLongOrNull() ?: return null
            val totalLength = contentRange.groupValues[3].toLongOrNull() ?: return null
            if (
                responseStart != start ||
                responseEnd != end ||
                totalLength <= responseEnd ||
                (expectedLength != null && totalLength != expectedLength)
            ) {
                return null
            }
            val expectedBytes = (end - start + 1L).toInt()
            val body = it.body
            if (body.contentLength() > expectedBytes) return null
            val bytes = body.byteStream().use { stream ->
                val result = ByteArray(expectedBytes)
                var copied = 0
                while (copied < expectedBytes) {
                    val count = stream.read(result, copied, expectedBytes - copied)
                    if (count < 0) return null
                    if (count == 0) {
                        val single = stream.read()
                        if (single < 0) return null
                        result[copied++] = single.toByte()
                    } else {
                        copied += count
                    }
                }
                if (stream.read() >= 0) return null
                result
            }
            return RemoteRange(
                resolvedValue = currentValue,
                totalLength = totalLength,
                bytes = bytes,
            )
        }
    }
    return null
}

private fun buildRangeRequest(
    value: String,
    input: SceneVideoInputSpec,
    start: Long,
    end: Long,
): Request? {
    if (!SceneProtectionHeaderPolicy.hasSameOrigin(input.value, value)) return null
    val builder = runCatching {
        Request.Builder()
            .url(value)
            .get()
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")
    }.getOrNull() ?: return null
    val forwardedHeaders = SceneProtectionHeaderPolicy.headersForRequest(
        input = input,
        targetValue = value,
        redirectChainStayedOnRootOrigin = true,
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
        if (option.name == "user_agent" && "user-agent" !in forwardedNames) {
            builder.header("User-Agent", option.value)
        }
    }
    return builder.build()
}

private fun resolveSafeRangeRedirect(
    rootValue: String,
    currentValue: String,
    location: String,
): String? {
    if (
        location.isBlank() ||
        location.length > MAX_REMOTE_REDIRECT_LOCATION_LENGTH ||
        location.any { it == '\u0000' || it == '\r' || it == '\n' || it.code < 0x20 }
    ) {
        return null
    }
    val current = runCatching { URI(currentValue) }.getOrNull() ?: return null
    val redirected = runCatching { current.resolve(location).normalize() }.getOrNull()
        ?: return null
    val resolved = redirected.toASCIIString()
    if (
        redirected.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
        redirected.host.isNullOrBlank() ||
        redirected.userInfo != null ||
        redirected.fragment != null ||
        (
            current.scheme.equals("https", ignoreCase = true) &&
                redirected.scheme.equals("http", ignoreCase = true)
            ) ||
        !SceneProtectionHeaderPolicy.hasSameOrigin(rootValue, resolved)
    ) {
        return null
    }
    return resolved
}

private const val HTTP_PARTIAL_CONTENT = 206
private const val MAX_REMOTE_REDIRECT_COUNT = 5
private const val MAX_REMOTE_REDIRECT_LOCATION_LENGTH = 8_192
private const val REMOTE_CACHE_BYTES = 64L * 1024L
private val REMOTE_CONTENT_RANGE =
    Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
private val REMOTE_REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
