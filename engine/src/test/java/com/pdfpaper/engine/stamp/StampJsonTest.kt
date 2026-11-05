package com.pdfpaper.engine.stamp

import com.pdfpaper.engine.create.imageBytes
import com.pdfpaper.engine.graphics.PdfColor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T23 acceptance: custom stamps round-trip as a self-contained JSON list
 * (text, colours, base64 image bytes) and malformed input is a failed
 * [Result] with a reason — never a crash.
 */
class StampJsonTest {

    @Test
    fun roundTripsTextAndImageStamps() {
        val text = CustomStamp.text(
            "ship-it", "Ship it", "SHIP IT",
            textColor = PdfColor(0.0, 0.4, 0.0),
            borderColor = PdfColor(0.1, 0.1, 0.1),
            fillColor = PdfColor(0.95, 0.98, 0.95)
        ).getOrThrow()
        val image = CustomStamp.image("logo", "Our logo", imageBytes("png", 4, 2)).getOrThrow()

        val decoded = StampJson.decode(StampJson.encode(listOf(text, image))).getOrThrow()
        assertEquals(listOf(text, image), decoded)
    }

    @Test
    fun writesAJsonArrayWithStableFields() {
        val encoded = StampJson.encode(listOf(CustomStamp.text("ok", "OK", "OK").getOrThrow()))
        assertTrue(encoded.startsWith("["), encoded)
        assertTrue(encoded.endsWith("]"), encoded)
        assertTrue(encoded.contains("\"id\": \"ok\""), encoded)
        assertTrue(encoded.contains("\"text\": \"OK\""), encoded)
        assertTrue(encoded.contains("\"textColor\": [0, 0, 0]"), encoded)
        assertEquals("[]", StampJson.encode(emptyList()))
    }

    @Test
    fun decodesTolerantly() {
        val json = """
            [
              {"id":"one","label":"One","text":"ONE","flag":true,"extra":{"nested":[1,2,null]}},
              {"id":"two","label":"Two","text":"TWO","note":"ignored"}
            ]
        """.trimIndent()
        val stamps = StampJson.decode(json).getOrThrow()
        assertEquals(listOf("one", "two"), stamps.map { it.id })
        assertEquals("ONE", stamps[0].text)
        assertTrue(StampJson.decode("  []  ").getOrThrow().isEmpty())
    }

    @Test
    fun roundTripsEscapesAndUnicode() {
        val stamp = CustomStamp.text(
            "quotes",
            "Grüße — \"quoted\" & \\backslashes\\",
            "A\\B\",( )"
        ).getOrThrow()
        val decoded = StampJson.decode(StampJson.encode(listOf(stamp))).getOrThrow()
        assertEquals(listOf(stamp), decoded)
    }

    @Test
    fun rejectsMalformedInput() {
        val bad = listOf(
            "not json",
            "{\"id\":\"x\",\"label\":\"x\",\"text\":\"t\"}",
            "[{\"label\":\"x\",\"text\":\"t\"}]",
            "[{\"id\":\"x\",\"text\":\"t\"}]",
            "[{\"id\":\"x\",\"label\":\"x\"}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"text\":\"t\",\"image\":\"AA==\"}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"text\":\"t\",\"textColor\":[1,2]}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"text\":\"t\",\"textColor\":[\"red\",0,0]}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"text\":\"a\"},{\"id\":\"X\",\"label\":\"b\",\"text\":\"b\"}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"text\":\"\"}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"image\":\"AAA\",\"format\":\"png\",\"width\":1,\"height\":1}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"image\":\"/9j/\",\"format\":\"png\",\"width\":1,\"height\":1}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"image\":\"AA==\",\"format\":\"gif\",\"width\":1,\"height\":1}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"image\":\"AA==\",\"format\":\"png\",\"width\":0,\"height\":1}]",
            "[{\"id\":\"x\",\"label\":\"x\",\"text\":\"t\",}]",
            "[{\"id\":\"x\" \"label\":\"x\"}]"
        )
        for (input in bad) {
            val result = StampJson.decode(input)
            assertTrue(result.isFailure, "should fail: $input")
            assertFalse(result.exceptionOrNull()?.message.isNullOrBlank(), "reason missing for: $input")
        }
    }

    @Test
    fun savesAndLoadsAFile() {
        val file = File.createTempFile("stamps", ".json")
        try {
            val stamp = CustomStamp.text("tmp", "Tmp", "TMP").getOrThrow()
            assertTrue(StampJson.save(file, listOf(stamp)).isSuccess)
            assertEquals(listOf(stamp), StampJson.load(file).getOrThrow())
            val missing = File(file.parentFile, "no-such-stamp-list-${System.nanoTime()}.json")
            assertTrue(StampJson.load(missing).isFailure)
        } finally {
            file.delete()
        }
    }
}
