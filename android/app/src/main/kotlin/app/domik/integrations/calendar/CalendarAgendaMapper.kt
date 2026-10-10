package app.domik.integrations.calendar

import androidx.compose.ui.graphics.Color
import app.domik.ui.components.AgendaAllDayEvent
import app.domik.ui.components.AgendaDay
import app.domik.ui.components.AgendaEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Mapowanie instancji z kalendarza systemowego na dni harmonogramu ([AgendaDay]) – czysta funkcja, bez Androida.
 *
 *  - **Całodniowe:** w `CalendarContract` mają północ UTC (początek) i północ UTC dnia po ostatnim dniu (koniec
 *    wyłączny), więc datę czytamy w UTC – nigdy w strefie lokalnej (inaczej wypadłyby o dzień wcześniej/później).
 *    Wielodniowe są chipem w każdym dniu, którego dotyczą.
 *  - **Z godziną:** czas przeliczany na strefę [zone] urządzenia. Wydarzenie przechodzące przez północ jest
 *    pokazane w każdym dniu: pierwszego dnia od godziny rozpoczęcia do końca doby ([LocalTime.MAX]), dni
 *    pośrednie jako chip całodniowy, ostatniego dnia od 00:00 do godziny zakończenia.
 *  - **Pomijane:** odwołane, odrzucone przez użytkownika, z kalendarzy niewidocznych w systemie albo wyłączonych
 *    w aplikacji ([CalendarSelection]), spoza zakresu [CalendarWindow].
 *  - **Kolejność w dniu:** całodniowe (chipy) wg tytułu, potem wydarzenia z godziną chronologicznie.
 */
object CalendarAgendaMapper {
    /**
     * @return każdy dzień od [CalendarWindow.firstDay] do [CalendarWindow.lastDay] włącznie (także puste – tak chce
     * kafelek: „Brak wydarzeń”), rosnąco po dacie
     */
    fun build(
        instances: List<CalendarInstance>,
        today: LocalDate,
        zone: ZoneId,
        selection: CalendarSelection = CalendarSelection(),
    ): List<AgendaDay> {
        val first = CalendarWindow.firstDay(today)
        val last = CalendarWindow.lastDay(today)
        val allDay = HashMap<LocalDate, MutableList<AgendaAllDayEvent>>()
        val timed = HashMap<LocalDate, MutableList<AgendaEvent>>()
        val seen = HashSet<Pair<Long, Long>>()

        for (instance in instances) {
            if (!instance.isShown(selection)) continue
            if (!seen.add(instance.eventId to instance.begin)) continue // ta sama instancja dwa razy – jedna wystarczy
            val id = CalendarEventRef(instance.eventId, instance.begin, instance.end).encode()
            val title = instance.title.orEmpty().trim().replace(WHITESPACE, " ")
            val color = instance.color?.let { Color(it or OPAQUE) }

            if (instance.allDay) {
                for (date in allDayDates(instance, first, last)) {
                    allDay.getOrPut(date) { ArrayList() }.add(AgendaAllDayEvent(id, title, color))
                }
            } else {
                val start = instant(instance.begin, zone)
                val end = instant(instance.end, zone)
                val lastDate = if (end.isAfter(start)) end.minusNanos(1).toLocalDate() else start.toLocalDate()
                var date = maxOf(start.toLocalDate(), first)
                val limit = minOf(lastDate, last)
                while (!date.isAfter(limit)) {
                    when {
                        // dni pośrednie wydarzenia wielodniowego – chip całodniowy
                        date.isAfter(start.toLocalDate()) && date.isBefore(lastDate) ->
                            allDay.getOrPut(date) { ArrayList() }.add(AgendaAllDayEvent(id, title, color))
                        else -> timed.getOrPut(date) { ArrayList() }.add(slice(id, title, color, start, end, date))
                    }
                    date = date.plusDays(1)
                }
            }
        }

        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { date ->
            AgendaDay(
                date = date,
                allDay = allDay[date].orEmpty().sortedWith(compareBy({ it.title.lowercase() }, { it.id })),
                events = timed[date].orEmpty()
                    .sortedWith(compareBy<AgendaEvent>({ it.start }, { it.end ?: LocalTime.MIN }, { it.title.lowercase() }, { it.id })),
            )
        }.toList()
    }

    /** Fragment wydarzenia z godziną przypadający na dzień [date] (początek lub koniec – albo całość – tego dnia). */
    private fun slice(
        id: String,
        title: String,
        color: Color?,
        start: LocalDateTime,
        end: LocalDateTime,
        date: LocalDate,
    ): AgendaEvent {
        val from = if (start.toLocalDate().isBefore(date)) LocalTime.MIDNIGHT else start.toLocalTime()
        val to = when {
            !end.isAfter(start) -> null // wydarzenie punktowe
            end.toLocalDate().isAfter(date) -> LocalTime.MAX // trwa do końca doby (także gdy kończy się o północy)
            else -> end.toLocalTime()
        }
        return AgendaEvent(id = id, start = from, title = title, end = to, color = color)
    }

    /**
     * Daty wydarzenia całodniowego z przedziału [from]..[to] (daty UTC; koniec wyłączny, co najmniej jeden dzień).
     * Przycięcie do przedziału chroni przed wydarzeniami trwającymi latami.
     */
    private fun allDayDates(instance: CalendarInstance, from: LocalDate, to: LocalDate): List<LocalDate> {
        val firstDate = utcDate(instance.begin)
        val endExclusive = utcDate(instance.end).let { if (it.isAfter(firstDate)) it else firstDate.plusDays(1) }
        val start = maxOf(firstDate, from)
        val limit = minOf(endExclusive.minusDays(1), to)
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(limit) }.toList()
    }

    private fun CalendarInstance.isShown(selection: CalendarSelection): Boolean =
        calendarVisible &&
            status != CalendarStatus.CANCELED &&
            selfAttendeeStatus != CalendarStatus.ATTENDEE_DECLINED &&
            selection.isVisible(calendarId)

    private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()

    private fun instant(millis: Long, zone: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    private val WHITESPACE = Regex("\\s+")
    private const val OPAQUE = 0xFF000000.toInt()
}
