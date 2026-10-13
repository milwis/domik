package app.domik.demo

import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.InputCalibration
import app.domik.deimic.protocol.SerialNumber

/** `output_type` values stored in the controller's database (`outputs.output_type`). */
object DemoOutputType {
    const val ON_OFF: Int = 1
    const val RGB: Int = 4
    const val SHUTTER: Int = 5
    const val LED: Int = 7
    const val DIMMER_ON_OFF: Int = 8
    const val HEATING_ZONE: Int = 123
}

/** A floor of the fictional house (`template_levels`). */
data class DemoLevel(val id: Int, val name: String)

/** A room of the fictional house (`template_rooms`). */
data class DemoRoom(val id: Int, val levelId: Int, val name: String)

/**
 * A switchable device ("light", socket, LED strip, dimmer, …) addressed by ([module], [relay]) = (`id_device`, `output_relay`).
 * [outputType] is one of [DemoOutputType]; [iconId] and [outputsNumber] are stored as given.
 */
data class DemoOutput(
    val module: Int,
    val relay: Int,
    val name: String,
    val roomId: Int,
    val outputType: Int = DemoOutputType.ON_OFF,
    val iconId: Int = 0,
    val outputsNumber: Int = 0,
    val initiallyOn: Boolean = false,
)

/**
 * A heating zone addressed by ([module], [outputNr]). [relay] is the zone's `output_relay`, which differs from
 * `output_nr` and must never be used in heating commands (it is stored only to make the database realistic).
 *
 * Every profile keeps its own setpoint ([eco], [comfort], [maximum]); [off] is what an inactive zone reports.
 *
 * The zone's temperature sensor is wired to the analog input [inputNr] of the same module (the sensor reports through
 * 0x72 / 0x130 under that number and is calibrated with 0x131 / 0x132 under it). The input number is deliberately NOT
 * the zone's [outputNr] in [DemoHome.standard]: like in a real installation the two numberings are independent, and the
 * link between a zone and its input exists only through the room (the `inputs` table of [DemoDatabase] gives the
 * input the zone's room, `input_type` 8).
 */
data class DemoZone(
    val module: Int,
    val outputNr: Int,
    val name: String,
    val roomId: Int,
    val relay: Int,
    val inputNr: Int,
    val profile: HeatingProfile,
    val eco: Float,
    val comfort: Float,
    val maximum: Float,
    val current: Float,
    val off: Float = 5.0f,
) {
    /** Setpoint stored for [profile]; null for an unknown profile. */
    fun setpointOf(profile: HeatingProfile): Float? = when (profile) {
        HeatingProfile.Off -> off
        HeatingProfile.Eco -> eco
        HeatingProfile.Comfort -> comfort
        HeatingProfile.Maximum -> maximum
        else -> null
    }
}

/**
 * A shutter addressed by ([module], [outputNr]) = (`id_device`, `output_nr`), like in the controller: commands use the
 * shutter's own number, never the relays it drives. In the database a shutter has `output_type` 5, `outputs_number` 2
 * and `output_relay` = 2 × [outputNr] (a pair of relays); those relays do not appear among [DemoHome.outputs].
 */
data class DemoShutter(
    val module: Int,
    val outputNr: Int,
    val name: String,
    val roomId: Int,
    val iconId: Int = 0,
) {
    /** The first relay of the shutter's pair, as stored in `outputs.output_relay`. */
    val relay: Int get() = 2 * outputNr
}

/**
 * A temperature sensor that is not part of a zone (for example outdoors); reports a constant [temperature].
 * [number] is its analog input number on the [module] (it must differ from the [DemoZone.inputNr] of every zone).
 */
data class DemoSensor(val module: Int, val number: Int, val roomId: Int, val temperature: Float)

/**
 * The fictional house of demo mode: floors, rooms, devices and heating zones. It is the single source for both the
 * synthetic configuration database ([DemoDatabase]) and the simulated state ([FakeController]); contains no data of
 * any real house. Names are generic.
 */
