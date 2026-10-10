@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.automation

import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.ModuleState
import app.domik.deimic.protocol.OutputEvent
import app.domik.deimic.protocol.StateFormat
import app.domik.deimic.protocol.StateSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Neutral output addresses (no data of any real installation). */
val LIGHT_A = OutputAddress(2, 3)
val LIGHT_B = OutputAddress(2, 4)
val LIGHT_C = OutputAddress(3, 9)

fun rule(id: String, output: OutputAddress, delay: Duration = 30.minutes, enabled: Boolean = true) =
    AutoOffRule(id, output, delay, enabled)

fun ruleSet(vararg rules: AutoOffRule) = AutoOffRuleSet(rules.toList())

/** Wall clock driven by the test scheduler's virtual time; [jump] simulates a clock change (NTP, resume from sleep). */
class TestClock(
    private val scheduler: TestCoroutineScheduler,
    private val origin: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000),
) : Clock {
    var jump: Duration = Duration.ZERO

    override fun now(): Instant = origin + scheduler.currentTime.milliseconds + jump
}

/** State snapshot with one record per module; [modules] maps module -> relays that are on. */
fun snapshot(modules: Map<Int, Set<Int>>): StateSnapshot =
    StateSnapshot(
        command = CommandCode.STATE_NEW,
        format = StateFormat.NEW,
        modules = modules.map { (module, on) ->
            ModuleState(
                module = module.toUInt(),
                format = StateFormat.NEW,
                outputs = List(128) { it in on },
                heaters = null,
                temperatures = null,
                dimmers = emptyList(),
                inputs = emptyList(),
                shutterStates = emptyList(),
                shutterPositions = null,
            )
        },
    )

/** Fake controller link: a hot message flow, a connection flag and a state that can be requested. */
class FakeEvents(connected: Boolean = true, modules: Set<Int> = setOf(2, 3)) : ControllerEvents {
    private val connectedFlow = MutableStateFlow(connected)
    private val messageFlow = MutableSharedFlow<ControllerMessage>(extraBufferCapacity = 1024)

    /** module -> relays that are on (what a state request reports). */
    val state: MutableMap<Int, MutableSet<Int>> = modules.associateWith { mutableSetOf<Int>() }.toMutableMap()

    var stateRequests = 0
        private set

    /** When set, [requestState] gets no answer. */
    var readFails = false

    override val connected: Flow<Boolean> get() = connectedFlow
    override val messages: Flow<ControllerMessage> get() = messageFlow

    override suspend fun requestState(): Boolean {
        stateRequests++
        if (readFails) return false
        emit(snapshot(state)) // like a Connection: the answer is also a message
        return true
    }

    fun setConnected(value: Boolean) {
        connectedFlow.value = value
    }

    /** The output goes on and the controller reports it. */
    fun switchOn(output: OutputAddress) {
        state.getOrPut(output.module) { mutableSetOf() } += output.relay
        emit(output, true)
    }

    /** The output goes off and the controller reports it. */
    fun switchOff(output: OutputAddress) {
        state[output.module]?.remove(output.relay)
        emit(output, false)
    }

    /** Changes the state without an event (an event lost during an outage / never sent). */
    fun changeSilently(output: OutputAddress, on: Boolean) {
        val relays = state.getOrPut(output.module) { mutableSetOf() }
        if (on) relays += output.relay else relays -= output.relay
    }

    fun emit(message: ControllerMessage) {
        check(messageFlow.tryEmit(message))
    }

    private fun emit(output: OutputAddress, on: Boolean) {
        emit(OutputEvent(output.module.toUInt(), output.relay.toUByte(), on))
    }
}

/** One recorded command. */
data class SwitchCall(val atMillis: Long, val output: OutputAddress, val on: Boolean)

/**
 * Fake switcher. Without a script it behaves like a healthy controller: the output goes off, the OFF event is
 * delivered and the call returns [CommandOutcome.Confirmed]. [script] outcomes are used first, one per call; with
 * [applyScripted] the controller still switches the output off (the command arrived, only the answer failed).
 */
class FakeSwitcher(private val events: FakeEvents, private val scheduler: TestCoroutineScheduler) : OutputSwitcher {
    val calls = mutableListOf<SwitchCall>()
    val script = ArrayDeque<CommandOutcome>()
    var applyScripted = false
    var hang = false
    var throwOnCall: Exception? = null

    /** Runs when a call is made, before it returns (e.g. to drop the connection in the middle of a command). */
    var onCall: () -> Unit = {}

    /** When set, calls wait for this result (the test completes it when it wants the command to return). */
    var gate: CompletableDeferred<CommandOutcome>? = null

    override suspend fun setOutput(module: Int, relay: Int, on: Boolean): CommandOutcome {
        val output = OutputAddress(module, relay)
        calls += SwitchCall(scheduler.currentTime, output, on)
        onCall()
        throwOnCall?.let { throw it }
        if (hang) awaitCancellation()
        gate?.let { return it.await() }
        val scripted = script.removeFirstOrNull()
        if (scripted == null || applyScripted) {
            if (!on) events.switchOff(output) else events.switchOn(output)
        }
        return scripted ?: CommandOutcome.Confirmed
    }

    fun callsFor(output: OutputAddress): List<SwitchCall> = calls.filter { it.output == output }
}

/** Test setup: engine on the test's background scope with fakes and a virtual clock. */
class Rig(
    private val testScope: TestScope,
    rules: AutoOffRuleSet,
    connected: Boolean = true,
    val store: InMemoryDeadlineStore = InMemoryDeadlineStore(),
    private val config: AutoOffConfig = AutoOffConfig(),
    modules: Set<Int> = setOf(2, 3),
) {
    val events = FakeEvents(connected, modules)
    val switcher = FakeSwitcher(events, testScope.testScheduler)
    val rules = MutableStateFlow(rules)
    val clock = TestClock(testScope.testScheduler)
    var engine = newEngine()
        private set

    private fun newEngine() = AutoOffEngine(events, switcher, this.rules, testScope.backgroundScope, clock, store, config)

    val now: Long get() = testScope.testScheduler.currentTime

    val timers get() = engine.timers.value

    /** Starts the engine and lets it process what it was given. */
    fun start() {
        engine.start()
        testScope.runCurrent()
    }

    /** Lets [duration] of virtual time pass, including everything that falls exactly on the end. */
    fun advance(duration: Duration) {
        testScope.advanceTimeBy(duration)
        testScope.runCurrent()
    }

    fun settle() = testScope.runCurrent()

    /** Stops the engine and starts a new one on the same fakes and store (a restart of the app). */
    suspend fun restart() {
        engine.stop()
        engine = newEngine()
        start()
    }
}
