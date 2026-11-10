package de.yurtemre.sonora.shared.scan

import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** LIB-22: per-song seed colour from artwork, Palette vibrant → dominant; null on failure. */
class SeedColorExtractor(private val decodeSize: Int = 128) {

    /** Returns ARGB int, or null when the image is unreadable or has no usable swatch. */
    suspend fun extract(path: String): Int? = withContext(Dispatchers.IO) { extractBlocking(path) }

    fun extractBlocking(path: String): Int? = try {
        val file = File(path)
        if (!file.isFile) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= decodeSize && bounds.outHeight / (sample * 2) >= decodeSize) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp == null) null else try {
                val palette = Palette.from(bmp).maximumColorCount(16).generate()
                (palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb
            } finally {
                bmp.recycle()
            }
        }
    } catch (_: Throwable) {
        null
    }
}
