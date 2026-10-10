package app.domik.data

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.ConnectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ConnectionNoticeTest {
    @Test
    fun `bez problemu pasek jest ukryty`() {
        assertNull(ConnectionState.Idle.toNotice())
        assertNull(ConnectionState.Ready.toNotice())
        assertNull(ConnectionState.Connecting(1).toNotice())
    }

    @Test
    fun `oczekiwanie na ponowna probe pokazuje pasek bez przycisku`() {
        val state = ConnectionState.WaitingToReconnect(2, 5.seconds, ConnectionProblem.TIMEOUT)
        assertEquals(ConnectionNotice(ConnectionProblem.TIMEOUT, gaveUp = false), state.toNotice())
    }

    @Test
    fun `poddanie sie pokazuje pasek z ponowieniem`() {
        val state = ConnectionState.Failed(ConnectionProblem.CERTIFICATE_MISMATCH)
        assertEquals(ConnectionNotice(ConnectionProblem.CERTIFICATE_MISMATCH, gaveUp = true), state.toNotice())
    }
}
