package pl.beepoo.ztmwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import java.time.LocalTime
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Co ma pokazać tablica w danej sekundzie. */
sealed class Screen {
    object NoStop : Screen()
    data class Paused(val title: String) : Screen()
    data class Message(val title: String, val text: String) : Screen()
    data class Departures(val title: String, val board: Board, val stale: Boolean) : Screen()
}

/**
 * Tablica LED: tekst rysowany fontem pikselowym (Micro5) w niskiej rozdzielczości – 1 piksel = 1 dioda –
 * a potem każda dioda jako kropka: świecąca z poświatą albo ciemna „nieświecąca”.
 */
object LedRenderer {
    const val ROWS = 65
    private const val MIN_COLS = 110
    private const val ROW_H = 9
    private val ROW_TOP = intArrayOf(1, 11, 20, 29, 38, 47, 56)
    private val PL = Locale("pl", "PL")

    /** Kolory diod: rdzeń, poświata. */
    private val COLORS = arrayOf(
        intArrayOf(0xFFFFC21A.toInt(), 0xFFFF8F00.toInt()), // 0: bursztynowy
        intArrayOf(0xFFFF3D2E.toInt(), 0xFFFF1A0A.toInt()), // 1: czerwony
        intArrayOf(0xFF7DFF6A.toInt(), 0xFF33E022.toInt()), // 2: zielony
        intArrayOf(0xFFFF4DA6.toInt(), 0xFFE60073.toInt()), // 3: różowy
        intArrayOf(0xFF40C4FF.toInt(), 0xFF008CFF.toInt()), // 4: niebieski
    )
    val COLOR_NAMES = arrayOf("Bursztyn", "Czerwony", "Zielony", "Różowy", "Niebieski")
    fun colorOf(i: Int): Int = COLORS[i.coerceIn(0, COLORS.lastIndex)][0]

    private var typeface: Typeface? = null

    private fun textPaint(ctx: Context): Paint {
        val tf = typeface ?: ctx.resources.getFont(R.font.micro5).also { typeface = it }
        return Paint().apply {
            this.typeface = tf
            textSize = 10f
            isAntiAlias = false
            color = Color.WHITE
        }
    }

    private var bgBitmap: Bitmap? = null
    private var bgKey: String? = null
    private var cachedSprite: Bitmap? = null
    private var spriteKey: String? = null

    /** Rysuje całą tablicę w bitmapie o rozmiarze widgetu (piksele). */
    fun render(
        ctx: Context,
        wPx: Int,
        hPx: Int,
        screen: Screen,
        now: LocalTime,
        blink: Boolean,
        colorIdx: Int,
        brightness: Int,
        style: Int = Prefs.STYLE_REAL
    ): Bitmap {
        return if (style == Prefs.STYLE_MODERN) {
            renderModern(ctx, wPx, hPx, screen, now, blink, colorIdx, brightness)
        } else {
            renderReal(ctx, wPx, hPx, screen, now, blink, colorIdx, brightness)
        }
    }

    private fun renderReal(
        ctx: Context,
        wPx: Int,
        hPx: Int,
        screen: Screen,
        now: LocalTime,
        blink: Boolean,
        colorIdx: Int,
        brightness: Int
    ): Bitmap {
        // Ograniczamy rozmiar bitmapy (przesyłana do launchera co sekundę).
        val scale = min(1f, 900f / max(wPx, hPx))
        val w = max(1, (wPx * scale).toInt())
        val h = max(1, (hPx * scale).toInt())
        var pitch = h.toFloat() / ROWS
        var cols = floor(w / pitch).toInt()
        if (cols < MIN_COLS) { cols = MIN_COLS; pitch = w.toFloat() / cols }

        val dots = Bitmap.createBitmap(cols, ROWS, Bitmap.Config.ARGB_8888)
        compose(Canvas(dots), textPaint(ctx), cols, screen, now, blink)
        val px = IntArray(cols * ROWS)
        dots.getPixels(px, 0, cols, 0, 0, cols, ROWS)
        dots.recycle()

        val ox = (w - cols * pitch) / 2f
        val oy = (h - ROWS * pitch) / 2f
        val b = brightness.coerceIn(20, 100) / 100f
        val (core, glow) = COLORS[colorIdx.coerceIn(0, COLORS.lastIndex)].let { it[0] to it[1] }

        // Cache tła ciemnych diod – generowane raz na rozmiar/kolor zamiast 6000 drawCircle co sekundę!
        // Wyrazisty kontrast: 6% zamiast 10% – głęboka czerń i brak zlewania się z tekstem
        val bgK = "${w}_${h}_${cols}_${pitch}_$colorIdx"
        var bg = bgBitmap
        if (bg == null || bgKey != bgK || bg.isRecycled) {
            bg?.recycle()
            val newBg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bgCanvas = Canvas(newBg)
            bgCanvas.drawColor(0xFF000000.toInt())
            val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim(core, 0.06f) }
            val rOff = pitch * 0.35f
            for (y in 0 until ROWS) for (x in 0 until cols) {
                val cx = ox + (x + 0.5f) * pitch
                val cy = oy + (y + 0.5f) * pitch
                bgCanvas.drawCircle(cx, cy, rOff, offPaint)
            }
            bg = newBg
            bgBitmap = newBg
            bgKey = bgK
        }

