// SPDX-License-Identifier: MIT

package chimahon.anki

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.immersion.model.AnkiInventoryFailure
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.service.AnkiInventoryConfiguration
import tachiyomi.domain.immersion.service.AnkiInventoryProvider
import tachiyomi.domain.immersion.service.AnkiInventoryProviderException
import tachiyomi.domain.immersion.service.AnkiProviderCapability
import tachiyomi.domain.immersion.service.AnkiProviderCard
import tachiyomi.domain.immersion.service.AnkiProviderInventory
import tachiyomi.domain.immersion.service.AnkiProviderNote
import tachiyomi.domain.immersion.service.AnkiProviderQueryMetrics

/**
 * Read-only adapter for AnkiDroid's official flashcards ContentProvider.
 *
 * Card scheduler state has no modification timestamp in the public provider, so
 * maturity refreshes intentionally use an atomic full snapshot.
 */
class AnkiDroidInventoryProvider(
    private val context: Context,
) : AnkiInventoryProvider {

    override suspend fun probe(enabled: Boolean): AnkiProviderCapability = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext unavailable(AnkiInventoryFailure.DISABLED)
        val packageInfo = try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext unavailable(AnkiInventoryFailure.NOT_INSTALLED)
        }
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext unavailable(
                AnkiInventoryFailure.PERMISSION_DENIED,
                packageInfo.versionName,
            )
        }
        try {
            context.contentResolver.query(
                MODELS_URI,
                arrayOf(MODEL_ID, MODEL_NAME, MODEL_FIELD_NAMES),
                null,
                null,
                null,
            )?.close() ?: return@withContext unavailable(
                AnkiInventoryFailure.UNSUPPORTED_PROVIDER,
                packageInfo.versionName,
            )
        } catch (_: SecurityException) {
            return@withContext unavailable(
                AnkiInventoryFailure.PERMISSION_DENIED,
                packageInfo.versionName,
            )
        } catch (_: Exception) {
            return@withContext unavailable(
                AnkiInventoryFailure.UNSUPPORTED_PROVIDER,
                packageInfo.versionName,
            )
        }
        AnkiProviderCapability(
            state = CapabilityState.AVAILABLE,
            packageVersion = packageInfo.versionName,
            noteModificationTime = true,
            cardModificationTime = false,
            reviewHistory = false,
        )
    }

    override suspend fun load(
        configuration: AnkiInventoryConfiguration,
    ): AnkiProviderInventory = withContext(Dispatchers.IO) {
        val capability = probe(configuration.enabled)
        if (capability.state != CapabilityState.AVAILABLE) {
            throw AnkiInventoryProviderException(
                capability.failure ?: AnkiInventoryFailure.PROVIDER_ERROR,
                "AnkiDroid inventory is unavailable",
            )
        }
        try {
            val model = findModel(configuration.noteTypeName)
                ?: throw AnkiInventoryProviderException(
                    AnkiInventoryFailure.MISCONFIGURED_FIELDS,
                    "Configured AnkiDroid note type does not exist",
                )
            val deckId = findDeckId(configuration.deckName)
                ?: throw AnkiInventoryProviderException(
                    AnkiInventoryFailure.MISCONFIGURED_FIELDS,
                    "Configured AnkiDroid deck does not exist",
                )
            val requiredFields = buildSet {
                add(configuration.expressionField)
                configuration.readingField?.takeIf(String::isNotBlank)?.let(::add)
                configuration.characterField?.takeIf(String::isNotBlank)?.let(::add)
            }
            if (!model.fieldNames.containsAll(requiredFields)) {
                throw AnkiInventoryProviderException(
                    AnkiInventoryFailure.MISCONFIGURED_FIELDS,
                    "Configured AnkiDroid fields do not exist",
                )
            }

            val noteStart = SystemClock.elapsedRealtime()
            val notes = readNotes(model)
            val noteQueryMillis = SystemClock.elapsedRealtime() - noteStart

            val cardStart = SystemClock.elapsedRealtime()
            val cards = readCards(configuration, deckId)
            val cardQueryMillis = SystemClock.elapsedRealtime() - cardStart
            val cardNoteIds = cards.asSequence().map(AnkiProviderCard::noteId).toHashSet()

            AnkiProviderInventory(
                capability = capability,
                notes = notes.filter { it.id in cardNoteIds },
                cards = cards,
                metrics = AnkiProviderQueryMetrics(noteQueryMillis, cardQueryMillis),
            )
        } catch (error: AnkiInventoryProviderException) {
            throw error
        } catch (error: SecurityException) {
            throw AnkiInventoryProviderException(
                AnkiInventoryFailure.PERMISSION_DENIED,
                "AnkiDroid permission was revoked during refresh",
                error,
            )
        } catch (error: UnsupportedOperationException) {
            throw AnkiInventoryProviderException(
                AnkiInventoryFailure.UNSUPPORTED_PROVIDER,
                "Installed AnkiDroid does not expose required scheduler fields",
                error,
            )
        } catch (error: Exception) {
            throw AnkiInventoryProviderException(
                AnkiInventoryFailure.PROVIDER_ERROR,
                "AnkiDroid inventory query failed",
                error,
            )
        }
    }

    private fun findModel(name: String): Model? {
        val cursor = context.contentResolver.query(
            MODELS_URI,
            arrayOf(MODEL_ID, MODEL_NAME, MODEL_FIELD_NAMES),
            null,
            null,
            null,
        ) ?: return null
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(MODEL_ID)
            val nameIndex = it.getColumnIndexOrThrow(MODEL_NAME)
            val fieldsIndex = it.getColumnIndexOrThrow(MODEL_FIELD_NAMES)
            while (it.moveToNext()) {
                if (it.getString(nameIndex) == name) {
                    return Model(
                        id = it.getLong(idIndex),
                        fieldNames = parseFieldNames(it.getString(fieldsIndex).orEmpty()),
                    )
                }
            }
        }
        return null
    }

    private fun findDeckId(name: String): Long? {
        val cursor = context.contentResolver.query(
            DECKS_URI,
            arrayOf(DECK_ID, DECK_NAME),
            null,
            null,
            null,
        ) ?: return null
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DECK_ID)
            val nameIndex = it.getColumnIndexOrThrow(DECK_NAME)
            while (it.moveToNext()) {
                if (it.getString(nameIndex) == name) return it.getLong(idIndex)
            }
        }
        return null
    }

    private fun readNotes(model: Model): List<AnkiProviderNote> {
        val cursor = context.contentResolver.query(
            NOTES_V2_URI,
            arrayOf(NOTE_ID, NOTE_MID, NOTE_MOD, NOTE_FLDS),
            "$NOTE_MID = ?",
            arrayOf(model.id.toString()),
            "$NOTE_ID ASC",
        ) ?: throw AnkiInventoryProviderException(
            AnkiInventoryFailure.PARTIAL_RESULT,
            "AnkiDroid note query returned no cursor",
        )
        cursor.requireBounded()
        return cursor.use {
            val result = ArrayList<AnkiProviderNote>(it.count)
            val idIndex = it.getColumnIndexOrThrow(NOTE_ID)
            val midIndex = it.getColumnIndexOrThrow(NOTE_MID)
            val modIndex = it.getColumnIndexOrThrow(NOTE_MOD)
            val fieldsIndex = it.getColumnIndexOrThrow(NOTE_FLDS)
            while (it.moveToNext()) {
                val values = it.getString(fieldsIndex).orEmpty().split(FIELD_SEPARATOR)
                result += AnkiProviderNote(
                    id = it.getLong(idIndex),
                    noteTypeId = it.getLong(midIndex),
                    modifiedAtEpochSeconds = it.getLongOrNull(modIndex),
                    fields = model.fieldNames.mapIndexed { index, field ->
                        field to values.getOrElse(index) { "" }
                    }.toMap(),
                )
            }
            result
        }
    }

    private fun readCards(
        configuration: AnkiInventoryConfiguration,
        deckId: Long,
    ): List<AnkiProviderCard> {
        val browserQuery = buildString {
            append("deck:\"")
            append(configuration.deckName.escapeBrowserQuery())
            append("\" note:\"")
            append(configuration.noteTypeName.escapeBrowserQuery())
            append('"')
        }
        val cursor = context.contentResolver.query(
            CARDS_URI,
            CARD_PROJECTION,
            browserQuery,
            null,
            null,
        ) ?: throw AnkiInventoryProviderException(
            AnkiInventoryFailure.PARTIAL_RESULT,
            "AnkiDroid card query returned no cursor",
        )
        cursor.requireBounded()
        return cursor.use {
            val result = ArrayList<AnkiProviderCard>(it.count)
            while (it.moveToNext()) {
                val rowDeckId = it.getLong(it.getColumnIndexOrThrow(CARD_DECK_ID))
                if (rowDeckId != deckId) continue
                result += AnkiProviderCard(
                    id = it.getLong(it.getColumnIndexOrThrow(CARD_ID)),
                    noteId = it.getLong(it.getColumnIndexOrThrow(CARD_NOTE_ID)),
                    deckId = rowDeckId,
                    type = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_TYPE)),
                    queue = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_QUEUE)),
                    intervalDays = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_INTERVAL)),
                    due = it.getLongOrNull(it.getColumnIndexOrThrow(CARD_DUE)),
                    repetitions = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_REPS)),
                    lapses = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_LAPSES)),
                    ease = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_EASE)),
                )
            }
            result
        }
    }

    private fun Cursor.requireBounded() {
        if (count > MAX_PROVIDER_ROWS) {
            close()
            throw AnkiInventoryProviderException(
                AnkiInventoryFailure.PARTIAL_RESULT,
                "AnkiDroid query exceeded the bounded snapshot limit",
            )
        }
    }

    private data class Model(
        val id: Long,
        val fieldNames: List<String>,
    )

    companion object {
        private const val PACKAGE_NAME = "com.ichi2.anki"
        private const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val FIELD_SEPARATOR = "\u001f"
        private const val MAX_PROVIDER_ROWS = 250_000

        private val BASE_URI = Uri.parse("content://com.ichi2.anki.flashcards")
        private val NOTES_V2_URI = Uri.withAppendedPath(BASE_URI, "notes_v2")
        private val MODELS_URI = Uri.withAppendedPath(BASE_URI, "models")
        private val DECKS_URI = Uri.withAppendedPath(BASE_URI, "decks")
        private val CARDS_URI = Uri.withAppendedPath(BASE_URI, "cards")

        private const val NOTE_ID = "_id"
        private const val NOTE_MID = "mid"
        private const val NOTE_MOD = "mod"
        private const val NOTE_FLDS = "flds"
        private const val MODEL_ID = "_id"
        private const val MODEL_NAME = "name"
        private const val MODEL_FIELD_NAMES = "field_names"
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
        private const val CARD_ID = "_id"
        private const val CARD_NOTE_ID = "note_id"
        private const val CARD_DECK_ID = "deck_id"
        private const val CARD_TYPE = "type"
        private const val CARD_QUEUE = "queue"
        private const val CARD_INTERVAL = "interval"
        private const val CARD_DUE = "due"
        private const val CARD_REPS = "reps"
        private const val CARD_LAPSES = "lapses"
        private const val CARD_EASE = "sm2_factor"

        private val CARD_PROJECTION = arrayOf(
            CARD_ID,
            CARD_NOTE_ID,
            CARD_DECK_ID,
            CARD_TYPE,
            CARD_QUEUE,
            CARD_INTERVAL,
            CARD_DUE,
            CARD_REPS,
            CARD_LAPSES,
            CARD_EASE,
        )

        private fun unavailable(
            failure: AnkiInventoryFailure,
            packageVersion: String? = null,
        ) = AnkiProviderCapability(
            state = CapabilityState.UNAVAILABLE,
            packageVersion = packageVersion,
            noteModificationTime = false,
            cardModificationTime = false,
            reviewHistory = false,
            failure = failure,
        )
    }
}

private fun parseFieldNames(rawData: String): List<String> {
    val trimmed = rawData.trim()
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        runCatching {
            val json = JSONArray(trimmed)
            return (0 until json.length()).map { index ->
                when (val item = json.get(index)) {
                    is JSONObject -> item.optString("name")
                    else -> item.toString()
                }
            }
        }
    }
    return rawData.split("\u001f")
}

private fun Cursor.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun Cursor.getIntOrNull(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

private fun String.escapeBrowserQuery(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
