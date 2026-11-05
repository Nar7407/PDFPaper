package com.pdfpaper.app.ui.redact

import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.redact.RedactionFindMatch
import com.pdfpaper.engine.redact.RedactionLeakKind
import com.pdfpaper.engine.redact.RedactionMark
import com.pdfpaper.engine.redact.RedactionMarkType
import com.pdfpaper.engine.redact.RedactionPreset
import com.pdfpaper.engine.redact.RedactionVerification
import com.pdfpaper.engine.sanitize.FormDataHandling
import com.pdfpaper.engine.sanitize.SanitizeItem
import com.pdfpaper.engine.sanitize.SanitizeOptions
import com.pdfpaper.engine.sanitize.SanitizeReport

/** The three ways the viewer can select something for redaction. */
enum class RedactionTool(val label: String, val hint: String) {
    AREA("Box", "Drag over anything that should disappear."),
    TEXT("Text", "Tap or drag across text, then choose Redact selection."),
    IMAGE("Image", "Tap a picture to mark the whole image.")
}

/** The current cover settings. They apply to marks added from now on. */
data class RedactionProperties(
    val coverColor: PdfColor = PdfColor.BLACK,
    val overlayText: String = "REDACTED",
    val fontSize: Float = 12f
)

/** One pending mark, kept in app-space units (PDF user space, y up). */
data class RedactionMarkUi(
    val id: Long,
    val pageIndex: Int,
    val type: RedactionMarkType,
    val rects: List<PdfRect>,
    val expectedText: String? = null,
    val overlayText: String? = null,
    val color: PdfColor = PdfColor.BLACK,
    val fontSize: Float = 12f
) {
    val title: String
        get() = when (type) {
            RedactionMarkType.PAGE -> "Whole page"
            RedactionMarkType.IMAGE -> "Image"
            RedactionMarkType.TEXT_SELECTION -> expectedText
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "Text: \u201c${preview(it)}\u201d" }
                ?: "Text selection"
            RedactionMarkType.AREA -> "Box"
        }

    fun toEngineMark(): RedactionMark = RedactionMark(
        pageIndex = pageIndex,
        rects = rects,
        type = type,
        overlayText = overlayText?.takeIf { it.isNotBlank() },
        color = color,
        expectedText = expectedText
    )

    private fun preview(text: String): String =
        if (text.length <= 44) text else text.take(41) + "\u2026"
}

enum class RedactionFindMode(val label: String) {
    EMAIL("Email"),
    PHONE("Phone"),
    REGEX("Custom regex");

    val preset: RedactionPreset?
        get() = when (this) {
            EMAIL -> RedactionPreset.EMAIL
            PHONE -> RedactionPreset.PHONE
            REGEX -> null
        }
}

/** Search results shown as selectable rows in the Find text and redact panel. */
data class RedactionFindUi(
    val mode: RedactionFindMode = RedactionFindMode.EMAIL,
    val query: String = "",
    val matches: List<RedactionFindMatch> = emptyList(),
    val checked: Set<Int> = emptySet(),
    val running: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null
) {
    val allChecked: Boolean
        get() = matches.isNotEmpty() && checked.size == matches.size
}

/** One plain-language verification problem from the engine. */
data class RedactionProblem(
    val expected: String,
    val pageIndex: Int,
    val reason: String
)

/**
 * The verification result the user sees. It is assembled from the engine's
 * re-extraction, re-rendering and decoded-stream scan; the viewer never claims
 * success from [RedactionOutcome.bytes] alone.
 */
data class RedactionVerificationUi(
    val expected: List<String>,
    val remaining: Map<String, Int>,
    val problems: List<RedactionProblem>,
    val unredacted: List<String>,
    val checkedPages: Int
) {
    val textChecked: Boolean get() = expected.isNotEmpty()
    val passed: Boolean
        get() = problems.isEmpty() &&
            unredacted.isEmpty() &&
            remaining.values.all { it == 0 }
    val checkedStringCount: Int get() = expected.size

    val headline: String
        get() = when {
            !textChecked && passed -> "No unredacted content was reported"
            !textChecked -> "The check found content that could not be removed"
            passed -> "No matches of redacted text found"
            else -> "Redacted text was still found"
        }

    val detail: String
        get() = when {
            !textChecked && passed ->
                "No text strings were marked, so there was no text search to run. " +
                    "The engine reported no content it could not rewrite."
            !textChecked ->
                "The engine reported content it could not rewrite safely. " +
                    "Review the problems below before saving."
            passed ->
                "Checked ${expected.size} text string${if (expected.size == 1) "" else "s"} " +
                    "across $checkedPages page${if (checkedPages == 1) "" else "s"} in " +
                    "text extraction, rendered commands and decoded stream data."
            else ->
                "Checked ${expected.size} text string${if (expected.size == 1) "" else "s"} " +
                    "across $checkedPages page${if (checkedPages == 1) "" else "s"}. " +
                    "The items below are still present or could not be removed."
        }
}

