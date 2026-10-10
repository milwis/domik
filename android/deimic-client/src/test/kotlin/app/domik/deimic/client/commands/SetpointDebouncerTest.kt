@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.deimic.client.commands

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.now
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.HeatingProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

class SetpointDebouncerTest {
    private val zone = ZoneKey(1, 5)
    private val otherZone = ZoneKey(1, 4)

    /** Stands in for [DeviceCommands.setSetpoint]: records calls, answers with [outcome] (optionally after a gate). */
    private class FakeSend(private val scope: TestScope) {
        class Call(val zone: ZoneKey, val celsius: Float, val at: Long)

        val calls = mutableListOf<Call>()
        var outcome: (Call) -> CommandOutcome = { CommandOutcome.Confirmed }

        /** When set, calls suspend until the gate is completed. */
        var gate: CompletableDeferred<Unit>? = null
        var inFlight = 0
        var maxInFlight = 0
        private val inFlightByZone = mutableMapOf<ZoneKey, Int>()
        var maxInFlightPerZone = 0

        suspend fun send(zone: ZoneKey, celsius: Float): CommandOutcome {
            val call = Call(zone, celsius, scope.now)
            calls += call
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            val perZone = (inFlightByZone[zone] ?: 0) + 1
            inFlightByZone[zone] = perZone
            maxInFlightPerZone = maxOf(maxInFlightPerZone, perZone)
            try {
                gate?.await()
                return outcome(call)
            } finally {
                inFlight--
                inFlightByZone[zone] = perZone - 1
            }
        }

        fun values(): List<Float> = calls.map { it.celsius }
    }

    private fun TestScope.debouncer(send: FakeSend) =
        SetpointDebouncer(backgroundScope) { zone, celsius -> send.send(zone, celsius) }

    private fun heaterState(setpoint: Float) =
        HeaterState(1u, 5u, false, 20.0f, HeatingProfile.Comfort, setpoint)

    @Test
    fun rapidTapsAreCoalescedIntoOneRequestWithTheLastValue() = runTest {
        val send = FakeSend(this)
        val debouncer = debouncer(send)
        assertEquals(21.5f, debouncer.adjust(zone, +1, 21.0f))
        advanceTimeBy(100)
        assertEquals(22.0f, debouncer.adjust(zone, +1, 21.0f))
        advanceTimeBy(100)
        assertEquals(22.5f, debouncer.adjust(zone, +1, 21.0f))
        advanceTimeBy(100)
        assertEquals(22.0f, debouncer.adjust(zone, -1, 21.0f))

        advanceTimeBy(599) // t = 899: 599 ms after the last tap at t = 300
        runCurrent()
        assertTrue(send.calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(22.0f), send.values())
        assertEquals(900L, send.calls.single().at) // 600 ms after the last tap at t = 300
        assertEquals(zone, send.calls.single().zone)
        advanceTimeBy(10_000)
        assertEquals(1, send.calls.size)
    }

