package com.pdfpaper.app.ui.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Sections shown as headings in All tools and in the viewer's tools sheet.
 * The order here is the order both screens render.
 */
enum class ToolSection(val label: String) {
    PAGES("Pages"),
    EDIT("Edit"),
    ANNOTATE("Annotate"),
    FORMS_SECURITY("Forms & Security"),
    DOCUMENT("Document")
}

/** Where the All tools flow goes after the user chooses a PDF. */
enum class ToolDestination { ORGANIZER, VIEWER, INFO }

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
    /** Viewer tools: also open the form-fill panel. */
    val opensForms: Boolean = false,
    /** Where choosing a PDF takes the user (the All tools flow). */
    val destination: ToolDestination = ToolDestination.VIEWER
) {
    /** Usable in this build: the engine supports it, or the debug demo stands in. */
    fun isAvailable(demoEngine: Boolean): Boolean =
        supported && (!needsEditingEngine || demoEngine)
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
            description = "Break a PDF into separate files.",
            icon = Icons.AutoMirrored.Outlined.CallSplit,
            section = ToolSection.PAGES,
            keywords = listOf("separate", "parts", "chunks"),
            organizerHint = "Split: tap Apply to split every five pages."
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
