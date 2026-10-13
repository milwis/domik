package app.domik.integrations.emodul

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmodulPolicyTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `odstepy odswiezania to 60 s 15 min i 6 h`() {
        assertEquals(60_000L, EmodulPolicy.MODULE_INTERVAL_MILLIS)
        assertEquals(15 * minute, EmodulPolicy.MENU_INTERVAL_MILLIS)
        assertEquals(6 * 60 * minute, EmodulPolicy.MODULES_INTERVAL_MILLIS)
        assertEquals(24 * 60 * minute, EmodulPolicy.I18N_INTERVAL_MILLIS)
    }

    @Test
    fun `brak danych jest zalegly od razu`() {
        assertEquals(0L, EmodulPolicy.delayUntilDue(null, now, EmodulPolicy.MODULE_INTERVAL_MILLIS))
        assertTrue(EmodulPolicy.needsRefresh(null, now, EmodulPolicy.RESUME_MAX_AGE_MILLIS))
    }

    @Test
    fun `czas do nastepnego odczytu maleje i po okresie wynosi zero`() {
        val interval = EmodulPolicy.MODULE_INTERVAL_MILLIS
        assertEquals(60_000L, EmodulPolicy.delayUntilDue(now, now, interval))
        assertEquals(45_000L, EmodulPolicy.delayUntilDue(now - 15_000L, now, interval))
        assertEquals(0L, EmodulPolicy.delayUntilDue(now - 60_000L, now, interval))
        assertEquals(0L, EmodulPolicy.delayUntilDue(now - 15 * minute, now, interval))
    }

    @Test
    fun `po powrocie na pierwszy plan odswiezamy dane starsze niz 30 s`() {
        val max = EmodulPolicy.RESUME_MAX_AGE_MILLIS
        assertFalse(EmodulPolicy.needsRefresh(now - 30_000L, now, max))
        assertTrue(EmodulPolicy.needsRefresh(now - 30_001L, now, max))
    }

    @Test
    fun `zegar cofniety o malo to wiek zero a o duzo to dane bardzo stare`() {
        val interval = EmodulPolicy.MODULE_INTERVAL_MILLIS
        assertEquals(interval, EmodulPolicy.delayUntilDue(now + minute, now, interval))
        assertEquals(0L, EmodulPolicy.delayUntilDue(now + 3 * minute, now, interval))
        assertFalse(EmodulPolicy.needsRefresh(now + minute, now, 30_000L))
        assertTrue(EmodulPolicy.needsRefresh(now + 3 * minute, now, 30_000L))
    }

    @Test
    fun `backoff to 60 120 240 s i najwyzej 900 s`() {
        assertEquals(0L, EmodulPolicy.backoffMillis(0))
        assertEquals(listOf(60L, 120L, 240L, 480L, 900L, 900L), (1..6).map { EmodulPolicy.backoffMillis(it) / 1000 })
        assertEquals(900L, EmodulPolicy.backoffMillis(1_000) / 1000)
    }

    @Test
    fun `statusy HTTP sa klasyfikowane na rodzaje niepowodzen`() {
        assertEquals(EmodulFailure.KeyRejected, EmodulPolicy.classify(EmodulHttpException(401)))
        assertEquals(EmodulFailure.KeyRejected, EmodulPolicy.classify(EmodulHttpException(403)))
        assertEquals(EmodulFailure.RateLimited(120), EmodulPolicy.classify(EmodulHttpException(429, 120)))
        assertEquals(EmodulFailure.Network, EmodulPolicy.classify(EmodulHttpException(500)))
        assertEquals(EmodulFailure.Network, EmodulPolicy.classify(EmodulHttpException(404)))
        assertEquals(EmodulFailure.Network, EmodulPolicy.classify(java.io.IOException("x")))
        assertEquals(EmodulFailure.TooLarge, EmodulPolicy.classify(EmodulResponseTooLargeException()))
        assertEquals(EmodulFailure.BadResponse, EmodulPolicy.classify(EmodulParseException()))
    }

    @Test
    fun `401 zatrzymuje odpytywanie a siec i 5xx ponawia z backoffem`() {
        assertNull(EmodulPolicy.retryDelayMillis(EmodulFailure.KeyRejected, 1))
        assertEquals(60_000L, EmodulPolicy.retryDelayMillis(EmodulFailure.Network, 1))
        assertEquals(240_000L, EmodulPolicy.retryDelayMillis(EmodulFailure.Network, 3))
        assertEquals(120_000L, EmodulPolicy.retryDelayMillis(EmodulFailure.BadResponse, 2))
        assertEquals(60_000L, EmodulPolicy.retryDelayMillis(EmodulFailure.TooLarge, 0))
    }

    @Test
    fun `429 uzywa Retry-After albo 15 minut`() {
        assertEquals(120_000L, EmodulPolicy.retryDelayMillis(EmodulFailure.RateLimited(120), 1))
        assertEquals(15 * minute, EmodulPolicy.retryDelayMillis(EmodulFailure.RateLimited(null), 1))
        assertEquals(24 * 60 * minute, EmodulPolicy.retryDelayMillis(EmodulFailure.RateLimited(Long.MAX_VALUE / 2), 1))
    }

    @Test
    fun `slownik jest pobierany gdy brak pliku zmienil sie jezyk albo brakuje id po ponad dobie`() {
        val day = EmodulPolicy.I18N_INTERVAL_MILLIS
        assertTrue(EmodulPolicy.needsFullI18n(null, true, false, now))
        assertTrue(EmodulPolicy.needsFullI18n(now, false, false, now))
        assertFalse(EmodulPolicy.needsFullI18n(now - 2 * day, true, false, now)) // nic nie brakuje – nie pobieramy
        assertFalse(EmodulPolicy.needsFullI18n(now - day + 1, true, true, now)) // brakuje, ale pobrano niedawno
        assertTrue(EmodulPolicy.needsFullI18n(now - day - 1, true, true, now))
    }

    @Test
    fun `dane sa nieaktualne po 15 minutach wedlug odczytu lub zegara sterownika`() {
        val fresh = Instant.ofEpochMilli(now - 5 * minute)
        val old = Instant.ofEpochMilli(now - 15 * minute - 1)
        assertFalse(EmodulPolicy.isStale(fresh, now, now))
        assertTrue(EmodulPolicy.isStale(old, now, now))
        assertFalse(EmodulPolicy.isStale(null, now, now)) // brak tilesLastUpdate – decyduje odczyt
        assertTrue(EmodulPolicy.isStale(fresh, now - 15 * minute - 1, now))
        assertFalse(EmodulPolicy.isStale(fresh, now - 15 * minute, now))
        assertTrue(EmodulPolicy.isStale(fresh, null, now))
    }

    @Test
    fun `tolerancja zegara 2 minuty dla stanu z przyszlosci a zegar mocno cofniety to nieaktualne`() {
        assertFalse(EmodulPolicy.isStale(Instant.ofEpochMilli(now + minute), now, now))
        assertTrue(EmodulPolicy.isStale(Instant.ofEpochMilli(now + 3 * minute), now, now))
        assertTrue(EmodulPolicy.isStale(null, now + 3 * minute, now))
    }

    @Test
    fun `prog nieaktualnosci to 15 minut`() {
        assertEquals(15 * minute, EmodulPolicy.STALE_AFTER_MILLIS)
    }

    @Test
    fun `swieze dane i modul online to Fresh a brak tilesLastUpdate nie psuje`() {
        val fresh = Instant.ofEpochMilli(now - 14 * minute)
        assertEquals(EmodulStaleness.Fresh, EmodulPolicy.staleness(fresh, now - minute, true, now))
        assertEquals(EmodulStaleness.Fresh, EmodulPolicy.staleness(null, now - minute, true, now))
    }

    @Test
    fun `cisza sterownika gdy chmura odpowiada a tilesLastUpdate jest starsze niz 15 minut`() {
        val old = Instant.ofEpochMilli(now - 15 * minute - 1)
        assertEquals(EmodulStaleness.ControllerSilent(old), EmodulPolicy.staleness(old, now - minute, true, now))
    }

    @Test
    fun `modul offline to cisza sterownika nawet ze swiezym tilesLastUpdate`() {
        val fresh = Instant.ofEpochMilli(now - minute)
        assertEquals(EmodulStaleness.ControllerSilent(fresh), EmodulPolicy.staleness(fresh, now - minute, false, now))
        assertEquals(EmodulStaleness.ControllerSilent(null), EmodulPolicy.staleness(null, now - minute, false, now))
    }

    @Test
    fun `brak udanego odczytu z chmury od 15 minut to CloudUnreachable i ma pierwszenstwo`() {
        val old = Instant.ofEpochMilli(now - 40 * minute)
        val fetched = now - 15 * minute - 1
        assertEquals(
            EmodulStaleness.CloudUnreachable(Instant.ofEpochMilli(fetched)),
            EmodulPolicy.staleness(old, fetched, false, now),
        )
        assertEquals(EmodulStaleness.CloudUnreachable(null), EmodulPolicy.staleness(old, null, true, now))
        assertEquals(EmodulStaleness.Fresh, EmodulPolicy.staleness(Instant.ofEpochMilli(now), now - 15 * minute, true, now))
    }

    @Test
    fun `pasek uwagi wybiera najgorszy stan a przy rownych najstarszy czas`() {
        val early = Instant.ofEpochMilli(1_000)
        val late = Instant.ofEpochMilli(5_000)
        assertEquals(EmodulAttention.None, EmodulAttention.worst(listOf(EmodulStaleness.Fresh)))
        assertEquals(EmodulAttention.None, EmodulAttention.worst(emptyList()))
        assertEquals(
            EmodulAttention.ControllerSilent(early),
            EmodulAttention.worst(listOf(EmodulStaleness.ControllerSilent(late), EmodulStaleness.Fresh, EmodulStaleness.ControllerSilent(early))),
        )
        assertEquals(
            EmodulAttention.CloudUnreachable(late),
            EmodulAttention.worst(listOf(EmodulStaleness.ControllerSilent(early), EmodulStaleness.CloudUnreachable(late))),
        )
        assertEquals(
            EmodulAttention.ControllerSilent(null),
            EmodulAttention.worst(listOf(EmodulStaleness.ControllerSilent(early), EmodulStaleness.ControllerSilent(null))),
        )
    }
}
