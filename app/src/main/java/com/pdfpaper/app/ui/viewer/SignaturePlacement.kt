package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfpaper.app.signatures.CertificateSummary
import com.pdfpaper.app.signatures.SignatureDraft
import com.pdfpaper.app.signatures.previewAspect
import com.pdfpaper.app.ui.UiTags

enum class SignaturePlacementKind { VISUAL, DIGITAL }

/**
 * A signature waiting to be dropped on a page. Fractions are on-screen
 * coordinates; [ViewerViewModel] converts them to PDF user space when the user
 * applies the placement.
 */
data class SignaturePlacementUi(
    val kind: SignaturePlacementKind,
    val pageIndex: Int,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val widthFraction: Float = 0.42f,
    val draft: SignatureDraft? = null,
    val certificate: CertificateSummary? = null,
    val reason: String = "",
    val location: String = "",
    val captionName: String? = null,
    val captionDate: String? = null
) {
    val aspect: Float
        get() = if (kind == SignaturePlacementKind.VISUAL && draft != null) {
            draft.previewAspect().coerceIn(1.2f, 8f)
        } else {
            2.8f
        }
}

/** A chosen signature that has not been dropped on a page yet. */
data class PendingSignature(
    val kind: SignaturePlacementKind,
    val widthFraction: Float = 0.42f,
    val draft: SignatureDraft? = null,
    val certificate: CertificateSummary? = null,
    val reason: String = "",
    val location: String = "",
    val captionName: String? = null,
    val captionDate: String? = null
) {
    fun at(pageIndex: Int, centerX: Float, centerY: Float): SignaturePlacementUi =
        SignaturePlacementUi(
            kind = kind,
            pageIndex = pageIndex,
            centerX = centerX,
            centerY = centerY,
            widthFraction = widthFraction,
            draft = draft,
            certificate = certificate,
            reason = reason,
            location = location,
            captionName = captionName,
            captionDate = captionDate
        )
}

/** The draggable, pinchable signature preview drawn over one page. */
@Composable
fun SignaturePlacementOverlay(
    placement: SignaturePlacementUi,
    modifier: Modifier = Modifier,
    onTransform: (centerX: Float, centerY: Float, widthFraction: Float) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val width = maxWidth * placement.widthFraction.coerceIn(0.08f, 0.92f)
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
                .pointerInput(placement.pageIndex, placement.kind) {
                    var centerX = placement.centerX
                    var centerY = placement.centerY
                    var widthFraction = placement.widthFraction
                    detectTransformGestures { _, pan, zoom, _ ->
                        val dx = if (size.width > 0) pan.x / size.width else 0f
                        val dy = if (size.height > 0) pan.y / size.height else 0f
                        centerX = (centerX + dx).coerceIn(0.06f, 0.94f)
                        centerY = (centerY + dy).coerceIn(0.06f, 0.94f)
                        widthFraction = (widthFraction * zoom).coerceIn(0.08f, 0.92f)
                        onTransform(centerX, centerY, widthFraction)
                    }
                }
                .alpha(0.92f)
                .testTag(UiTags.SIGNATURE_PLACEMENT)
        ) {
            when {
                placement.kind == SignaturePlacementKind.VISUAL && placement.draft != null ->
                    SignaturePreview(
                        draft = placement.draft,
                        modifier = Modifier.fillMaxSize()
                    )

                placement.certificate != null -> DigitalPlacementPreview(
                    certificate = placement.certificate,
                    reason = placement.reason,
                    location = placement.location
                )

                else -> SignaturePreview(
                    draft = SignatureDraft.Typed("Signature"),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun DigitalPlacementPreview(
    certificate: CertificateSummary,
    reason: String,
    location: String
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                "Digitally signed by",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                certificate.commonName ?: certificate.label,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val extra = listOfNotNull(
                reason.takeIf { it.isNotBlank() },
                location.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (extra.isNotBlank()) {
                Text(
                    extra,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Digital signature",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Prompt bar shown until the user taps the page to drop the signature. */
@Composable
fun SignatureTapBar(
    pending: PendingSignature,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.SIGNATURE_TAP_BAR)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (pending.kind == SignaturePlacementKind.DIGITAL) {
                    "Tap the page where the digital signature should appear."
                } else {
                    "Tap the page where your signature should appear."
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** Confirmation bar shown while a signature follows the user's finger. */
@Composable
fun SignaturePlacementBar(
    placement: SignaturePlacementUi,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.SIGNATURE_PLACEMENT_BAR)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                if (placement.kind == SignaturePlacementKind.DIGITAL) {
                    "Drag to move • pinch to resize. Then confirm with your device lock."
                } else {
                    "Drag to move • pinch to resize"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(
                onClick = onApply,
                modifier = Modifier.testTag(UiTags.SIGNATURE_PLACE_CONFIRM)
            ) {
                Text(
                    if (placement.kind == SignaturePlacementKind.DIGITAL) "Sign here" else "Apply signature"
                )
            }
        }
    }
}
