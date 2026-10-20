package com.pdfpaper.app.signatures

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * App-private storage for reusable visual signatures (task UI-3).
 *
 * The JSON list is encrypted with [KeystoreCipher], so both the drawn strokes
 * and any embedded image bytes are unreadable without the Android Keystore
 * key.  Nothing is uploaded and no password is involved: the user unlocks the
 * device, not a PDF.
 */
class SavedSignatureStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    fun load(): Result<List<SavedSignature>> = runCatching {
        val encoded = prefs.getString(KEY_ITEMS, null) ?: return@runCatching emptyList()
        val envelope = Base64.decode(encoded, Base64.NO_WRAP)
        val json = String(cipher.decrypt(envelope), Charsets.UTF_8)
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                runCatching { parse(array.getJSONObject(i)) }.getOrNull()?.let(::add)
            }
        }.sortedByDescending { it.createdAt }
    }

    /** Saves a draft and returns the stored signature plus the refreshed list. */
    fun save(draft: SignatureDraft): Result<Pair<SavedSignature, List<SavedSignature>>> = runCatching {
        require(!draft.isEmpty()) { "The signature is empty." }
        val existing = load().getOrThrow()
        val saved = SavedSignature(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            draft = draft
        )
        val next = (listOf(saved) + existing).take(MAX_SAVED)
        persist(next)
        saved to next
    }

    fun delete(id: String): Result<List<SavedSignature>> = runCatching {
        val next = load().getOrThrow().filterNot { it.id == id }
        persist(next)
        next
    }

    private fun persist(items: List<SavedSignature>) {
        val array = JSONArray()
        for (item in items) array.put(serialize(item))
        val encrypted = cipher.encrypt(array.toString().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(KEY_ITEMS, encoded).commit()) {
            "Could not write to app-private storage."
        }
    }

    private fun serialize(item: SavedSignature): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("createdAt", item.createdAt)
        when (val draft = item.draft) {
            is SignatureDraft.Drawn -> {
                put("kind", "drawn")
                put("strokes", JSONArray().apply {
                    for (stroke in draft.strokes) {
                        put(JSONArray().apply {
                            for (point in stroke) {
                                put(JSONObject().apply {
                                    put("x", point.x.toDouble())
                                    put("y", point.y.toDouble())
                                    put("pressure", point.pressure.toDouble())
                                })
                            }
                        })
                    }
                })
            }

            is SignatureDraft.Typed -> {
                put("kind", "typed")
                put("name", draft.name)
                put("font", draft.font.name)
            }

            is SignatureDraft.Image -> {
                put("kind", "image")
                put("image", Base64.encodeToString(draft.bytes, Base64.NO_WRAP))
            }
        }
    }

    private fun parse(json: JSONObject): SavedSignature {
        val id = json.getString("id")
        val createdAt = json.optLong("createdAt", 0L)
        val draft = when (json.getString("kind")) {
            "drawn" -> {
                val strokesJson = json.optJSONArray("strokes") ?: JSONArray()
                val strokes = buildList {
                    for (i in 0 until strokesJson.length()) {
                        val strokeJson = strokesJson.optJSONArray(i) ?: continue
                        val points = buildList {
                            for (j in 0 until strokeJson.length()) {
                                val point = strokeJson.optJSONObject(j) ?: continue
                                add(
                                    SignatureSample(
                                        x = point.optDouble("x", 0.0).toFloat(),
                                        y = point.optDouble("y", 0.0).toFloat(),
                                        pressure = point.optDouble("pressure", 1.0)
                                            .toFloat()
                                            .coerceIn(0.15f, 2f)
                                    )
                                )
                            }
                        }
                        if (points.isNotEmpty()) add(points)
                    }
                }
                SignatureDraft.Drawn(strokes)
            }

            "typed" -> SignatureDraft.Typed(
                name = json.optString("name").take(MAX_NAME_LENGTH),
                font = runCatching {
                    SignatureFontStyle.valueOf(json.optString("font"))
                }.getOrDefault(SignatureFontStyle.SCRIPT)
            )

            "image" -> {
                val bytes = runCatching {
                    Base64.decode(json.getString("image"), Base64.NO_WRAP)
                }.getOrDefault(ByteArray(0))
                SignatureDraft.Image(bytes)
            }

            else -> error("unknown signature kind")
        }
        require(!draft.isEmpty()) { "stored signature is empty" }
        return SavedSignature(id, createdAt, draft)
    }

    private companion object {
        const val PREFS = "pdfpaper_saved_signatures"
        const val KEY_ITEMS = "items_v1"
        const val KEY_ALIAS = "pdfpaper.visual-signatures.v1"
        const val MAX_SAVED = 24
        const val MAX_NAME_LENGTH = 120
    }
}
