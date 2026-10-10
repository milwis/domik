package app.domik.data

/**
 * Ustawienia trybu tabletu ściennego (menu → „Tablet ścienny”). Zapis i odczyt: [AppSettings.kiosk].
 * Są to ustawienia urządzenia, nie profilu sterownika – nie znikają po „Usuń sterownik”.
 *
 * @property keepScreenOnWhenPowered ekran nie przygasa i nie gaśnie, dopóki Domik jest na pierwszym planie
 *   **i** tablet jest podłączony do zasilania (na baterii obowiązuje zwykłe wygaszanie systemu)
 * @property fullscreen tryb pełnoekranowy: paski systemowe ukryte, pojawiają się na chwilę po przesunięciu od krawędzi
 */
data class KioskSettings(
    val keepScreenOnWhenPowered: Boolean = true,
    val fullscreen: Boolean = true,
) {
    /**
     * Czy okno ma mieć ustawioną flagę „ekran zawsze włączony”.
     * @param powered tablet jest podłączony do zasilania (ładowarka, USB, indukcyjnie, stacja dokująca)
     * @param visible Domik jest widoczny (aktywność uruchomiona)
     */
    fun keepScreenOn(powered: Boolean, visible: Boolean): Boolean = keepScreenOnWhenPowered && powered && visible
}
