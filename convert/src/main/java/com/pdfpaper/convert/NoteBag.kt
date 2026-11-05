package com.pdfpaper.convert

/**
 * Warnings collected while reading or laying out a document; the same note is
 * only reported once. Every converter (`DocxToPdf`, `PptxToPdf`) owns one and
 * hands its content to the conversion report, so nothing is dropped silently.
 */
internal class NoteBag {
    private val seen = LinkedHashSet<String>()

    fun add(note: String) {
        seen.add(note)
    }

    fun addAll(notes: Collection<String>) {
        seen.addAll(notes)
    }

    fun toList(): List<String> = seen.toList()
}
