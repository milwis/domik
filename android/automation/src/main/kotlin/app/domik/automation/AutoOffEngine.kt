package app.domik.automation

import app.domik.deimic.client.ConnectionErrors
import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.OutputEvent
import app.domik.deimic.protocol.StateSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Timing and retry policy of [AutoOffEngine]. */
data class AutoOffConfig(
    /** Switch-off attempts per countdown before the engine gives up (until the next state read). */
    val maxAttempts: Int = 5,
    /** Pause before the 2nd attempt; it doubles with every further attempt up to [retryMaxDelay]. */
    val retryBaseDelay: Duration = 5.seconds,
    val retryMaxDelay: Duration = 2.minutes,
    /** How often the engine asks for the controller's state while connected ([Duration.INFINITE] = only on connect). */
    val resyncInterval: Duration = 10.minutes,
    /** Pause before another try when the state could not be obtained. */
    val resyncRetryDelay: Duration = 10.seconds,
    /** Upper limit of one wait of a countdown; the wall clock is compared with the deadline after each wait. */
    val clockCheckInterval: Duration = 30.seconds,
    /** A switch-off command that gives no result for this long counts as failed. */
    val switchTimeout: Duration = 30.seconds,
    /** A state request that gets no answer for this long counts as failed. */
    val stateRequestTimeout: Duration = 15.seconds,
) {
    init {
        require(maxAttempts >= 1)
        require(retryBaseDelay.isPositive() && retryMaxDelay >= retryBaseDelay)
        require(resyncInterval.isPositive() && resyncRetryDelay.isPositive())
        require(clockCheckInterval.isPositive() && switchTimeout.isPositive() && stateRequestTimeout.isPositive())
    }

    /** Pause after [failedAttempts] failed attempts (1 -> [retryBaseDelay], then doubling). */
    fun retryDelay(failedAttempts: Int): Duration {
        require(failedAttempts >= 1)
        val delay = retryBaseDelay * (1L shl (failedAttempts - 1).coerceAtMost(30)).toDouble()
        return if (delay > retryMaxDelay) retryMaxDelay else delay
    }
}

/** State of one running countdown, for the UI ("switches off in 12 min") and diagnostics. */
data class AutoOffTimer(
    val ruleId: String,
    /** When the output is to be switched off (wall-clock time). */
    val deadline: Instant,
    val phase: Phase,
    /** Switch-off commands sent so far in this countdown. */
    val attempts: Int = 0,
    /** Result of the last failed attempt. */
    val lastOutcome: CommandOutcome? = null,
) {
    enum class Phase {
        /** Waiting for the deadline. */
        COUNTING,

        /** The deadline passed (or came during an outage) but the link or the current state of the output is not known yet. */
        WAITING_FOR_CONTROLLER,

        /** The switch-off command is on its way. */
        SWITCHING,

        /** The last attempt failed; the next one follows after a pause. */
        RETRYING,

        /** [AutoOffConfig.maxAttempts] failed; tried again with the next state read (or reconnect). */
        GAVE_UP,
    }
}

