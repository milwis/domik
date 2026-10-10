package app.domik.deimic.protocol

/**
 * Collective state record formats, in detection order (the first whose record size divides the payload
 * length wins). [label] is a short stable name for logs and diagnostics.
 */
enum class StateFormat(val recordSize: Int, val label: String) {
    V3(2292, "v3"),
    NEW(2164, "new"),
    V2(1556, "v2"),
    V1(672, "v1"),
    OLD(544, "old");

    /** Field extents (offset, byte length, element type) of this record format. */
    val layout: List<StateFieldExtent>
        get() = when (this) {
            V3 -> StateLayouts.V3
            NEW -> StateLayouts.NEW
            V2 -> StateLayouts.V2
            V1 -> StateLayouts.V1
            OLD -> StateLayouts.OLD
        }

    fun extent(field: StateField): StateFieldExtent? = layout.firstOrNull { it.field == field }
}

enum class StateField {
    DIMMERS, INPUTS, OUTPUTS, SHUTTER_STATES, RECUPERATORS, FAN_COIL_UNITS, COUNTERS, TIMERS,
    CLOCKS, ASTRONOMICAL, ADDRESS, TEMPERATURES, HEATERS, ANALOG_INPUTS, SPRINKLERS, SHUTTER_POSITIONS,
}

enum class StateElementType(val size: Int) { U8(1), I16(2), I32(4), F32(4) }

data class StateFieldExtent(
    val field: StateField,
    val offset: Int,
    val byteLength: Int,
    val elementType: StateElementType,
) {
    val elementCount: Int get() = byteLength / elementType.size
}

internal object StateLayouts {
    private fun extent(field: StateField, offset: Int, byteLength: Int, type: StateElementType) =
        StateFieldExtent(field, offset, byteLength, type)

    val V2: List<StateFieldExtent> = listOf(
        extent(StateField.DIMMERS, 0, 128, StateElementType.I32),
        extent(StateField.INPUTS, 128, 128, StateElementType.U8),
        extent(StateField.OUTPUTS, 256, 128, StateElementType.U8),
        extent(StateField.SHUTTER_STATES, 384, 64, StateElementType.U8),
        extent(StateField.RECUPERATORS, 448, 8, StateElementType.U8),
        extent(StateField.FAN_COIL_UNITS, 456, 8, StateElementType.U8),
        extent(StateField.COUNTERS, 464, 256, StateElementType.I32),
        extent(StateField.TIMERS, 720, 256, StateElementType.I32),
        extent(StateField.CLOCKS, 976, 32, StateElementType.U8),
        extent(StateField.ASTRONOMICAL, 1008, 32, StateElementType.U8),
        extent(StateField.ADDRESS, 1040, 4, StateElementType.I32),
        extent(StateField.TEMPERATURES, 1044, 512, StateElementType.F32),
    )

    val NEW: List<StateFieldExtent> = V2 + listOf(
        extent(StateField.HEATERS, 1556, 32, StateElementType.U8),
        extent(StateField.ANALOG_INPUTS, 1588, 512, StateElementType.F32),
        extent(StateField.SPRINKLERS, 2100, 64, StateElementType.U8),
    )

    val V3: List<StateFieldExtent> = NEW + extent(StateField.SHUTTER_POSITIONS, 2164, 128, StateElementType.I16)

    val OLD: List<StateFieldExtent> = listOf(
        extent(StateField.DIMMERS, 0, 32, StateElementType.I32),
        extent(StateField.INPUTS, 32, 32, StateElementType.U8),
        extent(StateField.OUTPUTS, 64, 128, StateElementType.U8),
        extent(StateField.SHUTTER_STATES, 192, 12, StateElementType.U8),
        extent(StateField.RECUPERATORS, 204, 8, StateElementType.U8),
        extent(StateField.FAN_COIL_UNITS, 212, 8, StateElementType.U8),
        extent(StateField.COUNTERS, 220, 128, StateElementType.I32),
        extent(StateField.TIMERS, 348, 128, StateElementType.I32),
        extent(StateField.CLOCKS, 476, 32, StateElementType.U8),
        extent(StateField.ASTRONOMICAL, 508, 32, StateElementType.U8),
        extent(StateField.ADDRESS, 540, 4, StateElementType.I32),
    )

    val V1: List<StateFieldExtent> = OLD + extent(StateField.TEMPERATURES, 544, 128, StateElementType.F32)
}
