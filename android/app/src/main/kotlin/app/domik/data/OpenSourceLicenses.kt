package app.domik.data

/** Licencje składników wymienionych na ekranie „O aplikacji”. */
enum class LicenseKind(val url: String) {
    Apache2(ProjectLinks.LICENSE_APACHE_2),
    CcBy4(ProjectLinks.LICENSE_CC_BY_4),
}

/** Składniki zewnętrzne, które trafiają do aplikacji (biblioteki, ikony) albo z których korzysta (dane pogodowe). */
enum class OpenSourceComponent {
    Kotlin,
    KotlinxCoroutines,
    AndroidX,
    JetpackCompose,
    Material3,
    MaterialIcons,
    OpenMeteo,
}

/**
 * Pozycja listy licencji.
 *
 * @property link strona składnika, jeśli ma być klikalna (null = tylko tekst)
 * @property hasNoticeText składnik ma dołączoną informację o licencji (res/raw/notice_material_icons.txt),
 *   którą ekran pokazuje po stuknięciu
 */
data class OpenSourceItem(
    val component: OpenSourceComponent,
    val license: LicenseKind,
    val link: String? = null,
    val hasNoticeText: Boolean = false,
)

/**
 * Lista składników zewnętrznych pokazywana w „O aplikacji”. Odpowiada `android/gradle/libs.versions.toml`,
 * plikom `build.gradle.kts` modułów oraz `NOTICE.md` (narzędzia budowania i testów, które nie trafiają do APK,
 * nie są tu wymieniane). Przy dodaniu biblioteki do aplikacji dopisać ją tutaj – pilnuje tego `OpenSourceLicensesTest`.
 */
object OpenSourceLicenses {
    val items: List<OpenSourceItem> = listOf(
        OpenSourceItem(OpenSourceComponent.Kotlin, LicenseKind.Apache2),
        OpenSourceItem(OpenSourceComponent.KotlinxCoroutines, LicenseKind.Apache2),
        OpenSourceItem(OpenSourceComponent.AndroidX, LicenseKind.Apache2),
        OpenSourceItem(OpenSourceComponent.JetpackCompose, LicenseKind.Apache2),
        OpenSourceItem(OpenSourceComponent.Material3, LicenseKind.Apache2),
        OpenSourceItem(OpenSourceComponent.MaterialIcons, LicenseKind.Apache2, hasNoticeText = true),
        OpenSourceItem(OpenSourceComponent.OpenMeteo, LicenseKind.CcBy4, link = ProjectLinks.OPEN_METEO),
    )
}

/**
 * Układa tekst informacji o licencji do czytania w wąskim oknie: akapity (rozdzielone pustymi wierszami)
 * zlewa w ciągły tekst, czyli usuwa sztuczne łamanie wierszy i wcięcia z pliku (treść bez zmian), oraz pomija
 * wiersze samych znaków „=” (podkreślenie nagłówka).
 */
fun reflowNoticeText(raw: String): String =
    raw.replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { block ->
            block.lines()
                .map { it.trim() }
                .filter { line -> line.isNotEmpty() && !line.all { it == '=' } }
                .joinToString(" ")
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
