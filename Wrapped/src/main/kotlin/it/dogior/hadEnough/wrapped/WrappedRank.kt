package it.dogior.hadEnough.wrapped

/**
 * Grado assegnato in base alle ore totali guardate (vedi WrappedStats).
 * Soglie di partenza, facili da aggiustare in un secondo momento: non
 * sono "scientifiche", sono un punto di partenza ragionevole in attesa di
 * dati reali di utilizzo.
 *
 * [iconName] è il nome (senza estensione) del drawable/PNG caricato a
 * runtime via plugin.resources, stesso pattern di getDrawable() in
 * WrappedFragment — se il PNG non è ancora presente nel plugin si usa un
 * fallback disegnato a codice (vedi RankIconView/drawRankFallback), così
 * l'app non si rompe prima che le icone vengano aggiunte.
 */
enum class WrappedRank(val minHours: Int, val title: String, val iconName: String) {
    BRONZE(0, "Bronzo", "rank_bronze"),
    SILVER(20, "Argento", "rank_silver"),
    GOLD(60, "Oro", "rank_gold"),
    DIAMOND(150, "Diamante", "rank_diamond"),
    // Rank più alto, tema viola/arcobaleno — "CloudStream" invece di "Master"
    // su richiesta esplicita.
    MASTER(400, "CloudStream", "rank_master");

    val isMax: Boolean get() = this == MASTER

    companion object {
        fun forTotalMs(totalMs: Long): WrappedRank {
            val hours = totalMs / 3_600_000.0
            return entries.sortedByDescending { it.minHours }.first { hours >= it.minHours }
        }

        /** Il rank successivo, o null se si è già a Master (il tetto). */
        fun next(of: WrappedRank): WrappedRank? {
            val ordered = entries.sortedBy { it.minHours }
            val i = ordered.indexOf(of)
            return ordered.getOrNull(i + 1)
        }
    }
}

/**
 * Progresso (0f..1f) tra il rank attuale e il prossimo, calcolato sulle ore
 * totali. 1f (barra piena) e nessun prossimo rank quando si è già a Master:
 * in quel caso il chiamante mostra "MAX" invece di una percentuale.
 */
data class WrappedRankProgress(
    val current: WrappedRank,
    val next: WrappedRank?,
    val fraction: Float,
) {
    companion object {
        fun compute(totalMs: Long): WrappedRankProgress {
            val current = WrappedRank.forTotalMs(totalMs)
            val next = WrappedRank.next(current)
            if (next == null) return WrappedRankProgress(current, null, 1f)
            val hours = totalMs / 3_600_000.0
            val span = (next.minHours - current.minHours).coerceAtLeast(1)
            val progressed = (hours - current.minHours).coerceIn(0.0, span.toDouble())
            return WrappedRankProgress(current, next, (progressed / span).toFloat())
        }
    }
}