/**
 * Auto-off automation: switches an output off a set time after it went on ([AutoOffRule]).
 *
 * **Where it runs.** On the wall tablet (the app in the foreground 24/7) or on a JVM service in the house. Only
 * **one** instance may execute the rules of a controller (the app needs a switch "this device runs automations"),
 * otherwise two of them would switch off the same lights twice; with a phone in a pocket the countdown is not reliable.
 *
 * **What starts a countdown.** Every "output went on" event (0x109) of an output with an enabled rule – no matter
 * who switched it (this app, another client, a wall switch, a scene). A further "on" event of an output that is
 * already counting restarts the countdown. An "off" event (0x108) cancels it. An output that is already on when the
 * engine learns about it (start of the app, reconnect, a rule created or enabled while the light is on, a state
 * read) is counted **from the moment of observation** – the real switch-on time is unknown, and a late switch-off
 * is safer than switching off a light someone is using right now. Editing a rule (new delay) restarts its
 * countdown from the moment of the change; disabling or deleting it cancels the countdown.
 *
 * **Reliability.**
 * - Deadlines are absolute times and are saved through [DeadlineStore]; after a restart the engine resumes them when
 *   the state read shows the output still on (a deadline that has passed means: switch off right away).
 * - Nothing is sent while the link is down. After a reconnect the knowledge from before the outage is **stale**
 *   (events were missed): the engine requests the state (0x202) first, drops countdowns of outputs that are off now,
 *   and only then switches off whatever is overdue. A countdown that was started by an event on the current
 *   connection is trusted without that.
 * - The state is requested again every [AutoOffConfig.resyncInterval] too, which catches missed events and outputs that
 *   were on without any event.
 * - A failed switch-off (no acknowledgement, error, state still on) is retried with growing pauses, at most
 *   [AutoOffConfig.maxAttempts] times; failures caused by a lost connection do not count. After giving up the next
 *   state read tries again, so a light never stays on because of one bad moment.
 * - Wall-clock jumps and device sleep: each wait is cut to [AutoOffConfig.clockCheckInterval] and the clock is
 *   compared with the deadline after it.
 * - Switching off an already-off output is harmless, so a switch-off command does not need another check first.
 * - Without a running app / tablet / network there is no switch-off – to be stated in the terms of use.
 *
 * The engine runs in [scope] (cancel it or call [stop] to end it). All its state lives in one coroutine fed by an
 * event queue, so it is deterministic under virtual time; [clock] is injectable.
 *
 * @param rules the current rules; changes are applied immediately.
 */
