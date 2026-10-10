package app.domik.ui.dashboard

import androidx.compose.runtime.Immutable
import app.domik.data.Features
import app.domik.data.HomeState
import app.domik.data.IconChoices
import app.domik.data.LightIcon
import app.domik.data.LightIcons
import app.domik.data.OutputKey
import app.domik.data.ShutterButton
import app.domik.data.ShutterKey
import app.domik.data.TileId
import app.domik.data.TileSize
import app.domik.data.DashboardLayout
import app.domik.data.automaticIcon
import app.domik.data.isAlarm
import app.domik.deimic.client.commands.CommandLimits
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.HeatingZone
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Output
import app.domik.deimic.config.Shutter
import app.domik.deimic.protocol.ShutterDirection
import app.domik.ui.components.DeviceIcon
import app.domik.ui.components.HeatingTileState
import app.domik.ui.components.ShutterControl
import app.domik.ui.components.ShutterDisplay
import app.domik.deimic.protocol.HeatingProfile as ProtocolProfile
import app.domik.ui.components.HeatingProfile as TileProfile

/** Kafelek pulpitu gotowy do wyświetlenia: [id] i [size] z układu plus dane z konfiguracji i stanu domu. */
@Immutable
sealed interface TileModel {
    val id: TileId
    val size: TileSize
}

@Immutable
data class WeatherTileModel(override val size: TileSize) : TileModel {
    override val id: TileId get() = TileId.Weather
}

@Immutable
data class AgendaTileModel(override val size: TileSize) : TileModel {
    override val id: TileId get() = TileId.Agenda
}

/** Kafelek strefy ogrzewania. */
@Immutable
data class ZoneTileModel(override val id: TileId.Zone, override val size: TileSize, val state: HeatingTileState) : TileModel

/**
 * Kafelek przełącznika (światło, gniazdko…). [on] to stan docelowy, gdy [pending].
 *
 * @property lightIcon ikona pokazana na kafelku: wybór użytkownika albo [autoIcon] (dobór automatyczny)
 * @property confirm dotknięcie wymaga potwierdzenia (wyjście steruje alarmem) – patrz [confirmRequest]
 */
@Immutable
data class SwitchTileModel(
    override val id: TileId.Switch,
    override val size: TileSize,
    val name: String,
    val room: String,
    val on: Boolean,
    val pending: Boolean,
    val icon: DeviceIcon,
    val lightIcon: LightIcon = LightIcon.BULB,
    val autoIcon: LightIcon = lightIcon,
    val confirm: Boolean = false,
) : TileModel

/**
 * Kafelek rolety. [display] to ostatni kierunek zgłoszony przez sterownik (brak = „stan nieznany”), a [pending] to
 * przycisk, na którego skutek czeka sterownik (null = brak polecenia w toku).
 */
@Immutable
data class ShutterTileModel(
    override val id: TileId.Shutter,
    override val size: TileSize,
    val name: String,
    val room: String,
    val display: ShutterDisplay,
    val pending: ShutterControl?,
) : TileModel

/** Grupy w arkuszu „Dodaj” (ukryte kafelki), w kolejności wyświetlania. */
enum class TileGroup { HEATING, LIGHTS, SHUTTERS, ALARM, OTHER }

/** Ukryty kafelek do ponownego dodania: nazwa i pomieszczenie z konfiguracji ([name] puste dla pogody i kalendarza). */
@Immutable
data class HiddenTileModel(val id: TileId, val name: String, val room: String, val group: TileGroup)

/** Zawartość pulpitu wyliczona ze stanu domu i układu (czysta funkcja – bez Androida, testowalna). */
@Immutable
data class DashboardModel(
    val tiles: List<TileModel>,
    val hidden: List<HiddenTileModel> = emptyList(),
) {
    val zones: List<ZoneTileModel> get() = tiles.filterIsInstance<ZoneTileModel>()
    val switches: List<SwitchTileModel> get() = tiles.filterIsInstance<SwitchTileModel>()
    val shutters: List<ShutterTileModel> get() = tiles.filterIsInstance<ShutterTileModel>()
    val showWeather: Boolean get() = tiles.any { it is WeatherTileModel }
    val showAgenda: Boolean get() = tiles.any { it is AgendaTileModel }

    /** Czy konfiguracja ma urządzenia, które aplikacja potrafi pokazać (także te ukryte przez użytkownika). */
    val hasDevices: Boolean
        get() = tiles.any { it is ZoneTileModel || it is SwitchTileModel || it is ShutterTileModel } ||
            hidden.any { it.group != TileGroup.OTHER }
}

