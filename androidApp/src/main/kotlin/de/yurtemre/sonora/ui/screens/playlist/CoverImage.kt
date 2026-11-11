package de.yurtemre.sonora.ui.screens.playlist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.RequiresApi
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Decodes a picked image, centre-crops it square, scales to [SIZE] px and encodes JPEG (LIB-17). */
internal object CoverImage {
    private const val SIZE = 1024

    suspend fun toJpeg(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeP(context, uri) else decodeLegacy(context, uri)
            val side = minOf(bmp.width, bmp.height)
            val cropped = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val scaled = if (side > SIZE) Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true) else cropped
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeP(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val min = minOf(info.size.width, info.size.height)
            if (min > SIZE * 2) {
                val f = min / SIZE
                decoder.setTargetSize(info.size.width / f, info.size.height / f)
            }
        }
    }

    private fun decodeLegacy(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return checkNotNull(context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) })
    }
}
