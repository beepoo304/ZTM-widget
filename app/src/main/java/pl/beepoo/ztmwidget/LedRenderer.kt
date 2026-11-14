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
    const val ROWS = 56
    private const val MIN_COLS = 110
    private const val ROW_H = 9
    private val ROW_TOP = intArrayOf(1, 11, 20, 29, 38, 47)
    private val PL = Locale("pl", "PL")

    /** Kolory diod: rdzeń, poświata. */
    private val COLORS = arrayOf(
        intArrayOf(0xFFFFC21A.toInt(), 0xFFFF8F00.toInt()), // bursztynowy
        intArrayOf(0xFFFF3D2E.toInt(), 0xFFFF1A0A.toInt()), // czerwony
        intArrayOf(0xFF7DFF6A.toInt(), 0xFF33E022.toInt()), // zielony
    )
    val COLOR_NAMES = arrayOf("Bursztynowy", "Czerwony", "Zielony")
    fun colorOf(i: Int): Int = COLORS[i.coerceIn(0, 2)][0]

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
    fun render(ctx: Context, wPx: Int, hPx: Int, screen: Screen, now: LocalTime, blink: Boolean, colorIdx: Int, brightness: Int): Bitmap {
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
        val (core, glow) = COLORS[colorIdx.coerceIn(0, 2)].let { it[0] to it[1] }

        // Cache tła ciemnych diod – generowane raz na rozmiar/kolor zamiast 6000 drawCircle co sekundę!
        val bgK = "${w}_${h}_${cols}_${pitch}_$colorIdx"
        var bg = bgBitmap
        if (bg == null || bgKey != bgK || bg.isRecycled) {
            bg?.recycle()
            val newBg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bgCanvas = Canvas(newBg)
            bgCanvas.drawColor(0xFF000000.toInt())
            val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim(core, 0.10f) }
            val rOff = pitch * 0.36f
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

    /** Świecąca dioda: jasny rdzeń + miękka poświata. */
    private fun litSprite(pitch: Float, core: Int, glow: Int, b: Float): Bitmap {
        val size = max(3, ceil(pitch * 1.8f).toInt())
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(r, r, r, intArrayOf(withAlpha(glow, 0.55f * b), withAlpha(glow, 0f)), null, Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r, p)
        p.shader = null
        p.color = withAlpha(lighten(core, 0.15f), b)
        c.drawCircle(r, r, pitch * 0.40f, p)
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
            is Screen.Paused -> { center("MONITOROWANIE", 2); center("WSTRZYMANE", 3); center("DOTKNIJ = 5 MIN", 5) }
            is Screen.Message -> center(screen.text.uppercase(PL), 3)
            is Screen.Departures -> {
                val deps = screen.board.departures.take(if (screen.stale) 4 else 5)
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
                if (screen.stale) center("BRAK AKTUALIZACJI", 5)
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
