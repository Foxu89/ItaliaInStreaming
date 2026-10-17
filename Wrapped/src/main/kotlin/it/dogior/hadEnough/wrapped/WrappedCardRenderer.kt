package it.dogior.hadEnough.wrapped

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
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
 *
 * Layout (dall'alto in basso), per righe ben separate invece di una lista
 * verticale disordinata:
 *  1. Header: icona + "CLOUDSTREAM WRAPPED" a sinistra, @nomeutente a destra.
 *  2. Badge esagonale del rank con alone colorato dietro, pillola col nome
 *     del rank sotto.
 *  3. Blocco unico col tempo totale (icona orologio + testo, mai decimali).
 *  4. Due card affiancate: film guardati / serie TV guardate.
 *  5. Footer di branding, centrato.
 */
object WrappedCardRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    // Sfondo "dark mode profondo" richiesto, non il viola dell'app: qui la
    // card deve leggersi come un contenuto a sé, quasi nero con riflessi blu/indaco.
    private val BG_TOP = Color.parseColor("#05070C")
    private val BG_BOTTOM = Color.parseColor("#0B0E14")
    private val ACCENT = Color.parseColor("#3B65F5")
    private val ACCENT_LIGHT = Color.parseColor("#818FFF")
    private val CARD_FILL = Color.parseColor("#15161F")
    private val CARD_STROKE = Color.parseColor("#262838")
    private val TEXT_PRIMARY = Color.WHITE
    private val TEXT_SECONDARY = Color.parseColor("#9A98AE")

    /** Stesso colore per rank usato nel pannello impostazioni (WrappedFragment.rankColor). */
    private fun rankColor(rank: WrappedRank): Int = when (rank) {
        WrappedRank.BRONZE -> Color.parseColor("#D07040")
        WrappedRank.SILVER -> Color.parseColor("#B0B0B8")
        WrappedRank.GOLD -> Color.parseColor("#E0A830")
        WrappedRank.DIAMOND -> Color.parseColor("#4FC3F7")
        WrappedRank.MASTER -> Color.parseColor("#B388FF")
    }

    /**
     * [rankIcon] è il PNG vero del rank, già caricato dal chiamante (via
     * plugin.resources — questo renderer riceve solo il Context dell'host,
     * che non ha accesso alle risorse del plugin). Se null (icona non
     * trovata) si disegna un badge esagonale di riserva disegnato a codice.
     */
    fun render(context: Context, stats: WrappedStats, rankIcon: Drawable? = null): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val accent = rankColor(stats.rank)

        drawBackground(canvas, accent)
        drawHeader(context, canvas, stats.profileName)
        drawRankBadge(canvas, stats.rank, rankIcon, accent)
        drawHeroTime(canvas, stats.totalWatchedMs)
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

    private fun drawBackground(canvas: Canvas, accent: Int) {
        val gradient = LinearGradient(
            0f, 0f, 0f, HEIGHT.toFloat(),
            BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        // Alone morbido col colore del rank dietro al badge, non un blu fisso:
        // Diamante si illumina d'azzurro, CloudStream di viola, ecc.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                WIDTH / 2f, 560f, 520f,
                intArrayOf(colorWithAlpha(accent, 110), colorWithAlpha(accent, 0)),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 40f, WIDTH.toFloat(), 1080f, glow)
    }

    /** Riga header: icona + etichetta a sinistra, handle utente a destra. */
    private fun drawHeader(context: Context, canvas: Canvas, profileName: String) {
        val iconSize = 64f
        val top = 90f
        val left = 80f

        drawAppIcon(context, canvas, left, top, iconSize)

        val labelPaint = textPaint(TEXT_SECONDARY, 26f, bold = true).apply {
            textAlign = Paint.Align.LEFT
            letterSpacing = 0.14f
        }
        canvas.drawText("CLOUDSTREAM WRAPPED", left + iconSize + 24f, top + iconSize / 2f + 9f, labelPaint)

        val handlePaint = textPaint(TEXT_PRIMARY, 32f, bold = true).apply {
            textAlign = Paint.Align.RIGHT
        }
        val handle = "@${profileName.trim()}"
        canvas.drawText(handle, WIDTH - 80f, top + iconSize / 2f + 11f, handlePaint)

        // Sottile linea divisoria, per separare l'header dal resto senza
        // un altro blocco di testo: solo struttura.
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorWithAlpha(TEXT_SECONDARY, 50)
            strokeWidth = 2f
        }
        canvas.drawLine(80f, top + iconSize + 34f, WIDTH - 80f, top + iconSize + 34f, dividerPaint)
    }

    private fun drawAppIcon(context: Context, canvas: Canvas, left: Float, top: Float, size: Float) {
        val icon: Drawable? = runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
        }.getOrNull()

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_FILL }
        canvas.drawCircle(left + size / 2f, top + size / 2f, size / 2f + 8f, circlePaint)

        if (icon != null) {
            icon.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
            icon.draw(canvas)
        } else {
            val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
            canvas.drawCircle(left + size / 2f, top + size / 2f, size / 2f, fallback)
        }
    }

    /** Badge esagonale del rank (hero section), con pillola del nome sotto. */
    private fun drawRankBadge(canvas: Canvas, rank: WrappedRank, rankIcon: Drawable?, accent: Int) {
        val badgeSize = 420
        val badgeLeft = (WIDTH - badgeSize) / 2
        val badgeTop = 300

        if (rankIcon != null) {
            rankIcon.setBounds(badgeLeft, badgeTop, badgeLeft + badgeSize, badgeTop + badgeSize)
            rankIcon.draw(canvas)
        } else {
            // Riserva: esagono disegnato a codice, stesso colore del rank,
            // così la card non resta mai senza badge anche senza i PNG.
            drawHexagonFallback(canvas, badgeLeft + badgeSize / 2f, badgeTop + badgeSize / 2f, badgeSize / 2f, accent)
        }

        val pillTop = (badgeTop + badgeSize + 40).toFloat()
        val pillHeight = 88f
        val label = rank.title.uppercase()
        val textPaintObj = textPaint(TEXT_PRIMARY, 40f, bold = true).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.06f
        }
        val textWidth = textPaintObj.measureText(label)
        val paddingH = 56f
        val pillRect = RectF(
            WIDTH / 2f - textWidth / 2f - paddingH,
            pillTop,
            WIDTH / 2f + textWidth / 2f + paddingH,
            pillTop + pillHeight
        )
        val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorWithAlpha(accent, 48) }
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, pillFill)
        val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, pillStroke)
        canvas.drawText(label, WIDTH / 2f, pillTop + pillHeight / 2f + 15f, textPaintObj)
    }

    private fun drawHexagonFallback(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val path = android.graphics.Path()
        for (i in 0 until 6) {
            val angle = Math.toRadians((60 * i - 90).toDouble())
            val x = cx + radius * Math.cos(angle).toFloat()
            val y = cy + radius * Math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx, cy - radius, cx, cy + radius,
                color, colorWithAlpha(color, 160), Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(path, fill)
    }

    /**
     * Blocco unico centrale col tempo totale: icona orologio + testo in fila,
     * mai un numero decimale ("14,4 giorni"), sempre la scomposizione
     * giorni/ore/minuti separata da "•" (stessa logica di WrappedFormat,
     * usata anche nel pannello impostazioni).
     */
    private fun drawHeroTime(canvas: Canvas, totalMs: Long) {
        val units = WrappedFormat.breakDownDuration(totalMs)
        val timeText = units.joinToString("   •   ") {
            "${it.value} ${it.label.replaceFirstChar(Char::uppercase)}"
        }

        val labelPaint = textPaint(TEXT_SECONDARY, 26f, bold = true).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.14f
        }
        canvas.drawText("TEMPO TOTALE GUARDATO", WIDTH / 2f, 1000f, labelPaint)

        // Dimensione del font adattiva: la stringa cambia lunghezza in base
        // a quante unità (giorni/ore/Min) ci sono, non deve mai uscire dalla card.
        val maxWidth = WIDTH - 160f
        var fontSize = 58f
        val valuePaint = textPaint(TEXT_PRIMARY, fontSize, bold = true).apply {
            textAlign = Paint.Align.CENTER
        }
        while (valuePaint.measureText(timeText) > maxWidth && fontSize > 32f) {
            fontSize -= 2f
            valuePaint.textSize = fontSize
        }

        val cardTop = 1050f
        val cardHeight = 150f
        val cardRect = RectF(80f, cardTop, WIDTH - 80f, cardTop + cardHeight)
        val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_FILL }
        canvas.drawRoundRect(cardRect, 32f, 32f, cardFill)
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CARD_STROKE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(cardRect, 32f, 32f, cardStroke)

        val clockPaint = textPaint(TEXT_PRIMARY, fontSize, bold = false).apply {
            textAlign = Paint.Align.CENTER
        }
        val clockWidth = clockPaint.measureText("⏱ ")
        val totalWidth = valuePaint.measureText(timeText) + clockWidth
        val startX = WIDTH / 2f - totalWidth / 2f
        val baseline = cardTop + cardHeight / 2f + fontSize * 0.34f

        canvas.drawText("⏱", startX + clockWidth / 2f, baseline, textPaint(TEXT_PRIMARY, fontSize, bold = false).apply {
            textAlign = Paint.Align.CENTER
        })
        canvas.drawText(
            timeText,
            startX + clockWidth + (totalWidth - clockWidth) / 2f,
            baseline,
            textPaint(TEXT_PRIMARY, fontSize, bold = true).apply { textAlign = Paint.Align.CENTER }
        )
    }

    /** Griglia in basso: due card affiancate, sfondo grigio scuro su sfondo quasi nero. */
    private fun drawCounters(canvas: Canvas, movies: Int, series: Int) {
        val cardTop = 1270f
        val cardHeight = 300f
        val gap = 32f
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
            color = CARD_STROKE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(rect, 36f, 36f, borderPaint)

        val centerX = left + width / 2f
        val emojiPaint = textPaint(TEXT_PRIMARY, 56f, bold = false).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(emoji, centerX, top + 100f, emojiPaint)

        val valuePaint = textPaint(TEXT_PRIMARY, 92f, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(value, centerX, top + 210f, valuePaint)

        val labelPaint = textPaint(TEXT_SECONDARY, 32f, bold = false).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(label, centerX, top + 262f, labelPaint)
    }

    private fun drawFooter(canvas: Canvas) {
        val paint = textPaint(TEXT_SECONDARY, 30f, bold = false).apply {
            textAlign = Paint.Align.CENTER
            alpha = 170
            letterSpacing = 0.02f
        }
        canvas.drawText("cloudstream.wiki  •  Watch Timeline", WIDTH / 2f, HEIGHT - 110f, paint)
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
