package pl.beepoo.ztmwidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Wybór przystanku: wyszukiwarka po nazwie + mapka ze wszystkimi słupkami w okolicy.
 * Dotknięcie ikonki → dymek z liniami i „Obserwuj” → widget ustawiony, ekran się zamyka.
 * Otwierany przy dodawaniu widgetu, przy zmianie (przytrzymaj widget) albo z ikonki aplikacji.
 */
class ConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var stops: List<Stop> = emptyList()
    private lateinit var prefs: Prefs
    private lateinit var search: EditText
    private lateinit var results: LinearLayout
    private lateinit var resultsBox: ScrollView
    private lateinit var web: WebView

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Wyjście bez wyboru = launcher nie doda widgetu.
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111315.toInt())
            fitsSystemWindows = true
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF171A1C.toInt())
            setPadding(px(16), px(10), px(8), px(10))
        }
        top.addView(TextView(this).apply {
            text = "Wybierz przystanek"
            textSize = 19f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text = "⚙ Ustawienia"
            isAllCaps = false
            setOnClickListener { showSettings() }
        })
        root.addView(top)

        search = EditText(this).apply {
            hint = "Pobieram listę przystanków…"
            isSingleLine = true
            setTextColor(0xFFE2E5E3.toInt())
            setHintTextColor(0xFF8A9290.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF1E2225.toInt()); cornerRadius = px(10).toFloat(); setStroke(px(1), 0xFF3B4140.toInt())
            }
            setPadding(px(12), px(10), px(12), px(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = showResults(s?.toString() ?: "")
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(px(12), px(10), px(12), px(8))
        })

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsBox = ScrollView(this).apply { addView(results); visibility = View.GONE }
        root.addView(resultsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(200)))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "Android")
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = "github.com/beepoo304/ZTM-widget · dane: SDIP ZTM"
            textSize = 10f
            setTextColor(0xFF7C8582.toInt())
            gravity = Gravity.CENTER
            setPadding(0, px(4), 0, px(6))
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/beepoo304/ZTM-widget")))
            }
        })
        setContentView(root)

        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) = centerOnCurrent()
        }
        web.loadUrl("file:///android_asset/map.html")

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        lifecycleScope.launch {
            try {
                stops = withContext(Dispatchers.IO) { StopsRepo.load(applicationContext) }
                search.hint = "Szukaj przystanku, np. Rondo"
                centerOnCurrent()
            } catch (e: Exception) {
                search.hint = "Brak połączenia z ZTM – spróbuj ponownie"
            }
        }
    }

    /** Mapka startuje na przystanku, który widget już pokazuje. */
    private fun centerOnCurrent() {
        val cur = currentStop() ?: return
        val s = stops.firstOrNull { it.id == cur.id } ?: return
        web.evaluateJavascript("focusStops(${s.lat},${s.lon},17,${JSONArray(listOf(s.name))}[0])", null)
    }

    private fun currentStop(): WidgetStop? =
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) prefs.stopFor(widgetId)
        else Widgets.ids(this).firstNotNullOfOrNull { prefs.stopFor(it) }

    // ---- wyszukiwarka ----

    private fun showResults(q: String) {
        results.removeAllViews()
        val f = Sdip.fold(q.trim())
        if (f.length < 2 || stops.isEmpty()) { resultsBox.visibility = View.GONE; return }
        val groups = stops.filter { Sdip.fold(it.name).contains(f) }
            .groupBy { it.name }
            .entries
            .sortedWith(compareBy({ !Sdip.fold(it.key).startsWith(f) }, { it.key }))
            .take(40)
        resultsBox.visibility = View.VISIBLE
        if (groups.isEmpty()) {
            results.addView(row("Nic nie znaleziono", "", null))
            return
        }
        for ((name, posts) in groups) {
            val types = posts.map { it.type }.toSet()
            val kind = listOfNotNull(
                if ("0" in types) "tramwaj" else null,
                if ("3" in types || "" in types) "autobus" else null,
                if ("11" in types) "trolejbus" else null,
            ).joinToString(" / ")
            val sub = "${posts.first().municipality} · ${posts.size} ${slupki(posts.size)} · $kind"
            results.addView(row(name, sub) { pickName(name, posts) })
        }
    }

    private fun slupki(n: Int) = if (n == 1) "słupek" else if (n % 10 in 2..4 && n % 100 !in 12..14) "słupki" else "słupków"

    private fun row(title: String, sub: String, onClick: (() -> Unit)?): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(9), px(16), px(9))
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(0xFFE2E5E3.toInt()) })
            if (sub.isNotEmpty()) addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(0xFF9DA4A0.toInt()) })
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

    /** Wybrana nazwa: mapka pokazuje jej słupki (zielona obwódka) i wszystko dookoła. */
    private fun pickName(name: String, posts: List<Stop>) {
        resultsBox.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        val s = posts.minOf { it.lat }; val n = posts.maxOf { it.lat }
        val w = posts.minOf { it.lon }; val e = posts.maxOf { it.lon }
        web.evaluateJavascript("fitStops($s,$w,$n,$e,${JSONArray(listOf(name))}[0])", null)
    }

    // ---- most JS ↔ Kotlin ----

    inner class Bridge {
        @JavascriptInterface
        fun stopsIn(s: Double, w: Double, n: Double, e: Double): String {
            val arr = JSONArray()
            stops.asSequence().filter { it.lat in s..n && it.lon in w..e }.take(400).forEach {
                arr.put(JSONArray().put(it.id).put(it.name).put(it.code).put(it.lat).put(it.lon).put(it.type))
            }
            return arr.toString()
        }

        /** Linie ze słupka (z bieżących odjazdów) – wpisywane do dymka po pobraniu. */
        @JavascriptInterface
        fun lines(id: String) {
            lifecycleScope.launch {
                val txt = try {
                    val b = withContext(Dispatchers.IO) { Sdip.fetchBoard(id) }
                    val ls = b.departures.map { it.line }.distinct()
                        .sortedWith(compareBy({ it.filter(Char::isDigit).toIntOrNull() ?: 9999 }, { it }))
                    if (ls.isEmpty()) "Linie: brak odjazdów" else "Linie: " + ls.joinToString(" · ")
                } catch (e: Exception) {
                    "Linie: brak połączenia"
                }
                web.evaluateJavascript("setLines(${JSONArray(listOf(id))}[0],${JSONArray(listOf(txt))}[0])", null)
            }
        }

        @JavascriptInterface
        fun observe(id: String) {
            runOnUiThread { observeStop(id) }
        }
    }

    private fun observeStop(id: String) {
        val s = stops.firstOrNull { it.id == id } ?: return
        val ws = WidgetStop(s.id, s.name, s.code)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            assign(widgetId, ws)
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
            return
        }
        // Otwarte z ikonki aplikacji: wybierz widget albo dodaj nowy.
        val ids = Widgets.ids(this)
        val labels = ids.map { prefs.stopFor(it)?.title ?: "(pusty widget)" } + "➕ Nowy widget na ekranie głównym"
        AlertDialog.Builder(this)
            .setTitle("Pokaż ${ws.title} na:")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < ids.size) {
                    assign(ids[i], ws)
                    finish()
                } else pinNew(ws)
            }
            .show()
    }

    private fun assign(id: Int, ws: WidgetStop) {
        prefs.setStop(id, ws)
        Widgets.states.getOrPut(ws.id) { StopState() }.lastAttempt = 0L
        Widgets.render(this, AppWidgetManager.getInstance(this), id)
        BoardService.start(this)
    }

    private fun pinNew(ws: WidgetStop) {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr != null && mgr.isRequestPinAppWidgetSupported) {
            prefs.pendingStop = ws
            mgr.requestPinAppWidget(ComponentName(this, BoardWidget::class.java), null, null)
        } else {
            Toast.makeText(this, "Przytrzymaj ekran główny → Widżety → Przystanek ZTM", Toast.LENGTH_LONG).show()
        }
    }

    // ---- ustawienia ----

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(4))
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, px(12), 0, px(2)) }

        box.addView(label("Kolor diod"))
        val colors = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        LedRenderer.COLOR_NAMES.forEachIndexed { i, n ->
            colors.addView(RadioButton(this).apply {
                id = 100 + i
                text = n
                setTextColor(LedRenderer.colorOf(i))
                isChecked = prefs.color == i
            })
        }
        colors.setOnCheckedChangeListener { _, checked -> prefs.color = checked - 100; Widgets.renderAll(this) }
        box.addView(colors)

        val brLabel = label("Jasność: ${prefs.brightness}%")
        box.addView(brLabel)
        box.addView(SeekBar(this).apply {
            max = 80
            progress = prefs.brightness - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.brightness = p + 20
                    brLabel.text = "Jasność: ${p + 20}%"
                    Widgets.renderAll(this@ConfigActivity)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val mon = Switch(this).apply {
            text = "Monitorowanie (pobieranie odjazdów)"
            isChecked = prefs.monitoring
            setPadding(0, px(14), 0, 0)
            setOnCheckedChangeListener { _, on ->
                prefs.monitoring = on
                if (on) { prefs.manualUntil = 0; BoardService.start(this@ConfigActivity) }
                else BoardService.stop(this@ConfigActivity)
                Widgets.renderAll(this@ConfigActivity)
            }
        }
        box.addView(mon)
        box.addView(CheckBox(this).apply {
            text = "Tylko pon–pt (w weekend wstrzymane)"
            isChecked = prefs.weekdaysOnly
            setOnCheckedChangeListener { _, on -> prefs.weekdaysOnly = on; Widgets.renderAll(this@ConfigActivity) }
        })

        box.addView(label("Godziny działania"))
        val hours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hList = (0..24).map { "%02d:00".format(it) }
        fun spinner(sel: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
            adapter = ArrayAdapter(this@ConfigActivity, android.R.layout.simple_spinner_dropdown_item, hList)
            setSelection(sel)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onPick(pos); Widgets.renderAll(this@ConfigActivity)
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        hours.addView(TextView(this).apply { text = "od " })
        hours.addView(spinner(prefs.fromHour) { prefs.fromHour = it.coerceAtMost(23) })
        hours.addView(TextView(this).apply { text = "  do " })
        hours.addView(spinner(prefs.toHour) { prefs.toHour = it.coerceAtLeast(1) })
        box.addView(hours)
        box.addView(TextView(this).apply {
            text = "Poza tymi godzinami widget nic nie pobiera. Dotknięcie wstrzymanego widgetu włącza go na 5 minut."
            textSize = 12f
            setPadding(0, px(8), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Ustawienia")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
