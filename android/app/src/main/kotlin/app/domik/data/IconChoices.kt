package app.domik.data

/**
 * Ikony wybrane ręcznie przez użytkownika dla kafelków-przełączników (jedna para: kafelek → ikona); kafelek bez
 * wpisu ma ikonę dobraną automatycznie ([LightIcons.automatic]). Niezmienne; zmiany zwracają nowy obiekt.
 *
 * Zapis ([encode] / [decode]) jest wersjonowany: `v1;s:1:5=sconce;s:2:0=socket` (id kafelka jak w układzie pulpitu,
 * [LightIcon.key]). Uszkodzony wpis albo wpis z nieznaną ikoną/kafelkiem jest pomijany, zapis z innej wersji
 * aplikacji jest ignorowany w całości – wtedy obowiązuje dobór automatyczny.
 */
data class IconChoices(val byTile: Map<TileId.Switch, LightIcon> = emptyMap()) {
    val isEmpty: Boolean get() = byTile.isEmpty()

    operator fun get(id: TileId): LightIcon? = byTile[id]

    /** Wybór ikony [icon] dla kafelka [id]; `null` przywraca dobór automatyczny. */
    fun with(id: TileId.Switch, icon: LightIcon?): IconChoices =
        if (icon == null) copy(byTile = byTile - id) else copy(byTile = byTile + (id to icon))

    /** Zapis do ustawień; wpisy w stałej kolejności (moduł, wyjście), więc równe wybory dają równy tekst. */
    fun encode(): String = buildString {
        append("v").append(VERSION)
        byTile.entries.sortedWith(compareBy({ it.key.module }, { it.key.relay })).forEach {
            append(';').append(it.key.code).append('=').append(it.value.key)
        }
    }

    companion object {
        const val VERSION = 1

        val EMPTY = IconChoices()

        /** Odczyt zapisu z [encode]; brak, uszkodzenie albo inna wersja zapisu = [EMPTY]. */
        fun decode(text: String?): IconChoices {
            if (text.isNullOrBlank()) return EMPTY
            val records = text.trim().split(';')
            if (records[0] != "v$VERSION") return EMPTY
            val result = LinkedHashMap<TileId.Switch, LightIcon>()
            for (record in records.drop(1)) {
                val parts = record.split('=')
                if (parts.size != 2) continue
                val id = TileId.decode(parts[0].trim()) as? TileId.Switch ?: continue
                val icon = LightIcon.fromKey(parts[1].trim()) ?: continue
                result.putIfAbsent(id, icon)
            }
            return IconChoices(result)
        }
    }
}
