package app.domik.ui.components

import java.time.LocalDate
import java.time.LocalDateTime

/*
 * Czysta logika harmonogramu (bez Compose) – klasyfikacja wydarzeń względem „teraz”, etykiety dni,
 * płaska lista wierszy i kotwica przewijania. Wszystko zależy wyłącznie od przekazanego `now`,
 * więc działa tak samo w teście, w podglądzie i o północy na tablecie.
 */

/** Stan wydarzenia względem „teraz”. */
enum class EventPhase {
    /** Zakończone (koniec <= teraz) – przygaszone. */
    Ended,

    /** Trwa (początek <= teraz < koniec) – wyróżnione znacznikiem „teraz”. */
    Ongoing,

    /** Jeszcze się nie zaczęło – normalne. */
    Upcoming,
}

/**
 * Faza wydarzenia z godziną w dniu [date]. Wydarzenie bez końca (albo o zerowej długości) kończy się w chwili
 * rozpoczęcia – nigdy nie jest „trwające”. Koniec wcześniejszy niż początek oznacza następny dzień
 * (np. 23:00–01:00). Granice: koniec == teraz → zakończone; początek == teraz → trwa.
 */
fun AgendaEvent.phase(date: LocalDate, now: LocalDateTime): EventPhase {
    val startAt = date.atTime(start)
    val endAt = when {
        end == null || end == start -> startAt
        end > start -> date.atTime(end)
        else -> date.plusDays(1).atTime(end)
    }
    return when {
        !endAt.isAfter(now) -> EventPhase.Ended
        !startAt.isAfter(now) -> EventPhase.Ongoing
        else -> EventPhase.Upcoming
    }
}

/** Wydarzenie całodniowe trwa przez cały swój dzień: wcześniejsze dni – zakończone, dziś – trwa, później – przed nim. */
fun phaseOfAllDay(date: LocalDate, now: LocalDateTime): EventPhase {
    val today = now.toLocalDate()
    return when {
        date.isBefore(today) -> EventPhase.Ended
        date == today -> EventPhase.Ongoing
        else -> EventPhase.Upcoming
    }
}

/** Rodzaj etykiety dnia – tłumaczenie na tekst robi warstwa UI (zasoby). */
sealed interface DayLabel {
    data object Today : DayLabel
    data object Tomorrow : DayLabel
    data object Yesterday : DayLabel

    /** [weekdayIndex] 0 = poniedziałek … 6 = niedziela. */
    data class Date(val weekdayIndex: Int, val dayOfMonth: Int, val month: Int) : DayLabel
}

/** Etykieta dnia [date] przy bieżącym dniu [today]: Dziś / Jutro / Wczoraj / „pt 12.10”. */
fun dayLabel(date: LocalDate, today: LocalDate): DayLabel = when (date) {
    today -> DayLabel.Today
    today.plusDays(1) -> DayLabel.Tomorrow
    today.minusDays(1) -> DayLabel.Yesterday
    else -> DayLabel.Date(date.dayOfWeek.value - 1, date.dayOfMonth, date.monthValue)
}

/** Wiersz płaskiej listy harmonogramu (jeden element `LazyColumn`). */
sealed interface AgendaRow {
    val date: LocalDate
    val key: String

    data class Header(override val date: LocalDate) : AgendaRow {
        override val key get() = "h${date.toEpochDay()}"
    }

    data class AllDay(override val date: LocalDate, val events: List<AgendaAllDayEvent>) : AgendaRow {
        override val key get() = "a${date.toEpochDay()}"
    }

    data class NoEvents(override val date: LocalDate) : AgendaRow {
        override val key get() = "n${date.toEpochDay()}"
    }

    data class Event(override val date: LocalDate, val event: AgendaEvent) : AgendaRow {
        override val key get() = "e${date.toEpochDay()}:${event.id}"
    }
}

/**
 * Spłaszcza dni do wierszy: nagłówek, chipy całodniowe (jeśli są), wydarzenia wg godziny albo „brak wydarzeń”.
 * Dni sortowane rosnąco po dacie.
 */
fun buildAgendaRows(days: List<AgendaDay>): List<AgendaRow> = buildList {
    for (day in days.sortedBy { it.date }) {
        add(AgendaRow.Header(day.date))
        if (day.allDay.isNotEmpty()) add(AgendaRow.AllDay(day.date, day.allDay))
        if (day.allDay.isEmpty() && day.events.isEmpty()) add(AgendaRow.NoEvents(day.date))
        for (event in day.events.sortedBy { it.start }) add(AgendaRow.Event(day.date, event))
    }
}

/**
 * Indeks wiersza, od którego lista ma być pokazana (kotwica przewijania):
 *  - pierwsze **niezakończone** wydarzenie z godziną dzisiaj – wcześniejsze dzisiejsze (zakończone) zostają nad nim,
 *    dostępne przez przewinięcie w górę razem z poprzednimi dniami;
 *  - gdy dziś są wydarzenia całodniowe (liczą się jako trwające cały dzień, a stoją w dniu na początku) albo
 *    nie ma już niezakończonych wydarzeń – nagłówek dzisiejszego dnia;
 *  - gdy dzisiaj nie ma w danych – nagłówek najbliższego późniejszego dnia (a jeśli takiego nie ma – ostatni wiersz).
 */
fun anchorIndex(rows: List<AgendaRow>, now: LocalDateTime): Int {
    if (rows.isEmpty()) return 0
    val today = now.toLocalDate()
    val headerIndex = rows.indexOfFirst { it is AgendaRow.Header && it.date == today }
    if (headerIndex < 0) {
        val next = rows.indexOfFirst { it is AgendaRow.Header && it.date.isAfter(today) }
        return if (next >= 0) next else rows.lastIndex
    }
    var i = headerIndex + 1
    while (i < rows.size && rows[i].date == today) {
        when (val row = rows[i]) {
            is AgendaRow.AllDay -> return headerIndex
            is AgendaRow.Event -> if (row.event.phase(today, now) != EventPhase.Ended) return i
            else -> Unit
        }
        i++
    }
    return headerIndex
}

/** Znacznik „użytkownik jeszcze nie dotykał listy”. */
const val NEVER_TOUCHED: Long = Long.MIN_VALUE

/**
 * Ile jeszcze poczekać (ms) do automatycznego powrotu do kotwicy: [idleMillis] od ostatniego dotyku listy
 * ([lastTouchMs]); 0 = można wracać od razu (lista nie była dotykana albo bezczynność już minęła).
 */
fun remainingIdleMillis(nowMs: Long, lastTouchMs: Long, idleMillis: Long): Long =
    if (lastTouchMs == NEVER_TOUCHED) 0L else (lastTouchMs + idleMillis - nowMs).coerceAtLeast(0L)
