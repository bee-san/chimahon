package chimahon.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import exh.log.xLogE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class PreparedAnkiAddTarget(
    val deckName: String,
    val modelName: String,
    val deckId: Long?,
    val modelId: Long?,
    val modelFields: List<String>,
    val createDefaultDeck: Boolean,
    val lapisModelAssets: AnkiLapisModelAssets?,
) {
    val requiresResourceCreation: Boolean
        get() = createDefaultDeck || lapisModelAssets != null

    fun resolvedWithoutMutation(): ResolvedAnkiAddTarget {
        check(!requiresResourceCreation)
        return ResolvedAnkiAddTarget(
            deckId = checkNotNull(deckId),
            modelId = checkNotNull(modelId),
            modelFields = modelFields,
        )
    }
}

internal data class ResolvedAnkiAddTarget(
    val deckId: Long,
    val modelId: Long,
    val modelFields: List<String>,
)

internal data class PreparedAnkiNoteUpdate(
    val noteId: Long,
    val noteUri: Uri,
    val oldFields: List<String>,
    val modelFields: List<String>,
)

internal data class AnkiLapisModelAssets(
    val css: String,
    val front: String,
    val back: String,
)

internal class AnkiDeckNotFoundException(
    deckName: String,
) : Exception("Deck '$deckName' not found")

internal fun requireProviderRowsUpdated(
    count: Int,
    operation: String,
) {
    if (count <= 0) {
        throw Exception("AnkiDroid failed to $operation")
    }
}

class AnkiDroidBridge(private val context: Context) {

    companion object {
        private const val TAG = "AnkiDroidBridge"
        private const val SYNC_COOLDOWN_MS = 120_000L
        private var lastSyncTimeMs = 0L
        private const val ANKIDROID_PACKAGE = "com.ichi2.anki"

        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        const val PERMISSION_REQUEST_CODE = 2001

        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private val BASE_URI = Uri.parse("content://$AUTHORITY")
        private val NOTES_URI = Uri.withAppendedPath(BASE_URI, "notes")
        private val NOTES_V2_URI = Uri.withAppendedPath(BASE_URI, "notes_v2")
        private val MODELS_URI = Uri.withAppendedPath(BASE_URI, "models")
        private val DECKS_URI = Uri.withAppendedPath(BASE_URI, "decks")
        private val MEDIA_URI = Uri.withAppendedPath(BASE_URI, "media")

        private const val NOTE_ID = "_id"
        private const val NOTE_MID = "mid"
        private const val NOTE_FLDS = "flds"
        private const val NOTE_TAGS = "tags"
        private const val NOTE_CSUM = "csum"

        private const val MODEL_ID = "_id"
        private const val MODEL_NAME = "name"
        private const val MODEL_FIELD_NAMES = "field_names"
        private const val MODEL_NUM_CARDS = "num_cards"
        private const val MODEL_CSS = "css"
        private const val MODEL_DECK_ID = "deck_id"
        private const val MODEL_SORT_FIELD_INDEX = "sort_field_index"

        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"

        private const val CARD_TEMPLATE_NAME = "card_template_name"
        private const val CARD_TEMPLATE_QUESTION_FORMAT = "question_format"
        private const val CARD_TEMPLATE_ANSWER_FORMAT = "answer_format"

        private const val MEDIA_FILE_URI = "file_uri"
        private const val MEDIA_PREFERRED_NAME = "preferred_name"

        private const val FIELD_SEPARATOR = "\u001f"
    }

    private data class ModelInfo(
        val id: Long,
        val name: String,
        val fields: List<String>,
    )

    // ==========================================================================
    // Public API
    // ==========================================================================

