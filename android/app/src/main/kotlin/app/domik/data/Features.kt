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
     * Wyłączone w wydaniu 0.2.1 do czasu zmiany regulaminu (§ 4 ust. 3 i § 12 ust. 1: „nie zmienia zapisanej
     * konfiguracji sterownika") – decyzja właściciela 2026-10-25. Wcześniej włączone za zgodą właściciela
     * (2026-10-14, po fazie 1 pomiaru częstości). Wartość `false` blokuje tryb rzeczywisty: korekta działa wyłącznie
     * w trybie próbnym (decyzje tylko w dzienniku); wyjątek: sesja demo, gdzie zapis trafia do symulatora.
     * Wartość `true` + wyłączenie trybu próbnego przez użytkownika = realne zapisy.
     */
    const val SENSOR_CALIBRATION_WRITES: Boolean = false
}
