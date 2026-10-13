package app.domik.data

import app.domik.deimic.config.HeatingZone
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Output
import app.domik.deimic.config.OutputKind
import app.domik.deimic.config.Shutter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Wyjścia alarmowe (`icon_id` 124) są domyślnie ukryte: w układzie domyślnym i wśród nowych urządzeń. */
class DashboardLayoutAlarmTest {
    private fun output(relay: Int, iconId: Int? = 3, kind: OutputKind = OutputKind.ON_OFF, module: Int = 1) = Output(
        dbId = module * 1000L + relay, module = module, relay = relay, kind = kind, rawType = kind.rawType ?: 99,
        name = "Wyjście $relay", roomId = null, levelId = null, iconId = iconId, canOn = true, outputsNumber = null,
    )

    private fun config(outputs: List<Output>, zones: List<HeatingZone> = emptyList()) =
        HomeConfig(levels = emptyList(), rooms = emptyList(), modules = listOf(1, 2), outputs = outputs, heatingZones = zones)

    private val zone = HeatingZone(dbId = 7, module = 1, outputNr = 0, name = "Strefa", roomId = null, levelId = null)
    private fun sw(relay: Int, module: Int = 1) = TileId.Switch(module, relay)
    private fun entry(id: TileId, size: TileSize = id.kind.defaultSize) = TileEntry(id, size)

    private val withAlarm = config(
        outputs = listOf(output(0), output(1, iconId = 124), output(2), output(3, iconId = 124, kind = OutputKind.LED)),
        zones = listOf(zone),
    )

    @Test
    fun `uklad domyslny chowa wyjscia alarmowe, reszta tak jak dotad`() {
        val layout = DashboardLayout.default(withAlarm)

        assertEquals(
            listOf(TileId.Weather, TileId.Agenda, TileId.Zone(1, 0), sw(0), sw(2)),
            layout.ids,
        )
        assertEquals(listOf<TileId>(sw(1), sw(3)), layout.hidden.map { it.id })
        assertTrue(layout.hidden.all { it.size == TileKind.SWITCH.defaultSize })
    }

    @Test
    fun `uklad domyslny bez wyjsc alarmowych nie ma ukrytych`() {
        val layout = DashboardLayout.default(config(listOf(output(0), output(1))))
        assertTrue(layout.hidden.isEmpty())
        assertEquals(listOf(TileId.Weather, TileId.Agenda, sw(0), sw(1)), layout.ids)
    }

    @Test
    fun `uklad domyslny zalezy od liczby kolumn tylko w naglowku, alarm zawsze ukryty`() {
        for (columns in listOf(2, 4, 6, 8)) {
            val layout = DashboardLayout.default(withAlarm, columns)
            assertEquals(listOf<TileId>(sw(1), sw(3)), layout.hidden.map { it.id }, "kolumny: $columns")
        }
    }

    @Test
    fun `nazwa wyjscia nie wystarcza - alarm to tylko icon_id 124`() {
        val named = config(listOf(output(0).copy(name = "Alarm"), output(1, iconId = null)))
        assertTrue(DashboardLayout.default(named).hidden.isEmpty())
    }

    @Test
    fun `nowe wyjscie alarmowe po uzgodnieniu trafia do ukrytych a nowe zwykle na koniec pulpitu`() {
        val saved = DashboardLayout(
            tiles = listOf(entry(TileId.Weather), entry(TileId.Agenda), entry(TileId.Zone(1, 0)), entry(sw(0))),
        )

        val layout = DashboardLayout.reconcile(saved, withAlarm)

        assertEquals(listOf(TileId.Weather, TileId.Agenda, TileId.Zone(1, 0), sw(0), sw(2)), layout.ids)
        assertEquals(listOf<TileId>(sw(1), sw(3)), layout.hidden.map { it.id })
    }

    @Test
    fun `uzgadnianie zachowuje wyjscie alarmowe dodane przez uzytkownika na pulpit`() {
        val saved = DashboardLayout(
            tiles = listOf(
                entry(TileId.Weather), entry(TileId.Agenda), entry(TileId.Zone(1, 0)), entry(sw(0)), entry(sw(1)), entry(sw(2)),
            ),
            hidden = listOf(entry(sw(3))),
        )

        val layout = DashboardLayout.reconcile(saved, withAlarm)

        assertEquals(saved, layout)
        assertTrue(sw(1) in layout)
    }

    @Test
    fun `uzgadnianie nie wyciaga ukrytego wyjscia alarmowego ani nie dubluje go`() {
        val first = DashboardLayout.reconcile(null, withAlarm)
        val again = DashboardLayout.reconcile(first, withAlarm)

        assertEquals(first, again)
        assertEquals(listOf<TileId>(sw(1), sw(3)), again.hidden.map { it.id })
    }

