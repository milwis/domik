package app.domik.deimic.client

import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ConfigChecksum
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ConnectionTesterTest {
    private val tlsInfo = TransportInfo("TLSv1.2", "TLS_RSA_WITH_AES_256_GCM_SHA384", CertificatePinning.CONTROLLER_CERTIFICATE_SHA1)

    private fun factory(
        controller: FakeController = FakeController(),
        onConnect: suspend () -> Unit = {},
        transports: MutableList<InMemoryTransport> = mutableListOf(),
    ) = FrameTransportFactory {
        InMemoryTransport(onConnect, tlsInfo) { frame -> controller.handle(this, frame) }.also { transports += it }
    }

    private fun statuses(report: ConnectionTestReport) = report.steps.map { it.step to it.status }

    @Test
    fun happyPath() = runTest {
        val transports = mutableListOf<InMemoryTransport>()
        val report = ConnectionTester(factory(transports = transports)).run(TEST_ENDPOINT, FAKE_SERIAL)

        assertTrue(report.succeeded)
        assertEquals(ConnectionTestStep.entries.map { it to StepStatus.PASSED }, statuses(report))
        assertEquals("TLSv1.2 TLS_RSA_WITH_AES_256_GCM_SHA384", report.steps[0].detail)
        assertEquals("Dec 13 2017", report.steps[2].detail)
        assertEquals("4d62", report.steps[3].detail)
        assertEquals("Dec 13 2017", report.firmwareVersion)
        assertEquals(ConfigChecksum(0x4d62u), report.configChecksum)
        assertEquals(tlsInfo, report.transportInfo)
        assertNull(report.failure)
        // Only read commands were sent, and the connection was closed afterwards.
        val sent = transports.single().sentFrames.map { it.command }.toSet()
        assertEquals(setOf(CommandCode.PING, CommandCode.FIRMWARE_VERSION, CommandCode.CONFIG_CHECKSUM), sent)
        assertTrue(transports.single().isClosed)
    }

    @ParameterizedTest
    @EnumSource(
        ConnectionProblem::class,
        names = ["HOST_UNREACHABLE", "CONNECTION_REFUSED", "TIMEOUT", "TLS_FAILED", "CERTIFICATE_MISMATCH", "OTHER"],
    )
    fun tlsStepFailure(problem: ConnectionProblem) = runTest {
        val transports = mutableListOf<InMemoryTransport>()
        val report = ConnectionTester(
            factory(onConnect = { throw ConnectionException(problem, "simulated") }, transports = transports),
        ).run(TEST_ENDPOINT, FAKE_SERIAL)

        assertFalse(report.succeeded)
        assertEquals(
            listOf(
                ConnectionTestStep.TLS to StepStatus.FAILED,
                ConnectionTestStep.PING to StepStatus.SKIPPED,
                ConnectionTestStep.FIRMWARE_VERSION to StepStatus.SKIPPED,
                ConnectionTestStep.CONFIG_CHECKSUM to StepStatus.SKIPPED,
            ),
            statuses(report),
        )
        assertEquals(problem, report.failure?.problem)
        assertEquals(1, transports.size) // no retries
    }

    @Test
    fun hangingConnectIsTimeout() = runTest {
        val report = ConnectionTester(factory(onConnect = { awaitCancellation() })).run(TEST_ENDPOINT, FAKE_SERIAL)
        assertEquals(ConnectionTestStep.TLS, report.failure?.step)
        assertEquals(ConnectionProblem.TIMEOUT, report.failure?.problem)
    }

    @Test
    fun silentControllerIsNoResponseAtPing() = runTest {
        // E.g. wrong serial number: TLS works, but nothing comes back.
        val controller = FakeController(sendAcks = false, respondToPing = false)
        val report = ConnectionTester(factory(controller)).run(TEST_ENDPOINT, FAKE_SERIAL)

        val failure = report.failure!!
        assertEquals(ConnectionTestStep.PING, failure.step)
        assertEquals(ConnectionProblem.NO_RESPONSE, failure.problem)
        assertEquals(false, failure.acknowledged)
        assertEquals(StepStatus.PASSED, report.steps[0].status)
        assertEquals(listOf(StepStatus.SKIPPED, StepStatus.SKIPPED), report.steps.drop(2).map { it.status })
    }

    @Test
    fun controllerClosingConnectionAtPingIsConnectionLost() = runTest {
        val report = ConnectionTester(
            FrameTransportFactory { InMemoryTransport(info = tlsInfo) { closeFromPeer() } },
        ).run(TEST_ENDPOINT, FAKE_SERIAL)
        assertEquals(ConnectionTestStep.PING, report.failure?.step)
        assertEquals(ConnectionProblem.CONNECTION_LOST, report.failure?.problem)
    }

    @Test
    fun firmwareStepFailure() = runTest {
        val controller = FakeController(silent = setOf(CommandCode.FIRMWARE_VERSION))
        val report = ConnectionTester(factory(controller)).run(TEST_ENDPOINT, FAKE_SERIAL)
        assertEquals(ConnectionTestStep.FIRMWARE_VERSION, report.failure?.step)
        assertEquals(ConnectionProblem.NO_RESPONSE, report.failure?.problem)
        assertEquals(StepStatus.SKIPPED, report.steps[3].status)
        assertNull(report.firmwareVersion)
    }

    @Test
    fun checksumStepFailure() = runTest {
        val controller = FakeController(silent = setOf(CommandCode.CONFIG_CHECKSUM))
        val report = ConnectionTester(factory(controller)).run(TEST_ENDPOINT, FAKE_SERIAL)
        assertEquals(ConnectionTestStep.CONFIG_CHECKSUM, report.failure?.step)
        assertEquals(ConnectionProblem.NO_RESPONSE, report.failure?.problem)
        assertEquals("Dec 13 2017", report.firmwareVersion)
        assertNull(report.configChecksum)
    }

    @Test
    fun malformedResponseIsOther() = runTest {
        val report = ConnectionTester(
            FrameTransportFactory {
                InMemoryTransport(info = tlsInfo) { frame ->
                    when (frame.command) {
                        CommandCode.CONFIG_CHECKSUM -> deliver(controllerFrame(CommandCode.CONFIG_CHECKSUM)) // empty payload
                        else -> FakeController().handle(this, frame)
                    }
                }
            },
        ).run(TEST_ENDPOINT, FAKE_SERIAL)
        assertEquals(ConnectionTestStep.CONFIG_CHECKSUM, report.failure?.step)
        assertEquals(ConnectionProblem.OTHER, report.failure?.problem)
    }
}