        // Cache świecącej diody (sprite)
        val sKey = "${pitch}_${core}_${glow}_$b"
        var sprite = cachedSprite
        if (sprite == null || spriteKey != sKey || sprite.isRecycled) {
            sprite?.recycle()
            val newSprite = litSprite(pitch, core, glow, b)
            sprite = newSprite
            cachedSprite = newSprite
            spriteKey = sKey
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(bg, 0f, 0f, null)

        val half = sprite.width / 2f
        // Rysujemy TYLKO zapalone diody (zamiast 6720 operacji – tylko kilkaset)
        for (y in 0 until ROWS) {
            val rowOffset = y * cols
            val cy = oy + (y + 0.5f) * pitch - half
            for (x in 0 until cols) {
                if (Color.alpha(px[rowOffset + x]) > 127) {
                    val cx = ox + (x + 0.5f) * pitch - half
                    c.drawBitmap(sprite, cx, cy, null)
                }
            }
        }
        return out
    }

    /** Nowoczesny, gładki i ultra-czytelny motyw wektorowy na czarnym tle. */
    private fun renderModern(
        ctx: Context,
        wPx: Int,
        hPx: Int,
        screen: Screen,
        now: LocalTime,
        blink: Boolean,
        colorIdx: Int,
        brightness: Int
    ): Bitmap {
        val scale = min(1f, 900f / max(wPx, hPx))
        val w = max(1, (wPx * scale).toInt())
        val h = max(1, (hPx * scale).toInt())

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF000000.toInt())

        // Kroje systemowe: wąski pogrubiony na kierunki (więcej liter w wierszu), ciężki na linie i czasy.
        val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val heavy = Typeface.create("sans-serif-black", Typeface.NORMAL)

        val b = brightness.coerceIn(20, 100) / 100f
        val accent = colorOf(colorIdx)
        val accentBright = withAlpha(accent, b)
        val textPrimary = withAlpha(0xFFFFFFFF.toInt(), b)
        val textSecondary = withAlpha(0xFFC4CACC.toInt(), b)
        val lineSep = withAlpha(0xFF2B3032.toInt(), b)

        val padX = max(w * 0.03f, 10f)
        val padY = max(h * 0.03f, 6f)

        // Nagłówek: przystanek + zegar
        val headerH = h * 0.16f
        val headerBaseline = padY + headerH * 0.75f

        val title = when (screen) {
            Screen.NoStop -> "PRZYSTANEK ZTM"
            is Screen.Paused -> screen.title
            is Screen.Message -> screen.title
            is Screen.Departures -> screen.title
        }

