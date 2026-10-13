package app.domik.demo

import app.domik.deimic.client.ConnectionException
import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.ControllerEndpoint
import app.domik.deimic.client.ControllerProfile
import app.domik.deimic.client.FrameTransport
import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.client.InMemoryTransport
import app.domik.deimic.client.TransportInfo
import app.domik.deimic.protocol.ByteReader
import app.domik.deimic.protocol.ByteWriter
import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.FrameAddress
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.InputCalibration
import app.domik.deimic.protocol.InputCalibrationWritten
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.SerialNumber
import app.domik.deimic.protocol.ShutterDirection
import app.domik.deimic.protocol.StateField
import app.domik.deimic.protocol.StateFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * How the simulated network and controller respond: every request is answered after a latency drawn from
 * [minLatency]..[maxLatency] with a generator seeded by [seed], so a run is repeatable.
 */
data class DemoNetwork(
    val minLatency: Duration = 50.milliseconds,
    val maxLatency: Duration = 150.milliseconds,
    /** Time to "open the TLS connection". */
    val connectDelay: Duration = 300.milliseconds,
    val seed: Long = 20261010L,
    /** The configuration database is delivered in pieces of this size, like TCP segments (0 = in one piece). */
    val downloadChunkBytes: Int = 1460,
) {
    init {
        require(!minLatency.isNegative() && maxLatency >= minLatency) { "invalid latency range" }
        require(!connectDelay.isNegative()) { "connectDelay must not be negative" }
        require(downloadChunkBytes >= 0) { "downloadChunkBytes must not be negative" }
    }

    companion object {
        /** No delays at all (answers still arrive asynchronously, through the coroutine scope). */
        val Instant: DemoNetwork = DemoNetwork(Duration.ZERO, Duration.ZERO, Duration.ZERO)
    }
}

/**
 * A controller simulated in memory, for demo mode, previews and tests. It speaks the real protocol: the app
 * connects with an ordinary `Connection` whose [transportFactory] is this controller's, and everything above that
 * (keepalive, requests, `DeviceCommands`, `ConfigStore`) runs unchanged.
 *
 * Behaviour (the same frames the real controller sends):
 * - ping (0x1CE) -> pong; every other command is acknowledged first (0x1E0 echo of the request);
 * - firmware version (0x1D6) and configuration checksum (0x1C8) of the fictional house;
 * - file download (0x212) of [DemoDatabase.PATH]: the synthetic SQLite database of [home];
 * - collective state (0x202 `[01]` -> 0x21E, `[]` -> 0x202 v2, `[02]` -> empty like the real firmware);
 * - zone state (0x6B), temperature readout (0x72 to broadcast -> 0x72 + 0x130 per sensor);
 * - output ON/OFF (0x104 / 0x103) -> event 0x109 / 0x108 to every connected client, **only when the relay really
 *   changes** (switching an output that already is in that state is just acknowledged, like the real controller);
 * - setpoint (0x64) and profile (0x66) -> the new zone state (0x6B) to every connected client;
 * - shutter up / down / stop (0x8D / 0x8E / 0x82, `[output_nr]`) -> shutter event 0x8B `[output_nr, direction]` (no
 *   position) to every connected client. Like the real firmware, a direction command during motion – the same or the
 *   opposite one – only stops the shutter. A moving shutter arrives by itself after [DemoSimulation.shutterTravel]
 *   and reports "at top" / "at bottom" (4 / 5). The collective state never carries shutter states (they stay 0).
 * - input calibration: read (0x131 -> 0x131 with the 12 records of the module, 216 B) and write (0x132
 *   `[input_nr][18 B]` -> the same payload echoed in a 0x132 to the requesting client, then the sensor of that input
 *   reports its value through the new calibration: 0x72 + 0x130 and, for a zone's sensor, 0x6B, to every connected
 *   client). A write for an unknown module, an input above 11 or a non-finite active point is only acknowledged.
 *   The sensor of an input is the one wired to it ([DemoZone.inputNr] for a zone, whose number differs from the
 *   zone's `output_nr`; the room link is in the `inputs` table of the demo database). Initially every sensor has
 *   a one-point calibration that changes no reported value (see [calibration]).
 * Unknown commands, modules, zones and out-of-range values are acknowledged and otherwise ignored.
 *
 * While [simulation] is enabled the house keeps living (started by the first connection): heating zones warm up
 * towards their setpoint, cool down otherwise, and sensors send temperature reports (0x72) when a value moved.
 *
 * Responses are scheduled on [scope] with delays from [network]; with a test scope's virtual time everything is
 * deterministic. Responses are delivered in the order of the requests (like a TCP stream). Thread-safe.
 */