/** A completed redaction before the new copy is saved. */
data class RedactionOutcome(
    val bytes: ByteArray,
    val suggestedName: String,
    val verification: RedactionVerificationUi,
    val marksApplied: Int,
    val warnings: List<String>
) {
    val safeToSave: Boolean get() = verification.passed
}

/** One choice in the cover-colour row. */
data class RedactionColorOption(val label: String, val color: PdfColor)

val redactionColorOptions: List<RedactionColorOption> = listOf(
    RedactionColorOption("Black", PdfColor.BLACK),
    RedactionColorOption("Charcoal", PdfColor(0.18, 0.18, 0.18)),
    RedactionColorOption("Red", PdfColor(0.70, 0.05, 0.05)),
    RedactionColorOption("White", PdfColor.WHITE)
)

/** Pending cover/page selections and the latest verification result. */
data class RedactionUiState(
    val active: Boolean = false,
    val tool: RedactionTool = RedactionTool.AREA,
    val properties: RedactionProperties = RedactionProperties(),
    val marks: List<RedactionMarkUi> = emptyList(),
    val find: RedactionFindUi = RedactionFindUi(),
    val pageSelection: Set<Int> = emptySet(),
    val busy: Boolean = false,
    val outcome: RedactionOutcome? = null,
    val error: String? = null
) {
    fun marksForPage(pageIndex: Int): List<RedactionMarkUi> =
        marks.filter { it.pageIndex == pageIndex }

    fun addMark(mark: RedactionMarkUi): RedactionUiState =
        copy(marks = marks + mark, outcome = null, error = null)

    fun updateMark(mark: RedactionMarkUi): RedactionUiState =
        copy(marks = marks.map { if (it.id == mark.id) mark else it }, outcome = null)

    fun removeMark(id: Long): RedactionUiState =
        copy(marks = marks.filterNot { it.id == id }, outcome = null)
}

/** A completed sanitizer run and its counted report. */
data class SanitizeOutcome(
    val bytes: ByteArray,
    val report: SanitizeReport,
    val suggestedName: String
)

/** Sanitizer options and the latest run, shown in the sanitize sheet. */
data class SanitizeUiState(
    val options: SanitizeOptions = SanitizeOptions(),
    val running: Boolean = false,
    val outcome: SanitizeOutcome? = null,
    val error: String? = null
)

/** Builds the UI verification model from what the engine actually checked. */
fun RedactionVerification.toUi(): RedactionVerificationUi = RedactionVerificationUi(
    expected = expected,
    remaining = remaining,
    problems = leaks.map { leak ->
        RedactionProblem(
            expected = leak.expected,
            pageIndex = leak.pageIndex,
            reason = when (leak.kind) {
                RedactionLeakKind.TEXT ->
                    "The extracted text still contains this on page ${leak.pageIndex + 1}."
                RedactionLeakKind.RENDERED_TEXT ->
                    "The drawing commands still contain this on page ${leak.pageIndex + 1}."
                RedactionLeakKind.RAW_STREAM ->
                    "A decoded stream (object ${leak.streamObjNum}) still contains this."
            }
        )
    },
    unredacted = unredacted,
    checkedPages = checkedPages
)

/**
 * Checklist labels and explanations. The engine owns the categories; this
 * table only turns each category into plain language for the sheet.
 */
