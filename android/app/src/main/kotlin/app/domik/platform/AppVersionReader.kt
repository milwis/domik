package app.domik.platform

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.domik.data.AppVersion
import app.domik.data.appVersionOf

/**
 * Wersja tej aplikacji odczytana z systemu (versionName i versionCode z `build.gradle.kts`, bez przepisywania
 * ich w kodzie); null, gdy systemu nie da się zapytać o własny pakiet.
 */
fun readAppVersion(context: Context): AppVersion? {
    val info = try {
        val packageManager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    } catch (_: RuntimeException) {
        return null
    }
    return info.toAppVersion()
}

private fun PackageInfo.toAppVersion(): AppVersion? =
    appVersionOf(versionName, PackageInfoCompat.getLongVersionCode(this))
