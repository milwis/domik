package app.domik.automation

import app.domik.deimic.client.commands.CommandLimits
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * One controller output: [module] is the module address (`id_device`), [relay] the `output_relay` – exactly the
 * numbers [app.domik.deimic.client.commands.DeviceCommands.setOutput] takes and the output events carry.
 *
 * Plain data without validation (events from the controller may carry any relay byte); rules validate their own
 * output ([AutoOffRule]).
 */
data class OutputAddress(val module: Int, val relay: Int) {
    override fun toString(): String = "$module/$relay"
}

/**
 * "Switch this output off when it has been on for [delay]". The timer starts when the controller reports the
 * output going on – whoever switched it (app, wall switch, scene) – and is cancelled when it goes off.
 *
 * The rule holds no names: which light an [output] is comes from the controller's configuration database.
 *
 * @property id stable identifier chosen by the caller (e.g. a UUID); unique within an [AutoOffRuleSet].
 * @property delay [MIN_DELAY]..[MAX_DELAY].
 * @property enabled a disabled rule is kept (and edited) but ignored by the engine.
 */
data class AutoOffRule(
    val id: String,
    val output: OutputAddress,
    val delay: Duration = DEFAULT_DELAY,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "rule id must be 1..$MAX_ID_LENGTH characters" }
        require(CommandLimits.isValidModule(output.module)) { "module address out of range: ${output.module}" }
        require(CommandLimits.isValidRelay(output.relay)) { "output relay out of range: ${output.relay}" }
        require(delay in MIN_DELAY..MAX_DELAY) { "delay $delay outside $MIN_DELAY..$MAX_DELAY" }
    }

    companion object {
        val MIN_DELAY: Duration = 1.minutes
        val MAX_DELAY: Duration = 24.hours
        val DEFAULT_DELAY: Duration = 30.minutes
        const val MAX_ID_LENGTH: Int = 100
    }
}

/**
 * The rules of one controller profile. Immutable; ids are unique and **at most one rule per output** (two timers
 * for one light would be ambiguous). Violations throw [IllegalArgumentException].
 */
class AutoOffRuleSet(rules: List<AutoOffRule> = emptyList()) {
    val rules: List<AutoOffRule> = rules.toList()

    init {
        val ids = HashSet<String>()
        val outputs = HashSet<OutputAddress>()
        for (rule in this.rules) {
            require(ids.add(rule.id)) { "duplicate rule id: ${rule.id}" }
            require(outputs.add(rule.output)) { "more than one rule for output ${rule.output}" }
        }
    }

    fun forOutput(output: OutputAddress): AutoOffRule? = rules.firstOrNull { it.output == output }

    /** Adds [rule] or replaces the rule with the same id. */
    fun with(rule: AutoOffRule): AutoOffRuleSet {
        val index = rules.indexOfFirst { it.id == rule.id }
        return AutoOffRuleSet(if (index < 0) rules + rule else rules.toMutableList().also { it[index] = rule })
    }

    fun without(id: String): AutoOffRuleSet = AutoOffRuleSet(rules.filterNot { it.id == id })

    override fun equals(other: Any?): Boolean = other is AutoOffRuleSet && other.rules == rules

    override fun hashCode(): Int = rules.hashCode()

    override fun toString(): String = "AutoOffRuleSet($rules)"

    companion object {
        val EMPTY = AutoOffRuleSet()
    }
}
