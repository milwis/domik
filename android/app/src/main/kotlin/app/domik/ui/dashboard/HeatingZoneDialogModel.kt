package app.domik.ui.dashboard

import androidx.compose.runtime.Immutable
import app.domik.data.HomeState
import app.domik.data.TileId
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.protocol.HeatingProfile as ProtocolProfile
import app.domik.ui.components.HeatingControls
import app.domik.ui.components.HeatingProfile as TileProfile
import app.domik.ui.components.HeatingTileState
import app.domik.ui.components.controls

/*
 * Model okna strefy ogrzewania (HeatingZoneDialog): czyste funkcje bez Androida – testowalne w JVM.
 * Dane strefy te same co na kafelku ([zoneState]), tu tylko dołożony stan okna (zapisywanie, brak połączenia…).
 */

/** Strefa w oknie: stan jak na kafelku plus to, czego kafelek nie pokazuje. */
@Immutable
data class ZoneDialogModel(
    val tile: HeatingTileState,
    /** Temperatura zadana wybrana przez użytkownika czeka na wysłanie / potwierdzenie sterownika. */
    val savingSetpoint: Boolean,
) {
    /** Strefa została odczytana ze sterownika (jest temperatura zadana); inaczej nie ma czym sterować. */
    val hasReading: Boolean get() = tile.setpoint != null
}

/** Bieżący stan strefy [id] ze stanu domu; `null`, gdy strefy nie ma w konfiguracji (albo konfiguracji jeszcze nie ma). */
internal fun zoneDialogModel(state: HomeState, id: TileId.Zone): ZoneDialogModel? {
    val config = state.config ?: return null
    val zone = config.heatingZone(id.module, id.outputNr) ?: return null
    return ZoneDialogModel(
        tile = zoneState(zone, state, config),
        savingSetpoint = ZoneKey(id.module, id.outputNr) in state.pendingSetpoints,
    )
}

/** Komunikat pod kontrolkami okna (jeden naraz); od najważniejszego do najmniej ważnego – patrz [zoneDialogUi]. */
enum class ZoneDialogStatus(val interactive: Boolean) {
    /** Wszystko w porządku – bez komunikatu. */
    NONE(interactive = true),

    /** Polecenie w drodze do sterownika („Zapisuję…”). */
    SAVING(interactive = true),

    /** Ostatnie polecenie się nie powiodło (tekst z komunikatu zdarzenia). */
    ERROR(interactive = true),

    /** Połączone, ale strefa jeszcze nie została odczytana. */
    NO_READING(interactive = false),

    /** Brak połączenia ze sterownikiem. */
    OFFLINE(interactive = false),

    /** Strefy nie ma już w konfiguracji (okno pokazuje ostatni znany stan, bez sterowania). */
    GONE(interactive = false),
}

/**
 * Co pokazać w oknie: [tile] (ostatni znany stan, gdy strefa zniknęła; `null`, gdy nigdy jej nie było), [status]
 * i aktywność kontrolek ([controls] – ta sama reguła co na dużym kafelku, dodatkowo wyłączone bez połączenia, bez
 * odczytu strefy i po jej zniknięciu).
 */
@Immutable
data class ZoneDialogUi(
    val tile: HeatingTileState?,
    val status: ZoneDialogStatus,
    val controls: HeatingControls,
)

/**
 * @param model bieżący stan strefy ([zoneDialogModel]); `null` = strefy nie ma
 * @param lastKnown ostatni stan, jaki okno widziało (do pokazania po zniknięciu strefy)
 * @param online połączenie ze sterownikiem jest gotowe
 * @param failed ostatnie polecenie z tego okna się nie powiodło i komunikat jeszcze nie wygasł
 */
internal fun zoneDialogUi(model: ZoneDialogModel?, lastKnown: ZoneDialogModel?, online: Boolean, failed: Boolean): ZoneDialogUi {
    val shown = model ?: lastKnown
    val status = when {
        model == null -> ZoneDialogStatus.GONE
        !online -> ZoneDialogStatus.OFFLINE
        !model.hasReading -> ZoneDialogStatus.NO_READING
        failed -> ZoneDialogStatus.ERROR
        model.savingSetpoint || model.tile.busy -> ZoneDialogStatus.SAVING
        else -> ZoneDialogStatus.NONE
    }
    val tile = shown?.tile
    val controls = if (tile == null) {
        HeatingControls(canDecrease = false, canIncrease = false, increaseSwitchesOn = false, profileEnabled = false)
    } else {
        tile.controls(online = status.interactive)
    }
    return ZoneDialogUi(tile, status, controls)
}

/** Profil z interfejsu → profil protokołu (polecenie 0x66). */
internal fun TileProfile.toProtocolProfile(): ProtocolProfile = when (this) {
    TileProfile.Off -> ProtocolProfile.Off
    TileProfile.Eco -> ProtocolProfile.Eco
    TileProfile.Comfort -> ProtocolProfile.Comfort
    TileProfile.Max -> ProtocolProfile.Maximum
}
