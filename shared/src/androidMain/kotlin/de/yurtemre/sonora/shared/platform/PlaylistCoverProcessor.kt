package de.yurtemre.sonora.shared.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** LIB-17: center-crop square, scale to ≤512 px, JPEG q85. Returns null on decode failure. */
object PlaylistCoverProcessor {
    const val SIZE = 512
    const val QUALITY = 85

    fun process(input: ByteArray): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            val minSide = minOf(bounds.outWidth, bounds.outHeight)
            while (minSide / (sample * 2) >= SIZE) sample *= 2
            val src = BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { inSampleSize = sample })
            src?.let { encode(it) }
        }
    } catch (e: Throwable) { null }

    private fun encode(src: Bitmap): ByteArray {
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val target = minOf(side, SIZE)
        val scaled = if (target == side) cropped else Bitmap.createScaledBitmap(cropped, target, target, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== src) cropped.recycle()
        src.recycle()
        return out.toByteArray()
    }
}
