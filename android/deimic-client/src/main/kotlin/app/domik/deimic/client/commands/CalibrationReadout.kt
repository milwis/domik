package app.domik.deimic.client.commands

import app.domik.deimic.protocol.InputCalibration

/** Result of [DeviceCommands.readCalibrations]; like commands, it never throws (apart from cancellation). */
sealed interface CalibrationReadout {
    /** The records in input order (index = input number). */
    data class Success(val records: List<InputCalibration>) : CalibrationReadout

    /** Nothing was read; [outcome] says why (never an accepted outcome). */
    data class Failure(val outcome: CommandOutcome) : CalibrationReadout
}
