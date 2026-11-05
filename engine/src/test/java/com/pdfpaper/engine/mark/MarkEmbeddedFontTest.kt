package com.pdfpaper.engine.mark

import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfRef
import com.pdfpaper.engine.model.PdfStream
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.layout
import com.pdfpaper.engine.write.WriteFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Text in a script the standard 14 fonts cannot show is embedded as a
 * TrueType font, so it travels with the file and can be searched and copied
 * out of it again.
 */
class MarkEmbeddedFontTest {

    private fun fontFile(): File? {
        val candidates = listOf(
            File("..", "app/src/main/assets/fonts/NotoSansDevanagari-Regular.ttf"),
            File("app/src/main/assets/fonts/NotoSansDevanagari-Regular.ttf")
        )
        return candidates.firstOrNull { it.isFile }
    }

    @Test
    fun hindiTextIsEmbeddedAndCanBeReadBack() {
        val font = fontFile()
        assertNotNull("the Devanagari font must be in the repository", font)
        val bytes = font!!.readBytes()
        val original = WriteFixtures.threePageDoc()
        val document = PdfDocument.open(ByteArraySource(original)).getOrThrow()
        val hindi = "गोपनीय"
        val marked = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(
                content = WatermarkContent.Text(hindi),
                font = MarkFont.Embedded(bytes, "NotoSansDevanagari"),
                fontSize = 40.0
            )
        ).getOrThrow()

        // The text comes back out of the file through the ToUnicode map.
        val result = PdfDocument.open(ByteArraySource(marked)).getOrThrow()
        result.use {
            val text = it.pages().joinToString("\n") { page -> page.layout().text }
            assertTrue("the Hindi text must be readable again: $text", text.contains(hindi))
        }
    }

    @Test
    fun theFontProgramTravelsWithTheFile() {
        val font = fontFile() ?: return
        val original = WriteFixtures.threePageDoc()
        val document = PdfDocument.open(ByteArraySource(original)).getOrThrow()
        val marked = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(
                content = WatermarkContent.Text("अ"),
                font = MarkFont.Embedded(font.readBytes(), "NotoSansDevanagari")
            )
        ).getOrThrow()

        val result = PdfDocument.open(ByteArraySource(marked)).getOrThrow()
        result.use {
            val embedded = it.pages().flatMap { page ->
                val fonts = page.resources?.get("Font") as? PdfDict ?: return@flatMap emptyList()
                fonts.entries.values.mapNotNull { value ->
                    val ref = value as? PdfRef ?: return@mapNotNull null
                    val dict = it.resolve(ref) as? PdfDict ?: return@mapNotNull null
                    val descendants = dict["DescendantFonts"] as? PdfArray ?: return@mapNotNull null
                    val descendant = it.resolve(descendants.items.first() as PdfRef) as? PdfDict
                    val descriptor = descendant?.get("FontDescriptor") as? PdfRef
                    val descriptorDict = descriptor?.let { d -> it.resolve(d) as? PdfDict }
                    descriptorDict?.get("FontFile2")
                }
            }
            assertTrue("the font program must be embedded", embedded.isNotEmpty())
        }
    }

    @Test
    fun aBrokenFontFileIsReportedInsteadOfCrashing() {
        val original = WriteFixtures.threePageDoc()
        val document = PdfDocument.open(ByteArraySource(original)).getOrThrow()
        val result = PdfMarks.applyWatermark(
            document,
            WatermarkOptions(
                content = WatermarkContent.Text("text"),
                font = MarkFont.Embedded(byteArrayOf(1, 2, 3, 4), "Broken")
            )
        )
        assertTrue("a broken font must not produce a file", result.isFailure)
    }
}
