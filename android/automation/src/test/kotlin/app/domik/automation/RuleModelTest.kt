package app.domik.automation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RuleModelTest {
    @Test
    fun `rule defaults to 30 minutes and enabled`() {
        val rule = AutoOffRule("r1", LIGHT_A)
        assertEquals(30.minutes, rule.delay)
        assertTrue(rule.enabled)
    }

    @Test
    fun `delay bounds are inclusive`() {
        AutoOffRule("a", LIGHT_A, AutoOffRule.MIN_DELAY)
        AutoOffRule("b", LIGHT_A, AutoOffRule.MAX_DELAY)
        assertThrows(IllegalArgumentException::class.java) { AutoOffRule("c", LIGHT_A, 59.seconds) }
        assertThrows(IllegalArgumentException::class.java) { AutoOffRule("d", LIGHT_A, 24.hours + 1.seconds) }
    }

    @Test
    fun `output is validated like the commands do`() {
        assertThrows(IllegalArgumentException::class.java) { AutoOffRule("a", OutputAddress(0, 1)) }
        assertThrows(IllegalArgumentException::class.java) { AutoOffRule("a", OutputAddress(0x1FF, 1)) }
        assertThrows(IllegalArgumentException::class.java) { AutoOffRule("a", OutputAddress(1, 128)) }
        assertThrows(IllegalArgumentException::class.java) { AutoOffRule("a", OutputAddress(1, -1)) }
        AutoOffRule("a", OutputAddress(1, 127))
        AutoOffRule("a", OutputAddress(1, 0))
    }

    @Test
    fun `id must not be blank or huge`() {
        assertThrows(IllegalArgumentException::class.java) { AutoOffRule("  ", LIGHT_A) }
        assertThrows(IllegalArgumentException::class.java) { AutoOffRule("", LIGHT_A) }
        assertThrows(IllegalArgumentException::class.java) { AutoOffRule("x".repeat(101), LIGHT_A) }
    }

    @Test
    fun `rule set rejects duplicate ids and a second rule for one output`() {
        assertThrows(IllegalArgumentException::class.java) { ruleSet(rule("a", LIGHT_A), rule("a", LIGHT_B)) }
        assertThrows(IllegalArgumentException::class.java) { ruleSet(rule("a", LIGHT_A), rule("b", LIGHT_A)) }
        // a disabled rule still occupies its output
        assertThrows(IllegalArgumentException::class.java) { ruleSet(rule("a", LIGHT_A, enabled = false), rule("b", LIGHT_A)) }
    }

    @Test
    fun `with replaces by id and without removes`() {
        val set = ruleSet(rule("a", LIGHT_A), rule("b", LIGHT_B))
        val changed = set.with(rule("a", LIGHT_A, delay = 10.minutes))
        assertEquals(listOf(10.minutes, 30.minutes), changed.rules.map { it.delay })
        assertEquals(3, changed.with(rule("c", LIGHT_C)).rules.size)
        assertEquals(listOf("b"), set.without("a").rules.map { it.id })
        assertEquals(set, set.without("zzz"))
        // moving a rule to an occupied output fails
        assertThrows(IllegalArgumentException::class.java) { set.with(rule("a", LIGHT_B)) }
    }

    @Test
    fun `rule set is a copy and compares by content`() {
        val list = mutableListOf(rule("a", LIGHT_A))
        val set = AutoOffRuleSet(list)
        list += rule("b", LIGHT_B)
        assertEquals(1, set.rules.size)
        assertEquals(ruleSet(rule("a", LIGHT_A)), set)
        assertEquals(ruleSet(rule("a", LIGHT_A)).hashCode(), set.hashCode())
    }

    @Test
    fun `forOutput finds disabled rules too`() {
        val set = ruleSet(rule("a", LIGHT_A, enabled = false))
        assertFalse(set.forOutput(LIGHT_A)!!.enabled)
        assertNull(set.forOutput(LIGHT_B))
    }

    @Test
    fun `retry delays double up to the maximum`() {
        val config = AutoOffConfig()
        assertEquals(5.seconds, config.retryDelay(1))
        assertEquals(10.seconds, config.retryDelay(2))
        assertEquals(20.seconds, config.retryDelay(3))
        assertEquals(40.seconds, config.retryDelay(4))
        assertEquals(80.seconds, config.retryDelay(5))
        assertEquals(2.minutes, config.retryDelay(6))
        assertEquals(2.minutes, config.retryDelay(500))
    }
}
