package app.domik.deimic.config

/**
 * The only user-visible strings produced by the configuration layer: names substituted when the controller's
 * database has no name for an item. Everything else comes from the database. Supply another implementation to
 * localize the fallbacks.
 */
interface ConfigTexts {
    fun levelName(id: Int): String

    fun roomName(id: Int): String

    /** [relay] is the output's `output_relay`. */
    fun outputName(relay: Int): String

    /** [outputNr] is the zone's `output_nr`. */
    fun heatingZoneName(outputNr: Int): String

    /**
     * [number] is the sensor's `output_nr`. Has a default (the Polish text) so that implementations written before
     * temperature sensors existed keep compiling; override it to localize.
     */
    fun temperatureSensorName(number: Int): String = "Czujnik $number"

    /** [outputNr] is the shutter's `output_nr`. Has a default (the Polish text) so that older implementations keep compiling. */
    fun shutterName(outputNr: Int): String = "Roleta $outputNr"

    /** [inputNr] is the input's `input_nr`. Has a default (the Polish text) so that older implementations keep compiling. */
    fun analogInputName(inputNr: Int): String = "Wejście $inputNr"

    object Polish : ConfigTexts {
        override fun levelName(id: Int): String = "Poziom $id"

        override fun roomName(id: Int): String = "Pomieszczenie $id"

        override fun outputName(relay: Int): String = "Wyjście $relay"

        override fun heatingZoneName(outputNr: Int): String = "Strefa $outputNr"

        override fun temperatureSensorName(number: Int): String = "Czujnik $number"

        override fun shutterName(outputNr: Int): String = "Roleta $outputNr"

        override fun analogInputName(inputNr: Int): String = "Wejście $inputNr"
    }
}
