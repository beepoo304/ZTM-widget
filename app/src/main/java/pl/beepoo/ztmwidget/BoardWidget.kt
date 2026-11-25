package pl.beepoo.ztmwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

class BoardWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val prefs = Prefs(ctx)
        prefs.pendingStop?.let { pending ->
            ids.firstOrNull { prefs.stopFor(it) == null }?.let { prefs.setStop(it, pending); prefs.pendingStop = null }
        }
        Widgets.renderAll(ctx)
        BoardService.start(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        Widgets.render(ctx, mgr, id)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val p = Prefs(ctx)
        ids.forEach(p::remove)
    }

    override fun onDisabled(ctx: Context) {
        BoardService.stop(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Widgets.renderAll(ctx)
        BoardService.start(ctx)
    }
}

/** Stan pobierania jednego słupka (wspólny dla kilku widgetów z tym samym słupkiem). */
class StopState {
    @Volatile var board: Board? = null
    @Volatile var lastAttempt = 0L
    @Volatile var failed = false
    @Volatile var loading = false
    /** Po dotknięciu, do końca pobierania: w nagłówku „ODŚWIEŻANIE…”. */
    @Volatile var refreshing = false
}

object Widgets {
    private const val TAG = "ZTMWidget"
    const val STALE_MS = 120_000L
    val states = ConcurrentHashMap<String, StopState>()

    fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, BoardWidget::class.java))

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        ids(ctx).forEach { render(ctx, mgr, it) }
    }

    fun screenFor(ctx: Context, widgetId: Int, now: Long): Screen {
        val prefs = Prefs(ctx)
        val stop = prefs.stopFor(widgetId) ?: return Screen.NoStop
        if (!prefs.active(now)) return Screen.Paused(stop.title)
        val st = states[stop.id]
        val board = st?.board
        return when {
            board != null -> Screen.Departures(
                if (st?.refreshing == true) "ODŚWIEŻANIE…" else stop.title, board, now - board.fetchedAt > STALE_MS
            )
            st != null && st.failed && !st.loading -> Screen.Message(stop.title, "BRAK POŁĄCZENIA")
            else -> Screen.Message(stop.title, "ŁADOWANIE…")
        }
    }

    fun render(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
        try {
            val prefs = Prefs(ctx)
            val (w, h) = sizePx(ctx, mgr, widgetId)
            val time = LocalTime.now()
            val bmp = LedRenderer.render(
                ctx, w, h, screenFor(ctx, widgetId, System.currentTimeMillis()),
                time, time.second % 2 == 0, prefs.color, prefs.brightness, prefs.style
            )
            val views = RemoteViews(ctx.packageName, R.layout.widget_board)
            views.setImageViewBitmap(R.id.board, bmp)
            views.setOnClickPendingIntent(R.id.root, clickIntent(ctx, widgetId, prefs.stopFor(widgetId) != null))
            mgr.updateAppWidget(widgetId, views)
        } catch (e: Exception) {
            Log.w(TAG, "render $widgetId failed", e)
        }
    }

    /**
     * Dotknięcie: ustawiony widget idzie przez [TapActivity] (pojedyncze = odśwież / obudź na 5 min,
     * podwójne = ustawienia); pusty = wybór przystanku.
     */
    private fun clickIntent(ctx: Context, widgetId: Int, configured: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (configured) {
            val i = Intent(ctx, TapActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            PendingIntent.getActivity(ctx, widgetId, i, flags)
        } else {
            val i = Intent(ctx, ConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(ctx, widgetId, i, flags)
        }
    }

    /** Rozmiar widgetu w pikselach (launcher podaje dp: szerokość min/max zależnie od orientacji). */
    private fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var wDp = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        var hDp = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (wDp <= 0) wDp = 300
        if (hDp <= 0) hDp = 130
        val d = ctx.resources.displayMetrics.density
        return (wDp * d).toInt() to (hDp * d).toInt()
    }
}
