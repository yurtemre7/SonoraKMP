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
import de.yurtemre.sonora.ui.format.withAppLocale
import java.io.File
import java.util.concurrent.Executors

/** Draws every widget instance from [WidgetStateStore] (WID-02, WID-04). */
object WidgetRenderer {
    private const val TAG = "WidgetRenderer"

    // Height from which the large layout is used.
    private const val LARGE_MIN_HEIGHT_DP = 100

    // 96dp art at xxxhdpi; an ARGB bitmap stays well under the binder limit.
    private const val ART_MAX_PX = 384

    // Square widget cover: longest side in px (ARGB ≈ 700 KB at most, under the binder limit).
    private const val COVER_MAX_PX = 420f
    private const val SQUARE_DEFAULT_DP = 160
    private const val COVER_SCRIM_COLOR = 0x73000000

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
                val targets = ids ?: (
                    manager.getAppWidgetIds(ComponentName(appContext, NowPlayingWidgetProvider::class.java)) +
                        manager.getAppWidgetIds(ComponentName(appContext, SquareNowPlayingWidgetProvider::class.java))
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
            if (isSquare(manager, id)) {
                val (w, h, scale) = squareArtSize(context, manager.getAppWidgetOptions(id))
                val path = state.artPath
                val art = if (state.hasTrack && path != null) loadCoverArt(context, path, w, h, scale) else null
                manager.updateAppWidget(id, buildViews(context, state, R.layout.widget_now_playing_square, art, cover = true))
                continue
            }
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
            val layout = if (large) R.layout.widget_now_playing_large else R.layout.widget_now_playing
            manager.updateAppWidget(id, buildViews(context, state, layout, art, cover = false))
        }
    }

    private fun isLarge(options: Bundle?): Boolean =
        (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0) >= LARGE_MIN_HEIGHT_DP

    private fun isSquare(manager: AppWidgetManager, id: Int): Boolean =
        manager.getAppWidgetInfo(id)?.provider?.className == SquareNowPlayingWidgetProvider::class.java.name

    /**
     * Pixel size of the square widget's cover bitmap: the portrait cell size (min width × max height),
     * scaled down so the longer side stays at [COVER_MAX_PX], along with that downscale factor.
     */
    private fun squareArtSize(context: Context, options: Bundle?): Triple<Int, Int, Float> {
        val density = context.resources.displayMetrics.density
        val wDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 } ?: SQUARE_DEFAULT_DP
        val hDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)?.takeIf { it > 0 } ?: wDp
        val w = wDp * density
        val h = hDp * density
        val scale = minOf(1f, COVER_MAX_PX / maxOf(w, h))
        return Triple((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), scale)
    }

    /**
     * @param cover the square layout: [art] already fills the box (dimmed, rounded); without art the
     *   placeholder background shows through instead of a small sleeve placeholder.
     */
    private fun buildViews(baseContext: Context, state: WidgetState, layout: Int, art: Bitmap?, cover: Boolean): RemoteViews {
        val context = baseContext.withAppLocale()
        val views = RemoteViews(context.packageName, layout)
        val openApp = WidgetActions.openApp(context)

        if (state.hasTrack) {
            views.setTextViewText(R.id.widget_title, state.title.orEmpty())
            views.setTextViewText(R.id.widget_artist, state.artist.orEmpty())
        } else {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_nothing_playing))
            views.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_tap_to_open))
        }
        when {
            art != null -> views.setImageViewBitmap(R.id.widget_art, art)
            cover -> views.setImageViewResource(R.id.widget_art, android.R.color.transparent)
            state.hasTrack -> views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
            else -> views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
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

    private fun loadArt(context: Context, path: String, sizePx: Int): Bitmap? = loadRoundedArt(
        path = path,
        widthPx = sizePx,
        heightPx = sizePx,
        radius = context.resources.getDimension(R.dimen.widget_art_radius),
    )

    private fun loadCoverArt(context: Context, path: String, widthPx: Int, heightPx: Int, scale: Float): Bitmap? =
        loadRoundedArt(
            path = path,
            widthPx = widthPx,
            heightPx = heightPx,
            radius = context.resources.getDimension(R.dimen.widget_radius) * scale,
            scrimColor = COVER_SCRIM_COLOR,
        )

    private fun loadRoundedArt(
        path: String,
        widthPx: Int,
        heightPx: Int,
        radius: Float,
        scrimColor: Int = 0,
    ): Bitmap? = try {
        decodeSampledBitmap(path, widthPx, heightPx)?.let {
            cropRounded(it, widthPx, heightPx, radius, scrimColor)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun decodeSampledBitmap(path: String, targetW: Int, targetH: Int): Bitmap? {
        if (!File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Centre-crops [source] to [width]×[height] with rounded corners and an optional [scrimColor] overlay. */
    private fun cropRounded(
        source: Bitmap,
        width: Int,
        height: Int,
        radius: Float,
        scrimColor: Int = 0,
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
                },
            )
        }
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (scrimColor != 0) {
            val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scrimColor }
            canvas.drawRoundRect(rect, radius, radius, scrimPaint)
        }
        source.recycle()
        return output
    }
}