    @Test
    fun `dodanie ukrytego alarmu przywraca go na pulpit a ukrycie z powrotem do ukrytych`() {
        val layout = DashboardLayout.default(withAlarm)

        val shown = layout.show(sw(1))
        assertEquals(sw(1), shown.ids.last())
        assertFalse(sw(1) in shown.hidden.map { it.id })

        val hiddenAgain = shown.hide(sw(1))
        assertTrue(sw(1) in hiddenAgain.hidden.map { it.id })
    }

    @Test
    fun `przywrocenie ukladu domyslnego chowa alarm ponownie`() {
        val userLayout = DashboardLayout.default(withAlarm).show(sw(1))
        assertTrue(sw(1) in userLayout)

        // „Przywróć układ domyślny” = default(config, columns) – tak samo jak przy pierwszym uruchomieniu.
        val restored = DashboardLayout.default(withAlarm)

        assertFalse(sw(1) in restored)
        assertEquals(listOf<TileId>(sw(1), sw(3)), restored.hidden.map { it.id })
    }

    @Test
    fun `alarm w wyjsciu bez przelacznika, w rolecie albo bez konfiguracji nie tworzy ukrytych kafelkow`() {
        val rgb = config(listOf(output(0, iconId = 124, kind = OutputKind.RGB)))
        assertTrue(DashboardLayout.default(rgb).hidden.isEmpty())
        // Roleta (osobny model, nie wyjście) z icon_id 124 zostaje na pulpicie – alarm dotyczy tylko przełączników.
        val shutter = config(emptyList()).copy(
            shutters = listOf(Shutter(dbId = 1, module = 1, outputNr = 0, name = "Roleta", roomId = null, levelId = null, iconId = 124)),
        )
        val layout = DashboardLayout.default(shutter)
        assertTrue(layout.hidden.isEmpty())
        assertEquals(TileId.Shutter(1, 0), layout.ids.last())
        assertTrue(DashboardLayout.default(null).hidden.isEmpty())
    }

    @Test
    fun `rolety i alarm razem - rolety na koncu widocznych, alarm ukryty, nowe uzgadniane osobno`() {
        val shutters = listOf(
            Shutter(dbId = 1, module = 1, outputNr = 0, name = "Roleta", roomId = null, levelId = null, iconId = null),
            Shutter(dbId = 2, module = 1, outputNr = 1, name = "Roleta 2", roomId = null, levelId = null, iconId = null),
        )
        val both = withAlarm.copy(shutters = shutters)

        val layout = DashboardLayout.default(both)
        assertEquals(
            listOf(TileId.Weather, TileId.Agenda, TileId.Zone(1, 0), sw(0), sw(2), TileId.Shutter(1, 0), TileId.Shutter(1, 1)),
            layout.ids,
        )
        assertEquals(listOf<TileId>(sw(1), sw(3)), layout.hidden.map { it.id })

        // Zapis sprzed rolet i alarmu: nowa roleta trafia na koniec widocznych, nowy alarm do ukrytych.
        val saved = DashboardLayout(
            tiles = listOf(TileEntry(TileId.Weather, TileSize.M), TileEntry(TileId.Agenda, TileSize.XL), entry(TileId.Zone(1, 0)), entry(sw(0))),
            hidden = emptyList(),
        )
        val reconciled = DashboardLayout.reconcile(saved, both)
        assertEquals(
            listOf(TileId.Weather, TileId.Agenda, TileId.Zone(1, 0), sw(0), sw(2), TileId.Shutter(1, 0), TileId.Shutter(1, 1)),
            reconciled.ids,
        )
        assertEquals(listOf<TileId>(sw(1), sw(3)), reconciled.hidden.map { it.id })
        assertEquals(DashboardLayout.decode(reconciled.encode()), reconciled) // zapis zna „r:” i ukryte alarmy
    }

    // Migracja zapisu sprzed ukrywania wyjść alarmowych (v2 -> v3)

    private fun migrated(text: String, config: HomeConfig = withAlarm): DashboardLayout {
        val stored = checkNotNull(DashboardLayout.load(text))
        return DashboardLayout.reconcile(stored.layout, config, migrateAlarms = stored.alarmMigrationPending)
    }

