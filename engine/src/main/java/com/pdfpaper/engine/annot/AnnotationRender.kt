package com.pdfpaper.engine.annot

import com.pdfpaper.engine.graphics.DisplayList
import com.pdfpaper.engine.graphics.DisplayListBuilder
import com.pdfpaper.engine.image.ExternalImageDecoder
import com.pdfpaper.engine.page.PdfPage

/**
 * Display list that paints this page's annotations over the page content
 * (ISO 32000-1, §12.5). It is a separate list from [buildDisplayList] because
 * a viewer may want to toggle annotations, and because the page content is
 * cached independently of the (occasionally heavier) appearance streams.
 *
 * Rendering uses each annotation's /AP /N appearance when it has one (§12.5.5)
 * and falls back to a synthetic appearance built from the annotation's geometry
 * otherwise.
 */
fun PdfPage.buildAnnotationDisplayList(
    filter: AnnotationFilter,
    decoder: ExternalImageDecoder? = null
): DisplayList = TODO("T68")

fun PdfPage.buildAnnotationDisplayList(decoder: ExternalImageDecoder? = null): DisplayList =
    DisplayListBuilder(this, decoder).buildAnnotations(annotations())
