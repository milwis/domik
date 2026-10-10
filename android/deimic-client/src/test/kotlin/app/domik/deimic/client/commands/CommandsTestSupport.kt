@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.deimic.client.commands

import app.domik.deimic.client.Connection
import app.domik.deimic.client.FAKE_SERIAL
import app.domik.deimic.client.InMemoryTransport
import app.domik.deimic.client.RecordingFactory
import app.domik.deimic.client.TEST_ENDPOINT
import app.domik.deimic.client.ackFor
import app.domik.deimic.client.controllerFrame
import app.domik.deimic.client.pong
import app.domik.deimic.protocol.ByteReader
import app.domik.deimic.protocol.ByteWriter
import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.ShutterDirection
import app.domik.deimic.protocol.StateField
import app.domik.deimic.protocol.StateFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import java.io.File

/** Output event as sent by the controller: 0x109 = went on, 0x108 = went off. */
fun outputEvent(module: Int, relay: Int, on: Boolean): Frame =
    controllerFrame(
        if (on) CommandCode.OUTPUT_ROSE else CommandCode.OUTPUT_FELL,
        Bytes.of(relay),
        address = module.toUInt(),
    )

/** Shutter event 0x8B as sent by firmware 2017: `[number, direction]`, no position. */
fun shutterEvent(module: Int, outputNr: Int, direction: ShutterDirection): Frame =
    controllerFrame(
        CommandCode.SHUTTER_STATE,
        Bytes.of(outputNr, checkNotNull(direction.raw)),
        address = module.toUInt(),
    )

/** 0x6B response (17-byte payload, layout verified on a live controller). */
fun heaterStateFrame(module: Int, outputNr: Int, zone: FakeDevice.Zone): Frame {
    val writer = ByteWriter()
    writer.writeU8(outputNr.toUByte())
    writer.writeU8(if (zone.heating) 1u else 0u)
    writer.writeF32(zone.current)
    writer.writeZeros(4)
    writer.writeU8(zone.profile.raw)
    writer.writeU8(0u)
    writer.writeF32(zone.setpoint)
    writer.writeU8(0u)
    return controllerFrame(CommandCode.HEATER_STATE, writer.toBytes(), address = module.toUInt())
}

/** 0x21E collective state in the "new" format with one record per module in [outputs] (module -> ON relays). */
fun stateSnapshotFrame(outputs: Map<Int, Set<Int>>): Frame {
    val format = StateFormat.NEW
    val addressAt = checkNotNull(format.extent(StateField.ADDRESS)).offset
    val outputsAt = checkNotNull(format.extent(StateField.OUTPUTS)).offset
    val payload = outputs.entries.fold(Bytes.EMPTY) { acc, (module, on) ->
        val record = ByteWriter(zeroCount = format.recordSize)
        record.setI32(module, addressAt)
        on.forEach { record.setU8(1u, outputsAt + it) }
        acc + record.toBytes()
    }
    return controllerFrame(CommandCode.STATE_NEW, payload)
}

/**
 * Controller with state, answering like the real one: acknowledgement first, then the effect (output event, zone
 * state). The flags switch parts of that behaviour off to simulate the failure cases.
 */
class FakeDevice {
    class Zone(
        var profile: HeatingProfile = HeatingProfile.Comfort,
        var setpoint: Float = 21.0f,
        var current: Float = 20.5f,
        var heating: Boolean = false,
        /** When set, switching the zone to comfort makes this the reported setpoint (comfort keeps its own value). */
        var comfortSetpoint: Float? = null,
    )

    /** (module, relay) -> on. */
    val outputs = mutableMapOf<Pair<Int, Int>, Boolean>()

    /** (module, output_nr) -> zone; unknown zones are created on first access. */
    val zones = mutableMapOf<Pair<Int, Int>, Zone>()

    /** (module, shutter number) -> what the shutter is doing; shutters not listed stand. */
    val shutters = mutableMapOf<Pair<Int, Int>, ShutterDirection>()

    /** Frames the device received, in order (without keepalive pings). */
    val received = mutableListOf<Frame>()

    var sendAcks = true
    var sendOutputEvents = true

    /** Send the shutter event 0x8B after a shutter command. */
    var sendShutterEvents = true

    /** Send an event only when the output really changes (it is unknown whether the controller does). */
    var eventOnlyOnChange = false

    /** Send 0x6B by itself after 0x64 / 0x66 (verified for 0x64). */
    var sendAutomaticHeaterState = true

    /** The automatic 0x6B shows the state from *before* the write (a stale frame); explicit reads are right. */
    var automaticHeaterStateIsStale = false

    /** Answer 0x6B requests and state snapshot requests (they are acknowledged either way). */
    var answerReads = true

    /** Accept (acknowledge) writes but do not change anything. */
    var applyWrites = true

    fun zone(module: Int, outputNr: Int): Zone = zones.getOrPut(module to outputNr) { Zone() }

