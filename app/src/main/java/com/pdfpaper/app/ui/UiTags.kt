package com.pdfpaper.app.ui

/** Stable semantics tags shared by the screens and the Compose UI tests. */
object UiTags {
    const val HOME_SCREEN = "screen_home"
    const val TOOLS_SCREEN = "screen_tools"
    const val RECENT_SCREEN = "screen_recent"
    const val TOOL_DETAIL_SCREEN = "screen_tool_detail"
    const val INFO_SCREEN = "screen_info"

    const val NAV_HOME = "nav_home"
    const val NAV_TOOLS = "nav_tools"
    const val NAV_RECENT = "nav_recent"

    const val TOOL_SEARCH = "tool_search"
    const val TOOL_GRID = "tool_grid"
    const val TOOL_DETAIL_TITLE = "tool_detail_title"
    const val RECENT_LIST = "recent_list"

    const val EDIT_TOOLBAR = "edit_toolbar"
    const val EDIT_SELECT = "edit_select"
    const val EDIT_ADD_TEXT = "edit_add_text"
    const val EDIT_ADD_IMAGE = "edit_add_image"
    const val EDIT_UNDO = "edit_undo"
    const val EDIT_REDO = "edit_redo"
    const val EDIT_SAVE = "edit_save"
    const val EDIT_OBJECT_BAR = "edit_object_bar"

    const val VIEWER_TOOLS_BUTTON = "viewer_tools_button"
    const val VIEWER_TOOLS_SHEET = "viewer_tools_sheet"
    const val VIEWER_TOOLS_SEARCH = "viewer_tools_search"
    const val VIEWER_TOOL_MODE_BAR = "viewer_tool_mode_bar"
    const val VIEWER_TOOL_DONE = "viewer_tool_done"
    const val VIEWER_TOOL_CANCEL = "viewer_tool_cancel"

    fun toolCard(id: String): String = "tool_card_$id"

    fun viewerToolItem(id: String): String = "viewer_tool_item_$id"

    fun objectBox(id: String): String = "object_$id"
}
