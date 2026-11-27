package com.nexos.play.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.nexos.play.R
import com.nexos.play.playback.PlaybackService
import com.nexos.play.playback.PlayerHolder

/** Mini-controles en el escritorio: titulo + play/pausa. */
class NexosWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        mgr: AppWidgetManager,
        ids: IntArray
    ) {
        updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, NexosWidget::class.java)
            )
            if (ids.isEmpty()) return
            val playing = PlayerHolder.player?.playWhenReady == true
            val title = PlayerHolder.playable?.title?.ifBlank { null }
                ?: context.getString(R.string.app_name)
            val toggle = Intent(context, PlaybackService::class.java).setAction(
                if (playing) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY
            )
            val togglePi = PendingIntent.getService(
                context, 21, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val open = PlayerActivity.intent(
                context, PlayerHolder.pageUrl, PlayerHolder.playable?.title.orEmpty()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val openPi = PendingIntent.getActivity(
                context, 22, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(R.id.widget_title, title)
            views.setImageViewResource(
                R.id.widget_toggle,
                if (playing) R.drawable.ic_pause else R.drawable.ic_play_triangle
            )
            views.setOnClickPendingIntent(R.id.widget_toggle, togglePi)
            views.setOnClickPendingIntent(R.id.widget_title, openPi)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }
}
