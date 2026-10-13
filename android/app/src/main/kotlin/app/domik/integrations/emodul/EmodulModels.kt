package app.domik.integrations.emodul

import java.time.Instant
import java.time.LocalDateTime

/** Identyfikator tekstu w globalnym słowniku eModul (`i18n`). Wartości ≤ 0 oznaczają brak tekstu. */
@JvmInline
value class TxtId(val value: Int) {
    val isPresent: Boolean get() = value > 0
}

/** Moduł (sterownik) z listy kont. Inne pola odpowiedzi (e-mail, telefon…) są świadomie ignorowane. */
data class EmodulModuleInfo(val udid: String, val name: String, val type: String, val version: String, val online: Boolean)

/** Dane modułu: kafelki, strefy (sterowniki strefowe), kolejność z eModul i czas ostatniej aktualizacji. */
data class EmodulSnapshot(
    val tiles: List<EmodulTile>,
    val zones: List<EmodulZone>,
    val order: List<Int>,
    val lastUpdate: Instant?,
)

/** Kafelek z `tiles` modułu. `parentId ≠ 0` = kafelek wewnątrz kontenera (typ 60). */
sealed interface EmodulTile {
    val id: Int
    val parentId: Int
    val menuId: Int
    val orderId: Int?
    val visible: Boolean

    /** Typ 1. [celsius] `null` = czujnik odłączony albo wartość poza zakresem. */
    data class TemperatureSensor(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val name: TxtId, val celsius: Double?, val working: Boolean, val battery: Int?, val signal: Int?,
    ) : EmodulTile

    /** Typ 2. */
    data class FireSensor(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val working: Boolean, val value: Int?,
    ) : EmodulTile

    /** Typ 3 (rozpalanie / wygaszanie); 0 / 1 = nieaktywne / aktywne (mapper), inne wartości nieznane – pokazywać surowo. */
    data class FiringUp(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val value: Int?,
    ) : EmodulTile

    /** Typ 6 (status z widżetami). Widżet `null` = niewidoczny (`unit == -1`) albo brak. */
    data class Widgets(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val icon: Int, val w1: EmodulWidget?, val w2: EmodulWidget?,
    ) : EmodulTile

    /** Typ 11. */
    data class Relay(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val name: TxtId, val on: Boolean, val icon: Int,
    ) : EmodulTile

    /** Typ 21. */
    data class Pump(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val name: TxtId, val on: Boolean, val number: Int,
    ) : EmodulTile

    /** Typ 22; [gear] w procentach. */
    data class Fan(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val on: Boolean, val gear: Int?, val number: Int,
    ) : EmodulTile

    /** Typ 23 ([builtIn] = true) albo 24. Temperatury w °C; [setC] całkowite °C. */
    data class Valve(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val builtIn: Boolean, val number: Int, val currentC: Double?, val returnC: Double?, val setC: Int?,
        val openingPercent: Int?, val pumpOn: Boolean, val working: Boolean,
    ) : EmodulTile

    /** Typ 31. */
    data class Fuel(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val percent: Int?, val hours: Int?,
    ) : EmodulTile

    /** Typ 32. */
    data class Disinfection(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val name: TxtId, val percent: Int?,
    ) : EmodulTile

    /** Typ 40 (informacja tekstowa): nagłówek i stan to identyfikatory słownika. */
    data class Text(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val header: TxtId, val status: TxtId,
    ) : EmodulTile

    /** Typ 41: zegar sterownika. */
    data class ControllerDate(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val dateTime: LocalDateTime?,
    ) : EmodulTile

    /** Typ 50. Identyfikator sterownika (`mainControllerId`) jest celowo pomijany. */
    data class Version(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val name: String, val version: String,
    ) : EmodulTile

    /** Typ 51. */
    data class PeripheralVersion(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val name: TxtId, val version: String,
    ) : EmodulTile

    /** Typ 60: grupa kafelków. */
    data class Container(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val name: TxtId,
    ) : EmodulTile

    /** Nieznany typ albo kafelek bez wymaganych pól. [description] to angielski opis z API (może być pusty). */
    data class Unknown(
        override val id: Int, override val parentId: Int, override val menuId: Int, override val orderId: Int?,
        override val visible: Boolean,
        val type: Int, val description: String,
    ) : EmodulTile
}

/** Widżet kafelka typu 6. [unit] = „value type” z OpenAPI (patrz [EmodulFormat]); [raw] to surowa liczba. */
data class EmodulWidget(val txtId: TxtId, val raw: Int, val unit: Int)

/** Strefa sterownika strefowego. Temperatury w °C; [heating] `null` = brak informacji. */
data class EmodulZone(
    val id: Int,
    val name: String,
    val currentC: Double?,
    val setC: Double?,
    val heating: Boolean?,
    val humidity: Int?,
    val visible: Boolean,
)

/**
 * Element menu sterownika (tylko odczyt). [kind] = pole `type` (0 grupa, 1 liczba, 2 liczba ×0,1, …, 30 krzywa
 * grzewcza); [format], [value], [min], [max] i [unitTxtId] pochodzą z `params` (brak → `null` / puste).
 */
data class EmodulMenuItem(
    val type: MenuType,
    val id: Int,
    val parentId: Int,
    val kind: Int,
    val access: Boolean,
    val txtId: TxtId,
    val format: Int?,
    val value: Int?,
    val min: Int?,
    val max: Int?,
    val unitTxtId: TxtId,
    val duringChange: Boolean,
)

data class EmodulMenu(val type: MenuType, val items: Map<Int, EmodulMenuItem>)
