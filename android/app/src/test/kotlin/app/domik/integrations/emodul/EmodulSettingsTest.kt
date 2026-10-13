package app.domik.integrations.emodul

import app.domik.data.FakeCipher
import app.domik.data.InMemoryKeyValueStore
import app.domik.data.TileId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmodulSettingsTest {
    private val store = InMemoryKeyValueStore()
    private val cipher = FakeCipher()
    private val settings = EmodulSettings(store, cipher)

    private val token = EmodulFixtures.jwt("""{"username":"ktos","user_id":4242,"is_generated":true}""")
    private val key = (EmodulKey.parse(token, 0L) as EmodulKeyResult.Valid).key
    private val udidA = "aaaaaaaa11111111"
    private val udidB = "bbbbbbbb22222222"
    private val udidC = "cccccccc33333333"

    @Test
    fun `bez zapisu nie ma klucza ani modulow ani akceptacji`() {
        assertNull(settings.key())
        assertTrue(settings.slots().isEmpty())
        assertEquals(0, settings.acceptedNoticeVersion)
        assertFalse(settings.noticeAccepted)
    }

    @Test
    fun `klucz jest zapisany zaszyfrowany i wraca po odczycie`() {
        settings.saveKey(key)

        assertEquals(key, EmodulSettings(store, cipher).key())
        assertEquals(4242L, settings.key()!!.userId)
        // w magazynie nie ma tokenu jawnym tekstem ani w ogóle żadnej jego części
        assertFalse(store.values.values.any { it.toString().contains(token) })
        assertFalse(store.values.values.any { it.toString().contains(token.substringAfter('.').take(12)) })
        assertNotNull(store.getString("emodul.key"))
    }

    @Test
    fun `klucz ma wlasne AAD i nie da sie go odszyfrowac pod innym`() {
        settings.saveKey(key)
        val sealed = store.getString("emodul.key")!!

        // ten sam szyfrogram wpisany jako numer seryjny Deimic (inne AAD) nie zadziała w żadną stronę
        val other = EmodulSettings(InMemoryKeyValueStore().also { it.edit { putString("emodul.key", sealed) } }, object : app.domik.data.SecretCipher {
            override fun encrypt(plain: ByteArray, associatedData: ByteArray) = cipher.encrypt(plain, associatedData)
            override fun decrypt(sealed: ByteArray, associatedData: ByteArray) = cipher.decrypt(sealed, "domik.serial:x".toByteArray())
        })
        assertNull(other.key())
    }

    @Test
    fun `nieudane odszyfrowanie albo uszkodzony wpis to brak klucza`() {
        settings.saveKey(key)
        cipher.failDecrypt = true
        assertNull(settings.key())
        cipher.failDecrypt = false
        store.edit { putString("emodul.key", "to nie jest base64!!") }
        assertNull(settings.key())
    }

    @Test
    fun `niepowodzenie szyfrowania nic nie zmienia`() {
        settings.saveKey(key)
        cipher.failEncrypt = true
        val other = (EmodulKey.parse(EmodulFixtures.jwt("""{"user_id":7}"""), 0L) as EmodulKeyResult.Valid).key
        assertThrows(app.domik.data.SecretStorageException::class.java) { settings.saveKey(other) }
        cipher.failEncrypt = false
        assertEquals(4242L, settings.key()!!.userId)
    }

    @Test
    fun `zapisany klucz z data waznosci w przeszlosci nadal sie odczytuje`() {
        val expired = (EmodulKey.parse(EmodulFixtures.jwt("""{"user_id":9,"exp":1000}"""), 0L) as EmodulKeyResult.Valid).key
        settings.saveKey(expired)
        assertEquals(9L, settings.key()!!.userId)
    }

    @Test
    fun `sloty sa stale a nowy modul dostaje najmniejszy wolny`() {
        val first = settings.registerModules(listOf(udidA, udidB))
        assertEquals(listOf(EmodulSlot(1, udidA, true), EmodulSlot(2, udidB, true)), first)

        // kolejność na liście z serwera nie zmienia slotów; nowy moduł dostaje kolejny numer
        val second = settings.registerModules(listOf(udidB, udidC, udidA))
        assertEquals(listOf(1, 2, 3), second.map { it.slot })
        assertEquals(udidC, second.single { it.slot == 3 }.udid)
        assertEquals(second, EmodulSettings(store, cipher).slots())
    }

    @Test
    fun `najmniejszy wolny slot jest wybierany tak, by nie zajac istniejacego`() {
        val registry = EmodulSettings.register(listOf(EmodulSlot(2, udidA, true)), listOf(udidB))
        assertEquals(listOf(EmodulSlot(1, udidB, true), EmodulSlot(2, udidA, true)), registry)
    }

    @Test
    fun `modul znikajacy z konta zostaje w rejestrze`() {
        settings.registerModules(listOf(udidA, udidB))
        settings.registerModules(listOf(udidB))
        assertEquals(listOf(udidA, udidB), settings.slots().map { it.udid })
    }

    @Test
    fun `wylaczanie modulu jest zapamietane`() {
        settings.registerModules(listOf(udidA, udidB))

        assertTrue(settings.setEnabled(2, false))
        assertEquals(listOf(true, false), EmodulSettings(store, cipher).slots().map { it.enabled })
        assertTrue(settings.setEnabled(2, true))
        assertTrue(settings.slots().all { it.enabled })
        assertFalse(settings.setEnabled(9, false))
    }

    @Test
    fun `nieprawidlowy udid nie trafia do rejestru`() {
        val registry = settings.registerModules(listOf("../..", "a b", udidA))
        assertEquals(listOf(udidA), registry.map { it.udid })
    }

    @Test
    fun `uszkodzone wpisy rejestru sa pomijane`() {
        store.edit { putString("emodul.modules", "x|$udidA|1;0|$udidB|1;2|$udidB|7;3|$udidB|0;3|$udidC|1;4|$udidC|1;zly") }
        assertEquals(listOf(EmodulSlot(3, udidB, false), EmodulSlot(4, udidC, true)), settings.slots())
    }

    @Test
    fun `akceptacja komunikatu dotyczy wersji`() {
        settings.acceptNotice()
        assertTrue(settings.noticeAccepted)
        assertEquals(EmodulSettings.NOTICE_VERSION, settings.acceptedNoticeVersion)

        store.edit { putInt("emodul.notice", EmodulSettings.NOTICE_VERSION - 1) }
        assertFalse(settings.noticeAccepted)
    }

    @Test
    fun `removeAll usuwa klucz i rejestr ale zostawia akceptacje komunikatu`() {
        settings.saveKey(key)
        settings.registerModules(listOf(udidA))
        settings.acceptNotice()

        settings.removeAll()

        assertNull(settings.key())
        assertTrue(settings.slots().isEmpty())
        assertTrue(settings.noticeAccepted)
        assertEquals(setOf("emodul.notice"), store.values.keys)
    }

    @Test
    fun `ustawienia w pamieci dzialaja bez Keystore`() {
        val memory = EmodulSettings.inMemory()
        memory.saveKey(key)
        assertEquals(key, memory.key())
    }

    // Wybór danych na pulpicie (§6.4a)

    private fun tile(slot: Int, id: Int) = TileId.Emodul(slot, id)
    private fun zone(slot: Int, id: Int) = TileId.EmodulZone(slot, id)

    @Test
    fun `brak zapisu wyboru to pusty wybor`() {
        assertEquals(EmodulSelection.EMPTY, settings.selection(1))
    }

    @Test
    fun `wybor jest zapisany per slot i wraca po odczycie, takze strefy`() {
        val first = EmodulSelection(setOf(tile(1, 6), zone(1, 101)), setOf(tile(1, 6), tile(1, 7), zone(1, 101)))
        val second = EmodulSelection(setOf(tile(2, 1)), setOf(tile(2, 1), tile(2, 2)))
        settings.setSelection(1, first)
        settings.setSelection(2, second)

        val reread = EmodulSettings(store, cipher)
        assertEquals(first, reread.selection(1))
        assertEquals(second, reread.selection(2))
        assertEquals("e:1:6,ez:1:101", store.getString("emodul.slot.1.selected"))
        assertEquals(EmodulSelection.EMPTY, reread.selection(3))
    }

    @Test
    fun `wybrane kafelki zawsze naleza tez do znanych, a obce sloty i uszkodzone kody sa pomijane`() {
        store.edit {
            putString("emodul.slot.1.selected", "e:1:6,e:2:9,zly,e:x:1")
            putString("emodul.slot.1.known", "e:1:7")
        }
        val selection = settings.selection(1)
        assertEquals(setOf<TileId.EmodulBase>(tile(1, 6)), selection.selected)
        assertEquals(setOf<TileId.EmodulBase>(tile(1, 6), tile(1, 7)), selection.known)
    }

    @Test
    fun `zapis wyboru ignoruje kafelki innego slotu`() {
        settings.setSelection(1, EmodulSelection(setOf(tile(1, 6), tile(2, 6)), emptySet()))
        assertEquals(setOf<TileId.EmodulBase>(tile(1, 6)), settings.selection(1).selected)
    }

    @Test
    fun `pusty wybor jest zapisany jako pusty (nic nie wybrane)`() {
        settings.setSelection(1, EmodulSelection(setOf(tile(1, 6)), setOf(tile(1, 6))))
        settings.setSelection(1, EmodulSelection(emptySet(), setOf(tile(1, 6))))
        assertEquals(emptySet<TileId.EmodulBase>(), settings.selection(1).selected)
        assertEquals(setOf<TileId.EmodulBase>(tile(1, 6)), settings.selection(1).known)
    }

    @Test
    fun `removeAll usuwa tez wybor wszystkich slotow`() {
        settings.registerModules(listOf(udidA, udidB))
        settings.setSelection(1, EmodulSelection(setOf(tile(1, 6)), setOf(tile(1, 6))))
        settings.setSelection(2, EmodulSelection(setOf(tile(2, 6)), setOf(tile(2, 6))))
        settings.acceptNotice()

        settings.removeAll()

        assertEquals(EmodulSelection.EMPTY, settings.selection(1))
        assertEquals(EmodulSelection.EMPTY, settings.selection(2))
        assertEquals(setOf("emodul.notice"), store.values.keys)
    }
}
