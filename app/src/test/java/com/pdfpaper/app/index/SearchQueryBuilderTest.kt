package com.pdfpaper.app.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryBuilderTest {

    @Test
    fun blankInputHasNoQuery() {
        assertNull(SearchQueryBuilder.parse("   ").matchExpression)
        assertTrue(SearchQueryBuilder.parse("").plainTerms.isEmpty())
    }

    @Test
    fun wordsArePrefixQueries() {
        val parsed = SearchQueryBuilder.parse("budg report")

        assertEquals("budg* AND report*", parsed.matchExpression)
        assertEquals(listOf("budg", "report"), parsed.plainTerms)
    }

    @Test
    fun quotedTextIsAPhrase() {
        val parsed = SearchQueryBuilder.parse("\"annual report\"")

        assertEquals("\"annual report\"", parsed.matchExpression)
        assertEquals(listOf("annual report"), parsed.plainTerms)
    }

    @Test
    fun mixedPhraseAndWordKeepTheirMeaning() {
        val parsed = SearchQueryBuilder.parse("\"annual report\" budg")

        assertEquals("\"annual report\" AND budg*", parsed.matchExpression)
        assertEquals(listOf("annual report", "budg"), parsed.plainTerms)
    }

    @Test
    fun ftsOperatorsAndPunctuationCannotBreakTheQuery() {
        val parsed = SearchQueryBuilder.parse("budget* OR (tax:2026) -draft")

        assertTrue(parsed.matchExpression!!.startsWith("budget*"))
        assertTrue(parsed.matchExpression!!.contains("or*"))
        assertTrue(parsed.matchExpression!!.contains("tax*"))
        assertTrue(parsed.matchExpression!!.contains("2026*"))
        assertTrue(parsed.matchExpression!!.contains("draft*"))
    }

    @Test
    fun aTrailingQuoteStillProducesAPhrase() {
        val parsed = SearchQueryBuilder.parse("\"open budget")

        assertEquals("\"open budget\"", parsed.matchExpression)
    }

    @Test
    fun longInputIsCapped() {
        val parsed = SearchQueryBuilder.parse("word ".repeat(100))
        assertTrue(parsed.plainTerms.size <= 12)
    }
}
