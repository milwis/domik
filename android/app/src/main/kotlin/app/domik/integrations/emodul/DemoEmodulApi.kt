package app.domik.integrations.emodul

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tryb demo: syntetyczny kocioł na pellet zamiast konta eModul. Odpowiedzi to JSON budowany w kodzie (nic nie
 * skopiowano z prawdziwego konta), parsowany tym samym [EmodulParser] co odpowiedzi serwera. Bez sieci i bez opóźnień.
 *
 * Wartości zależą od [clock] (deterministycznie): temperatura CO i CWU „oddychają” wokół wartości zadanej, podajnik
 * przełącza się co 30 s, `tilesLastUpdate` to bieżący czas (dane nigdy nie są nieaktualne).
 */
class DemoEmodulApi(private val clock: () -> Long = System::currentTimeMillis) : EmodulApi {
    override fun modules(key: EmodulKey): String =
        """[{"udid":"$UDID","name":"Kocioł na pellet (demo)","type":"pellet","version":"Demo: Kocioł P-1 (v.1.0.0)",""" +
            """"controllerStatus":"connected","moduleStatus":"active"}]"""

    override fun module(key: EmodulKey, udid: String): String {
        if (udid != UDID) throw IOException("Nieznany moduł demo")
        val now = clock()
        val boiler = tenths(64.0 + 2.0 * sin(2 * PI * now / 600_000.0))
        val hotWater = tenths(50.25 + 0.75 * sin(2 * PI * now / 420_000.0 + 1.0))
        val feederOn = (now / 30_000L) % 2L == 0L
        val tiles = listOf(
            tile(1, 40, 0, """"headerId":$TXT_STATE_HEADER,"statusId":$TXT_STATE_RUNNING,"iconId":3"""),
            tile(
                2, 6, MENU_BOILER_SET,
                """"iconId":0,"widget1":${widget(TXT_BOILER_SET, 0, 6)},"widget2":${widget(TXT_BOILER, boiler, 7)}""",
            ),
            tile(
                3, 6, MENU_WATER_SET,
                """"iconId":0,"widget1":${widget(TXT_WATER_SET, 0, 6)},"widget2":${widget(TXT_WATER, hotWater, 7)}""",
            ),
            tile(
                4, 23, MENU_VALVE_BUILT_IN,
                """"valveNumber":0,"currentTemp":345,"returnTemp":301,"setTemp":35,"openingPercentage":40,"valvePump":1,""" +
                    """"boilerProtection":0,"returnProtection":0,"setTempCorrection":0,"workingStatus":true,"txtId":-1""",
            ),
            tile(
                5, 24, MENU_VALVE_CURVE,
                """"valveNumber":1,"currentTemp":412,"returnTemp":365,"setTemp":42,"openingPercentage":55,"valvePump":1,""" +
                    """"boilerProtection":0,"returnProtection":0,"setTempCorrection":0,"workingStatus":true,"txtId":-1""",
            ),
            tile(6, 1, 0, """"txtId":$TXT_OUTDOOR,"value":84,"workingStatus":true"""),
            tile(7, 31, 0, """"percentage":64,"hours":41"""),
            tile(8, 11, 0, """"txtId":$TXT_FEEDER,"workingStatus":$feederOn,"iconId":17"""),
            tile(9, 11, 0, """"txtId":$TXT_BOILER_PUMP,"workingStatus":true,"iconId":9"""),
            tile(10, 22, 0, """"workingStatus":true,"gear":60,"fanNumber":1,"txtId":-1"""),
            tile(11, 50, 0, """"controllerName":"Kocioł P-1","version":"1.0.0","txtId":-1,"mainControllerId":1"""),
            tile(12, 32, 0, """"percentage":0,"txtId":0"""),
            // Rodzaje kafelków spoza listy domyślnej: w tym samym modelu co reszta, domyślnie ukryte.
            tile(13, 41, 0, clockParams(now)),
            tile(14, 21, 0, """"txtId":$TXT_CIRCULATION_PUMP,"workingStatus":false,"pumpNumber":1"""),
            tile(15, 2, 0, """"workingStatus":true,"value":187"""),
            tile(16, 3, 0, """"firingUp":0"""),
            tile(17, 51, 0, """"version":"1.0.0","txtId":$TXT_ROOM_MODULE"""),
            tile(18, 60, 0, """"txtId":$TXT_GROUP,"iconId":0"""),
        )
        return """{"zones":{"elements":[]},"tiles":[${tiles.joinToString(",")}],""" +
            """"tilesOrder":"${tiles.indices.joinToString("") { "${it + 1}|1," }}","tilesLastUpdate":"${updatedAt(now)}"}"""
    }

    override fun menu(key: EmodulKey, udid: String, type: MenuType): String {
        if (udid != UDID) throw IOException("Nieznany moduł demo")
        val items = when (type) {
            MenuType.MU -> listOf(
                group(type, 0, 0, TXT_MENU_USER),
                number(type, MENU_BOILER_SET, 0, TXT_BOILER_SET, value = 65, min = 53, max = 80),
                number(type, MENU_WATER_SET, 0, TXT_WATER_SET, value = 52, min = 40, max = 75),
            )
            MenuType.MI -> listOf(
                group(type, 0, 0, TXT_MENU_USER),
                group(type, 30, 0, TXT_MENU_INSTALLER),
                group(type, 31, 30, TXT_VALVE_BUILT_IN),
                group(type, 32, 30, TXT_VALVE_1),
                number(type, MENU_VALVE_BUILT_IN, 31, TXT_VALVE_SET, value = 35, min = 10, max = 50),
                element(type, MENU_VALVE_CURVE, 32, 30, TXT_HEATING_CURVE, "{}"),
            )
        }
        return """{"status":"success","data":{"elements":[${items.joinToString(",")}],"transaction_time":"demo"}}"""
    }

