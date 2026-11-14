package pl.beepoo.ztmwidget

import android.content.Context
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime

/** Ustawienia aplikacji i przypisanie słupka do każdego widgetu. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("ztm", Context.MODE_PRIVATE)

    /** 0 bursztynowy, 1 czerwony, 2 zielony, 3 różowy, 4 niebieski. */
    var color: Int
        get() = sp.getInt("color", 0)
        set(v) = sp.edit().putInt("color", v).apply()

    /** Styl tablicy: 0 = REAL (LED matrix), 1 = MODERN (czysty wektorowy tekst na czarnym tle). */
    var style: Int
        get() = sp.getInt("style", STYLE_REAL)
        set(v) = sp.edit().putInt("style", v).apply()

    companion object {
        const val STYLE_REAL = 0
        const val STYLE_MODERN = 1
    }

    /** Jasność diod 20..100 %. */
    var brightness: Int
        get() = sp.getInt("brightness", 100)
        set(v) = sp.edit().putInt("brightness", v.coerceIn(20, 100)).apply()

    /** Główny wyłącznik: false = widget nie pobiera danych i nie tyka. */
    var monitoring: Boolean
        get() = sp.getBoolean("monitoring", true)
        set(v) = sp.edit().putBoolean("monitoring", v).apply()

    /** Tylko w dni robocze (pon–pt). */
    var weekdaysOnly: Boolean
        get() = sp.getBoolean("weekdays", false)
        set(v) = sp.edit().putBoolean("weekdays", v).apply()

    /** Godziny działania [fromHour, toHour); 0..24 = cała doba. */
    var fromHour: Int
        get() = sp.getInt("from", 0)
        set(v) = sp.edit().putInt("from", v).apply()
    var toHour: Int
        get() = sp.getInt("to", 24)
        set(v) = sp.edit().putInt("to", v).apply()

    /** Ręczne „obudzenie” dotknięciem widgetu poza harmonogramem (do tej chwili). */
    var manualUntil: Long
        get() = sp.getLong("manualUntil", 0)
        set(v) = sp.edit().putLong("manualUntil", v).apply()

    fun inSchedule(t: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!monitoring) return false
        if (weekdaysOnly && (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY)) return false
        val h = t.hour
        return if (fromHour <= toHour) h in fromHour until toHour else h >= fromHour || h < toHour
    }

    /** Czy widget ma teraz żyć (harmonogram albo ręczne obudzenie). */
    fun active(now: Long = System.currentTimeMillis()): Boolean = inSchedule() || now < manualUntil

    fun stopFor(widgetId: Int): WidgetStop? {
        val id = sp.getString("w$widgetId.id", null) ?: return null
        return WidgetStop(id, sp.getString("w$widgetId.name", "") ?: "", sp.getString("w$widgetId.code", "") ?: "")
    }

    fun setStop(widgetId: Int, s: WidgetStop) {
        sp.edit().putString("w$widgetId.id", s.id).putString("w$widgetId.name", s.name).putString("w$widgetId.code", s.code).apply()
    }

    /** Przystanek dla widgetu przypinanego z aplikacji (przypisywany w pierwszym onUpdate). */
    var pendingStop: WidgetStop?
        get() = sp.getString("pending.id", null)?.let {
            WidgetStop(it, sp.getString("pending.name", "") ?: "", sp.getString("pending.code", "") ?: "")
        }
        set(v) {
            if (v == null) sp.edit().remove("pending.id").apply()
            else sp.edit().putString("pending.id", v.id).putString("pending.name", v.name).putString("pending.code", v.code).apply()
        }

    fun remove(widgetId: Int) {
        sp.edit().remove("w$widgetId.id").remove("w$widgetId.name").remove("w$widgetId.code").apply()
    }
}

data class WidgetStop(val id: String, val name: String, val code: String) {
    val title: String get() = "${name.uppercase()} ${code.trimEnd { it.isLetter() }.ifEmpty { code }}".trim()
}

/** Lista słupków: pobierana raz i trzymana w pliku, odświeżana co tydzień. */
object StopsRepo {
    private const val MAX_AGE = 7 * 24 * 3600_000L
    @Volatile private var cache: List<Stop>? = null

    fun load(ctx: Context): List<Stop> {
        cache?.let { return it }
        val f = File(ctx.filesDir, "stops.json")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE
        val json = if (fresh) f.readText() else try {
            Sdip.fetchStopsJson().also { f.writeText(it) }
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw e
        }
        return Sdip.parseStops(json).also { cache = it }
    }
}
