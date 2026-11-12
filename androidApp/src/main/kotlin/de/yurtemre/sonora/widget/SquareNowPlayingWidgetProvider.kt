package de.yurtemre.sonora.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/** Square "Now playing" widget: full-bleed dimmed artwork with the controls on top. Same state as the bar widget. */
class SquareNowPlayingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.renderAsync(context, appWidgetIds, goAsync())
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        WidgetRenderer.renderAsync(context, intArrayOf(appWidgetId), goAsync())
    }
}
