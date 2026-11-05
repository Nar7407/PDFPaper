package com.pdfpaper.engine.stamp

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The text a dynamic stamp adds at placement time (task T23): "By <author>"
 * and the date time. Both are written in UTC so a stamp placed in any time
 * zone reads the same everywhere it is opened.
 */
internal object StampText {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

    /** "yyyy-MM-dd HH:mm" in UTC for [millis]. */
    fun date(millis: Long): String = DATE.format(Instant.ofEpochMilli(millis))

    /** The small lines of a dynamic stamp: the author line (when known) and the date. */
    fun lines(author: String?, millis: Long): List<String> =
        if (author.isNullOrBlank()) listOf(date(millis)) else listOf("By $author", date(millis))

    /** One line for the annotation's /Contents, so a comment list shows the stamp. */
    fun contents(label: String, author: String?, millis: Long): String =
        if (author.isNullOrBlank()) "$label - ${date(millis)}" else "$label - By $author, ${date(millis)}"
}
