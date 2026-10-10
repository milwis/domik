package app.domik.ui.dashboard

import androidx.compose.ui.graphics.Color
import app.domik.ui.components.AgendaAllDayEvent
import app.domik.ui.components.AgendaDay
import app.domik.ui.components.AgendaEvent
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Kalendarz przykładowy **tylko dla trybu demo** (w trybie sterownika kafelek czyta prawdziwy kalendarz z
 * urządzenia, `integrations.calendar`). Pogoda przykładowa trybu demo: [DemoWeather]. Nie zawiera żadnych danych
 * prawdziwego domu; wydarzenia są zmyślone.
 */
object PlaceholderData {
    private val calendarBlue = Color(0xFF4C8DF6)
    private val calendarOrange = Color(0xFFE0813A)

    /**
     * Fikcyjny harmonogram wokół [now]: 2 dni wstecz, 10 dni naprzód (część dni pusta). Dzisiejsze wydarzenia
     * są ustawione względem pełnej godziny [now], żeby w podglądzie zawsze było jedno zakończone, jedno trwające
     * („teraz”) i jedno nadchodzące (o ile mieszczą się w dobie).
     */
    fun agenda(now: LocalDateTime): List<AgendaDay> = buildList {
        val today = now.toLocalDate()
        val hour = now.hour
        val todayEvents = buildList {
            if (hour >= 2) add(AgendaEvent("e0a", LocalTime.of(hour - 2, 0), "Spotkanie zespołu", LocalTime.of(hour - 1, 0), calendarBlue))
            add(AgendaEvent("e0b", LocalTime.of(hour, 0), "Obiad", LocalTime.of((hour + 1) % 24, 0), calendarOrange))
            if (hour <= 20) add(AgendaEvent("e0c", LocalTime.of(hour + 2, 0), "Wizyta u dentysty", LocalTime.of(hour + 3, 0), calendarBlue))
        }
        for (offset in -2..10) {
            val date = today.plusDays(offset.toLong())
            add(
                when (offset) {
                    -2 -> AgendaDay(
                        date,
                        events = listOf(AgendaEvent("e-2", LocalTime.of(17, 0), "Zakupy", color = calendarBlue)),
                    )
                    0 -> AgendaDay(
                        date,
                        allDay = listOf(AgendaAllDayEvent("a0", "Wywóz śmieci", calendarOrange)),
                        events = todayEvents,
                    )
                    1 -> AgendaDay(
                        date,
                        events = listOf(AgendaEvent("e1", LocalTime.of(9, 30), "Przegląd samochodu", color = calendarBlue)),
                    )
                    3 -> AgendaDay(
                        date,
                        allDay = listOf(AgendaAllDayEvent("a3", "Urodziny Ani", calendarOrange)),
                        events = listOf(AgendaEvent("e3", LocalTime.of(19, 0), "Kolacja u rodziców", color = calendarOrange)),
                    )
                    5 -> AgendaDay(
                        date,
                        events = listOf(
                            AgendaEvent("e5a", LocalTime.of(10, 0), "Fryzjer", color = calendarBlue),
                            AgendaEvent("e5b", LocalTime.of(16, 15), "Odbiór paczki", color = calendarBlue),
                        ),
                    )
                    else -> AgendaDay(date)
                },
            )
        }
    }
}
