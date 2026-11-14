package pl.beepoo.ztmwidget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/** Słupek przystankowy z listy SDIP (`action=sbd`). [code] to numer słupka, np. "01t". */
data class Stop(
    val id: String,
    val name: String,
    val code: String,
    val lat: Double,
    val lon: Double,
    val type: String, // "0" tramwaj, "3" autobus, "11" trolejbus, "" nieznany
    val municipality: String,
) {
    /** Numer słupka bez literki typu: "01t" -> "01". */
    val shortCode: String get() = code.trimEnd { it.isLetter() }.ifEmpty { code }
    val isTram: Boolean get() = type == "0"
}

/** Jeden odjazd z tablicy. [atStop] = pojazd na przystanku (na tablicy migające ">>>"). */
data class Departure(val line: String, val destination: String, val time: String, val atStop: Boolean, val realtime: Boolean)

/** Tablica jednego słupka w chwili pobrania. */
data class Board(val stopId: String, val name: String, val code: String, val departures: List<Departure>, val fetchedAt: Long)

/**
 * Nieoficjalne API systemu SDIP ZTM (to samo, z którego korzysta strona sdip.transportgzm.pl
 * i tablice na przystankach).
 */
object Sdip {
    private const val BASE = "https://sdip.transportgzm.pl/vm/main?command=planner"

    fun fetchStopsJson(): String = get("$BASE&action=sbd")

    fun fetchBoard(stopId: String): Board = parseBoard(get("$BASE&action=sd&id=$stopId"), System.currentTimeMillis())

    fun parseStops(json: String): List<Stop> {
        val arr = JSONObject(json).getJSONArray("stops")
        val out = ArrayList<Stop>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            out += Stop(
                id = o.optString("id"),
                name = o.optString("name").trim(),
                code = o.optString("alt_id"),
                lat = lat,
                lon = lon,
                type = o.optString("linetype"),
                municipality = o.optString("municipality"),
            )
        }
        return out
    }

    private val ROW = Regex(
        "class='departure status-(\\d+)'.*?class='line'>([^<]*)<.*?class='destination'[^>]*>([^<]*)<.*?class='time'>([^<]*)<",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun input(html: String, id: String): String =
        Regex("id='$id'[^>]*value='([^']*)'").find(html)?.groupValues?.get(1)?.let(::unescape) ?: ""

    /**
     * Parsuje fragment HTML z `action=sd`. Bierzemy tylko zakładkę „Następne odjazdy”
     * (`stop-departures-…`); druga zakładka powtarza te same kursy pogrupowane wg linii.
     * Status 2 = pojazd na przystanku, 0/1 = czas z GPS ("X min"), 8 = z rozkładu ("HH:MM").
     */
    fun parseBoard(html: String, now: Long): Board {
        val start = html.indexOf("id='stop-departures-").let { if (it < 0) 0 else it }
        val end = html.indexOf("id='line-departures-", start).let { if (it < 0) html.length else it }
        val block = html.substring(start, end)
        val deps = ROW.findAll(block).map { m ->
            val status = m.groupValues[1].toIntOrNull() ?: 0
            val time = unescape(m.groupValues[4]).trim()
            Departure(
                line = unescape(m.groupValues[2]).trim(),
                destination = unescape(m.groupValues[3]).trim(),
                time = time,
                atStop = status == 2 || time == "0 min" || time.startsWith("<"),
                realtime = status != 8,
            )
        }.toList()
        return Board(input(html, "stop-id"), input(html, "stop-name"), input(html, "stop-code"), deps, now)
    }

    private fun unescape(s: String): String = s
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

    /** Do wyszukiwania: małe litery bez polskich znaków ("Chorzów" -> "chorzow"). */
    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase().replace('ł', 'l'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "ZTMWidget/1.0 (Android)")
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        c.setRequestProperty("Accept-Encoding", "gzip")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val stream = if ("gzip".equals(c.contentEncoding, ignoreCase = true)) {
                java.util.zip.GZIPInputStream(c.inputStream)
            } else {
                c.inputStream
            }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