class AutoOffEngine(
    private val events: ControllerEvents,
    private val switcher: OutputSwitcher,
    private val rules: StateFlow<AutoOffRuleSet>,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val deadlineStore: DeadlineStore = DeadlineStore.None,
    val config: AutoOffConfig = AutoOffConfig(),
) {
    private val _timers = MutableStateFlow<Map<OutputAddress, AutoOffTimer>>(emptyMap())

    /** Running countdowns by output. Empty while the engine is stopped. */
    val timers: StateFlow<Map<OutputAddress, AutoOffTimer>> = _timers.asStateFlow()

    private val lock = Any()
    private var job: Job? = null

    val isRunning: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** Starts the engine in the background. No-op while it is running. */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { Runner().run() }
        }
    }

    /** Stops the engine and forgets its in-memory state (saved deadlines stay in the [DeadlineStore]). */
    suspend fun stop() {
        val running = synchronized(lock) { job.also { job = null } }
        running?.cancelAndJoin()
        _timers.value = emptyMap()
    }

    private sealed interface Input {
        class Message(val message: ControllerMessage) : Input
        class Rules(val set: AutoOffRuleSet) : Input
        class Connected(val connected: Boolean) : Input

        /** A countdown or a retry pause of the entry with [token] is over. */
        class Due(val output: OutputAddress, val token: Long) : Input
        class SwitchDone(val output: OutputAddress, val token: Long, val outcome: CommandOutcome) : Input
    }

    private class Entry(
        val output: OutputAddress,
        var ruleId: String,
        val deadline: Instant,
        /** Generation of the countdown; results and timers of an older generation are ignored. */
        val token: Long,
        /** Known to hold on the current connection (started by an event, or confirmed by a state read). */
        var verified: Boolean,
    ) {
        var phase = AutoOffTimer.Phase.COUNTING
        var attempts = 0
        var lastOutcome: CommandOutcome? = null
        var job: Job? = null
    }

    /**
     * One run of the engine. Everything except the child coroutines' `inbox.trySend` happens in the single coroutine of
     * [run], so no locking is needed.
     */
    private inner class Runner {
        private val inbox = Channel<Input>(Channel.UNLIMITED)
        private val saves = Channel<Map<OutputAddress, Instant>>(Channel.CONFLATED)
        private lateinit var children: CoroutineScope

        private var rulesByOutput: Map<OutputAddress, AutoOffRule> = emptyMap()
        private val entries = LinkedHashMap<OutputAddress, Entry>()

        /** Last known state of outputs; cleared when the link goes down (stale from then on). */
        private val known = HashMap<OutputAddress, Boolean>()

        /** Deadlines from the store that no state read has confirmed or dropped yet. */
        private val persisted = LinkedHashMap<OutputAddress, Instant>()
        private var lastSaved: Map<OutputAddress, Instant> = emptyMap()
        private var isConnected = false
        private var resyncJob: Job? = null
        private var nextToken = 0L

        suspend fun run() = supervisorScope {
            children = this
            // Subscribe first, then ask for anything: no event can slip between the two.
            launch(start = CoroutineStart.UNDISPATCHED) {
                events.messages.collect { message ->
                    if (message is OutputEvent || message is StateSnapshot) inbox.trySend(Input.Message(message))
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                rules.collect { inbox.trySend(Input.Rules(it)) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                events.connected.distinctUntilChanged().collect { inbox.trySend(Input.Connected(it)) }
            }
            persisted.putAll(loadDeadlines())
            lastSaved = persisted.toMap()
            launch {
                for (deadlines in saves) {
                    try {
                        deadlineStore.save(deadlines)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // not saving a deadline is not worth stopping the automation for
                    }
                }
            }
            for (input in inbox) {
                handle(input)
                publish()
            }
        }

        private suspend fun loadDeadlines(): Map<OutputAddress, Instant> =
            try {
                deadlineStore.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }

        private fun handle(input: Input) {
            when (input) {
                is Input.Message -> when (val message = input.message) {
                    is OutputEvent -> onOutputEvent(message)
                    is StateSnapshot -> onSnapshot(message)
                    else -> Unit
                }
                is Input.Rules -> onRules(input.set)
                is Input.Connected -> onConnected(input.connected)
                is Input.Due -> entries[input.output]?.takeIf { it.token == input.token }?.let(::fire)
                is Input.SwitchDone -> onSwitchDone(input)
            }
        }

        // Inputs

        private fun onOutputEvent(event: OutputEvent) {
            val output = OutputAddress(event.module.toInt(), event.relay.toInt())
            known[output] = event.isOn
            if (!event.isOn) {
                forget(output)
                return
            }
            val rule = rulesByOutput[output] ?: return
            startCountdown(output, rule, clock.now() + rule.delay, verified = true)
        }

        private fun onSnapshot(snapshot: StateSnapshot) {
            if (snapshot.isEmpty || snapshot.modules.isEmpty()) return
            val now = clock.now()
            val covered = HashSet<Int>()
            for (module in snapshot.modules) {
                covered += module.module.toInt()
                module.outputs.forEachIndexed { relay, on -> known[OutputAddress(module.module.toInt(), relay)] = on }
            }
            for ((output, rule) in rulesByOutput) {
                if (output.module !in covered) continue
                val on = known[output] ?: continue
                val entry = entries[output]
                when {
                    !on -> forget(output)
                    entry == null -> {
                        // Already on when we learn about it: resume the saved deadline, otherwise count from now.
                        val limit = now + rule.delay
                        val saved = persisted[output]
                        startCountdown(output, rule, if (saved != null && saved < limit) saved else limit, verified = true)
                    }
                    else -> {
                        entry.verified = true
                        when (entry.phase) {
                            AutoOffTimer.Phase.WAITING_FOR_CONTROLLER -> fire(entry)
                            AutoOffTimer.Phase.GAVE_UP -> {
                                entry.attempts = 0
                                fire(entry)
                            }
                            else -> Unit
                        }
                    }
                }
            }
            persisted.keys.removeAll { it.module in covered }
        }

        private fun onRules(set: AutoOffRuleSet) {
            val previous = rulesByOutput
            val current = set.rules.filter { it.enabled }.associateBy { it.output }
            rulesByOutput = current
            for (output in previous.keys - current.keys) forget(output)
            for ((output, rule) in current) {
                val before = previous[output]
                if (before != null && before.delay == rule.delay) {
                    entries[output]?.ruleId = rule.id
                    continue
                }
                // New rule, or a new delay: count from now (only if the output is known to be on).
                removeEntry(output)
                if (known[output] == true) startCountdown(output, rule, clock.now() + rule.delay, verified = true)
            }
        }

        private fun onConnected(connected: Boolean) {
            if (connected == isConnected) return
            isConnected = connected
            if (connected) {
                startResync()
            } else {
                resyncJob?.cancel()
                resyncJob = null
                known.clear()
                entries.values.forEach { it.verified = false }
            }
        }

        private fun onSwitchDone(done: Input.SwitchDone) {
            val entry = entries[done.output]?.takeIf { it.token == done.token } ?: return
            val outcome = done.outcome
            when {
                outcome.isAccepted || (outcome is CommandOutcome.OutputMismatch && !outcome.actualOn) -> {
                    known[done.output] = false
                    forget(done.output)
                }
                outcome is CommandOutcome.Invalid || outcome is CommandOutcome.NotEnabled -> giveUp(entry, outcome)
                !isConnected -> {
                    // The link broke: not the output's fault, wait for the reconnect and the state read.
                    entry.attempts--
                    entry.lastOutcome = outcome
                    entry.job = null
                    entry.phase = AutoOffTimer.Phase.WAITING_FOR_CONTROLLER
                }
                entry.attempts >= config.maxAttempts -> giveUp(entry, outcome)
                else -> {
                    entry.lastOutcome = outcome
                    entry.phase = AutoOffTimer.Phase.RETRYING
                    val wait = config.retryDelay(entry.attempts)
                    entry.job = children.launch {
                        delay(wait)
                        inbox.trySend(Input.Due(entry.output, entry.token))
                    }
                }
            }
        }

        // Countdowns

        private fun startCountdown(output: OutputAddress, rule: AutoOffRule, deadline: Instant, verified: Boolean) {
            forget(output)
            val entry = Entry(output, rule.id, deadline, nextToken++, verified)
            entries[output] = entry
            if (deadline <= clock.now()) {
                fire(entry)
                return
            }
            entry.job = children.launch {
                while (true) {
                    val remaining = deadline - clock.now()
                    if (!remaining.isPositive()) break
                    delay(minOf(remaining, config.clockCheckInterval))
                }
                inbox.trySend(Input.Due(output, entry.token))
            }
        }

        /** Drops the countdown of [output] (the saved deadline stays: see [forget]). */
        private fun removeEntry(output: OutputAddress) {
            entries.remove(output)?.job?.cancel()
        }

        /** Drops the countdown of [output] and its saved deadline. */
        private fun forget(output: OutputAddress) {
            removeEntry(output)
            persisted.remove(output)
        }

        /** The deadline of [entry] has come (or a retry pause is over): send the switch-off command if allowed. */
        private fun fire(entry: Entry) {
            entry.job?.cancel()
            entry.job = null
            if (!isConnected || !entry.verified) {
                entry.phase = AutoOffTimer.Phase.WAITING_FOR_CONTROLLER
                return
            }
            entry.attempts++
            entry.phase = AutoOffTimer.Phase.SWITCHING
            val output = entry.output
            val token = entry.token
            entry.job = children.launch {
                inbox.trySend(Input.SwitchDone(output, token, switchOff(output)))
            }
        }

        private suspend fun switchOff(output: OutputAddress): CommandOutcome =
            try {
                withTimeoutOrNull(config.switchTimeout) { switcher.setOutput(output.module, output.relay, false) }
                    ?: CommandOutcome.Failed(ConnectionProblem.TIMEOUT, "no result within ${config.switchTimeout}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CommandOutcome.Failed(ConnectionErrors.classify(error), error.message)
            }

        private fun giveUp(entry: Entry, outcome: CommandOutcome) {
            entry.lastOutcome = outcome
            entry.phase = AutoOffTimer.Phase.GAVE_UP
            entry.job = null
        }

        // State reads

        /**
         * While connected: ask for the state now and then every [AutoOffConfig.resyncInterval]. The answer arrives
         * through the message flow, in order with the events, and is handled by [onSnapshot].
         */
        private fun startResync() {
            resyncJob?.cancel()
            resyncJob = children.launch {
                while (true) {
                    delay(if (requestState()) config.resyncInterval else config.resyncRetryDelay)
                }
            }
        }

        private suspend fun requestState(): Boolean =
            try {
                withTimeoutOrNull(config.stateRequestTimeout) { events.requestState() } ?: false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }

        // Status and persistence

        private fun publish() {
            _timers.value = entries.mapValues { (_, entry) ->
                AutoOffTimer(entry.ruleId, entry.deadline, entry.phase, entry.attempts, entry.lastOutcome)
            }
            // Deadlines not yet confirmed by a state read stay in the file until they are used or dropped.
            val deadlines = persisted + entries.mapValues { it.value.deadline }
            if (deadlines != lastSaved) {
                lastSaved = deadlines
                saves.trySend(deadlines)
            }
        }
    }
}
