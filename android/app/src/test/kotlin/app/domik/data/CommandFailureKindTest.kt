package app.domik.data

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.HeatingProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommandFailureKindTest {
    private val heater = HeaterState(1u, 0u, false, 20f, HeatingProfile.Comfort, 21f)

    @Test
    fun `przyjete polecenia nie sa bledem`() {
        assertNull(CommandOutcome.Confirmed.failureKind())
        assertNull(CommandOutcome.AckOnly.failureKind())
        assertNull(CommandOutcome.SwitchedToComfort(heater).failureKind())
        assertNull(CommandOutcome.SwitchedToComfort(null).failureKind())
    }

    @Test
    fun `brak potwierdzenia i brak odpowiedzi to NO_RESPONSE`() {
        assertEquals(CommandFailureKind.NO_RESPONSE, CommandOutcome.NoAck.failureKind())
        assertEquals(
            CommandFailureKind.NO_RESPONSE,
            CommandOutcome.Failed(ConnectionProblem.NO_RESPONSE).failureKind(),
        )
    }

    @Test
    fun `problemy z polaczeniem to NOT_CONNECTED`() {
        listOf(ConnectionProblem.CONNECTION_LOST, ConnectionProblem.HOST_UNREACHABLE, ConnectionProblem.TIMEOUT, ConnectionProblem.OTHER)
            .forEach { assertEquals(CommandFailureKind.NOT_CONNECTED, CommandOutcome.Failed(it).failureKind(), it.name) }
    }

    @Test
    fun `inny stan po poleceniu to NOT_CONFIRMED`() {
        assertEquals(CommandFailureKind.NOT_CONFIRMED, CommandOutcome.OutputMismatch(true).failureKind())
        assertEquals(CommandFailureKind.NOT_CONFIRMED, CommandOutcome.HeaterMismatch(heater).failureKind())
    }

    @Test
    fun `odrzucone przed wyslaniem to REJECTED`() {
        assertEquals(CommandFailureKind.REJECTED, CommandOutcome.Invalid("x").failureKind())
        assertEquals(CommandFailureKind.REJECTED, CommandOutcome.NotEnabled.failureKind())
    }
}
