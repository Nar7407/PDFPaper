package com.pdfpaper.engine.compress

import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfNull
import com.pdfpaper.engine.model.PdfObject
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.write.PdfDocumentBuilder
import java.security.MessageDigest

/**
 * Copies the object graph of a document into a new file, rewriting the streams
 * on the way and letting identical streams share one object.
 *
 * Only objects that are actually reachable are copied, so the objects nothing
 * points at are gone in the output. Shared objects stay shared: the first
 * reference copies the body, every later reference points at the same copy.
 */
internal class CompressGraphCopier(
    private val source: PdfDocument,
    private val builder: PdfDocumentBuilder,
    /** Rewrites one stream; the result is what gets written. */
    private val transform: (Int, PdfStream) -> PdfStream
) {

    private val memo = HashMap<Int, PdfRef>()
    private val sharedStreams = HashMap<String, PdfRef>()

    /** Number of streams that ended up sharing an object with an earlier one. */
    var duplicatesShared: Int = 0
        private set

    /** Copies the indirect object [objNum] and returns its new reference. */
    fun copyIndirect(objNum: Int): PdfRef? {
        memo[objNum]?.let { return it }
        val obj = source.resolve(PdfRef(objNum, 0)) ?: return null
        if (obj is PdfStream) {
            val rewritten = transform(objNum, obj)
            val key = contentKey(rewritten)
            val existing = sharedStreams[key]
            if (existing != null) {
                duplicatesShared++
                memo[objNum] = existing
                return existing
            }
            val ref = PdfRef(builder.allocate(), 0)
            memo[objNum] = ref
            sharedStreams[key] = ref
            builder.put(ref.objNum, PdfStream(copyDict(rewritten.dict), rewritten.data))
            return ref
        }
        val ref = PdfRef(builder.allocate(), 0)
        memo[objNum] = ref
        builder.put(ref.objNum, copyValue(obj) ?: PdfNull)
        return ref
    }

    /** Copies a direct value, remapping every reference it contains. */
    fun copyValue(obj: PdfObject?): PdfObject? = when (obj) {
        null -> null
        is PdfRef -> copyIndirect(obj.objNum)
        is PdfArray -> PdfArray(obj.items.map { copyValue(it) ?: PdfNull })
        is PdfDict -> copyDict(obj)
        is PdfStream -> {
            val rewritten = transform(0, obj)
            PdfStream(copyDict(rewritten.dict), rewritten.data)
        }
        else -> obj
    }

    private fun copyDict(dict: PdfDict): PdfDict {
        val entries = LinkedHashMap<PdfName, PdfObject>()
        for ((key, value) in dict.entries) {
            entries[key] = copyValue(value) ?: PdfNull
        }
        return PdfDict(entries)
    }

    /**
     * The identity of a stream: its bytes plus the parts of its dictionary
     * that change how it is drawn. Two streams with the same key can share one
     * object, because they would be drawn the same way.
     */
    private fun contentKey(stream: PdfStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(stream.data)
        val builder = StringBuilder(digest.digest().joinToString("") { "%02x".format(it) })
        for ((key, value) in stream.dict.entries) {
            builder.append('|').append(key.name).append('=').append(describe(value))
        }
        return builder.toString()
    }

    /** References are described by their source object number. */
    private fun describe(value: PdfObject): String = when (value) {
        is PdfRef -> "R${value.objNum}"
        is PdfName -> "/" + value.name
        is PdfArray -> "[" + value.items.joinToString(",") { describe(it) } + "]"
        is PdfDict -> "<<" + value.entries.entries.joinToString(",") { (k, v) -> "${k.name}=${describe(v)}" } + ">>"
        is PdfStream -> "stream"
        else -> value.toString()
    }
}
