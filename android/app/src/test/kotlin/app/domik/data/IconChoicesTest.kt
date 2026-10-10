package app.domik.data

import app.domik.deimic.protocol.SerialNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IconChoicesTest {
    private val a = TileId.Switch(1, 5)
    private val b = TileId.Switch(2, 0)
    private val c = TileId.Switch(1, 12)

    @Test
    fun `zapis jest wersjonowany i uporzadkowany po module i wyjsciu`() {
        val choices = IconChoices().with(b, LightIcon.SOCKET).with(c, LightIcon.FAN).with(a, LightIcon.SCONCE)
        assertEquals("v1;s:1:5=sconce;s:1:12=fan;s:2:0=socket", choices.encode())
        assertEquals("v1", IconChoices.EMPTY.encode())
    }

    @Test
    fun `zapis i odczyt daja ten sam wybor`() {
        val choices = IconChoices(LightIcon.entries.mapIndexed { i, icon -> TileId.Switch(1, i) to icon }.toMap())
        assertEquals(choices, IconChoices.decode(choices.encode()))
        assertEquals(IconChoices.EMPTY, IconChoices.decode(IconChoices.EMPTY.encode()))
    }

    @Test
    fun `wybor dla kafelka mozna zmienic i cofnac do automatu`() {
        val chosen = IconChoices.EMPTY.with(a, LightIcon.SCONCE)
        assertEquals(LightIcon.SCONCE, chosen[a])
        assertNull(chosen[b])
        assertEquals(LightIcon.PENDANT, chosen.with(a, LightIcon.PENDANT)[a])
        assertEquals(IconChoices.EMPTY, chosen.with(a, null))
        assertTrue(chosen.with(a, null).isEmpty)
        assertEquals(chosen, chosen.with(b, null)) // brak wpisu zostaje brakiem
    }

    @Test
    fun `brak lub pusty zapis to brak wyboru`() {
        assertEquals(IconChoices.EMPTY, IconChoices.decode(null))
        assertEquals(IconChoices.EMPTY, IconChoices.decode(""))
        assertEquals(IconChoices.EMPTY, IconChoices.decode("   "))
    }

    @Test
    fun `zapis z innej wersji albo bez wersji jest ignorowany w calosci`() {
        assertEquals(IconChoices.EMPTY, IconChoices.decode("v2;s:1:5=sconce"))
        assertEquals(IconChoices.EMPTY, IconChoices.decode("s:1:5=sconce"))
        assertEquals(IconChoices.EMPTY, IconChoices.decode("śmieci"))
    }

    @Test
    fun `uszkodzony wpis jest pomijany a reszta zostaje`() {
        val text = listOf(
            "v1",
            "s:1:5=sconce", // dobry
            "s:1:6", // bez ikony
            "s:1:7=", // pusta ikona
            "s:1:8=kosmiczna", // nieznana ikona
            "z:1:0=fan", // strefa ogrzewania nie ma ikony wyboru
            "w=bulb", // pogoda też nie
            "s:x:9=fan", // zepsute id
            "=fan",
            "s:2:0=socket=fan", // dwa znaki równości
            "",
            "s:2:1=pump", // dobry
        ).joinToString(";")
        assertEquals(
            IconChoices(mapOf(TileId.Switch(1, 5) to LightIcon.SCONCE, TileId.Switch(2, 1) to LightIcon.PUMP)),
            IconChoices.decode(text),
        )
    }

    @Test
    fun `powtorzony kafelek - obowiazuje pierwszy wpis`() {
        assertEquals(LightIcon.FAN, IconChoices.decode("v1;s:1:5=fan;s:1:5=pump")[a])
    }

    @Test
    fun `odstepy wokol wpisow nie szkodza`() {
        assertEquals(LightIcon.FAN, IconChoices.decode(" v1; s:1:5 = fan ")[a])
    }

    // Ustawienia: osobno dla profilu, bez wpływu na układ

    private val store = InMemoryKeyValueStore()
    private var counter = 0
    private val settings = AppSettings(store, FakeCipher()) { "profil-${++counter}" }

    @Test
    fun `ikony sa zapisywane osobno dla kazdego profilu pod kluczem icons`() {
        settings.saveIcons("p1", "v1;s:1:5=sconce")
        settings.saveIcons("p2", "v1;s:1:5=fan")

        assertEquals("v1;s:1:5=sconce", settings.icons("p1"))
        assertEquals("v1;s:1:5=fan", settings.icons("p2"))
        assertNull(settings.icons("p3"))
        assertEquals("v1;s:1:5=sconce", store.getString("icons.p1"))
    }

    @Test
    fun `czyszczenie ikon profilu usuwa tylko jego wpis i nie rusza ukladu`() {
        settings.saveIcons("p1", "v1;s:1:5=sconce")
        settings.saveIcons("p2", "v1;s:1:5=fan")
        settings.saveLayout("p1", "v2;+ w m")

        settings.clearIcons("p1")

        assertNull(settings.icons("p1"))
        assertEquals("v1;s:1:5=fan", settings.icons("p2"))
        assertEquals("v2;+ w m", settings.layout("p1"))
    }

    @Test
    fun `czyszczenie ukladu nie kasuje ikon`() {
        settings.saveIcons("p1", "v1;s:1:5=sconce")
        settings.saveLayout("p1", "v2;+ w m")

        settings.clearLayout("p1")

        assertNull(settings.layout("p1"))
        assertEquals("v1;s:1:5=sconce", settings.icons("p1"))
    }

    @Test
    fun `zapomnienie sterownika usuwa jego ikony razem z ukladem a cudze zostaja`() {
        val profile = settings.saveController("A", "192.0.2.10", 60110, SerialNumber(0x0A1B2C3Du))
        settings.saveLayout(profile.id, "v2;+ w m")
        settings.saveIcons(profile.id, "v1;s:1:5=sconce")
        settings.saveIcons("demo", "v1;s:1:5=fan")

        assertEquals(profile, settings.clearController())

        assertNull(settings.layout(profile.id))
        assertNull(settings.icons(profile.id))
        assertEquals("v1;s:1:5=fan", settings.icons("demo"))
        assertTrue(store.values.keys.none { it.contains(profile.id) })
    }
}