class DemoHome(
    val name: String,
    val levels: List<DemoLevel>,
    val rooms: List<DemoRoom>,
    val outputs: List<DemoOutput>,
    val zones: List<DemoZone>,
    val sensors: List<DemoSensor> = emptyList(),
    val shutters: List<DemoShutter> = emptyList(),
) {
    init {
        require(levels.map { it.id }.toSet().size == levels.size) { "duplicate level id" }
        require(rooms.map { it.id }.toSet().size == rooms.size) { "duplicate room id" }
        require(rooms.all { room -> levels.any { it.id == room.levelId } }) { "a room refers to an unknown level" }
        // Temperature reports (0x72) carry the level and room as single bytes.
        require(levels.all { it.id in 0..255 } && rooms.all { it.id in 0..255 }) { "level or room id out of range" }
        require(outputs.all { room(it.roomId) != null }) { "an output refers to an unknown room" }
        require(zones.all { room(it.roomId) != null }) { "a zone refers to an unknown room" }
        require(sensors.all { room(it.roomId) != null }) { "a sensor refers to an unknown room" }
        require(shutters.all { room(it.roomId) != null }) { "a shutter refers to an unknown room" }
        require(shutters.all { it.module >= 1 && it.outputNr in 0..MAX_SHUTTER && it.relay <= MAX_RELAY }) {
            "shutter address out of range"
        }
        require(shutters.map { it.module to it.outputNr }.toSet().size == shutters.size) { "duplicate shutter address" }
        require(outputs.all { it.module >= 1 && it.relay in 0..MAX_RELAY }) { "output address out of range" }
        require(outputs.map { it.module to it.relay }.toSet().size == outputs.size) { "duplicate output address" }
        require(zones.all { it.module >= 1 && it.outputNr in 0..MAX_ZONE && it.relay in 0..MAX_RELAY }) {
            "zone address out of range"
        }
        require(zones.map { it.module to it.outputNr }.toSet().size == zones.size) { "duplicate zone address" }
        require(zones.all { it.profile !is HeatingProfile.Unknown }) { "a zone has an unknown profile" }
        require(sensors.all { it.module >= 1 && it.number in 0..MAX_SENSOR }) { "sensor address out of range" }
        require(zones.all { it.inputNr in 0..InputCalibration.MAX_INPUT }) { "zone input number out of range" }
        val sensorAddresses = zones.map { it.module to it.inputNr } + sensors.map { it.module to it.number }
        require(sensorAddresses.toSet().size == sensorAddresses.size) { "an analog input number is used twice" }
    }

    /** The distinct module addresses (`id_device`) of all devices, ascending. */
    val modules: List<Int> =
        (outputs.map { it.module } + zones.map { it.module } + sensors.map { it.module } + shutters.map { it.module })
            .distinct().sorted()

    fun room(id: Int): DemoRoom? = rooms.firstOrNull { it.id == id }

    /** The floor a room is on. */
    fun levelOf(roomId: Int): Int = checkNotNull(room(roomId)) { "unknown room $roomId" }.levelId

    companion object {
        /** Serial number of the demo controller – the same fake value as in all test fixtures; not a real one. */
        val SERIAL: SerialNumber = SerialNumber(0x12345678u)

        /** Highest `output_relay` (the collective state has 128 outputs per module). */
        const val MAX_RELAY: Int = 127

        /** Highest heating zone number (`output_nr`); the collective state has 32 heater flags. */
        const val MAX_ZONE: Int = 31

        /** Highest sensor number (the collective state has 128 temperatures per module). */
        const val MAX_SENSOR: Int = 127

        /** Highest shutter number (`output_nr`); the collective state has 64 shutter states. */
        const val MAX_SHUTTER: Int = 63

        /**
         * A two-storey house with two modules: lights of several kinds (plain, wall light, pendant, spots, floor
         * lamp, garden lamp, LED strip, dimmer, RGB), a socket, a fan, a pump, an alarm output (icon_id 124, hidden on
         * the dashboard by default), five shutters and four heating zones with different profiles (comfort, eco,
         * maximum, off). Zone 0 and shutter 0 exist on both modules on purpose, so that code must address them by
         * module *and* number. The zones' sensors sit on analog inputs whose numbers differ from the zone numbers
         * (module 1: zone 0 -> input 4, zone 1 -> input 0, zone 2 -> input 3; module 2: zone 0 -> input 2; the
         * outdoor sensor is input 1 of module 2).
         */
        fun standard(): DemoHome {
            val levels = listOf(DemoLevel(1, "Parter"), DemoLevel(2, "Piętro"))
            val rooms = listOf(
                DemoRoom(1, 1, "Salon"),
                DemoRoom(2, 1, "Kuchnia"),
                DemoRoom(3, 1, "Przedpokój"),
                DemoRoom(4, 1, "Gabinet"),
                DemoRoom(5, 1, "Taras"),
                DemoRoom(6, 2, "Sypialnia"),
                DemoRoom(7, 2, "Łazienka"),
                DemoRoom(8, 2, "Garderoba"),
            )
            val light = 3
            val socket = 1003 // icon_id of the controller database: socket
            val pump = 1064 // icon_id: pump
            val alarm = 124 // icon_id: output that arms an alarm zone (hidden on the dashboard by default)
            val strip = 9
            val outputs = listOf(
                DemoOutput(1, 0, "Sufit", 1, iconId = light, initiallyOn = true),
                DemoOutput(1, 1, "Kinkiet", 1, iconId = light),
                DemoOutput(1, 2, "Choinka", 1, iconId = socket),
                DemoOutput(1, 3, "Taśma LED", 1, DemoOutputType.LED, strip),
                DemoOutput(1, 4, "Sufit", 2, iconId = light),
                DemoOutput(1, 5, "Blat", 2, iconId = light, initiallyOn = true),
                DemoOutput(1, 6, "Oczka", 3, iconId = light),
                DemoOutput(1, 7, "Biurko", 4, iconId = light),
                DemoOutput(1, 8, "Żyrandol", 4, DemoOutputType.DIMMER_ON_OFF, strip),
                DemoOutput(1, 9, "Lampka", 6, iconId = light),
                DemoOutput(1, 10, "Sufit", 6, DemoOutputType.DIMMER_ON_OFF, strip),
                DemoOutput(1, 11, "Lustro", 7, iconId = light, initiallyOn = true),
                DemoOutput(1, 12, "Sufit", 8, iconId = light),
                DemoOutput(1, 13, "Lampa stojąca", 1, iconId = light),
                DemoOutput(1, 14, "Wentylator", 7, iconId = light),
                DemoOutput(2, 0, "Gniazdko", 5, iconId = socket),
                DemoOutput(2, 1, "Oświetlenie", 5, iconId = light),
                DemoOutput(2, 2, "Ogród", 5, DemoOutputType.RGB, strip, outputsNumber = 3),
                DemoOutput(2, 3, "Lampa ogrodowa", 5, iconId = light),
                DemoOutput(2, 4, "Pompa ogrodowa", 5, iconId = pump),
                DemoOutput(2, 5, "Uzbrój alarm", 3, iconId = alarm),
            )
            val zones = listOf(
                DemoZone(
                    1, 0, "Salon", roomId = 1, relay = 40, inputNr = 4, profile = HeatingProfile.Comfort,
                    eco = 19.0f, comfort = 22.0f, maximum = 24.0f, current = 21.4f,
                ),
                DemoZone(
                    1, 1, "Sypialnia", roomId = 6, relay = 41, inputNr = 0, profile = HeatingProfile.Eco,
                    eco = 18.0f, comfort = 21.0f, maximum = 23.0f, current = 19.2f,
                ),
                DemoZone(
                    1, 2, "Łazienka", roomId = 7, relay = 42, inputNr = 3, profile = HeatingProfile.Maximum,
                    eco = 20.0f, comfort = 23.0f, maximum = 24.5f, current = 22.3f,
                ),
                DemoZone(
                    2, 0, "Gabinet", roomId = 4, relay = 40, inputNr = 2, profile = HeatingProfile.Off,
                    eco = 18.5f, comfort = 21.5f, maximum = 23.5f, current = 19.8f,
                ),
            )
            val sensors = listOf(DemoSensor(module = 2, number = 1, roomId = 5, temperature = 11.5f))
            val shutters = listOf(
                DemoShutter(1, 0, "Okno", roomId = 1),
                DemoShutter(1, 1, "Drzwi tarasowe", roomId = 1),
                DemoShutter(1, 2, "Okno", roomId = 6),
                DemoShutter(1, 3, "Okno", roomId = 4),
                DemoShutter(2, 0, "Okno", roomId = 2),
            )
            return DemoHome("Dom demonstracyjny", levels, rooms, outputs, zones, sensors, shutters)
        }
    }
}
