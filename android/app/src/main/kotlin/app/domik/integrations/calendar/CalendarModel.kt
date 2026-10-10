package app.domik.integrations.calendar

import app.domik.data.AppSettings
import app.domik.ui.components.AgendaDay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stan danych kalendarza dla kafelka i menu. Wszystko w pamięci – nic nie trafia na dysk. */
sealed interface CalendarState {
    /** Nikt jeszcze nie poprosił o dane. */
    data object Idle : CalendarState

    /** Pierwsze wczytanie (przy odświeżaniu zostają poprzednie dane – [Ready]). */
    data object Loading : CalendarState

    /** [days] = cały zakres [CalendarWindow]; [calendars] = widoczne kalendarze z urządzenia (do menu). */
    data class Ready(val days: List<AgendaDay>, val calendars: List<CalendarInfo>) : CalendarState {
        override fun toString(): String = "Ready(days=${days.size}, calendars=${calendars.size})"
    }

    /** Brak zgody na odczyt kalendarza. */
    data object NoAccess : CalendarState

    /** Nie udało się odczytać kalendarza (np. brak dostawcy kalendarza w systemie) i nie ma żadnych starszych danych. */
    data object Unavailable : CalendarState
}

/**
 * Wczytuje kalendarz z [source] **poza wątkiem UI** ([ioDispatcher]) i odświeża go, gdy:
 *  - ktoś wywoła [refresh] (nowy dzień, zmiana strefy czasu, powrót aplikacji na pierwszy plan, nowa zgoda),
 *  - kalendarz się zmieni ([CalendarSource.changes] z opóźnieniem [changeDebounceMillis] – zmiany przychodzą seriami),
 *  - użytkownik zmieni wybór kalendarzy ([setCalendarVisible]).
 *
 * Wybór kalendarzy zapisuje w [AppSettings] (tylko identyfikatory). Dane wydarzeń nie są nigdzie zapisywane.
 */
@OptIn(FlowPreview::class)
class CalendarModel(
    private val source: CalendarSource,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val changeDebounceMillis: Long = DEFAULT_CHANGE_DEBOUNCE_MILLIS,
) {
    private data class Request(val today: LocalDate, val zone: ZoneId)

    private val _state = MutableStateFlow<CalendarState>(CalendarState.Idle)
    private val _selection = MutableStateFlow(settings.calendarSelection)
    private var request: Request? = null
    private var job: Job? = null

    val state: StateFlow<CalendarState> = _state.asStateFlow()
    val selection: StateFlow<CalendarSelection> = _selection.asStateFlow()

    init {
        scope.launch {
            source.changes().debounce(changeDebounceMillis).collect { reload() }
        }
    }

    /**
     * Wczytuje kalendarz dla dnia [today] w strefie [zone]. Powtórne wywołanie z tymi samymi argumentami w trakcie
     * wczytywania jest pomijane; po jego zakończeniu wczytuje od nowa (np. powrót aplikacji na pierwszy plan).
     */
    fun refresh(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()) {
        val next = Request(today, zone)
        if (next == request && job?.isActive == true) return
        request = next
        reload()
    }

    /** Ponawia ostatnie żądanie (przycisk „Spróbuj ponownie”). */
    fun retry() = reload()

    /** Pokazuje albo ukrywa kalendarz [calendarId]; wybór jest zapamiętywany, wydarzenia wczytują się od nowa. */
    fun setCalendarVisible(calendarId: Long, visible: Boolean) {
        val updated = _selection.value.withVisible(calendarId, visible)
        if (updated == _selection.value) return
        settings.calendarSelection = updated
        _selection.value = updated
        reload()
    }

    private fun reload() {
        val current = request ?: return
        job?.cancel()
        _state.update { if (it is CalendarState.Ready) it else CalendarState.Loading }
        val selection = _selection.value
        job = scope.launch {
            val result = try {
                withContext(ioDispatcher) { load(current, selection) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: CalendarAccessDeniedException) {
                CalendarState.NoAccess
            } catch (_: SecurityException) {
                CalendarState.NoAccess
            } catch (_: Exception) {
                // Przejściowy błąd dostawcy: starsze dane lepsze niż pusty kafelek (bez logowania – to dane prywatne).
                _state.value.takeIf { it is CalendarState.Ready } ?: CalendarState.Unavailable
            }
            _state.value = result
        }
    }

    private suspend fun load(request: Request, selection: CalendarSelection): CalendarState.Ready {
        val calendars = source.calendars()
        val (from, to) = CalendarWindow.queryRange(request.today, request.zone)
        val instances = source.instances(from, to)
        return CalendarState.Ready(
            days = CalendarAgendaMapper.build(instances, request.today, request.zone, selection),
            calendars = calendars,
        )
    }

    companion object {
        const val DEFAULT_CHANGE_DEBOUNCE_MILLIS = 600L
    }
}
