package app.domik.ui.dashboard

import androidx.compose.runtime.Immutable
import app.domik.integrations.emodul.EmodulPolicy
import java.time.Instant

/** Ikona kafelka eModul (zestaw ikon dobierany w warstwie UI; tu tylko rodzaj). */
enum class EmodulIcon { THERMOMETER, BOILER, WATER, VALVE, FAN, PUMP, RELAY, FUEL, FLAME, INFO, CLOCK, GENERIC }

/**
 * Gotowa do pokazania zawartość kafelka eModul (tylko odczyt): teksty, wartości i flagi, bez zależności od modelu
 * integracji. Tworzy ją czysty mapper ([EmodulTileMapper]).
 *
 * @property title nazwa kafelka
 * @property module nazwa modułu (kotła) – pomieszczenie na liście „Dodaj” i podpis, gdy modułów jest kilka
 * @property multiModule na koncie jest więcej niż jeden włączony moduł (dopiero wtedy nazwa modułu jest podpisem kafelka)
 * @property primary wartość główna (np. `65,0°`) albo `null`
 * @property primaryLabel podpis wartości głównej (rozmiar L), np. „Zał./wył.” – zwykle `null`
 * @property details pary (etykieta, wartość) w kolejności ważności
 * @property progress postęp 0…1 (otwarcie zaworu, zasobnik paliwa) albo `null`
 * @property active kafelek „pracuje” (grzeje, pompa włączona) – wyróżniony kolor
 * @property note dodatkowy podpis (np. „wg krzywej grzewczej”) albo `null`
 * @property dataTime czas danych = `tilesLastUpdate` sterownika (nie czas pobrania z chmury); `null` = nieznany
 * @property fetchedAtMillis kiedy dane pobrano z chmury (do oceny nieaktualności)
 * @property moduleOnline moduł jest online wg listy modułów
 * @property description opis dla czytnika ekranu (pełne jednostki, bez czasu danych – ten dopisuje kafelek)
 */
@Immutable
data class EmodulTileUi(
    val title: String,
    val module: String,
    val icon: EmodulIcon,
    val primary: String?,
    val primaryLabel: String?,
    val details: List<Pair<String, String>>,
    val progress: Float?,
    val active: Boolean,
    val note: String?,
    val description: String,
    val multiModule: Boolean = false,
    val dataTime: Instant? = null,
    val fetchedAtMillis: Long? = null,
    val moduleOnline: Boolean = true,
) {
    /** Dane starsze niż 15 min (sterownik milczy albo brak odczytu z chmury) albo moduł offline – kafelek jest przygaszony. */
    fun isStale(nowMillis: Long): Boolean =
        !moduleOnline || EmodulPolicy.isStale(dataTime, fetchedAtMillis, nowMillis)
}
