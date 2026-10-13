package app.domik.data

/** Przełączniki funkcji, które łatwo wyłączyć w jednym miejscu. */
object Features {
    /**
     * Zmiana profilu ogrzewania (‹ ›) i włączanie wyłączonej strefy przyciskiem „+” (komenda 0x66).
     *
     * Zweryfikowana na żywym sterowniku (2026-10-10): sterownik sam odsyła 0x6B z nowym profilem
     * i zadaną tego profilu. Nazwa parametru w `DeviceCommands` zostaje „experimental…”, bo to jedna próba na
     * jednym sterowniku i jednym oprogramowaniu; po zebraniu doświadczeń od innych użytkowników można ją usunąć.
     * Wartość `false` ukrywa strzałki profilu i blokuje „+” na wyłączonej strefie.
     */
    const val PROFILE_SWITCHING: Boolean = true

    /**
     * Rzeczywisty zapis kalibracji czujników (0x132) przez moduł termometrów Xiaomi (`notes/THERMOSTATS.md` §3).
     *
     * Dopóki `false`, tryb rzeczywisty jest zablokowany: korekta działa wyłącznie w trybie próbnym (decyzje tylko
     * w dzienniku – pomiar fazy 1). Wyjątek: sesja demo, gdzie zapis trafia do symulatora. Zmiana na `true` dopiero
     * za zgodą właściciela, po pomiarze częstości kalibracji.
     */
    const val SENSOR_CALIBRATION_WRITES: Boolean = false
}
