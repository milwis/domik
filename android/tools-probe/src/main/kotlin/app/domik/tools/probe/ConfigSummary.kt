package app.domik.tools.probe

import app.domik.deimic.config.HeatingZone
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Output
import app.domik.deimic.config.Room
import app.domik.deimic.config.TemperatureSensor

/** Prints the device model read from the controller database: levels -> rooms -> devices, then loose items and issues. */
internal fun printConfigSummary(config: HomeConfig) {
    val supported = config.supportedOutputs.size
    println(
        "[baza] moduły: ${config.modules.ifEmpty { listOf() }}, poziomów: ${config.levels.size}, pomieszczeń: ${config.rooms.size}, " +
            "wyjść: ${config.outputs.size} (obsługiwanych: $supported), stref grzewczych: ${config.heatingZones.size}, " +
            "czujników temperatury: ${config.temperatureSensors.size}, " +
            "problemów: ${config.issues.size} (informacyjnych: ${config.issues.count { it.isInformational }})",
    )

    fun printRoom(room: Room, indent: String) {
        val outputs = config.outputsInRoom(room.id)
        val zones = config.heatingZonesInRoom(room.id)
        val sensors = config.temperatureSensorsInRoom(room.id)
        println(
            "[baza] $indent pomieszczenie ${room.id} \"${room.name}\": wyjść ${outputs.size}, stref ${zones.size}, " +
                "czujników ${sensors.size}",
        )
        outputs.forEach { println("[baza] $indent   ${describe(it)}") }
        zones.forEach { println("[baza] $indent   ${describe(it)}") }
        sensors.forEach { println("[baza] $indent   ${describe(it)}") }
    }

    for (level in config.levels) {
        val rooms = config.roomsOnLevel(level.id)
        println("[baza] poziom ${level.id} \"${level.name}\": pomieszczeń ${rooms.size}")
        rooms.forEach { printRoom(it, "  ") }
    }
    val roomsWithoutLevel = config.rooms.filter { it.levelId == null || config.level(it.levelId!!) == null }
    if (roomsWithoutLevel.isNotEmpty()) {
        println("[baza] pomieszczenia bez (istniejącego) poziomu: ${roomsWithoutLevel.size}")
        roomsWithoutLevel.forEach { printRoom(it, "  ") }
    }

    val loneOutputs = config.outputs.filter { config.roomOf(it) == null }
    val loneZones = config.heatingZones.filter { config.roomOf(it) == null }
    val loneSensors = config.temperatureSensors.filter { config.roomOf(it) == null }
    if (loneOutputs.isNotEmpty() || loneZones.isNotEmpty() || loneSensors.isNotEmpty()) {
        println(
            "[baza] urządzenia bez (istniejącego) pomieszczenia: wyjść ${loneOutputs.size}, stref ${loneZones.size}, " +
                "czujników ${loneSensors.size}",
        )
        loneOutputs.forEach { println("[baza]   ${describe(it)}") }
        loneZones.forEach { println("[baza]   ${describe(it)}") }
        loneSensors.forEach { println("[baza]   ${describe(it)}") }
    }

    if (config.issues.isNotEmpty()) {
        println("[baza] problemy (${config.issues.size}):")
        config.issues.groupBy { it.kind }.forEach { (kind, issues) ->
            println("[baza]   $kind: ${issues.size}${if (issues.all { it.isInformational }) " (informacyjne)" else ""}")
            issues.forEach { println("[baza]     ${it.table} #${it.rowId}${it.column?.let { column -> " kolumna $column" }.orEmpty()}") }
        }
    }
}

private fun describe(output: Output): String =
    "wyjście ${output.module}:${output.relay} [${output.kind}${if (output.isSupported) "" else " ${output.rawType}"}] \"${output.name}\"" +
        (if (output.canOn) "" else " (bez włączania)") + " (baza #${output.dbId})"

private fun describe(zone: HeatingZone): String = "strefa ${zone.module}:${zone.outputNr} \"${zone.name}\" (baza #${zone.dbId})"

private fun describe(sensor: TemperatureSensor): String =
    "czujnik temperatury ${sensor.module}:${sensor.number} \"${sensor.name}\" (baza #${sensor.dbId})"
