package app.domik.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Wydarzenie całodniowe (chip na górze dnia). [color] = kolor kalendarza; `null` = kolor akcentu. */
@Immutable
data class AgendaAllDayEvent(val id: String, val title: String, val color: Color? = null)

/**
 * Wydarzenie z godziną w dniu [AgendaDay.date]. [end] opcjonalny (brak = wydarzenie punktowe, kończy się w chwili
 * rozpoczęcia); [color] = kolor kalendarza (kropka); `null` = kolor akcentu.
 */
@Immutable
data class AgendaEvent(
    val id: String,
    val start: LocalTime,
    val title: String,
    val end: LocalTime? = null,
    val color: Color? = null,
)

/** Jeden dzień harmonogramu (dane z dostawcy kalendarza – dostarczane z zewnątrz). */
@Immutable
data class AgendaDay(
    val date: LocalDate,
    val allDay: List<AgendaAllDayEvent> = emptyList(),
    val events: List<AgendaEvent> = emptyList(),
)

/** Czas bezczynności, po którym lista sama wraca do kotwicy (90 s). */
const val AGENDA_IDLE_RETURN_MILLIS = 90_000L

/**
 * Kafelek kalendarza „kolumna” / XL: harmonogram dzień po dniu, jak widżet agendy na Androidzie,
 * **zawsze względem bieżącej chwili [now]** (podawanej z zewnątrz – patrz `rememberNow()`):
 *
 *  - etykiety dni wynikają z [now]: „Dziś”, „Jutro”, „Wczoraj”, potem „pt 12.10” – o północy „Dziś” przechodzi
 *    na kolejny dzień samo;
 *  - wydarzenia względem [now]: zakończone – przygaszone; trwające – pogrubione ze znacznikiem „teraz”
 *    w kolorze akcentu; nadchodzące – normalne; całodniowe trwają przez cały swój dzień;
 *  - kotwica ([anchorIndex]): pierwsze niezakończone wydarzenie dzisiaj (albo nagłówek dnia); zakończone
 *    dzisiejsze zostają nad nim, razem z poprzednimi dniami, dostępne po przewinięciu w górę;
 *  - gdy upływ czasu przesunie kotwicę (wydarzenie się skończyło), lista płynnie przewija się do nowej –
 *    ale tylko jeśli użytkownik nie dotykał jej przez [idleReturnMillis]; po takiej bezczynności lista też sama
 *    wraca do kotwicy. Przycisk „Dziś” (widoczny poza kotwicą) wraca natychmiast.
 *
 * Bez dostępu do zegara i dostawcy kalendarza – lista dni i [now] to zwykłe parametry.
 *
 * @param idleReturnMillis czas bezczynności do powrotu/przesunięcia kotwicy; `null` = bez automatycznego przewijania
 * @param onEventClick dotknięcie wydarzenia lub chipa całodniowego, z jego `id` (np. otwarcie w aplikacji kalendarza);
 *   `null` = brak interakcji
 */
@Composable
fun CalendarAgendaTile(
    days: List<AgendaDay>,
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    idleReturnMillis: Long? = AGENDA_IDLE_RETURN_MILLIS,
    onEventClick: ((String) -> Unit)? = null,
) {
    val rows = remember(days) { buildAgendaRows(days) }
    val anchor = remember(rows, now) { anchorIndex(rows, now) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = anchor)
    val scope = rememberCoroutineScope()

    // Ostatni dotyk listy przez użytkownika (uptime, ms); programowe przewinięcia się nie liczą.
    var lastTouchMs by remember { mutableLongStateOf(NEVER_TOUCHED) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction) lastTouchMs = SystemClock.uptimeMillis()
        }
    }

    // Czy widoczny początek listy to kotwica (z tolerancją; na końcu listy wystarczy, że kotwica jest widoczna).
    val atAnchor by remember(anchor) {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            (first == anchor && offset < ANCHOR_TOLERANCE_PX) ||
                (!listState.canScrollForward && listState.layoutInfo.visibleItemsInfo.any { it.index == anchor })
        }
    }

    // Powrót/przesunięcie kotwicy: natychmiast, gdy lista nie była dotykana ≥ idleReturnMillis, inaczej po reszcie czasu.
    LaunchedEffect(anchor, atAnchor, lastTouchMs, idleReturnMillis) {
        if (idleReturnMillis == null || atAnchor) return@LaunchedEffect
        val wait = remainingIdleMillis(SystemClock.uptimeMillis(), lastTouchMs, idleReturnMillis)
        if (wait > 0) delay(wait)
        if (!listState.isScrollInProgress) listState.animateScrollToItem(anchor)
    }

    TileCard(
        state = TileState.Neutral,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        mergeDescendants = false,
    ) {
        // Pionowy odstęp jako padding LazyColumn (nie contentPadding): wiersze przewinięte w górę są wtedy
        // obcinane równo przy krawędzi listy, a nie prześwitują w pasie odstępu nad kotwicą.
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(vertical = 16.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                AgendaRowContent(row = row, now = now, onEventClick = onEventClick)
            }
        }
        AnimatedVisibility(
            visible = !atAnchor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
        ) {
            TodayButton(onClick = { scope.launch { listState.animateScrollToItem(anchor) } })
        }
    }
}