    fun handle(transport: InMemoryTransport, frame: Frame) {
        if (frame.command == CommandCode.PING) {
            transport.deliver(pong())
            return
        }
        received += frame
        if (sendAcks) transport.deliver(ackFor(frame))
        val module = frame.address.toInt()
        when (frame.command) {
            CommandCode.OUTPUT_ON, CommandCode.OUTPUT_OFF -> {
                val on = frame.command == CommandCode.OUTPUT_ON
                val relay = frame.payload[0].toInt()
                val before = outputs[module to relay] ?: false
                if (applyWrites) outputs[module to relay] = on
                if (sendOutputEvents && !(eventOnlyOnChange && before == on)) {
                    transport.deliver(outputEvent(module, relay, on))
                }
            }
            CommandCode.SET_SETPOINT, CommandCode.SET_PROFILE -> {
                val nr = frame.payload[0].toInt()
                val zone = zone(module, nr)
                val old = Zone(zone.profile, zone.setpoint, zone.current, zone.heating)
                if (applyWrites) {
                    if (frame.command == CommandCode.SET_SETPOINT) {
                        zone.setpoint = ByteReader(frame.payload).f32(1)
                    } else {
                        zone.profile = HeatingProfile.of(frame.payload[1])
                        if (zone.profile == HeatingProfile.Comfort) zone.comfortSetpoint?.let { zone.setpoint = it }
                    }
                }
                if (sendAutomaticHeaterState) {
                    transport.deliver(heaterStateFrame(module, nr, if (automaticHeaterStateIsStale) old else zone))
                }
            }
            CommandCode.SHUTTER_UP, CommandCode.SHUTTER_DOWN, CommandCode.SHUTTER_STOP -> {
                val nr = frame.payload[0].toInt()
                val before = shutters[module to nr] ?: ShutterDirection.STOPPED
                // Firmware 2017: a direction command during motion (same or opposite) only stops the shutter.
                val after = when {
                    frame.command == CommandCode.SHUTTER_STOP || before.isMoving -> ShutterDirection.STOPPED
                    frame.command == CommandCode.SHUTTER_UP -> ShutterDirection.MOVING_UP
                    else -> ShutterDirection.MOVING_DOWN
                }
                if (applyWrites) shutters[module to nr] = after
                if (sendShutterEvents) transport.deliver(shutterEvent(module, nr, if (applyWrites) after else before))
            }
            CommandCode.HEATER_STATE -> {
                val nr = frame.payload[0].toInt()
                if (answerReads) transport.deliver(heaterStateFrame(module, nr, zone(module, nr)))
            }
            CommandCode.GET_STATE -> {
                if (answerReads) {
                    val modules = outputs.keys.map { it.first }.ifEmpty { listOf(1) }.distinct()
                    transport.deliver(
                        stateSnapshotFrame(
                            modules.associateWith { m -> outputs.filter { it.key.first == m && it.value }.keys.map { it.second }.toSet() },
                        ),
                    )
                }
            }
        }
    }

    /** Commands that change state (everything except reads), in the order received. */
    val writes: List<Frame>
        get() = received.filter {
            it.command in setOf(
                CommandCode.OUTPUT_ON, CommandCode.OUTPUT_OFF, CommandCode.SET_SETPOINT, CommandCode.SET_PROFILE,
                CommandCode.SHUTTER_UP, CommandCode.SHUTTER_DOWN, CommandCode.SHUTTER_STOP,
            )
        }
}

/** Request vectors of `testdata/recorded_frames.json` ("requests": name -> frame hex), produced by an independent Python implementation. */
object RequestVectors {
    private val vectors: Map<String, String> by lazy {
        val dir = checkNotNull(System.getProperty("domik.testdata")) { "system property domik.testdata not set" }
        val text = File(dir, "recorded_frames.json").readText()
        val start = text.indexOf("\"requests\"")
        check(start >= 0) { "no \"requests\" in recorded_frames.json" }
        val section = text.substring(start, text.indexOf(']', start))
        Regex("""\{[^{}]*}""").findAll(section).associate { entry ->
            val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(entry.value)!!.groupValues[1]
            val frameHex = Regex(""""frameHex"\s*:\s*"([0-9a-f]+)"""").find(entry.value)!!.groupValues[1]
            name to frameHex
        }
    }

    operator fun get(name: String): String = checkNotNull(vectors[name]) { "no request vector $name" }
}

/** A [DeviceCommands] on a connection to an in-memory transport answered by [device]. */
class CommandsRig(
    val device: FakeDevice,
    val factory: RecordingFactory,
    val connection: Connection,
    val commands: DeviceCommands,
) {
    val transport get() = factory.transports.single()

    /** Everything the client wrote except keepalive pings. */
    fun sent(): List<Frame> = transport.sentFrames.filter { it.command != CommandCode.PING }

    fun sentCommands(): List<CommandCode> = sent().map { it.command }
}

/** Creates a [CommandsRig] and, unless [connect] is false, brings the connection to Ready. */
fun TestScope.commandsRig(
    device: FakeDevice = FakeDevice(),
    experimentalProfileSwitching: Boolean = false,
    config: DeviceCommandsConfig = DeviceCommandsConfig(),
    connect: Boolean = true,
): CommandsRig {
    val factory = RecordingFactory { frame -> device.handle(this, frame) }
    val connection = Connection(TEST_ENDPOINT, FAKE_SERIAL, backgroundScope, factory)
    if (connect) {
        connection.connect()
        runCurrent()
    }
    return CommandsRig(device, factory, connection, DeviceCommands(connection, experimentalProfileSwitching, config))
}
