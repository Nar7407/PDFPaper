package com.pdfpaper.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentFilesTest {

    @Test
    fun codecRoundTripsArbitraryUriAndDisplayNameCharacters() {
        val files = listOf(
            RecentFile("content://provider/doc%2F1?id=2&x=3", "Quarterly report\tfinal\n.pdf"),
            RecentFile("content://provider/2", "naïve — résumé.pdf")
        )

        val decoded = RecentCodec.decode(RecentCodec.encode(files))

        assertEquals(files, decoded)
    }

    @Test
    fun addPutsTheNewestFirstAndDeduplicatesByUri() {
        val old = (1..3).map { RecentFile("content://doc/$it", "doc$it.pdf") }

        val next = RecentCodec.add(old, RecentFile("content://doc/2", "renamed.pdf"))

        assertEquals(
            listOf("content://doc/2", "content://doc/1", "content://doc/3"),
            next.map { it.uri }
        )
        assertEquals("renamed.pdf", next.first().displayName)
    }

    @Test
    fun addKeepsOnlyTheTwentyMostRecent() {
        var files = emptyList<RecentFile>()
        repeat(25) { index ->
            files = RecentCodec.add(files, RecentFile("content://doc/$index", "doc$index.pdf"))
        }

        assertEquals(RecentCodec.MAX_RECENT, files.size)
        assertEquals("content://doc/24", files.first().uri)
        assertEquals("content://doc/5", files.last().uri)
    }

    @Test
    fun decodeSkipsMalformedRecordsInsteadOfThrowing() {
        val good = RecentCodec.encode(listOf(RecentFile("content://doc/1", "one.pdf")))
        val raw = "not-a-record\n$good\n%not-base64%\tname"

        val decoded = RecentCodec.decode(raw)

        assertEquals(listOf(RecentFile("content://doc/1", "one.pdf")), decoded)
    }

    @Test
    fun blankStorageReadsAsAnEmptyList() {
        assertTrue(RecentCodec.decode(null).isEmpty())
        assertTrue(RecentCodec.decode("").isEmpty())
        assertTrue(RecentCodec.decode("   \n\t\n").isEmpty())
    }
}
