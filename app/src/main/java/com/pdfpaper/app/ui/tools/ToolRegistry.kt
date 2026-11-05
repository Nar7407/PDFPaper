package com.pdfpaper.app.ui.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BrandingWatermark
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Approval
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Sections shown as headings in All tools and in the viewer's tools sheet.
 * The order here is the order both screens render.
 */
enum class ToolSection(val label: String) {
    PAGES("Pages"),
    EDIT("Edit"),
    ANNOTATE("Annotate"),
    REDACT("Redact"),
    FORMS_SECURITY("Forms & Security"),
    DOCUMENT("Document")
}

/**
 * The status chip shown in the catalogue and the viewer's tools sheet. The
 * label lives here, next to the availability rules, so both surfaces say the
 * same thing for the same tool.
 */
enum class ToolStatus(val label: String) {
    READY("Ready"),
    COMING_SOON("Coming soon")
}

/** Where the All tools flow goes after the user chooses a PDF. */
enum class ToolDestination { ORGANIZER, VIEWER, INFO, NEW_PDF, SCAN, COMPRESS, MARK, EXPORT_IMAGES, CROP, SAFETY }

/**
 * One tool in the registry.
 *
 * [id] is stable and unique; it is what the viewer, the AI tools and the tests
 * refer to. [name] is the label. [keywords] hold synonyms so search also finds a
 * tool by what it does ("combine" for Merge, "password" for Protect), not only
 * by its title.
 *
 * Availability has two parts. [supported] is false for tools nothing in this
 * build can do yet (they are dimmed, "Coming soon"). [needsEditingEngine] marks
 * the tools that depend on the PDF engine's content-editing API, which today
 * only the debug FakeEngine provides as a demo; [isAvailable] combines both.
 */
data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val section: ToolSection,
    val keywords: List<String> = emptyList(),
    /** False when nothing in this build can do the tool yet. */
    val supported: Boolean = true,
    /** True when the tool needs the content-editing engine (debug demo only today). */
    val needsEditingEngine: Boolean = false,
    /** Page tools: the one-line hint shown when the organizer opens for this tool. */
    val organizerHint: String? = null,
    /** Viewer tools: the mode to switch into ("text", "image", "select", "highlight", "ink", "note"). */
    val viewerTool: String? = null,
    /** Viewer tools: a panel to open instead of a markup mode ("comments", "stamps", "signatures", "ocr", "snip"). */
    val viewerAction: String? = null,
    /** Viewer tools: also open the form-fill panel. */
    val opensForms: Boolean = false,
    /** Where choosing a PDF takes the user (the All tools flow). */
    val destination: ToolDestination = ToolDestination.VIEWER
) {
    /** Usable in this build: the engine supports it, or the debug demo stands in. */
    fun isAvailable(demoEngine: Boolean): Boolean =
        supported && (!needsEditingEngine || demoEngine)

    /** The chip both the All tools screen and the viewer's sheet display. */
    fun status(demoEngine: Boolean): ToolStatus =
        if (isAvailable(demoEngine)) ToolStatus.READY else ToolStatus.COMING_SOON
}

/**
 * The single source of the tool list. The Home "All tools" screen, the tool
 * detail screens and the viewer's tools sheet all read from here, so a new tool
 * or a status change is made once.
 */
object ToolRegistry {