fun sanitizeItemTitle(item: SanitizeItem): String = when (item) {
    SanitizeItem.METADATA -> "Document metadata"
    SanitizeItem.JAVASCRIPT_ACTIONS -> "JavaScript and actions"
    SanitizeItem.EMBEDDED_FILES -> "Embedded files"
    SanitizeItem.ANNOTATIONS -> "Comments and annotations"
    SanitizeItem.FORM_DATA -> "Form data"
    SanitizeItem.HIDDEN_LAYERS -> "Hidden layers"
    SanitizeItem.BOOKMARKS -> "Bookmarks"
    SanitizeItem.LINKS -> "Links"
    SanitizeItem.THUMBNAILS -> "Page thumbnails"
    SanitizeItem.NAMED_DESTINATIONS -> "Named destinations"
    SanitizeItem.PREVIOUS_REVISIONS -> "Previous revisions"
    SanitizeItem.UNUSED_OBJECTS -> "Unused objects"
    SanitizeItem.HIDDEN_TEXT -> "Hidden text"
}

fun sanitizeItemDescription(item: SanitizeItem): String = when (item) {
    SanitizeItem.METADATA -> "Title, author, producer, dates and XMP properties."
    SanitizeItem.JAVASCRIPT_ACTIONS -> "Scripts, open actions and action chains."
    SanitizeItem.EMBEDDED_FILES -> "Attachments carried inside the PDF."
    SanitizeItem.ANNOTATIONS -> "Comments, highlights, notes and other annotations."
    SanitizeItem.FORM_DATA -> "Field values and their widget appearances."
    SanitizeItem.HIDDEN_LAYERS -> "Optional-content groups that the default view hides."
    SanitizeItem.BOOKMARKS -> "The outline and its saved destinations."
    SanitizeItem.LINKS -> "Link annotations and the references they expose."
    SanitizeItem.THUMBNAILS -> "Small page previews stored in the file."
    SanitizeItem.NAMED_DESTINATIONS -> "The named destination table."
    SanitizeItem.PREVIOUS_REVISIONS -> "Report older revisions; the copy is always a full rewrite."
    SanitizeItem.UNUSED_OBJECTS -> "Objects no page or catalog can reach."
    SanitizeItem.HIDDEN_TEXT -> "Invisible, transparent or off-page text runs."
}

fun SanitizeOptions.isRemovalEnabled(item: SanitizeItem): Boolean = when (item) {
    SanitizeItem.METADATA -> removeMetadata
    SanitizeItem.JAVASCRIPT_ACTIONS -> removeJavaScriptAndActions
    SanitizeItem.EMBEDDED_FILES -> removeEmbeddedFiles
    SanitizeItem.ANNOTATIONS -> removeCommentsAndAnnotations
    SanitizeItem.FORM_DATA -> formData != FormDataHandling.KEEP
    SanitizeItem.HIDDEN_LAYERS -> removeHiddenLayers
    SanitizeItem.BOOKMARKS -> removeBookmarks
    SanitizeItem.LINKS -> removeLinks
    SanitizeItem.THUMBNAILS -> removeThumbnails
    SanitizeItem.NAMED_DESTINATIONS -> removeNamedDestinations
    SanitizeItem.PREVIOUS_REVISIONS -> removePreviousRevisions
    SanitizeItem.UNUSED_OBJECTS -> removeUnusedObjects
    SanitizeItem.HIDDEN_TEXT -> removeHiddenText
}

fun SanitizeOptions.withRemoval(item: SanitizeItem, enabled: Boolean): SanitizeOptions = when (item) {
    SanitizeItem.METADATA -> copy(removeMetadata = enabled)
    SanitizeItem.JAVASCRIPT_ACTIONS -> copy(removeJavaScriptAndActions = enabled)
    SanitizeItem.EMBEDDED_FILES -> copy(removeEmbeddedFiles = enabled)
    SanitizeItem.ANNOTATIONS -> copy(removeCommentsAndAnnotations = enabled)
    SanitizeItem.FORM_DATA -> copy(
        formData = if (enabled) FormDataHandling.CLEAR else FormDataHandling.KEEP
    )
    SanitizeItem.HIDDEN_LAYERS -> copy(removeHiddenLayers = enabled)
    SanitizeItem.BOOKMARKS -> copy(removeBookmarks = enabled)
    SanitizeItem.LINKS -> copy(removeLinks = enabled)
    SanitizeItem.THUMBNAILS -> copy(removeThumbnails = enabled)
    SanitizeItem.NAMED_DESTINATIONS -> copy(removeNamedDestinations = enabled)
    SanitizeItem.PREVIOUS_REVISIONS -> copy(removePreviousRevisions = enabled)
    SanitizeItem.UNUSED_OBJECTS -> copy(removeUnusedObjects = enabled)
    SanitizeItem.HIDDEN_TEXT -> copy(removeHiddenText = enabled)
}
