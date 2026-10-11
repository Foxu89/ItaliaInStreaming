package it.dogior.hadEnough.wrapped

/**
 * Grado ironico assegnato in base alle ore totali (vedi WrappedStats).
 * Soglie pensate per essere divertenti più che "scientifiche": un
 * contenuto onesto ma scherzoso, in stile meme — non una metrica seria.
 */
enum class WrappedRank(val minHours: Int, val title: String, val emoji: String) {
    FRESH(0, "Fresh Install", "🌱"),
    CASUAL(5, "Spettatore Occasionale", "🍿"),
    REGULAR(20, "Habitué del Divano", "📺"),
    MARATHONER(50, "Maratoneta", "🏃"),
    PRO_MARATHONER(150, "Maratoneta Pro", "🔥"),
    NO_LIFE(400, "Ha Sacrificato una Vita Sociale", "💀"),
    LEGEND(1000, "Leggenda Vivente di CloudStream", "👑");

    companion object {
        fun forTotalMs(totalMs: Long): WrappedRank {
            val hours = totalMs / 3_600_000.0
            return entries.sortedByDescending { it.minHours }.first { hours >= it.minHours }
        }
    }
}
