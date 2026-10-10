package app.domik.ui.components

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgendaLogicTest {

    private val today = LocalDate.of(2026, 10, 10) // sobota
    private fun at(h: Int, m: Int = 0, date: LocalDate = today): LocalDateTime = date.atTime(h, m)
    private fun time(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private fun event(id: String, start: LocalTime, end: LocalTime? = null) =
        AgendaEvent(id = id, start = start, title = id, end = end)

    // ---------- klasyfikacja ----------

    @Test
    fun `wydarzenie przed poczatkiem jest nadchodzace`() {
        val e = event("e", time(10), time(11))
        assertEquals(EventPhase.Upcoming, e.phase(today, at(9, 59)))
    }

    @Test
    fun `poczatek rowny teraz oznacza trwa`() {
        val e = event("e", time(10), time(11))
        assertEquals(EventPhase.Ongoing, e.phase(today, at(10, 0)))
        assertEquals(EventPhase.Ongoing, e.phase(today, at(10, 59)))
    }

    @Test
    fun `koniec rowny teraz oznacza zakonczone`() {
        val e = event("e", time(10), time(11))
        assertEquals(EventPhase.Ended, e.phase(today, at(11, 0)))
        assertEquals(EventPhase.Ended, e.phase(today, at(23, 59)))
    }

    @Test
    fun `wydarzenie bez konca konczy sie w chwili poczatku i nigdy nie trwa`() {
        val e = event("e", time(10))
        assertEquals(EventPhase.Upcoming, e.phase(today, at(9, 59)))
        assertEquals(EventPhase.Ended, e.phase(today, at(10, 0)))
        val zero = event("z", time(10), time(10))
        assertEquals(EventPhase.Ended, zero.phase(today, at(10, 0)))
    }

    @Test
    fun `wydarzenie przez polnoc trwa do nastepnego dnia`() {
        val e = event("e", time(23), time(1))
        assertEquals(EventPhase.Upcoming, e.phase(today, at(22, 59)))
        assertEquals(EventPhase.Ongoing, e.phase(today, at(23, 30)))
        assertEquals(EventPhase.Ongoing, e.phase(today, at(0, 30, today.plusDays(1))))
        assertEquals(EventPhase.Ended, e.phase(today, at(1, 0, today.plusDays(1))))
    }

    @Test
    fun `wydarzenie sprzed dni jest zakonczone, a z przyszlych dni nadchodzace`() {
        val e = event("e", time(10), time(11))
        assertEquals(EventPhase.Ended, e.phase(today.minusDays(3), at(9)))
        assertEquals(EventPhase.Upcoming, e.phase(today.plusDays(2), at(23, 59)))
    }

    @Test
    fun `calodniowe trwa przez caly swoj dzien`() {
        assertEquals(EventPhase.Ended, phaseOfAllDay(today.minusDays(1), at(0, 0)))
        assertEquals(EventPhase.Ongoing, phaseOfAllDay(today, at(0, 0)))
        assertEquals(EventPhase.Ongoing, phaseOfAllDay(today, at(23, 59)))
        assertEquals(EventPhase.Upcoming, phaseOfAllDay(today.plusDays(1), at(23, 59)))
        // po północy wczorajsze całodniowe jest już zakończone
        assertEquals(EventPhase.Ended, phaseOfAllDay(today, at(0, 0, today.plusDays(1))))
    }

    // ---------- etykiety dni ----------

    @Test
    fun `etykiety dnia wzgledem dzis`() {
        assertEquals(DayLabel.Today, dayLabel(today, today))
        assertEquals(DayLabel.Tomorrow, dayLabel(today.plusDays(1), today))
        assertEquals(DayLabel.Yesterday, dayLabel(today.minusDays(1), today))
        // piątek 16.10 = weekdayIndex 4 (pon = 0)
        assertEquals(DayLabel.Date(4, 16, 10), dayLabel(LocalDate.of(2026, 10, 16), today))
        assertEquals(DayLabel.Date(0, 12, 10), dayLabel(LocalDate.of(2026, 10, 12), today))
    }

    @Test
    fun `etykiety przesuwaja sie o polnocy`() {
        val saturday = today
        val sunday = today.plusDays(1)
        val monday = today.plusDays(2)

        // 23:59 w sobotę: sobota = dziś, niedziela = jutro
        var now = at(23, 59)
        assertEquals(DayLabel.Today, dayLabel(saturday, now.toLocalDate()))
        assertEquals(DayLabel.Tomorrow, dayLabel(sunday, now.toLocalDate()))

        // 00:00 w niedzielę: sobota = wczoraj, niedziela = dziś, poniedziałek = jutro
        now = at(0, 0, sunday)
        assertEquals(DayLabel.Yesterday, dayLabel(saturday, now.toLocalDate()))
        assertEquals(DayLabel.Today, dayLabel(sunday, now.toLocalDate()))
        assertEquals(DayLabel.Tomorrow, dayLabel(monday, now.toLocalDate()))
    }

    @Test
    fun `granica miesiaca i roku`() {
        val dec31 = LocalDate.of(2026, 12, 31)
        assertEquals(DayLabel.Tomorrow, dayLabel(LocalDate.of(2027, 1, 1), dec31))
        assertEquals(DayLabel.Yesterday, dayLabel(dec31, LocalDate.of(2027, 1, 1)))
        assertEquals(DayLabel.Date(4, 1, 1), dayLabel(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 20)))
    }

    // ---------- lista wierszy ----------

    private fun day(date: LocalDate, vararg events: AgendaEvent, allDay: List<AgendaAllDayEvent> = emptyList()) =
        AgendaDay(date, allDay, events.toList())

    @Test
    fun `wiersze sa sortowane i zawieraja naglowki, calodniowe i brak wydarzen`() {
        val rows = buildAgendaRows(
            listOf(
                day(today.plusDays(1)), // pusty
                day(
                    today,
                    event("b", time(14)),
                    event("a", time(9)),
                    allDay = listOf(AgendaAllDayEvent("x", "Urlop")),
                ),
            ),
        )
        val kinds = rows.map { it::class.simpleName }
        assertEquals(listOf("Header", "AllDay", "Event", "Event", "Header", "NoEvents"), kinds)
        assertEquals(today, rows[0].date)
        assertEquals("a", (rows[2] as AgendaRow.Event).event.id)
        assertEquals("b", (rows[3] as AgendaRow.Event).event.id)
        assertEquals(today.plusDays(1), rows[4].date)
    }

    @Test
    fun `klucze wierszy sa unikalne`() {
        val rows = buildAgendaRows(
            listOf(
                day(today, event("a", time(9)), event("b", time(9))),
                day(today.plusDays(1), event("a", time(9))),
            ),
        )
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    // ---------- kotwica ----------

    private val rows = buildAgendaRows(
        listOf(
            day(today.minusDays(1), event("y1", time(10), time(11))),
            day(
                today,
                event("t1", time(8), time(9)),
                event("t2", time(12), time(13)),
                event("t3", time(18), time(19)),
            ),
            day(today.plusDays(1), event("n1", time(9), time(10))),
        ),
    )

    private fun indexOfEvent(id: String) = rows.indexOfFirst { it is AgendaRow.Event && it.event.id == id }
    private fun indexOfHeader(date: LocalDate) = rows.indexOfFirst { it is AgendaRow.Header && it.date == date }

    @Test
    fun `kotwica to pierwsze niezakonczone wydarzenie dzisiaj`() {
        assertEquals(indexOfEvent("t1"), anchorIndex(rows, at(7, 59)))
        assertEquals(indexOfEvent("t2"), anchorIndex(rows, at(9, 0))) // t1 zakończone dokładnie teraz
        assertEquals(indexOfEvent("t2"), anchorIndex(rows, at(12, 30))) // t2 trwa – nadal kotwica
        assertEquals(indexOfEvent("t3"), anchorIndex(rows, at(13, 0)))
    }

    @Test
    fun `zakonczone dzisiejsze zostaja nad kotwica`() {
        val anchor = anchorIndex(rows, at(13, 0))
        assertTrue(indexOfEvent("t1") < anchor)
        assertTrue(indexOfEvent("t2") < anchor)
        assertTrue(indexOfEvent("y1") < anchor)
    }

    @Test
    fun `gdy wszystko zakonczone kotwica to naglowek dzisiaj`() {
        assertEquals(indexOfHeader(today), anchorIndex(rows, at(19, 0)))
        assertEquals(indexOfHeader(today), anchorIndex(rows, at(23, 59)))
    }

    @Test
    fun `po polnocy kotwica przesuwa sie na nowy dzien`() {
        val sunday = today.plusDays(1)
        // 23:59 w sobotę wszystko dzisiejsze skończone -> nagłówek soboty
        assertEquals(indexOfHeader(today), anchorIndex(rows, at(23, 59)))
        // 00:00 w niedzielę: dziś = niedziela, pierwsze niezakończone = n1
        assertEquals(indexOfEvent("n1"), anchorIndex(rows, at(0, 0, sunday)))
    }

    @Test
    fun `calodniowe dzisiaj kotwiczy na naglowku dnia`() {
        val withAllDay = buildAgendaRows(
            listOf(
                day(today, event("t1", time(12), time(13)), allDay = listOf(AgendaAllDayEvent("u", "Urlop"))),
            ),
        )
        assertEquals(0, anchorIndex(withAllDay, at(8)))
    }

    @Test
    fun `gdy dzisiaj nie ma w danych kotwica to najblizszy pozniejszy dzien`() {
        val future = buildAgendaRows(
            listOf(
                day(today.minusDays(2), event("p", time(10), time(11))),
                day(today.plusDays(3), event("f", time(10), time(11))),
            ),
        )
        assertEquals(future.indexOfFirst { it is AgendaRow.Header && it.date == today.plusDays(3) }, anchorIndex(future, at(9)))
    }

    @Test
    fun `gdy sa tylko dni minione kotwica to ostatni wiersz`() {
        val past = buildAgendaRows(listOf(day(today.minusDays(2), event("p", time(10), time(11)))))
        assertEquals(past.lastIndex, anchorIndex(past, at(9)))
    }

    @Test
    fun `pusta lista ma kotwice zero`() {
        assertEquals(0, anchorIndex(emptyList(), at(9)))
    }

    @Test
    fun `dzien dzisiejszy bez wydarzen kotwiczy na naglowku`() {
        val empty = buildAgendaRows(listOf(day(today), day(today.plusDays(1), event("n", time(9), time(10)))))
        assertEquals(0, anchorIndex(empty, at(9)))
    }

    // ---------- bezczynność ----------

    @Test
    fun `powrot do kotwicy po okresie bezczynnosci`() {
        assertEquals(0L, remainingIdleMillis(nowMs = 1_000, lastTouchMs = NEVER_TOUCHED, idleMillis = 90_000))
        assertEquals(90_000L, remainingIdleMillis(nowMs = 5_000, lastTouchMs = 5_000, idleMillis = 90_000))
        assertEquals(30_000L, remainingIdleMillis(nowMs = 65_000, lastTouchMs = 5_000, idleMillis = 90_000))
        assertEquals(0L, remainingIdleMillis(nowMs = 95_000, lastTouchMs = 5_000, idleMillis = 90_000))
        assertEquals(0L, remainingIdleMillis(nowMs = 500_000, lastTouchMs = 5_000, idleMillis = 90_000))
    }
}
