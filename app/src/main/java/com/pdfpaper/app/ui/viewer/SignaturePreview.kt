package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfpaper.app.signatures.SignatureDraft
import com.pdfpaper.app.signatures.SignatureFontStyle
import com.pdfpaper.app.ui.components.rememberEncodedImageBitmap

/**
 * Renders a signature draft on a white paper-like box. This is the same
 * artwork the engine paints, drawn here so the user can check a drawn, typed
 * or image signature before placing it on the page.
 */
@Composable
fun SignaturePreview(
    draft: SignatureDraft,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Box(
        modifier = modifier
            .background(Color.White)
            .padding(if (compact) 4.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        when (draft) {
            is SignatureDraft.Drawn -> DrawnPreview(draft, compact)
            is SignatureDraft.Typed -> TypedPreview(draft, compact)
            is SignatureDraft.Image -> {
                val bitmap = rememberEncodedImageBitmap(draft.bytes)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Saved image signature",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        "Image signature",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawnPreview(draft: SignatureDraft.Drawn, compact: Boolean) {
    val points = draft.strokes.flatten()
    Canvas(Modifier.fillMaxSize()) {
        if (points.isEmpty()) return@Canvas
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(0.0001f)
        val spanY = (maxY - minY).coerceAtLeast(0.0001f)
        val inset = size.minDimension * 0.08f
        val width = (size.width - inset * 2).coerceAtLeast(1f)
        val height = (size.height - inset * 2).coerceAtLeast(1f)
        val scale = minOf(width / spanX, height / spanY)
        val offsetX = inset + (width - spanX * scale) / 2f
        val offsetY = inset + (height - spanY * scale) / 2f
        val strokeWidth = if (compact) 1.8.dp.toPx() else 2.4.dp.toPx()

        fun mapX(x: Float): Float = offsetX + (x - minX) * scale
        fun mapY(y: Float): Float = offsetY + (y - minY) * scale

        draft.strokes.forEach { stroke ->
            if (stroke.isEmpty()) return@forEach
            val path = Path()
            path.moveTo(mapX(stroke.first().x), mapY(stroke.first().y))
            for (index in 1 until stroke.size) {
                val point = stroke[index]
                path.lineTo(mapX(point.x), mapY(point.y))
            }
            drawPath(
                path = path,
                color = Color(0xFF18181B),
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            if (stroke.size == 1) {
                drawCircle(
                    color = Color(0xFF18181B),
                    radius = strokeWidth / 2f,
                    center = Offset(mapX(stroke.first().x), mapY(stroke.first().y))
                )
            }
        }
    }
}

@Composable
private fun TypedPreview(draft: SignatureDraft.Typed, compact: Boolean) {
    val text = draft.name.trim().ifBlank { "Your name" }
    val family = draft.font.previewFamily()
    val italic = draft.font == SignatureFontStyle.CLASSIC
    val size = when {
        compact -> 20.sp
        text.length <= 10 -> 40.sp
        text.length <= 16 -> 34.sp
        text.length <= 24 -> 27.sp
        else -> 21.sp
    }
    Text(
        text = text,
        color = Color(0xFF18181B),
        fontFamily = family,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontWeight = if (draft.font == SignatureFontStyle.SCRIPT) FontWeight.Medium else FontWeight.Normal,
        fontSize = size,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
    )
}

internal fun SignatureFontStyle.previewFamily(): FontFamily = when (this) {
    SignatureFontStyle.CLASSIC, SignatureFontStyle.FORMAL -> FontFamily.Serif
    SignatureFontStyle.MODERN -> FontFamily.SansSerif
    SignatureFontStyle.SCRIPT -> FontFamily.Cursive
}
