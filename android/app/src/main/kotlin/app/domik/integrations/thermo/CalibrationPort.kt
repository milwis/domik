package app.domik.integrations.thermo

import app.domik.deimic.client.commands.CalibrationReadout
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.client.commands.DeviceCommands
import app.domik.deimic.protocol.InputCalibration

/**
 * Dostęp do kalibracji wejść analogowych (czujników temperatury) sterownika: odczyt tabeli (0x131) i zapis jednego
 * rekordu (0x132). Port odcina moduł termometrów od klienta protokołu – w testach podstawia się atrapę.
 *
 * **Zapis zmienia stan sterownika** (temperatura raportowana przez czujnik, a przez nią decyzje o ogrzewaniu), więc
 * wolno go wywołać dopiero na wyraźne życzenie / za zgodą użytkownika; sam port niczego nie wysyła.
 */
interface CalibrationPort {
    /** Rekordy kalibracji wejść modułu [module] (indeks = numer wejścia); nigdy nie rzuca (poza anulowaniem). */
    suspend fun read(module: Int): CalibrationReadout

    /** Zapisuje [record] jako kalibrację wejścia [inputNr] modułu [module]; potwierdzenie to echo sterownika. */
    suspend fun write(module: Int, inputNr: Int, record: InputCalibration): CommandOutcome
}

/** Port na prawdziwych poleceniach ([DeviceCommands.readCalibrations] / [DeviceCommands.writeCalibration]). */
class DeviceCommandsCalibrationPort(private val commands: DeviceCommands) : CalibrationPort {
    override suspend fun read(module: Int): CalibrationReadout = commands.readCalibrations(module)

    override suspend fun write(module: Int, inputNr: Int, record: InputCalibration): CommandOutcome =
        commands.writeCalibration(module, inputNr, record)
}