    suspend fun isAnkiDroidInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            context.packageManager.getPackageInfo("com.ichi2.anki", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission(activity: android.app.Activity) {
        activity.requestPermissions(arrayOf(PERMISSION), PERMISSION_REQUEST_CODE)
    }

    suspend fun deckNames(): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val names = mutableListOf<String>()
        try {
            context.contentResolver.query(
                DECKS_URI,
                arrayOf(DECK_NAME),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let(names::add)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost during deckNames query", e)
        } catch (e: Exception) {
            Log.e(TAG, "deckNames", e)
        }
        names
    }

    suspend fun modelNames(): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val names = mutableListOf<String>()
        try {
            context.contentResolver.query(
                MODELS_URI,
                arrayOf(MODEL_NAME),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let(names::add)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost during modelNames query", e)
        } catch (e: Exception) {
            Log.e(TAG, "modelNames", e)
        }
        names
    }

    suspend fun modelFieldNames(modelName: String): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val result = mutableListOf<String>()
        try {
            context.contentResolver.query(
                MODELS_URI,
                arrayOf(MODEL_NAME, MODEL_FIELD_NAMES),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val dbModelName = c.getString(0) ?: continue
                    if (dbModelName != modelName) continue

                    val rawData = c.getString(1) ?: continue
                    val parsed = parseFieldNames(rawData)
                    if (parsed.size > result.size) {
                        result.clear()
                        result.addAll(parsed)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost during modelFieldNames query", e)
        } catch (e: Exception) {
            Log.e(TAG, "modelFieldNames", e)
        }
        result
    }

    suspend fun ensureDefaultDeckName(): String = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext ""
        val defaultName = LapisPreset.DEFAULT_DECK_NAME
        findDeckIdOrNull(defaultName) ?: createDeck(defaultName)
        defaultName
    }

    suspend fun ensureLapisModelName(): String = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext ""

        val models = readModelInfos()
        if (models.any { it.name == LapisPreset.MODEL_NAME }) {
            LapisPreset.MODEL_NAME
        } else {
            createLapisModel(LapisPreset.MODEL_NAME)
            LapisPreset.MODEL_NAME
        }
    }

    internal suspend fun prepareAddTarget(
        deckName: String,
        modelName: String,
        allowDefaultDeckCreation: Boolean,
        allowLapisModelCreation: Boolean,
    ): PreparedAnkiAddTarget = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw SecurityException("AnkiDroid permission not granted")

        val deckId = findDeckIdOrNull(deckName)
        val createDefaultDeck = deckId == null && allowDefaultDeckCreation
        if (deckId == null && !createDefaultDeck) {
            throw Exception("Deck '$deckName' not found")
        }

        val model = findModelInfoOrNull(modelName)
        val createLapisModel = model == null && allowLapisModelCreation
        if (model == null && !createLapisModel) {
            throw Exception("Model '$modelName' not found")
        }

