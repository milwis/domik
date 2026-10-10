package app.domik.automation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class AutoOffRulesJsonTest {
    @Test
    fun `encodes in the documented layout`() {
        val json = AutoOffRulesJson.encode(ruleSet(rule("r1", OutputAddress(2, 3), 30.minutes), rule("r2", OutputAddress(2, 4), 90.minutes, enabled = false)))
        assertEquals(
            """{"version":1,"rules":[{"id":"r1","module":2,"relay":3,"delaySeconds":1800,"enabled":true},""" +
                """{"id":"r2","module":2,"relay":4,"delaySeconds":5400,"enabled":false}]}""",
            json,
        )
    }

    @Test
    fun `round trip keeps every rule and its order`() {
        val original = ruleSet(
            rule("550e8400-e29b-41d4-a716-446655440000", LIGHT_A, 30.minutes),
            rule("zażółć \"gęślą\" jaźń", LIGHT_B, 1.hours, enabled = false),
            rule("r3", LIGHT_C, AutoOffRule.MIN_DELAY),
        )
        val decoded = AutoOffRulesJson.decode(AutoOffRulesJson.encode(original))
        assertEquals(original, decoded.rules)
        assertTrue(decoded.problems.isEmpty())
    }

    @Test
    fun `empty set round trip`() {
        val decoded = AutoOffRulesJson.decode(AutoOffRulesJson.encode(AutoOffRuleSet.EMPTY))
        assertEquals(AutoOffRuleSet.EMPTY, decoded.rules)
    }

    @Test
    fun `unknown fields are ignored and enabled defaults to true`() {
        val decoded = AutoOffRulesJson.decode(
            """{"version":1,"future":{"x":1},"rules":[{"id":"a","module":2,"relay":3,"delaySeconds":600,"color":"red"}]}""",
        )
        assertEquals(listOf(rule("a", LIGHT_A, 10.minutes)), decoded.rules.rules)
    }

    @Test
    fun `missing version is accepted`() {
        val decoded = AutoOffRulesJson.decode("""{"rules":[]}""")
        assertTrue(decoded.rules.rules.isEmpty())
    }

    @Test
    fun `broken rules are skipped one by one and reported`() {
        val decoded = AutoOffRulesJson.decode(
            """{"version":1,"rules":[
              {"id":"good1","module":2,"relay":3,"delaySeconds":600},
              {"id":"bad-module","module":0,"relay":3,"delaySeconds":600},
              {"id":"bad-relay","module":2,"relay":500,"delaySeconds":600},
              {"id":"bad-delay","module":2,"relay":5,"delaySeconds":5},
              {"id":"neg-delay","module":2,"relay":5,"delaySeconds":-5},
              {"id":"no-delay","module":2,"relay":5},
              {"id":"frac","module":2,"relay":5,"delaySeconds":60.5},
              {"module":2,"relay":6,"delaySeconds":600},
              {"id":"dup-output","module":2,"relay":3,"delaySeconds":600},
              {"id":"good1","module":2,"relay":7,"delaySeconds":600},
              {"id":"bad-flag","module":2,"relay":8,"delaySeconds":600,"enabled":"yes"},
              "not an object",
              {"id":"good2","module":3,"relay":9,"delaySeconds":3600,"enabled":false}
            ]}""",
        )
        assertEquals(listOf("good1", "good2"), decoded.rules.rules.map { it.id })
        assertEquals(11, decoded.problems.size)
        assertTrue(decoded.problems.all { it.startsWith("rules[") })
        assertFalse(decoded.rules.rules.last().enabled)
    }

    @Test
    fun `unreadable documents throw a format exception`() {
        listOf(
            "", "not json", "[]", "null", "{}", """{"rules":"x"}""", """{"version":2,"rules":[]}""", """{"version":0,"rules":[]}""",
            """{"version":"1","rules":[]}""", """{"version":1,"rules":[""",
        ).forEach { text ->
            assertThrows(AutoOffRulesFormatException::class.java, { AutoOffRulesJson.decode(text) }, "should fail: <$text>")
        }
    }

    // Deadlines

    private val at = Instant.fromEpochMilliseconds(1_800_000_123_456)

    @Test
    fun `deadlines round trip`() {
        val deadlines = mapOf(LIGHT_A to at, LIGHT_C to Instant.fromEpochMilliseconds(5))
        assertEquals(deadlines, DeadlinesJson.decode(DeadlinesJson.encode(deadlines)))
        assertEquals(
            """{"version":1,"deadlines":[{"module":2,"relay":3,"at":1800000123456}]}""",
            DeadlinesJson.encode(mapOf(LIGHT_A to at)),
        )
    }

    @Test
    fun `deadlines reading never fails`() {
        assertEquals(emptyMap<OutputAddress, Instant>(), DeadlinesJson.decode(""))
        assertEquals(emptyMap<OutputAddress, Instant>(), DeadlinesJson.decode("garbage"))
        assertEquals(emptyMap<OutputAddress, Instant>(), DeadlinesJson.decode("[1]"))
        assertEquals(emptyMap<OutputAddress, Instant>(), DeadlinesJson.decode("""{"deadlines":5}"""))
        assertEquals(
            mapOf(LIGHT_A to at),
            DeadlinesJson.decode(
                """{"deadlines":[{"module":2,"relay":3,"at":1800000123456},{"module":"x"},7,{"module":1,"relay":2},
                   {"module":99999999999,"relay":1,"at":1}]}""",
            ),
        )
    }

    @Test
    fun `file store saves atomically and reads back`(@TempDir dir: File) = runTest {
        val file = File(dir, "state/deadlines.json")
        val store = FileDeadlineStore(file)
        assertEquals(emptyMap<OutputAddress, Instant>(), store.load())

        store.save(mapOf(LIGHT_A to at))
        assertEquals(mapOf(LIGHT_A to at), store.load())
        assertEquals(mapOf(LIGHT_A to at), FileDeadlineStore(file).load())

        store.save(emptyMap())
        assertEquals(emptyMap<OutputAddress, Instant>(), store.load())
        assertEquals(listOf("deadlines.json"), file.parentFile.list()!!.toList())
    }

    @Test
    fun `file store treats a damaged file as empty`(@TempDir dir: File) = runTest {
        val file = File(dir, "deadlines.json")
        file.writeText("{\"deadlines\": [{\"mod")
        assertEquals(emptyMap<OutputAddress, Instant>(), FileDeadlineStore(file).load())
        file.writeBytes(byteArrayOf(0x00, 0x7F, -1, -2))
        assertEquals(emptyMap<OutputAddress, Instant>(), FileDeadlineStore(file).load())
    }
}
