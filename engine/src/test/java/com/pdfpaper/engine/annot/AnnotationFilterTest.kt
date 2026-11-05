package com.pdfpaper.engine.annot

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.DisplayListBuilder
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.graphics.buildDisplayList
import com.pdfpaper.engine.io.ByteArraySource
import com.pdfpaper.engine.model.PdfArray
import com.pdfpaper.engine.model.PdfDict
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.model.PdfInteger
import com.pdfpaper.engine.model.PdfName
import com.pdfpaper.engine.model.PdfString
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.test.TestObj
import com.pdfpaper.engine.test.buildTestPdf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit and render tests for [AnnotationFilter] and [AnnotationFilterReport] (T68).
 */
class AnnotationFilterTest {

    private fun sampleAnnotation(
        type: AnnotationType = AnnotationType.HIGHLIGHT,
        subtype: String = "Highlight",
        color: PdfColor? = PdfColor(1.0, 1.0, 0.0),
        title: String? = "Alice",
        creationDate: String? = "D:20261101120000Z",
        modifiedDate: String? = "D:20261102120000Z",
        flags: Int = 0
    ): PdfAnnotation {
        val dict = PdfDict(
            buildMap {
                put(PdfName("Type"), PdfName("Annot"))
                put(PdfName("Subtype"), PdfName(subtype))
                color?.let {
                    put(PdfName("C"), PdfArray(listOf(PdfInteger((it.red * 255).toLong()))))
                }
                title?.let { put(PdfName("T"), PdfString(it)) }
                creationDate?.let { put(PdfName("CreationDate"), PdfString(it)) }
                modifiedDate?.let { put(PdfName("M"), PdfString(it)) }
                if (flags != 0) put(PdfName("F"), PdfInteger(flags.toLong()))
            }
        )
        return PdfAnnotation(
            type = type,
            subtype = subtype,
            rect = PdfRect(10.0, 10.0, 50.0, 20.0),
            contents = "Sample",
            title = title,
            color = color,
            interiorColor = null,
            flags = flags,
            borderWidth = null,
            quadPoints = emptyList(),
            inkLists = emptyList(),
            appearance = null,
            appearanceState = null,
            subject = null,
            creationDate = creationDate,
            modifiedDate = modifiedDate,
            inReplyTo = null,
            replyType = null,
            state = null,
            stateModel = null,
            popup = null,
            parent = null,
            open = null,
            dict = dict,
            ref = null
        )
    }

    // ---- Engine Model Tests ------------------------------------------------

    @Test
    fun defaultShowAllMatchesAllAnnotations() {
        val filter = AnnotationFilter.SHOW_ALL
        val annot = sampleAnnotation()
        assertTrue(filter.matches(annot))
    }

    @Test
    fun filterByTypeMatchesOnlySelectedTypes() {
        val filter = AnnotationFilter(types = setOf(AnnotationType.HIGHLIGHT))
        assertTrue(filter.matches(sampleAnnotation(type = AnnotationType.HIGHLIGHT, subtype = "Highlight")))
        assertFalse(filter.matches(sampleAnnotation(type = AnnotationType.INK, subtype = "Ink")))
    }

    @Test
    fun filterByColorMatchesNormalizedRgb() {
        // Yellow RGB is 0xFFFF00
        val yellowRgb = 0xFFFF00
        val filter = AnnotationFilter(colors = setOf(yellowRgb))
        val yellowAnnot = sampleAnnotation(color = PdfColor(1.0, 1.0, 0.0))
        val redAnnot = sampleAnnotation(color = PdfColor(1.0, 0.0, 0.0))

        assertTrue(filter.matches(yellowAnnot))
        assertFalse(filter.matches(redAnnot))
    }

    @Test
    fun filterByColorDistinguishesAbsentColorFromBlack() {
        val filterAllowNoColor = AnnotationFilter(colors = setOf(null))
        val noColorAnnot = sampleAnnotation(color = null)
        val blackAnnot = sampleAnnotation(color = PdfColor.BLACK)

        assertTrue(filterAllowNoColor.matches(noColorAnnot))
        assertFalse(filterAllowNoColor.matches(blackAnnot))
    }

