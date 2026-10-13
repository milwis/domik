package app.domik.integrations.emodul

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EmodulStoreTest {
    @TempDir
    lateinit var dir: File

    private fun store() = EmodulStore(File(dir, "emodul"))

    @Test
    fun `brak plikow to brak wpisow`() {
        val store = store()
        assertNull(store.readModule(1))
        assertNull(store.readMenu(1, MenuType.MU))
        assertNull(store.readI18n("pl"))
    }

    @Test
    fun `surowa odpowiedz modulu przezywa zapis i odczyt razem z czasem`() {
        store().writeModule(1, StoredResponse(1_760_000_000_000L, EmodulFixtures.moduleJson))
        val read = store().readModule(1)
        assertNotNull(read)
        assertEquals(1_760_000_000_000L, read!!.fetchedAtMillis)
        assertEquals(EmodulFixtures.moduleJson, read.json)
        // odpowiedź z pliku da się sparsować tak jak świeżą
        assertEquals(EmodulFixtures.snapshot.tiles.size, EmodulParser.parseModule(read.json).tiles.size)
    }

    @Test
    fun `pliki maja nazwy ze specyfikacji i sloty sa niezalezne`() {
        val store = store()
        store.writeModule(1, StoredResponse(1L, "{\"a\":1}"))
        store.writeModule(2, StoredResponse(2L, "{\"a\":2}"))
        store.writeMenu(1, MenuType.MU, StoredResponse(3L, EmodulFixtures.menuUserJson))
        store.writeMenu(1, MenuType.MI, StoredResponse(4L, EmodulFixtures.menuInstallerJson))
        store.writeI18n("pl", 5L, mapOf(1 to "a"))
        val names = File(dir, "emodul").list()!!.toSet()
        assertEquals(setOf("module_1.json", "module_2.json", "menu_1_MU.json", "menu_1_MI.json", "i18n_pl.txt"), names)
        assertEquals(2L, store.readModule(2)!!.fetchedAtMillis)
        assertEquals(3L, store.readMenu(1, MenuType.MU)!!.fetchedAtMillis)
        assertEquals(EmodulFixtures.menuInstallerJson, store.readMenu(1, MenuType.MI)!!.json)
        assertNull(store.readMenu(2, MenuType.MU))
    }

    @Test
    fun `nadpisanie zastepuje poprzednia wersje i nie zostawia pliku tymczasowego`() {
        val store = store()
        store.writeModule(1, StoredResponse(1L, "{\"v\":1}"))
        store.writeModule(1, StoredResponse(2L, "{\"v\":2}"))
        assertEquals("{\"v\":2}", store.readModule(1)!!.json)
        assertTrue(File(dir, "emodul").list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `uszkodzony naglowek lub brak pliku daje null`() {
        val store = store()
        val folder = File(dir, "emodul").apply { mkdirs() }
        File(folder, "module_1.json").writeText("{\"bez\":\"naglowka\"}")
        File(folder, "module_2.json").writeText("v1 nie-liczba\n{}")
        File(folder, "module_3.json").writeText("v9 5\n{}")
        File(folder, "module_4.json").writeText("")
        for (slot in 1..5) assertNull(store.readModule(slot), "slot $slot")
    }

    @Test
    fun `slot musi byc dodatni`() {
        assertThrows(IllegalArgumentException::class.java) { store().readModule(0) }
        assertThrows(IllegalArgumentException::class.java) { store().writeMenu(-1, MenuType.MU, StoredResponse(1L, "{}")) }
    }

    @Test
    fun `podzbior slownika z tabulatorami nowymi liniami i ukosnikami wraca bez zmian`() {
        val entries = mapOf(
            795 to "Temperatura zewnętrzna",
            1 to "a\tb",
            2 to "linia1\nlinia2\r\n",
            3 to "ukośnik \\ i \\n dosłownie",
            4 to "",
            1050 to "Zażółć gęślą jaźń",
        )
        store().writeI18n("pl", 1_760_000_000_000L, entries)
        val read = store().readI18n("pl")
        assertNotNull(read)
        assertEquals("pl", read!!.language)
        assertEquals(1_760_000_000_000L, read.fetchedAtMillis)
        assertEquals(entries, read.entries)
    }

    @Test
    fun `plik slownika ma naglowek v1 i linie id tabulator tekst`() {
        store().writeI18n("en", 42L, mapOf(2 to "b\tc", 1 to "a"))
        assertEquals("v1 42\n1\ta\n2\tb\\tc\n", File(dir, "emodul/i18n_en.txt").readText())
    }

    @Test
    fun `jezyk spoza listy zapisuje i czyta pl a slowniki jezykow sa osobne`() {
        val store = store()
        store.writeI18n("../../etc/passwd", 1L, mapOf(1 to "pl"))
        store.writeI18n("en", 2L, mapOf(1 to "en"))
        assertEquals(setOf("i18n_pl.txt", "i18n_en.txt"), File(dir, "emodul").list()!!.toSet())
        assertEquals("pl", store.readI18n("xx")!!.entries[1])
        assertEquals("en", store.readI18n("EN")!!.entries[1])
    }

    @Test
    fun `uszkodzony plik slownika daje null a nie czesciowy slownik`() {
        val folder = File(dir, "emodul").apply { mkdirs() }
        val broken = listOf(
            "v1 5\n1\ta\nzepsute\n",
            "v1 5\nx\ta\n",
            "v1 5\n1\tzly\\qescape\n",
            "v1 5\n1\tbez konca linii",
            "to nie jest plik\n",
        )
        for (content in broken) {
            File(folder, "i18n_pl.txt").writeText(content)
            assertNull(store().readI18n("pl"), content)
        }
    }

    @Test
    fun `pusty podzbior jest poprawnym slownikiem`() {
        store().writeI18n("pl", 7L, emptyMap())
        val read = store().readI18n("pl")
        assertNotNull(read)
        assertTrue(read!!.entries.isEmpty())
        assertEquals(7L, read.fetchedAtMillis)
    }

    @Test
    fun `zapisany podzbior pozwala zbudowac EmodulTexts ze wszystkimi potrzebnymi nazwami`() {
        val needed = EmodulTexts.requiredIds(EmodulFixtures.snapshot, EmodulFixtures.menus)
        val full = EmodulParser.parseI18n(EmodulFixtures.i18nJson)
        store().writeI18n("pl", 1L, EmodulTexts.subset(full, needed))
        val texts = EmodulTexts(store().readI18n("pl")!!.entries, EmodulFixtures.TestFallbacks)
        assertTrue(needed.all { texts.has(it) })
        assertEquals("Temperatura zewnętrzna", texts.text(TxtId(795)))
        assertEquals("Podajnik", texts.title(EmodulFixtures.tile<EmodulTile.Relay>(1002)))
    }

    @Test
    fun `clearSlot usuwa tylko pliki slotu a clear wszystko`() {
        val store = store()
        for (slot in 1..2) {
            store.writeModule(slot, StoredResponse(1L, "{}"))
            store.writeMenu(slot, MenuType.MU, StoredResponse(1L, "{}"))
            store.writeMenu(slot, MenuType.MI, StoredResponse(1L, "{}"))
        }
        store.writeI18n("pl", 1L, mapOf(1 to "a"))
        store.clearSlot(1)
        assertNull(store.readModule(1))
        assertNull(store.readMenu(1, MenuType.MI))
        assertNotNull(store.readModule(2))
        assertNotNull(store.readI18n("pl"))
        store.clear()
        assertNull(store.readModule(2))
        assertNull(store.readI18n("pl"))
        assertFalse(File(dir, "emodul").listFiles()!!.any { it.isFile })
    }

    @Test
    fun `clear na nieistniejacym katalogu nie zglasza bledu`() {
        store().clear()
    }

    @Test
    fun `lista modulow ma wlasny plik i znika razem z reszta`() {
        val store = store()
        assertNull(store.readModules())
        store.writeModules(StoredResponse(7L, EmodulFixtures.modulesJson))
        assertEquals(EmodulFixtures.modulesJson, store.readModules()!!.json)
        assertTrue(File(dir, "emodul/modules.json").isFile)
        store.clearSlot(1)
        assertNotNull(store.readModules())
        store.clear()
        assertNull(store.readModules())
    }
}