    @Test
    fun everyTapRestartsTheQuietPeriod() = runTest {
        val send = FakeSend(this)
        val debouncer = debouncer(send)
        repeat(5) {
            debouncer.adjust(zone, +1, 21.0f)
            advanceTimeBy(500) // always shorter than 600 ms
        }
        runCurrent()
        assertTrue(send.calls.isEmpty())
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf(23.5f), send.values())
        assertEquals(2_600L, send.calls.single().at)
    }

    @Test
    fun pendingTargetIsVisibleImmediatelyAndClearedAfterConfirmation() = runTest {
        val send = FakeSend(this)
        val debouncer = debouncer(send)
        assertTrue(debouncer.pending.value.isEmpty())

        debouncer.adjust(zone, +1, 21.0f)
        assertEquals(PendingSetpoint(target = 21.5f, confirmed = 21.0f, isSending = false), debouncer.pending.value[zone])
        debouncer.adjust(zone, +1, 21.0f)
        assertEquals(22.0f, debouncer.pending.value.getValue(zone).target)

        advanceTimeBy(601)
        runCurrent()
        assertTrue(debouncer.pending.value.isEmpty()) // confirmed: the UI shows the controller's value again
        assertEquals(listOf(22.0f), send.values())
    }

    @Test
    fun targetIsKeptWhileTheRequestIsInFlight() = runTest {
        val send = FakeSend(this)
        send.gate = CompletableDeferred()
        val debouncer = debouncer(send)
        debouncer.adjust(zone, +2, 21.0f)
        advanceTimeBy(601)
        runCurrent()
        assertEquals(PendingSetpoint(22.0f, 21.0f, isSending = true), debouncer.pending.value[zone])

        send.gate!!.complete(Unit)
        runCurrent()
        assertTrue(debouncer.pending.value.isEmpty())
    }

    @Test
    fun onlyOneRequestPerZoneIsInFlightAndNewerTapsAreSentAfterwards() = runTest {
        val send = FakeSend(this)
        val gate = CompletableDeferred<Unit>()
        send.gate = gate
        val debouncer = debouncer(send)

        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(601)
        runCurrent()
        assertEquals(listOf(21.5f), send.values()) // in flight, gate closed

        // More taps while the first request is still in flight; their quiet period elapses too.
        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(100)
        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(21.5f), send.values()) // still only the first request
        assertEquals(PendingSetpoint(target = 22.5f, confirmed = 21.0f, isSending = true), debouncer.pending.value[zone])

        gate.complete(Unit) // first request done -> the newest target goes out at once
        runCurrent()
        assertEquals(listOf(21.5f, 22.5f), send.values())
        assertEquals(1, send.maxInFlightPerZone)
        assertTrue(debouncer.pending.value.isEmpty())
    }

    @Test
    fun tapsDuringARequestWaitForTheirOwnQuietPeriod() = runTest {
        val send = FakeSend(this)
        val gate = CompletableDeferred<Unit>()
        send.gate = gate
        val debouncer = debouncer(send)

        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(601)
        runCurrent() // request for 21.5 in flight
        debouncer.adjust(zone, +1, 21.0f) // quiet period of this tap ends 600 ms later
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(21.5f), send.values()) // the request finished, but the user may still be tapping

        send.gate = null
        advanceTimeBy(601)
        runCurrent()
        assertEquals(listOf(21.5f, 22.0f), send.values())
        assertTrue(debouncer.pending.value.isEmpty())
    }

    @Test
    fun failureRevertsToTheLastConfirmedValueAndDropsNewerTaps() = runTest {
        val send = FakeSend(this)
        val gate = CompletableDeferred<Unit>()
        send.gate = gate
        send.outcome = { CommandOutcome.NoAck }
        val debouncer = debouncer(send)
        val results = mutableListOf<SetpointResult>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { debouncer.results.collect { results += it } }

        debouncer.adjust(zone, +2, 21.0f)
        advanceTimeBy(601)
        runCurrent()
        debouncer.adjust(zone, +1, 21.0f) // tap while the doomed request is in flight
        gate.complete(Unit)
        runCurrent()

        assertTrue(debouncer.pending.value.isEmpty()) // UI shows the reported value again (21.0)
        assertEquals(listOf(SetpointResult(zone, 22.0f, CommandOutcome.NoAck, revertedTo = 21.0f)), results)
        advanceTimeBy(10_000)
        assertEquals(listOf(22.0f), send.values()) // the dropped tap was never sent
    }

    @Test
    fun everyKindOfFailureReverts() = runTest {
        val failures = listOf(
            CommandOutcome.NoAck,
            CommandOutcome.Failed(ConnectionProblem.CONNECTION_LOST, "lost"),
            CommandOutcome.OutputMismatch(true),
            CommandOutcome.NotEnabled,
            CommandOutcome.Invalid("x"),
        )
        for (failure in failures) {
            val send = FakeSend(this)
            send.outcome = { failure }
            val debouncer = debouncer(send)
            val results = mutableListOf<SetpointResult>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { debouncer.results.collect { results += it } }
            debouncer.adjust(zone, +1, 21.0f)
            advanceTimeBy(601)
            runCurrent()
            assertTrue(debouncer.pending.value.isEmpty(), "$failure")
            assertEquals(21.0f, results.single().revertedTo, "$failure")
            assertEquals(failure, results.single().outcome)
        }
    }

    @Test
    fun thrownExceptionIsAFailure() = runTest {
        val debouncer = SetpointDebouncer(backgroundScope) { _, _ -> throw IllegalStateException("boom") }
        val results = mutableListOf<SetpointResult>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { debouncer.results.collect { results += it } }
        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(601)
        runCurrent()
        assertTrue(debouncer.pending.value.isEmpty())
        val outcome = results.single().outcome
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.OTHER, "$outcome")
        assertEquals(21.0f, results.single().revertedTo)
    }

    @Test
    fun mismatchRevertsToTheSetpointTheControllerReported() = runTest {
        val send = FakeSend(this)
        send.outcome = { CommandOutcome.HeaterMismatch(heaterState(19.5f)) }
        val debouncer = debouncer(send)
        val results = mutableListOf<SetpointResult>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { debouncer.results.collect { results += it } }
        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(601)
        runCurrent()
        assertTrue(debouncer.pending.value.isEmpty())
        assertEquals(19.5f, results.single().revertedTo)
    }

    @Test
    fun switchingAnOffZoneOnSettlesAtOnceAndDropsTapsMadeMeanwhile() = runTest {
        val send = FakeSend(this)
        val gate = CompletableDeferred<Unit>()
        send.gate = gate
        val switched = CommandOutcome.SwitchedToComfort(heaterState(22.5f))
        send.outcome = { switched }
        val debouncer = debouncer(send)
        val results = mutableListOf<SetpointResult>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { debouncer.results.collect { results += it } }

        debouncer.adjust(zone, +1, 18.0f) // off zone: the UI shows 18.5 for now
        advanceTimeBy(601)
        runCurrent() // 18.5 in flight
        debouncer.adjust(zone, +1, 18.0f) // tap while the zone is being switched on
        gate.complete(Unit)
        runCurrent()

        assertTrue(debouncer.pending.value.isEmpty()) // the UI shows the comfort setpoint the controller reports
        assertEquals(listOf(SetpointResult(zone, 18.5f, switched, revertedTo = null)), results)
        advanceTimeBy(10_000)
        assertEquals(listOf(18.5f), send.values()) // the tap made meanwhile was dropped

        // From now on taps start at the reported (comfort) setpoint and are sent as usual.
        assertEquals(23.0f, debouncer.adjust(zone, +1, 22.5f))
        send.gate = null
        advanceTimeBy(601)
        runCurrent()
        assertEquals(listOf(18.5f, 23.0f), send.values())
    }

    @Test
    fun acknowledgedWithoutVerificationCountsAsAccepted() = runTest {
        val send = FakeSend(this)
        send.outcome = { CommandOutcome.AckOnly }
        val debouncer = debouncer(send)
        val results = mutableListOf<SetpointResult>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { debouncer.results.collect { results += it } }
        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(601)
        runCurrent()
        assertTrue(debouncer.pending.value.isEmpty())
        assertNull(results.single().revertedTo)
        assertEquals(CommandOutcome.AckOnly, results.single().outcome)
    }

    @Test
    fun failureOfTheSecondRequestRevertsToTheValueConfirmedByTheFirst() = runTest {
        val send = FakeSend(this)
        val gate = CompletableDeferred<Unit>()
        send.gate = gate
        send.outcome = { if (it.celsius == 21.5f) CommandOutcome.Confirmed else CommandOutcome.NoAck }
        val debouncer = debouncer(send)
        val results = mutableListOf<SetpointResult>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { debouncer.results.collect { results += it } }

        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(601)
        runCurrent() // 21.5 in flight
        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(601)
        runCurrent() // 22.0 waits behind it
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(21.5f, 22.0f), send.values())
        assertTrue(debouncer.pending.value.isEmpty())
        assertEquals(listOf(null, 21.5f), results.map { it.revertedTo })
    }

    @Test
    fun tappingBackToTheConfirmedValueSendsNothing() = runTest {
        val send = FakeSend(this)
        val debouncer = debouncer(send)
        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(200)
        assertEquals(21.0f, debouncer.adjust(zone, -1, 21.0f))
        advanceTimeBy(601)
        runCurrent()
        assertTrue(send.calls.isEmpty())
        assertTrue(debouncer.pending.value.isEmpty())
    }

    @Test
    fun targetIsClampedToTheAllowedRange() = runTest {
        val send = FakeSend(this)
        val debouncer = debouncer(send)
        assertEquals(30.0f, debouncer.adjust(zone, +10, 29.5f))
        assertEquals(30.0f, debouncer.adjust(zone, +1, 29.5f))
        assertEquals(5.0f, debouncer.adjust(otherZone, -100, 6.0f))
        assertEquals(30.0f, debouncer.setTarget(zone, 99f, 21.0f))
        assertEquals(21.5f, debouncer.setTarget(zone, 21.3f, 21.0f))
        assertThrows<IllegalArgumentException> { debouncer.setTarget(zone, Float.NaN, 21.0f) }
        assertThrows<IllegalArgumentException> { debouncer.adjust(zone, 1, Float.NaN) }
        advanceTimeBy(601)
        runCurrent()
        assertEquals(setOf(21.5f, 5.0f), send.values().toSet())
    }

    @Test
    fun reportedSetpointOnlyMattersWhenNothingIsPending() = runTest {
        val send = FakeSend(this)
        val debouncer = debouncer(send)
        debouncer.adjust(zone, +1, 21.0f)
        // The controller's reported value changes (or is stale) while the user keeps tapping: ignored.
        assertEquals(22.0f, debouncer.adjust(zone, +1, 25.0f))
        advanceTimeBy(601)
        runCurrent()
        assertEquals(listOf(22.0f), send.values())

        // Nothing pending any more: the new reported value is the starting point.
        assertEquals(25.5f, debouncer.adjust(zone, +1, 25.0f))
    }

    @Test
    fun zonesAreIndependent() = runTest {
        val send = FakeSend(this)
        val gate = CompletableDeferred<Unit>()
        send.gate = gate
        val debouncer = debouncer(send)

        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(300)
        debouncer.adjust(otherZone, -1, 19.0f)
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf(21.5f), send.values()) // first zone sent, the other one still waiting
        assertEquals(setOf(zone, otherZone), debouncer.pending.value.keys)

        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(21.5f, 18.5f), send.values())
        assertEquals(2, send.maxInFlight) // different zones may be in flight together
        assertEquals(1, send.maxInFlightPerZone)
        assertEquals(setOf(zone to 21.5f, otherZone to 18.5f), send.calls.map { it.zone to it.celsius }.toSet())

        gate.complete(Unit)
        runCurrent()
        assertTrue(debouncer.pending.value.isEmpty())
    }

    @Test
    fun failureOfOneZoneDoesNotAffectAnother() = runTest {
        val send = FakeSend(this)
        send.outcome = { if (it.zone == zone) CommandOutcome.NoAck else CommandOutcome.Confirmed }
        val debouncer = debouncer(send)
        debouncer.adjust(zone, +1, 21.0f)
        debouncer.adjust(otherZone, +1, 19.0f)
        advanceTimeBy(601)
        runCurrent()
        assertEquals(2, send.calls.size)
        assertTrue(debouncer.pending.value.isEmpty())
    }

    @Test
    fun sendsThroughDeviceCommands() = runTest {
        val rig = commandsRig()
        val debouncer = SetpointDebouncer(backgroundScope, rig.commands)
        val results = mutableListOf<SetpointResult>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { debouncer.results.collect { results += it } }

        repeat(3) { debouncer.adjust(zone, +1, 21.0f) }
        advanceTimeBy(601)
        runCurrent()
        assertEquals(listOf(CommandCode.SET_SETPOINT), rig.sentCommands())
        assertEquals(22.5f, rig.device.zone(1, 5).setpoint)
        assertEquals(listOf(SetpointResult(zone, 22.5f, CommandOutcome.Confirmed, revertedTo = null)), results)
        assertTrue(debouncer.pending.value.isEmpty())

        // A controller that acknowledges but does not apply the setpoint: the zone reverts to what it reports.
        rig.device.applyWrites = false
        debouncer.adjust(zone, -4, 22.5f)
        advanceTimeBy(601)
        runCurrent()
        val mismatch = results.last().outcome
        assertTrue(mismatch is CommandOutcome.HeaterMismatch, "$mismatch")
        assertEquals(22.5f, results.last().revertedTo)
        assertTrue(debouncer.pending.value.isEmpty())
    }

    @Test
    fun quietPeriodCanBeChosen() = runTest {
        val send = FakeSend(this)
        val debouncer = SetpointDebouncer(backgroundScope, quietPeriod = 250.milliseconds) { zone, celsius ->
            send.send(zone, celsius)
        }
        assertEquals(250.milliseconds, debouncer.quietPeriod)
        assertEquals(600.milliseconds, SetpointDebouncer.DEFAULT_QUIET_PERIOD)
        debouncer.adjust(zone, +1, 21.0f)
        advanceTimeBy(251)
        runCurrent()
        assertEquals(listOf(21.5f), send.values())
        assertFalse(debouncer.pending.value.containsKey(zone))
        assertThrows<IllegalArgumentException> { SetpointDebouncer(backgroundScope, quietPeriod = 0.milliseconds) { _, _ -> CommandOutcome.Confirmed } }
    }
}
