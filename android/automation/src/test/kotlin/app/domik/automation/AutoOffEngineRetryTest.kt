package app.domik.automation

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.commands.CommandOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Failed switch-offs, retries, the attempt limit, odd switchers and wall-clock changes. */
class AutoOffEngineRetryTest {
    private fun times(rig: Rig): List<Duration> = rig.switcher.calls.map { it.atMillis.milliseconds }

    private fun Rig.lightOn(output: OutputAddress = LIGHT_A) {
        events.switchOn(output)
        settle()
    }

    @Test
    fun `a missing acknowledgement is retried with growing pauses until it works`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.switcher.script += listOf(CommandOutcome.NoAck, CommandOutcome.NoAck)
        rig.lightOn()

        rig.advance(30.minutes)
        assertEquals(AutoOffTimer.Phase.RETRYING, rig.timers.getValue(LIGHT_A).phase)
        assertEquals(CommandOutcome.NoAck, rig.timers.getValue(LIGHT_A).lastOutcome)
        rig.advance(1.hours)

        // 30:00 fails, +5 s fails, +10 s works
        assertEquals(listOf(30.minutes, 30.minutes + 5.seconds, 30.minutes + 15.seconds), times(rig))
        assertTrue(rig.timers.isEmpty())
        assertTrue(rig.store.deadlines.isEmpty())
    }

    @Test
    fun `it gives up after the attempt limit and tries again with the next state read`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        repeat(5) { rig.switcher.script += CommandOutcome.NoAck }
        rig.lightOn()

        rig.advance(35.minutes)

        // attempts at 30:00, +5 s, +10 s, +20 s, +40 s - then nothing more for now
        assertEquals(
            listOf(30.minutes, 30.minutes + 5.seconds, 30.minutes + 15.seconds, 30.minutes + 35.seconds, 30.minutes + 75.seconds),
            times(rig),
        )
        val timer = rig.timers.getValue(LIGHT_A)
        assertEquals(AutoOffTimer.Phase.GAVE_UP, timer.phase)
        assertEquals(5, timer.attempts)

        // the periodic read at t = 40 min finds the light still on and starts over (attempt counter reset)
        rig.advance(5.minutes)
        assertEquals(6, rig.switcher.calls.size)
        assertEquals(40.minutes, rig.switcher.calls.last().atMillis.milliseconds)
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `a custom attempt limit is respected`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 1.minutes)), config = AutoOffConfig(maxAttempts = 2, resyncInterval = Duration.INFINITE))
        rig.start()
        repeat(10) { rig.switcher.script += CommandOutcome.NoAck }
        rig.lightOn()

        rig.advance(1.hours)

        assertEquals(2, rig.switcher.calls.size)
        assertEquals(AutoOffTimer.Phase.GAVE_UP, rig.timers.getValue(LIGHT_A).phase)
    }

    @Test
    fun `a state that is still on after an acknowledged command counts as a failure`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.switcher.script += CommandOutcome.OutputMismatch(actualOn = true)
        rig.lightOn()

        rig.advance(31.minutes)

        assertEquals(2, rig.switcher.calls.size)
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `an acknowledged command without proof counts as done`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.switcher.script += CommandOutcome.AckOnly
        rig.switcher.applyScripted = true
        rig.lightOn()

        rig.advance(2.hours)

        assertEquals(1, rig.switcher.calls.size)
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `a rejected command is not retried`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)), config = AutoOffConfig(resyncInterval = Duration.INFINITE))
        rig.start()
        rig.switcher.script += CommandOutcome.Invalid("relay out of range")
        rig.lightOn()

        rig.advance(2.hours)

        assertEquals(1, rig.switcher.calls.size)
        val timer = rig.timers.getValue(LIGHT_A)
        assertEquals(AutoOffTimer.Phase.GAVE_UP, timer.phase)
        assertEquals(CommandOutcome.Invalid("relay out of range"), timer.lastOutcome)
    }

    @Test
    fun `a failure caused by a lost connection does not use up an attempt`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.lightOn()
        val result = CompletableDeferred<CommandOutcome>()
        rig.switcher.gate = result

        rig.advance(30.minutes)
        assertEquals(AutoOffTimer.Phase.SWITCHING, rig.timers.getValue(LIGHT_A).phase)
        rig.events.setConnected(false) // the link breaks while the command is on its way
        rig.settle()
        result.complete(CommandOutcome.Failed(ConnectionProblem.CONNECTION_LOST))
        rig.settle()

        assertEquals(1, rig.switcher.calls.size)
        val waiting = rig.timers.getValue(LIGHT_A)
        assertEquals(AutoOffTimer.Phase.WAITING_FOR_CONTROLLER, waiting.phase)
        assertEquals(0, waiting.attempts)

        rig.switcher.gate = null
        rig.advance(10.minutes)
        assertEquals(1, rig.switcher.calls.size) // still offline: nothing is sent

        rig.events.setConnected(true) // t = 40 min; the light is still on
        rig.settle()

        assertEquals(listOf(30.minutes, 40.minutes), times(rig))
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `a failure that is reported before the lost connection ends up waiting for the reconnect too`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.lightOn()
        rig.switcher.script += CommandOutcome.Failed(ConnectionProblem.CONNECTION_LOST)
        rig.switcher.onCall = { rig.events.setConnected(false) }

        rig.advance(30.minutes + 6.seconds) // the pause is over, but there is no link
        assertEquals(1, rig.switcher.calls.size)
        assertEquals(AutoOffTimer.Phase.WAITING_FOR_CONTROLLER, rig.timers.getValue(LIGHT_A).phase)

        rig.switcher.onCall = {}
        rig.advance(10.minutes)
        rig.events.setConnected(true)
        rig.settle()

        assertEquals(2, rig.switcher.calls.size)
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `switching off by hand during the pause cancels the retry`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.switcher.script += CommandOutcome.NoAck
        rig.lightOn()
        rig.advance(30.minutes + 2.seconds)
        assertEquals(AutoOffTimer.Phase.RETRYING, rig.timers.getValue(LIGHT_A).phase)

        rig.events.switchOff(LIGHT_A)
        rig.advance(1.hours)

        assertEquals(1, rig.switcher.calls.size)
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `switching on during the pause starts a fresh countdown`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.switcher.script += CommandOutcome.NoAck
        rig.lightOn()
        rig.advance(30.minutes + 2.seconds)

        rig.events.switchOn(LIGHT_A) // someone presses the switch again
        rig.advance(29.minutes + 59.seconds)
        assertEquals(1, rig.switcher.calls.size)

        rig.advance(1.seconds + 1.milliseconds)
        assertEquals(2, rig.switcher.calls.size)
    }

    @Test
    fun `an exception from the switcher is a failure like any other`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.switcher.throwOnCall = IllegalStateException("boom")
        rig.lightOn()

        rig.advance(30.minutes + 6.seconds)
        assertEquals(2, rig.switcher.calls.size)
        assertInstanceOf(CommandOutcome.Failed::class.java, rig.timers.getValue(LIGHT_A).lastOutcome)

        rig.switcher.throwOnCall = null
        rig.advance(1.minutes)
        assertEquals(3, rig.switcher.calls.size)
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `a command that never returns times out and is retried`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.switcher.hang = true
        rig.lightOn()

        rig.advance(30.minutes + 1.seconds)
        assertEquals(AutoOffTimer.Phase.SWITCHING, rig.timers.getValue(LIGHT_A).phase)
        assertEquals(1, rig.timers.getValue(LIGHT_A).attempts)

        rig.advance(30.seconds) // switchTimeout, then the pause of 5 s
        assertEquals(AutoOffTimer.Phase.RETRYING, rig.timers.getValue(LIGHT_A).phase)
        assertEquals(CommandOutcome.Failed(ConnectionProblem.TIMEOUT, "no result within 30s"), rig.timers.getValue(LIGHT_A).lastOutcome)

        rig.switcher.hang = false
        rig.advance(5.seconds)
        assertEquals(listOf(30.minutes, 30.minutes + 35.seconds), times(rig))
        assertTrue(rig.timers.isEmpty())
    }

    // Wall clock

    @Test
    fun `a wall clock set forward is noticed within the check interval`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.lightOn()
        rig.advance(10.minutes)

        rig.clock.jump = 2.hours // e.g. the tablet woke up from sleep or synchronised its clock
        rig.advance(30.seconds)

        assertEquals(1, rig.switcher.calls.size)
        val calledAt = rig.switcher.calls.single().atMillis.milliseconds
        assertTrue(calledAt > 10.minutes && calledAt <= 10.minutes + 30.seconds, "called at $calledAt")
    }

    @Test
    fun `a wall clock set back postpones the switch-off by the same amount`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)), config = AutoOffConfig(resyncInterval = Duration.INFINITE))
        rig.start()
        rig.lightOn()
        rig.advance(10.minutes)

        rig.clock.jump = (-1).hours
        rig.advance(30.minutes) // t = 40 min: the countdown, in wall-clock terms, has 50 min left
        assertTrue(rig.switcher.calls.isEmpty())

        rig.advance(30.minutes) // t = 70 min
        assertTrue(rig.switcher.calls.isEmpty())
        rig.advance(20.minutes) // t = 90 min = 30 min + 1 h
        assertEquals(listOf(90.minutes), times(rig))
    }
}
