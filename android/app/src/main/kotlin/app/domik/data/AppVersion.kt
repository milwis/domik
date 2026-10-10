package app.domik.data

/** Wersja zainstalowanej aplikacji: [name] (versionName, np. „0.1.0”) i [code] (versionCode). */
data class AppVersion(val name: String, val code: Long)

/**
 * Składa [AppVersion] z danych pakietu; null, gdy system nie podał nazwy wersji (wtedy ekran „O aplikacji”
 * nie pokazuje wiersza z wersją, zamiast zgadywać).
 */
fun appVersionOf(versionName: String?, versionCode: Long): AppVersion? {
    val name = versionName?.trim().orEmpty()
    return if (name.isEmpty()) null else AppVersion(name, versionCode)
}