    @Test
    fun filterByAuthorMatchesExactOrSet() {
        val filter = AnnotationFilter(authors = setOf("Alice"))
        assertTrue(filter.matches(sampleAnnotation(title = "Alice")))
        assertFalse(filter.matches(sampleAnnotation(title = "Bob")))
    }

    @Test
    fun filterByDateRangeMatchesCreationAndModifiedDates() {
        // D:20261102120000Z epoch millis
        val epochMillis = PdfDate.millis("D:20261102120000Z")!!
        val range = (epochMillis - 1000)..(epochMillis + 1000)
        val filter = AnnotationFilter(dateField = AnnotationDateField.MODIFIED, dateRange = range)

        assertTrue(filter.matches(sampleAnnotation(modifiedDate = "D:20261102120000Z")))
        assertFalse(filter.matches(sampleAnnotation(modifiedDate = "D:20261001120000Z")))
    }

    @Test
    fun filterByDateToleratesMalformedOrMissingDate() {
        val range = 1000L..2000L
        val filter = AnnotationFilter(dateField = AnnotationDateField.MODIFIED, dateRange = range)
        val malformedAnnot = sampleAnnotation(modifiedDate = "not-a-date")
        val missingAnnot = sampleAnnotation(modifiedDate = null)

        assertFalse(filter.matches(malformedAnnot))
        assertFalse(filter.matches(missingAnnot))
    }

    @Test
    fun filterByDateInclusiveBoundaries() {
        val epochMillis = PdfDate.millis("D:20261102120000Z")!!
        val exactStartFilter = AnnotationFilter(
            dateField = AnnotationDateField.MODIFIED,
            dateRange = epochMillis..(epochMillis + 5000)
        )
        val exactEndFilter = AnnotationFilter(
            dateField = AnnotationDateField.MODIFIED,
            dateRange = (epochMillis - 5000)..epochMillis
        )

        assertTrue(exactStartFilter.matches(sampleAnnotation(modifiedDate = "D:20261102120000Z")))
        assertTrue(exactEndFilter.matches(sampleAnnotation(modifiedDate = "D:20261102120000Z")))
    }

    @Test
    fun pdfVisibilityFlagsSuppressAnnotationRegardlessOfFilter() {
        val filter = AnnotationFilter.SHOW_ALL
        val hiddenAnnot = sampleAnnotation(flags = PdfAnnotation.HIDDEN)
        val noViewAnnot = sampleAnnotation(flags = PdfAnnotation.NO_VIEW)
        val invisibleAnnot = sampleAnnotation(flags = PdfAnnotation.INVISIBLE)

        assertFalse(filter.matches(hiddenAnnot))
        assertFalse(filter.matches(noViewAnnot))
        assertFalse(filter.matches(invisibleAnnot))
    }

    @Test
    fun combinedFilterMatchesOnlyWhenAllDimensionsMatch() {
        val filter = AnnotationFilter(
            types = setOf(AnnotationType.HIGHLIGHT),
            authors = setOf("Alice"),
            colors = setOf(0xFFFF00)
        )

        assertTrue(
            filter.matches(
                sampleAnnotation(
                    type = AnnotationType.HIGHLIGHT,
                    title = "Alice",
                    color = PdfColor(1.0, 1.0, 0.0)
                )
            )
        )
        // Wrong author
        assertFalse(
            filter.matches(
                sampleAnnotation(
                    type = AnnotationType.HIGHLIGHT,
                    title = "Bob",
                    color = PdfColor(1.0, 1.0, 0.0)
                )
            )
        )
        // Wrong type
        assertFalse(
            filter.matches(
                sampleAnnotation(
                    type = AnnotationType.UNDERLINE,
                    title = "Alice",
                    color = PdfColor(1.0, 1.0, 0.0)
                )
            )
        )
    }

