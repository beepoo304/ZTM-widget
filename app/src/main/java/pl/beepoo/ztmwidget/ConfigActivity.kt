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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
            text = "github.com/beepoo304/ZTM-widget · v1.0.5 (25.11.2026) · dane: SDIP ZTM"
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
        if (intent?.getBooleanExtra(EXTRA_SETTINGS, false) == true) showSettings()

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
        else Widgets.ids(this).asList().firstNotNullOfOrNull { prefs.stopFor(it) }

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
                    // Pełna lista z rozkładów; gdy słupka tam nie ma – linie z najbliższych odjazdów.
                    val all = stops.firstOrNull { it.id == id }
                        ?.let { s -> withContext(Dispatchers.IO) { runCatching { Rj.lines(s) }.getOrNull() } }
                    val ls = (all ?: withContext(Dispatchers.IO) { Sdip.fetchBoard(id) }.departures.map { it.line }.distinct())
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
        val ids = Widgets.ids(this).filter { it !in prefs.hidden }.toIntArray()
        val labels = ids.map { prefs.stopFor(it)?.title ?: "(pusty widget)" } + "➕ Nowy widget na ekranie głównym" +
            (if (ids.isNotEmpty()) listOf("🗑 Usuń widget…") else emptyList())
        AlertDialog.Builder(this)
            .setTitle("Pokaż ${ws.title} na:")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < ids.size -> { assign(ids[i], ws); finish() }
                    i == ids.size -> pinNew(ws)
                    else -> removeWidget(ids)
                }
            }
            .show()
    }

    /**
     * Android nie pozwala aplikacji zdjąć widgetu z ekranu głównego – robi to tylko launcher.
     * Odpinamy więc przystanek (widget przestaje pobierać dane) i podpowiadamy, jak go zdjąć.
     */
    private fun removeWidget(ids: IntArray) {
        val labels = ids.map { prefs.stopFor(it)?.title ?: "(pusty widget)" }
        AlertDialog.Builder(this)
            .setTitle("Który widget usunąć?")
            .setItems(labels.toTypedArray()) { _, i ->
                prefs.remove(ids[i])
                prefs.hidden = prefs.hidden + ids[i]
                Widgets.render(this, AppWidgetManager.getInstance(this), ids[i])
                AlertDialog.Builder(this)
                    .setTitle("Widget odpięty")
                    .setMessage("„${labels[i]}” zniknął z listy i nie pobiera już odjazdów.\n\nŻeby zdjąć go z ekranu głównego: przytrzymaj widget i wybierz „Usuń” (albo przeciągnij go na „Usuń”).")
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun assign(id: Int, ws: WidgetStop) {
        prefs.setStop(id, ws)
        prefs.hidden = prefs.hidden - id
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

    /**
     * Ustawienia: żywy podgląd tablicy, kolor diod (3 kafelki), jasność i harmonogram.
     * Ciemne kolory ustawione wprost – nie zależą od motywu telefonu.
     */
    private fun showSettings() {
        val fg = 0xFFE2E5E3.toInt()
        val dim = 0xFF8A9290.toInt()
        val line = 0xFF3B4140.toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(4))
        }
        fun section(t: String) = TextView(this).apply {
            text = t; textSize = 12f; letterSpacing = 0.1f; setTextColor(dim); paint.isFakeBoldText = true
            setPadding(0, px(16), 0, px(8))
        }
        val wrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        box.addView(TextView(this).apply { text = "Ustawienia"; textSize = 20f; setTextColor(Color.WHITE); paint.isFakeBoldText = true })

        // Podgląd: ta sama grafika co na widgecie, odświeżana co sekundę.
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = px(8).toFloat() }
            clipToOutline = true
        }
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })
        val demoTitle = Widgets.ids(this).asList().firstNotNullOfOrNull { prefs.stopFor(it) }?.title ?: "KATOWICE RONDO 01"
        val demo = Board(
            "", "", "", listOf(
                Departure("T7", "Bytom Szkoła Medyczna", "2 min", atStop = false, realtime = true),
                Departure("T6", "Chorzów Stadion Śląski Pętla Zach.", "3 min", atStop = false, realtime = true),
                Departure("T13", "Siemianowice Śląskie", ">>>", atStop = true, realtime = true),
                Departure("910", "Katowice Dworzec", "6 min", atStop = false, realtime = true),
                Departure("T6", "Stroszek Zajezdnia", "11 min", atStop = false, realtime = true),
                Departure("T16", "Wełnowiec Plac Alfreda", "14:52", atStop = false, realtime = false),
            ), 0L
        )
        fun drawPreview() {
            val w = maxOf(preview.width, px(280))
            val t = java.time.LocalTime.now()
            preview.setImageBitmap(
                LedRenderer.render(this, w, w * 11 / 25, Screen.Departures(demoTitle, demo, false), t, t.second % 2 == 0, prefs.color, prefs.brightness, prefs.style,
                    android.os.SystemClock.uptimeMillis())
            )
        }
        val ticker = object : Runnable {
            override fun run() {
                drawPreview()
                preview.postDelayed(this, if (LedRenderer.scrolling) (1_000f / LedRenderer.SCROLL_DOTS_PER_S).toLong() else 1_000L)
            }
        }

        // Styl tablicy: REAL (LED) vs MODERN (TXT)
        box.addView(section("STYL TABLICY"))
        val styleTiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val styleViews = ArrayList<LinearLayout>()
        val styleNames = arrayOf("REAL (LED)", "MODERN (TXT)")
        val styleDescs = arrayOf("Diody SDIP", "Czysty tekst")

        fun markStyleTiles() = styleViews.forEachIndexed { i, v ->
            val sel = i == prefs.style
            val c = LedRenderer.colorOf(prefs.color)
            v.background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = px(10).toFloat()
                if (sel) setStroke(px(2), c) else setStroke(px(1), line)
            }
            val titleTv = v.getChildAt(0) as TextView
            titleTv.setTextColor(if (sel) c else fg)
        }

        styleNames.forEachIndexed { i, n ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(px(4), px(10), px(4), px(8))
                addView(TextView(this@ConfigActivity).apply {
                    text = n; textSize = 14f; gravity = Gravity.CENTER
                    paint.isFakeBoldText = true
                })
                addView(TextView(this@ConfigActivity).apply {
                    text = styleDescs[i]; textSize = 11f; gravity = Gravity.CENTER; setTextColor(dim)
                })
                setOnClickListener {
                    prefs.style = i
                    markStyleTiles()
                    drawPreview()
                    Widgets.renderAll(this@ConfigActivity)
                }
            }
            styleViews += tile
            styleTiles.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = px(8)
            })
        }
        markStyleTiles()
        box.addView(styleTiles, wrap)

        // Kolor diod / akcentu: 5 równych kafelków z próbką, wybrany obwiedziony.
        box.addView(section("KOLOR DIOD / AKCENTU"))
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tileViews = ArrayList<LinearLayout>()
        var dialog: AlertDialog? = null
        fun markTiles() {
            tileViews.forEachIndexed { i, v ->
                v.background = GradientDrawable().apply {
                    setColor(Color.BLACK); cornerRadius = px(10).toFloat()
                    if (i == prefs.color) setStroke(px(2), LedRenderer.colorOf(i)) else setStroke(px(1), line)
                }
            }
            markStyleTiles()
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(LedRenderer.colorOf(prefs.color))
        }
        LedRenderer.COLOR_NAMES.forEachIndexed { i, n ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(px(2), px(8), px(2), px(8))
                addView(TextView(this@ConfigActivity).apply {
                    text = "12:34"; textSize = 19f; gravity = Gravity.CENTER
                    typeface = resources.getFont(R.font.micro5)
                    setTextColor(LedRenderer.colorOf(i))
                    setShadowLayer(px(4).toFloat(), 0f, 0f, LedRenderer.colorOf(i))
                })
                addView(TextView(this@ConfigActivity).apply {
                    text = n; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(fg)
                    isSingleLine = true
                })
                setOnClickListener { prefs.color = i; markTiles(); drawPreview(); Widgets.renderAll(this@ConfigActivity) }
            }
            tileViews += tile
            tiles.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = px(5)
            })
        }
        markTiles()
        box.addView(tiles, wrap)

        // Jasność.
        val brHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brHead.addView(section("JASNOŚĆ"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val brValue = TextView(this).apply { text = "${prefs.brightness} %"; textSize = 14f; setTextColor(fg); setPadding(0, px(16), 0, px(8)) }
        brHead.addView(brValue)
        box.addView(brHead, wrap)
        box.addView(SeekBar(this).apply {
            max = 80
            progress = prefs.brightness - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.brightness = p + 20
                    brValue.text = "${p + 20} %"
                    drawPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { Widgets.renderAll(this@ConfigActivity) }
            })
        }, wrap)

        box.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { topMargin = px(14) })

        // Harmonogram.
        box.addView(section("HARMONOGRAM"))
        fun toggle(t: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
            text = t; textSize = 15f; setTextColor(fg); isChecked = on
            setPadding(0, px(6), 0, px(6))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val schedule = ArrayList<View>()
        fun enableSchedule(on: Boolean) = schedule.forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.4f }
        box.addView(toggle("Monitorowanie", prefs.monitoring) { on ->
            prefs.monitoring = on
            if (on) { prefs.manualUntil = 0; BoardService.start(this) } else BoardService.stop(this)
            enableSchedule(on)
            Widgets.renderAll(this)
        }, wrap)
        val weekdays = toggle("Tylko pon–pt", prefs.weekdaysOnly) { on -> prefs.weekdaysOnly = on; Widgets.renderAll(this) }
        box.addView(weekdays, wrap)
        schedule += weekdays

        val hours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), 0, 0) }
        val hList = (0..24).map { "%02d:00".format(it) }
        fun spinner(sel: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@ConfigActivity, android.R.layout.simple_spinner_item, hList) {
                override fun getView(pos: Int, v: View?, parent: ViewGroup) =
                    super.getView(pos, v, parent).also { (it as TextView).setTextColor(fg); it.textSize = 15f }
            }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(sel)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onPick(pos); Widgets.renderAll(this@ConfigActivity)
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        hours.addView(TextView(this).apply { text = "Godziny"; textSize = 15f; setTextColor(fg) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hours.addView(TextView(this).apply { text = "od"; setTextColor(dim) })
        hours.addView(spinner(prefs.fromHour) { prefs.fromHour = it.coerceAtMost(23) })
        hours.addView(TextView(this).apply { text = "do"; setTextColor(dim) })
        hours.addView(spinner(prefs.toHour) { prefs.toHour = it.coerceAtLeast(1) })
        box.addView(hours, wrap)
        schedule += hours
        val hint = TextView(this).apply {
            text = "Poza godzinami widget śpi i nic nie pobiera.\nDotknięcie śpiącego widgetu budzi go na 5 minut."
            textSize = 12f; setTextColor(dim); setPadding(0, px(8), 0, 0)
        }
        box.addView(hint, wrap)
        schedule += hint
        enableSchedule(prefs.monitoring)

        box.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { topMargin = px(14) })
        box.addView(TextView(this).apply {
            text = "ZTM Widget v1.0.5 · Wydanie: 25.11.2026\ngithub.com/beepoo304/ZTM-widget"
            textSize = 12f
            setTextColor(dim)
            gravity = Gravity.CENTER
            setPadding(0, px(14), 0, px(8))
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/beepoo304/ZTM-widget")))
            }
        }, wrap)

        dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("OK", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(LedRenderer.colorOf(prefs.color))
                    preview.post(ticker)
                }
                setOnDismissListener { preview.removeCallbacks(ticker); Widgets.renderAll(this@ConfigActivity) }
                window?.setBackgroundDrawable(GradientDrawable().apply { setColor(0xFF171A1C.toInt()); cornerRadius = px(16).toFloat() })
                show()
            }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /** Otwórz od razu okno ustawień (podwójne dotknięcie widgetu). */
        const val EXTRA_SETTINGS = "pl.beepoo.ztmwidget.SETTINGS"
    }
}
