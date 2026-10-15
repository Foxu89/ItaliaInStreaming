package it.dogior.hadEnough.wrapped

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream

/**
 * Disegna la card 9:16 interamente a runtime con Canvas (niente immagini
 * pre-fatte): testo, forme, colori e l'icona VERA di CloudStream presa
 * dalle risorse dell'app stessa via PackageManager — non un logo
 * ricostruito da zero, quello vero installato sul telefono dell'utente.
 */
object WrappedCardRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    // Stessi colori del tema reale di CloudStream (colorAccent / background nero
    // dell'app icon), per restare visivamente coerenti con l'app.
    private val ACCENT = Color.parseColor("#3B65F5")
    private val ACCENT_LIGHT = Color.parseColor("#818FFF")
    private val BG_TOP = Color.parseColor("#0B0B12")
    private val BG_BOTTOM = Color.parseColor("#15141F")
    private val CARD_FILL = Color.parseColor("#1C1B29")
    private val TEXT_PRIMARY = Color.WHITE
    private val TEXT_SECONDARY = Color.parseColor("#9A98AE")

    /**
     * [rankIcon] è il PNG vero del rank, già caricato dal chiamante (via
     * plugin.resources — questo renderer riceve solo il Context dell'host,
     * che non ha accesso alle risorse del plugin). Se null (icona non
     * trovata) si disegna solo il testo del rank, senza badge.
     */
    fun render(context: Context, stats: WrappedStats, rankIcon: Drawable? = null): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        drawBackground(canvas)
        drawHeader(context, canvas, stats.profileName)
        drawRank(canvas, stats.rank, rankIcon)
        drawHeroNumber(canvas, stats.totalWatchedMs)
        drawCounters(canvas, stats.moviesWatched, stats.seriesWatched)
        drawFooter(canvas)

        return bmp
    }

    /** Salva il bitmap in cache e ritorna il file, pronto per FileProvider. */
    fun saveToCache(context: Context, bitmap: Bitmap): File {
        val file = File(context.cacheDir, "cloudstream_wrapped.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    private fun drawBackground(canvas: Canvas) {
        val gradient = LinearGradient(
            0f, 0f, 0f, HEIGHT.toFloat(),
            BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        // Alone di accento morbido in alto, stile "glow" — decorativo, nessun testo.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                WIDTH / 2f, 260f, 620f,
                intArrayOf(colorWithAlpha(ACCENT, 90), colorWithAlpha(ACCENT, 0)),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 900f, glow)
    }

    private fun drawHeader(context: Context, canvas: Canvas, profileName: String) {
        val iconSize = 132
        val iconLeft = (WIDTH - iconSize) / 2f
        val iconTop = 150f

        drawAppIcon(context, canvas, iconLeft, iconTop, iconSize.toFloat())

        val appNamePaint = textPaint(TEXT_SECONDARY, 34f, bold = true).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.12f
        }
        canvas.drawText("CLOUDSTREAM WRAPPED", WIDTH / 2f, iconTop + iconSize + 76f, appNamePaint)

        val namePaint = textPaint(TEXT_PRIMARY, 56f, bold = true).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(profileName, WIDTH / 2f, iconTop + iconSize + 150f, namePaint)
    }

    private fun drawAppIcon(context: Context, canvas: Canvas, left: Float, top: Float, size: Float) {
        val icon: Drawable? = runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
        }.getOrNull()

        // Sfondo circolare dietro l'icona, così si legge bene anche se l'icona
        // ha angoli trasparenti o non è perfettamente quadrata.
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_FILL }
        canvas.drawCircle(left + size / 2f, top + size / 2f, size / 2f + 14f, circlePaint)

        if (icon != null) {
            icon.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
            icon.draw(canvas)
        } else {
            // Rete di sicurezza se l'icona non si recupera: un placeholder col
            // colore del tema invece di uno spazio vuoto.
            val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
            canvas.drawCircle(left + size / 2f, top + size / 2f, size / 2f, fallback)
        }
    }

    private fun drawRank(canvas: Canvas, rank: WrappedRank, rankIcon: Drawable?) {
        // Icona del rank (il PNG esagonale vero), se caricata dal chiamante.
        if (rankIcon != null) {
            val iconSize = 220
            val iconLeft = (WIDTH - iconSize) / 2
            val iconTop = 420
            rankIcon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            rankIcon.draw(canvas)
        }

        val badgeText = rank.title
        val paint = textPaint(TEXT_PRIMARY, 38f, bold = true).apply {
            textAlign = Paint.Align.CENTER
        }
        val textWidth = paint.measureText(badgeText)
        val paddingH = 48f
        val badgeHeight = 96f
        val badgeTop = if (rankIcon != null) 680f else 560f
        val badgeRect = RectF(
            WIDTH / 2f - textWidth / 2f - paddingH,
            badgeTop,
            WIDTH / 2f + textWidth / 2f + paddingH,
            badgeTop + badgeHeight
        )
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                badgeRect.left, 0f, badgeRect.right, 0f,
                ACCENT, ACCENT_LIGHT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(badgeRect, badgeHeight / 2f, badgeHeight / 2f, badgePaint)
        canvas.drawText(badgeText, WIDTH / 2f, badgeTop + badgeHeight / 2f + 14f, paint)
    }

    /**
     * Mai un numero decimale di ore/giorni (es. "14,4 giorni"): il
     * componente più grande (giorni, o ore se meno di un giorno) in grande,
     * il resto della scomposizione sotto, in chiaro — stessa logica di
     * WrappedFormat usata nel pannello impostazioni.
     */
    private fun drawHeroNumber(canvas: Canvas, totalMs: Long) {
        val units = WrappedFormat.breakDownDuration(totalMs)
        val big = units.first()
        val rest = units.drop(1)

        val numberPaint = textPaint(TEXT_PRIMARY, 220f, bold = true).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(big.value.toString(), WIDTH / 2f, 980f, numberPaint)

        val labelPaint = textPaint(TEXT_SECONDARY, 44f, bold = true).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(big.label, WIDTH / 2f, 1040f, labelPaint)

        if (rest.isNotEmpty()) {
            val restText = rest.joinToString("   ") { "${it.value} ${it.label}" }
            val restPaint = textPaint(TEXT_SECONDARY, 36f, bold = false).apply {
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(restText, WIDTH / 2f, 1100f, restPaint)
        }
    }

    private fun drawCounters(canvas: Canvas, movies: Int, series: Int) {
        val cardTop = 1220f
        val cardHeight = 300f
        val gap = 40f
        val cardWidth = (WIDTH - 160f - gap) / 2f

        drawStatCard(canvas, 80f, cardTop, cardWidth, cardHeight, movies.toString(), "Film guardati", "🎬")
        drawStatCard(canvas, 80f + cardWidth + gap, cardTop, cardWidth, cardHeight, series.toString(), "Serie TV guardate", "📺")
    }

    private fun drawStatCard(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        value: String, label: String, emoji: String
    ) {
        val rect = RectF(left, top, left + width, top + height)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_FILL }
        canvas.drawRoundRect(rect, 36f, 36f, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorWithAlpha(ACCENT_LIGHT, 60)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(rect, 36f, 36f, borderPaint)

        val centerX = left + width / 2f
        val emojiPaint = textPaint(TEXT_PRIMARY, 56f, bold = false).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(emoji, centerX, top + 96f, emojiPaint)

        val valuePaint = textPaint(TEXT_PRIMARY, 88f, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(value, centerX, top + 200f, valuePaint)

        val labelPaint = textPaint(TEXT_SECONDARY, 32f, bold = false).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(label, centerX, top + 250f, labelPaint)
    }

    private fun drawFooter(canvas: Canvas) {
        val paint = textPaint(TEXT_SECONDARY, 30f, bold = false).apply {
            textAlign = Paint.Align.CENTER
            alpha = 160
        }
        canvas.drawText("cloudstream.wiki", WIDTH / 2f, HEIGHT - 110f, paint)
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}
