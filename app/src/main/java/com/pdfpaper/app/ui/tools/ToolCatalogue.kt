package com.pdfpaper.app.ui.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.ui.graphics.vector.ImageVector

/** What happens after the user chooses a PDF for a tool. */
enum class ToolAction {
    ORGANIZE,
    ANNOTATE,
    FILL_FORM,
    ENCRYPT_INFO,
    INFO,
    ADD_TEXT,
    EDIT_TEXT,
    ADD_IMAGE,
    EDIT_IMAGE
}

/** Catalogue sections shown as headings in All tools. */
enum class ToolGroup(val label: String) {
    ORGANIZE("Organize"),
    ANNOTATE("Annotate & forms"),
    EDIT("Edit"),
    SECURITY("Security & info")
}

/**
 * One row in the tool catalogue.
 *
 * [keywords] hold synonyms so search also finds a tool by what it does
 * ("combine" for Merge, "password" for Encrypt), not only by its title.
 */
data class ToolDefinition(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val action: ToolAction,
    val group: ToolGroup,
    val keywords: List<String> = emptyList(),
    /** One-line reminder shown when the organizer opens for this tool. */
    val organizerHint: String? = null,
    /** True when the item needs the new object-editing engine API. */
    val requiresEditingEngine: Boolean = false
)

/** The catalogue shown on the All tools tab, in section and reading order. */
object ToolCatalogue {

