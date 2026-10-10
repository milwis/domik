package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KioskModelTest {
    private class FakePlatform : KioskPlatform {
        override var homeAliasEnabled = false
        override var isDefaultHome = false
        var settingsOpened = 0
        var settingsAvailable = true
        override var isPinned = false
        override var pinningEnabledInSystem: Boolean? = true
        var startFails = false
        var stopFails = false

        override fun openHomeSettings(): Boolean {
            if (!settingsAvailable) return false
            settingsOpened++
            return true
        }

        override fun startPinning(): Boolean {
            if (startFails) return false
            isPinned = true
            return true
        }

        override fun stopPinning(): Boolean {
            if (stopFails) return false
            isPinned = false
            return true
        }
    }

    private val store = InMemoryKeyValueStore()
    private val platform = FakePlatform()
    private fun model() = KioskModel(AppSettings(store, FakeCipher()), platform)

    @Test
    fun `stan poczatkowy odzwierciedla domyslne ustawienia i platforme`() {
        val state = model().state.value
        assertEquals(KioskSettings(), state.settings)
        assertFalse(state.homeAliasEnabled)
        assertFalse(state.isDefaultHome)
        assertFalse(state.pinned)
        assertNull(state.notice)
    }

    @Test
    fun `przelaczniki ekranu i pelnego ekranu sa zapisywane`() {
        val model = model()
        model.setKeepScreenOnWhenPowered(false)
        model.setFullscreen(false)
        assertEquals(KioskSettings(keepScreenOnWhenPowered = false, fullscreen = false), model.state.value.settings)
        // przetrwaly w magazynie, wiec nowy model je widzi
        assertEquals(KioskSettings(keepScreenOnWhenPowered = false, fullscreen = false), model().state.value.settings)
    }

    @Test
    fun `wlaczenie ekranu glownego wlacza alias i otwiera ustawienia`() {
        val model = model()
        model.setHomeLauncher(true)
        assertTrue(platform.homeAliasEnabled)
        assertEquals(1, platform.settingsOpened)
        assertTrue(model.state.value.homeAliasEnabled)
        // Domik nie jest jeszcze wybrany w systemie, dopoki uzytkownik tego nie zrobi
        assertFalse(model.state.value.isDefaultHome)
    }

    @Test
    fun `po wyborze w ustawieniach odswiezenie pokazuje Domika jako ekran glowny`() {
        val model = model()
        model.setHomeLauncher(true)
        platform.isDefaultHome = true
        model.refresh()
        assertTrue(model.state.value.isDefaultHome)
    }

    @Test
    fun `wylaczenie gdy Domik jest ekranem glownym wylacza alias i otwiera ustawienia`() {
        platform.homeAliasEnabled = true
        platform.isDefaultHome = true
        val model = model()
        model.setHomeLauncher(false)
        assertFalse(platform.homeAliasEnabled)
        assertEquals(1, platform.settingsOpened)
        assertFalse(model.state.value.homeAliasEnabled)
    }

    @Test
    fun `wylaczenie gdy Domik nie byl wybrany nie otwiera ustawien`() {
        platform.homeAliasEnabled = true
        val model = model()
        model.setHomeLauncher(false)
        assertFalse(platform.homeAliasEnabled)
        assertEquals(0, platform.settingsOpened)
    }

    @Test
    fun `brak ustawien ekranu glownego daje komunikat ale alias zostaje przelaczony`() {
        platform.settingsAvailable = false
        val model = model()
        model.setHomeLauncher(true)
        assertTrue(platform.homeAliasEnabled)
        assertEquals(KioskNotice.HOME_SETTINGS_UNAVAILABLE, model.state.value.notice)
        model.dismissNotice()
        assertNull(model.state.value.notice)
    }

    @Test
    fun `przypiecie uruchamia przypinanie i pokazuje odepnij`() {
        val model = model()
        model.pin()
        assertTrue(model.state.value.pinned)
        assertNull(model.state.value.notice)
        model.unpin()
        assertFalse(model.state.value.pinned)
        assertNull(model.state.value.notice)
    }

    @Test
    fun `przypinanie wylaczone w systemie nie jest uruchamiane i daje komunikat`() {
        platform.pinningEnabledInSystem = false
        val model = model()
        model.pin()
        assertFalse(platform.isPinned)
        assertEquals(KioskNotice.PINNING_DISABLED, model.state.value.notice)
    }

    @Test
    fun `nieznany stan przypinania w systemie nie blokuje proby`() {
        platform.pinningEnabledInSystem = null
        val model = model()
        model.pin()
        assertTrue(platform.isPinned)
        assertNull(model.state.value.notice)
    }

    @Test
    fun `blad przypinania daje komunikat o wlaczeniu przypinania`() {
        platform.startFails = true
        val model = model()
        model.pin()
        assertEquals(KioskNotice.PINNING_DISABLED, model.state.value.notice)
    }

    @Test
    fun `odmowa odpinania daje komunikat o gescie`() {
        platform.isPinned = true
        platform.stopFails = true
        val model = model()
        assertTrue(model.state.value.pinned)
        model.unpin()
        assertTrue(model.state.value.pinned)
        assertEquals(KioskNotice.UNPIN_FAILED, model.state.value.notice)
    }

    @Test
    fun `odswiezenie zauwaza odpiecie gestem i zachowuje komunikat`() {
        platform.isPinned = true
        platform.stopFails = true
        val model = model()
        model.unpin()
        platform.isPinned = false // uzytkownik odpial gestem
        model.refresh()
        assertFalse(model.state.value.pinned)
        assertEquals(KioskNotice.UNPIN_FAILED, model.state.value.notice)
    }
}
