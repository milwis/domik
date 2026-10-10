package app.domik.automation

import app.domik.deimic.protocol.OutputEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Start of the app, reconnects, outages, state reads. */
class AutoOffEngineRecoveryTest {
    private fun at(offset: Duration, output: OutputAddress) = SwitchCall(offset.inWholeMilliseconds, output, false)

    @Test
    fun `an output that is already on at start is counted from the moment of observation`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.events.changeSilently(LIGHT_A, on = true)
        rig.advance(5.minutes) // the engine is not running yet

        rig.start() // t = 5 min
        rig.advance(29.minutes)
        assertTrue(rig.switcher.calls.isEmpty())

        rig.advance(1.minutes)
        assertEquals(listOf(at(35.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `an output that is off at start is left alone`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.start()
        rig.advance(3.hours)

        assertTrue(rig.switcher.calls.isEmpty())
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `nothing happens before the link is up and the state is read right after it comes up`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)), connected = false)
        rig.events.changeSilently(LIGHT_A, on = true)
        rig.start()
        rig.advance(1.hours)
        assertEquals(0, rig.events.stateRequests)
        assertTrue(rig.timers.isEmpty())

        rig.events.setConnected(true) // t = 60 min
        rig.settle()
        assertEquals(1, rig.events.stateRequests)
        rig.advance(30.minutes)