        val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = heavy
            textSize = max(headerH * 0.8f, 12f)
            color = accentBright
        }
        val hh = "%02d".format(now.hour)
        val mm = "%02d".format(now.minute)
        val clockStr = if (blink) "$hh:$mm" else "$hh $mm"
        val clockX = w - padX - clockPaint.measureText(clockStr)

        if (screen !is Screen.Paused) {
            c.drawText(clockStr, clockX, headerBaseline, clockPaint)
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = condensed
            textSize = max(headerH * 0.72f, 11f)
            color = textSecondary
        }
        c.save()
        c.clipRect(padX, padY, clockX - padX * 0.4f, padY + headerH)
        c.drawText(title.uppercase(PL), padX, headerBaseline, titlePaint)
        c.restore()

        val sepY = padY + headerH
        val sepPaint = Paint().apply { color = lineSep; strokeWidth = max(1f, h * 0.008f) }
        c.drawLine(padX, sepY, w - padX, sepY, sepPaint)

        // Treść
        val contentTop = sepY + padY * 0.4f
        val contentBottom = h - padY * 0.5f
        val contentH = max(1f, contentBottom - contentTop)

        when (screen) {
            Screen.NoStop -> {
                drawCenteredMessage(c, "DOTKNIJ, ABY WYBRAĆ PRZYSTANEK", w / 2f, contentTop + contentH * 0.5f, textPrimary, max(contentH * 0.16f, 12f))
            }
            is Screen.Paused -> {
                drawCenteredMessage(c, "MONITOROWANIE WSTRZYMANE", w / 2f, contentTop + contentH * 0.4f, textSecondary, max(contentH * 0.15f, 12f))
                drawCenteredMessage(c, "Dotknij, aby włączyć na 5 minut", w / 2f, contentTop + contentH * 0.65f, accentBright, max(contentH * 0.13f, 11f))
            }
            is Screen.Message -> {
                drawCenteredMessage(c, screen.text.uppercase(PL), w / 2f, contentTop + contentH * 0.5f, accentBright, max(contentH * 0.16f, 12f))
            }
            is Screen.Departures -> {
                val deps = screen.board.departures.take(if (screen.stale) 5 else 6)
                if (deps.isEmpty()) {
                    drawCenteredMessage(c, "BRAK ODJAZDÓW", w / 2f, contentTop + contentH * 0.5f, textSecondary, max(contentH * 0.16f, 12f))
                    return out
                }

                val rowCount = if (screen.stale) 6 else max(deps.size, 5)
                val rowH = contentH / rowCount
                val rowTextSize = max(rowH * 0.74f, 11f)

                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = heavy
                    textSize = rowTextSize
                    color = accentBright
                }
                val destPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = condensed
                    textSize = rowTextSize
                    color = textPrimary
                }
                val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = heavy
                    textSize = rowTextSize
                    color = accentBright
                }
                val timeSchedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = condensed
                    textSize = rowTextSize
                    color = textSecondary
                }

                val maxLineW = max(linePaint.measureText("000"), deps.maxOf { linePaint.measureText(it.line.uppercase(PL)) })
                val maxTimeW = max(timePaint.measureText("00 min"), timePaint.measureText("00:00"))
                val destLeft = padX + maxLineW + padX * 0.6f
                val destRight = w - padX - maxTimeW - padX * 0.4f

                deps.forEachIndexed { i, d ->
                    val rowCenter = contentTop + (i + 0.5f) * rowH
                    val baseline = rowCenter + rowTextSize * 0.36f

                    // Linia
                    c.drawText(d.line.uppercase(PL), padX, baseline, linePaint)

                    // Kierunek
                    c.save()
                    c.clipRect(destLeft, contentTop + i * rowH, destRight, contentTop + (i + 1) * rowH)
                    c.drawText(d.destination, destLeft, baseline, destPaint)
                    c.restore()

                    // Czas
                    if (d.atStop) {
                        val atStopStr = if (blink) ">>>" else " "
                        val tw = timePaint.measureText(atStopStr)
                        c.drawText(atStopStr, w - padX - tw, baseline, timePaint)
                    } else {
                        val tp = if (d.realtime) timePaint else timeSchedPaint
                        val tw = tp.measureText(d.time)
                        c.drawText(d.time, w - padX - tw, baseline, tp)
                    }
                }

                if (screen.stale) {
                    val staleY = contentTop + 5.5f * rowH + rowTextSize * 0.35f
                    val stalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                        textSize = max(rowTextSize * 0.85f, 10f)
                        color = 0xFFFF453A.toInt()
                    }
                    val stW = stalePaint.measureText("BRAK AKTUALIZACJI")
                    c.drawText("BRAK AKTUALIZACJI", (w - stW) / 2f, staleY, stalePaint)
                }
            }
        }

        return out
    }

    private fun drawCenteredMessage(c: Canvas, text: String, cx: Float, cy: Float, color: Int, size: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = size
            this.color = color
        }
        val w = p.measureText(text)
        c.drawText(text, cx - w / 2f, cy + size * 0.35f, p)
    }

    /** Świecąca dioda: jasny rdzeń + miękka poświata. */
    private fun litSprite(pitch: Float, core: Int, glow: Int, b: Float): Bitmap {
        val size = max(3, ceil(pitch * 1.8f).toInt())
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(r, r, r, intArrayOf(withAlpha(glow, 0.65f * b), withAlpha(glow, 0f)), null, Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r, p)
        p.shader = null
        p.color = withAlpha(lighten(core, 0.20f), b)
        c.drawCircle(r, r, pitch * 0.43f, p)
        return bmp
    }

    // ---- układ tekstu w siatce diod ----

    private fun compose(c: Canvas, p: Paint, cols: Int, screen: Screen, now: LocalTime, blink: Boolean) {
        val baseline = baselineOffset(p)
        fun text(s: String, x: Float, row: Int, right: Int = cols) {
            c.save()
            c.clipRect(x.toInt(), ROW_TOP[row], right, ROW_TOP[row] + ROW_H)
            c.drawText(s, x, (ROW_TOP[row] + baseline).toFloat(), p)
            c.restore()
        }
        fun textRight(s: String, right: Int, row: Int) = text(s, right - p.measureText(s), row)
        fun center(s: String, row: Int) = text(s, ((cols - p.measureText(s)) / 2f).coerceAtLeast(1f), row)

        // Nagłówek: nazwa przystanku + zegar z migającym dwukropkiem.
        val title = when (screen) {
            Screen.NoStop -> "PRZYSTANEK ZTM"
            is Screen.Paused -> screen.title
            is Screen.Message -> screen.title
            is Screen.Departures -> screen.title
        }
        val hh = "%02d".format(now.hour)
        val mm = "%02d".format(now.minute)
        val clockRight = cols - 1
        val clockLeft = clockRight - p.measureText("$hh:$mm")
        if (screen !is Screen.Paused) {
            text(hh, clockLeft, 0)
            if (blink) text(":", clockLeft + p.measureText(hh), 0)
            textRight(mm, clockRight, 0)
        }
        text(title.uppercase(PL), 1f, 0, (clockLeft - 4).toInt())

        when (screen) {
            Screen.NoStop -> { center("DOTKNIJ, ABY", 2); center("WYBRAĆ PRZYSTANEK", 3) }
            is Screen.Paused -> { center("MONITOROWANIE", 2); center("WSTRZYMANE", 3); center("DOTKNIJ = 5 MIN", 6) }
            is Screen.Message -> center(screen.text.uppercase(PL), 3)
            is Screen.Departures -> {
                val deps = screen.board.departures.take(if (screen.stale) 5 else 6)
                if (deps.isEmpty()) { center("BRAK ODJAZDÓW", 3); return }
                val lineW = max(p.measureText("000"), deps.maxOf { p.measureText(it.line.uppercase(PL)) })
                val timeW = max(p.measureText("00 min"), p.measureText("00:00"))
                val dirX = 1f + lineW + 4f
                val timeRight = cols - 1
                deps.forEachIndexed { i, d ->
                    val row = i + 1
                    text(d.line.uppercase(PL), 1f, row)
                    text(d.destination.uppercase(PL), dirX, row, (timeRight - timeW - 3).toInt())
                    if (d.atStop) { if (blink) textRight(">>>", timeRight, row) }
                    else textRight(d.time, timeRight, row)
                }
                if (screen.stale) center("BRAK AKTUALIZACJI", 6)
            }
        }
    }

    private var cachedBaseline = -1
    private fun baselineOffset(p: Paint): Int {
        if (cachedBaseline >= 0) return cachedBaseline
        val r = Rect()
        p.getTextBounds("ŻÓŁ", 0, 3, r)
        // Górna kropka ogonka w pierwszym wierszu diody; reszta litery pod nią.
        cachedBaseline = (-r.top).coerceIn(5, ROW_H - 1)
        return cachedBaseline
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((Color.alpha(c) * a.coerceIn(0f, 1f)).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    private fun dim(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
    )
}