    override fun i18n(key: EmodulKey, language: String): String {
        val english = language.equals("en", ignoreCase = true)
        val entries = TEXTS.joinToString(",") { (id, pl, en) -> "\"$id\":\"${if (english) en else pl}\"" }
        return """{"status":"success","data":{$entries}}"""
    }

    private fun tile(id: Int, type: Int, menuId: Int, params: String): String =
        """{"id":$id,"parentId":0,"type":$type,"menuId":$menuId,"orderId":$id,"visibility":true,"params":{"description":"Demo","""+
            "$params}}"

    private fun widget(txtId: Int, value: Int, unit: Int): String =
        """{"txtId":$txtId,"value":$value,"unit":$unit,"type":0,"params":[0,0,0,0,0]}"""

    private fun clockParams(now: Long): String {
        val time = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
        return """"year":${time.year},"month":${time.monthValue},"day":${time.dayOfMonth},"dayId":${time.dayOfWeek.value},""" +
            """"hours":${time.hour},"minutes":${time.minute}"""
    }

    private fun updatedAt(now: Long): String = UPDATED_AT_FORMAT.format(Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))

    private fun tenths(celsius: Double): Int = (celsius * 10).roundToInt()

    private fun element(type: MenuType, id: Int, parentId: Int, kind: Int, txtId: Int, params: String): String =
        """{"menuType":"${type.name}","type":$kind,"id":$id,"parentId":$parentId,"access":true,"txtId":$txtId,""" +
            """"params":$params,"duringChange":null}"""

    private fun group(type: MenuType, id: Int, parentId: Int, txtId: Int): String = element(type, id, parentId, 0, txtId, "{}")

    private fun number(type: MenuType, id: Int, parentId: Int, txtId: Int, value: Int, min: Int, max: Int): String =
        element(
            type, id, parentId, 1, txtId,
            """{"format":1,"value":$value,"min":$min,"max":$max,"default":$value,"txtId":$TXT_UNIT_CELSIUS}""",
        )

    companion object {
        /** Identyfikator jedynego modułu demo (zgodny z walidacją [EmodulUrls.isValidUdid]). */
        const val UDID = "demo0000000000000000000000000001"

        private const val MENU_BOILER_SET = 100
        private const val MENU_WATER_SET = 101
        private const val MENU_VALVE_CURVE = 300
        private const val MENU_VALVE_BUILT_IN = 310

        private const val TXT_BOILER_SET = 937
        private const val TXT_WATER_SET = 938
        private const val TXT_UNIT_CELSIUS = 1035
        private const val TXT_STATE_HEADER = 9001
        private const val TXT_STATE_RUNNING = 9002
        private const val TXT_BOILER = 9010
        private const val TXT_WATER = 9011
        private const val TXT_OUTDOOR = 9020
        private const val TXT_FEEDER = 9030
        private const val TXT_BOILER_PUMP = 9031
        private const val TXT_CIRCULATION_PUMP = 9032
        private const val TXT_ROOM_MODULE = 9050
        private const val TXT_GROUP = 9060
        private const val TXT_MENU_USER = 9100
        private const val TXT_MENU_INSTALLER = 9101
        private const val TXT_VALVE_BUILT_IN = 9102
        private const val TXT_VALVE_1 = 9103
        private const val TXT_VALVE_SET = 9104
        private const val TXT_HEATING_CURVE = 9105

        private val UPDATED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSx")

        /** Słownik demo: (id, polski, angielski). Identyfikatory 937/938/1035 odpowiadają globalnemu słownikowi TECH. */
        private val TEXTS = listOf(
            Triple(TXT_BOILER_SET, "Temp. zadana CO", "Boiler set temp."),
            Triple(TXT_WATER_SET, "Temp. zadana CWU", "Hot water set temp."),
            Triple(TXT_UNIT_CELSIUS, "°C", "°C"),
            Triple(TXT_STATE_HEADER, "Stan sterownika", "Controller status"),
            Triple(TXT_STATE_RUNNING, "Praca", "Running"),
            Triple(TXT_BOILER, "Temperatura CO", "Boiler temperature"),
            Triple(TXT_WATER, "Temperatura CWU", "Hot water temperature"),
            Triple(TXT_OUTDOOR, "Temperatura zewnętrzna", "Outdoor temperature"),
            Triple(TXT_FEEDER, "Podajnik", "Feeder"),
            Triple(TXT_BOILER_PUMP, "Pompa CO", "Boiler pump"),
            Triple(TXT_CIRCULATION_PUMP, "Pompa cyrkulacyjna", "Circulation pump"),
            Triple(TXT_ROOM_MODULE, "Moduł pokojowy", "Room module"),
            Triple(TXT_GROUP, "Pozostałe", "Other"),
            Triple(TXT_MENU_USER, "Menu użytkownika", "User menu"),
            Triple(TXT_MENU_INSTALLER, "Menu instalatora", "Installer menu"),
            Triple(TXT_VALVE_BUILT_IN, "Zawór wbudowany", "Built-in valve"),
            Triple(TXT_VALVE_1, "Zawór 1", "Valve 1"),
            Triple(TXT_VALVE_SET, "Temp. zadana zaworu", "Valve set temp."),
            Triple(TXT_HEATING_CURVE, "Krzywa grzewcza", "Heating curve"),
        )
    }
}
