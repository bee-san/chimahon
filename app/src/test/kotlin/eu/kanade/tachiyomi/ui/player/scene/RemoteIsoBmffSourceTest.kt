package eu.kanade.tachiyomi.ui.player.scene

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class RemoteIsoBmffSourceTest {
    @Test
    fun `remote random access finds a late protection box with bounded ranges`() = runTest {
        val bytes = box("ftyp", "isom".encodeToByteArray()) +
            box("mdat", ByteArray((1 shl 20) + 37) { 0x2a }) +
            box("pssh")
        val ranges = Collections.synchronizedList(mutableListOf<LongRange>())
        val server = rangeServer(bytes, ranges)
        try {
            val value = server.url("/video.mp4")
            val source = RemoteIsoBmffSource.open(
                initialValue = value,
                input = input(value),
                client = client(),
            )
            assertNotNull(source)
            try {
                assertEquals(
                    IsoBmffProtectionInspection.Protected(IsoBmffProtectionBox.PSSH),
                    IsoBmffProtectionInspector().inspect(source!!),
                )
            } finally {
                source?.close()
            }

            assertTrue(ranges.any { it.first > (1 shl 20).toLong() })
            assertTrue(ranges.all { range -> range.last - range.first + 1L <= 64L * 1024L })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `cross origin range redirect is rejected without contacting target`() = runTest {
        val targetRequests = AtomicInteger()
        val target = TestServer.start {
            targetRequests.incrementAndGet()
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                "",
            )
        }
        val redirect = TestServer.start {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "",
            ).apply {
                addHeader("Location", target.url("/stolen"))
            }
        }
        try {
            val value = redirect.url("/video.mp4")
            assertNull(
                RemoteIsoBmffSource.open(
                    initialValue = value,
                    input = input(value),
                    client = client(),
                ),
            )
            assertEquals(0, targetRequests.get())
        } finally {
            redirect.stop()
            target.stop()
        }
    }

    private fun rangeServer(
        bytes: ByteArray,
        ranges: MutableList<LongRange>,
    ): TestServer {
        return TestServer.start { session ->
            val requested = session.headers["range"]
                ?.let(RANGE_HEADER::matchEntire)
            if (requested == null) {
                return@start NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "",
                )
            }
            val start = requested.groupValues[1].toLong()
            val end = requested.groupValues[2].toLong()
            if (start < 0L || end < start || end >= bytes.size) {
                return@start NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "",
                )
            }
            ranges += start..end
            val payload = bytes.copyOfRange(start.toInt(), end.toInt() + 1)
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                "application/octet-stream",
                ByteArrayInputStream(payload),
                payload.size.toLong(),
            ).apply {
                addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
            }
        }
    }

    private fun input(value: String): SceneVideoInputSpec {
        return SceneVideoInputSpec(
            value = value,
            kind = SceneVideoInputKind.REMOTE_HTTP,
            headers = emptyList(),
            inputOptions = emptyList(),
            externalAudioValue = null,
            identity = SceneVideoIdentity(
                episodeId = 1L,
                sourceId = 2L,
                quality = "test",
                inputDigest = "digest",
            ),
        )
    }

    private fun client(): OkHttpClient {
        return OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun box(
        type: String,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val size = 8L + payload.size
        return uint32(size) + type.encodeToByteArray() + payload
    }

    private fun uint32(value: Long): ByteArray {
        return ByteArray(4) { index ->
            (value ushr (24 - index * 8)).toByte()
        }
    }

    private class TestServer private constructor(
        private val server: NanoHTTPD,
    ) {
        fun url(path: String): String {
            return "http://127.0.0.1:${server.listeningPort}$path"
        }

        fun stop() {
            server.stop()
        }

        companion object {
            fun start(handler: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response): TestServer {
                val server = object : NanoHTTPD("127.0.0.1", 0) {
                    override fun serve(session: IHTTPSession): Response {
                        return handler(session)
                    }
                }
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                return TestServer(server)
            }
        }
    }

    private companion object {
        val RANGE_HEADER = Regex("""bytes=(\d+)-(\d+)""")
    }
}
