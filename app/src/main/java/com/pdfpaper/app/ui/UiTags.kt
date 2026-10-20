package com.pdfpaper.app.ui

/** Stable semantics tags shared by the screens and the Compose UI tests. */
object UiTags {
    const val HOME_SCREEN = "screen_home"
    const val HOME_NEW_PDF = "home_new_pdf"
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

    const val OPEN_SCREEN = "screen_open"
    const val OPEN_ERROR = "open_error"

    const val OFFICE_BANNER = "office_view_banner"
    const val OFFICE_BANNER_DISMISS = "office_banner_dismiss"
    const val OFFICE_BANNER_MISSING = "office_banner_missing"

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

    // Task UI-2 screens and sheets.
    const val NEW_PDF_SCREEN = "screen_new_pdf"
    const val NEW_PDF_CREATE = "new_pdf_create"
    const val NEW_PDF_ADD_IMAGES = "new_pdf_add_images"
    const val NEW_PDF_TEXT = "new_pdf_text"
    const val SCAN_SCREEN = "screen_scan"
    const val SCAN_START = "scan_start"
    const val SCAN_SAVE = "scan_save"
    const val SETTINGS_SCREEN = "screen_settings"
    const val SETTINGS_AUTHOR = "settings_author"
    const val SETTINGS_SAVE = "settings_save"
    const val SETTINGS_LINK_PREVIEW = "settings_link_preview"
    const val SETTINGS_LINK_JUMP = "settings_link_jump"

    const val LINK_PREVIEW_SHEET = "link_preview_sheet"
    const val LINK_PINNED_PANEL = "link_pinned_panel"
    const val LINK_BACK = "link_back"

    const val COMMENTS_SHEET = "comments_sheet"
    const val COMMENTS_ADD = "comments_add"
    const val COMMENTS_REPLY = "comments_reply"
    const val COMMENTS_RESOLVE = "comments_resolve"
    const val STAMP_SHEET = "stamp_sheet"
    const val STAMP_CREATE_CUSTOM = "stamp_create_custom"
    const val STAMP_CUSTOM_DIALOG = "stamp_custom_dialog"
    const val STAMP_CUSTOM_CREATE = "stamp_custom_create"
    const val STAMP_PLACEMENT = "stamp_placement"
    const val STAMP_PLACEMENT_BAR = "stamp_placement_bar"
    const val STAMP_PLACE_CONFIRM = "stamp_place_confirm"
    const val SIGNATURES_SHEET = "signatures_sheet"
    const val OCR_SHEET = "ocr_sheet"
    const val OCR_START = "ocr_start"
    const val OCR_CANCEL = "ocr_cancel"

    // Task UI-3: signing flows.
    const val SIGN_OPTIONS_SHEET = "sign_options_sheet"
    const val SIGN_VISUAL_OPTION = "sign_visual_option"
    const val SIGN_DIGITAL_OPTION = "sign_digital_option"
    const val VISUAL_SIGNATURE_DIALOG = "visual_signature_dialog"
    const val SIGNATURE_PAD = "signature_pad"
    const val SIGNATURE_TYPE_NAME = "signature_type_name"
    const val SIGNATURE_PICK_IMAGE = "signature_pick_image"
    const val SIGNATURE_ADD_CAPTION = "signature_add_caption"
    const val SIGNATURE_SAVE_FOR_LATER = "signature_save_for_later"
    const val SIGNATURE_PLACE_BUTTON = "signature_place_button"
    const val DIGITAL_SIGNATURE_DIALOG = "digital_signature_dialog"
    const val CERTIFICATE_IMPORT = "certificate_import"
    const val CERTIFICATE_PASSWORD = "certificate_password"
    const val DIGITAL_REASON = "digital_reason"
    const val DIGITAL_LOCATION = "digital_location"
    const val DIGITAL_VISIBLE_SWITCH = "digital_visible_switch"
    const val DIGITAL_CONTINUE = "digital_continue"
    const val SIGNATURE_PLACEMENT = "signature_placement"
    const val SIGNATURE_TAP_BAR = "signature_tap_bar"
    const val SIGNATURE_PLACEMENT_BAR = "signature_placement_bar"
    const val SIGNATURE_PLACE_CONFIRM = "signature_place_confirm"
    const val SIGNING_RESULT_DIALOG = "signing_result_dialog"
    const val SIGNING_RESULT_SAVE = "signing_result_save"
    const val SIGNING_RESULT_DONE = "signing_result_done"
    const val SETTINGS_SIGNATURES = "settings_signatures"
    const val DEVICE_CONFIRM_FALLBACK = "device_confirm_fallback"

    fun toolCard(id: String): String = "tool_card_$id"

    fun viewerToolItem(id: String): String = "viewer_tool_item_$id"

    fun objectBox(id: String): String = "object_$id"

    fun newPdfMode(mode: String): String = "new_pdf_mode_$mode"

    fun stampItem(id: String): String = "stamp_item_$id"

    fun commentThread(id: Int): String = "comment_thread_$id"

    fun signatureCard(fieldName: String): String = "signature_card_${fieldName.hashCode()}"

    fun certificateItem(id: String): String = "certificate_item_$id"

    fun savedSignatureItem(id: String): String = "saved_signature_item_$id"

    fun settingsSavedSignature(id: String): String = "settings_saved_signature_$id"

    fun settingsCertificate(id: String): String = "settings_certificate_$id"
}
