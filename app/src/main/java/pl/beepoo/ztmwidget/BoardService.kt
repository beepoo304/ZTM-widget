package pl.beepoo.ztmwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serce widgetu: co sekundę przerysowuje tablicę (zegar, miganie ">>>"), a co [FETCH_MS]
 * pobiera odjazdy – tylko przy włączonym ekranie i w godzinach z harmonogramu.
 * Poza harmonogramem tablica pokazuje „wstrzymane” i usługa tylko czeka (bez sieci).
 * Usługa pierwszoplanowa to jedyny sposób na pewne odświeżanie co sekundę.
 */
class BoardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var lastActive: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        if (intent?.action == ACTION_TAP) onTap(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
        val prefs = Prefs(this)
        if (Widgets.ids(this).isEmpty() || (!prefs.monitoring && System.currentTimeMillis() >= prefs.manualUntil)) {
            Widgets.renderAll(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    /** Dotknięcie widgetu: natychmiastowe pobranie, a poza harmonogramem – obudzenie na 5 minut. */
    private fun onTap(widgetId: Int) {
        val prefs = Prefs(this)
        val now = System.currentTimeMillis()
        if (!prefs.inSchedule()) prefs.manualUntil = now + MANUAL_MS
        prefs.stopFor(widgetId)?.let { Widgets.states.getOrPut(it.id) { StopState() }.lastAttempt = 0L }
    }

    private suspend fun runLoop() {
        val power = getSystemService(PowerManager::class.java)
        while (scope.isActive) {
            val prefs = Prefs(this)
            val now = System.currentTimeMillis()
            val ids = Widgets.ids(this)
            if (ids.isEmpty() || (!prefs.monitoring && now >= prefs.manualUntil)) {
                Widgets.renderAll(this)
                stopSelf()
                return
            }
            val active = prefs.active(now)
            if (active != lastActive) {
                lastActive = active
                updateNotification(active)
                Widgets.renderAll(this)
            }
            if (!active) {
                delay(15_000L)
                continue
            }
            if (power?.isInteractive != false) {
                fetchDue(prefs, ids, now)
                Widgets.renderAll(this)
                // Następne tyknięcie tuż po granicy pełnej sekundy: zegar zmienia się równo.
                delay(1_000L - (System.currentTimeMillis() % 1_000L) + 15L)
            } else {
                delay(2_000L) // ekran wyłączony: bez sieci i bez rysowania
            }
        }
    }

    private fun fetchDue(prefs: Prefs, ids: IntArray, now: Long) {
        val stopIds = ids.mapNotNull { prefs.stopFor(it)?.id }.toSet()
        for (id in stopIds) {
            val st = Widgets.states.getOrPut(id) { StopState() }
            if (st.loading || now - st.lastAttempt < FETCH_MS) continue
            st.loading = true
            st.lastAttempt = now
            scope.launch(Dispatchers.IO) {
                try {
                    st.board = Sdip.fetchBoard(id)
                    st.failed = false
                } catch (e: Exception) {
                    Log.w(TAG, "fetch $id failed", e)
                    st.failed = true
                    // Po błędzie spróbuj ponownie szybciej niż za 20 s.
                    st.lastAttempt = System.currentTimeMillis() - FETCH_MS + RETRY_MS
                } finally {
                    st.loading = false
                }
            }
        }
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, Prefs(this).active()), type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed", e)
        false
    }

    private fun updateNotification(active: Boolean) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(this, active))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ZTMWidget"
        const val ACTION_TAP = "pl.beepoo.ztmwidget.TAP"
        private const val CHANNEL = "board"
        private const val NOTIFICATION_ID = 3001
        private const val FETCH_MS = 20_000L
        private const val RETRY_MS = 5_000L
        private const val MANUAL_MS = 5 * 60_000L

        @Volatile
        var isRunning = false
            private set

        private fun notification(ctx: Context, active: Boolean): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tablica przystankowa", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, ConfigActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (active) "Tablica przystankowa na żywo" else "Tablica wstrzymana")
                .setContentText(if (active) "Odjazdy co 20 s, zegar co 1 s (przy włączonym ekranie)" else "Poza godzinami z harmonogramu – bez pobierania danych")
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .build()
        }

        /** Uruchamia usługę (np. z aktywności, po starcie telefonu). Z tła Android może odmówić. */
        fun start(ctx: Context) {
            val prefs = Prefs(ctx)
            if (!prefs.monitoring && System.currentTimeMillis() >= prefs.manualUntil) return
            if (Widgets.ids(ctx).isEmpty()) return
            try {
                ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx, BoardService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start BoardService", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(Intent(ctx, BoardService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
