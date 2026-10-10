package app.domik.data

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.ConnectionTestReport
import app.domik.deimic.client.ConnectionTestStep
import app.domik.deimic.client.StepResult
import app.domik.deimic.client.StepStatus
import app.domik.deimic.protocol.ConfigChecksum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionTestOutcomeTest {
    private fun passed(vararg steps: ConnectionTestStep) = steps.map { StepResult(it, StepStatus.PASSED) }

    private fun skipped(vararg steps: ConnectionTestStep) = steps.map { StepResult(it, StepStatus.SKIPPED) }

    @Test
    fun `udany test niesie wersje i sume kontrolna`() {
        val report = ConnectionTestReport(
            steps = passed(*ConnectionTestStep.entries.toTypedArray()),
            firmwareVersion = "Dec 13 2017",
            configChecksum = ConfigChecksum(0x4D62u),
        )
        assertEquals(ConnectionTestOutcome.Success("Dec 13 2017", "4d62"), report.toOutcome())
    }

    @Test
    fun `problemy warstwy TLS dostaja wlasne powody`() {
        val expected = mapOf(
            ConnectionProblem.HOST_UNREACHABLE to ConnectionTestFailure.HOST_UNREACHABLE,
            ConnectionProblem.CONNECTION_REFUSED to ConnectionTestFailure.CONNECTION_REFUSED,
            ConnectionProblem.TIMEOUT to ConnectionTestFailure.TIMEOUT,
            ConnectionProblem.CERTIFICATE_MISMATCH to ConnectionTestFailure.CERTIFICATE_MISMATCH,
            ConnectionProblem.TLS_FAILED to ConnectionTestFailure.TLS_FAILED,
        )
        expected.forEach { (problem, reason) ->
            val report = ConnectionTestReport(
                listOf(StepResult(ConnectionTestStep.TLS, StepStatus.FAILED, problem)) +
                    skipped(ConnectionTestStep.PING, ConnectionTestStep.FIRMWARE_VERSION, ConnectionTestStep.CONFIG_CHECKSUM),
            )
            assertEquals(ConnectionTestOutcome.Failure(reason), report.toOutcome(), problem.name)
        }
    }

    @Test
    fun `brak odpowiedzi na ping to prawdopodobnie zly numer seryjny`() {
        val report = ConnectionTestReport(
            passed(ConnectionTestStep.TLS) +
                StepResult(ConnectionTestStep.PING, StepStatus.FAILED, ConnectionProblem.NO_RESPONSE, acknowledged = false) +
                skipped(ConnectionTestStep.FIRMWARE_VERSION, ConnectionTestStep.CONFIG_CHECKSUM),
        )
        assertEquals(ConnectionTestOutcome.Failure(ConnectionTestFailure.NO_RESPONSE), report.toOutcome())
    }

    @Test
    fun `brak odpowiedzi w kroku TLS to przekroczenie czasu`() {
        val report = ConnectionTestReport(
            listOf(StepResult(ConnectionTestStep.TLS, StepStatus.FAILED, ConnectionProblem.NO_RESPONSE)) +
                skipped(ConnectionTestStep.PING, ConnectionTestStep.FIRMWARE_VERSION, ConnectionTestStep.CONFIG_CHECKSUM),
        )
        assertEquals(ConnectionTestOutcome.Failure(ConnectionTestFailure.TIMEOUT), report.toOutcome())
    }

    @Test
    fun `zerwane polaczenie i nieznane problemy`() {
        fun failureAt(step: ConnectionTestStep, problem: ConnectionProblem?) = ConnectionTestReport(
            listOf(StepResult(step, StepStatus.FAILED, problem)),
        ).toOutcome()
        assertEquals(
            ConnectionTestOutcome.Failure(ConnectionTestFailure.CONNECTION_LOST),
            failureAt(ConnectionTestStep.FIRMWARE_VERSION, ConnectionProblem.CONNECTION_LOST),
        )
        assertEquals(
            ConnectionTestOutcome.Failure(ConnectionTestFailure.CONNECTION_LOST),
            failureAt(ConnectionTestStep.CONFIG_CHECKSUM, ConnectionProblem.DECODER_ERROR),
        )
        assertEquals(
            ConnectionTestOutcome.Failure(ConnectionTestFailure.OTHER),
            failureAt(ConnectionTestStep.PING, ConnectionProblem.OTHER),
        )
        assertEquals(ConnectionTestOutcome.Failure(ConnectionTestFailure.OTHER), failureAt(ConnectionTestStep.PING, null))
    }
}
