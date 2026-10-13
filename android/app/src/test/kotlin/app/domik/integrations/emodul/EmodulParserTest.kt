package app.domik.integrations.emodul

import java.time.Instant
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmodulParserTest {
    private val snapshot = EmodulFixtures.snapshot
    private val menus = EmodulFixtures.menus

    private fun module(vararg tiles: String, extra: String = "") =
        """{"tiles":[${tiles.joinToString(",")}],"tilesOrder":"","tilesLastUpdate":"2026-10-13 17:37:24.733345+02"$extra}"""

    private fun tile(type: Int, params: String?, id: Int = 1, menuId: Int = 0) =
        """{"id":$id,"parentId":0,"type":$type,"menuId":$menuId,"orderId":1,"visibility":true""" +
            (params?.let { ""","params":$it""" } ?: "") + "}"

    @Test
    fun `lista modulow ma jeden online modul bez danych konta`() {
        val modules = EmodulParser.parseModules(EmodulFixtures.modulesJson)
        assertEquals(1, modules.size)
        val m = modules.single()
        assertEquals("0123456789abcdef0123456789abcdef", m.udid)
        assertEquals("pellet", m.type)
        assertTrue(m.online)
        assertTrue(m.version.contains("ST-978"))
        val fields = EmodulModuleInfo::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.any { "mail" in it || "phone" in it })
    }

    @Test
    fun `modul z niepoprawnym udid jest pomijany a offline rozpoznawany`() {
        val json = """[{"udid":"../x","name":"zly","moduleStatus":"active"},
            {"udid":"abcdefgh12345678","name":"ok","moduleStatus":"inactive","type":"x","version":"v"}]"""
        val modules = EmodulParser.parseModules(json)
        assertEquals(listOf("abcdefgh12345678"), modules.map { it.udid })
        assertFalse(modules.single().online)
    }

    @Test
    fun `kafelki maja typy jak w specyfikacji`() {
        assertEquals(19, snapshot.tiles.size)
        val types = mapOf(
            EmodulTile.Text::class to listOf(1001, 1018, 2040),
            EmodulTile.Relay::class to listOf(1002, 1003, 1004, 1005),
            EmodulTile.Widgets::class to listOf(1006, 1007),
            EmodulTile.TemperatureSensor::class to listOf(1009),
            EmodulTile.Fuel::class to listOf(1010),
            EmodulTile.Fan::class to listOf(1011),
            EmodulTile.Valve::class to listOf(1012, 1014),
            EmodulTile.FireSensor::class to listOf(1016),
            EmodulTile.Pump::class to listOf(1023),
            EmodulTile.Version::class to listOf(2000),
            EmodulTile.FiringUp::class to listOf(2010),
            EmodulTile.Disinfection::class to listOf(3630),
        )
        for ((kind, ids) in types) {
            assertEquals(ids, snapshot.tiles.filter { kind.isInstance(it) }.map { it.id }.sorted(), kind.simpleName)
        }
        assertTrue(snapshot.tiles.none { it is EmodulTile.Unknown })
        assertTrue(snapshot.tiles.all { it.parentId == 0 && it.visible })
    }

    @Test
    fun `czujnik temperatury ma 12 stopni`() {
        val t = EmodulFixtures.tile<EmodulTile.TemperatureSensor>(1009)
        assertEquals(12.0, t.celsius!!, 1e-9)
        assertEquals(TxtId(795), t.name)
        assertTrue(t.working)
        assertNull(t.battery)
        assertNull(t.signal)
    }

    @Test
    fun `zawory maja temperatury zadana i rodzaj`() {
        val builtIn = EmodulFixtures.tile<EmodulTile.Valve>(1012)
        assertEquals(32.3, builtIn.currentC!!, 1e-9)
        assertEquals(55.8, builtIn.returnC!!, 1e-9)
        assertEquals(28, builtIn.setC)
        assertEquals(0, builtIn.openingPercent)
        assertTrue(builtIn.builtIn)
        assertEquals(2217, builtIn.menuId)
        val valve1 = EmodulFixtures.tile<EmodulTile.Valve>(1014)
        assertFalse(valve1.builtIn)
        assertEquals(1, valve1.number)
        assertEquals(48, valve1.setC)
        assertEquals(2429, valve1.menuId)
    }

    @Test
    fun `paliwo tekst i wersja sterownika`() {
        val fuel = EmodulFixtures.tile<EmodulTile.Fuel>(1010)
        assertEquals(78, fuel.percent)
        assertEquals(59, fuel.hours)
        val text = EmodulFixtures.tile<EmodulTile.Text>(2040)
        assertEquals(TxtId(1039), text.header)
        assertEquals(TxtId(676), text.status)
        val version = EmodulFixtures.tile<EmodulTile.Version>(2000)
        assertEquals("ST-978 F2", version.name)
        assertEquals("2.7.0", version.version)
        assertFalse(version.toString().contains("mainController"))
    }

    @Test
    fun `widzety kafelka statusu`() {
        val t = EmodulFixtures.tile<EmodulTile.Widgets>(1006)
        assertEquals(EmodulWidget(TxtId(937), 0, 7), t.w1)
        assertEquals(EmodulWidget(TxtId(1050), 650, 7), t.w2)
        assertEquals(2060, t.menuId)
    }

    @Test
    fun `kolejnosc kafelkow i czas aktualizacji`() {
        assertEquals(
            listOf(2040, 1006, 1012, 1014, 1007, 1023, 1004, 1005, 1010, 1009, 1016, 1003, 1002, 1011, 3630, 1001, 1018),
            snapshot.order,
        )
        assertEquals(Instant.parse("2026-10-13T15:37:24.733345Z"), snapshot.lastUpdate)
        assertTrue(snapshot.zones.isEmpty())
    }

    @Test
    fun `zly czas aktualizacji to null`() {
        assertNull(EmodulParser.parseModule("""{"tiles":[],"tilesLastUpdate":"wczoraj"}""").lastUpdate)
        assertNull(EmodulParser.parseModule("""{"tiles":[]}""").lastUpdate)
        assertEquals(
            Instant.parse("2026-10-13T15:37:24Z"),
            EmodulParser.parseModule("""{"tiles":[],"tilesLastUpdate":"2026-10-13 17:37:24+02:00"}""").lastUpdate,
        )
    }

    @Test
    fun `menu uzytkownika ma nastawe CO`() {
        val item = menus.getValue(MenuType.MU).items.getValue(2060)
        assertEquals(1, item.kind)
        assertEquals(1, item.format)
        assertEquals(65, item.value)
        assertEquals(53, item.min)
        assertEquals(80, item.max)
        assertEquals(TxtId(1035), item.unitTxtId)
        assertEquals(TxtId(937), item.txtId)
        assertTrue(item.type == MenuType.MU)
        assertEquals(81, menus.getValue(MenuType.MU).items.size)
    }

    @Test
    fun `menu instalatora krzywa i duringChange f to false`() {
        val mi = menus.getValue(MenuType.MI).items
        assertEquals(255, mi.size)
        assertFalse(mi.getValue(2229).duringChange)
        assertEquals(30, mi.getValue(2217).kind)
        assertEquals(30094, mi.getValue(2217).parentId.let { mi.getValue(it).parentId })
        assertTrue(mi.values.none { it.duringChange })
    }

    @Test
    fun `duringChange w roznych wariantach`() {
        fun during(extra: String): Boolean {
            val json = EmodulFixtures.menuJson(EmodulFixtures.element(5, 0, 1, 1, extra = extra))
            return EmodulParser.parseMenu(json, MenuType.MU).items.getValue(5).duringChange
        }
        assertTrue(during(""","duringChange":"t""""))
        assertTrue(during(""","duringChange":true"""))
        assertTrue(during(""","duringChange":"true""""))
        assertTrue(during(""","duringChange":"1""""))
        assertFalse(during(""","duringChange":"f""""))
        assertFalse(during(""","duringChange":false"""))
        assertFalse(during(""","duringChange":null"""))
        assertFalse(during(""))
    }

    @Test
    fun `menu bez obwiedni status data`() {
        val json = """{"elements":[${EmodulFixtures.element(7, 0, 1, 3, """{"format":1,"value":5,"min":1,"max":9,"txtId":1035}""")}]}"""
        val item = EmodulParser.parseMenu(json, MenuType.MI).items.getValue(7)
        assertEquals(5, item.value)
        assertEquals(MenuType.MI, item.type)
    }

    @Test
    fun `nieznany typ i kafelek bez params to Unknown`() {
        val snapshot = EmodulParser.parseModule(
            module(
                tile(252, """{"description":"OpenTherm","x":1}""", id = 10),
                tile(6, null, id = 11),
                tile(1, """{"description":"Temperature sensor","txtId":5}""", id = 12),
                tile(23, """{"description":"Valve"}""", id = 13),
            ),
        )
        val unknown = snapshot.tiles.map { it as EmodulTile.Unknown }
        assertEquals(listOf(10, 11, 12, 13), unknown.map { it.id })
        assertEquals(252, unknown[0].type)
        assertEquals("OpenTherm", unknown[0].description)
        assertEquals("", unknown[1].description)
        assertEquals(1, unknown[2].type)
    }

    @Test
    fun `liczby niecalkowite i sentinel daja null`() {
        val snapshot = EmodulParser.parseModule(
            module(
                tile(31, """{"percentage":12.5,"hours":7.5}""", id = 1),
                tile(1, """{"txtId":3,"value":32767,"workingStatus":false}""", id = 2),
                tile(22, """{"workingStatus":true,"gear":250,"fanNumber":0}""", id = 3),
                tile(6, """{"iconId":1,"widget1":{"txtId":1,"value":1.5,"unit":7},"widget2":{"txtId":2,"value":5,"unit":-1}}""", id = 4),
            ),
        )
        val fuel = snapshot.tiles[0] as EmodulTile.Fuel
        assertNull(fuel.percent)
        assertNull(fuel.hours)
        assertNull((snapshot.tiles[1] as EmodulTile.TemperatureSensor).celsius)
        assertNull((snapshot.tiles[2] as EmodulTile.Fan).gear)
        val widgets = snapshot.tiles[3] as EmodulTile.Widgets
        assertNull(widgets.w1)
        assertNull(widgets.w2)
    }

    @Test
    fun `kafelek wewnatrz kontenera i niewidoczny`() {
        val json = """{"tiles":[
            {"id":1,"parentId":0,"type":60,"menuId":0,"orderId":1,"visibility":true,"params":{"txtId":9,"iconId":1}},
            {"id":2,"parentId":1,"type":41,"menuId":0,"orderId":2,"visibility":false,
             "params":{"year":2026,"month":10,"day":13,"hours":17,"minutes":37,"dayId":2}},
            {"id":3,"parentId":0,"type":41,"menuId":0,"visibility":true,
             "params":{"year":2026,"month":13,"day":40,"hours":17,"minutes":37}}]}"""
        val tiles = EmodulParser.parseModule(json).tiles
        assertTrue(tiles[0] is EmodulTile.Container)
        val clock = tiles[1] as EmodulTile.ControllerDate
        assertEquals(1, clock.parentId)
        assertFalse(clock.visible)
        assertEquals(LocalDateTime.of(2026, 10, 13, 17, 37), clock.dateTime)
        assertNull((tiles[2] as EmodulTile.ControllerDate).dateTime)
        assertNull(tiles[2].orderId)
    }

    @Test
    fun `strefy z syntetycznego JSON`() {
        val json = """{"tiles":[],"zones":{"elements":[
            {"zone":{"id":101,"currentTemperature":219,"setTemperature":230,"flags":{"relayState":"on"},
                     "humidity":45,"batteryLevel":80,"signalStrength":60,"visibility":true},
             "description":{"name":"Salon testowy"},"mode":{"mode":"constantTemp"}},
            {"zone":{"id":102,"currentTemperature":32767,"setTemperature":200,"flags":{"relayState":"off"},
                     "humidity":0,"visibility":false},
             "description":{"name":"Sypialnia testowa"}},
            {"zone":{"id":103},"description":{}}]}}"""
        val zones = EmodulParser.parseModule(json).zones
        assertEquals(3, zones.size)
        val salon = zones[0]
        assertEquals(101, salon.id)
        assertEquals("Salon testowy", salon.name)
        assertEquals(21.9, salon.currentC!!, 1e-9)
        assertEquals(23.0, salon.setC!!, 1e-9)
        assertEquals(true, salon.heating)
        assertEquals(45, salon.humidity)
        assertTrue(salon.visible)
        val bedroom = zones[1]
        assertNull(bedroom.currentC)
        assertEquals(false, bedroom.heating)
        assertNull(bedroom.humidity)
        assertFalse(bedroom.visible)
        assertNull(zones[2].heating)
        assertEquals("", zones[2].name)
    }

    @Test
    fun `zly JSON to EmodulParseException bez danych w komunikacie`() {
        for (bad in listOf("", "nie json", "{", "[1,2")) {
            val e = assertThrows(EmodulParseException::class.java) { EmodulParser.parseModule(bad) }
            assertEquals("Niepoprawna odpowiedź eModul", e.message)
        }
        assertThrows(EmodulParseException::class.java) { EmodulParser.parseModules("""{"a":1}""") }
        assertThrows(EmodulParseException::class.java) { EmodulParser.parseModule("[]") }
        assertThrows(EmodulParseException::class.java) { EmodulParser.parseMenu("""{"data":{}}""", MenuType.MU) }
        assertThrows(EmodulParseException::class.java) { EmodulParser.parseI18n("""{"data":[]}""") }
    }

    @Test
    fun `slownik pelny i podzbior`() {
        val json = """{"status":"success","data":{"1":"Jeden","2":"Dwa","x":"zly","3":5}}"""
        assertEquals(mapOf(1 to "Jeden", 2 to "Dwa"), EmodulParser.parseI18n(json))
        assertEquals(mapOf(2 to "Dwa"), EmodulParser.parseI18n(json, setOf(2, 3, 4)))
        assertNotNull(EmodulParser.parseI18n(EmodulFixtures.i18nJson)[937])
    }
}
