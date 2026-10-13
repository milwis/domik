package app.domik.ui.emodul

import app.domik.data.TileId
import app.domik.integrations.emodul.DemoEmodulApi
import app.domik.integrations.emodul.EmodulFixtures
import app.domik.integrations.emodul.EmodulKey
import app.domik.integrations.emodul.EmodulModuleData
import app.domik.integrations.emodul.EmodulModuleInfo
import app.domik.integrations.emodul.EmodulParser
import app.domik.integrations.emodul.EmodulSeeds
import app.domik.integrations.emodul.EmodulSelection
import app.domik.integrations.emodul.EmodulSnapshot
import app.domik.integrations.emodul.EmodulTexts
import app.domik.ui.dashboard.EmodulUiTexts
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lista wyboru danych eModul (kreator i ustawienia): wiersze, podgląd wartości, wstępne zaznaczenie, „nowe”. */
class EmodulSetupRowsTest {
    private val pl = Locale.forLanguageTag("pl-PL")
    private val texts = EmodulUiTexts { label, args -> if (args.isEmpty()) label.name else "${label.name}${args.toList()}" }
    private val api = DemoEmodulApi { 1_760_000_000_000L }
    private val key = EmodulKey("demo.demo.demo-demo-demo-demo", 1L)
    private val info = EmodulModuleInfo(DemoEmodulApi.UDID, "Kocioł na pellet (demo)", "pellet", "Demo (v.1.0.0)", online = true)
    private val demo: EmodulSnapshot = EmodulParser.parseModule(api.module(key, DemoEmodulApi.UDID))
    private val demoTexts = EmodulTexts(EmodulParser.parseI18n(api.i18n(key, "pl")), EmodulFixtures.TestFallbacks)

    private fun module(selection: EmodulSelection = EmodulSelection.EMPTY, slot: Int = 1) = EmodulModuleData(
        slot, info, enabled = true, snapshot = demo,
        menus = mapOf(
            app.domik.integrations.emodul.MenuType.MU to EmodulParser.parseMenu(api.menu(key, DemoEmodulApi.UDID, app.domik.integrations.emodul.MenuType.MU), app.domik.integrations.emodul.MenuType.MU),
            app.domik.integrations.emodul.MenuType.MI to EmodulParser.parseMenu(api.menu(key, DemoEmodulApi.UDID, app.domik.integrations.emodul.MenuType.MI), app.domik.integrations.emodul.MenuType.MI),
        ),
        texts = demoTexts, fetchedAtMillis = 1L, selection = selection,
    )

    private fun tile(id: Int) = TileId.Emodul(1, id)

    @Test
    fun `sekcja ma nazwe i wersje modulu oraz wiersz na kazdy kafelek w kolejnosci eModul`() {
        val section = EmodulSetupRows.sections(listOf(module()), pl, texts).single()
        assertEquals("Kocioł na pellet (demo)", section.name)
        assertEquals("Demo (v.1.0.0)", section.version)
        assertEquals(18, section.rows.size)
        assertEquals((1..18).map(::tile), section.rows.map { it.id })
    }

    @Test
    fun `wiersz pokazuje nazwe i biezaca wartosc z zadana`() {
        val rows = EmodulSetupRows.sections(listOf(module()), pl, texts).single().rows.associateBy { it.id }
        val boiler = rows.getValue(tile(2))
        assertTrue(boiler.title.isNotBlank())
        // wartość aktualna kafelka typu 6 i pierwszy szczegół (zadana)
        assertTrue(boiler.value!!.contains("°"), boiler.value)
        assertTrue(boiler.value!!.contains("SETPOINT"), boiler.value)
        val fuel = rows.getValue(tile(7))
        assertTrue(fuel.value!!.contains("%"), fuel.value)
    }

    @Test
    fun `moduly bez migawki sa pomijane`() {
        val empty = module().copy(snapshot = null)
        assertTrue(EmodulSetupRows.sections(listOf(empty), pl, texts).isEmpty())
    }

    @Test
    fun `wstepne zaznaczenie bez zapisanego wyboru to domyslne reguly (typy 6, 23, 24, 1, 31, 40, najwyzej osiem)`() {
        val checked = EmodulSetupRows.initialChecked(listOf(module()))
        assertEquals((1..7).map(::tile).toSet(), checked)
    }

    @Test
    fun `wstepne zaznaczenie z zapisanym wyborem to ten wybor ograniczony do obecnych kafelkow`() {
        val selection = EmodulSelection(
            selected = setOf(tile(2), tile(9), tile(9999)),
            known = (1..18).map(::tile).toSet(),
        )
        assertEquals(setOf(tile(2), tile(9)), EmodulSetupRows.initialChecked(listOf(module(selection))))
    }