        PreparedAnkiAddTarget(
            deckName = deckName,
            modelName = modelName,
            deckId = deckId,
            modelId = model?.id,
            modelFields = model?.fields ?: LapisPreset.fields,
            createDefaultDeck = createDefaultDeck,
            lapisModelAssets = if (createLapisModel) readLapisModelAssets() else null,
        )
    }

    internal suspend fun resolveAddTargetForCommit(
        prepared: PreparedAnkiAddTarget,
    ): ResolvedAnkiAddTarget = withContext(Dispatchers.IO) {
        // AnkiCardCreator performs the final read-only recheck while holding
        // its cancellable resource lock. This method begins the provider
        // mutation phase using that refreshed snapshot.
        val deckId = prepared.deckId ?: createDeck(prepared.deckName)
        val modelId = prepared.modelId ?: createLapisModel(
            modelName = prepared.modelName,
            assets = checkNotNull(prepared.lapisModelAssets),
            deckId = deckId,
        )
        ResolvedAnkiAddTarget(
            deckId = deckId,
            modelId = modelId,
            modelFields = prepared.modelFields,
        )
    }

    internal suspend fun prepareNoteUpdate(
        noteId: Long,
    ): PreparedAnkiNoteUpdate = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw SecurityException("AnkiDroid permission not granted")
        val uri = Uri.withAppendedPath(NOTES_URI, noteId.toString())
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(NOTE_MID, NOTE_FLDS),
            null,
            null,
            null,
        ) ?: throw Exception("AnkiDroid note query failed")
        cursor.use { c ->
            if (!c.moveToFirst()) throw Exception("AnkiDroid note not found")
            val modelId = c.getLong(0)
            PreparedAnkiNoteUpdate(
                noteId = noteId,
                noteUri = uri,
                oldFields = splitFields(c.getString(1)),
                modelFields = getModelFields(modelId),
            )
        }
    }

    internal suspend fun addPreparedNote(
        target: ResolvedAnkiAddTarget,
        fields: Map<String, String>,
        tags: List<String>,
    ): AddedAnkiNote = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw SecurityException("AnkiDroid permission not granted")

        val values = Array(target.modelFields.size) { i ->
            fields[target.modelFields[i]] ?: ""
        }
        val cv = ContentValues().apply {
            put(NOTE_MID, target.modelId)
            put(NOTE_FLDS, values.joinToString(FIELD_SEPARATOR))
            tags.toSet().takeIf { it.isNotEmpty() }?.let { put(NOTE_TAGS, it.joinToString(" ")) }
        }
        val result = context.contentResolver.insert(NOTES_URI, cv)
            ?: throw Exception("AnkiDroid insert failed")
        val noteId = result.lastPathSegment?.toLongOrNull()
            ?: throw Exception("Failed to parse note ID from insert result")
        val warnings = moveCardsToDeckAfterCommittedNote(noteId, target.deckId)
        AddedAnkiNote(noteId, warnings)
    }

    internal suspend fun updatePreparedNote(
        target: PreparedAnkiNoteUpdate,
        fields: Map<String, String>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw SecurityException("AnkiDroid permission not granted")
        val newFields = Array(target.modelFields.size) { i ->
            when {
                fields.containsKey(target.modelFields[i]) -> fields[target.modelFields[i]]!!
                i < target.oldFields.size -> target.oldFields[i]
                else -> ""
            }
        }
        val cv = ContentValues().apply {
            put(NOTE_FLDS, newFields.joinToString(FIELD_SEPARATOR))
        }
        context.contentResolver.update(target.noteUri, cv, null, null) > 0
    }

    suspend fun getDeckId(deckName: String): Long = withContext(Dispatchers.IO) {
        findDeckId(deckName)
    }

    suspend fun findNotes(expression: String, modelName: String? = null, deckId: Long? = null): List<Long> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) {
                throw SecurityException("AnkiDroid permission not granted")
            }
            val ids = mutableListOf<Long>()
            val csum = fieldChecksum(expression)
            val cursor = context.contentResolver.query(
                NOTES_V2_URI,
                arrayOf(NOTE_ID, NOTE_MID),
                "$NOTE_CSUM=?",
                arrayOf(csum.toString()),
                null,
            ) ?: throw Exception("AnkiDroid duplicate query failed")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val nid = c.getLong(0)

                    if (deckId != null) {
                        if (!isNoteInDeck(nid, deckId)) continue
                    }

                    ids.add(nid)
                }
            }
            ids
        }

    suspend fun addNote(
        deckName: String,
        modelName: String,
        fields: Map<String, String>,
        tags: List<String>,
    ): Long = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw Exception("AnkiDroid permission not granted")

        val deckId = findDeckId(deckName)
        val modelId = findModelId(modelName)
        val fieldNames = getModelFields(modelId)

        val values = Array(fieldNames.size) { i ->
            fields[fieldNames[i]] ?: ""
        }

        val tagSet = tags.toMutableSet()

        val cv = ContentValues().apply {
            put(NOTE_MID, modelId)
            put(NOTE_FLDS, values.joinToString(FIELD_SEPARATOR))
            if (tagSet.isNotEmpty()) put(NOTE_TAGS, tagSet.joinToString(" "))
        }

        val result = try {
            context.contentResolver.insert(NOTES_URI, cv)
        } catch (e: Exception) {
            null
        } ?: throw Exception("AnkiDroid insert failed")

        val newNoteId = result.lastPathSegment?.toLongOrNull()
            ?: throw Exception("Failed to parse note ID from insert result")

        // This legacy API cannot return structured warnings, but the helper
        // records the committed partial-success diagnostic and must not turn
        // an existing note into a false failure result.
        moveCardsToDeckAfterCommittedNote(newNoteId, deckId)
        newNoteId
    }

    suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(NOTES_URI, noteId.toString())
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(NOTE_MID, NOTE_FLDS),
                null,
                null,
                null,
            ) ?: return@withContext false
            cursor.use { c ->
                if (!c.moveToFirst()) return@withContext false

                val modelId = c.getLong(0)
                val oldFields = splitFields(c.getString(1))
                val fieldNames = getModelFields(modelId)
                val newFields = Array(fieldNames.size) { i ->
                    when {
                        fields.containsKey(fieldNames[i]) -> fields[fieldNames[i]]!!
                        i < oldFields.size -> oldFields[i]
                        else -> ""
                    }
                }

                val cv = ContentValues().apply {
                    put(NOTE_FLDS, newFields.joinToString(FIELD_SEPARATOR))
                }
                context.contentResolver.update(uri, cv, null, null) > 0
            }
        }

    fun guiBrowse(query: String) {
        try {
            val uri = Uri.parse("anki://x-callback-url/browser?search=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.ichi2.anki")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_TASK_ON_HOME
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "guiBrowse", e)
        }
    }

    fun guiEditNote(noteId: Long) {
        // AnkiDroid's NoteEditor has no public intent filter for external apps.
        // The only supported external navigation is the Card Browser filtered by note ID.
        guiBrowse("nid:$noteId")
    }

    suspend fun storeMedia(filename: String, data: ByteArray): String =
        withContext(Dispatchers.IO) {
            val extension = AnkiMediaNaming.safeExtension(filename, "bin")
            val preferredBase = filename.substringBeforeLast('.', filename)
            saveMediaBytes(preferredBase, extension, data)
        }

    suspend fun storeMedia(source: AnkiMediaSource): String =
        withContext(Dispatchers.IO) {
            when (source) {
                is AnkiMediaSource.Bytes -> {
                    saveMediaBytes(
                        preferredBaseName = source.preferredBaseName,
                        extension = source.extension,
                        data = source.data,
                    )
                }
                is AnkiMediaSource.FileSource -> saveMediaFile(
                    file = source.file,
                    preferredBaseName = source.preferredBaseName,
                    extension = source.extension,
                    deleteAfterAttempt = source.ownership == AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT,
                )
            }
        }

    suspend fun storeMediaFromBase64(filename: String, base64: String): String =
        storeMedia(filename, Base64.decode(base64, Base64.NO_WRAP))

    suspend fun storeMediaFromUrl(filename: String, urlString: String): String =
        withContext(Dispatchers.IO) {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP ${conn.responseCode}")
                }
                val buffer = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        buffer.write(buf, 0, r)
                    }
                }
                val extension = AnkiMediaNaming.safeExtension(filename, "bin")
                saveMediaBytes(
                    preferredBaseName = filename.substringBeforeLast('.', filename),
                    extension = extension,
                    data = buffer.toByteArray(),
                )
            } finally {
                conn.disconnect()
            }
        }

    suspend fun storeMediaFromFile(filename: String, filePath: String): String =
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) throw Exception("File not found: $filePath")
            saveMediaFile(
                file = file,
                preferredBaseName = filename.substringBeforeLast('.', filename),
                extension = AnkiMediaNaming.safeExtension(filename, "bin"),
                deleteAfterAttempt = false,
            )
        }

    fun triggerSync() {
        try {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastSyncTimeMs
            if (elapsed < SYNC_COOLDOWN_MS) {
                Log.d(TAG, "triggerSync: skipped (cooldown ${(SYNC_COOLDOWN_MS - elapsed) / 1000}s remaining)")
                return
            }

            context.packageManager.getPackageInfo("com.ichi2.anki", 0)

            val intent = Intent("com.ichi2.anki.DO_SYNC").apply {
                setPackage("com.ichi2.anki")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            lastSyncTimeMs = now
            Log.d(TAG, "triggerSync: sent")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "triggerSync: AnkiDroid not installed")
        } catch (e: SecurityException) {
            Log.w(TAG, "triggerSync: permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "triggerSync: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ==========================================================================
    // Internal helpers
    // ==========================================================================

    private fun findDeckId(deckName: String): Long {
        findDeckIdOrNull(deckName)?.let { return it }
        throw AnkiDeckNotFoundException(deckName)
    }

    private fun findDeckIdOrNull(deckName: String): Long? {
        val cursor = context.contentResolver.query(
            DECKS_URI,
            arrayOf(DECK_ID, DECK_NAME),
            null,
            null,
            null,
        ) ?: throw Exception("AnkiDroid deck query failed")
        cursor.use { c ->
            val idIdx = c.getColumnIndex(DECK_ID)
            val nameIdx = c.getColumnIndex(DECK_NAME)
            if (idIdx == -1 || nameIdx == -1) throw Exception("Missing deck columns")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == deckName) return c.getLong(idIdx)
            }
        }
        return null
    }

    private fun createDeck(deckName: String): Long {
        val cv = ContentValues().apply {
            put(DECK_NAME, deckName)
        }
        val uri = context.contentResolver.insert(DECKS_URI, cv)
            ?: throw Exception("Failed to create deck '$deckName'")
        return uri.lastPathSegment?.toLongOrNull()
            ?: throw Exception("Failed to parse deck ID for '$deckName'")
    }

    private fun findModelId(modelName: String): Long {
        try {
            context.contentResolver.query(
                MODELS_URI,
                arrayOf(MODEL_ID, MODEL_NAME),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == modelName) return c.getLong(0)
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "findModelId failed", e)
        }
        throw Exception("Model '$modelName' not found")
    }

    private fun findModelInfoOrNull(modelName: String): ModelInfo? {
        val cursor = context.contentResolver.query(
            MODELS_URI,
            arrayOf(MODEL_ID, MODEL_NAME, MODEL_FIELD_NAMES),
            null,
            null,
            null,
        ) ?: throw Exception("AnkiDroid model query failed")
        cursor.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) != modelName) continue
                return ModelInfo(
                    id = c.getLong(0),
                    name = modelName,
                    fields = parseFieldNames(c.getString(2) ?: ""),
                )
            }
        }
        return null
    }

    private fun getModelFields(modelId: Long): List<String> {
        try {
            context.contentResolver.query(
                MODELS_URI,
                arrayOf(MODEL_ID, MODEL_FIELD_NAMES),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) == modelId) {
                        val rawData = c.getString(1) ?: continue
                        return parseFieldNames(rawData)
                    }
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getModelFields failed", e)
        }
        throw Exception("Model fields not found for ID: $modelId")
    }

    private fun readModelInfos(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        try {
            context.contentResolver.query(
                MODELS_URI,
                arrayOf(MODEL_ID, MODEL_NAME, MODEL_FIELD_NAMES),
                null,
                null,
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndex(MODEL_ID)
                val nameIdx = c.getColumnIndex(MODEL_NAME)
                val fieldsIdx = c.getColumnIndex(MODEL_FIELD_NAMES)
                if (idIdx == -1 || nameIdx == -1 || fieldsIdx == -1) return@use
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val fields = parseFieldNames(c.getString(fieldsIdx) ?: "")
                    models += ModelInfo(c.getLong(idIdx), name, fields)
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "readModelInfos failed", e)
        }
        return models
    }

    private fun createLapisModel(modelName: String): Long {
        val deckId = findDeckIdOrNull(LapisPreset.DEFAULT_DECK_NAME)
        return createLapisModel(
            modelName = modelName,
            assets = readLapisModelAssets(),
            deckId = deckId,
        )
    }

    private fun createLapisModel(
        modelName: String,
        assets: AnkiLapisModelAssets,
        deckId: Long?,
    ): Long {
        val modelValues = ContentValues().apply {
            put(MODEL_NAME, modelName)
            put(MODEL_FIELD_NAMES, LapisPreset.fields.joinToString(FIELD_SEPARATOR))
            put(MODEL_NUM_CARDS, 1)
            put(MODEL_CSS, assets.css)
            put(MODEL_SORT_FIELD_INDEX, 0)
            if (deckId != null) put(MODEL_DECK_ID, deckId)
        }

        val modelUri = context.contentResolver.insert(MODELS_URI, modelValues)
            ?: throw Exception("Failed to create Anki model '$modelName'")
        val modelId = modelUri.lastPathSegment?.toLongOrNull()
            ?: throw Exception("Failed to parse Anki model ID for '$modelName'")

        val templatesUri = Uri.withAppendedPath(modelUri, "templates")
        val firstTemplateUri = Uri.withAppendedPath(templatesUri, "0")
        val templateValues = ContentValues().apply {
            put(CARD_TEMPLATE_NAME, "Card 1")
            put(CARD_TEMPLATE_QUESTION_FORMAT, assets.front)
            put(CARD_TEMPLATE_ANSWER_FORMAT, assets.back)
        }
        requireProviderRowsUpdated(
            count = context.contentResolver.update(firstTemplateUri, templateValues, null, null),
            operation = "configure Anki model template",
        )

        return modelId
    }

    private fun readLapisModelAssets(): AnkiLapisModelAssets =
        AnkiLapisModelAssets(
            css = readLapisAsset("styling.css"),
            front = readLapisAsset("front.html"),
            back = readLapisAsset("back.html"),
        )

    private fun readLapisAsset(filename: String): String =
        context.assets.open("lapis/$filename").use { input ->
            input.bufferedReader(StandardCharsets.UTF_8).readText()
        }

    private fun parseFieldNames(rawData: String): List<String> {
        val trimmed = rawData.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                val json = org.json.JSONArray(trimmed)
                return (0 until json.length()).map { i ->
                    val item = json.get(i)
                    if (item is org.json.JSONObject) item.optString("name") else item.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Field parse error", e)
            }
        }
        return splitFields(rawData)
    }

    private fun moveCardsToDeck(noteId: Long, targetDeckId: Long) {
        val cardsUri = Uri.withAppendedPath(NOTES_URI, "$noteId/cards")
        val cursor = context.contentResolver.query(
            cardsUri,
            arrayOf("ord", "deck_id"),
            null,
            null,
            null,
        ) ?: throw Exception("AnkiDroid card query failed after note creation")
        cursor.use { c ->
            while (c.moveToNext()) {
                val ord = c.getInt(0)
                val currentDeckId = c.getLong(1)
                if (currentDeckId != targetDeckId) {
                    val cardUri = Uri.withAppendedPath(cardsUri, ord.toString())
                    val cv = ContentValues().apply {
                        put("deck_id", targetDeckId)
                    }
                    requireProviderRowsUpdated(
                        count = context.contentResolver.update(cardUri, cv, null, null),
                        operation = "move Anki card $ord to deck",
                    )
                }
            }
        }
    }

    private fun moveCardsToDeckAfterCommittedNote(
        noteId: Long,
        targetDeckId: Long,
    ): List<AnkiWriteWarning> {
        return try {
            moveCardsToDeck(noteId, targetDeckId)
            emptyList()
        } catch (e: Exception) {
            // The note insertion is already committed and AnkiDroid offers no
            // transaction/rollback API. Preserve success semantics while
            // surfacing the incomplete deck assignment to the caller.
            xLogE("Note $noteId was created but its cards could not be moved to deck $targetDeckId", e)
            listOf(AnkiWriteWarning.NoteCreatedDeckMoveFailed)
        }
    }

    private fun saveMediaBytes(
        preferredBaseName: String,
        extension: String,
        data: ByteArray,
    ): String {
        require(data.isNotEmpty()) { "Media data is empty" }
        val mediaDir = File(context.cacheDir, "anki_media").apply {
            check(mkdirs() || isDirectory) { "Failed to create Anki media cache" }
        }
        val safeBase = sanitizePreferredBaseName(preferredBaseName)
        val safeExtension = AnkiMediaNaming.safeExtension(extension, "bin")
        val file = File.createTempFile("${safeBase}_", ".$safeExtension", mediaDir)
        return try {
            file.outputStream().use { it.write(data) }
            saveMediaFile(
                file = file,
                preferredBaseName = safeBase,
                extension = safeExtension,
                deleteAfterAttempt = true,
            )
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun saveMediaFile(
        file: File,
        preferredBaseName: String,
        extension: String,
        deleteAfterAttempt: Boolean,
    ): String {
        require(file.isFile && file.canRead() && file.length() > 0L) { "Media file is not readable" }
        val safeBase = sanitizePreferredBaseName(preferredBaseName)
        val expectedExtension = AnkiMediaNaming.safeExtension(extension, "bin")
        require(file.extension.equals(expectedExtension, ignoreCase = true)) {
            "Media file extension does not match its provider type"
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file,
            )
            var granted = false
            try {
                context.grantUriPermission(
                    ANKIDROID_PACKAGE,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                granted = true
                val mediaCv = ContentValues().apply {
                    put(MEDIA_FILE_URI, contentUri.toString())
                    // AnkiDroid treats this as a prefix and derives the extension from
                    // the FileProvider MIME type.
                    put(MEDIA_PREFERRED_NAME, safeBase)
                }
                val result = context.contentResolver.insert(MEDIA_URI, mediaCv)
                    ?: throw Exception("AnkiDroid failed to copy the media")
                return result.lastPathSegment
                    ?.takeIf(String::isNotBlank)
                    ?: result.path
                        ?.let(::File)
                        ?.name
                        ?.takeIf(String::isNotBlank)
                    ?: throw Exception("AnkiDroid returned an invalid media name")
            } finally {
                if (granted) {
                    runCatching {
                        context.revokeUriPermission(
                            ANKIDROID_PACKAGE,
                            contentUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        } finally {
            if (deleteAfterAttempt) {
                file.delete()
            }
        }
    }

    private fun sanitizePreferredBaseName(value: String): String {
        return value
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .take(96)
            .ifBlank { "chimahon_media" }
    }

    private fun isNoteInDeck(noteId: Long, deckId: Long): Boolean {
        val noteUri = Uri.withAppendedPath(NOTES_URI, noteId.toString())
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        var inDeck = false
        val cursor = context.contentResolver.query(
            cardsUri,
            arrayOf("deck_id"),
            null,
            null,
            null,
        ) ?: throw Exception("AnkiDroid deck-membership query failed")
        cursor.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(0) == deckId) {
                    inDeck = true
                    break
                }
            }
        }
        return inDeck
    }

    private val stylePattern = Regex("(?s)<style.*?>.*?</style>")
    private val scriptPattern = Regex("(?s)<script.*?>.*?</script>")
    private val tagPattern = Regex("<.*?>")
    private val imgPattern = Regex("<img src=[\"']?([^\"'>]+)[\"']? ?/?>")

    private fun entsToTxt(htmlText: String): String {
        val htmlReplaced = htmlText.replace("&nbsp;", " ")
        val sb = StringBuffer()
        val matcher = java.util.regex.Pattern.compile("&#?\\w+;").matcher(htmlReplaced)
        while (matcher.find()) {
            val entity = matcher.group()
            val decoded = android.text.Html.fromHtml(entity, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            matcher.appendReplacement(sb, decoded)
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun stripHTML(s: String): String {
        var strRep = stylePattern.replace(s, "")
        strRep = scriptPattern.replace(strRep, "")
        strRep = tagPattern.replace(strRep, "")
        return entsToTxt(strRep)
    }

    private fun stripHTMLMedia(s: String): String {
        val replacedImg = imgPattern.replace(s) { matchResult ->
            " ${matchResult.groupValues[1]} "
        }
        return stripHTML(replacedImg)
    }

    private fun fieldChecksum(data: String): Long {
        val sha1Zeroes = "0000000000000000000000000000000000000000"
        val strippedData = stripHTMLMedia(data)

        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest(strippedData.toByteArray(StandardCharsets.UTF_8))
            val bigInteger = BigInteger(1, digest)
            var result = bigInteger.toString(16)

            if (result.length < 40) {
                result = sha1Zeroes.substring(0, sha1Zeroes.length - result.length) + result
            }
            result.substring(0, 8).toLong(16)
        } catch (e: Exception) {
            Log.e(TAG, "Error making field checksum", e)
            0L
        }
    }

    private fun splitFields(str: String): List<String> =
        str.split(FIELD_SEPARATOR)
}
