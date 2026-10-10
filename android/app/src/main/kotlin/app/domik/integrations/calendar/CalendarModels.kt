package app.domik.integrations.calendar

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/*
 * Model i źródło danych integracji z kalendarzem – bez Androida (testowalne na JVM). Dane kalendarza trzymamy
 * wyłącznie w pamięci procesu: nic z poniższych typów nie jest zapisywane na dysk ani logowane (dlatego
 * [toString] typów z tytułami ich nie ujawnia).
 */

/** Kalendarz z urządzenia (np. konto Google → „Rodzina”); [color] = ARGB albo `null`. */
data class CalendarInfo(val id: Long, val accountName: String, val displayName: String, val color: Int?)

/**
 * Surowa instancja wydarzenia z `CalendarContract.Instances` (wydarzenia cykliczne są już rozwinięte).
 *
 * @property begin początek (UTC, ms); dla całodniowych: północ UTC pierwszego dnia
 * @property end koniec (UTC, ms, wyłączny); dla całodniowych: północ UTC dnia po ostatnim dniu
 * @property color kolor wydarzenia (`DISPLAY_COLOR`, ARGB) albo `null`
 * @property status `Events.STATUS` ([CalendarStatus.CANCELED] = odwołane)
 * @property selfAttendeeStatus `Events.SELF_ATTENDEE_STATUS` ([CalendarStatus.ATTENDEE_DECLINED] = odrzucone przez użytkownika)
 * @property calendarVisible kalendarz jest widoczny w systemowej aplikacji kalendarza (`Calendars.VISIBLE = 1`)
 */
data class CalendarInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String?,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int? = null,
    val status: Int = CalendarStatus.NONE,
    val selfAttendeeStatus: Int = CalendarStatus.NONE,
    val calendarVisible: Boolean = true,
) {
    override fun toString(): String = "CalendarInstance(eventId=$eventId, calendarId=$calendarId, title=<ukryty>)"
}

/** Wartości kolumn statusu z `CalendarContract` (stałe API, powtórzone tu, by logika nie zależała od Androida). */
object CalendarStatus {
    /** Brak informacji (kolumna pusta). */
    const val NONE = 0

    /** `CalendarContract.Events.STATUS_CANCELED`. */
    const val CANCELED = 2

    /** `CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED`. */
    const val ATTENDEE_DECLINED = 2
}

/**
 * Które kalendarze z urządzenia są pokazywane. Zapisujemy kalendarze **ukryte**, więc domyślnie (pusty zbiór)
 * widać wszystkie, a kalendarz dodany później (nowe konto) pojawia się sam.
 */
data class CalendarSelection(val hiddenIds: Set<Long> = emptySet()) {
    fun isVisible(calendarId: Long): Boolean = calendarId !in hiddenIds

    fun withVisible(calendarId: Long, visible: Boolean): CalendarSelection =
        copy(hiddenIds = if (visible) hiddenIds - calendarId else hiddenIds + calendarId)

    /** Zapis do `KeyValueStore`: identyfikatory oddzielone przecinkami, rosnąco. */
    fun encode(): String = hiddenIds.sorted().joinToString(",")

    companion object {
        /** Odczyt z [encode]; śmieci (nie-liczby) są pomijane. */
        fun decode(text: String?): CalendarSelection =
            CalendarSelection(text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet())
    }
}

/**
 * Odnośnik do instancji wydarzenia, zakodowany w tekstowym identyfikatorze wiersza harmonogramu
 * (`AgendaEvent.id`): dzięki temu stuknięcie może otworzyć wydarzenie w aplikacji kalendarza, a identyfikator
 * jest unikalny także dla powtórzeń tego samego wydarzenia cyklicznego.
 */
data class CalendarEventRef(val eventId: Long, val beginMillis: Long, val endMillis: Long) {
    fun encode(): String = "$eventId:$beginMillis:$endMillis"

    companion object {
        fun decode(text: String): CalendarEventRef? {
            val parts = text.split(':')
            if (parts.size != 3) return null
            val numbers = parts.map { it.toLongOrNull() ?: return null }
            return CalendarEventRef(numbers[0], numbers[1], numbers[2])
        }
    }
}

/** Zakres dni, które pobieramy i pokazujemy (względem dzisiejszej daty). */
object CalendarWindow {
    const val PAST_DAYS = 14L
    const val FUTURE_DAYS = 60L

    fun firstDay(today: LocalDate): LocalDate = today.minusDays(PAST_DAYS)

    fun lastDay(today: LocalDate): LocalDate = today.plusDays(FUTURE_DAYS)

    /**
     * Zakres zapytania do `Instances` w ms UTC: od północy pierwszego dnia do północy po ostatnim dniu (czas lokalny),
     * poszerzony z każdej strony o dobę – wydarzenia całodniowe są w UTC, więc przy dużym przesunięciu strefy
     * mogłyby wypaść. Nadmiar odcina mapowanie ([CalendarAgendaMapper]).
     */
    fun queryRange(today: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val from = firstDay(today).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = lastDay(today).plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return from to to
    }
}

/** Brak zgody na odczyt kalendarza (`READ_CALENDAR`) – źródło zgłasza to tym wyjątkiem. */
class CalendarAccessDeniedException : Exception("Brak dostępu do kalendarza")

/** Źródło danych kalendarza. Wywołania mogą blokować – model woła je poza wątkiem UI. */
interface CalendarSource {
    /** Kalendarze widoczne w systemie (`VISIBLE = 1`), wg konta i nazwy. @throws CalendarAccessDeniedException */
    suspend fun calendars(): List<CalendarInfo>

    /** Instancje wydarzeń nachodzące na przedział [fromMillis, toMillis) (ms UTC). @throws CalendarAccessDeniedException */
    suspend fun instances(fromMillis: Long, toMillis: Long): List<CalendarInstance>

    /** Sygnał „coś się zmieniło w kalendarzu” (bez debounce – robi to model). */
    fun changes(): Flow<Unit>
}

/** Brak kalendarza (testy, środowiska bez dostawcy): zawsze „brak dostępu”. */
object NoCalendarSource : CalendarSource {
    override suspend fun calendars(): List<CalendarInfo> = throw CalendarAccessDeniedException()

    override suspend fun instances(fromMillis: Long, toMillis: Long): List<CalendarInstance> =
        throw CalendarAccessDeniedException()

    override fun changes(): Flow<Unit> = emptyFlow()
}

/**
 * Czy po odmowie w oknie zgody system nie pokaże go już ponownie (trwała odmowa): odmowa i `shouldShowRationale`
 * == false. Wtedy jedyna droga to ustawienia aplikacji. Wołać dopiero po faktycznym poproszeniu o zgodę – przed
 * pierwszą prośbą `shouldShowRationale` też jest false.
 */
fun isCalendarAccessBlocked(granted: Boolean, shouldShowRationale: Boolean): Boolean = !granted && !shouldShowRationale