private const val ANCHOR_TOLERANCE_PX = 8
private const val ENDED_ALPHA = 0.45f

@Composable
private fun TodayButton(onClick: () -> Unit) {
    val colors = DomikTheme.colors
    val description = stringResource(R.string.agenda_back_to_today_description)
    Box(
        modifier = Modifier
            .clip(DomikShapes.chip)
            .background(colors.tileOnEnd)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.agenda_today),
            style = MaterialTheme.typography.labelLarge,
            color = colors.textOnTile,
        )
    }
}

@Composable
private fun AgendaRowContent(row: AgendaRow, now: LocalDateTime, onEventClick: ((String) -> Unit)?) {
    val colors = DomikTheme.colors
    when (row) {
        is AgendaRow.Header -> {
            val today = now.toLocalDate()
            Text(
                text = dayLabelText(dayLabel(row.date, today)),
                style = MaterialTheme.typography.titleSmall,
                color = if (row.date == today) colors.textPrimary else colors.textSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        is AgendaRow.AllDay -> AllDayChips(row, now, onEventClick)
        is AgendaRow.NoEvents -> Text(
            text = stringResource(R.string.agenda_no_events),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        is AgendaRow.Event -> EventLine(
            event = row.event,
            phase = row.event.phase(row.date, now),
            onClick = onEventClick?.let { callback -> { callback(row.event.id) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllDayChips(row: AgendaRow.AllDay, now: LocalDateTime, onEventClick: ((String) -> Unit)?) {
    val phase = phaseOfAllDay(row.date, now)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.alpha(if (phase == EventPhase.Ended) ENDED_ALPHA else 1f),
    ) {
        row.events.forEach { event ->
            AllDayChip(
                event = event,
                ongoing = phase == EventPhase.Ongoing,
                onClick = onEventClick?.let { callback -> { callback(event.id) } },
            )
        }
    }
}

@Composable
private fun AllDayChip(event: AgendaAllDayEvent, ongoing: Boolean, onClick: (() -> Unit)?) {
    val colors = DomikTheme.colors
    val dot = event.color ?: colors.accent
    Row(
        modifier = Modifier
            .clip(DomikShapes.chip)
            .background(if (ongoing) colors.tileOnEnd else colors.accent.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(
            text = event.title.ifBlank { stringResource(R.string.agenda_untitled) },
            style = MaterialTheme.typography.bodyMedium,
            color = if (ongoing) colors.textOnTile else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EventLine(event: AgendaEvent, phase: EventPhase, onClick: (() -> Unit)?) {
    val colors = DomikTheme.colors
    val ongoing = phase == EventPhase.Ongoing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (phase == EventPhase.Ended) ENDED_ALPHA else 1f)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClock(event.start.hour, event.start.minute),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
        )
        Box(Modifier.size(8.dp).clip(CircleShape).background(event.color ?: colors.accent))
        Spacer(Modifier.width(10.dp))
        Text(
            text = event.title.ifBlank { stringResource(R.string.agenda_untitled) },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            fontWeight = if (ongoing) FontWeight.SemiBold else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (ongoing) {
            Spacer(Modifier.width(10.dp))
            NowMarker()
        }
    }
}

/** Mały znacznik „teraz” przy trwającym wydarzeniu: kropka w kolorze akcentu + etykieta (kontrast z tokenów palety). */
@Composable
private fun NowMarker() {
    val colors = DomikTheme.colors
    Row(
        modifier = Modifier
            .clip(DomikShapes.chip)
            .background(colors.tileOnEnd)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.agenda_now),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textOnTile,
            maxLines = 1,
        )
    }
}

/** Tekst etykiety dnia z zasobów: „Dziś”, „Jutro”, „Wczoraj” albo „pt 12.10” (skrót dnia + data, wg języka). */
@Composable
private fun dayLabelText(label: DayLabel): String = when (label) {
    DayLabel.Today -> stringResource(R.string.agenda_today)
    DayLabel.Tomorrow -> stringResource(R.string.agenda_tomorrow)
    DayLabel.Yesterday -> stringResource(R.string.agenda_yesterday)
    is DayLabel.Date -> stringResource(
        R.string.agenda_day_format,
        stringArrayResource(R.array.weekdays_short)[label.weekdayIndex],
        label.dayOfMonth,
        label.month,
    )
}
