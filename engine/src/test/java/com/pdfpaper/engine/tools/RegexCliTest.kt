package com.pdfpaper.engine.tools

import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * T65: the `search-regex` command line. The CSV it writes is the contract
 * (RFC 4180 quoting, a BOM by default, one-based pages), and the exit codes and
 * the two stderr sentences are what a script reads.
 */
class RegexCliTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Two pages; only page 2 carries the ID code. */
    private fun twoPageFile(): File {
        val file = tempFolder.newFile("report.pdf")
        file.writeBytes(pdf(listOf("intro text", "invoice AB-1234 dated")))
        return file
    }

    private fun runCli(vararg args: String): CliRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runSearchRegex(
            args.toList().toTypedArray(),
            PrintStream(out, true, StandardCharsets.UTF_8.name()),
            PrintStream(err, true, StandardCharsets.UTF_8.name())
        )
        return CliRun(code, out.toString(StandardCharsets.UTF_8.name()), err.toString(StandardCharsets.UTF_8.name()))
    }

    private fun csvOf(file: File): String {
        val text = file.readText(StandardCharsets.UTF_8)
        return text.removePrefix("\uFEFF")
    }

    // ---------------------------------------------------------------- the CSV

    @Test
    fun writesHeaderAndOneBasedPages() {
        val input = twoPageFile()
        val output = File(tempFolder.root, "matches.csv")
        val run = runCli("search-regex", input.absolutePath, "AB-1234", "--out", output.absolutePath)
        assertEquals(run.err, 0, run.code)

        val raw = output.readText(StandardCharsets.UTF_8)
        assertEquals('\uFEFF', raw.first())
        val rows = readCsv(csvOf(output))
        assertEquals(listOf("file", "page", "match", "context"), rows.first())
        assertEquals(2, rows.size)
        val row = rows[1]
        assertEquals(input.name, row[0])
        assertEquals("2", row[1])
        assertEquals("AB-1234", row[2])
        assertTrue(row[3], row[3].contains("AB-1234"))
    }

    @Test
    fun quotesCommasQuotesAndNewlines() {
        val commaFile = tempFolder.newFile("comma.pdf")
        commaFile.writeBytes(pdf(listOf("x A,\"B y")))
        val commaOut = File(tempFolder.root, "comma.csv")
        assertEquals(
            0,
            runCli("search-regex", commaFile.absolutePath, "A,\"B", "--out", commaOut.absolutePath).code
        )
        val commaRow = readCsv(csvOf(commaOut))[1]
        assertEquals("A,\"B", commaRow[2])
        // The raw field is wrapped and its own quotes are doubled.
        assertTrue(csvOf(commaOut).contains("\"A,\"\"B\""))

        val lineFile = tempFolder.newFile("lines.pdf")
        lineFile.writeBytes(
            pdf(listOf("alpha beta\ngamma delta"))
        )
        val lineOut = File(tempFolder.root, "lines.csv")
        assertEquals(
            0,
            runCli(
                "search-regex", lineFile.absolutePath, "beta\\ngamma",
                "--out", lineOut.absolutePath
            ).code
        )
        val lineRows = readCsv(csvOf(lineOut))
        assertEquals(2, lineRows.size)
        assertEquals("beta\ngamma", lineRows[1][2])
        assertEquals("beta\ngamma", lineRows[1][3].substringAfter(" ").substringBefore(" "))
    }

    @Test
    fun bomByDefaultAndNoBomFlag() {
        val input = twoPageFile()
        val withBom = File(tempFolder.root, "with-bom.csv")
        val withoutBom = File(tempFolder.root, "no-bom.csv")
        assertEquals(
            0,
            runCli("search-regex", input.absolutePath, "AB-1234", "--out", withBom.absolutePath).code
        )
        assertEquals(
            0,
            runCli(
                "search-regex", input.absolutePath, "AB-1234",
                "--out", withoutBom.absolutePath, "--no-bom"
            ).code
        )
        assertEquals('\uFEFF', withBom.readText(StandardCharsets.UTF_8).first())
        assertEquals('f', withoutBom.readText(StandardCharsets.UTF_8).first())
    }

    @Test
    fun pagesSelectionKeepsOnlyTheListedPages() {
        val input = twoPageFile()
        val firstOnly = File(tempFolder.root, "page1.csv")
        assertEquals(
            0,
            runCli("search-regex", input.absolutePath, "AB-1234", "--out", firstOnly.absolutePath, "--pages", "1").code
        )
        assertEquals(1, readCsv(csvOf(firstOnly)).size)

        val secondOnly = File(tempFolder.root, "page2.csv")
        assertEquals(
            0,
            runCli("search-regex", input.absolutePath, "AB-1234", "--out", secondOnly.absolutePath, "--pages", "2").code
        )
        val rows = readCsv(csvOf(secondOnly))
        assertEquals(2, rows.size)
        assertEquals("2", rows[1][1])
    }

    // ------------------------------------------------------------- exit codes

    @Test
    fun exitCodesForUsageBadFileAndInvalidPattern() {
        val input = twoPageFile()
        val output = File(tempFolder.root, "out.csv")

        // Usage: no pattern, no --out, no file at the path.
        assertEquals(2, runCli("search-regex", input.absolutePath).code)
        assertEquals(2, runCli("search-regex", input.absolutePath, "AB").code)
        assertEquals(
            2,
            runCli("search-regex", File(tempFolder.root, "missing.pdf").absolutePath, "AB", "--out", output.absolutePath).code
        )

        // A file that is not a PDF: the search could not run.
        val garbage = tempFolder.newFile("garbage.pdf")
        garbage.writeText("this is not a PDF")
        assertEquals(
            1,
            runCli("search-regex", garbage.absolutePath, "AB", "--out", output.absolutePath).code
        )

        // An invalid pattern: also exit 1, and nothing is written.
        val invalid = File(tempFolder.root, "invalid.csv")
        assertEquals(
            1,
            runCli("search-regex", input.absolutePath, "[A-Z", "--out", invalid.absolutePath).code
        )
        assertFalse(invalid.exists())
    }

    @Test
    fun invalidPatternPrintsItsPosition() {
        val input = twoPageFile()
        val output = File(tempFolder.root, "invalid.csv")
        val run = runCli("search-regex", input.absolutePath, "[A-Z", "--out", output.absolutePath)
        assertEquals(1, run.code)
        assertTrue(run.err, run.err.contains("Invalid pattern: "))
        assertTrue(run.err, run.err.contains("(at "))
    }

    @Test
    fun truncatedRunStillWritesRowsAndSaysSo() {
        val input = tempFolder.newFile("both.pdf")
        input.writeBytes(pdf(listOf("AB-1234", "AB-1234")))
        val output = File(tempFolder.root, "capped.csv")
        val run = runCli(
            "search-regex", input.absolutePath, "AB-1234",
            "--out", output.absolutePath, "--max", "1"
        )
        assertEquals(run.err, 0, run.code)
        assertTrue(run.err, run.err.contains("Search stopped early"))
        assertEquals(2, readCsv(csvOf(output)).size)
    }

    // --------------------------------------------------------------- helpers

    private class CliRun(val code: Int, val out: String, val err: String)

    /** A tiny RFC 4180 reader, so quoting is checked by parsing, not by eye. */
    private fun readCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                inQuotes && ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index += 2
                }
                inQuotes && ch == '"' -> {
                    inQuotes = false
                    index++
                }
                !inQuotes && ch == '"' && field.isEmpty() -> {
                    inQuotes = true
                    index++
                }
                !inQuotes && ch == ',' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    index++
                }
                !inQuotes && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row.add(field.toString())
                    field.setLength(0)
                    rows.add(row.toList())
                    row.clear()
                    index++
                }
                else -> {
                    field.append(ch)
                    index++
                }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row.toList())
        }
        return rows
    }

    private fun pdf(pageTexts: List<String>): ByteArray {
        val objects = ArrayList<TestObj>()
        val kids = ArrayList<String>()
        var number = 3
        for (unused in pageTexts) {
            kids += "$number 0 R"
            number += 2
        }
        objects += TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>")
        objects += TestObj(
            2, 0,
            "<< /Type /Pages /Kids [ ${kids.joinToString(" ")} ] /Count ${pageTexts.size} >>"
        )
        number = 3
        for (text in pageTexts) {
            objects += TestObj(
                number, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 100 0 R >> >> /Contents ${number + 1} 0 R >>"
            )
            val content = text.split('\n').mapIndexed { index, line ->
                if (index == 0) "BT /F1 12 Tf 50 700 Td ($line) Tj ET"
                else "BT /F1 12 Tf 50 ${700 - index * 14} Td ($line) Tj ET"
            }.joinToString(" ")
            val data = content.toByteArray(StandardCharsets.ISO_8859_1)
            val body = "<< /Length ${data.size} >>\nstream\n".toByteArray(StandardCharsets.US_ASCII) +
                data + "\nendstream".toByteArray(StandardCharsets.US_ASCII)
            objects += TestObj(number + 1, 0, body)
            number += 2
        }
        val widths = (32..126).joinToString(" ") { "500" }
        objects += TestObj(
            100, 0,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
                "/FirstChar 32 /Widths [ $widths ] >>"
        )
        return buildTestPdf(objects)
    }
}