/**
 * Buduje pulpit z [state] i [layout]. Nazwy i pomieszczenia pochodzą wyłącznie z konfiguracji sterownika
 * ([HomeState.config]); urządzenia, których nie ma w konfiguracji, nie mają kafelka.
 */
fun buildDashboard(state: HomeState, layout: DashboardLayout, icons: IconChoices = IconChoices.EMPTY): DashboardModel {
    val config = state.config
        ?: return DashboardModel(
            tiles = layout.tiles.mapNotNull { entry ->
                when (entry.id) {
                    TileId.Weather -> WeatherTileModel(entry.size)
                    TileId.Agenda -> AgendaTileModel(entry.size)
                    else -> null
                }
            },
        )
    val tiles = layout.tiles.mapNotNull { (id, size) ->
        when (id) {
            TileId.Weather -> WeatherTileModel(size)
            TileId.Agenda -> AgendaTileModel(size)
            is TileId.Zone ->
                config.heatingZone(id.module, id.outputNr)?.let { ZoneTileModel(id, size, zoneState(it, state, config)) }
            is TileId.Switch ->
                config.output(id.module, id.relay)?.takeIf { it.isSupported }?.let { switchModel(id, size, it, state, config, icons[id]) }
            is TileId.Shutter ->
                config.shutter(id.module, id.outputNr)?.let { shutterModel(id, size, it, state, config) }
        }
    }
    val hidden = layout.hidden.mapNotNull { (id, _) -> hiddenModel(id, config) }
    return DashboardModel(tiles, hidden)
}

private fun hiddenModel(id: TileId, config: HomeConfig): HiddenTileModel? = when (id) {
    TileId.Weather, TileId.Agenda -> HiddenTileModel(id, name = "", room = "", group = TileGroup.OTHER)
    is TileId.Zone -> config.heatingZone(id.module, id.outputNr)?.let {
        HiddenTileModel(id, it.name, placeLabel(config, it.name, it.roomId, it.levelId), TileGroup.HEATING)
    }
    is TileId.Switch -> config.output(id.module, id.relay)?.takeIf { it.isSupported }?.let {
        val group = if (it.isAlarm) TileGroup.ALARM else TileGroup.LIGHTS
        HiddenTileModel(id, it.name, placeLabel(config, it.name, it.roomId, it.levelId), group)
    }
    is TileId.Shutter -> config.shutter(id.module, id.outputNr)?.let {
        HiddenTileModel(id, it.name, placeLabel(config, it.name, it.roomId, it.levelId), TileGroup.SHUTTERS)
    }
}

internal fun zoneState(zone: HeatingZone, state: HomeState, config: HomeConfig): HeatingTileState {
    val key = ZoneKey(zone.module, zone.outputNr)
    val reading = state.zones[key]
    val pendingProfile = state.pendingProfiles[key]
    val pendingSetpoint = state.pendingSetpoints[key]
    val reportedOff = reading?.profile == ProtocolProfile.Off
    // „+” na wyłączonej strefie włącza ją; do potwierdzenia pokazujemy dotychczasowy stan i blokujemy sterowanie.
    val switchingOn = reportedOff && pendingSetpoint != null
    val busy = pendingProfile != null || switchingOn
    val setpoint = when {
        reading == null -> null
        pendingSetpoint != null && !switchingOn -> pendingSetpoint.target
        else -> reading.setpoint
    }?.takeIf { it.isFinite() }
    return HeatingTileState(
        name = zone.name,
        room = placeLabel(config, zone.name, zone.roomId, zone.levelId),
        currentTemp = reading?.currentTemperature?.takeIf { it.isFinite() },
        setpoint = setpoint,
        profile = (pendingProfile ?: reading?.profile)?.toTileProfile() ?: TileProfile.Off,
        heating = reading?.isHeating == true,
        canDecrease = setpoint != null && setpoint - CommandLimits.SETPOINT_STEP_CELSIUS >= CommandLimits.MIN_SETPOINT_CELSIUS,
        canIncrease = setpoint != null && setpoint + CommandLimits.SETPOINT_STEP_CELSIUS <= CommandLimits.MAX_SETPOINT_CELSIUS,
        canSwitchOn = Features.PROFILE_SWITCHING && reportedOff,
        busy = busy,
        profileSwitching = Features.PROFILE_SWITCHING,
    )
}

