package app.domik.data

/**
 * Adresy projektu i licencji używane w aplikacji – jedyne miejsce, w którym występują (ekran akceptacji
 * regulaminu, „O aplikacji”). Wszystkie to adresy https; aplikacja otwiera je w przeglądarce systemowej.
 */
object ProjectLinks {
    const val REPOSITORY = "https://github.com/milwis/domik"
    const val RELEASES = "$REPOSITORY/releases"
    const val ISSUES = "$REPOSITORY/issues"
    const val SECURITY_ADVISORY = "$REPOSITORY/security/advisories/new"

    /** Katalog `legal/` w repozytorium (regulamin, polityka prywatności, wersje angielskie). */
    const val LEGAL_DIR = "$REPOSITORY/tree/main/legal"
    const val TERMS = "$REPOSITORY/blob/main/legal/regulamin.md"
    const val PRIVACY_POLICY = "$REPOSITORY/blob/main/legal/polityka-prywatnosci.md"
    const val LICENSE_MIT = "$REPOSITORY/blob/main/LICENSE"

    const val OPEN_METEO = "https://open-meteo.com"
    const val LICENSE_APACHE_2 = "https://www.apache.org/licenses/LICENSE-2.0"
    const val LICENSE_CC_BY_4 = "https://creativecommons.org/licenses/by/4.0/"

    /** Adresy w repozytorium projektu (wszystkie zaczynają się od [REPOSITORY]). */
    val repositoryLinks: List<String> = listOf(
        REPOSITORY, RELEASES, ISSUES, SECURITY_ADVISORY, LEGAL_DIR, TERMS, PRIVACY_POLICY, LICENSE_MIT,
    )

    /** Pozostałe adresy: strony licencji i dostawcy danych pogodowych. */
    val externalLinks: List<String> = listOf(OPEN_METEO, LICENSE_APACHE_2, LICENSE_CC_BY_4)

    /**
     * Adres w postaci, w jakiej pokazujemy go w tekście: bez „https://” i bez końcowego ukośnika
     * (`github.com/milwis/domik/releases`).
     */
    fun displayAddress(url: String): String = url.removePrefix("https://").removeSuffix("/")
}

/** Czy [url] da się bezpiecznie przekazać przeglądarce: https, bez spacji i znaków sterujących. */
fun isHttpsUrl(url: String): Boolean =
    url.startsWith("https://") && url.length > "https://".length && url.none { it.isWhitespace() || it.isISOControl() }
