package pl.beepoo.ztmwidget

import android.app.Application
import java.io.File

/**
 * Zapisuje treść awarii do pliku, żeby po ponownym otwarciu aplikacja mogła ją pokazać
 * (do skopiowania i wysłania) – bez tego „przestaje działać” nic nie mówi.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile(this).writeText(
                    "ZTM Widget ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()}, Android ${android.os.Build.VERSION.RELEASE}, " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nwątek: ${t.name}\n\n" +
                        android.util.Log.getStackTraceString(e)
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashFile(ctx: android.content.Context) = File(ctx.filesDir, "crash.txt")
    }
}
