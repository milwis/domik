package app.domik.platform

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import app.domik.data.KioskPlatform

/**
 * [KioskPlatform] na `PackageManager` (alias HOME), `ActivityManager` (stan przypięcia) i metodach aktywności
 * (`startLockTask` / `stopLockTask`). Bez uprawnień administratora urządzenia – przypinanie wymaga zgody
 * użytkownika w systemie. Instancja trzyma aktywność, więc żyje tylko tak długo jak ona.
 */
class AndroidKioskPlatform(private val activity: Activity) : KioskPlatform {
    private val packageManager = activity.packageManager
    private val homeAlias = ComponentName(activity.packageName, HOME_ALIAS_CLASS)

    override var homeAliasEnabled: Boolean
        get() = packageManager.getComponentEnabledSetting(homeAlias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        set(value) {
            packageManager.setComponentEnabledSetting(
                homeAlias,
                if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

    override val isDefaultHome: Boolean
        get() {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            // Z MATCH_DEFAULT_ONLY dostajemy wybraną aplikację ekranu głównego albo systemowy wybieracz ("android").
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            }
            return resolved?.activityInfo?.packageName == activity.packageName
        }

    override fun openHomeSettings(): Boolean {
        // Ustawienia „Domyślne aplikacje → Ekran główny”; gdy producent ich nie udostępnia – ogólne ustawienia.
        for (action in listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                activity.startActivity(Intent(action))
                return true
            } catch (_: ActivityNotFoundException) {
                // następna próba
            } catch (_: SecurityException) {
                // następna próba
            }
        }
        return false
    }

    override val isPinned: Boolean
        get() {
            val manager = activity.getSystemService(ActivityManager::class.java) ?: return false
            return manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        }

    override val pinningEnabledInSystem: Boolean?
        get() {
            // „Przypinanie ekranu” w ustawieniach systemu; klucz nie jest publiczny, więc brak wartości = nie wiadomo.
            val resolver = activity.contentResolver
            val value = try {
                Settings.System.getInt(resolver, LOCK_TO_APP_ENABLED, UNKNOWN).takeIf { it != UNKNOWN }
                    ?: Settings.Secure.getInt(resolver, LOCK_TO_APP_ENABLED, UNKNOWN).takeIf { it != UNKNOWN }
            } catch (_: SecurityException) {
                null
            }
            return value?.let { it != 0 }
        }

    override fun startPinning(): Boolean = try {
        activity.startLockTask()
        true
    } catch (_: RuntimeException) {
        false
    }

    override fun stopPinning(): Boolean = try {
        activity.stopLockTask()
        // Odpięcie następuje asynchronicznie, a system przy odmowie tylko zapisuje błąd w logu – sprawdzamy stan.
        true
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        /** Pełna nazwa klasy aliasu z manifestu (przestrzeń nazw aplikacji, niezależna od applicationId). */
        const val HOME_ALIAS_CLASS = "app.domik.HomeAlias"
        const val LOCK_TO_APP_ENABLED = "lock_to_app_enabled"
        const val UNKNOWN = -1
    }
}
