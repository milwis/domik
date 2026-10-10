package app.domik.integrations.calendar

import androidx.compose.ui.graphics.Color
import app.domik.ui.components.AgendaDay
import app.domik.ui.components.EventPhase
import app.domik.ui.components.phase
import app.domik.ui.components.phaseOfAllDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarAgendaMapperTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val today = LocalDate.of(2026, 10, 10) // sobota; w Warszawie jeszcze czas letni (UTC+2)

    private var nextId = 1L

    /** Wydarzenie całodniowe wg konwencji CalendarContract: północ UTC … północ UTC dnia po ostatnim dniu. */
    private fun allDay(
        from: LocalDate,
        toExclusive: LocalDate = from.plusDays(1),
        title: String? = "Cały dzień",
        calendarId: Long = 1,
        status: Int = CalendarStatus.NONE,
    ) = CalendarInstance(
        eventId = nextId++,
        calendarId = calendarId,
        title = title,
        begin = utcMidnight(from),
        end = utcMidnight(toExclusive),
        allDay = true,
        status = status,
    )

    private fun timed(
        start: LocalDateTime,
        end: LocalDateTime,
        title: String? = "Wydarzenie",
        zone: ZoneId = warsaw,
        calendarId: Long = 1,
        eventId: Long = nextId++,
        color: Int? = null,
        status: Int = CalendarStatus.NONE,
        self: Int = CalendarStatus.NONE,
        visible: Boolean = true,
    ) = CalendarInstance(
        eventId = eventId,
        calendarId = calendarId,
        title = title,
        begin = millis(start, zone),
        end = millis(end, zone),
        allDay = false,
        color = color,
        status = status,
        selfAttendeeStatus = self,
        calendarVisible = visible,
    )

    private fun utcMidnight(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun millis(local: LocalDateTime, zone: ZoneId) = local.atZone(zone).toInstant().toEpochMilli()

    private fun at(date: LocalDate, h: Int, m: Int = 0): LocalDateTime = date.atTime(h, m)

    private fun build(
        vararg instances: CalendarInstance,
        zone: ZoneId = warsaw,
        selection: CalendarSelection = CalendarSelection(),
    ) = CalendarAgendaMapper.build(instances.toList(), today, zone, selection)

    private fun List<AgendaDay>.day(date: LocalDate) = single { it.date == date }

    // ---------- całodniowe ----------

    @Test
    fun `calodniowe w UTC trafia na ten sam dzien w kazdej strefie`() {
        val date = LocalDate.of(2026, 10, 12)
        val zones = listOf("Europe/Warsaw", "UTC", "America/Los_Angeles", "Pacific/Auckland", "Pacific/Kiritimati", "Pacific/Pago_Pago")
        for (zone in zones) {
            val days = build(allDay(date), zone = ZoneId.of(zone))
            val withChip = days.filter { it.allDay.isNotEmpty() }.map { it.date }
            assertEquals(listOf(date), withChip, "strefa $zone")
            assertTrue(days.all { it.events.isEmpty() })
        }
    }

    @Test
    fun `calodniowe wielodniowe jest w kazdym dniu a koniec jest wylaczny`() {
        val days = build(allDay(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15), title = "Urlop"))
        assertEquals(
            listOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14)),
            days.filter { it.allDay.isNotEmpty() }.map { it.date },
        )
        assertEquals(listOf("Urlop"), days.day(LocalDate.of(2026, 10, 13)).allDay.map { it.title })
    }

    @Test
    fun `calodniowe z koncem rownym poczatkowi liczy sie jako jeden dzien`() {
        val date = LocalDate.of(2026, 10, 12)
        val days = build(allDay(date, toExclusive = date))
        assertEquals(listOf(date), days.filter { it.allDay.isNotEmpty() }.map { it.date })
    }

    @Test
    fun `calodniowe trwajace latami jest przyciete do zakresu`() {
        val days = build(allDay(LocalDate.of(2020, 1, 1), LocalDate.of(2040, 1, 1)))
        assertEquals(75, days.count { it.allDay.isNotEmpty() })
    }

    // ---------- z godziną, strefy ----------

    @Test
    fun `godziny sa przeliczane na strefe urzadzenia`() {
        // 2026-10-12 08:00 UTC = 10:00 w Warszawie (CEST) = 01:00 w Los Angeles (PDT)
        val instant = CalendarInstance(1, 1, "Spotkanie", 1_791_792_000_000L, 1_791_795_600_000L, allDay = false)
        assertEquals(LocalDateTime.of(2026, 10, 12, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), instant.begin)
        val warsawEvent = build(instant, zone = warsaw).day(LocalDate.of(2026, 10, 12)).events.single()
        assertEquals(LocalTime.of(10, 0), warsawEvent.start)
        assertEquals(LocalTime.of(11, 0), warsawEvent.end)
        val laEvent = build(instant, zone = ZoneId.of("America/Los_Angeles")).day(LocalDate.of(2026, 10, 12)).events.single()
        assertEquals(LocalTime.of(1, 0), laEvent.start)
    }

    @Test
    fun `wydarzenie o 23 30 w UTC wypada nastepnego dnia w Warszawie`() {
        val e = timed(at(LocalDate.of(2026, 10, 12), 23, 30), at(LocalDate.of(2026, 10, 12), 23, 59), zone = ZoneId.of("UTC"))
        val days = build(e, zone = warsaw)
        assertTrue(days.day(LocalDate.of(2026, 10, 12)).events.isEmpty())
        assertEquals(LocalTime.of(1, 30), days.day(LocalDate.of(2026, 10, 13)).events.single().start)
    }

    @Test
    fun `wydarzenie przez polnoc jest w obu dniach`() {
        val d = LocalDate.of(2026, 10, 12)
        val days = build(timed(at(d, 23), at(d.plusDays(1), 1)))
        val first = days.day(d).events.single()
        assertEquals(LocalTime.of(23, 0), first.start)
        assertEquals(LocalTime.MAX, first.end)
        val second = days.day(d.plusDays(1)).events.single()
        assertEquals(LocalTime.MIDNIGHT, second.start)
        assertEquals(LocalTime.of(1, 0), second.end)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `wielodniowe z godzina ma poczatek dni posrednie jako chip i koniec`() {
        val mon = LocalDate.of(2026, 10, 12)
        val days = build(timed(at(mon, 20), at(mon.plusDays(2), 10), title = "Wyjazd"))
        assertEquals(LocalTime.of(20, 0), days.day(mon).events.single().start)
        assertTrue(days.day(mon).allDay.isEmpty())
        assertTrue(days.day(mon.plusDays(1)).events.isEmpty())
        assertEquals(listOf("Wyjazd"), days.day(mon.plusDays(1)).allDay.map { it.title })
        val last = days.day(mon.plusDays(2)).events.single()
        assertEquals(LocalTime.MIDNIGHT, last.start)
        assertEquals(LocalTime.of(10, 0), last.end)
        assertTrue(days.day(mon.plusDays(2)).allDay.isEmpty())
    }

    @Test
    fun `wydarzenie konczace sie o polnocy nie zaczyna nastepnego dnia`() {
        val d = LocalDate.of(2026, 10, 12)
        val days = build(timed(at(d, 20), at(d.plusDays(1), 0)))
        assertEquals(LocalTime.MAX, days.day(d).events.single().end)
        assertTrue(days.day(d.plusDays(1)).events.isEmpty())
        assertTrue(days.day(d.plusDays(1)).allDay.isEmpty())
    }

    @Test
    fun `wydarzenie punktowe nie ma konca`() {
        val d = LocalDate.of(2026, 10, 12)
        val days = build(timed(at(d, 9), at(d, 9)))
        assertNull(days.day(d).events.single().end)
    }

    @Test
    fun `fragmenty wydarzenia wielodniowego zachowuja fazy wzgledem teraz`() {
        val mon = LocalDate.of(2026, 10, 12)
        val days = build(timed(at(mon, 20), at(mon.plusDays(2), 10)))
        val firstDay = days.day(mon).events.single()
        val lastDay = days.day(mon.plusDays(2)).events.single()
        assertEquals(1, days.day(mon.plusDays(1)).allDay.size)
        // wtorek 12:00: poniedzialkowy fragment skonczony, dzien posredni trwa, srodowy jeszcze sie nie zaczal
        val tuesdayNoon = at(mon.plusDays(1), 12)
        assertEquals(EventPhase.Ended, firstDay.phase(mon, tuesdayNoon))
        assertEquals(EventPhase.Ongoing, phaseOfAllDay(mon.plusDays(1), tuesdayNoon))
        assertEquals(EventPhase.Upcoming, lastDay.phase(mon.plusDays(2), tuesdayNoon))
        // poniedzialek 22:00: pierwszy fragment trwa
        assertEquals(EventPhase.Ongoing, firstDay.phase(mon, at(mon, 22)))
        // sroda 05:00: ostatni fragment trwa, 10:00 - koniec
        assertEquals(EventPhase.Ongoing, lastDay.phase(mon.plusDays(2), at(mon.plusDays(2), 5)))
        assertEquals(EventPhase.Ended, lastDay.phase(mon.plusDays(2), at(mon.plusDays(2), 10)))
    }

    // ---------- kolejność, tytuły, kolory ----------

    @Test
    fun `w dniu calodniowe sa na gorze potem chronologicznie`() {
        val d = LocalDate.of(2026, 10, 12)
        val days = build(
            timed(at(d, 15), at(d, 16), title = "Popołudnie"),
            allDay(d, title = "b-dzień"),
            timed(at(d, 8), at(d, 9), title = "Rano"),
            allDay(d, title = "A-dzień"),
            timed(at(d, 8), at(d, 8, 30), title = "Rano krótkie"),
            timed(at(d, 12), at(d, 13), title = "Południe"),
        )
        val day = days.day(d)
        assertEquals(listOf("A-dzień", "b-dzień"), day.allDay.map { it.title })
        assertEquals(listOf("Rano krótkie", "Rano", "Południe", "Popołudnie"), day.events.map { it.title })
    }

    @Test
    fun `wynik jest posortowany po dacie i zawiera dni puste`() {
        val days = build(timed(at(LocalDate.of(2026, 10, 20), 9), at(LocalDate.of(2026, 10, 20), 10)))
        assertEquals(days.map { it.date }.sorted(), days.map { it.date })
        assertTrue(days.day(today).let { it.allDay.isEmpty() && it.events.isEmpty() })
    }

    @Test
    fun `tytul bez wartosci jest pusty a biale znaki sa sciskane`() {
        val d = LocalDate.of(2026, 10, 12)
        val days = build(
            timed(at(d, 9), at(d, 10), title = null),
            timed(at(d, 11), at(d, 12), title = "  Wizyta \n u  lekarza  "),
        )
        assertEquals(listOf("", "Wizyta u lekarza"), days.day(d).events.map { it.title })
    }

    @Test
    fun `kolor wydarzenia jest nieprzezroczysty a brak koloru to null`() {
        val d = LocalDate.of(2026, 10, 12)
        val days = build(
            timed(at(d, 9), at(d, 10), color = 0x00336699),
            timed(at(d, 11), at(d, 12)),
        )
        val events = days.day(d).events
        assertEquals(Color(0xFF336699.toInt()), events[0].color)
        assertNull(events[1].color)
    }

    // ---------- pomijanie ----------

    @Test
    fun `odwolane sa pomijane`() {
        val d = LocalDate.of(2026, 10, 12)
        val days = build(
            timed(at(d, 9), at(d, 10), title = "Odwołane", status = CalendarStatus.CANCELED),
            timed(at(d, 11), at(d, 12), title = "Zostaje"),
            allDay(d, title = "Odwołane całodniowe", status = CalendarStatus.CANCELED),
        )
        assertEquals(listOf("Zostaje"), days.day(d).events.map { it.title })
        assertTrue(days.day(d).allDay.isEmpty())
    }

    @Test
    fun `odrzucone przez uzytkownika sa pomijane`() {
        val d = LocalDate.of(2026, 10, 12)
        val days = build(
            timed(at(d, 9), at(d, 10), title = "Odrzucone", self = CalendarStatus.ATTENDEE_DECLINED),
            timed(at(d, 11), at(d, 12), title = "Przyjęte", self = 1),
        )
        assertEquals(listOf("Przyjęte"), days.day(d).events.map { it.title })
    }

    @Test
    fun `filtr kalendarzy ukrywa wybrane i niewidoczne w systemie`() {
        val d = LocalDate.of(2026, 10, 12)
        val instances = arrayOf(
            timed(at(d, 8), at(d, 9), title = "Jan", calendarId = 1),
            timed(at(d, 9), at(d, 10), title = "Rodzina", calendarId = 2),
            timed(at(d, 10), at(d, 11), title = "Ukryty w systemie", calendarId = 3, visible = false),
            allDay(d, title = "Święta", calendarId = 2),
        )
        val all = build(*instances)
        assertEquals(listOf("Jan", "Rodzina"), all.day(d).events.map { it.title })
        val hidden = build(*instances, selection = CalendarSelection(setOf(2L)))
        assertEquals(listOf("Jan"), hidden.day(d).events.map { it.title })
        assertTrue(hidden.day(d).allDay.isEmpty())
    }

    @Test
    fun `ta sama instancja podana dwa razy jest jedna`() {
        val d = LocalDate.of(2026, 10, 12)
        val e = timed(at(d, 9), at(d, 10), eventId = 77)
        assertEquals(1, build(e, e.copy()).day(d).events.size)
    }

    @Test
    fun `powtorzenia tego samego wydarzenia cyklicznego maja rozne identyfikatory`() {
        val d = LocalDate.of(2026, 10, 12)
        val days = build(
            timed(at(d, 9), at(d, 10), eventId = 5),
            timed(at(d, 14), at(d, 15), eventId = 5),
            timed(at(d.plusDays(1), 9), at(d.plusDays(1), 10), eventId = 5),
        )
        val ids = days.flatMap { it.events }.map { it.id }
        assertEquals(3, ids.distinct().size)
        assertEquals(5L, CalendarEventRef.decode(ids.first())?.eventId)
    }

    // ---------- zakres ----------

    @Test
    fun `zakres to 14 dni wstecz i 60 naprzod wlacznie z dniami pustymi`() {
        val days = build()
        assertEquals(75, days.size)
        assertEquals(today.minusDays(14), days.first().date)
        assertEquals(today.plusDays(60), days.last().date)
        assertEquals(days.map { it.date }.distinct().size, days.size)
    }

    @Test
    fun `granice zakresu - pierwszy i ostatni dzien tak, dzien przed i po nie`() {
        val first = today.minusDays(14)
        val last = today.plusDays(60)
        val days = build(
            timed(at(first, 0), at(first, 1), title = "pierwszy 00:00"),
            timed(at(first.minusDays(1), 23, 30), at(first.minusDays(1), 23, 59), title = "przed zakresem"),
            timed(at(last, 23, 30), at(last, 23, 59), title = "ostatni 23:30"),
            timed(at(last.plusDays(1), 0), at(last.plusDays(1), 1), title = "po zakresie"),
        )
        assertEquals(listOf("pierwszy 00:00"), days.day(first).events.map { it.title })
        assertEquals(listOf("ostatni 23:30"), days.day(last).events.map { it.title })
        assertEquals(2, days.sumOf { it.events.size })
    }

    @Test
    fun `calodniowe na granicach zakresu`() {
        val first = today.minusDays(14)
        val last = today.plusDays(60)
        val days = build(
            allDay(first.minusDays(1), title = "przed"),
            allDay(first, title = "pierwszy"),
            allDay(last, title = "ostatni"),
            allDay(last.plusDays(1), title = "po"),
        )
        assertEquals(listOf("pierwszy"), days.day(first).allDay.map { it.title })
        assertEquals(listOf("ostatni"), days.day(last).allDay.map { it.title })
        assertEquals(2, days.sumOf { it.allDay.size })
    }

    @Test
    fun `wydarzenie zaczete przed zakresem pokazuje sie od pierwszego dnia`() {
        val first = today.minusDays(14)
        val days = build(timed(at(first.minusDays(3), 10), at(first.plusDays(1), 12), title = "Długie"))
        // pierwszy dzien zakresu: wydarzenie trwa od polnocy... jest chipem; ostatni dzien: fragment do 12:00
        assertEquals(listOf("Długie"), days.day(first).allDay.map { it.title })
        assertEquals(LocalTime.of(12, 0), days.day(first.plusDays(1)).events.single().end)
        assertEquals(1, days.sumOf { it.events.size })
    }

    @Test
    fun `zapytanie obejmuje caly zakres z zapasem doby`() {
        val (from, to) = CalendarWindow.queryRange(today, warsaw)
        assertTrue(from <= millis(today.minusDays(14).atStartOfDay(), warsaw))
        assertTrue(to >= millis(today.plusDays(61).atStartOfDay(), warsaw))
        assertTrue(from > millis(today.minusDays(16).atStartOfDay(), warsaw))
        assertTrue(to < millis(today.plusDays(64).atStartOfDay(), warsaw))
    }

    // ---------- odnośnik do wydarzenia i wybór ----------

    @Test
    fun `odnosnik do wydarzenia koduje sie i odczytuje`() {
        val ref = CalendarEventRef(42, 1_791_792_000_000L, 1_791_795_600_000L)
        assertEquals(ref, CalendarEventRef.decode(ref.encode()))
        assertNull(CalendarEventRef.decode("e0a"))
        assertNull(CalendarEventRef.decode("1:2"))
        assertNull(CalendarEventRef.decode("1:x:3"))
    }

    @Test
    fun `wybor kalendarzy koduje sie i odczytuje a smieci sa pomijane`() {
        val selection = CalendarSelection(setOf(7L, 3L, 12L))
        assertEquals("3,7,12", selection.encode())
        assertEquals(selection, CalendarSelection.decode(selection.encode()))
        assertEquals(CalendarSelection(), CalendarSelection.decode(null))
        assertEquals(CalendarSelection(), CalendarSelection.decode(""))
        assertEquals(CalendarSelection(setOf(5L)), CalendarSelection.decode("x, 5 ,,y"))
        assertTrue(selection.isVisible(1))
        assertTrue(!selection.isVisible(7))
        assertEquals(CalendarSelection(setOf(3L, 12L)), selection.withVisible(7, true))
        assertEquals(selection, selection.withVisible(7, false))
    }

    @Test
    fun `trwala odmowa tylko po odmowie bez uzasadnienia`() {
        assertTrue(isCalendarAccessBlocked(granted = false, shouldShowRationale = false))
        assertTrue(!isCalendarAccessBlocked(granted = false, shouldShowRationale = true))
        assertTrue(!isCalendarAccessBlocked(granted = true, shouldShowRationale = false))
    }
}
