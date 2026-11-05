package com.pdfpaper.engine.text

import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T65: the regex contract the CLI and the app both speak. Everything here is
 * pure data and string work, so it runs without a document or a device.
 */
class RegexModelTest {

    // ------------------------------------------------------------------ flags

    @Test
    fun defaultFlagsAreCaseInsensitiveAndAcrossLines() {
        val options = RegexOptions()
        assertEquals(
            setOf(RegexFlag.CASE_INSENSITIVE, RegexFlag.ACROSS_LINES),
            options.flags
        )
        assertTrue(options.isOn(RegexFlag.CASE_INSENSITIVE))
        assertTrue(options.isOn(RegexFlag.ACROSS_LINES))
        assertTrue(!options.isOn(RegexFlag.MULTILINE))
        assertTrue(!options.isOn(RegexFlag.WHOLE_WORD))
    }

    // ---------------------------------------------------------------- context

    @Test
    fun contextClipsWithEllipsisAndCollapsesWhitespace() {
        // "alpha beta gamma": the match is "beta" at 6..10.
        val (before, after) = contextAround("alpha beta gamma", 6, 10, 4)
        assertEquals("…pha ", before)
        assertEquals(" gam…", after)
    }

    @Test
    fun contextAtTheEndsOfTheTextHasNoLeadingOrTrailingEllipsis() {
        val atStart = contextAround("one two", 0, 3, 10).first
        assertEquals("", atStart)
        val atEnd = contextAround("one two", 4, 7, 10).second
        assertEquals("", atEnd)
        // A context that fits on both sides is returned whole and unmarked;
        // the space that separates it from the match is kept.
        assertEquals("one ", contextAround("one two", 4, 7, 10).first)
    }

    @Test
    fun contextNeverExceedsRadiusOnEachSide() {
        val text = "x ".repeat(40) + "needle" + " y".repeat(40)
        val start = text.indexOf("needle")
        val end = start + "needle".length
        for (radius in listOf(0, 1, 4, 17)) {
            val (before, after) = contextAround(text, start, end, radius)
            assertTrue(before.removePrefix("…").length <= radius, "before=$before r=$radius")
            assertTrue(after.removeSuffix("…").length <= radius, "after=$after r=$radius")
        }
        val (before0, after0) = contextAround(text, start, end, 0)
        assertEquals("", before0)
        assertEquals("", after0)
    }

    // --------------------------------------------------------------- examples

    @Test
    fun sixExamplesHaveUniqueIdsAndCompile() {
        val all = RegexExamples.ALL
        assertEquals(6, all.size)
        assertEquals(listOf("date", "email", "phone", "url", "ipv4", "code"), all.map { it.id })
        assertEquals(6, all.map { it.id }.toSet().size)
        for (example in all) {
            Pattern.compile(example.pattern)
            assertTrue(example.label.isNotBlank(), example.id)
            assertTrue(example.sample.isNotBlank(), example.id)
        }
        assertEquals("date", RegexExamples.byId("date")?.id)
        assertEquals(null, RegexExamples.byId("nope"))
    }

    @Test
    fun everyExampleFindsItsSample() {
        for (example in RegexExamples.ALL) {
            val matcher = Pattern.compile(example.pattern).matcher(example.sample)
            assertTrue(matcher.find(), "${example.id} did not match ${example.sample}")
        }
    }

    // ------------------------------------------------------ normalised offsets

    @Test
    fun normaliseWithOffsetsMatchesIndexNormalisation() {
        val awkward = listOf(
            "",
            "   ",
            "a\n\nb",
            "x".repeat(300),
            "a\u0000b",
            "  leading and trailing  ",
            "tab\there\r\nand there"
        )
        for (source in awkward) {
            assertEquals(indexNormalize(source), normaliseWithOffsets(source).text, "source=$source")
        }
    }

    @Test
    fun normaliseWithOffsetsMapsEveryCharacterBack() {
        val source = "a \n\t b   c"
        val normalised = normaliseWithOffsets(source)
        assertEquals("a b c", normalised.text)
        assertEquals(normalised.text.length + 1, normalised.offsets.size)
        for (i in normalised.text.indices) {
            assertEquals(
                normalised.text[i],
                source[normalised.offsets[i]],
                "normalised index $i"
            )
        }
        assertEquals(source.length, normalised.offsets[normalised.text.length])
    }

    @Test
    fun normaliseWithOffsetsCapsAtMaxChars() {
        val source = "abcdefghij"
        val normalised = normaliseWithOffsets(source, maxChars = 3)
        assertEquals("abc", normalised.text)
        assertEquals(4, normalised.offsets.size)
        assertEquals(0, normalised.offsets[0])
        assertEquals(1, normalised.offsets[1])
        assertEquals(2, normalised.offsets[2])
        // The final entry is always the source length, so a range ending at the
        // cap can still be mapped back.
        assertEquals(source.length, normalised.offsets[3])
        assertEquals("", normaliseWithOffsets(source, maxChars = 0).text)
    }

    // ---------------------------------------------------------------- options

    @Test
    fun optionsNormalisedClampsBudgets() {
        val clamped = RegexOptions(
            flags = emptySet(),
            maxMatches = 0,
            timeLimitMs = 0,
            runTimeLimitMs = 0,
            contextRadius = -5
        ).normalised()
        assertTrue(clamped.maxMatches >= 1)
        assertTrue(clamped.timeLimitMs >= 1)
        assertTrue(clamped.runTimeLimitMs >= 1)
        assertEquals(0, clamped.contextRadius)
        assertTrue(clamped.flags.isEmpty())

        val untouched = RegexOptions()
        assertEquals(untouched, untouched.normalised())
        assertNotNull(RegexOptions.DEFAULT_FLAGS)
        assertEquals(2000, RegexOptions.DEFAULT_MAX_MATCHES)
        assertEquals(1500L, RegexOptions.DEFAULT_TIME_LIMIT_MS)
        assertEquals(60_000L, RegexOptions.DEFAULT_RUN_TIME_LIMIT_MS)
        assertEquals(48, RegexOptions.DEFAULT_CONTEXT_RADIUS)
    }

    // ---------------------------------------------------------------- helper

    /**
     * An inline copy of the T50 rule (`IndexTextPolicy.normalize`), which the
     * engine cannot import. If this copy and `normaliseWithOffsets` disagree,
     * a library hit would not map back onto the open page.
     */
    private fun indexNormalize(text: String): String {
        if (text.isEmpty()) return ""
        val builder = StringBuilder(minOf(text.length, 200_000))
        var pendingSpace = false
        for (ch in text) {
            if (ch.isWhitespace() || ch.code < 0x20) {
                pendingSpace = builder.isNotEmpty()
                continue
            }
            if (pendingSpace && builder.length < 200_000) {
                builder.append(' ')
                pendingSpace = false
            }
            if (builder.length >= 200_000) break
            builder.append(ch)
        }
        return builder.toString().trim()
    }
}
