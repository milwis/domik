package app.domik.automation

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The document is not a readable rule set at all (not JSON, wrong shape, newer format version). */
class AutoOffRulesFormatException(message: String) : Exception(message)

/**
 * JSON form of an [AutoOffRuleSet], for the app's storage (and for export / backup):
 *
 * ```
 * {"version":1,"rules":[{"id":"…","module":1,"relay":15,"delaySeconds":1800,"enabled":true}]}
 * ```
 *
 * Reading is lenient about content and strict about structure: unknown fields are ignored, `enabled` defaults to
 * true, and a single broken rule (out of range, duplicate id or output) is skipped and reported in
 * [Decoded.problems] instead of losing all the others.
 */
object AutoOffRulesJson {
    const val VERSION: Int = 1

    /** Result of [decode]: the usable rules and a technical description of every skipped entry. */
    class Decoded(val rules: AutoOffRuleSet, val problems: List<String>)

    fun encode(rules: AutoOffRuleSet): String =
        MiniJson.stringify(
            mapOf(
                "version" to VERSION,
                "rules" to rules.rules.map {
                    mapOf(
                        "id" to it.id,
                        "module" to it.output.module,
                        "relay" to it.output.relay,
                        "delaySeconds" to it.delay.inWholeSeconds,
                        "enabled" to it.enabled,
                    )
                },
            ),
        )

    /** @throws AutoOffRulesFormatException when [text] is not a rule set document of a supported version. */
    fun decode(text: String): Decoded {
        val root = try {
            MiniJson.parse(text)
        } catch (error: JsonSyntaxException) {
            throw AutoOffRulesFormatException("not valid JSON: ${error.message}")
        }
        val document = root as? Map<*, *> ?: throw AutoOffRulesFormatException("the document is not a JSON object")
        document["version"]?.let { version ->
            if (version !is Long) throw AutoOffRulesFormatException("invalid version")
            if (version < 1 || version > VERSION) throw AutoOffRulesFormatException("unsupported version $version")
        }
        val entries = document["rules"] as? List<*> ?: throw AutoOffRulesFormatException("no \"rules\" array")

        val rules = mutableListOf<AutoOffRule>()
        val problems = mutableListOf<String>()
        val ids = HashSet<String>()
        val outputs = HashSet<OutputAddress>()
        entries.forEachIndexed { index, entry ->
            try {
                val rule = parseRule(entry)
                require(ids.add(rule.id)) { "duplicate rule id" }
                require(outputs.add(rule.output)) { "second rule for output ${rule.output}" }
                rules += rule
            } catch (error: IllegalArgumentException) {
                problems += "rules[$index]: ${error.message}"
            }
        }
        return Decoded(AutoOffRuleSet(rules), problems)
    }

    private fun parseRule(entry: Any?): AutoOffRule {
        val obj = entry as? Map<*, *> ?: throw IllegalArgumentException("not an object")
        val enabled = when (val flag = obj["enabled"]) {
            null -> true
            is Boolean -> flag
            else -> throw IllegalArgumentException("\"enabled\" is not a boolean")
        }
        return AutoOffRule(
            id = obj["id"] as? String ?: throw IllegalArgumentException("\"id\" missing or not a string"),
            output = OutputAddress(obj.int("module"), obj.int("relay")),
            delay = obj.long("delaySeconds").let {
                require(it in 0..Int.MAX_VALUE) { "\"delaySeconds\" out of range" }
                it.seconds
            },
            enabled = enabled,
        )
    }
}

/**
 * JSON form of the persisted timer deadlines ([DeadlineStore]):
 * `{"version":1,"deadlines":[{"module":1,"relay":15,"at":1760000000000}]}` with `at` = epoch milliseconds.
 * Reading never fails: whatever is unreadable is dropped (a lost deadline only means counting from observation).
 */
internal object DeadlinesJson {
    private const val VERSION = 1

    fun encode(deadlines: Map<OutputAddress, Instant>): String =
        MiniJson.stringify(
            mapOf(
                "version" to VERSION,
                "deadlines" to deadlines.map { (output, at) ->
                    mapOf("module" to output.module, "relay" to output.relay, "at" to at.toEpochMilliseconds())
                },
            ),
        )

    fun decode(text: String): Map<OutputAddress, Instant> {
        val document = try {
            MiniJson.parse(text) as? Map<*, *>
        } catch (_: JsonSyntaxException) {
            null
        } ?: return emptyMap()
        val entries = document["deadlines"] as? List<*> ?: return emptyMap()
        val result = LinkedHashMap<OutputAddress, Instant>()
        for (entry in entries) {
            try {
                val obj = entry as? Map<*, *> ?: continue
                result[OutputAddress(obj.int("module"), obj.int("relay"))] =
                    Instant.fromEpochMilliseconds(obj.long("at"))
            } catch (_: IllegalArgumentException) {
                // skip the entry
            }
        }
        return result
    }
}

private fun Map<*, *>.long(key: String): Long =
    this[key] as? Long ?: throw IllegalArgumentException("\"$key\" missing or not an integer")

private fun Map<*, *>.int(key: String): Int =
    long(key).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        ?: throw IllegalArgumentException("\"$key\" out of range")
