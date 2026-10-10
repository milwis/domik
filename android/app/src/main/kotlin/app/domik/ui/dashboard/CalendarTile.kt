package app.domik.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.R
import app.domik.integrations.calendar.CalendarEventRef
import app.domik.integrations.calendar.CalendarModel
import app.domik.integrations.calendar.CalendarState
import app.domik.integrations.calendar.hasCalendarPermission
import app.domik.integrations.calendar.isCalendarAccessBlocked
import app.domik.integrations.calendar.openCalendarEvent
import app.domik.ui.components.CalendarAgendaTile
import app.domik.ui.components.CalendarMessageTile
import app.domik.ui.time.rememberSystemZone
import java.time.LocalDateTime

/**
 * Zgoda na odczyt kalendarza (`READ_CALENDAR`) w interfejsie. Pytamy dopiero po stuknięciu przycisku
 * ([requestOrOpenSettings]) – nigdy przy starcie. Po trwałej odmowie ([blocked]) przycisk prowadzi do ustawień aplikacji.
 */
@Stable
class CalendarAccess internal constructor(
    val granted: Boolean,
    val blocked: Boolean,
    private val ask: () -> Unit,
    private val openSettings: () -> Unit,
) {
    fun requestOrOpenSettings() = if (blocked) openSettings() else ask()
}

/** Stan zgody; sprawdzany ponownie po każdym powrocie na pierwszy plan (użytkownik mógł ją zmienić w ustawieniach). */
@Composable
fun rememberCalendarAccess(): CalendarAccess {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCalendarPermission(context)) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        val activity = context.findActivity()
        blocked = activity != null && isCalendarAccessBlocked(
            granted = result,
            shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                android.Manifest.permission.READ_CALENDAR,
            ),
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasCalendarPermission(context)
        if (granted) blocked = false
    }
    return remember(granted, blocked, launcher, context) {
        CalendarAccess(
            granted = granted,
            blocked = blocked,
            ask = { launcher.launch(android.Manifest.permission.READ_CALENDAR) },
            openSettings = { openAppSettings(context) },
        )
    }
}

/**
 * Kafelek kalendarza na pulpicie.
 *  - tryb demo: dane przykładowe, bez zgody i bez dostępu do kalendarza;
 *  - tryb sterownika: prawdziwy kalendarz z urządzenia; bez zgody – krótki tekst i przycisk „Pokaż mój kalendarz”.
 * Dane odświeżają się: o północy (zmiana [now].toLocalDate()), po zmianie strefy czasu, po powrocie aplikacji
 * na pierwszy plan i przy zmianach w kalendarzu ([CalendarModel]).
 */
@Composable
fun CalendarTile(
    calendar: CalendarModel,
    access: CalendarAccess,
    isDemo: Boolean,
    now: LocalDateTime,
    modifier: Modifier = Modifier,
) {
    if (isDemo) {
        val agenda = remember(now.toLocalDate()) { PlaceholderData.agenda(now) }
        CalendarAgendaTile(days = agenda, now = now, modifier = modifier)
        return
    }

    val zone by rememberSystemZone()
    val today = now.toLocalDate()
    LaunchedEffect(access.granted, today, zone) {
        if (access.granted) calendar.refresh(today, zone)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (access.granted) calendar.refresh(today, zone)
    }

    val state by calendar.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noApp = stringResource(R.string.calendar_no_app)
    val current = state
    when {
        !access.granted || current is CalendarState.NoAccess -> CalendarMessageTile(
            message = stringResource(if (access.blocked) R.string.calendar_access_blocked else R.string.calendar_access_message),
            actionLabel = stringResource(if (access.blocked) R.string.calendar_open_settings else R.string.calendar_show_mine),
            onAction = access::requestOrOpenSettings,
            modifier = modifier,
        )
        current is CalendarState.Ready -> CalendarAgendaTile(
            days = current.days,
            now = now,
            modifier = modifier,
            onEventClick = { id ->
                val ref = CalendarEventRef.decode(id)
                if (ref == null || !openCalendarEvent(context, ref)) {
                    Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
                }
            },
        )
        current is CalendarState.Unavailable -> CalendarMessageTile(
            message = stringResource(R.string.calendar_unavailable),
            actionLabel = stringResource(R.string.dashboard_retry),
            onAction = calendar::retry,
            modifier = modifier,
        )
        else -> CalendarMessageTile(message = stringResource(R.string.calendar_loading), loading = true, modifier = modifier)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // Brak ekranu ustawień aplikacji (nietypowe urządzenie) – nic więcej nie da się zrobić.
    }
}
