package com.pdfpaper.app.ui.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector

/** What happens after the user chooses a PDF for a tool. */
enum class ToolAction {
    ORGANIZE,
    ANNOTATE,
    FILL_FORM,
    ENCRYPT_INFO,
    INFO
}

/**
 * One tile in the tool catalogue.
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
    val keywords: List<String> = emptyList(),
    /** One-line reminder shown when the organizer opens for this tool. */
    val organizerHint: String? = null
)

/** The ten tools shown on the Tools tab, in catalogue order. */
object ToolCatalogue {

    val tools: List<ToolDefinition> = listOf(
        ToolDefinition(
            id = "merge",
            title = "Merge",
            description = "Combine several PDFs into one document.",
            icon = Icons.Filled.Add,
            action = ToolAction.ORGANIZE,
            keywords = listOf("combine", "join", "append", "add pdf"),
            organizerHint = "Merge: choose the first PDF, then tap Add PDF to append another."
        ),
        ToolDefinition(
            id = "split",
            title = "Split",
            description = "Break a PDF into separate files.",
            icon = Icons.Filled.Menu,
            action = ToolAction.ORGANIZE,
            keywords = listOf("separate", "parts", "chunks"),
            organizerHint = "Split: select pages and tap Extract, or use Split 5 for every five pages."
        ),
        ToolDefinition(
            id = "rotate",
            title = "Rotate",
            description = "Turn pages to the right orientation.",
            icon = Icons.Filled.Refresh,
            action = ToolAction.ORGANIZE,
            keywords = listOf("turn", "orientation", "landscape", "portrait"),
            organizerHint = "Rotate: select pages, then tap ⟲ 90 or ⟳ 90."
        ),
        ToolDefinition(
            id = "reorder",
            title = "Reorder",
            description = "Move pages into a new order.",
            icon = Icons.AutoMirrored.Filled.List,
            action = ToolAction.ORGANIZE,
            keywords = listOf("move", "order", "organize"),
            organizerHint = "Reorder: select pages, then use ↑ or ↓ to move them."
        ),
        ToolDefinition(
            id = "delete",
            title = "Delete",
            description = "Remove pages you no longer need.",
            icon = Icons.Filled.Delete,
            action = ToolAction.ORGANIZE,
            keywords = listOf("remove", "drop", "pages"),
            organizerHint = "Delete: select pages, then tap Delete."
        ),
        ToolDefinition(
            id = "extract",
            title = "Extract",
            description = "Keep only the pages you choose.",
            icon = Icons.Filled.Share,
            action = ToolAction.ORGANIZE,
            keywords = listOf("keep", "save pages", "selected", "export"),
            organizerHint = "Extract: select pages, then tap Extract."
        ),
        ToolDefinition(
            id = "annotate",
            title = "Annotate",
            description = "Highlight, draw and add notes.",
            icon = Icons.Filled.Edit,
            action = ToolAction.ANNOTATE,
            keywords = listOf("highlight", "ink", "draw", "note", "comment", "markup")
        ),
        ToolDefinition(
            id = "fill-form",
            title = "Fill form",
            description = "Complete interactive form fields.",
            icon = Icons.Filled.CheckCircle,
            action = ToolAction.FILL_FORM,
            keywords = listOf("form", "fields", "acroform", "text field", "checkbox", "radio")
        ),
        ToolDefinition(
            id = "encrypt",
            title = "Encrypt",
            description = "Open protected PDFs; writing encryption is planned.",
            icon = Icons.Filled.Lock,
            action = ToolAction.ENCRYPT_INFO,
            keywords = listOf("password", "security", "protected", "lock")
        ),
        ToolDefinition(
            id = "info",
            title = "Info",
            description = "See page count, version and file details.",
            icon = Icons.Filled.Info,
            action = ToolAction.INFO,
            keywords = listOf("details", "metadata", "properties", "version", "pages")
        )
    )

    fun byId(id: String): ToolDefinition? = tools.firstOrNull { it.id == id }

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
