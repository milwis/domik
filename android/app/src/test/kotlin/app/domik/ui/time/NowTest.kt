package app.domik.ui.time

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NowTest {
    @Test
    fun `do nastepnej minuty`() {
        assertEquals(60_000L, millisUntilNextMinute(LocalDateTime.of(2026, 10, 10, 12, 30, 0)))
        assertEquals(30_000L, millisUntilNextMinute(LocalDateTime.of(2026, 10, 10, 12, 30, 30)))
        assertEquals(1_000L, millisUntilNextMinute(LocalDateTime.of(2026, 10, 10, 12, 30, 59)))
        assertEquals(1L, millisUntilNextMinute(LocalDateTime.of(2026, 10, 10, 12, 30, 59, 999_000_000)))
    }

    @Test
    fun `o polnocy tez`() {
        assertEquals(1_000L, millisUntilNextMinute(LocalDateTime.of(2026, 10, 10, 23, 59, 59)))
    }
}
