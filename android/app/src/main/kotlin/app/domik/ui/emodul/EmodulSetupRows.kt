package app.domik.ui.emodul

import app.domik.data.TileId
import app.domik.integrations.emodul.EmodulModuleData
import app.domik.integrations.emodul.EmodulSeeds
import app.domik.ui.dashboard.EmodulIcon
import app.domik.ui.dashboard.EmodulTileMapper
import app.domik.ui.dashboard.EmodulUiTexts
import java.util.Locale

/**
 * Wiersz listy wyboru danych eModul (kreator i „Ustawienia eModul”): [title] to nazwa kafelka, [value] – podgląd
 * bieżącej wartości (`65,0° · zadana 65°`, `78 % · czas pracy ≈ 59 h`) albo `null`, [isNew] – kafelek pojawił się w
 * sterowniku po ostatnim zapisie wyboru (oznaczany „nowe”, domyślnie odznaczony).
 */
data class EmodulSetupRow(
    val id: TileId.EmodulBase,
    val title: String,
    val value: String?,
    val icon: EmodulIcon,
    val isNew: Boolean,
)

/** Jeden moduł listy wyboru: nagłówek ([name], [version]) i jego wiersze w kolejności eModul. */
data class EmodulSetupSection(val slot: Int, val name: String, val version: String, val rows: List<EmodulSetupRow>)

/**
 * Jedyne źródło prawdy o zaznaczeniu na liście wyboru (kreator i „Ustawienia eModul”): to, co pokazują pola wyboru,
 * i to, co trafia do zapisu ([effective]), pochodzi z tego samego obiektu. Niezmienny; każda zmiana zwraca nowy stan,
 * a UI woła ją w chwili zdarzenia na **aktualnym** stanie (nie na kopii z poprzedniej kompozycji), więc szybkie
 * kolejne dotknięcia niczego nie gubią. Wstępne zaznaczenie ([initialChecked]) jest liczone raz na moduł –
 * [merge] (odświeżenie danych) go nie powtarza i nie nadpisuje zmian użytkownika.
 */
data class EmodulPickState(
    val checked: Set<TileId.EmodulBase> = emptySet(),
    private val initializedSlots: Set<Int> = emptySet(),
) {
    /** Zaznacza / odznacza [id] (tylko ten jeden wiersz). */
    fun set(id: TileId.EmodulBase, on: Boolean): EmodulPickState = copy(checked = if (on) checked + id else checked - id)

    /** „Zaznacz wszystkie” – wszystkie wiersze z [sections]. */
    fun selectAll(sections: List<EmodulSetupSection>): EmodulPickState = copy(checked = checked + EmodulSetupRows.allIds(sections))

    /** „Odznacz wszystkie”. */
    fun selectNone(): EmodulPickState = copy(checked = emptySet())

    /**
     * Dołącza wstępne zaznaczenie **tylko** modułów, których jeszcze nie było w stanie; moduły już znane (po
     * odświeżeniu danych) zostają z wyborem użytkownika.
     */
    fun merge(modules: List<EmodulModuleData>): EmodulPickState {
        val fresh = modules.filter { it.slot !in initializedSlots }
        if (fresh.isEmpty()) return this
        return EmodulPickState(checked + EmodulSetupRows.initialChecked(fresh), initializedSlots + fresh.map { it.slot })
    }

    /** Zaznaczenie do zapisu: dokładnie to, co widać na liście [sections] (bez kafelków, które zniknęły). */
    fun effective(sections: List<EmodulSetupSection>): Set<TileId.EmodulBase> {
        val visible = EmodulSetupRows.allIds(sections)
        return checked.filterTo(LinkedHashSet()) { it in visible }
    }

    companion object {
        /** Stan początkowy listy [modules] (wstępne zaznaczenie wg [EmodulSetupRows.initialChecked]). */
        fun initial(modules: List<EmodulModuleData>): EmodulPickState = EmodulPickState().merge(modules)
    }
}

/**
 * Czysta logika listy wyboru danych eModul (§6.4a): wiersze z podglądem wartości (ten sam mapper co kafelki
 * pulpitu), wstępne zaznaczenie i operacje „Zaznacz / Odznacz wszystkie”. Tylko odczyt – lista nie zmienia niczego
 * w sterowniku.
 */
object EmodulSetupRows {
    /** Sekcje modułów z migawką; moduły bez danych są pomijane. */
    fun sections(modules: List<EmodulModuleData>, locale: Locale, texts: EmodulUiTexts): List<EmodulSetupSection> =
        modules.filter { it.snapshot != null }.map { module ->
            val ui = EmodulTileMapper.map(module, multiModule = false, locale = locale, ui = texts)
            val known = module.selection.known
            EmodulSetupSection(
                slot = module.slot,
                name = module.info.name,
                version = module.info.version,
                rows = EmodulSeeds.candidates(module).map { candidate ->
                    val tile = ui[candidate.id]
                    EmodulSetupRow(
                        id = candidate.id,
                        title = tile?.title.orEmpty(),
                        value = tile?.let(::preview),
                        icon = tile?.icon ?: EmodulIcon.GENERIC,
                        isNew = known.isNotEmpty() && candidate.id !in known,
                    )
                },
            )
        }

    /**
     * Wstępne zaznaczenie: zapisany wybór modułu (tylko kafelki wciąż obecne) albo – gdy wybór nie był jeszcze zapisany
     * (kreator) – domyślne reguły widoczności ([EmodulSeeds.defaultSelection]). Nowe kafelki są odznaczone.
     */
    fun initialChecked(modules: List<EmodulModuleData>): Set<TileId.EmodulBase> =
        modules.flatMapTo(LinkedHashSet()) { module ->
            if (module.selection.known.isEmpty()) {
                EmodulSeeds.defaultSelection(module)
            } else {
                val present = EmodulSeeds.candidates(module).map { it.id }.toSet()
                module.selection.selected.filter { it in present }
            }
        }

    /** Wszystkie identyfikatory z [sections]. */
    fun allIds(sections: List<EmodulSetupSection>): Set<TileId.EmodulBase> =
        sections.flatMapTo(LinkedHashSet()) { section -> section.rows.map { it.id } }

    /** Podgląd wartości w wierszu: wartość główna i pierwszy szczegół (np. „zadana 65°”). */
    private fun preview(ui: app.domik.ui.dashboard.EmodulTileUi): String? =
        listOfNotNull(ui.primary, ui.details.firstOrNull()?.let { "${it.first} ${it.second}" })
            .joinToString(" · ").takeIf { it.isNotBlank() }
}
