package app.domik.integrations.emodul

/** Rodzaje tekstów zastępczych, gdy eModul nie podaje nazwy (zasoby `strings.xml` w warstwie Androida). */
enum class EmodulFallback {
    FLAME_SENSOR, FIRING_UP, STATUS, FAN, BUILT_IN_VALVE, VALVE_N, FUEL, DISINFECTION, CLOCK, CONTROLLER,
    PERIPHERAL, GROUP, OUTPUT_N, PUMP_N, SENSOR_N, TILE_N,
}

/** Teksty zastępcze; [number] to numer zaworu albo `id` kafelka (dla rodzajów `*_N`), inaczej ignorowany. */
fun interface EmodulFallbacks {
    fun text(kind: EmodulFallback, number: Int): String
}

/**
 * Rozwiązywanie `txtId` z (podzbioru) słownika eModul oraz nazw zastępczych. Bez plików i bez Androida;
 * zapis podzbioru na dysku robi `EmodulStore`.
 */
class EmodulTexts(private val dictionary: Map<Int, String>, private val fallbacks: EmodulFallbacks) {
    val size: Int get() = dictionary.size

    /** Tekst ze słownika albo `null` (id ≤ 0 lub brak w słowniku). */
    fun text(id: TxtId): String? = if (id.isPresent) dictionary[id.value]?.takeIf { it.isNotBlank() } else null

    fun has(id: Int): Boolean = id > 0 && dictionary.containsKey(id)

    /** Nazwa kafelka: tekst ze słownika albo tekst zastępczy wg §4.4 (dla zaworów z menu – patrz [EmodulLinks]). */
    fun title(tile: EmodulTile): String = when (tile) {
        is EmodulTile.TemperatureSensor -> text(tile.name) ?: fallbacks.text(EmodulFallback.SENSOR_N, tile.id)
        is EmodulTile.FireSensor -> fallbacks.text(EmodulFallback.FLAME_SENSOR, 0)
        is EmodulTile.FiringUp -> fallbacks.text(EmodulFallback.FIRING_UP, 0)
        is EmodulTile.Widgets -> fallbacks.text(EmodulFallback.STATUS, 0)
        is EmodulTile.Relay -> text(tile.name) ?: fallbacks.text(EmodulFallback.OUTPUT_N, tile.id)
        is EmodulTile.Pump -> text(tile.name) ?: fallbacks.text(EmodulFallback.PUMP_N, tile.id)
        is EmodulTile.Fan -> fallbacks.text(EmodulFallback.FAN, 0)
        is EmodulTile.Valve ->
            if (tile.number == 0) fallbacks.text(EmodulFallback.BUILT_IN_VALVE, 0)
            else fallbacks.text(EmodulFallback.VALVE_N, tile.number)
        is EmodulTile.Fuel -> fallbacks.text(EmodulFallback.FUEL, 0)
        is EmodulTile.Disinfection -> text(tile.name) ?: fallbacks.text(EmodulFallback.DISINFECTION, 0)
        is EmodulTile.Text -> text(tile.header) ?: fallbacks.text(EmodulFallback.TILE_N, tile.id)
        is EmodulTile.ControllerDate -> fallbacks.text(EmodulFallback.CLOCK, 0)
        is EmodulTile.Version -> fallbacks.text(EmodulFallback.CONTROLLER, 0)
        is EmodulTile.PeripheralVersion -> text(tile.name) ?: fallbacks.text(EmodulFallback.PERIPHERAL, 0)
        is EmodulTile.Container -> text(tile.name) ?: fallbacks.text(EmodulFallback.GROUP, 0)
        is EmodulTile.Unknown -> tile.description.trim().ifEmpty { fallbacks.text(EmodulFallback.TILE_N, tile.id) }
    }

    companion object {
        private const val MAX_DEPTH = 32

        /**
         * Wszystkie id słownika potrzebne do pokazania [snapshot]: nazwy, nagłówki i stany kafelków, teksty
         * widżetów oraz – dla kafelków z `menuId` wskazującym element [menus] – tekst elementu, jego jednostki
         * i wszystkich przodków (nazwy grup zaworów, §4.3).
         */
        fun requiredIds(snapshot: EmodulSnapshot, menus: Map<MenuType, EmodulMenu>): Set<Int> {
            val ids = HashSet<Int>()
            fun add(id: TxtId) { if (id.isPresent) ids += id.value }
            for (tile in snapshot.tiles) {
                when (tile) {
                    is EmodulTile.TemperatureSensor -> add(tile.name)
                    is EmodulTile.Widgets -> listOfNotNull(tile.w1, tile.w2).forEach { add(it.txtId) }
                    is EmodulTile.Relay -> add(tile.name)
                    is EmodulTile.Pump -> add(tile.name)
                    is EmodulTile.Disinfection -> add(tile.name)
                    is EmodulTile.Text -> { add(tile.header); add(tile.status) }
                    is EmodulTile.PeripheralVersion -> add(tile.name)
                    is EmodulTile.Container -> add(tile.name)
                    else -> Unit
                }
                if (tile.menuId > 0) {
                    for (menu in menus.values) {
                        var item = menu.items[tile.menuId]
                        var depth = 0
                        while (item != null && depth++ < MAX_DEPTH) {
                            add(item.txtId)
                            add(item.unitTxtId)
                            item = if (item.id == 0 || item.parentId == item.id) null else menu.items[item.parentId]
                        }
                    }
                }
            }
            return ids
        }

        /** Podzbiór [full] ograniczony do [ids]. */
        fun subset(full: Map<Int, String>, ids: Set<Int>): Map<Int, String> =
            ids.mapNotNull { id -> full[id]?.let { id to it } }.toMap()
    }
}