class FakeController(
    private val scope: CoroutineScope,
    val home: DemoHome = DemoHome.standard(),
    val serial: SerialNumber = DemoHome.SERIAL,
    val network: DemoNetwork = DemoNetwork(),
    val simulation: DemoSimulation = DemoSimulation(),
) {
    /** Address to give to `Connection` (a name that never resolves – nothing real is contacted). */
    val endpoint: ControllerEndpoint = ControllerEndpoint(HOST)

    /** The controller profile of demo mode. */
    val profile: ControllerProfile = ControllerProfile(id = PROFILE_ID, name = home.name, host = HOST)

    /** The configuration database served for command 0x212. */
    val database: DemoDatabase = DemoDatabase.build(home)

    /** Creates a transport connected to this controller for each connection attempt of a `Connection`. */
    val transportFactory: FrameTransportFactory = FrameTransportFactory { createTransport() }

    private val lock = Any()
    private val model = HouseModel(home, simulation)
    private val random = Random(network.seed)
    private val connections = mutableListOf<InMemoryTransport>()
    private var lastResponse: Job? = null
    private var ticker: Job? = null
    private val shutterRuns = mutableMapOf<Pair<Int, Int>, Job>()

    /** When false, connection attempts fail like an unreachable host (to try out the offline UI). */
    @Volatile
    var isReachable: Boolean = true

    // Inspection

    /** True when the relay is on. */
    fun isOutputOn(module: Int, relay: Int): Boolean = synchronized(lock) { model.isOn(module, relay) }

    /** Current state of a zone, or null when there is no such zone. */
    fun heaterState(module: Int, outputNr: Int): HeaterState? = synchronized(lock) { model.zone(module, outputNr)?.state() }

    /**
     * Value the controller currently reports for the sensor on analog input [sensor] (0x72 / 0x130 numbering; for a
     * zone's sensor see [DemoZone.inputNr] – it is not the zone's `output_nr`), or null when there is no such sensor.
     */
    fun temperature(module: Int, sensor: Int): Float? = synchronized(lock) { model.sensor(module, sensor)?.temperature }

    /**
     * The true temperature of the room of heating zone ([module], [outputNr]) – what an independent thermometer in
     * the room would show, for simulated room thermometers. It follows the simulation but ignores the input's
     * calibration: writing a calibration (0x132) moves [temperature] and the zone's reported temperature, not this
     * value. Null when there is no such zone.
     */
    fun roomTemperature(module: Int, outputNr: Int): Float? =
        synchronized(lock) { model.zone(module, outputNr)?.let(model::roomTemperature) }

    /** Calibration record of an analog input (0..11), or null for an unknown module or input. */
    fun calibration(module: Int, input: Int): InputCalibration? =
        synchronized(lock) { model.calibrations(module)?.getOrNull(input) }

    // Things that happen without a command from the app

    /**
     * Someone presses a wall switch: changes the relay and announces it to all clients (0x109 / 0x108) when the
     * state really changed. Returns false when the module or relay does not exist.
     */
    fun switchOutputExternally(module: Int, relay: Int, on: Boolean): Boolean = synchronized(lock) {
        changeOutput(module, relay, on)
    }

    /** What the shutter is doing now, or null when there is no such shutter. */
    fun shutterDirection(module: Int, outputNr: Int): ShutterDirection? =
        synchronized(lock) { model.shutter(module, outputNr)?.direction }

    /**
     * Someone presses [button] on a wall switch (or uses another app): the shutter reacts exactly as to a command
     * and the controller announces it to all clients (0x8B). Returns false when there is no such shutter.
     */
    fun pressShutterExternally(module: Int, outputNr: Int, button: DemoShutterButton): Boolean = synchronized(lock) {
        val shutter = model.shutter(module, outputNr) ?: return false
        pressShutter(shutter, button)
        true
    }

    /** Forces the measured temperature of a zone (for example to show the heating switching on). */
    fun setZoneTemperature(module: Int, outputNr: Int, celsius: Float): Boolean = synchronized(lock) {
        val zone = model.zone(module, outputNr) ?: return false
        model.setCurrent(zone, celsius)
        true
    }

    /**
     * Moves the house forward by [simulated] time right now and sends the temperature reports that result. This
     * is what the background simulation calls every tick; tests call it directly with
     * [DemoSimulation.Manual].
     */
    fun advance(simulated: Duration) {
        require(!simulated.isNegative()) { "cannot go back in time" }
        synchronized(lock) {
            model.advance(simulated.inWholeNanoseconds / NANOS_PER_SECOND)
            broadcast(*model.takeChangedSensors().flatMap(::temperatureFrames).toTypedArray())
        }
    }

    /** Starts the background simulation (done by the first connection). No-op when disabled or running. */
    fun start() {
        synchronized(lock) {
            if (!simulation.enabled || ticker?.isActive == true) return
            ticker = scope.launch {
                while (true) {
                    delay(simulation.tickInterval)
                    advance(simulation.tickInterval * simulation.timeScale)
                }
            }
        }
    }

    /** Stops the background simulation; connections stay open. */
    fun stop() {
        synchronized(lock) {
            ticker?.cancel()
            ticker = null
        }
    }

    /** The controller closes every open connection (the client reconnects with its backoff). */
    fun dropConnections() {
        val open = synchronized(lock) { connections.filterNot { it.isClosed } }
        open.forEach { it.closeFromPeer() }
    }

    // Transport

    private fun createTransport(): FrameTransport {
        val transport = InMemoryTransport(
            onConnect = { acceptConnection() },
            info = TransportInfo(protocol = "demo", cipherSuite = "none", certificateSha1 = "demo"),
            recordSentFrames = false,
            onFrame = { frame -> enqueue(this, frame) },
        )
        synchronized(lock) {
            connections.removeAll { it.isClosed }
            connections += transport
        }
        return transport
    }

    private suspend fun acceptConnection() {
        delay(network.connectDelay)
        if (!isReachable) throw ConnectionException(ConnectionProblem.HOST_UNREACHABLE, "the demo controller is switched off")
        start()
    }

    /** Called synchronously for every frame the client writes: schedules the answer, keeping request order. */
    private fun enqueue(transport: InMemoryTransport, request: Frame) {
        synchronized(lock) {
            val previous = lastResponse
            val latency = nextLatency()
            lastResponse = scope.launch {
                delay(latency)
                previous?.join()
                synchronized(lock) { respond(transport, request) }
            }
        }
    }

    private fun nextLatency(): Duration {
        val spread = (network.maxLatency - network.minLatency).inWholeNanoseconds
        return network.minLatency + (spread * random.nextDouble()).toLong().nanoseconds
    }

    // Protocol (all of the following runs under `lock`)

    private fun respond(transport: InMemoryTransport, request: Frame) {
        if (transport.isClosed) return
        if (request.command == CommandCode.PING) {
            transport.deliver(frame(CommandCode.PING))
            return
        }
        transport.deliver(acknowledgement(request))
        when (request.command) {
            CommandCode.FIRMWARE_VERSION -> transport.deliver(firmwareFrame())
            CommandCode.CONFIG_CHECKSUM -> transport.deliver(
                frame(CommandCode.CONFIG_CHECKSUM, Bytes.of(database.checksum.toInt() and 0xFF, database.checksum.toInt() shr 8)),
            )
            CommandCode.FILE_DOWNLOAD -> download(transport, request)
            CommandCode.GET_STATE -> stateSnapshot(transport, request)
            CommandCode.HEATER_STATE -> zoneOf(request)?.let { transport.deliver(heaterFrame(it)) }
            CommandCode.TEMPERATURE_REPORT -> {
                model.sensors.forEach { transport.deliver(*temperatureFrames(it).toTypedArray()) }
                model.markAllReported()
            }
            CommandCode.OUTPUT_ON, CommandCode.OUTPUT_OFF -> switchOutput(request)
            CommandCode.SET_SETPOINT -> setSetpoint(request)
            CommandCode.SET_PROFILE -> setProfile(request)
            CommandCode.INPUT_CALIBRATION_GET -> model.calibrations(request.address.toInt())?.let { records ->
                val payload = records.fold(Bytes.EMPTY) { acc, record -> acc + record.encode() }
                transport.deliver(frame(CommandCode.INPUT_CALIBRATION_GET, payload, request.address))
            }
            CommandCode.INPUT_CALIBRATION_SET -> setCalibration(transport, request)
            CommandCode.SHUTTER_UP -> shutterCommand(request, DemoShutterButton.UP)
            CommandCode.SHUTTER_DOWN -> shutterCommand(request, DemoShutterButton.DOWN)
            CommandCode.SHUTTER_STOP -> shutterCommand(request, DemoShutterButton.STOP)
            else -> Unit // acknowledged only
        }
    }

    private fun switchOutput(request: Frame) {
        if (request.payload.size < 1) return
        val module = request.address.toInt()
        val relay = request.payload[0].toInt()
        changeOutput(module, relay, request.command == CommandCode.OUTPUT_ON)
    }

    /**
     * Sets the relay and, only if its state actually changed, announces it to all clients. Returns false for an
     * unknown module or relay. The real controller answers a command for an output already in that state with
     * the acknowledgement alone (verified on a real controller).
     */
    private fun changeOutput(module: Int, relay: Int, on: Boolean): Boolean {
        val changed = model.isOn(module, relay) != on
        if (!model.setOn(module, relay, on)) return false
        if (changed) broadcast(outputEvent(module, relay, on))
        return true
    }

    private fun setSetpoint(request: Frame) {
        if (request.payload.size < 5) return
        val zone = zoneOf(request) ?: return
        val celsius = ByteReader(request.payload).f32(1)
        if (!celsius.isFinite() || celsius < MIN_SETPOINT || celsius > MAX_SETPOINT) return
        model.setSetpoint(zone, celsius)
        broadcast(heaterFrame(zone))
    }

    private fun setProfile(request: Frame) {
        if (request.payload.size < 2) return
        val zone = zoneOf(request) ?: return
        val profile = HeatingProfile.of(request.payload[1])
        if (profile is HeatingProfile.Unknown) return
        model.setProfile(zone, profile)
        broadcast(heaterFrame(zone))
    }

    /** 0x132 `[input_nr][18 B]`: confirms to the requester, then reports the sensor's recalculated value to everyone. */
    private fun setCalibration(transport: InMemoryTransport, request: Frame) {
        if (request.payload.size < InputCalibrationWritten.LENGTH) return
        val module = request.address.toInt()
        val input = request.payload[0].toInt()
        val record = InputCalibration.decode(ByteReader(request.payload), 1)
        if (!model.setCalibration(module, input, record)) return
        transport.deliver(frame(CommandCode.INPUT_CALIBRATION_SET, request.payload.copyOfRange(0, InputCalibrationWritten.LENGTH), request.address))
        val sensor = model.sensor(module, input) ?: return
        val zone = sensor.zone // the zone whose sensor sits on that input - NOT the zone numbered like the input
        sensor.reported = sensor.temperature
        broadcast(*(temperatureFrames(sensor) + listOfNotNull(zone?.let(::heaterFrame))).toTypedArray())
    }

    /** 0x8D / 0x8E / 0x82 `[output_nr]`; a shutter that does not exist is only acknowledged. */
    private fun shutterCommand(request: Frame, button: DemoShutterButton) {
        if (request.payload.size < 1) return
        val shutter = model.shutter(request.address.toInt(), request.payload[0].toInt()) ?: return
        pressShutter(shutter, button)
    }

    /**
     * Applies [button], announces the result and, when the shutter now moves, schedules its arrival at the end
     * position. A new press replaces the pending arrival of the previous run.
     */
    private fun pressShutter(shutter: HouseModel.Shutter, button: DemoShutterButton) {
        val key = shutter.module to shutter.outputNr
        shutterRuns.remove(key)?.cancel()
        val direction = model.press(shutter, button)
        broadcast(shutterEvent(shutter, direction))
        if (!direction.isMoving) return
        val run = scope.launch {
            delay(simulation.shutterTravel)
            synchronized(lock) {
                // A press that came while this run was waiting for the lock has replaced it.
                if (shutterRuns[key] !== coroutineContext[Job]) return@synchronized
                shutterRuns.remove(key)
                model.arrive(shutter)?.let { broadcast(shutterEvent(shutter, it)) }
            }
        }
        shutterRuns[key] = run
    }

    private fun zoneOf(request: Frame): HouseModel.Zone? =
        if (request.payload.size < 1) null else model.zone(request.address.toInt(), request.payload[0].toInt())

    private fun download(transport: InMemoryTransport, request: Frame) {
        val path = StringBuilder()
        for (index in 0 until request.payload.size) {
            val byte = request.payload[index].toInt()
            if (byte == 0) break
            path.append(byte.toChar())
        }
        if (path.toString() != DemoDatabase.PATH) return // no such file: acknowledged only
        val payload = ByteWriter()
        payload.writeBytes(Bytes.ascii(DemoDatabase.PATH))
        payload.writeZeros(Request.FILE_PATH_LENGTH - DemoDatabase.PATH.length)
        payload.writeBytes(database.bytes())
        val bytes = frame(CommandCode.FILE_DOWNLOAD, payload.toBytes(), flags = 1u).encode().toByteArray()
        val chunk = network.downloadChunkBytes
        if (chunk <= 0 || chunk >= bytes.size) {
            transport.deliver(bytes)
        } else {
            for (start in bytes.indices step chunk) {
                transport.deliver(bytes.copyOfRange(start, minOf(bytes.size, start + chunk)))
            }
        }
    }

    private fun stateSnapshot(transport: InMemoryTransport, request: Frame) {
        when {
            request.payload.isEmpty() -> transport.deliver(stateFrame(CommandCode.GET_STATE, StateFormat.V2))
            request.payload.size == 1 && request.payload[0].toInt() == 1 ->
                transport.deliver(stateFrame(CommandCode.STATE_NEW, StateFormat.NEW))
            // The real firmware ("Dec 13 2017") answers the v3 request with an empty 0x202.
            request.payload.size == 1 && request.payload[0].toInt() == 2 -> transport.deliver(frame(CommandCode.GET_STATE))
        }
    }

    /** Delivers to every open connection (the controller reports changes to all its clients). */
    private fun broadcast(vararg frames: Frame) {
        if (frames.isEmpty()) return
        connections.removeAll { it.isClosed }
        connections.filter { it.isConnected }.forEach { it.deliver(*frames) }
    }

    // Frames

    private fun frame(
        command: CommandCode,
        payload: Bytes = Bytes.EMPTY,
        address: UInt = FrameAddress.BROADCAST,
        group: UInt = 0u,
        flags: UInt = 0u,
    ): Frame = Frame(serial, address, command, group, flags, payload)

    /** 0x1E0: echo of the request header and payload. */
    private fun acknowledgement(request: Frame): Frame {
        val echo = ByteWriter()
        echo.writeU32(request.address)
        echo.writeU32(request.command.raw)
        echo.writeU32(request.group)
        echo.writeU32(request.flags)
        echo.writeU32(request.payload.size.toUInt())
        echo.writeBytes(request.payload)
        return frame(CommandCode.ACK, echo.toBytes())
    }

    private fun firmwareFrame(): Frame {
        val text = FIRMWARE_VERSION
        val payload = ByteWriter()
        payload.writeBytes(Bytes.ascii(text))
        payload.writeZeros(FIRMWARE_FIELD_LENGTH - text.length)
        return frame(CommandCode.FIRMWARE_VERSION, payload.toBytes())
    }

    /** 0x109 (went on) / 0x108 (went off) `[output_relay]`. */
    private fun outputEvent(module: Int, relay: Int, on: Boolean): Frame = frame(
        if (on) CommandCode.OUTPUT_ROSE else CommandCode.OUTPUT_FELL,
        Bytes.of(relay),
        address = module.toUInt(),
        flags = 5u,
    )

    /** 0x8B `[output_nr][direction]` (2 bytes, no position), flags 5 like the other events. */
    private fun shutterEvent(shutter: HouseModel.Shutter, direction: ShutterDirection): Frame = frame(
        CommandCode.SHUTTER_STATE,
        Bytes.of(shutter.outputNr, checkNotNull(direction.raw)),
        address = shutter.module.toUInt(),
        flags = 5u,
    )

    /** 0x6B, 17 bytes: `[nr][heating][f32 current][4 zeros][profile][0][f32 setpoint][0]`. */
    private fun heaterFrame(zone: HouseModel.Zone): Frame {
        val payload = ByteWriter()
        payload.writeU8(zone.outputNr.toUByte())
        payload.writeU8(if (zone.heating) 1u else 0u)
        payload.writeF32(zone.current)
        payload.writeZeros(4)
        payload.writeU8(zone.profile.raw)
        payload.writeU8(0u)
        payload.writeF32(zone.setpoint)
        payload.writeU8(0u)
        return frame(CommandCode.HEATER_STATE, payload.toBytes(), address = zone.module.toUInt(), group = 1u, flags = 1u)
    }

    /** 0x72 `[f32 °C][level][room][0][0xFF][0]` followed by 0x130 `[sensor][f32 °C]`, as the real controller sends them. */
    private fun temperatureFrames(sensor: HouseModel.Sensor): List<Frame> {
        val report = ByteWriter()
        report.writeF32(sensor.temperature)
        report.writeU8(sensor.levelId.toUByte())
        report.writeU8(sensor.roomId.toUByte())
        report.writeU8(0u)
        report.writeU8(0xFFu)
        report.writeU8(0u)
        val analog = ByteWriter()
        analog.writeU8(sensor.number.toUByte())
        analog.writeF32(sensor.temperature)
        val address = sensor.module.toUInt()
        return listOf(
            frame(CommandCode.TEMPERATURE_REPORT, report.toBytes(), address, group = 1u),
            frame(CommandCode.ANALOG_VALUE_CHANGED, analog.toBytes(), address, group = 1u, flags = 5u),
        )
    }

    private fun stateFrame(command: CommandCode, format: StateFormat): Frame {
        val payload = model.modules.fold(Bytes.EMPTY) { acc, module -> acc + stateRecord(format, module) }
        return frame(command, payload)
    }

    /** One module's record of the collective state ([format] is v2 or "new"). */
    private fun stateRecord(format: StateFormat, module: Int): Bytes {
        val record = ByteWriter(zeroCount = format.recordSize)
        val outputsAt = checkNotNull(format.extent(StateField.OUTPUTS)).offset
        for (relay in 0 until HouseModel.OUTPUTS_PER_MODULE) {
            if (model.isOn(module, relay)) record.setU8(1u, outputsAt + relay)
        }
        record.setI32(module, checkNotNull(format.extent(StateField.ADDRESS)).offset)
        val temperaturesAt = checkNotNull(format.extent(StateField.TEMPERATURES)).offset
        model.sensorsOf(module).forEach { record.setF32(it.temperature, temperaturesAt + 4 * it.number) }
        format.extent(StateField.HEATERS)?.let { heaters ->
            for (nr in 0 until DemoHome.MAX_ZONE + 1) record.setU8(NO_ZONE, heaters.offset + nr)
            model.zones.filter { it.module == module }.forEach {
                record.setU8(if (it.heating) 1u else 0u, heaters.offset + it.outputNr)
            }
        }
        return record.toBytes()
    }

    companion object {
        /** Host name of the demo controller. `.invalid` never resolves (RFC 2606), so nothing real can be reached. */
        const val HOST: String = "demo.invalid"

        /** `ControllerProfile.id` of the demo controller. */
        const val PROFILE_ID: String = "demo"

        /** Firmware version text the demo controller reports (0x1D6). */
        const val FIRMWARE_VERSION: String = "Demo"

        private const val FIRMWARE_FIELD_LENGTH = 64
        private const val NO_ZONE: UByte = 255u
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** The controller UI allows 0–40 °C; anything outside is ignored. */
        private const val MIN_SETPOINT = 0.0f
        private const val MAX_SETPOINT = 40.0f
    }
}