    val tools: List<ToolDefinition> = listOf(
        ToolDefinition(
            id = "merge",
            title = "Merge",
            description = "Combine several PDFs into one document.",
            icon = Icons.AutoMirrored.Outlined.CallMerge,
            action = ToolAction.ORGANIZE,
            group = ToolGroup.ORGANIZE,
            keywords = listOf("combine", "join", "append", "add pdf"),
            organizerHint = "Merge: choose the first PDF, then tap Add PDF to append another."
        ),
        ToolDefinition(
            id = "split",
            title = "Split",
            description = "Break a PDF into separate files.",
            icon = Icons.AutoMirrored.Outlined.CallSplit,
            action = ToolAction.ORGANIZE,
            group = ToolGroup.ORGANIZE,
            keywords = listOf("separate", "parts", "chunks"),
            organizerHint = "Split: select pages and tap Extract, or use Split 5 for every five pages."
        ),
        ToolDefinition(
            id = "rotate",
            title = "Rotate",
            description = "Turn pages to the right orientation.",
            icon = Icons.AutoMirrored.Outlined.RotateRight,
            action = ToolAction.ORGANIZE,
            group = ToolGroup.ORGANIZE,
            keywords = listOf("turn", "orientation", "landscape", "portrait"),
            organizerHint = "Rotate: select pages, then tap ↺ 90 or ↻ 90."
        ),
        ToolDefinition(
            id = "reorder",
            title = "Reorder",
            description = "Move pages into a new order.",
            icon = Icons.Outlined.SwapVert,
            action = ToolAction.ORGANIZE,
            group = ToolGroup.ORGANIZE,
            keywords = listOf("move", "order", "organize"),
            organizerHint = "Reorder: select pages, then use ↑ or ↓ to move them."
        ),
        ToolDefinition(
            id = "delete",
            title = "Delete",
            description = "Remove pages you no longer need.",
            icon = Icons.Outlined.Delete,
            action = ToolAction.ORGANIZE,
            group = ToolGroup.ORGANIZE,
            keywords = listOf("remove", "drop", "pages"),
            organizerHint = "Delete: select pages, then tap Delete."
        ),
        ToolDefinition(
            id = "extract",
            title = "Extract",
            description = "Keep only the pages you choose.",
            icon = Icons.Outlined.FileDownload,
            action = ToolAction.ORGANIZE,
            group = ToolGroup.ORGANIZE,
            keywords = listOf("keep", "save pages", "selected", "export"),
            organizerHint = "Extract: select pages, then tap Extract."
        ),
        ToolDefinition(
            id = "annotate",
            title = "Annotate",
            description = "Highlight, draw and add notes.",
            icon = Icons.Outlined.Highlight,
            action = ToolAction.ANNOTATE,
            group = ToolGroup.ANNOTATE,
            keywords = listOf("highlight", "ink", "draw", "note", "comment", "markup")
        ),
        ToolDefinition(
            id = "fill-form",
            title = "Fill form",
            description = "Complete interactive form fields.",
            icon = Icons.AutoMirrored.Outlined.FactCheck,
            action = ToolAction.FILL_FORM,
            group = ToolGroup.ANNOTATE,
            keywords = listOf("form", "fields", "acroform", "text field", "checkbox", "radio")
        ),
        ToolDefinition(
            id = "add-text",
            title = "Add text",
            description = "Place new text anywhere on a page.",
            icon = Icons.Outlined.TextFields,
            action = ToolAction.ADD_TEXT,
            group = ToolGroup.EDIT,
            keywords = listOf("text", "type", "insert", "write", "label"),
            requiresEditingEngine = true
        ),
        ToolDefinition(
            id = "edit-text",
            title = "Edit text",
            description = "Change text, font size and colour.",
            icon = Icons.Outlined.EditNote,
            action = ToolAction.EDIT_TEXT,
            group = ToolGroup.EDIT,
            keywords = listOf("text", "edit", "content", "font", "colour", "color"),
            requiresEditingEngine = true
        ),
        ToolDefinition(
            id = "add-image",
            title = "Add image",
            description = "Place a picture and resize it on the page.",
            icon = Icons.Outlined.AddPhotoAlternate,
            action = ToolAction.ADD_IMAGE,
            group = ToolGroup.EDIT,
            keywords = listOf("image", "picture", "photo", "place", "insert"),
            requiresEditingEngine = true
        ),
        ToolDefinition(
            id = "edit-image",
            title = "Edit image",
            description = "Move, resize, replace or delete a picture.",
            icon = Icons.Outlined.Photo,
            action = ToolAction.EDIT_IMAGE,
            group = ToolGroup.EDIT,
            keywords = listOf("image", "picture", "photo", "move", "resize", "replace", "delete"),
            requiresEditingEngine = true
        ),
        ToolDefinition(
            id = "encrypt",
            title = "Encrypt",
            description = "Open password-protected PDFs.",
            icon = Icons.Outlined.Lock,
            action = ToolAction.ENCRYPT_INFO,
            group = ToolGroup.SECURITY,
            keywords = listOf("password", "security", "protected", "lock")
        ),
        ToolDefinition(
            id = "info",
            title = "Info",
            description = "See page count, version and file details.",
            icon = Icons.Outlined.Info,
            action = ToolAction.INFO,
            group = ToolGroup.SECURITY,
            keywords = listOf("details", "metadata", "properties", "version", "pages")
        )
    )

    fun byId(id: String): ToolDefinition? = tools.firstOrNull { it.id == id }

    /** Tools grouped for the sectioned catalogue, omitting empty sections. */
    fun grouped(tools: List<ToolDefinition> = this.tools): List<Pair<ToolGroup, List<ToolDefinition>>> =
        ToolGroup.entries.mapNotNull { group ->
            val members = tools.filter { it.group == group }
            if (members.isEmpty()) null else group to members
        }

    /**
     * Case-insensitive title, keyword and description search. Description
     * matching is word-prefix based, so "rot" finds Rotate but not "protected".
     */
    fun search(query: String): List<ToolDefinition> {
        val needle = query.trim()
        if (needle.isEmpty()) return tools
        return tools.filter { tool ->
            tool.title.contains(needle, ignoreCase = true) ||
                tool.keywords.any { it.contains(needle, ignoreCase = true) } ||
                tool.description.split(WORD_SEPARATOR).any { it.startsWith(needle, ignoreCase = true) }
        }
    }

    private val WORD_SEPARATOR = Regex("[^A-Za-z0-9]+")
}
