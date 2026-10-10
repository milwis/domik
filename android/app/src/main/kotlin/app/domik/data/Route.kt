package app.domik.data

/** Główne ekrany aplikacji (bez biblioteki nawigacji – stan wystarcza). */
enum class Route {
    /** Regulamin i polityka prywatności do zaakceptowania. */
    TERMS,

    /** Wybór: własny sterownik albo tryb demo. */
    WELCOME,

    /** Formularz połączenia ze sterownikiem. */
    CONNECTION_SETTINGS,

    /** Pulpit. */
    DASHBOARD,
}

/**
 * Ekran startowy wynikający z zapisanych ustawień: najpierw zgoda na obowiązującą wersję regulaminu, potem wybór
 * trybu; tryb sterownika bez użytecznego profilu (brak adresu albo nieodczytywalny numer seryjny) wraca do formularza.
 */
fun resolveRoute(settings: AppSettings): Route = when {
    !settings.termsAccepted -> Route.TERMS
    settings.mode == AppMode.UNSET -> Route.WELCOME
    settings.mode == AppMode.DEMO -> Route.DASHBOARD
    settings.controller()?.serial != null -> Route.DASHBOARD
    else -> Route.CONNECTION_SETTINGS
}
