package app.domik.integrations.calendar

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Czy aplikacja ma zgodę na odczyt kalendarza (`READ_CALENDAR`). */
fun hasCalendarPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

/**
 * Kalendarz systemowy przez `CalendarContract` – **tylko odczyt** (jedyne uprawnienie: `READ_CALENDAR`).
 * Kalendarz Google działa natywnie, o ile konto jest dodane na urządzeniu; aplikacja nie łączy się z żadnym serwerem.
 * Zapytania są blokujące – wołać poza wątkiem UI (robi to [CalendarModel]). Niczego nie zapisujemy ani nie logujemy.
 */
class ContentResolverCalendarSource(context: Context) : CalendarSource {
    private val context = context.applicationContext

    override suspend fun calendars(): List<CalendarInfo> {
        requireAccess()
        val result = ArrayList<CalendarInfo>()
        query(
            uri = CalendarContract.Calendars.CONTENT_URI,
            projection = CALENDAR_PROJECTION,
            selection = "${CalendarContract.Calendars.VISIBLE} = 1",
            sortOrder = "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, " +
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC, ${CalendarContract.Calendars._ID} ASC",
        ) { cursor ->
            result += CalendarInfo(
                id = cursor.getLong(0),
                accountName = cursor.getString(1).orEmpty(),
                displayName = cursor.getString(2).orEmpty(),
                color = if (cursor.isNull(3)) null else cursor.getInt(3),
            )
        }
        return result
    }

    override suspend fun instances(fromMillis: Long, toMillis: Long): List<CalendarInstance> {
        requireAccess()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMillis)
            ContentUris.appendId(it, toMillis)
        }.build()
        val result = ArrayList<CalendarInstance>()
        query(
            uri = uri,
            projection = INSTANCE_PROJECTION,
            selection = "${CalendarContract.Calendars.VISIBLE} = 1",
            sortOrder = "${CalendarContract.Instances.BEGIN} ASC",
        ) { cursor ->
            result += CalendarInstance(
                eventId = cursor.getLong(0),
                calendarId = cursor.getLong(1),
                title = cursor.getString(2),
                begin = cursor.getLong(3),
                end = cursor.getLong(4),
                allDay = cursor.getInt(5) != 0,
                color = if (cursor.isNull(6)) null else cursor.getInt(6),
                status = if (cursor.isNull(7)) CalendarStatus.NONE else cursor.getInt(7),
                selfAttendeeStatus = if (cursor.isNull(8)) CalendarStatus.NONE else cursor.getInt(8),
                calendarVisible = true, // zapytanie już wybiera tylko kalendarze widoczne
            )
        }
        return result
    }

    /** Obserwator zmian w dostawcy kalendarza; zamknięcie przepływu wyrejestrowuje go. */
    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = try {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            true
        } catch (_: SecurityException) {
            false
        }
        if (!registered) close()
        awaitClose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }

    private fun requireAccess() {
        if (!hasCalendarPermission(context)) throw CalendarAccessDeniedException()
    }

    private inline fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String,
        sortOrder: String,
        read: (Cursor) -> Unit,
    ) {
        val cursor = try {
            context.contentResolver.query(uri, projection, selection, null, sortOrder)
        } catch (_: SecurityException) {
            throw CalendarAccessDeniedException()
        }
        cursor?.use { while (it.moveToNext()) read(it) }
    }

    private companion object {
        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )

        // Kolejność kolumn = indeksy w odczycie powyżej.
        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DISPLAY_COLOR,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.SELF_ATTENDEE_STATUS,
        )
    }
}

/**
 * Otwiera wydarzenie [ref] w aplikacji kalendarza (`ACTION_VIEW` na `Events/<id>` z czasem instancji, żeby przy
 * wydarzeniu cyklicznym otworzyło się właściwe powtórzenie).
 * @return false, gdy na urządzeniu nie ma aplikacji, która to obsłuży
 */
fun openCalendarEvent(context: Context, ref: CalendarEventRef): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ref.eventId))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ref.beginMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ref.endMillis)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
