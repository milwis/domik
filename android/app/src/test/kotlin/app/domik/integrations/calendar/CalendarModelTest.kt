@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.integrations.calendar

import app.domik.data.AppSettings
import app.domik.data.FakeCipher
import app.domik.data.InMemoryKeyValueStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeSource : CalendarSource {
    var calendars = listOf(CalendarInfo(1, "jan@example.test", "Jan", 0xFF336699.toInt()), CalendarInfo(2, "jan@example.test", "Rodzina", null))
    var instances = emptyList<CalendarInstance>()
    var denied = false
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var instanceCalls = 0
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    override suspend fun calendars(): List<CalendarInfo> {
        if (denied) throw CalendarAccessDeniedException()
        return calendars
    }

    override suspend fun instances(fromMillis: Long, toMillis: Long): List<CalendarInstance> {
        instanceCalls++
        gate?.await()
        if (denied) throw CalendarAccessDeniedException()
        failure?.let { throw it }
        return instances
    }

    override fun changes() = changes
}

class CalendarModelTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val today = LocalDate.of(2026, 10, 10)

    private fun event(id: Long, calendarId: Long, hour: Int, title: String = "Wydarzenie $id"): CalendarInstance {
        val start = LocalDateTime.of(2026, 10, 12, hour, 0).atZone(zone).toInstant().toEpochMilli()
        return CalendarInstance(id, calendarId, title, start, start + 3_600_000L, allDay = false)
    }

    private fun TestScope.model(source: FakeSource, store: InMemoryKeyValueStore = InMemoryKeyValueStore()) =
        CalendarModel(
            source = source,
            settings = AppSettings(store, FakeCipher()),
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            changeDebounceMillis = 500,
        )

    private fun CalendarModel.titlesOn(day: LocalDate): List<String> =
        (state.value as CalendarState.Ready).days.single { it.date == day }.events.map { it.title }

    @Test
    fun `bez zadania model nic nie czyta`() = runTest {
        val source = FakeSource()
        val model = model(source)
        runCurrent()
        assertSame(CalendarState.Idle, model.state.value)
        assertEquals(0, source.instanceCalls)
    }

    @Test
    fun `odswiezenie wczytuje caly zakres i liste kalendarzy poza watkiem wywolujacym`() = runTest {
        val source = FakeSource().apply { instances = listOf(event(1, 1, 9)) }
        val model = model(source)
        model.refresh(today, zone)
        assertSame(CalendarState.Loading, model.state.value)
        runCurrent()
        val ready = assertInstanceOf(CalendarState.Ready::class.java, model.state.value)
        assertEquals(75, ready.days.size)
        assertEquals(listOf("Jan", "Rodzina"), ready.calendars.map { it.displayName })
        assertEquals(listOf("Wydarzenie 1"), model.titlesOn(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun `brak zgody daje NoAccess a po zgodzie kolejne odswiezenie dziala`() = runTest {
        val source = FakeSource().apply { denied = true }
        val model = model(source)
        model.refresh(today, zone)
        runCurrent()
        assertSame(CalendarState.NoAccess, model.state.value)
        source.denied = false
        model.refresh(today, zone)
        runCurrent()
        assertInstanceOf(CalendarState.Ready::class.java, model.state.value)
    }

    @Test
    fun `SecurityException z dostawcy to tez brak zgody`() = runTest {
        val source = FakeSource().apply { failure = SecurityException("brak READ_CALENDAR") }
        val model = model(source)
        model.refresh(today, zone)
        runCurrent()
        assertSame(CalendarState.NoAccess, model.state.value)
    }

    @Test
    fun `blad bez danych to Unavailable a z danymi zostaje stary stan`() = runTest {
        val source = FakeSource().apply { failure = IllegalStateException("dostawca nie odpowiada") }
        val model = model(source)
        model.refresh(today, zone)
        runCurrent()
        assertSame(CalendarState.Unavailable, model.state.value)

        source.failure = null
        source.instances = listOf(event(1, 1, 9))
        model.retry()
        runCurrent()
        val ready = model.state.value
        assertInstanceOf(CalendarState.Ready::class.java, ready)

        source.failure = IllegalStateException("chwilowy błąd")
        model.retry()
        runCurrent()
        assertSame(ready, model.state.value)
    }

    @Test
    fun `wybor kalendarza przeladowuje wydarzenia i jest zapisywany`() = runTest {
        val store = InMemoryKeyValueStore()
        val source = FakeSource().apply { instances = listOf(event(1, 1, 9), event(2, 2, 10)) }
        val model = model(source, store)
        model.refresh(today, zone)
        runCurrent()
        val day = LocalDate.of(2026, 10, 12)
        assertEquals(listOf("Wydarzenie 1", "Wydarzenie 2"), model.titlesOn(day))

        model.setCalendarVisible(2, false)
        runCurrent()
        assertEquals(listOf("Wydarzenie 1"), model.titlesOn(day))
        assertEquals(CalendarSelection(setOf(2L)), model.selection.value)
        // zapis przetrwa nowa instancje ustawien i nowy model
        assertEquals(CalendarSelection(setOf(2L)), AppSettings(store, FakeCipher()).calendarSelection)
        val reborn = model(source, store)
        assertEquals(CalendarSelection(setOf(2L)), reborn.selection.value)

        model.setCalendarVisible(2, true)
        runCurrent()
        assertEquals(listOf("Wydarzenie 1", "Wydarzenie 2"), model.titlesOn(day))
        assertEquals(CalendarSelection(), AppSettings(store, FakeCipher()).calendarSelection)
    }

    @Test
    fun `zmiany w kalendarzu odswiezaja z opoznieniem i seria to jedno wczytanie`() = runTest {
        val source = FakeSource()
        val model = model(source)
        model.refresh(today, zone)
        runCurrent()
        assertEquals(1, source.instanceCalls)

        source.instances = listOf(event(1, 1, 9))
        source.changes.emit(Unit)
        advanceTimeBy(200)
        source.changes.emit(Unit)
        advanceTimeBy(200)
        source.changes.emit(Unit)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, source.instanceCalls, "przed upływem opóźnienia nic się nie dzieje")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, source.instanceCalls)
        assertEquals(listOf("Wydarzenie 1"), model.titlesOn(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun `zmiana kalendarza przed pierwszym zadaniem niczego nie wczytuje`() = runTest {
        val source = FakeSource()
        val model = model(source)
        runCurrent()
        source.changes.emit(Unit)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, source.instanceCalls)
        assertSame(CalendarState.Idle, model.state.value)
    }

    @Test
    fun `to samo zadanie w trakcie wczytywania jest pomijane a po nim wczytuje od nowa`() = runTest {
        val source = FakeSource().apply { gate = CompletableDeferred() }
        val model = model(source)
        model.refresh(today, zone)
        runCurrent()
        model.refresh(today, zone)
        model.refresh(today, zone)
        runCurrent()
        assertEquals(1, source.instanceCalls)

        source.gate!!.complete(Unit)
        runCurrent()
        assertInstanceOf(CalendarState.Ready::class.java, model.state.value)

        model.refresh(today, zone) // np. powrot aplikacji na pierwszy plan
        runCurrent()
        assertEquals(2, source.instanceCalls)
    }

    @Test
    fun `nowy dzien wczytuje zakres wokol nowej daty`() = runTest {
        val source = FakeSource()
        val model = model(source)
        model.refresh(today, zone)
        runCurrent()
        assertEquals(today.minusDays(14), (model.state.value as CalendarState.Ready).days.first().date)
        model.refresh(today.plusDays(1), zone)
        runCurrent()
        assertEquals(today.minusDays(13), (model.state.value as CalendarState.Ready).days.first().date)
        assertEquals(2, source.instanceCalls)
    }

    @Test
    fun `stan nie ujawnia danych w toString`() {
        val ready = CalendarState.Ready(emptyList(), listOf(CalendarInfo(1, "a", "b", null)))
        assertTrue(ready.toString().contains("calendars=1"))
        val instance = CalendarInstance(1, 1, "Tajny tytuł", 0, 1, false)
        assertTrue(!instance.toString().contains("Tajny"))
    }
}