    val tools: List<ToolDefinition> = listOf(
        // ---- Pages: everything that runs on the page grid -------------------
        ToolDefinition(
            id = "rotate",
            name = "Rotate",
            description = "Turn pages to the right orientation.",
            icon = Icons.AutoMirrored.Outlined.RotateRight,
            section = ToolSection.PAGES,
            keywords = listOf("turn", "orientation", "landscape", "portrait"),
            organizerHint = "Rotate: select pages, then tap Apply."
        ),
        ToolDefinition(
            id = "reorder",
            name = "Reorder",
            description = "Move pages into a new order.",
            icon = Icons.Outlined.SwapVert,
            section = ToolSection.PAGES,
            keywords = listOf("move", "order", "organize", "arrange"),
            organizerHint = "Reorder: select pages, then tap Apply or use the arrows."
        ),
        ToolDefinition(
            id = "delete",
            name = "Delete",
            description = "Remove pages you no longer need.",
            icon = Icons.Outlined.Delete,
            section = ToolSection.PAGES,
            keywords = listOf("remove", "drop", "pages"),
            organizerHint = "Delete: select pages, then tap Apply."
        ),
        ToolDefinition(
            id = "extract",
            name = "Extract",
            description = "Keep only the pages you choose.",
            icon = Icons.Outlined.FileDownload,
            section = ToolSection.PAGES,
            keywords = listOf("keep", "save pages", "selected", "export"),
            organizerHint = "Extract: select pages, then tap Apply."
        ),
        ToolDefinition(
            id = "insert",
            name = "Insert",
            description = "Bring pages in from another PDF.",
            icon = Icons.Outlined.PostAdd,
            section = ToolSection.PAGES,
            keywords = listOf("add pages", "import", "bring in", "other pdf"),
            organizerHint = "Insert: tap Apply, then choose a PDF to add its pages."
        ),
        ToolDefinition(
            id = "split",
            name = "Split",
            description = "Break a PDF into one file per chapter.",
            icon = Icons.AutoMirrored.Outlined.CallSplit,
            section = ToolSection.PAGES,
            keywords = listOf("separate", "parts", "chunks", "chapters", "table of contents", "toc"),
            organizerHint = "Split: tap Apply to split at the table of contents."
        ),
        ToolDefinition(
            id = "merge",
            name = "Merge",
            description = "Combine several PDFs into one document.",
            icon = Icons.AutoMirrored.Outlined.CallMerge,
            section = ToolSection.PAGES,
            keywords = listOf("combine", "join", "append", "add pdf"),
            organizerHint = "Merge: tap Apply, then choose a PDF to append."
        ),

        // ---- Edit: the object-editing tools --------------------------------
        ToolDefinition(
            id = "add-text",
            name = "Add text",
            description = "Place new text anywhere on a page.",
            icon = Icons.Outlined.TextFields,
            section = ToolSection.EDIT,
            keywords = listOf("text", "type", "insert", "write", "label"),
            needsEditingEngine = true,
            viewerTool = "text"
        ),
        ToolDefinition(
            id = "edit-text",
            name = "Edit text",
            description = "Change text, font size and colour.",
            icon = Icons.Outlined.EditNote,
            section = ToolSection.EDIT,
            keywords = listOf("text", "edit", "content", "font", "colour", "color"),
            needsEditingEngine = true,
            viewerTool = "select"
        ),
        ToolDefinition(
            id = "add-image",
            name = "Add image",
            description = "Place a picture and resize it on the page.",
            icon = Icons.Outlined.AddPhotoAlternate,
            section = ToolSection.EDIT,
            keywords = listOf("image", "picture", "photo", "place", "insert"),
            needsEditingEngine = true,
            viewerTool = "image"
        ),
        ToolDefinition(
            id = "edit-image",
            name = "Edit image",
            description = "Move, resize, replace or delete a picture.",
            icon = Icons.Outlined.Photo,
            section = ToolSection.EDIT,
            keywords = listOf("image", "picture", "photo", "move", "resize", "replace", "delete"),
            needsEditingEngine = true,
            viewerTool = "select"
        ),

        // ---- Annotate: markup tools ---------------------------------------
        ToolDefinition(
            id = "highlight",
            name = "Highlight",
            description = "Mark up text with a highlight.",
            icon = Icons.Outlined.Highlight,
            section = ToolSection.ANNOTATE,
            keywords = listOf("markup", "marker", "yellow", "select", "text"),
            viewerTool = "highlight"
        ),
        ToolDefinition(
            id = "underline",
            name = "Underline",
            description = "Underline text on the page.",
            icon = Icons.Outlined.FormatUnderlined,
            section = ToolSection.ANNOTATE,
            keywords = listOf("markup", "line", "text", "annotate"),
            supported = false
        ),
        ToolDefinition(
            id = "draw",
            name = "Draw",
            description = "Draw freehand ink on the page.",
            icon = Icons.Outlined.Draw,
            section = ToolSection.ANNOTATE,
            keywords = listOf("ink", "pen", "freehand", "sketch", "write"),
            viewerTool = "ink"
        ),
        ToolDefinition(
            id = "note",
            name = "Note",
            description = "Drop a sticky note on the page.",
            icon = Icons.AutoMirrored.Outlined.NoteAdd,
            section = ToolSection.ANNOTATE,
            keywords = listOf("comment", "sticky", "stamp", "annotate"),
            viewerTool = "note"
        ),
        ToolDefinition(
            id = "comments",
            name = "Comments",
            description = "Read, reply to and resolve comments on the page.",
            icon = Icons.Outlined.Comment,
            section = ToolSection.ANNOTATE,
            keywords = listOf("comment", "thread", "reply", "resolve", "review", "sticky note"),
            viewerAction = "comments"
        ),
        ToolDefinition(
            id = "stamp",
            name = "Stamps",
            description = "Place a built-in or custom stamp on a page.",
            icon = Icons.Outlined.Approval,
            section = ToolSection.ANNOTATE,
            keywords = listOf("stamp", "approved", "sign here", "business", "seal", "mark"),
            viewerAction = "stamps"
        ),

        // ---- Redact: permanent privacy tools -------------------------------
        // UI-4 is still in progress: the redact screens exist
        // (ui/redact/*) but the viewer wiring is not finished, so the group
        // stays "Coming soon" until that task resumes.
        ToolDefinition(
            id = "redact",
            name = "Redact text and images",
            description = "Draw a box or select text and images, then remove them for good.",
            icon = Icons.Outlined.VisibilityOff,
            section = ToolSection.REDACT,
            keywords = listOf("redact", "remove", "hide", "black out", "privacy", "text", "image"),
            supported = false,
            viewerAction = "redact"
        ),
        ToolDefinition(
            id = "redact-pages",
            name = "Redact pages",
            description = "Cover whole pages so none of their content remains visible.",
            icon = Icons.Outlined.DeleteSweep,
            section = ToolSection.REDACT,
            keywords = listOf("redact", "page", "whole page", "hide", "privacy"),
            supported = false,
            viewerAction = "redact-pages"
        ),
        ToolDefinition(
            id = "redact-find",
            name = "Find text and redact",
            description = "Find emails, phone numbers or a pattern and mark every match.",
            icon = Icons.Outlined.FindReplace,
            section = ToolSection.REDACT,
            keywords = listOf("find", "search", "redact", "email", "phone", "regex", "pattern"),
            supported = false,
            viewerAction = "redact-find"
        ),
        ToolDefinition(
            id = "redact-properties",
            name = "Redaction properties",
            description = "Choose the cover color, label and label size.",
            icon = Icons.Outlined.Tune,
            section = ToolSection.REDACT,
            keywords = listOf("redact", "properties", "color", "label", "overlay", "font"),
            supported = false,
            viewerAction = "redact-properties"
        ),
        ToolDefinition(
            id = "sanitize",
            name = "Sanitize document",
            description = "Remove metadata, hidden data and other privacy leftovers.",
            icon = Icons.Outlined.CleaningServices,
            section = ToolSection.REDACT,
            keywords = listOf("sanitize", "clean", "privacy", "metadata", "hidden", "scrub", "strip"),
            supported = false,
            viewerAction = "sanitize"
        ),

        // ---- Forms & Security ---------------------------------------------
        ToolDefinition(
            id = "fill-form",
            name = "Fill form",
            description = "Complete interactive form fields.",
            icon = Icons.AutoMirrored.Outlined.FactCheck,
            section = ToolSection.FORMS_SECURITY,
            keywords = listOf("form", "fields", "acroform", "text field", "checkbox", "radio"),
            opensForms = true
        ),
        ToolDefinition(
            id = "sign",
            name = "Sign",
            description = "Add a visual signature or sign with a certificate.",
            icon = Icons.Outlined.Draw,
            section = ToolSection.FORMS_SECURITY,
            keywords = listOf("sign", "signature", "draw", "certificate", "p12", "digital"),
            viewerAction = "sign"
        ),
        ToolDefinition(
            id = "signatures",
            name = "Signatures",
            description = "See who signed and exactly what was checked.",
            icon = Icons.Outlined.Verified,
            section = ToolSection.FORMS_SECURITY,
            keywords = listOf("signature", "signed", "certificate", "verify", "trust", "digital id"),
            viewerAction = "signatures"
        ),
        ToolDefinition(
            id = "safety",
            name = "Safety check",
            description = "Look for scripts, actions, attachments and misleading links without running them.",
            icon = Icons.Outlined.Security,
            section = ToolSection.FORMS_SECURITY,
            keywords = listOf(
                "safety", "security", "javascript", "script", "link", "attachment",
                "virus", "malware", "phishing", "scan", "check"
            ),
            destination = ToolDestination.SAFETY
        ),
        ToolDefinition(
            id = "protect",
            name = "Protect / Encrypt",
            description = "Password-protect a PDF. Protected files open today; writing encryption is coming soon.",
            icon = Icons.Outlined.Lock,
            section = ToolSection.FORMS_SECURITY,
            keywords = listOf("password", "security", "protected", "lock", "encrypt"),
            supported = false
        ),

        // ---- Document ------------------------------------------------------
        ToolDefinition(
            id = "new-pdf",
            name = "New PDF",
            description = "Create a blank PDF, or one from images or text.",
            icon = Icons.Outlined.AddBox,
            section = ToolSection.DOCUMENT,
            keywords = listOf("create", "blank", "images", "text", "new document"),
            destination = ToolDestination.NEW_PDF
        ),
        ToolDefinition(
            id = "scan",
            name = "Scan",
            description = "Capture pages with the camera and save a PDF.",
            icon = Icons.Outlined.DocumentScanner,
            section = ToolSection.DOCUMENT,
            keywords = listOf("camera", "scanner", "capture", "photo", "document"),
            destination = ToolDestination.SCAN
        ),
        ToolDefinition(
            id = "ocr",
            name = "Make searchable",
            description = "Add searchable text to scanned pages on device.",
            icon = Icons.Outlined.TextSnippet,
            section = ToolSection.DOCUMENT,
            keywords = listOf("searchable", "recognition", "scan text", "hindi", "english", "ocr"),
            viewerAction = "ocr"
        ),
        ToolDefinition(
            id = "compress",
            name = "Compress PDF",
            description = "Make a PDF smaller: images are scaled down and re-encoded.",
            icon = Icons.Outlined.Compress,
            section = ToolSection.DOCUMENT,
            keywords = listOf("compress", "smaller", "optimize", "shrink", "size", "reduce"),
            destination = ToolDestination.COMPRESS
        ),
        ToolDefinition(
            id = "crop",
            name = "Crop pages",
            description = "Trim white margins or set a crop box on selected pages.",
            icon = Icons.Outlined.Crop,
            section = ToolSection.DOCUMENT,
            keywords = listOf("crop", "trim", "margins", "white space", "cropbox", "auto crop"),
            destination = ToolDestination.CROP
        ),
        ToolDefinition(
            id = "watermark",
            name = "Watermark",
            description = "Put a text or picture watermark on the pages.",
            icon = Icons.AutoMirrored.Outlined.BrandingWatermark,
            section = ToolSection.DOCUMENT,
            keywords = listOf("watermark", "stamp", "diagonal", "draft", "confidential", "background"),
            destination = ToolDestination.MARK
        ),
        ToolDefinition(
            id = "page-numbers",
            name = "Page numbers",
            description = "Add a header, a footer and page numbers.",
            icon = Icons.Outlined.FormatListNumbered,
            section = ToolSection.DOCUMENT,
            keywords = listOf("page number", "header", "footer", "pagination", "{page}"),
            destination = ToolDestination.MARK
        ),
        ToolDefinition(
            id = "bates",
            name = "Bates numbering",
            description = "Stamp a number on every page for legal files.",
            icon = Icons.Outlined.Tag,
            section = ToolSection.DOCUMENT,
            keywords = listOf("bates", "stamp", "number", "legal", "prefix"),
            destination = ToolDestination.MARK
        ),
        ToolDefinition(
            id = "export-images",
            name = "Export images",
            description = "Save pages as PNG or JPEG, or pull out the pictures inside.",
            icon = Icons.Outlined.Image,
            section = ToolSection.DOCUMENT,
            keywords = listOf("export", "images", "png", "jpeg", "jpg", "extract", "pictures", "dpi"),
            // Available since the T45 acceptance run: exported pages match the
            // viewer at 72/150/300 dpi, JPEGs come out byte-identical and the
            // 300-page export stays inside the memory budget.
            supported = true,
            viewerAction = "export-images",
            destination = ToolDestination.EXPORT_IMAGES
        ),
        ToolDefinition(
            id = "present",
            name = "Present",
            description = "Show pages full screen with a timer, a pointer and an external display.",
            icon = Icons.Outlined.Slideshow,
            section = ToolSection.DOCUMENT,
            keywords = listOf(
                "present", "presentation", "slideshow", "full screen", "projector",
                "external display", "timer", "clicker", "pointer", "laser"
            ),
            viewerAction = "present"
        ),
        ToolDefinition(
            id = "reflow",
            name = "Reflow",
            description = "Read the text as one column, sized for the screen.",
            icon = Icons.Outlined.TextSnippet,
            section = ToolSection.DOCUMENT,
            keywords = listOf(
                "reflow", "read", "reading", "text", "article", "column", "zoom",
                "font size", "mobile", "fit text"
            ),
            viewerAction = "reflow"
        ),
        ToolDefinition(
            id = "flow",
            name = "Column flow",
            description = "Read one column at a time, edge to edge, without pinching.",
            icon = Icons.Outlined.ViewColumn,
            section = ToolSection.DOCUMENT,
            keywords = listOf(
                "column", "columns", "flow", "two column", "three column",
                "newspaper", "across columns", "reading order", "one thumb",
                "auto flow", "zoom column"
            ),
            viewerAction = "flow"
        ),
        ToolDefinition(
            id = "read-aloud",
            name = "Read aloud",
            description = "Hear the page read out, with the spoken word highlighted. Keeps reading with the screen off.",
            icon = Icons.Outlined.Headphones,
            section = ToolSection.DOCUMENT,
            keywords = listOf(
                "read aloud", "read out", "speak", "speech", "voice", "listen",
                "audio", "text to speech", "tts", "headphones", "narrate"
            ),
            viewerAction = "read-aloud"
        ),
        ToolDefinition(
            id = "snip",
            name = "Snip table",
            description = "Draw a box around a table and copy it out as Markdown, CSV, TSV or plain text.",
            icon = Icons.Outlined.TableRows,
            section = ToolSection.DOCUMENT,
            keywords = listOf(
                "snip", "table", "extract", "copy table", "markdown", "csv", "tsv",
                "scanned table", "ocr table"
            ),
            viewerAction = "snip"
        ),
        ToolDefinition(
            id = "info",
            name = "Info",
            description = "See page count, version and file details.",
            icon = Icons.Outlined.Info,
            section = ToolSection.DOCUMENT,
            keywords = listOf("details", "metadata", "properties", "version", "pages"),
            destination = ToolDestination.INFO
        ),
        ToolDefinition(
            id = "save",
            name = "Save",
            description = "Write the pending edits to a new PDF.",
            icon = Icons.Outlined.Save,
            section = ToolSection.DOCUMENT,
            keywords = listOf("write", "export", "keep", "store")
        ),
        ToolDefinition(
            id = "save-as",
            name = "Save as",
            description = "Write the document to a new file.",
            icon = Icons.Outlined.SaveAs,
            section = ToolSection.DOCUMENT,
            keywords = listOf("copy", "export", "duplicate", "write")
        ),
        ToolDefinition(
            id = "share",
            name = "Share",
            description = "Send the PDF to another app.",
            icon = Icons.Outlined.Share,
            section = ToolSection.DOCUMENT,
            keywords = listOf("send", "export", "email", "another app")
        ),
        ToolDefinition(
            id = "print",
            name = "Print",
            description = "Send the document to a printer.",
            icon = Icons.Outlined.Print,
            section = ToolSection.DOCUMENT,
            keywords = listOf("paper", "printer", "hard copy"),
            supported = false
        )
    )

