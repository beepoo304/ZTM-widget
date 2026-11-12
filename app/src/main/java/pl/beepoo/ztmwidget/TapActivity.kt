package pl.beepoo.ztmwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Niewidoczny ekran, przez który przechodzi każde dotknięcie widgetu (usługa w tle nie może
 * sama otworzyć aplikacji). Pierwsze dotknięcie od razu odświeża tablicę; drugie w ciągu
 * [DOUBLE_MS] otwiera ustawienia.
 */
class TapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0) ?: 0
        val now = SystemClock.elapsedRealtime()
        if (id == lastId && now - lastTap < DOUBLE_MS) {
            lastTap = 0L
            startActivity(
                Intent(this, ConfigActivity::class.java)
                    .putExtra(ConfigActivity.EXTRA_SETTINGS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } else {
            lastTap = now
            lastId = id
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, BoardService::class.java).setAction(BoardService.ACTION_TAP)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                )
            } catch (e: Exception) {
                Log.w("ZTMWidget", "tap: cannot start BoardService", e)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val DOUBLE_MS = 500L
        private var lastTap = 0L
        private var lastId = 0
    }
}
