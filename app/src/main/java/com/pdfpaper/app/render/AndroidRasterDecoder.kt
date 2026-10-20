package com.pdfpaper.app.render

import android.graphics.BitmapFactory
import com.pdfpaper.engine.edit.Raster
import com.pdfpaper.engine.edit.RasterDecoder

/**
 * [RasterDecoder] backed by Android's platform codec. The engine uses it for
 * PNG bytes (gallery images, custom image stamps) where it cannot use the JDK
 * ImageIO codec. Returns null on undecodable input instead of throwing.
 */
object AndroidRasterDecoder : RasterDecoder {

    override fun decode(bytes: ByteArray): Raster? {
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            null
        } ?: return null

        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            Raster(width, height, argb)
        } catch (t: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }
}
