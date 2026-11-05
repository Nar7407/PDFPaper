package com.pdfpaper.app.ui.crop

import com.pdfpaper.engine.geom.PdfRect

/**
 * Task T57: "Make this permanent" already knows the boxes it wants cropped, so
 * they are handed over in memory instead of being serialised into a route.
 * The crop screen takes them once; anything else opens the screen unchanged.
 */
object PendingCrop {

    private var path: String? = null
    private var boxes: Map<Int, PdfRect> = emptyMap()

    fun put(path: String, boxes: Map<Int, PdfRect>) {
        this.path = path
        this.boxes = boxes
    }

    /** The boxes waiting for [path], consumed by the call. */
    fun take(path: String): Map<Int, PdfRect> {
        if (this.path != path) return emptyMap()
        val result = boxes
        this.path = null
        boxes = emptyMap()
        return result
    }
}