        assertEquals(listOf(at(90.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `a deadline that passes during an outage is carried out after the reconnect`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(5.minutes)
        rig.events.setConnected(false)

        rig.advance(35.minutes) // t = 40 min, deadline was 30 min
        assertTrue(rig.switcher.calls.isEmpty())
        assertEquals(AutoOffTimer.Phase.WAITING_FOR_CONTROLLER, rig.timers.getValue(LIGHT_A).phase)

        rig.events.setConnected(true)
        rig.settle()

        assertEquals(listOf(at(40.minutes, LIGHT_A)), rig.switcher.calls)
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `an output that went off during the outage is not switched off again`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(5.minutes)
        rig.events.setConnected(false)
        rig.events.changeSilently(LIGHT_A, on = false) // the OFF event is lost
        rig.advance(55.minutes)

        rig.events.setConnected(true)
        rig.advance(2.hours)

        assertTrue(rig.switcher.calls.isEmpty())
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `a reconnect before the deadline keeps the original deadline`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(10.minutes)
        rig.events.setConnected(false)
        rig.advance(5.minutes)
        rig.events.setConnected(true) // t = 15 min, the light is still on
        rig.advance(14.minutes + 59.seconds)
        assertTrue(rig.switcher.calls.isEmpty())

        rig.advance(1.seconds)
        assertEquals(listOf(at(30.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `an output switched on during the outage is counted from the reconnect`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.setConnected(false)
        rig.advance(10.minutes)
        rig.events.changeSilently(LIGHT_A, on = true)
        rig.advance(10.minutes)

        rig.events.setConnected(true) // t = 20 min
        rig.advance(30.minutes)

        assertEquals(listOf(at(50.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `a rule created during an outage takes effect with the state read after the reconnect`() = runTest {
        val rig = Rig(this, AutoOffRuleSet.EMPTY)
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.events.setConnected(false)
        rig.advance(10.minutes)
        rig.rules.value = ruleSet(rule("r", LIGHT_A, 30.minutes))
        rig.advance(10.minutes)
        assertTrue(rig.timers.isEmpty())

        rig.events.setConnected(true) // t = 20 min
        rig.advance(30.minutes)

        assertEquals(listOf(at(50.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `an event on the current connection is trusted even when the state cannot be read`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.events.readFails = true
        rig.start()

        rig.events.switchOn(LIGHT_A)
        rig.advance(30.minutes)

        assertEquals(listOf(at(30.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `a countdown from before an outage waits until the state has been read`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(5.minutes)
        rig.events.setConnected(false)
        rig.advance(1.minutes)
        rig.events.readFails = true
        rig.events.setConnected(true) // t = 6 min, link up but the state cannot be read
        rig.advance(30.minutes)

        // the countdown cannot be trusted (the light may have been switched off during the outage)
        assertTrue(rig.switcher.calls.isEmpty())
        assertEquals(AutoOffTimer.Phase.WAITING_FOR_CONTROLLER, rig.timers.getValue(LIGHT_A).phase)

        rig.events.readFails = false
        rig.advance(10.seconds) // the next try

        assertEquals(1, rig.switcher.calls.size)
        assertTrue(rig.timers.isEmpty())
    }

    @Test
    fun `failed state reads are retried at the configured pause`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)))
        rig.events.readFails = true
        rig.start()
        assertEquals(1, rig.events.stateRequests)

        rig.advance(9.seconds)
        assertEquals(1, rig.events.stateRequests)
        rig.advance(1.seconds)
        assertEquals(2, rig.events.stateRequests)
        rig.advance(30.seconds)
        assertEquals(5, rig.events.stateRequests)
    }

    @Test
    fun `the state is re-read periodically and catches an output that was on without an event`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        assertEquals(1, rig.events.stateRequests)

        rig.advance(3.minutes)
        rig.events.changeSilently(LIGHT_A, on = true) // no event
        rig.advance(7.minutes - 1.seconds)
        assertEquals(1, rig.events.stateRequests)
        rig.advance(1.seconds) // t = 10 min
        assertEquals(2, rig.events.stateRequests)
        assertEquals(AutoOffTimer.Phase.COUNTING, rig.timers.getValue(LIGHT_A).phase)

        rig.advance(30.minutes)
        assertEquals(listOf(at(40.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `a periodic read that shows the output off drops its countdown`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(3.minutes)
        rig.events.changeSilently(LIGHT_A, on = false) // the OFF event was never sent
        rig.advance(7.minutes)

        assertTrue(rig.timers.isEmpty())
        rig.advance(2.hours)
        assertTrue(rig.switcher.calls.isEmpty())
    }

    @Test
    fun `a periodic read does not restart a running countdown`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)

        rig.advance(30.minutes) // reads at 10 and 20 min took place

        assertEquals(listOf(at(30.minutes, LIGHT_A)), rig.switcher.calls)
        assertEquals(4, rig.events.stateRequests) // 0, 10, 20, 30 min
    }

    @Test
    fun `an infinite resync interval reads only once per connection`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)), config = AutoOffConfig(resyncInterval = Duration.INFINITE))
        rig.start()
        rig.advance(5.hours)
        assertEquals(1, rig.events.stateRequests)

        rig.events.setConnected(false)
        rig.settle()
        rig.events.setConnected(true)
        rig.settle()
        assertEquals(2, rig.events.stateRequests)
    }

    @Test
    fun `a state read without the rule's module leaves the countdown unverified`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)), modules = setOf(3)) // the controller reports module 3 only
        rig.start()
        rig.events.emit(OutputEvent(LIGHT_A.module.toUInt(), LIGHT_A.relay.toUByte(), true)) // not part of the readable state
        rig.advance(5.minutes)
        rig.events.setConnected(false)
        rig.settle()
        rig.events.setConnected(true)
        rig.advance(1.hours)

        // knowledge from before the reconnect cannot be confirmed, so nothing is sent
        assertTrue(rig.switcher.calls.isEmpty())
        assertEquals(AutoOffTimer.Phase.WAITING_FOR_CONTROLLER, rig.timers.getValue(LIGHT_A).phase)
    }

    // Persistence

    @Test
    fun `deadlines are saved while counting and removed when the countdown ends`() = runTest {
        val rig = Rig(this, ruleSet(rule("a", LIGHT_A, 30.minutes), rule("b", LIGHT_B, 10.minutes)))
        rig.start()
        assertTrue(rig.store.deadlines.isEmpty())

        rig.events.switchOn(LIGHT_A)
        rig.events.switchOn(LIGHT_B)
        rig.settle()
        assertEquals(mapOf(LIGHT_A to rig.clock.now() + 30.minutes, LIGHT_B to rig.clock.now() + 10.minutes), rig.store.deadlines)

        rig.advance(10.minutes)
        assertEquals(setOf(LIGHT_A), rig.store.deadlines.keys)

        rig.events.switchOff(LIGHT_A)
        rig.settle()
        assertTrue(rig.store.deadlines.isEmpty())
    }

    @Test
    fun `after a restart the saved deadline is kept`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A) // deadline at t = 30 min
        rig.advance(10.minutes)

        rig.restart() // the app is restarted at t = 10 min; the light is still on
        rig.advance(19.minutes + 59.seconds)
        assertTrue(rig.switcher.calls.isEmpty())

        rig.advance(1.seconds)
        assertEquals(listOf(at(30.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `a deadline that passed while the app was down is carried out as soon as the state is known`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(10.minutes)
        rig.engine.stop()
        rig.advance(2.hours) // t = 130 min, nobody is running the automation

        rig.start()

        assertEquals(listOf(at(130.minutes, LIGHT_A)), rig.switcher.calls)
        assertTrue(rig.store.deadlines.isEmpty())
    }

    @Test
    fun `a saved deadline of an output that is off now is dropped`() = runTest {
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(10.minutes)
        rig.engine.stop()
        rig.events.changeSilently(LIGHT_A, on = false) // switched off while the app was down

        rig.start()
        rig.advance(3.hours)

        assertTrue(rig.switcher.calls.isEmpty())
        assertTrue(rig.store.deadlines.isEmpty())
    }

    @Test
    fun `a saved deadline further away than the delay is shortened to the delay`() = runTest {
        val store = InMemoryDeadlineStore()
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)), store = store)
        // e.g. the rule was shortened (or the clock was set back) while the app was down
        store.save(mapOf(LIGHT_A to rig.clock.now() + 10.hours))
        rig.events.changeSilently(LIGHT_A, on = true)

        rig.start()
        rig.advance(30.minutes)

        assertEquals(listOf(at(30.minutes, LIGHT_A)), rig.switcher.calls)
    }

    @Test
    fun `saved deadlines of modules the controller did not report stay saved`() = runTest {
        val store = InMemoryDeadlineStore(mapOf(LIGHT_C to TestClock(testScheduler).now() + 1.hours))
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A)), store = store, modules = setOf(2))

        rig.start()
        rig.events.switchOn(LIGHT_A)
        rig.advance(5.minutes)

        assertEquals(setOf(LIGHT_A, LIGHT_C), store.deadlines.keys)
    }

    @Test
    fun `a store that fails does not stop the countdowns`() = runTest {
        val failing = object : DeadlineStore {
            override suspend fun load() = throw java.io.IOException("disk")
            override suspend fun save(deadlines: Map<OutputAddress, kotlin.time.Instant>) = throw java.io.IOException("disk")
        }
        val rig = Rig(this, ruleSet(rule("r", LIGHT_A, 30.minutes)))
        val engine = AutoOffEngine(rig.events, rig.switcher, rig.rules, backgroundScope, rig.clock, failing)
        engine.start()
        rig.settle()
        rig.events.switchOn(LIGHT_A)
        rig.advance(30.minutes)

        assertEquals(listOf(at(30.minutes, LIGHT_A)), rig.switcher.calls)
    }
}
