package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import it.dogior.hadEnough.BackupUtils.isResume

/**
 * Fase 2: unione elemento per elemento.
 *
 * Ogni chiave (preferito, progresso, impostazione...) ha una data di ultima modifica ("t").
 * Le date stanno in un file locale separato (syncstream_meta), che NON fa parte del backup.
 * Quando due dispositivi si incontrano, per ogni chiave vince la modifica piu' recente.
 * Una chiave cancellata resta come "tombstone" (segno di cancellazione) per 60 giorni.
 */
object SyncMerge {
    private const val STORE = "syncstream_meta"
    const val TOMBSTONE_TTL = 60L * 24 * 3600 * 1000

    data class LocalMeta(val t: Long, val vh: String, val del: Boolean)
    data class MergeResult(val applied: Boolean, val needsUpload: Boolean)

    private fun store(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun enc(m: LocalMeta) = "${m.t};${m.vh};${if (m.del) 1 else 0}"

    private fun dec(s: String?): LocalMeta? {
        val p = s?.split(";") ?: return null
        if (p.size < 3) return null
        val t = p[0].toLongOrNull() ?: return null
        return LocalMeta(t, p[1], p[2] == "1")
    }

    /** Rappresentazione canonica di un valore (per confronti e impronte). */
    fun canon(v: Any?): String = when (v) {
        null -> "N:"
        is Set<*> -> "S:" + v.map { it.toString() }.sorted().joinToString("\u0001")
        else -> "${v.javaClass.simpleName}:$v"
    }

    private fun vhash(v: Any?): String {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 bit
        for (c in canon(v)) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }

    fun catOfFull(k: String): String = BackupUtils.catOf(k.substring(2), k.startsWith("s:"))

    /** Tutte le chiavi trasferibili presenti ora sul dispositivo (prefisso d: = dati, s: = impostazioni). */
    private fun present(ctx: Context): Map<String, Any> {
        val out = HashMap<String, Any>()
        ctx.getSharedPrefs().all.forEach { (k, v) -> if (v != null && k.isResume()) out["d:$k"] = v }
        ctx.getDefaultSharedPrefs().all.forEach { (k, v) -> if (v != null && k.isResume()) out["s:$k"] = v }
        return out
    }

    fun flat(vars: BackupVars, prefix: String, out: MutableMap<String, Any>) {
        vars.bool?.forEach { (k, v) -> out[prefix + k] = v }
        vars.int?.forEach { (k, v) -> out[prefix + k] = (v as Number).toInt() }
        vars.string?.forEach { (k, v) -> out[prefix + k] = v }
        vars.float?.forEach { (k, v) -> out[prefix + k] = (v as Number).toFloat() }
        vars.long?.forEach { (k, v) -> out[prefix + k] = (v as Number).toLong() }
        vars.stringSet?.forEach { (k, v) -> if (v != null) out[prefix + k] = v.toSet() }
    }

    fun flatBackup(b: BackupFile): Map<String, Any> {
        val out = HashMap<String, Any>()
        flat(b.datastore, "d:", out)
        flat(b.settings, "s:", out)
        return out
    }

    private fun put(ed: SharedPreferences.Editor, key: String, v: Any) {
        when (v) {
            is Boolean -> ed.putBoolean(key, v)
            is Int -> ed.putInt(key, v)
            is Long -> ed.putLong(key, v)
            is Float -> ed.putFloat(key, v)
            is String -> ed.putString(key, v)
            is Set<*> -> ed.putStringSet(key, v.map { it.toString() }.toSet())
        }
    }

    /**
     * Allinea le date locali alla realta': chiavi nuove o cambiate prendono la data di adesso,
     * chiavi sparite diventano "tombstone". Al primissimo avvio le chiavi gia' presenti ricevono
     * una data "vecchia" (1), cosi' le modifiche vere vincono sempre sui dati preesistenti.
     */
    fun refresh(ctx: Context, now: Long): MutableMap<String, LocalMeta> {
        val sp = store(ctx)
        val meta = HashMap<String, LocalMeta>()
        sp.all.forEach { (k, v) ->
            if (k.startsWith("d:") || k.startsWith("s:")) dec(v as? String)?.let { meta[k] = it }
        }
        val first = !sp.getBoolean("baseline_done", false)
        val stampT = maxOf(now, sp.getLong("max_seen", 0L) + 1)
        val cur = present(ctx)
        val ed = sp.edit()
        for ((k, v) in cur) {
            val vh = vhash(v)
            val m = meta[k]
            if (m == null || m.del || m.vh != vh) {
                val nm = LocalMeta(if (first) 1L else stampT, vh, false)
                meta[k] = nm
                ed.putString(k, enc(nm))
            }
        }
        for ((k, m) in meta.toList()) {
            if (k in cur) continue
            if (m.del) {
                if (m.t > 1 && now - m.t > TOMBSTONE_TTL) {
                    meta.remove(k)
                    ed.remove(k)
                }
                continue
            }
            val nm = LocalMeta(stampT, "", true)
            meta[k] = nm
            ed.putString(k, enc(nm))
        }
        if (first) ed.putBoolean("baseline_done", true)
        ed.apply()
        return meta
    }

    /** Unisce il backup remoto ai dati locali, chiave per chiave. */
    fun merge(ctx: Context, env: SyncEnvelope, restoreCats: Collection<String>): MergeResult {
        val local = refresh(ctx, System.currentTimeMillis())
        val cur = present(ctx)
        val rFlat = flatBackup(env.backup)
        val rMeta = env.meta ?: emptyMap()
        val keys = HashSet<String>().apply { addAll(rFlat.keys); addAll(rMeta.keys) }

        val sp = store(ctx)
        val dataEd = ctx.getSharedPrefs().edit()
        val setEd = ctx.getDefaultSharedPrefs().edit()
        val metaEd = sp.edit()
        var maxSeen = sp.getLong("max_seen", 0L)
        var applied = false
        var needsUpload = false

        for (k in keys) {
            if (!(k.startsWith("d:") || k.startsWith("s:"))) continue
            val name = k.substring(2)
            if (!name.isResume()) continue // mai toccare le chiavi escluse (token, account...)
            if (catOfFull(k) !in restoreCats) continue
            val rm = rMeta[k]
            val rDel = rm?.x == true
            val rT = rm?.t ?: env.updatedAt
            maxSeen = maxOf(maxSeen, rT)
            val rv = rFlat[k]
            if (!rDel && rv == null) continue

            val lT = local[k]?.t ?: 0L
            val lv = cur[k]
            val win = when {
                rT > lT -> true
                rT < lT -> false
                else -> !rDel && lv != null && canon(rv) > canon(lv) // pareggio: scelta uguale su tutti i dispositivi
            }
            if (!win) {
                if (rT < lT) needsUpload = true
                continue
            }
            val ed = if (k.startsWith("s:")) setEd else dataEd
            if (rDel) {
                if (lv != null) {
                    ed.remove(name)
                    applied = true
                }
                metaEd.putString(k, enc(LocalMeta(rT, "", true)))
            } else {
                if (lv == null || canon(lv) != canon(rv)) {
                    put(ed, name, rv!!)
                    applied = true
                }
                metaEd.putString(k, enc(LocalMeta(rT, vhash(rv), false)))
            }
        }
        // modifiche locali piu' recenti dello snapshot remoto che il remoto non conosce
        for ((k, m) in local) {
            if (k in keys || m.del) continue
            if (m.t > env.updatedAt && catOfFull(k) in BackupUtils.enabledCats(true)) needsUpload = true
        }

        dataEd.apply()
        setEd.apply()
        metaEd.putLong("max_seen", maxSeen)
        metaEd.apply()
        return MergeResult(applied, needsUpload)
    }
}