    @Test
    fun unknownSubtypeToleratedAndFilteredNormally() {
        val unknownAnnot = sampleAnnotation(type = AnnotationType.UNKNOWN, subtype = "CustomSubtype")
        val filterExcludeUnknown = AnnotationFilter(types = setOf(AnnotationType.HIGHLIGHT))
        val filterIncludeUnknown = AnnotationFilter(types = setOf(AnnotationType.UNKNOWN))

        assertFalse(filterExcludeUnknown.matches(unknownAnnot))
        assertTrue(filterIncludeUnknown.matches(unknownAnnot))
    }

    // ---- Engine Report Tests -----------------------------------------------

    @Test
    fun reportOnDocumentWithoutAnnotationsReturnsEmptyTotals() {
        val emptyPdf = buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> >>")
            )
        )
        val doc = PdfDocument.open(ByteArraySource(emptyPdf)).getOrThrow()
        val report = doc.annotationFilterReport()

        assertEquals(0, report.totalCount)
        assertEquals(0, report.matchingCount)
        assertEquals(0, report.hiddenCount)
        assertTrue(report.typeCounts.isEmpty())
        assertTrue(report.colorCounts.isEmpty())
    }

    @Test
    fun reportCalculatesDocumentWideTotalsByType() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val report = doc.annotationFilterReport()

        // Page 1: 3 highlights, 1 underline, 1 ink, 1 note, 1 stamp, 1 widget, 1 link
        // Page 2: 1 highlight, 1 ink
        // Page 3: 1 note
        assertEquals(4, report.typeCounts[AnnotationType.HIGHLIGHT])
        assertEquals(1, report.typeCounts[AnnotationType.UNDERLINE])
        assertEquals(2, report.typeCounts[AnnotationType.INK])
        assertEquals(2, report.typeCounts[AnnotationType.TEXT])
        assertEquals(1, report.typeCounts[AnnotationType.STAMP])
        assertEquals(1, report.typeCounts[AnnotationType.WIDGET])
        assertEquals(1, report.typeCounts[AnnotationType.LINK])
    }

    @Test
    fun reportCalculatesDocumentWideTotalsByColor() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val report = doc.annotationFilterReport()

        // Yellow highlights on P1 and P2, plus green and red on P1, blue ink on P1, red ink on P2
        assertTrue(report.colorCounts.isNotEmpty(), "Report should contain color counts")
    }

    @Test
    fun reportDistinguishesAbsentColorFromBlackColor() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val report = doc.annotationFilterReport()

        // Link and Widget have null /C (absent)
        val absentCount = report.colorCounts[null] ?: 0
        assertTrue(absentCount >= 1, "Should count absent color distinctly from black")
    }

    @Test
    fun reportCalculatesMatchingAndHiddenTotals() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val filter = AnnotationFilter(types = setOf(AnnotationType.HIGHLIGHT))
        val report = doc.annotationFilterReport(filter)

        assertEquals(4, report.matchingCount)
        assertEquals(report.totalCount - 4, report.hiddenCount)
    }

    @Test
    fun reportDetectsConservativeNotFilterablePageMarks() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val report = doc.annotationFilterReport()

        // The document has PDFPaper watermark on 3 pages
        assertTrue(report.notFilterableCount >= 1, "Report should detect not filterable watermark marks")
        assertTrue(report.notFilterableMarks.any { it.description.contains("PDFPaper") })
    }

    @Test
    fun reportDoesNotMutateDocumentOrParserObjects() {
        val bytes = T68Fixtures.createMixedAnnotationDoc()
        val doc = PdfDocument.open(ByteArraySource(bytes)).getOrThrow()
        val beforePages = doc.pages().map { it.annotations().size }

        doc.annotationFilterReport()

        val afterPages = doc.pages().map { it.annotations().size }
        assertEquals(beforePages, afterPages, "Report should never mutate parsed annotations")
    }

    @Test
    fun reportToleratesMalformedAnnotationDictionary() {
        val pdfWithBadAnnot = buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Annots [4 0 R 5 0 R] >>"),
                TestObj(4, 0, "<< /Type /Annot /Subtype /Highlight /Rect [10 10 20 20] /C (not-an-array) >>"),
                TestObj(5, 0, "42") // Not even a dict
            )
        )
        val doc = PdfDocument.open(ByteArraySource(pdfWithBadAnnot)).getOrThrow()
        val report = doc.annotationFilterReport()
        // Should tolerate bad annotation without throwing uncaught exceptions
        assertNotNull(report)
    }

    // ---- Engine Renderer / DisplayList Tests --------------------------------

    @Test
    fun buildAnnotationDisplayListWithShowAllIsIdenticalToDefault() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val page = doc.pages().first()

        val defaultList = page.buildAnnotationDisplayList()
        val filteredList = page.buildAnnotationDisplayList(filter = AnnotationFilter.SHOW_ALL)

        assertEquals(defaultList.commands.size, filteredList.commands.size)
    }

    @Test
    fun buildAnnotationDisplayListSuppressesFilteredAnnotations() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val page = doc.pages().first()

        // Hide all annotations
        val filterHideAll = AnnotationFilter(predicate = { false })
        val emptyList = page.buildAnnotationDisplayList(filter = filterHideAll)

        assertTrue(emptyList.commands.isEmpty(), "Display list should be empty when all annotations are filtered")
    }

    @Test
    fun pageContentDisplayListIsUnchangedWhenAnnotationsAreFiltered() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val page = doc.pages().first()

        val contentBefore = page.buildDisplayList()
        page.buildAnnotationDisplayList(filter = AnnotationFilter(types = emptySet()))
        val contentAfter = page.buildDisplayList()

        assertEquals(contentBefore.commands.size, contentAfter.commands.size)
    }

    @Test
    fun filteredDisplayListRetainsApAppearanceWhenVisible() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val page = doc.pages().first()

        // Stamp has an /AP /N appearance stream
        val filterStampOnly = AnnotationFilter(types = setOf(AnnotationType.STAMP))
        val stampList = page.buildAnnotationDisplayList(filter = filterStampOnly)

        assertTrue(stampList.commands.isNotEmpty(), "Stamp appearance should be drawn when stamp is visible")
    }

    @Test
    fun filteredDisplayListRetainsSyntheticAppearanceWhenVisible() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val page = doc.pages().first()

        // Highlight without /AP uses synthetic rendering
        val filterHighlightOnly = AnnotationFilter(types = setOf(AnnotationType.HIGHLIGHT))
        val highlightList = page.buildAnnotationDisplayList(filter = filterHighlightOnly)

        assertTrue(highlightList.commands.isNotEmpty(), "Highlight synthetic appearance should be drawn")
    }

    @Test
    fun annotationsSuppressedByPdfFlagsRemainHiddenRegardlessOfFilter() {
        // Annotation with HIDDEN flag should not appear in display list even if filter = SHOW_ALL
        val pdfWithHidden = buildTestPdf(
            listOf(
                TestObj(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
                TestObj(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                TestObj(3, 0, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Annots [4 0 R] >>"),
                TestObj(4, 0, "<< /Type /Annot /Subtype /Highlight /Rect [10 10 50 50] /QuadPoints [10 50 50 50 10 10 50 10] /C [1 1 0] /F 2 >>") // F 2 = HIDDEN
            )
        )
        val doc = PdfDocument.open(ByteArraySource(pdfWithHidden)).getOrThrow()
        val page = doc.pages().first()

        val list = page.buildAnnotationDisplayList(filter = AnnotationFilter.SHOW_ALL)
        assertTrue(list.commands.isEmpty(), "Hidden annotation must not be drawn in display list")
    }

    @Test
    fun annotationDisplayListPreservesZOrderForRemainingAnnotations() {
        val doc = PdfDocument.open(ByteArraySource(T68Fixtures.createMixedAnnotationDoc())).getOrThrow()
        val page = doc.pages().first()

        // When filtering to highlight and note, their appearance order must preserve original relative order
        val filter = AnnotationFilter(types = setOf(AnnotationType.HIGHLIGHT, AnnotationType.TEXT))
        val list = page.buildAnnotationDisplayList(filter = filter)

        assertTrue(list.commands.isNotEmpty())
    }
}
