package app.domik.ui.dashboard

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.R
import app.domik.data.AppModel
import app.domik.data.CommandFailureKind
import app.domik.data.ConfigFailure
import app.domik.data.ConfigStatus
import app.domik.data.ConnectionNotice
import app.domik.data.HomeEvent
import app.domik.data.HomeSession
import app.domik.data.OutputKey
import app.domik.data.TileId
import app.domik.data.toNotice
import app.domik.deimic.client.ConnectionProblem
import app.domik.ui.components.ConnectionBanner
import app.domik.ui.components.LightTile
import app.domik.ui.components.ShutterTile
import app.domik.ui.menu.CalendarMenu
import app.domik.ui.menu.MenuActions
import app.domik.ui.menu.MenuSheet
import app.domik.ui.menu.WeatherMenu
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikTheme
import app.domik.ui.time.rememberNow

/** Pulpit: czeka na sesję, a potem pokazuje dom z [HomeSession]. */
@Composable
fun DashboardRoute(model: AppModel) {
    val session by model.session.collectAsStateWithLifecycle()
    val current = session
    if (current == null) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        SessionDashboard(model, current)
    }
}

@Composable
private fun SessionDashboard(model: AppModel, session: HomeSession) {
    val repository = session.repository
    val state by repository.state.collectAsStateWithLifecycle()
    val connection by repository.connectionState.collectAsStateWithLifecycle()
    val info by model.info.collectAsStateWithLifecycle()
    val appearance by model.appearance.collectAsStateWithLifecycle()
    val layoutState by model.layout.collectAsStateWithLifecycle()
    val iconChoices by model.icons.collectAsStateWithLifecycle()
    val weatherOptions by model.weatherOptions.collectAsStateWithLifecycle()
    val notice = connection.toNotice()
    val now by rememberNow()
    val calendarAccess = rememberCalendarAccess()
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var focusWeatherSearch by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showReset by rememberSaveable { mutableStateOf(false) }
    var showWeatherOptions by rememberSaveable { mutableStateOf(false) }
    var scrollToEnd by remember { mutableIntStateOf(0) }
    val editing = layoutState.editing
    var iconPickerFor by remember { mutableStateOf<TileId.Switch?>(null) }
    var confirmRequest by remember { mutableStateOf<ConfirmRequest?>(null) }
    LaunchedEffect(editing) { if (!editing) iconPickerFor = null }

    // Systemowy „wstecz” w trybie edycji kończy edycję (zmiany są już zapisane).
    BackHandler(enabled = editing) { model.finishLayoutEdit() }

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(repository) {
        repository.events.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(event.message(resources))
        }
    }

    val homeName = if (session.isDemo) {
        stringResource(R.string.home_demo_name)
    } else {
        info.controllerName.ifBlank { stringResource(R.string.home_default_name) }
    }
    val weatherLabel = stringResource(R.string.tile_weather)
    val agendaLabel = stringResource(R.string.tile_agenda)

    // Wartości wyliczone w układzie (zależą od rozmiaru okna), potrzebne arkuszowi „Dodaj” i oknu resetu.
    var columnsNow by remember { mutableIntStateOf(GridSpec.COLUMNS_PHONE) }
    var hiddenNow by remember { mutableStateOf(emptyList<HiddenTileModel>()) }
    var switchesNow by remember { mutableStateOf(emptyMap<TileId, SwitchTileModel>()) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ConnectionBanner(
                visible = notice != null,
                message = notice?.text() ?: stringResource(R.string.connection_lost),
                actionLabel = if (notice?.gaveUp == true) stringResource(R.string.connection_retry) else null,
                onAction = if (notice?.gaveUp == true) model::retryConnection else null,
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val tablet = maxWidth >= GridSpec.TABLET_MIN_WIDTH_DP.dp
                val gap = GridSpec.gap(maxWidth.value).dp
                val columns = GridSpec.columns(maxWidth.value, maxHeight.value)
                val config = state.config
                val status = state.configStatus
                val layout = layoutState.resolve(config, columns)
                val dashboard = remember(state, layout, iconChoices) { buildDashboard(state, layout, iconChoices) }
                SideEffect {
                    columnsNow = columns
                    hiddenNow = dashboard.hidden
                    switchesNow = dashboard.switches.associateBy { it.id }
                }
                val gridTiles = remember(dashboard) {
                    // Ikona na przycisku „Ikona” w edycji: uchwyt do przeciągania zasłania ikonę kafelka.
                    dashboard.tiles.map { GridTile(it.id, it.size, (it as? SwitchTileModel)?.icon?.outline) }
                }
                val byId = remember(dashboard) { dashboard.tiles.associateBy { it.id } }
                val labels = remember(dashboard, weatherLabel, agendaLabel) {
                    dashboard.tiles.associate { it.id to it.label(weatherLabel, agendaLabel) }
                }
                val editActions = remember(model) {
                    GridEditActions(
                        move = model::moveTile,
                        cycleSize = { id -> model.cycleTileSize(id, columnsNow) },
                        resize = model::resizeTile,
                        hide = model::hideTile,
                        chooseIcon = { id -> (id as? TileId.Switch)?.let { iconPickerFor = it } },
                        // Dotknięcie kafelka w trybie edycji: na razie bez działania (miejsce na ustawienia kafelka, np. pogody).
                        configure = { id -> if (id == TileId.Weather) showWeatherOptions = true },
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    if (editing) {
                        EditHeader(
                            gap = gap,
                            onReset = { showReset = true },
                            onAdd = { showAdd = true },
                            onDone = { model.finishLayoutEdit() },
                        )
                    } else {
                        Header(
                            name = homeName,
                            isDemo = session.isDemo,
                            gap = gap,
                            compact = !tablet,
                            canEdit = config != null && dashboard.hasDevices,
                            onEdit = { model.startLayoutEdit(config, columns) },
                            onMenu = { focusWeatherSearch = false; showMenu = true },
                        )
                    }
                    when {
                        config == null && status is ConfigStatus.Failed ->
                            MessagePanel(
                                text = stringResource(status.reason.messageRes()),
                                actionLabel = stringResource(R.string.dashboard_retry),
                                onAction = model::retryConnection,
                            )
                        config == null && notice != null ->
                            MessagePanel(
                                text = stringResource(R.string.dashboard_waiting_for_controller),
                                actionLabel = stringResource(R.string.menu_connection_settings),
                                onAction = model::openConnectionSettings,
                            )
                        config == null -> LoadingPanel()
                        !dashboard.hasDevices -> MessagePanel(text = stringResource(R.string.dashboard_empty))
                        else -> DashboardGrid(
                            tiles = gridTiles,
                            columns = columns,
                            editing = editing,
                            actions = editActions,
                            labels = labels,
                            emptyHint = stringResource(if (editing) R.string.edit_empty else R.string.dashboard_all_hidden),
                            modifier = Modifier.weight(1f),
                            scrollToEnd = scrollToEnd,
                        ) { tile, width, _ ->
                            when (val item = byId[tile.id]) {
                                is WeatherTileModel -> WeatherTileHost(
                                    weather = model.weather,
                                    isDemo = session.isDemo,
                                    options = weatherOptions,
                                    now = now,
                                    onChoosePlace = { focusWeatherSearch = true; showMenu = true },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                is AgendaTileModel -> CalendarTile(
                                    calendar = model.calendar,
                                    access = calendarAccess,
                                    isDemo = session.isDemo,
                                    now = now,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                is ZoneTileModel -> HeatingZoneTile(
                                    item = item,
                                    repository = repository,
                                    editing = editing,
                                    controlSize = if (tablet && width >= 270.dp) 56.dp else 48.dp,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                is SwitchTileModel -> LightTile(
                                    name = item.name,
                                    room = item.room,
                                    on = item.on,
                                    pending = item.pending,
                                    icon = item.icon,
                                    onToggle = {
                                        // Wyjście alarmowe: najpierw pytanie, do potwierdzenia nic nie jest wysyłane.
                                        val request = item.confirmRequest()
                                        if (request != null) {
                                            confirmRequest = request
                                        } else {
                                            repository.toggleOutput(item.id.module, item.id.relay)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    wide = item.size.cols >= 2,
                                )
                                is ShutterTileModel -> ShutterTile(
                                    name = item.name,
                                    room = item.room,
                                    display = item.display,
                                    pending = item.pending,
                                    onControl = { control ->
                                        repository.pressShutter(item.id.module, item.id.outputNr, control.toButton())
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))
    }

    if (showMenu) {
        val calendarState by model.calendar.state.collectAsStateWithLifecycle()
        val calendarSelection by model.calendar.selection.collectAsStateWithLifecycle()
        val weatherPlace by model.weather.place.collectAsStateWithLifecycle()
        val weatherSearch by model.weather.search.collectAsStateWithLifecycle()
        MenuSheet(
            info = info,
            appearance = appearance,
            onAppearanceChange = model::setAppearance,
            actions = MenuActions(
                onConnectionSettings = model::openConnectionSettings,
                onLeaveDemo = model::leaveDemo,
                onSwitchToDemo = model::chooseDemo,
            ),
            onDismiss = { showMenu = false },
            calendar = if (session.isDemo) {
                null
            } else {
                CalendarMenu(calendarState, calendarSelection, calendarAccess, model.calendar::setCalendarVisible)
            },
            weather = if (session.isDemo) {
                null
            } else {
                WeatherMenu(
                    place = weatherPlace,
                    search = weatherSearch,
                    onQueryChange = model.weather::onQueryChange,
                    onSelect = model.weather::selectPlace,
                    onClear = model.weather::clearPlace,
                    onSearchReset = model.weather::resetSearch,
                )
            },
            focusWeather = focusWeatherSearch,
            onWeatherOptions = { showWeatherOptions = true },
        )
    }
    if (showWeatherOptions) {
        WeatherOptionsDialog(
            options = weatherOptions,
            onChange = model::setWeatherOptions,
            onDismiss = { showWeatherOptions = false },
        )
    }
    if (showAdd && editing) {
        AddTileSheet(
            hidden = hiddenNow,
            onAdd = { id ->
                model.showTile(id)
                scrollToEnd++
            },
            onDismiss = { showAdd = false },
        )
    }
    iconPickerFor?.let { id ->
        val tile = switchesNow[id]
        if (editing && tile != null) {
            IconPickerSheet(
                name = tile.name,
                chosen = iconChoices[id],
                automatic = tile.autoIcon,
                onChoose = { model.setTileIcon(id, it) },
                onDismiss = { iconPickerFor = null },
            )
        }
    }
    confirmRequest?.let { request ->
        AlarmConfirmDialog(
            request = request,
            onConfirm = {
                confirmRequest = null
                val on = OutputKey(request.id.module, request.id.relay) in state.outputsOn
                if (request.stillNeeded(on)) repository.toggleOutput(request.id.module, request.id.relay)
            },
            onDismiss = { confirmRequest = null },
        )
    }
    if (showReset && editing) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.edit_reset_title)) },
            text = { Text(stringResource(R.string.edit_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    model.resetLayout(state.config, columnsNow)
                }) { Text(stringResource(R.string.edit_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text(stringResource(R.string.edit_cancel)) } },
        )
    }
}

private fun TileModel.label(weather: String, agenda: String): String = when (this) {
    is WeatherTileModel -> weather
    is AgendaTileModel -> agenda
    is ZoneTileModel -> listOf(state.name, state.room).filter { it.isNotBlank() }.joinToString(", ")
    is SwitchTileModel -> listOf(name, room).filter { it.isNotBlank() }.joinToString(", ")
    is ShutterTileModel -> listOf(name, room).filter { it.isNotBlank() }.joinToString(", ")
}

/**
 * Nagłówek pulpitu: ikona domu, nazwa (jedna linia – dłuższa kończy się wielokropkiem) i plakietka „Demo” (znika, gdy
 * zabrakłoby miejsca na pełną nazwę), a przy prawej krawędzi zawsze widoczne „edytuj” i menu.
 */
@Composable
private fun Header(
    name: String,
    isDemo: Boolean,
    gap: Dp,
    compact: Boolean,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onMenu: () -> Unit,
) {
    val colors = DomikTheme.colors
    // Telefon: 20 sp (dolna granica z wytycznych), żeby typowa nazwa mieściła się bez wielokropka; tablet: 22 sp.
    val titleStyle = MaterialTheme.typography.titleLarge.let { if (compact) it.copy(fontSize = 20.sp, lineHeight = 26.sp) else it }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Prawy margines: glif ikony menu (12 dp od brzegu pola dotyku 48 dp) wypada na krawędzi kafelków.
            .padding(start = gap, end = gap - 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_home),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.accent),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        // Przyciski po prawej mają stałą szerokość i nie ustępują miejsca – to nazwa i plakietka dzielą resztę.
        TitleWithBadge(
            modifier = Modifier.weight(1f),
            title = {
                Text(
                    text = name,
                    style = titleStyle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            badge = if (isDemo) {
                {
                    Text(
                        text = stringResource(R.string.header_demo),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textOnTile,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(DomikShapes.chip)
                            .background(colors.tileOnEnd)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            } else {
                null
            },
        )
        if (canEdit) {
            IconButton(onClick = onEdit) {
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.header_edit),
                    tint = colors.textPrimary,
                )
            }
        }
        IconButton(onClick = onMenu) {
            Icon(
                painterResource(R.drawable.ic_menu),
                contentDescription = stringResource(R.string.header_menu),
                tint = colors.textPrimary,
            )
        }
    }
}

/**
 * Nagłówek trybu edycji: tytuł, „Przywróć układ domyślny”, „Dodaj”, „Gotowe”. Na tablecie wszystko w jednym rzędzie;
 * na wąskim ekranie (telefon, duża czcionka) tytuł dostaje własny rząd, a przyciski schodzą pod niego
 * ([EditHeaderLayout]).
 */
@Composable
private fun EditHeader(gap: Dp, onReset: () -> Unit, onAdd: () -> Unit, onDone: () -> Unit) {
    val colors = DomikTheme.colors
    EditHeaderLayout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = gap, end = gap, top = 8.dp, bottom = 4.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.edit_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        reset = {
            IconButton(onClick = onReset) {
                Icon(
                    painterResource(R.drawable.ic_restore),
                    contentDescription = stringResource(R.string.edit_reset),
                    tint = colors.textPrimary,
                )
            }
        },
        primary = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onAdd) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.edit_add), maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onDone) { Text(stringResource(R.string.edit_done), maxLines = 1) }
            }
        },
    )
}

@Composable
private fun LoadingPanel() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.dashboard_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = DomikTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessagePanel(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = DomikTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun ConfigFailure.messageRes(): Int = when (this) {
    ConfigFailure.NO_CONNECTION -> R.string.dashboard_config_no_connection
    ConfigFailure.UNREADABLE -> R.string.dashboard_config_unreadable
    ConfigFailure.OTHER -> R.string.dashboard_config_other
}

@Composable
private fun ConnectionNotice.text(): String = stringResource(
    when {
        problem == ConnectionProblem.CERTIFICATE_MISMATCH -> R.string.connection_wrong_device
        gaveUp && problem == ConnectionProblem.NO_RESPONSE -> R.string.connection_wrong_serial
        gaveUp -> R.string.connection_gave_up
        else -> R.string.connection_lost
    },
)

internal fun HomeEvent.message(resources: Resources): String = when (this) {
    is HomeEvent.CommandFailed -> resources.getString(
        when (kind) {
            CommandFailureKind.NOT_CONNECTED -> R.string.event_not_connected
            CommandFailureKind.NO_RESPONSE -> R.string.event_no_response
            CommandFailureKind.NOT_CONFIRMED -> R.string.event_not_confirmed
            CommandFailureKind.REJECTED -> R.string.event_rejected
        },
        subject,
    )
    is HomeEvent.CannotSwitchOn -> resources.getString(R.string.event_cannot_switch_on, subject)
}
