package de.yurtemre.sonora.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import de.yurtemre.sonora.R
import java.io.File
import java.util.concurrent.Executors

/** Draws every widget instance from [WidgetStateStore] (WID-02, WID-04). */
object WidgetRenderer {
    private const val TAG = "WidgetRenderer"

    // Height from which the large layout is used.
    private const val LARGE_MIN_HEIGHT_DP = 100

    // 96dp art at xxxhdpi; an ARGB bitmap stays well under the binder limit.
    private const val ART_MAX_PX = 384

    private val executor = Executors.newSingleThreadExecutor()

    /** Renders [ids] (all instances when null) off the main thread, then finishes [pending]. */
    fun renderAsync(
        context: Context,
        ids: IntArray? = null,
        pending: BroadcastReceiver.PendingResult? = null,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                val manager = AppWidgetManager.getInstance(appContext)
                val targets = ids ?: manager.getAppWidgetIds(
                    ComponentName(appContext, NowPlayingWidgetProvider::class.java),
                )
                render(appContext, manager, targets)
            } catch (e: Exception) {
                Log.w(TAG, "Widget render failed", e)
            } finally {
                pending?.finish()
            }
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val state = WidgetStateStore.load(context)
        val artCache = HashMap<Int, Bitmap?>()
        for (id in ids) {
            val large = isLarge(manager.getAppWidgetOptions(id))
            val artSizePx = context.resources.getDimensionPixelSize(
                if (large) R.dimen.widget_art_size_large else R.dimen.widget_art_size,
            ).coerceAtMost(ART_MAX_PX)
            val path = state.artPath
            val art = if (state.hasTrack && path != null) {
                artCache.getOrPut(artSizePx) { loadArt(context, path, artSizePx) }
            } else {
                null
            }
            manager.updateAppWidget(id, buildViews(context, state, large, art))
        }
    }

    private fun isLarge(options: Bundle?): Boolean =
        (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0) >= LARGE_MIN_HEIGHT_DP

    private fun buildViews(context: Context, state: WidgetState, large: Boolean, art: Bitmap?): RemoteViews {
        val layout = if (large) R.layout.widget_now_playing_large else R.layout.widget_now_playing
        val views = RemoteViews(context.packageName, layout)
        val openApp = WidgetActions.openApp(context)

        if (state.hasTrack) {
            views.setTextViewText(R.id.widget_title, state.title.orEmpty())
            views.setTextViewText(R.id.widget_artist, state.artist.orEmpty())
            if (art != null) {
                views.setImageViewBitmap(R.id.widget_art, art)
            } else {
                views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
            }
        } else {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_nothing_playing))
            views.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_tap_to_open))
            views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
        }

        val playing = state.hasTrack && state.playing
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (playing) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
        )
        views.setContentDescription(
            R.id.widget_play_pause,
            context.getString(if (playing) R.string.widget_pause else R.string.widget_play),
        )

        views.setOnClickPendingIntent(R.id.widget_art, openApp)
        views.setOnClickPendingIntent(R.id.widget_text, openApp)
        if (state.hasTrack) {
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                WidgetActions.service(context, WidgetActions.ACTION_PLAY_PAUSE, 1),
            )
            views.setOnClickPendingIntent(R.id.widget_next, WidgetActions.service(context, WidgetActions.ACTION_NEXT, 2))
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                WidgetActions.service(context, WidgetActions.ACTION_PREVIOUS, 3),
            )
        } else {
            views.setOnClickPendingIntent(R.id.widget_play_pause, openApp)
            views.setOnClickPendingIntent(R.id.widget_next, openApp)
            views.setOnClickPendingIntent(R.id.widget_previous, openApp)
        }
        return views
    }

    private fun loadArt(context: Context, path: String, sizePx: Int): Bitmap? = try {
        if (!File(path).isFile) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.let {
                sleeve(it, sizePx, context.resources.getDimension(R.dimen.widget_art_radius))
            }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** Centre-crops [source] to a [size] square with sleeve corners. */
    private fun sleeve(source: Bitmap, size: Int, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = size.toFloat() / minOf(source.width, source.height)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((size - source.width * scale) / 2f, (size - source.height * scale) / 2f)
                },
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        Canvas(output).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        source.recycle()
        return output
    }
}
