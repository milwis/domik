package app.domik.integrations.emodul

import app.domik.data.EmodulCatalog
import app.domik.data.EmodulTileSeed
import app.domik.data.TileId
import app.domik.data.TileSize

/**
 * Kafelki eModul modułu i ich wybór (§6.4a, §7.2). Czyste funkcje: z danych modułu ([EmodulModuleData]) powstaje lista
 * wszystkich kafelków do wyboru ([candidates]), domyślny wybór kreatora ([defaultSelection]) i lista kafelków do
 * uzgodnienia układu pulpitu ([of] – tylko wybrane).
 */
object EmodulSeeds {
    /** Najwięcej kafelków jednego modułu zaznaczonych domyślnie w kreatorze. */
    const val MAX_VISIBLE = 8

    /** Kafelek modułu, który można wybrać: [id], rozmiar domyślny na pulpicie i czy kreator zaznacza go domyślnie. */
    data class Candidate(val id: TileId.EmodulBase, val defaultSize: TileSize, val preselected: Boolean)

    /**
     * Wszystkie kafelki i strefy modułu obecne w migawce, w kolejności eModul (`tilesOrder`, potem `orderId`, potem
     * `id`; kafelki przed strefami). Zaznaczone domyślnie: `visibility` i typ 6, 23, 24, 1, 31 albo 40 oraz widoczna
     * strefa, najwyżej [MAX_VISIBLE]. Rozmiar: zawory i strefy L, typy 6 i 40 M, reszta S.
     */
    fun candidates(module: EmodulModuleData): List<Candidate> {
        val snapshot = module.snapshot ?: return emptyList()
        val position = HashMap<Int, Int>()
        snapshot.order.forEachIndexed { index, id -> position.putIfAbsent(id, index) }
        val tiles = snapshot.tiles.sortedWith(
            compareBy<EmodulTile>({ position[it.id] ?: Int.MAX_VALUE }, { it.orderId ?: Int.MAX_VALUE }, { it.id }),
        )
        val raw = tiles.map { Triple(TileId.Emodul(module.slot, it.id) as TileId.EmodulBase, it.visible && it.isDefaultVisibleKind(), sizeOf(it)) } +
            snapshot.zones.sortedBy { it.id }.map { Triple(TileId.EmodulZone(module.slot, it.id) as TileId.EmodulBase, it.visible, TileSize.L) }
        var chosen = 0
        return raw.map { (id, wants, size) ->
            val pick = wants && chosen < MAX_VISIBLE
            if (pick) chosen++
            Candidate(id, size, pick)
        }
    }

    /** Domyślny wybór kreatora (dawne reguły widoczności z §7.2). */
    fun defaultSelection(module: EmodulModuleData): Set<TileId.EmodulBase> =
        candidates(module).filter { it.preselected }.mapTo(LinkedHashSet()) { it.id }

    /**
     * Kafelki modułu **wybrane przez użytkownika** ([EmodulModuleData.selection]) i obecne w migawce, w kolejności
     * eModul – to one trafiają na pulpit (wszystkie widoczne). Bez migawki – pusta lista.
     */
    fun of(module: EmodulModuleData): List<EmodulTileSeed> =
        candidates(module).filter { it.id in module.selection.selected }.map { EmodulTileSeed(it.id, it.defaultSize) }

    private fun EmodulTile.isDefaultVisibleKind(): Boolean = when (this) {
        is EmodulTile.Widgets, is EmodulTile.Valve, is EmodulTile.TemperatureSensor, is EmodulTile.Fuel, is EmodulTile.Text -> true
        else -> false
    }

    private fun sizeOf(tile: EmodulTile): TileSize = when (tile) {
        is EmodulTile.Valve -> TileSize.L
        is EmodulTile.Widgets, is EmodulTile.Text -> TileSize.M
        else -> TileSize.S
    }
}

/**
 * Katalog wybranych kafelków eModul dla uzgadniania układu (§7.3). Tylko brak integracji (nigdy nie skonfigurowana
 * albo jawnie usunięta przez [EmodulModel.removeKey]) daje pusty katalog – wtedy kafelki znikają z układu. Wszystkie stany
 * przejściowe i błędy – ładowanie, **klucz odrzucony** (401/403), błąd bez danych – dają `null`: układ użytkownika
 * zostaje nietknięty, a kafelki pokazują ostatnie dane albo komunikat. Gotowe – włączone sloty z listą kafelków
 * (`null`, gdy slot nie ma jeszcze migawki); wyłączony moduł nie ma wpisu w mapie, więc jego kafelki są usuwane.
 */
fun EmodulState.toCatalog(): EmodulCatalog? = when (this) {
    EmodulState.NotConfigured -> EmodulCatalog(emptyMap())
    EmodulState.Loading, EmodulState.KeyRejected, is EmodulState.Failed -> null
    is EmodulState.Ready -> EmodulCatalog(modules.filter { it.enabled }.associate { it.slot to if (it.snapshot != null) EmodulSeeds.of(it) else null })
}
