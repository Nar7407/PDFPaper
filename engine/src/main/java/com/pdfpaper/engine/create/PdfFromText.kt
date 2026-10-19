package com.pdfpaper.engine.create

import com.pdfpaper.engine.edit.Standard14
import com.pdfpaper.engine.edit.StandardFont
import java.io.File

/** Options for [PdfFromText]. [margin] is in points on every side. */
data class TextPdfOptions(
    val pageSize: PageSize = PageSize.A4,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val margin: Double = 54.0,
    val fontSize: Double = 12.0,
    val font: StandardFont = StandardFont.HELVETICA,
    /** Line height as a multiple of [fontSize]. */
    val lineSpacing: Double = 1.2,
    val metadata: PdfMetadata = PdfMetadata()
)

/**
 * Lays plain text out on standard pages, wrapping at spaces and starting a new
 * page when the margin is reached. Blank lines are preserved as vertical space.
 *
 * Each line is drawn at its baseline with [TextPdfOptions.font]; the default is
 * Helvetica 12 pt, so no font program needs embedding.
 */
object PdfFromText {

    /** Wraps [text] into lines no wider than [maxWidth] points. */
    fun wrap(
        text: String,
        maxWidth: Double,
        font: StandardFont = StandardFont.HELVETICA,
        fontSize: Double = 12.0
    ): List<String> {
        require(maxWidth.isFinite() && maxWidth > 0.0) { "maxWidth must be finite and positive" }
        require(fontSize.isFinite() && fontSize > 0.0) { "fontSize must be finite and positive" }
        require(font.winAnsi) { "${font.baseFont} does not use WinAnsi encoding" }

        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').removeSuffix("\n")
        if (normalized.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        for (paragraph in normalized.split('\n')) {
            if (paragraph.isBlank()) {
                lines += ""
                continue
            }
            var line = ""
            for (word in paragraph.trim().split(WHITESPACE)) {
                for (part in breakWord(word, maxWidth, font, fontSize)) {
                    when {
                        line.isEmpty() -> line = part
                        width(font, "$line $part", fontSize) <= maxWidth -> line = "$line $part"
                        else -> {
                            lines += line
                            line = part
                        }
                    }
                }
            }
            if (line.isNotEmpty()) lines += line
        }
        return lines
    }

    /** Builds the PDF from [text], starting a new page at each bottom margin. */
    fun create(text: String, options: TextPdfOptions = TextPdfOptions()): ByteArray {
        require(options.margin.isFinite() && options.margin >= 0.0) {
            "margin must be finite and non-negative"
        }
        require(options.fontSize.isFinite() && options.fontSize > 0.0) {
            "fontSize must be finite and positive"
        }
        require(options.lineSpacing.isFinite() && options.lineSpacing > 0.0) {
            "lineSpacing must be finite and positive"
        }
        require(options.font.winAnsi) { "${options.font.baseFont} does not use WinAnsi encoding" }

        val page = options.pageSize.oriented(options.orientation)
        val contentWidth = page.width - 2.0 * options.margin
        val contentHeight = page.height - 2.0 * options.margin
        require(contentWidth > 0.0 && contentHeight > 0.0) { "margins leave no room on the page" }

        val lines = wrap(text, contentWidth, options.font, options.fontSize)
        val builder = PdfBuilder(options.pageSize, options.orientation, options.metadata)
        if (lines.isEmpty()) {
            builder.addPage()
            return builder.build()
        }

        val lineHeight = options.fontSize * options.lineSpacing
        val linesPerPage = (contentHeight / lineHeight).toInt().coerceAtLeast(1)
        val ascent = Standard14.ascent(options.font, options.fontSize)
        for (chunk in lines.chunked(linesPerPage)) {
            val pageBuilder = builder.addPage()
            chunk.forEachIndexed { index, line ->
                if (line.isNotEmpty()) {
                    pageBuilder.drawText(
                        line,
                        options.margin,
                        options.margin + ascent + index * lineHeight,
                        options.fontSize,
                        options.font
                    )
                }
            }
        }
        return builder.build()
    }

    /** Reads [file] as UTF-8 (a leading BOM is dropped) and builds the PDF; never throws. */
    fun createFile(file: File, options: TextPdfOptions = TextPdfOptions()): Result<ByteArray> =
        runCatching {
            require(file.isFile) { "text file not found: ${file.path}" }
            create(file.readText().removePrefix("\uFEFF"), options)
        }

    /** Splits [word] into chunks that fit [maxWidth]; a single glyph may overflow. */
    private fun breakWord(
        word: String,
        maxWidth: Double,
        font: StandardFont,
        fontSize: Double
    ): List<String> {
        if (word.isEmpty()) return emptyList()
        if (width(font, word, fontSize) <= maxWidth) return listOf(word)

        val parts = ArrayList<String>()
        val current = StringBuilder()
        for (character in word) {
            if (current.isNotEmpty() && width(font, current.toString() + character, fontSize) > maxWidth) {
                parts += current.toString()
                current.clear()
            }
            current.append(character)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private fun width(font: StandardFont, text: String, fontSize: Double): Double =
        Standard14.width(font, text, fontSize)

    private val WHITESPACE = Regex("\\s+")
}