    @Test
    fun `zapis v2 z widocznym alarmem - po wczytaniu alarm jest ukryty, reszta bez zmian`() {
        val text = "v2;+ w m;+ a xl;+ z:1:0 l;+ s:1:0 s;+ s:1:1 s;+ s:1:2 m;+ s:1:3 s"

        val stored = checkNotNull(DashboardLayout.load(text))
        assertTrue(stored.alarmMigrationPending)

        val layout = DashboardLayout.reconcile(stored.layout, withAlarm, migrateAlarms = stored.alarmMigrationPending)
        assertEquals(
            listOf(entry(TileId.Weather, TileSize.M), entry(TileId.Agenda, TileSize.XL), entry(TileId.Zone(1, 0), TileSize.L), entry(sw(0)), entry(sw(2))), // dawny 2×1 wraca do 1×1
            layout.tiles,
        )
        assertEquals(listOf(entry(sw(1)), entry(sw(3))), layout.hidden)
    }

    @Test
    fun `migracja alarmu dopisuje go do juz ukrytych, a dawny rozmiar 2x1 wraca do 1x1`() {
        val layout = migrated("v2;+ w m;+ a xl;+ z:1:0 l;+ s:1:0 s;+ s:1:1 m;+ s:1:2 s;- s:1:3 s")

        assertFalse(sw(1) in layout)
        assertEquals(listOf(entry(sw(3)), entry(sw(1))), layout.hidden)
    }

    @Test
    fun `zapis v3 z widocznym alarmem zostaje bez zmian - to wybor uzytkownika`() {
        val text = "v3;+ w m;+ a xl;+ z:1:0 l;+ s:1:0 s;+ s:1:1 s;+ s:1:2 s;- s:1:3 s"

        val stored = checkNotNull(DashboardLayout.load(text))
        assertFalse(stored.alarmMigrationPending)

        val layout = migrated(text)
        assertTrue(sw(1) in layout)
        assertEquals(listOf(entry(sw(3))), layout.hidden)
    }

    @Test
    fun `zapis w biezacej wersji (v4) - odczyt i zapis w obie strony, alarm dodany przez uzytkownika nie jest ukrywany ponownie`() {
        val migratedLayout = migrated("v2;+ w m;+ a xl;+ z:1:0 l;+ s:1:0 s;+ s:1:1 s;+ s:1:2 s;+ s:1:3 s")
        val userChoice = migratedLayout.show(sw(1)) // świadome dodanie z powrotem

        val encoded = userChoice.encode()
        assertTrue(encoded.startsWith("v${DashboardLayout.VERSION};"))
        assertEquals("v4", encoded.substringBefore(';'))

        val stored = checkNotNull(DashboardLayout.load(encoded))
        assertEquals(StoredLayout(userChoice, alarmMigrationPending = false), stored)
        assertEquals(userChoice, DashboardLayout.decode(encoded))
        assertEquals(userChoice, migrated(encoded)) // po zapisie v4 nic już nie jest ukrywane
        assertTrue(sw(1) in migrated(encoded))
    }

    @Test
    fun `zapis v1 (sama lista kolejnosci) tez przechodzi migracje alarmu`() {
        val stored = checkNotNull(DashboardLayout.load("z:1:0,s:1:0,s:1:1,w,a"))
        assertTrue(stored.alarmMigrationPending)

        val layout = DashboardLayout.reconcile(stored.layout, withAlarm, migrateAlarms = true)
        assertFalse(sw(1) in layout)
        assertTrue(sw(1) in layout.hidden.map { it.id })
    }

    @Test
    fun `migracja alarmu nie rusza rolet, zwyklych przelacznikow ani nieznanej konfiguracji i jest idempotentna`() {
        val shutter = Shutter(dbId = 1, module = 1, outputNr = 0, name = "Roleta", roomId = null, levelId = null, iconId = 124)
        val cfg = withAlarm.copy(shutters = listOf(shutter))
        val text = "v2;+ w m;+ a xl;+ s:1:0 s;+ s:1:1 s;+ r:1:0 m"

        val once = migrated(text, cfg)
        // Zapis nie znał strefy i drugiego zwykłego przełącznika – dochodzą na koniec (uzgadnianie), alarmy (1, 3) są ukryte.
        assertEquals(listOf<TileId>(TileId.Weather, TileId.Agenda, sw(0), TileId.Shutter(1, 0), TileId.Zone(1, 0), sw(2)), once.ids)
        assertEquals(listOf<TileId>(sw(1), sw(3)), once.hidden.map { it.id })
        assertTrue(TileId.Shutter(1, 0) in once) // roleta z icon_id 124 zostaje
        assertFalse(sw(1) in once)

        assertEquals(once, DashboardLayout.reconcile(once, cfg, migrateAlarms = true))

        // Bez konfiguracji (jeszcze niewczytanej) zapis wraca bez zmian.
        val stored = checkNotNull(DashboardLayout.load(text))
        assertEquals(stored.layout, DashboardLayout.reconcile(stored.layout, null, migrateAlarms = true))
    }
}
