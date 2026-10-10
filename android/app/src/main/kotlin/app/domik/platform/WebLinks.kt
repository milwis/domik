package app.domik.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.domik.data.isHttpsUrl

/**
 * Otwiera adres https w przeglądarce systemowej (`ACTION_VIEW`; nie wymaga uprawnień ani deklaracji `<queries>`,
 * bo nie pytamy systemu o dostępne aplikacje, tylko łapiemy brak odbiorcy). Zwraca false, gdy adres nie jest
 * adresem https albo żadna aplikacja nie potrafi go otworzyć – wywołujący pokazuje wtedy komunikat.
 */
fun openWebUrl(context: Context, url: String): Boolean {
    if (!isHttpsUrl(url)) return false
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