    fun byId(id: String): ToolDefinition? = tools.firstOrNull { it.id == id }

    /** The tool that opens a given viewer mode ("highlight", "ink", "text", "select", …). */
    fun byViewerTool(mode: String): ToolDefinition? =
        tools.firstOrNull { it.viewerTool == mode && it.supported }

    /** Tools grouped for the sectioned lists, omitting empty sections. */
    fun grouped(tools: List<ToolDefinition> = this.tools): List<Pair<ToolSection, List<ToolDefinition>>> =
        ToolSection.entries.mapNotNull { section ->
            val members = tools.filter { it.section == section }
            if (members.isEmpty()) null else section to members
        }

    /**
     * Case-insensitive name, keyword and description search. Description
     * matching is word-prefix based, so "rot" finds Rotate but not "protected".
     */
    fun search(query: String): List<ToolDefinition> {
        val needle = query.trim()
        if (needle.isEmpty()) return tools
        return tools.filter { tool ->
            tool.name.contains(needle, ignoreCase = true) ||
                tool.keywords.any { it.contains(needle, ignoreCase = true) } ||
                tool.description.split(WORD_SEPARATOR).any { it.startsWith(needle, ignoreCase = true) }
        }
    }

    private val WORD_SEPARATOR = Regex("[^A-Za-z0-9]+")
}
