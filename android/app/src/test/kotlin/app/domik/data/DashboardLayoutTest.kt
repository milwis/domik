package app.domik.data

import app.domik.deimic.config.HeatingZone
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Output
import app.domik.deimic.config.OutputKind
import app.domik.deimic.config.Shutter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DashboardLayoutTest {
    private fun output(module: Int, relay: Int, kind: OutputKind = OutputKind.ON_OFF) = Output(
        dbId = (module * 1000L + relay), module = module, relay = relay, kind = kind, rawType = kind.rawType ?: 99,
        name = "Wyjście $relay", roomId = null, levelId = null, iconId = null, canOn = true, outputsNumber = null,
    )

    private fun zone(module: Int, nr: Int) = HeatingZone(
        dbId = 5000L + module * 100 + nr, module = module, outputNr = nr, name = "Strefa $nr", roomId = null, levelId = null,
    )

    private fun shutter(module: Int, nr: Int) = Shutter(
        dbId = 7000L + module * 100 + nr, module = module, outputNr = nr, name = "Roleta $nr", roomId = null, levelId = null, iconId = null,
    )

    private fun config(outputs: List<Output>, zones: List<HeatingZone> = emptyList(), shutters: List<Shutter> = emptyList()) =
        HomeConfig(
            levels = emptyList(), rooms = emptyList(), modules = listOf(1), outputs = outputs, heatingZones = zones,
            shutters = shutters,
        )

    private fun entry(id: TileId, size: TileSize = id.kind.defaultSize) = TileEntry(id, size)

    private val sample = DashboardLayout(
        tiles = listOf(
            entry(TileId.Weather, TileSize.M), entry(TileId.Agenda, TileSize(2, 4)),
            entry(TileId.Zone(1, 2)), entry(TileId.Switch(1, 3)), entry(TileId.Switch(1, 5)),
        ),
    )

    // Układ domyślny

    @Test
    fun `uklad domyslny na tablecie poziomo - pogoda 2x1 nad kalendarzem 2x4, strefy 2x2, przelaczniki 1x1, na koncu rolety 2x1`() {
        val config = config(
            outputs = listOf(
                output(1, 3), output(1, 4), output(1, 5, OutputKind.LED),
                output(1, 6, OutputKind.RGB), output(1, 7, OutputKind.DIMMER_ON_OFF), output(1, 8, OutputKind.UNKNOWN),
            ),
            zones = listOf(zone(1, 2), zone(2, 0)),
            shutters = listOf(shutter(1, 0), shutter(2, 0), shutter(1, 1)),
        )
        val layout = DashboardLayout.default(config, columns = 8)
        assertEquals(
            listOf(
                entry(TileId.Weather, TileSize.M), entry(TileId.Agenda, TileSize(2, 4)),
                entry(TileId.Zone(1, 2), TileSize.L), entry(TileId.Zone(2, 0), TileSize.L),
                entry(TileId.Switch(1, 3), TileSize.S), entry(TileId.Switch(1, 4), TileSize.S),
                entry(TileId.Switch(1, 5), TileSize.S), entry(TileId.Switch(1, 7), TileSize.S),
                entry(TileId.Shutter(1, 0), TileSize.M), entry(TileId.Shutter(2, 0), TileSize.M),
                entry(TileId.Shutter(1, 1), TileSize.M),
            ),
            layout.tiles,
        )
        assertTrue(layout.hidden.isEmpty())
    }

    @Test
    fun `roleta i swiatlo o tym samym numerze to rozne kafelki`() {
        val config = config(outputs = listOf(output(1, 0)), shutters = listOf(shutter(1, 0)))
        val ids = DashboardLayout.default(config, columns = 8).ids
        assertTrue(TileId.Switch(1, 0) in ids && TileId.Shutter(1, 0) in ids)
        assertEquals("r:1:0", TileId.Shutter(1, 0).code)
        assertEquals("s:1:0", TileId.Switch(1, 0).code)
        assertEquals(TileId.Shutter(1, 0), TileId.decode("r:1:0"))
        assertEquals(TileKind.SHUTTER, TileId.Shutter(1, 0).kind)
    }

    @Test
    fun `uklad domyslny zalezy od liczby kolumn`() {
        assertEquals(
            listOf(entry(TileId.Weather, TileSize.M), entry(TileId.Agenda, TileSize(2, 4))),
            DashboardLayout.default(null, columns = 7).tiles,
        )
        assertEquals(
            listOf(entry(TileId.Weather, TileSize.L), entry(TileId.Agenda, TileSize.XL)),
            DashboardLayout.default(null, columns = 6).tiles,
        )
        assertEquals(
            listOf(entry(TileId.Weather, TileSize.M), entry(TileId.Agenda, TileSize.XL)),
            DashboardLayout.default(null, columns = 4).tiles,
        )
        // Telefon: kalendarz 4×2 zwężony do liczby kolumn.
        assertEquals(
            listOf(entry(TileId.Weather, TileSize.M), entry(TileId.Agenda, TileSize(3, 2))),
            DashboardLayout.default(null, columns = 3).tiles,
        )
        assertEquals(
            listOf(entry(TileId.Weather, TileSize.M), entry(TileId.Agenda, TileSize.L)),
            DashboardLayout.default(null, columns = 2).tiles,
        )
    }

    // Zapis i odczyt

    @Test
    fun `zapis i odczyt ukladu z ukrytymi kafelkami`() {
        val layout = sample.hide(TileId.Switch(1, 3))
        assertEquals("v4;+ w 2x1;+ a 2x4;+ z:1:2 2x2;+ s:1:5 1x1;- s:1:3 1x1", layout.encode())
        assertEquals(layout, DashboardLayout.decode(layout.encode()))
    }

    @Test
    fun `zapis i odczyt rolet - widocznych i ukrytych, z rozmiarem`() {
        val layout = DashboardLayout(
            tiles = listOf(entry(TileId.Weather, TileSize.M), entry(TileId.Shutter(2, 3), TileSize.M), entry(TileId.Switch(1, 5))),
            hidden = listOf(entry(TileId.Shutter(1, 0), TileSize.M)),
        )
        assertEquals("v4;+ w 2x1;+ r:2:3 2x1;+ s:1:5 1x1;- r:1:0 2x1", layout.encode())
        assertEquals(layout, DashboardLayout.decode(layout.encode()))
    }

    @Test
    fun `odczyt rolet pomija uszkodzone wpisy i czyta rozmiary S i L, a XL zwezone do L`() {
        val decoded = DashboardLayout.decode("v2;+ r:1:2 l;+ r:x:1 m;+ r:1 m;+ r:2:0 s;+ r:1:2:3 m;+ r:1:2 m;- r:3:1 xl")
        assertEquals(
            DashboardLayout(
                tiles = listOf(entry(TileId.Shutter(1, 2), TileSize.L), entry(TileId.Shutter(2, 0), TileSize.S)), // L, S zostają
                hidden = listOf(entry(TileId.Shutter(3, 1), TileSize.L)), // XL niedozwolony -> 2×2
            ),
            decoded,
        )
        // Uszkodzona struktura rekordu = cały zapis do wyrzucenia (wtedy układ domyślny).
        assertNull(DashboardLayout.decode("v2;+ r:1:0"))
    }

    @Test
    fun `migracja starego zapisu z roletami - kolejnosc zostaje, rolety dostaja rozmiar domyslny`() {
        assertEquals(
            DashboardLayout(
                tiles = listOf(entry(TileId.Zone(1, 0), TileSize.L), entry(TileId.Shutter(1, 2), TileSize.M), entry(TileId.Weather, TileSize.M)),
                hidden = listOf(entry(TileId.Agenda, TileSize.XL)),
            ),
            DashboardLayout.decode("z:1:0,r:1:2,r:x:1,r:1,w,r:1:2"),
        )
    }

    @Test
    fun `odczyt pomija nierozpoznane elementy, duplikaty i naprawia niedozwolony rozmiar`() {
        val decoded = DashboardLayout.decode("v2;+ w m;+ x:1:2 s;+ s:1:2 s;+ s:1:2 m;+ z:1:0 xl;+ w s;- a l")
        assertEquals(
            DashboardLayout(
                tiles = listOf(entry(TileId.Weather, TileSize.M), entry(TileId.Switch(1, 2), TileSize.S), entry(TileId.Zone(1, 0), TileSize.L)),
                hidden = listOf(entry(TileId.Agenda, TileSize.L)),
            ),
            decoded,
        )
    }

    @Test
    fun `brak lub uszkodzony zapis daje null - wtedy uklad domyslny`() {
        assertNull(DashboardLayout.decode(null))
        assertNull(DashboardLayout.decode("  "))
        assertNull(DashboardLayout.decode("v2;+ w"))
        assertNull(DashboardLayout.decode("v2;* w m"))
        assertNull(DashboardLayout.decode("v2;+ w m extra"))
        assertNull(DashboardLayout.decode("v"))
        assertNull(DashboardLayout.decode("???"))
    }

    @Test
    fun `zapis z nowszej wersji aplikacji jest ignorowany`() {
        assertNull(DashboardLayout.decode("v5;+ w 2x1;+ a 2x4"))
        assertNull(DashboardLayout.decode("v1;+ w m"))
    }

    @Test
    fun `migracja starego zapisu - kolejnosc zostaje, rozmiary domyslne, brakujace pogoda i kalendarz ukryte`() {
        val migrated = DashboardLayout.decode("z:1:0,s:1:5,x:9,s:1:5,w")
        assertEquals(
            DashboardLayout(
                tiles = listOf(entry(TileId.Zone(1, 0), TileSize.L), entry(TileId.Switch(1, 5), TileSize.S), entry(TileId.Weather, TileSize.M)),
                hidden = listOf(entry(TileId.Agenda, TileSize.XL)),
            ),
            migrated,
        )
        assertNull(DashboardLayout.decode("x:1,y"))
    }

    // Reguły modelu

    @Test
    fun `kafelki musza byc unikalne a rozmiar dozwolony dla rodzaju`() {
        assertThrows(IllegalArgumentException::class.java) {
            DashboardLayout(listOf(entry(TileId.Weather), entry(TileId.Weather)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashboardLayout(listOf(entry(TileId.Weather)), hidden = listOf(entry(TileId.Weather)))
        }
        assertThrows(IllegalArgumentException::class.java) { TileEntry(TileId.Switch(1, 1), TileSize.XL) }
        assertThrows(IllegalArgumentException::class.java) { TileEntry(TileId.Switch(1, 1), TileSize.M) }
        // Strefa i roleta: 1×1, 2×1, 2×2 – nic poza tym.
        TileEntry(TileId.Zone(1, 1), TileSize.S)
        TileEntry(TileId.Shutter(1, 0), TileSize.S)
        TileEntry(TileId.Shutter(1, 0), TileSize.L)
        assertThrows(IllegalArgumentException::class.java) { TileEntry(TileId.Shutter(1, 0), TileSize.XL) }
        assertEquals(TileSize.M, TileKind.SHUTTER.defaultSize)
        assertTrue(TileKind.SHUTTER.resizable)
        assertFalse(TileKind.SWITCH.resizable)
    }

    @Test
    fun `roleta S - M - L - S, a przelacznik nie zmienia rozmiaru`() {
        val id = TileId.Shutter(1, 0)
        val layout = DashboardLayout(listOf(entry(TileId.Weather), entry(id)))
        assertEquals(TileSize.M, layout.size(id))
        val sizes = generateSequence(layout) { it.cycled(id) }.take(5).map { it.size(id) }.toList()
        assertEquals(listOf(TileSize.M, TileSize.L, TileSize.S, TileSize.M, TileSize.L), sizes)
        assertEquals(TileSize.S, layout.resized(id, TileSize.S).size(id))
        assertSame(layout, layout.resized(id, TileSize.XL))
        val switch = DashboardLayout(listOf(entry(TileId.Switch(1, 1))))
        assertNull(switch.nextSize(TileId.Switch(1, 1)))
        assertSame(switch, switch.cycled(TileId.Switch(1, 1)))
        val hidden = layout.hide(id)
        assertEquals(listOf(entry(id, TileSize.M)), hidden.hidden)
        assertEquals(TileSize.M, hidden.show(id).size(id))
    }

    @Test
    fun `zmiana rozmiaru tylko na dozwolony`() {
        assertEquals(TileSize.L, sample.resized(TileId.Zone(1, 2), TileSize.L).size(TileId.Zone(1, 2)))
        assertSame(sample, sample.resized(TileId.Switch(1, 3), TileSize.M)) // przełącznik tylko 1×1
        assertSame(sample, sample.resized(TileId.Switch(1, 3), TileSize.XL))
        assertEquals(TileSize.S, sample.resized(TileId.Zone(1, 2), TileSize.S).size(TileId.Zone(1, 2)))
        assertSame(sample, sample.resized(TileId.Zone(1, 2), TileSize.XL))
        assertSame(sample, sample.resized(TileId.Switch(7, 7), TileSize.S))
        // Kalendarz i pogoda – dowolny rozmiar w zakresie.
        assertEquals(TileSize(5, 7), sample.resized(TileId.Agenda, TileSize(5, 7)).size(TileId.Agenda))
        assertEquals(TileSize(3, 2), sample.resized(TileId.Weather, TileSize(3, 2)).size(TileId.Weather))
        assertSame(sample, sample.resized(TileId.Agenda, TileSize(1, 4))) // węższy niż 2
        assertSame(sample, sample.resized(TileId.Agenda, TileSize(2, 9))) // wyższy niż 8
        assertSame(sample, sample.resized(TileId.Weather, TileSize(2, 3))) // wyższy niż 2
        assertSame(sample, sample.resized(TileId.Agenda, TileSize(2, 4))) // ten sam rozmiar
    }

    @Test
    fun `przycisk rozmiar przechodzi po dozwolonych rozmiarach i zawija`() {
        val zone = TileId.Zone(1, 2)
        assertEquals(TileSize.S, sample.nextSize(zone)) // L -> S
        assertEquals(TileSize.M, sample.cycled(zone).cycled(zone).size(zone)) // L -> S -> M
        val agenda = TileId.Agenda
        val sizes = generateSequence(sample) { it.cycled(agenda) }.take(5).map { it.size(agenda) }.toList()
        assertEquals(listOf(TileSize(2, 4), TileSize(4, 4), TileSize.L, TileSize.XL, TileSize(2, 4)), sizes)
        assertNull(sample.nextSize(TileId.Switch(9, 9)))
    }

    @Test
    fun `przesuwanie kafelka zmienia kolejnosc, reszta sie przesuwa`() {
        val moved = sample.moved(TileId.Switch(1, 5), 0)
        assertEquals(
            listOf(TileId.Switch(1, 5), TileId.Weather, TileId.Agenda, TileId.Zone(1, 2), TileId.Switch(1, 3)),
            moved.ids,
        )
        assertEquals(TileSize.S, moved.size(TileId.Switch(1, 5)))
        assertEquals(
            listOf(TileId.Agenda, TileId.Zone(1, 2), TileId.Switch(1, 3), TileId.Switch(1, 5), TileId.Weather),
            sample.moved(TileId.Weather, 99).ids, // poza zakresem = na koniec
        )
        assertSame(sample, sample.moved(TileId.Zone(1, 2), 2))
        assertSame(sample, sample.moved(TileId.Switch(7, 7), 0))
        assertEquals(sample.ids.first(), sample.moved(TileId.Weather, -3).ids.first())
    }

    @Test
    fun `ukrywanie zapamietuje rozmiar a dodawanie wraca na koniec z tym rozmiarem`() {
        val hidden = sample.hide(TileId.Zone(1, 2))
        assertFalse(TileId.Zone(1, 2) in hidden)
        assertEquals(listOf(entry(TileId.Zone(1, 2), TileSize.L)), hidden.hidden)
        assertSame(hidden, hidden.hide(TileId.Zone(1, 2)))

        val resizedAndHidden = sample.resized(TileId.Zone(1, 2), TileSize.M).hide(TileId.Zone(1, 2))
        val shown = resizedAndHidden.show(TileId.Zone(1, 2))
        assertEquals(TileId.Zone(1, 2), shown.ids.last())
        assertEquals(TileSize.M, shown.size(TileId.Zone(1, 2)))
        assertTrue(shown.hidden.isEmpty())
        assertSame(sample, sample.show(TileId.Zone(1, 2)))
    }

    // Uzgadnianie z konfiguracją

    @Test
    fun `uzgadnianie zachowuje kolejnosc i rozmiary, usuwa nieistniejace i dopisuje nowe na koniec`() {
        val config = config(outputs = listOf(output(1, 1), output(1, 2), output(1, 9)), zones = listOf(zone(1, 0), zone(1, 1)))
        val saved = DashboardLayout(
            listOf(
                entry(TileId.Switch(1, 2), TileSize.S), entry(TileId.Zone(1, 1), TileSize.M), entry(TileId.Weather),
                entry(TileId.Switch(1, 77)), // 77 już nie istnieje
                entry(TileId.Zone(1, 0)), entry(TileId.Switch(1, 1)),
            ),
            hidden = listOf(entry(TileId.Agenda), entry(TileId.Switch(1, 88))),
        )
        val result = DashboardLayout.reconcile(saved, config)
        assertEquals(
            listOf(TileId.Switch(1, 2), TileId.Zone(1, 1), TileId.Weather, TileId.Zone(1, 0), TileId.Switch(1, 1), TileId.Switch(1, 9)),
            result.ids,
        )
        assertEquals(TileSize.S, result.size(TileId.Switch(1, 2)))
        assertEquals(TileSize.M, result.size(TileId.Zone(1, 1)))
        assertEquals(TileSize.S, result.size(TileId.Switch(1, 9))) // nowe urządzenie – rozmiar domyślny
        assertEquals(listOf(entry(TileId.Agenda)), result.hidden) // ukryte, ale nadal istniejące zostaje ukryte
    }

    @Test
    fun `uklad zapisany przed roletami dostaje ich kafelki M na koncu, a usunieta roleta znika`() {
        val config = config(outputs = listOf(output(1, 1)), shutters = listOf(shutter(1, 0), shutter(1, 1)))
        val saved = DashboardLayout(
            tiles = listOf(entry(TileId.Switch(1, 1)), entry(TileId.Weather), entry(TileId.Agenda), entry(TileId.Shutter(1, 7))), // 7 już nie istnieje
            hidden = listOf(entry(TileId.Shutter(1, 8))),
        )
        val result = DashboardLayout.reconcile(saved, config)
        assertEquals(
            listOf(TileId.Switch(1, 1), TileId.Weather, TileId.Agenda, TileId.Shutter(1, 0), TileId.Shutter(1, 1)),
            result.ids,
        )
        assertEquals(TileSize.M, result.size(TileId.Shutter(1, 0)))
        assertTrue(result.hidden.isEmpty())
    }

    @Test
    fun `ukryta roleta zostaje ukryta po uzgodnieniu a zachowuje rozmiar`() {
        val config = config(outputs = emptyList(), shutters = listOf(shutter(1, 0), shutter(1, 1)))
        val saved = DashboardLayout.default(config).hide(TileId.Shutter(1, 0))
        val result = DashboardLayout.reconcile(saved, config)
        assertFalse(TileId.Shutter(1, 0) in result)
        assertEquals(listOf(entry(TileId.Shutter(1, 0), TileSize.M)), result.hidden)
        assertTrue(TileId.Shutter(1, 1) in result)
    }

    @Test
    fun `ukryty kafelek nie wraca po uzgodnieniu`() {
        val config = config(outputs = listOf(output(1, 1), output(1, 2)))
        val saved = DashboardLayout.default(config).hide(TileId.Switch(1, 2))
        val result = DashboardLayout.reconcile(saved, config)
        assertFalse(TileId.Switch(1, 2) in result)
        assertEquals(listOf(TileId.Switch(1, 2)), result.hidden.map { it.id })
    }

    @Test
    fun `uzgadnianie bez zapisu daje uklad domyslny dla liczby kolumn a bez konfiguracji zostawia zapis`() {
        val config = config(outputs = listOf(output(1, 1)))
        assertEquals(DashboardLayout.default(config, 8), DashboardLayout.reconcile(null, config, 8))
        assertEquals(DashboardLayout.default(config, 4), DashboardLayout.reconcile(null, config, 4))
        val saved = DashboardLayout(listOf(entry(TileId.Agenda), entry(TileId.Switch(1, 5))))
        assertEquals(saved, DashboardLayout.reconcile(saved, null))
        assertTrue(DashboardLayout.reconcile(null, null).tiles.isNotEmpty())
    }
}
