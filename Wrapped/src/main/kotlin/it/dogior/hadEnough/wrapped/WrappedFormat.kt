package it.dogior.hadEnough.wrapped

/** Un componente del tempo scomposto ("14", "giorni"). */
data class WrappedTimeUnit(val value: Long, val label: String)

/**
 * Scompone una durata in millisecondi in giorni/ore/minuti leggibili,
 * es. 14 giorni 12 ore 15 Min — mai un numero decimale di ore come
 * "14,4 giorni". Omette i componenti a zero in testa (es. meno di un
 * giorno → solo "ore"/"Min"), ma mostra sempre almeno i minuti, anche a 0.
 */
object WrappedFormat {
    fun breakDownDuration(totalMs: Long): List<WrappedTimeUnit> {
        val totalMinutes = totalMs / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60

        val units = mutableListOf<WrappedTimeUnit>()
        if (days > 0) units.add(WrappedTimeUnit(days, "giorni"))
        if (hours > 0 || units.isNotEmpty()) units.add(WrappedTimeUnit(hours, "ore"))
        units.add(WrappedTimeUnit(minutes, "Min"))
        return units
    }

    /** Stessa scomposizione, su una riga sola: "14 giorni 12 ore 15 Min". */
    fun formatDurationInline(totalMs: Long): String =
        breakDownDuration(totalMs).joinToString(" ") { "${it.value} ${it.label}" }
}
