package com.pdfpaper.convert.ooxml

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A ZIP container of named parts: the Open Packaging Conventions layer that
 * every OOXML file (pptx, docx, xlsx) is built on (ISO/IEC 29500-2 §9).
 *
 * Writing is deterministic — parts keep insertion order and every ZIP entry
 * gets the same fixed timestamp — so two conversions of the same input are
 * byte-identical, which makes the tests meaningful.
 */
class OpcPackage {

    private val parts = LinkedHashMap<String, ByteArray>()

    fun add(name: String, bytes: ByteArray): OpcPackage {
        require(!name.startsWith("/")) { "part names are relative: $name" }
        parts[name] = bytes
        return this
    }

    fun addXml(name: String, xml: String): OpcPackage = add(name, xml.toByteArray(Charsets.UTF_8))

    fun contains(name: String): Boolean = parts.containsKey(name)

    fun partNames(): List<String> = parts.keys.toList()

    fun partBytes(name: String): ByteArray? = parts[name]

    /** Serializes the package as a ZIP archive ([Content_Types].xml first). */
    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream(1 shl 18)
        ZipOutputStream(out).use { zip ->
            val ordered = parts.keys.sortedWith(compareBy({ it != CONTENT_TYPES }, { it }))
            for (name in ordered) {
                val entry = ZipEntry(name)
                // Fixed timestamp: reproducible archives and no ZIP64 surprises.
                entry.timeLocal = FIXED_TIME
                entry.method = ZipEntry.DEFLATED
                zip.putNextEntry(entry)
                zip.write(parts.getValue(name))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val CONTENT_TYPES = "[Content_Types].xml"
        private val FIXED_TIME: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0)

        /** Reads a package back into part name → bytes (size-capped by the caller). */
        fun read(archive: ByteArray): Map<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return out
        }
    }
}