private fun switchModel(
    id: TileId.Switch,
    size: TileSize,
    output: Output,
    state: HomeState,
    config: HomeConfig,
    chosen: LightIcon?,
): SwitchTileModel {
    val key = OutputKey(output.module, output.relay)
    val pending = state.pendingOutputs[key]
    val auto = output.automaticIcon()
    val shown = chosen ?: auto
    return SwitchTileModel(
        id = id,
        size = size,
        name = output.name,
        room = placeLabel(config, output.name, output.roomId, output.levelId),
        on = pending ?: (key in state.outputsOn),
        pending = pending != null,
        icon = shown.toDeviceIcon(),
        lightIcon = shown,
        autoIcon = auto,
        confirm = LightIcons.requiresConfirmation(output.iconId, shown),
    )
}

private fun shutterModel(id: TileId.Shutter, size: TileSize, shutter: Shutter, state: HomeState, config: HomeConfig): ShutterTileModel {
    val key = ShutterKey(shutter.module, shutter.outputNr)
    return ShutterTileModel(
        id = id,
        size = size,
        name = shutter.name,
        room = placeLabel(config, shutter.name, shutter.roomId, shutter.levelId),
        display = state.shutters[key].toDisplay(),
        pending = state.pendingShutters[key]?.toControl(),
    )
}

/** Brak wpisu (nikt nie ruszył rolety) i kierunek spoza listy to „nieznany”. */
internal fun ShutterDirection?.toDisplay(): ShutterDisplay = when (this) {
    ShutterDirection.MOVING_UP -> ShutterDisplay.MovingUp
    ShutterDirection.MOVING_DOWN -> ShutterDisplay.MovingDown
    ShutterDirection.STOPPED -> ShutterDisplay.Stopped
    ShutterDirection.AT_TOP -> ShutterDisplay.Open
    ShutterDirection.AT_BOTTOM -> ShutterDisplay.Closed
    ShutterDirection.UNKNOWN, ShutterDirection.OTHER, null -> ShutterDisplay.Unknown
}

private fun ShutterButton.toControl(): ShutterControl = when (this) {
    ShutterButton.UP -> ShutterControl.Up
    ShutterButton.STOP -> ShutterControl.Stop
    ShutterButton.DOWN -> ShutterControl.Down
}

internal fun ShutterControl.toButton(): ShutterButton = when (this) {
    ShutterControl.Up -> ShutterButton.UP
    ShutterControl.Stop -> ShutterButton.STOP
    ShutterControl.Down -> ShutterButton.DOWN
}

/**
 * Podpis pod nazwą: pomieszczenie; gdy pomieszczenie nazywa się tak samo jak urządzenie (strefa „Salon” w pokoju
 * „Salon”) albo go nie ma – piętro.
 */
private fun placeLabel(config: HomeConfig, name: String, roomId: Int?, levelId: Int?): String {
    val room = roomId?.let(config::room)?.name
    val level = (levelId ?: roomId?.let(config::room)?.levelId)?.let(config::level)?.name
    return when {
        room != null && !room.equals(name, ignoreCase = true) -> room
        level != null -> level
        else -> room.orEmpty()
    }
}

private fun ProtocolProfile.toTileProfile(): TileProfile = when (this) {
    ProtocolProfile.Eco -> TileProfile.Eco
    ProtocolProfile.Comfort -> TileProfile.Comfort
    ProtocolProfile.Maximum -> TileProfile.Max
    else -> TileProfile.Off // wyłączone albo profil, którego ta wersja nie zna
}
