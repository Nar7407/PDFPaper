package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.engine.stamp.StampDesign

/** Where a selected stamp is being placed, in on-screen page fractions. */
data class StampPlacement(
    val design: StampDesign,
    val pageIndex: Int,
    val centerX: Float,
    val centerY: Float,
    val widthFraction: Float,
    val imageBytes: ByteArray? = null
) {
    val aspect: Float
        get() = design.aspect.takeIf { it.isFinite() && it > 0.35 }?.toFloat() ?: 2.6f
}

/**
 * The draggable/pinchable stamp preview drawn over the page. The caller turns
 * the final fractions into a [com.pdfpaper.engine.annot.StampSpec], taking the
 * page's /Rotate into account.
 */
@Composable
fun StampPlacementOverlay(
    placement: StampPlacement,
    author: String,
    modifier: Modifier = Modifier,
    onTransform: (centerX: Float, centerY: Float, widthFraction: Float) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val width = maxWidth * placement.widthFraction.coerceIn(0.06f, 0.92f)
        val height = width / placement.aspect
        Box(
            modifier = Modifier
                .offset(
                    x = maxWidth * placement.centerX - width / 2,
                    y = maxHeight * placement.centerY - height / 2
                )
                .width(width)
                .height(height)
                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                .pointerInput(placement.pageIndex, placement.design.id) {
                    var centerX = placement.centerX
                    var centerY = placement.centerY
                    var widthFraction = placement.widthFraction
                    detectTransformGestures { _, pan, zoom, _ ->
                        val dx = if (size.width > 0) pan.x / size.width else 0f
                        val dy = if (size.height > 0) pan.y / size.height else 0f
                        centerX = (centerX + dx).coerceIn(0.04f, 0.96f)
                        centerY = (centerY + dy).coerceIn(0.04f, 0.96f)
                        widthFraction = (widthFraction * zoom).coerceIn(0.06f, 0.92f)
                        onTransform(centerX, centerY, widthFraction)
                    }
                }
                .alpha(0.9f)
                .testTag(UiTags.STAMP_PLACEMENT)
        ) {
            StampPreview(
                design = placement.design,
                author = author,
                imageBytes = placement.imageBytes,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth(),
                showDynamicLine = true
            )
        }
    }
}

/** Confirmation bar shown while a stamp follows the user's finger. */
@Composable
fun StampPlacementBar(
    placement: StampPlacement,
    onCancel: () -> Unit,
    onPlace: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.STAMP_PLACEMENT_BAR)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Drag to move · pinch to resize",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = onPlace, modifier = Modifier.testTag(UiTags.STAMP_PLACE_CONFIRM)) {
                Text("Place ${placement.design.label}")
            }
        }
    }
}
