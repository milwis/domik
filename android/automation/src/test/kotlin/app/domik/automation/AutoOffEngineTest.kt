package app.domik.automation

import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.OutputEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The countdown semantics: what starts, restarts and cancels a countdown. All time is virtual. */
class AutoOffEngineTest {
    private fun SwitchCall(at: kotlin.time.Duration, output: OutputAddress) = app.domik.automation.SwitchCall(at.inWholeMilliseconds, output, false)

    @Test
    fun `an output switched on is switched off exactly after the delay`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()

        rig.events.switchOn(LIGHT_A) // from the controller: a wall switch, a scene or this very app
        rig.advance(30.minutes - 1.milliseconds)
        assertTrue(rig.switcher.calls.isEmpty())

        rig.advance(1.milliseconds)
        assertEquals(listOf(SwitchCall(30.minutes, LIGHT_A)), rig.switcher.calls)
        assertTrue(rig.timers.isEmpty())

        rig.advance(2.hours)
        assertEquals(1, rig.switcher.calls.size)
    }

    @Test
    fun `timers expose the running countdown`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        assertTrue(rig.timers.isEmpty())

        rig.events.switchOn(LIGHT_A)
        rig.advance(5.minutes)

        val timer = rig.timers.getValue(LIGHT_A)
        assertEquals("r", timer.ruleId)
        assertEquals(rig.clock.now() + 25.minutes, timer.deadline)
        assertEquals(AutoOffTimer.Phase.COUNTING, timer.phase)
        assertEquals(0, timer.attempts)
        assertEquals(setOf(LIGHT_A), rig.timers.keys)
    }

    @Test
    fun `switching off before the deadline cancels the countdown`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.start()

        rig.events.switchOn(LIGHT_A)
        rig.advance(10.minutes)
        rig.events.switchOff(LIGHT_A)
        rig.settle()
        assertTrue(rig.timers.isEmpty())

        rig.advance(3.hours)
        assertTrue(rig.switcher.calls.isEmpty())
    }

    @Test
    fun `switching on again after off restarts the full delay`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.start()

        rig.events.switchOn(LIGHT_A)
        rig.advance(10.minutes)
        rig.events.switchOff(LIGHT_A)
        rig.advance(10.minutes)
        rig.events.switchOn(LIGHT_A) // t = 20 min
        rig.advance(29.minutes) // t = 49 min: the first countdown would have ended at 30 min
        assertTrue(rig.switcher.calls.isEmpty())

        rig.advance(1.minutes) // t = 50 min
        assertEquals(listOf(SwitchCall(50.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `another on event of an output that is already counting restarts the countdown`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.start()

        rig.events.switchOn(LIGHT_A)
        rig.advance(20.minutes)
        rig.events.switchOn(LIGHT_A) // t = 20 min
        rig.advance(29.minutes)
        assertTrue(rig.switcher.calls.isEmpty())

        rig.advance(1.minutes)
        assertEquals(listOf(SwitchCall(50.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `outputs without an enabled rule are left alone`() = runTest {
        val rig = Rig(this, ruleSet(rule("off", LIGHT_A, enabled = false)))
        rig.start()

        rig.events.switchOn(LIGHT_A)
        rig.events.switchOn(LIGHT_B)
        rig.advance(5.hours)

        assertTrue(rig.switcher.calls.isEmpty())
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `outputs have independent countdowns`() = runTest {
        val rig = Rig(this, ruleSet(rule("a", LIGHT_A, 30.minutes), rule("b", LIGHT_B, 10.minutes), rule("c", LIGHT_C, 45.minutes)))
        rig.start()

        rig.events.switchOn(LIGHT_A)
        rig.advance(5.minutes)
        rig.events.switchOn(LIGHT_B)
        rig.events.switchOn(LIGHT_C)
        rig.advance(1.hours)

        assertEquals(
            listOf(SwitchCall(15.minutes, LIGHT_B), SwitchCall(30.minutes, LIGHT_A), SwitchCall(50.minutes, LIGHT_C)),
            rig.switcher.calls,
        )
    }

    @Test
    fun `the same relay number on another module is another output`() = runTest {
        val other = OutputAddress(LIGHT_A.module + 1, LIGHT_A.relay)
        val rig = Rig(this, ruleSet(rule("a", LIGHT_A)))
        rig.start()

        rig.events.switchOn(other)
        rig.advance(2.hours)

        assertTrue(rig.switcher.calls.isEmpty())
    }

    @Test
    fun `a rule created while the output is on counts from the change`() = runTest {
        val rig = Rig(this, AutoOffRuleSet.EMPTY)
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(5.minutes)

        rig.rules.value = ruleSet(rule("r", LIGHT_A, 30.minutes))
        rig.advance(29.minutes)
        assertTrue(rig.switcher.calls.isEmpty())

        rig.advance(1.minutes)
        assertEquals(listOf(SwitchCall(35.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `a rule created while the output is off waits for the next on event`() = runTest {
        val rig = Rig(this, AutoOffRuleSet.EMPTY)
        rig.start()
        rig.advance(1.minutes)

        rig.rules.value = ruleSet(rule("r", LIGHT_A))
        rig.advance(2.hours)
        assertTrue(rig.switcher.calls.isEmpty())

        rig.events.switchOn(LIGHT_A)
        rig.advance(30.minutes)
        assertEquals(1, rig.switcher.calls.size)
    }

    @Test
    fun `disabling a rule cancels the countdown and enabling it counts from then`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(10.minutes)

        rig.rules.value = ruleSet(rule("r", LIGHT_A, enabled = false))
        rig.settle()
        assertTrue(rig.timers.isEmpty())
        rig.advance(40.minutes) // t = 50 min
        assertTrue(rig.switcher.calls.isEmpty())

        rig.rules.value = ruleSet(rule("r", LIGHT_A, enabled = true)) // the light is still on
        rig.advance(30.minutes)
        assertEquals(listOf(SwitchCall(80.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `deleting a rule cancels the countdown`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(10.minutes)

        rig.rules.value = AutoOffRuleSet.EMPTY
        rig.advance(3.hours)

        assertTrue(rig.switcher.calls.isEmpty())
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `a new delay restarts the countdown from the change`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(20.minutes)

        rig.rules.value = ruleSet(rule("r", LIGHT_A, 10.minutes))
        rig.advance(9.minutes)
        assertTrue(rig.switcher.calls.isEmpty())

        rig.advance(1.minutes)
        assertEquals(listOf(SwitchCall(30.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `unrelated rule changes leave a running countdown alone`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(20.minutes)

        // another rule added, and the rule re-saved under a new id with the same delay
        rig.rules.value = ruleSet(rule("renamed", LIGHT_A, 30.minutes), rule("b", LIGHT_B))
        rig.advance(10.minutes)

        assertEquals(listOf(SwitchCall(30.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `an unrelated message does not disturb anything`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(10.minutes)

        rig.events.emit(ControllerMessage.Pong(0u))
        rig.events.emit(OutputEvent(LIGHT_A.module.toUInt(), 250u, true)) // relay without a rule, out of the usual range
        rig.advance(20.minutes)

        assertEquals(listOf(SwitchCall(30.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `start is idempotent and stop ends the countdowns`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.start()
        rig.engine.start()
        rig.settle()
        assertTrue(rig.engine.isRunning)

        rig.events.switchOn(LIGHT_A)
        rig.advance(10.minutes)
        rig.engine.stop()
        assertFalse(rig.engine.isRunning)
        assertTrue(rig.timers.isEmpty())

        rig.advance(2.hours)
        assertTrue(rig.switcher.calls.isEmpty())
    }

    @Test
    fun `a stopped engine can be started again`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.start()
        rig.engine.stop()
        rig.start()

        rig.events.switchOn(LIGHT_A)
        rig.advance(30.minutes)

        assertEquals(1, rig.switcher.calls.size)
    }

    @Test
    fun `an event from the controller works the same when it is the engine's own command`() = runTest {
        // The switcher reports OFF itself (FakeSwitcher emits the event); no second command must follow.
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 1.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(1.minutes)
        rig.advance(10.seconds)

        assertEquals(1, rig.switcher.calls.size)
        assertEquals(setOf<Int>(), rig.events.state.getValue(LIGHT_A.module))
    }
}
