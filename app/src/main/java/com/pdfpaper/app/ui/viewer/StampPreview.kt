package com.pdfpaper.app.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfpaper.app.ui.components.rememberEncodedImageBitmap
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.stamp.StampArt
import com.pdfpaper.engine.stamp.StampDesign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A faithful, Compose-drawn preview of an engine stamp design. The sheet and
 * the placement overlay use the same composable, so what the user positions is
 * what the engine paints (border, colours, word and the dynamic by/at line).
 */
@Composable
fun StampPreview(
    design: StampDesign,
    author: String,
    modifier: Modifier = Modifier,
    imageBytes: ByteArray? = null,
    fontSize: TextUnit = 12.sp,
    showDynamicLine: Boolean = true
) {
    val border = design.borderColor.toComposeColor()
    val textColor = design.textColor.toComposeColor()
    val fill = design.fillColor?.toComposeColor() ?: Color.White
    val shape = RoundedCornerShape(3.dp)
    val date = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date()) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.4.dp, border, shape)
            .padding(if (design.art == StampArt.CERTIFICATE) 3.dp else 5.dp)
    ) {
        when (design.art) {
            StampArt.IMAGE -> {
                val bitmap = rememberEncodedImageBitmap(imageBytes)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = design.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            design.label,
                            color = textColor,
                            fontSize = fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            StampArt.CERTIFICATE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, border.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        design.word,
                        color = textColor,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = fontSize,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            StampArt.ARROW -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        design.word,
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(1.dp))
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.height(16.dp)
                    )
                }
            }

            StampArt.BANNER -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        design.word,
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSize,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (design.dynamic && showDynamicLine) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "By ${author.ifBlank { "You" }} · $date",
                            color = textColor.copy(alpha = 0.8f),
                            fontSize = (fontSize.value * 0.55f).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

internal fun PdfColor.toComposeColor(): Color =
    Color(red.toFloat().coerceIn(0f, 1f), green.toFloat().coerceIn(0f, 1f), blue.toFloat().coerceIn(0f, 1f), alpha.toFloat().coerceIn(0f, 1f))