    @Test
    fun `nowe kafelki - spoza znanych - sa odznaczone i oznaczone, znikle nie wystepuja na liscie`() {
        val known = (1..16).map(::tile).toSet() + tile(5000) // 17 i 18 są nowe, 5000 zniknął ze sterownika
        val selection = EmodulSelection(selected = setOf(tile(1), tile(5000)), known = known)
        val data = module(selection)

        val rows = EmodulSetupRows.sections(listOf(data), pl, texts).single().rows
        assertEquals(listOf(tile(17), tile(18)), rows.filter { it.isNew }.map { it.id })
        assertTrue(rows.none { it.id == tile(5000) })
        val checked = EmodulSetupRows.initialChecked(listOf(data))
        assertEquals(setOf(tile(1)), checked)
        assertFalse(tile(17) in checked)
    }

    @Test
    fun `bez zapisanego wyboru (kreator) nic nie jest oznaczone jako nowe`() {
        assertTrue(EmodulSetupRows.sections(listOf(module()), pl, texts).single().rows.none { it.isNew })
    }

    @Test
    fun `allIds zbiera wszystkie wiersze wszystkich modulow`() {
        val sections = EmodulSetupRows.sections(listOf(module(slot = 1), module(slot = 2)), pl, texts)
        assertEquals(36, EmodulSetupRows.allIds(sections).size)
        assertEquals(EmodulSeeds.candidates(module(slot = 2)).map { it.id }.toSet(), sections[1].rows.map { it.id }.toSet())
    }

    // EmodulPickState: jedno źródło prawdy o zaznaczeniu

    @Test
    fun `stan wyboru - wstepne zaznaczenie liczone raz, odznaczenie wstepnie zaznaczonego trafia do zapisu`() {
        val modules = listOf(module())
        val sections = EmodulSetupRows.sections(modules, pl, texts)
        val pick = EmodulPickState.initial(modules)
        assertEquals((1..7).map(::tile).toSet(), pick.effective(sections))

        val toggled = pick.set(tile(3), on = false)
        assertFalse(tile(3) in toggled.effective(sections))
        assertEquals((1..7).map(::tile).toSet() - tile(3), toggled.effective(sections))
        // tyle samo, co pokazują pola wyboru (wiersz zaznaczony <=> id w zbiorze do zapisu)
        val shown = sections.single().rows.filter { it.id in toggled.checked }.map { it.id }.toSet()
        assertEquals(shown, toggled.effective(sections))
    }

    @Test
    fun `stan wyboru - odswiezenie danych nie cofa zmian uzytkownika, a nowy modul dostaje wstepne zaznaczenie`() {
        val first = listOf(module(slot = 1))
        val pick = EmodulPickState.initial(first).set(tile(3), on = false).set(tile(10), on = true)

        // odświeżenie tego samego modułu (nowa lista, te same sloty) niczego nie zmienia
        val refreshed = pick.merge(listOf(module(slot = 1)))
        assertEquals(pick.checked, refreshed.checked)
        assertFalse(tile(3) in refreshed.checked)
        assertTrue(tile(10) in refreshed.checked)

        // pojawia się drugi moduł: dostaje domyślne zaznaczenie, pierwszy zostaje z wyborem użytkownika
        val both = refreshed.merge(listOf(module(slot = 1), module(slot = 2)))
        assertEquals(refreshed.checked + (1..7).map { TileId.Emodul(2, it) }, both.checked)
    }

    @Test
    fun `stan wyboru - zaznaczenie jednego wiersza nie rusza sasiednich, wszystkie i zadne dzialaja na liscie`() {
        val modules = listOf(module())
        val sections = EmodulSetupRows.sections(modules, pl, texts)
        val none = EmodulPickState.initial(modules).selectNone()
        assertTrue(none.effective(sections).isEmpty())
        assertEquals(setOf(tile(12)), none.set(tile(12), on = true).effective(sections))
        assertEquals(EmodulSetupRows.allIds(sections), none.selectAll(sections).effective(sections))
        // dwa szybkie dotknięcia po kolei działają na bieżącym stanie, żadne nie ginie
        assertEquals(setOf(tile(4), tile(5)), none.set(tile(4), true).set(tile(5), true).effective(sections))
    }

    @Test
    fun `stan wyboru - zapis pomija kafelki, ktorych juz nie ma na liscie`() {
        val modules = listOf(module())
        val sections = EmodulSetupRows.sections(modules, pl, texts)
        val pick = EmodulPickState.initial(modules).set(tile(9999), on = true)
        assertFalse(tile(9999) in pick.effective(sections))
    }
}
