package app.away

/** Stable, collision-free LazyColumn keys for digest rows. */
object DigestKeys {
    fun of(items: List<DigestItem>): List<String> {
        val seen = HashMap<String, Int>()
        return items.map { d ->
            val base = "${d.time}_${d.pkg.ifBlank { d.app }}_${d.title.hashCode()}_${d.text.hashCode()}"
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            if (n == 1) base else "$base#$n"
        }
    }
}
