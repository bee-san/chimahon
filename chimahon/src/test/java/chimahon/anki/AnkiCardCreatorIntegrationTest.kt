package chimahon.anki

import android.content.Context
import android.util.Log
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.TermResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AnkiCardCreatorIntegrationTest {

    @TempDir
    lateinit var temporaryDirectory: File

    private val context = mockk<Context>(relaxed = true)

    @BeforeEach
    fun stubAndroidLogging() {
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } returns ""
    }

    @AfterEach
    fun restoreAndroidLogging() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `duplicate decision performs zero lazy generation`() = runBlocking {
        val gateway = FakeAnkiCardGateway(existingNotes = listOf(42L))
        val screenshotCalls = AtomicInteger()
        val audioCalls = AtomicInteger()
        val mediaStoreCalls = AtomicInteger()

        val result = createCard(
            gateway = gateway,
            mediaStore = {
                mediaStoreCalls.incrementAndGet()
                "unexpected"
            },
            fieldMap = """{"Screenshot":"{screenshot}","SentenceAudio":"{sentence-audio}"}""",
            mediaRequest = AnkiMediaRequest(
                screenshotMode = AnkiScreenshotMode.ANIMATED_SCENE,
                screenshotProvider = LazyAnkiScreenshotProvider {
                    screenshotCalls.incrementAndGet()
                    AnkiScreenshotPreparation.Still(mediaBytes("still"))
                },
                sentenceAudioProvider = LazyAnkiMediaProvider {
                    audioCalls.incrementAndGet()
                    mediaBytes("audio", extension = "m4a")
                },
            ),
        )

        assertEquals(AnkiResult.CardExists(42L), result)
        assertEquals(0, screenshotCalls.get())
        assertEquals(0, audioCalls.get())
        assertEquals(0, mediaStoreCalls.get())
        assertEquals(0, gateway.prepareAddCalls)
        assertEquals(0, gateway.noteMutationCalls)
    }

    @Test
    fun `duplicate query failure returns error before lazy generation`() = runBlocking {
        val gateway = FakeAnkiCardGateway(
            duplicateFailure = IllegalStateException("duplicate provider unavailable"),
        )
        val screenshotCalls = AtomicInteger()
        val mediaStoreCalls = AtomicInteger()

        val result = createCard(
            gateway = gateway,
            mediaStore = {
                mediaStoreCalls.incrementAndGet()
                "unexpected"
            },
            fieldMap = """{"Screenshot":"{screenshot}"}""",
            mediaRequest = AnkiMediaRequest(
                screenshotMode = AnkiScreenshotMode.FULL,
                screenshotProvider = LazyAnkiScreenshotProvider {
                    screenshotCalls.incrementAndGet()
                    AnkiScreenshotPreparation.Still(mediaBytes("still"))
                },
            ),
        )

        val error = assertInstanceOf(AnkiResult.Error::class.java, result)
        assertEquals("duplicate provider unavailable", error.message)
        assertEquals(0, screenshotCalls.get())
        assertEquals(0, mediaStoreCalls.get())
        assertEquals(0, gateway.prepareAddCalls)
        assertEquals(0, gateway.noteMutationCalls)
    }

    @Test
    fun `permission loss during duplicate lookup remains clean and lazy`() = runBlocking {
        val gateway = FakeAnkiCardGateway(
            duplicateFailure = SecurityException("permission lost"),
        )
        val screenshotCalls = AtomicInteger()

        val result = createCard(
            gateway = gateway,
            mediaStore = { error("No media should be stored") },
            fieldMap = """{"Screenshot":"{screenshot}"}""",
            mediaRequest = AnkiMediaRequest(
                screenshotMode = AnkiScreenshotMode.FULL,
                screenshotProvider = LazyAnkiScreenshotProvider {
                    screenshotCalls.incrementAndGet()
                    AnkiScreenshotPreparation.Still(mediaBytes("still"))
                },
            ),
        )

        assertEquals(AnkiResult.PermissionDenied, result)
        assertEquals(0, screenshotCalls.get())
        assertEquals(0, gateway.prepareAddCalls)
        assertEquals(0, gateway.noteMutationCalls)
    }

    @Test
    fun `media is committed before note mutation and placeholders are resolved`() = runBlocking {
        val events = mutableListOf<String>()
        val gateway = FakeAnkiCardGateway(events = events)

        val result = createCard(
            gateway = gateway,
            mediaStore = { source ->
                events += "media:${source.preferredBaseName}"
                "stored-scene.webp"
            },
            fieldMap = """{"Expression":"{expression}","Screenshot":"{screenshot}"}""",
            mediaRequest = AnkiMediaRequest(
                screenshotMode = AnkiScreenshotMode.FULL,
                screenshotProvider = LazyAnkiScreenshotProvider {
                    events += "generate"
                    AnkiScreenshotPreparation.Still(mediaBytes("still"))
                },
                onCommitStarted = { events += "commit-started" },
            ),
        )

        assertEquals(AnkiResult.Success(100L), result)
        assertEquals(
            listOf("generate", "prepare-add", "commit-started", "media:still", "note-add"),
            events,
        )
        assertEquals(
            "<img src=\"stored-scene.webp\">",
            gateway.lastAddedFields["Screenshot"],
        )
        assertFalse(gateway.lastAddedFields.values.any { "__chimahon_" in it })
    }

    @Test
    fun `media success followed by note failure reports possible orphan`() = runBlocking {
        val gateway = FakeAnkiCardGateway(
            noteFailure = IllegalStateException("note insert failed"),
        )

        val result = createCard(
            gateway = gateway,
            mediaStore = { "stored-scene.webp" },
            fieldMap = """{"Screenshot":"{screenshot}"}""",
            mediaRequest = AnkiMediaRequest(
                screenshotMode = AnkiScreenshotMode.FULL,
                screenshotProvider = LazyAnkiScreenshotProvider {
                    AnkiScreenshotPreparation.Still(mediaBytes("still"))
                },
            ),
        )

        val error = assertInstanceOf(AnkiResult.Error::class.java, result)
        assertEquals("note insert failed", error.message)
        assertEquals(
            listOf(AnkiMediaWarning.PossibleOrphanedMedia(1)),
            error.warnings,
        )
        assertEquals(1, gateway.noteMutationCalls)
    }

    @Test
    fun `permission loss after media insertion reports possible orphan`() = runBlocking {
        val gateway = FakeAnkiCardGateway(
            noteFailure = SecurityException("permission lost during note insert"),
        )

        val result = createCard(
            gateway = gateway,
            mediaStore = { "stored-scene.webp" },
            fieldMap = """{"Screenshot":"{screenshot}"}""",
            mediaRequest = AnkiMediaRequest(
                screenshotMode = AnkiScreenshotMode.FULL,
                screenshotProvider = LazyAnkiScreenshotProvider {
                    AnkiScreenshotPreparation.Still(mediaBytes("still"))
                },
            ),
        )

        val error = assertInstanceOf(AnkiResult.Error::class.java, result)
        assertEquals("permission lost during note insert", error.message)
        assertEquals(
            listOf(AnkiMediaWarning.PossibleOrphanedMedia(1)),
            error.warnings,
        )
        assertEquals(1, gateway.noteMutationCalls)
    }

    @Test
    fun `deck move failure remains committed success with write warning`() = runBlocking {
        val gateway = FakeAnkiCardGateway(
            addWarnings = listOf(AnkiWriteWarning.NoteCreatedDeckMoveFailed),
        )

        val result = createCard(
            gateway = gateway,
            mediaStore = { error("No media should be stored") },
            fieldMap = """{"Expression":"{expression}"}""",
            mediaRequest = AnkiMediaRequest(
                screenshotMode = AnkiScreenshotMode.NONE,
            ),
        )

        val success = assertInstanceOf(AnkiResult.Success::class.java, result)
        assertEquals(100L, success.noteId)
        assertEquals(
            listOf(AnkiWriteWarning.NoteCreatedDeckMoveFailed),
            success.writeWarnings,
        )
        assertEquals(1, gateway.noteMutationCalls)
    }

    @Test
    fun `file provider failure stores only the still fallback`() = runBlocking {
        val animationFile = temporaryDirectory.resolve("scene.webp")
        animationFile.writeBytes(byteArrayOf(1, 2, 3))
        val animation = AnkiMediaSource.FileSource(
            file = animationFile,
            preferredBaseName = "animation",
            extension = "webp",
            ownership = AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT,
        )
        val attempts = mutableListOf<AnkiMediaSource>()
        var successfulStores = 0
        val gateway = FakeAnkiCardGateway()

        val result = createCard(
            gateway = gateway,
            mediaStore = { source ->
                attempts += source
                if (source is AnkiMediaSource.FileSource) {
                    throw IllegalArgumentException("FileProvider rejected the URI")
                }
                successfulStores++
                "stored-fallback.webp"
            },
            fieldMap = """{"Screenshot":"{screenshot}"}""",
            mediaRequest = AnkiMediaRequest(
                screenshotMode = AnkiScreenshotMode.ANIMATED_SCENE,
                screenshotProvider = LazyAnkiScreenshotProvider {
                    AnkiScreenshotPreparation.Animated(
                        animation = animation,
                        stillFallback = mediaBytes("fallback"),
                    )
                },
            ),
        )

        val success = assertInstanceOf(AnkiResult.Success::class.java, result)
        assertEquals(listOf("animation", "fallback"), attempts.map { it.preferredBaseName })
        assertInstanceOf(AnkiMediaSource.FileSource::class.java, attempts[0])
        assertInstanceOf(AnkiMediaSource.Bytes::class.java, attempts[1])
        assertEquals(1, successfulStores)
        assertEquals(
            listOf(AnkiMediaWarning.AnimatedStorageFailed),
            success.warnings,
        )
        assertEquals(
            "<img src=\"stored-fallback.webp\">",
            gateway.lastAddedFields["Screenshot"],
        )
        assertEquals(1, gateway.noteMutationCalls)
        assertFalse(animationFile.exists())
    }

    @Test
    fun `concurrent first use creates shared deck and model once`() = runBlocking {
        val gateway = FirstUseAnkiCardGateway()

        val results = coroutineScope {
            listOf("猫-a", "猫-b").map { expression ->
                async {
                    createCard(
                        gateway = gateway,
                        mediaStore = { error("No media should be stored") },
                        fieldMap = """{"Expression":"{expression}"}""",
                        mediaRequest = AnkiMediaRequest(
                            screenshotMode = AnkiScreenshotMode.NONE,
                        ),
                        expression = expression,
                        deck = "",
                        model = "",
                    )
                }
            }.awaitAll()
        }

        assertTrue(results.all { it is AnkiResult.Success })
        assertEquals(4, gateway.prepareAddCalls.get())
        assertEquals(1, gateway.resolveCalls.get())
        assertEquals(1, gateway.resourceCreationCalls.get())
        assertEquals(1, gateway.maximumConcurrentResolves.get())
        assertEquals(2, gateway.noteMutationCalls.get())
    }

    @Test
    fun `cancellation while waiting for first use lock never enters commit`() = runBlocking {
        withTimeout(5_000) {
            val resourcesExist = AtomicBoolean()
            val firstGateway = BlockingFirstUseGateway(
                resourcesExist = resourcesExist,
                blockDuringRecheck = true,
            )
            val secondGateway = BlockingFirstUseGateway(
                resourcesExist = resourcesExist,
                blockDuringRecheck = false,
            )
            val firstCommitCalls = AtomicInteger()
            val secondCommitCalls = AtomicInteger()
            val first = async {
                createCard(
                    gateway = firstGateway,
                    mediaStore = { error("No media should be stored") },
                    fieldMap = """{"Expression":"{expression}"}""",
                    mediaRequest = AnkiMediaRequest(
                        screenshotMode = AnkiScreenshotMode.NONE,
                        onCommitStarted = { firstCommitCalls.incrementAndGet() },
                    ),
                    expression = "猫-a",
                    deck = "",
                    model = "",
                )
            }

            firstGateway.recheckStarted.await()
            val second = async {
                createCard(
                    gateway = secondGateway,
                    mediaStore = { error("No media should be stored") },
                    fieldMap = """{"Expression":"{expression}"}""",
                    mediaRequest = AnkiMediaRequest(
                        screenshotMode = AnkiScreenshotMode.NONE,
                        onCommitStarted = { secondCommitCalls.incrementAndGet() },
                    ),
                    expression = "猫-b",
                    deck = "",
                    model = "",
                )
            }
            try {
                secondGateway.initialPreparationCompleted.await()
                yield()
                second.cancelAndJoin()

                assertTrue(second.isCancelled)
                assertEquals(1, secondGateway.prepareAddCalls.get())
                assertEquals(0, secondGateway.resolveCalls.get())
                assertEquals(0, secondGateway.noteMutationCalls.get())
                assertEquals(0, secondCommitCalls.get())
            } finally {
                firstGateway.allowRecheckToFinish.complete(Unit)
            }

            assertInstanceOf(AnkiResult.Success::class.java, first.await())
            assertEquals(1, firstCommitCalls.get())
            assertEquals(1, firstGateway.resolveCalls.get())
            assertEquals(1, firstGateway.noteMutationCalls.get())
        }
    }

    private suspend fun createCard(
        gateway: AnkiCardGateway,
        mediaStore: suspend (AnkiMediaSource) -> String,
        fieldMap: String,
        mediaRequest: AnkiMediaRequest,
        expression: String = "猫",
        deck: String = "Mining",
        model: String = "Test model",
    ): AnkiResult {
        return AnkiCardCreator.addToAnkiWithDependencies(
            context = context,
            dependencies = AnkiCardCreatorDependencies(
                bridge = gateway,
                mediaStore = AnkiCardMediaStore(mediaStore),
                statisticsRecorder = AnkiCardStatisticsRecorder { _, _, _, _ -> },
            ),
            result = lookupResult(expression),
            deck = deck,
            model = model,
            fieldMapJson = fieldMap,
            tags = "",
            dupCheck = true,
            dupScope = "collection",
            dupAction = "prevent",
            mediaRequest = mediaRequest,
        )
    }

    private fun lookupResult(expression: String): LookupResult {
        return LookupResult(
            matched = expression,
            deinflected = expression,
            process = emptyArray(),
            term = TermResult(
                expression = expression,
                reading = "ねこ",
                rules = "",
                glossaries = arrayOf(
                    GlossaryEntry(
                        dictName = "Test",
                        glossary = "cat",
                        definitionTags = "",
                        termTags = "",
                    ),
                ),
                frequencies = emptyArray(),
                pitches = emptyArray(),
            ),
            preprocessorSteps = 0,
        )
    }

    private fun mediaBytes(
        name: String,
        extension: String = "webp",
    ): AnkiMediaSource.Bytes {
        return AnkiMediaSource.Bytes(
            data = name.encodeToByteArray(),
            preferredBaseName = name,
            extension = extension,
        )
    }

    private class FakeAnkiCardGateway(
        private val existingNotes: List<Long> = emptyList(),
        private val duplicateFailure: Exception? = null,
        private val noteFailure: Exception? = null,
        private val addWarnings: List<AnkiWriteWarning> = emptyList(),
        private val events: MutableList<String> = mutableListOf(),
    ) : AnkiCardGateway {
        var prepareAddCalls = 0
        var noteMutationCalls = 0
        var lastAddedFields: Map<String, String> = emptyMap()

        override fun hasPermission(): Boolean = true

        override suspend fun getDeckId(deckName: String): Long = 10L

        override suspend fun findNotes(
            expression: String,
            modelName: String?,
            deckId: Long?,
        ): List<Long> {
            duplicateFailure?.let { throw it }
            return existingNotes
        }

        override suspend fun prepareAddTarget(
            deckName: String,
            modelName: String,
            allowDefaultDeckCreation: Boolean,
            allowLapisModelCreation: Boolean,
        ): PreparedAnkiAddTarget {
            prepareAddCalls++
            events += "prepare-add"
            return PreparedAnkiAddTarget(
                deckName = deckName,
                modelName = modelName,
                deckId = 10L,
                modelId = 20L,
                modelFields = listOf("Expression", "Screenshot", "SentenceAudio"),
                createDefaultDeck = false,
                lapisModelAssets = null,
            )
        }

        override suspend fun resolveAddTargetForCommit(
            prepared: PreparedAnkiAddTarget,
        ): ResolvedAnkiAddTarget {
            error("Resolved target should not need provider mutation")
        }

        override suspend fun prepareNoteUpdate(noteId: Long): PreparedAnkiNoteUpdate {
            error("Overwrite is not expected")
        }

        override suspend fun addPreparedNote(
            target: ResolvedAnkiAddTarget,
            fields: Map<String, String>,
            tags: List<String>,
        ): AddedAnkiNote {
            noteMutationCalls++
            events += "note-add"
            lastAddedFields = fields
            noteFailure?.let { throw it }
            return AddedAnkiNote(100L, addWarnings)
        }

        override suspend fun updatePreparedNote(
            target: PreparedAnkiNoteUpdate,
            fields: Map<String, String>,
        ): Boolean {
            error("Overwrite is not expected")
        }

        override fun triggerSync() {
            error("Sync is not expected")
        }
    }

    private class FirstUseAnkiCardGateway : AnkiCardGateway {
        val prepareAddCalls = AtomicInteger()
        val resolveCalls = AtomicInteger()
        val resourceCreationCalls = AtomicInteger()
        val maximumConcurrentResolves = AtomicInteger()
        val noteMutationCalls = AtomicInteger()

        private val bothPrepared = CompletableDeferred<Unit>()
        private val resourcesExist = AtomicBoolean()
        private val activeResolves = AtomicInteger()

        override fun hasPermission(): Boolean = true

        override suspend fun getDeckId(deckName: String): Long {
            throw AnkiDeckNotFoundException(deckName)
        }

        override suspend fun findNotes(
            expression: String,
            modelName: String?,
            deckId: Long?,
        ): List<Long> = emptyList()

        override suspend fun prepareAddTarget(
            deckName: String,
            modelName: String,
            allowDefaultDeckCreation: Boolean,
            allowLapisModelCreation: Boolean,
        ): PreparedAnkiAddTarget {
            if (prepareAddCalls.incrementAndGet() == 2) {
                bothPrepared.complete(Unit)
            }
            bothPrepared.await()
            val alreadyCreated = resourcesExist.get()
            return PreparedAnkiAddTarget(
                deckName = deckName,
                modelName = modelName,
                deckId = if (alreadyCreated) 10L else null,
                modelId = if (alreadyCreated) 20L else null,
                modelFields = listOf("Expression"),
                createDefaultDeck = !alreadyCreated,
                lapisModelAssets = if (alreadyCreated) null else AnkiLapisModelAssets("", "", ""),
            )
        }

        override suspend fun resolveAddTargetForCommit(
            prepared: PreparedAnkiAddTarget,
        ): ResolvedAnkiAddTarget {
            resolveCalls.incrementAndGet()
            val active = activeResolves.incrementAndGet()
            maximumConcurrentResolves.accumulateAndGet(active) { current, candidate ->
                maxOf(current, candidate)
            }
            try {
                delay(20)
                if (resourcesExist.compareAndSet(false, true)) {
                    resourceCreationCalls.incrementAndGet()
                }
                return ResolvedAnkiAddTarget(
                    deckId = 10L,
                    modelId = 20L,
                    modelFields = prepared.modelFields,
                )
            } finally {
                activeResolves.decrementAndGet()
            }
        }

        override suspend fun prepareNoteUpdate(noteId: Long): PreparedAnkiNoteUpdate {
            error("Overwrite is not expected")
        }

        override suspend fun addPreparedNote(
            target: ResolvedAnkiAddTarget,
            fields: Map<String, String>,
            tags: List<String>,
        ): AddedAnkiNote {
            return AddedAnkiNote(noteMutationCalls.incrementAndGet().toLong())
        }

        override suspend fun updatePreparedNote(
            target: PreparedAnkiNoteUpdate,
            fields: Map<String, String>,
        ): Boolean {
            error("Overwrite is not expected")
        }

        override fun triggerSync() {
            error("Sync is not expected")
        }
    }

    private class BlockingFirstUseGateway(
        private val resourcesExist: AtomicBoolean,
        private val blockDuringRecheck: Boolean,
    ) : AnkiCardGateway {
        val initialPreparationCompleted = CompletableDeferred<Unit>()
        val recheckStarted = CompletableDeferred<Unit>()
        val allowRecheckToFinish = CompletableDeferred<Unit>()
        val prepareAddCalls = AtomicInteger()
        val resolveCalls = AtomicInteger()
        val noteMutationCalls = AtomicInteger()

        override fun hasPermission(): Boolean = true

        override suspend fun getDeckId(deckName: String): Long {
            throw AnkiDeckNotFoundException(deckName)
        }

        override suspend fun findNotes(
            expression: String,
            modelName: String?,
            deckId: Long?,
        ): List<Long> = emptyList()

        override suspend fun prepareAddTarget(
            deckName: String,
            modelName: String,
            allowDefaultDeckCreation: Boolean,
            allowLapisModelCreation: Boolean,
        ): PreparedAnkiAddTarget {
            val call = prepareAddCalls.incrementAndGet()
            if (call == 1) {
                initialPreparationCompleted.complete(Unit)
            } else {
                recheckStarted.complete(Unit)
                if (blockDuringRecheck) {
                    allowRecheckToFinish.await()
                }
            }
            val alreadyCreated = resourcesExist.get()
            return PreparedAnkiAddTarget(
                deckName = deckName,
                modelName = modelName,
                deckId = if (alreadyCreated) 10L else null,
                modelId = if (alreadyCreated) 20L else null,
                modelFields = listOf("Expression"),
                createDefaultDeck = !alreadyCreated,
                lapisModelAssets = if (alreadyCreated) null else AnkiLapisModelAssets("", "", ""),
            )
        }

        override suspend fun resolveAddTargetForCommit(
            prepared: PreparedAnkiAddTarget,
        ): ResolvedAnkiAddTarget {
            resolveCalls.incrementAndGet()
            resourcesExist.set(true)
            return ResolvedAnkiAddTarget(
                deckId = 10L,
                modelId = 20L,
                modelFields = prepared.modelFields,
            )
        }

        override suspend fun prepareNoteUpdate(noteId: Long): PreparedAnkiNoteUpdate {
            error("Overwrite is not expected")
        }

        override suspend fun addPreparedNote(
            target: ResolvedAnkiAddTarget,
            fields: Map<String, String>,
            tags: List<String>,
        ): AddedAnkiNote = AddedAnkiNote(noteMutationCalls.incrementAndGet().toLong())

        override suspend fun updatePreparedNote(
            target: PreparedAnkiNoteUpdate,
            fields: Map<String, String>,
        ): Boolean {
            error("Overwrite is not expected")
        }

        override fun triggerSync() {
            error("Sync is not expected")
        }
    }
}
