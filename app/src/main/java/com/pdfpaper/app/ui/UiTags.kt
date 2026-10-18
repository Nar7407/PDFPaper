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

    fun toolCard(id: String): String = "tool_card_$id"
}
