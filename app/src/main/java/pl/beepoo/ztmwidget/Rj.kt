package pl.beepoo.ztmwidget

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Rozkłady jazdy ZTM (rj.metropoliaztm.pl – ta sama mapa co rj.transportgzm.pl/v2/mapa).
 * Stąd bierzemy WSZYSTKIE linie słupka; tablica SDIP zna tylko najbliższe odjazdy.
 * Słupki mają tu inne id niż w SDIP – dopasowujemy po numerze słupka oraz miejscu lub nazwie
 * („nż” vs „[nż]”), najbliższy w promieniu 500 m.
 */
object Rj {
    private const val BASE = "https://rj.metropoliaztm.pl"
    private class RjStop(val id: String, val name: String, val code: String, val lat: Double, val lon: Double)

    @Volatile private var stops: List<RjStop>? = null
    private val linesCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val STOP = Regex("(.*?)\\s*\\nStanowisko:\\s*(\\S+)")
    private val NZ = Regex("\\s*\\[?nż]?$")
    private val LINK = Regex(">([^<>]+)</a>")

    /** Linie słupka SDIP albo null, gdy nie da się go dopasować / brak sieci. */
    fun lines(s: Stop): List<String>? {
        linesCache[s.id]?.let { return it }
        val rj = match(s) ?: return null
        val ls = LINK.findAll(get("$BASE/api/v1/stops/${rj.id}")).map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }.distinct().toList()
        if (ls.isNotEmpty()) linesCache[s.id] = ls
        return ls.ifEmpty { null }
    }

    private fun match(s: Stop): RjStop? {
        val all = stops ?: synchronized(this) { stops ?: load().also { stops = it } }
        val n = norm(s.name)
        val c = code(s.code)
        // Ten sam numer słupka i (to samo miejsce albo ta sama nazwa) – sprawdzone na wszystkich 7149 słupkach.
        return all.filter { it.code == c && (dist(it, s) < 3.0 || norm(it.name) == n) }
            .minByOrNull { dist(it, s) }?.takeIf { dist(it, s) < 500.0 }
    }

    private fun load(): List<RjStop> {
        val arr = JSONArray(get("$BASE/api/v1/stops/data/"))
        val out = ArrayList<RjStop>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            val m = STOP.matchEntire(a.getString(2)) ?: continue
            out += RjStop(a.get(3).toString(), m.groupValues[1], code(m.groupValues[2]), a.getDouble(0), a.getDouble(1))
        }
        return out
    }

    private fun norm(n: String) = n.trim().lowercase().replace(NZ, "").trim()
    private fun code(c: String) = c.trim().lowercase().trimEnd { it.isLetter() }
    private fun dist(r: RjStop, s: Stop) =
        hypot((r.lat - s.lat) * 111_320.0, (r.lon - s.lon) * 111_320.0 * cos(Math.toRadians(s.lat)))

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "ZTMWidget/1.0 (Android)")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
