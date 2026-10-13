package app.domik.integrations.emodul

import app.domik.integrations.weather.MiniJson
import app.domik.integrations.weather.WeatherParseException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Odpowiedź eModul nie jest poprawnym JSON-em albo nie ma oczekiwanego kształtu. Komunikat nie zawiera danych. */
class EmodulParseException(cause: Throwable? = null) : Exception("Niepoprawna odpowiedź eModul", cause)

/**
 * Parsowanie odpowiedzi eModul (lista modułów, dane modułu, menu, słownik) na model domenowy.
 * Nieznane pola są ignorowane; kafelek bez wymaganych pól staje się [EmodulTile.Unknown].
 */
object EmodulParser {
    /** @throws EmodulParseException odpowiedź nie jest tablicą modułów */
    fun parseModules(json: String): List<EmodulModuleInfo> {
        val root = parse(json)
        val list = (root as? List<*>) ?: ((root as? Map<*, *>)?.get("data") as? List<*>) ?: throw EmodulParseException()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val udid = (m["udid"] as? String)?.takeIf(EmodulUrls::isValidUdid) ?: return@mapNotNull null
            EmodulModuleInfo(
                udid = udid,
                name = (m["name"] as? String).orEmpty().trim(),
                type = (m["type"] as? String).orEmpty().trim(),
                version = (m["version"] as? String).orEmpty().trim(),
                online = m["moduleStatus"] == "active",
            )
        }
    }

    /** @throws EmodulParseException odpowiedź nie jest obiektem z danymi modułu */
    fun parseModule(json: String): EmodulSnapshot {
        var root = parse(json) as? Map<*, *> ?: throw EmodulParseException()
        if (root["tiles"] == null && root["data"] is Map<*, *>) root = root["data"] as Map<*, *>
        val tiles = (root["tiles"] as? List<*>).orEmpty().mapNotNull { parseTile(it as? Map<*, *>) }
        val zones = ((root["zones"] as? Map<*, *>)?.get("elements") as? List<*>).orEmpty().mapNotNull { parseZone(it as? Map<*, *>) }
        return EmodulSnapshot(
            tiles = tiles,
            zones = zones,
            order = parseOrder(root["tilesOrder"] as? String),
            lastUpdate = parseInstant(root["tilesLastUpdate"] as? String),
        )
    }

    /** Menu `MU`/`MI`; obsługuje też wariant bez obwiedni `status`/`data`. */
    fun parseMenu(json: String, type: MenuType): EmodulMenu {
        val root = parse(json) as? Map<*, *> ?: throw EmodulParseException()
        val data = root["data"] as? Map<*, *> ?: root
        val elements = data["elements"] as? List<*> ?: throw EmodulParseException()
        val items = LinkedHashMap<Int, EmodulMenuItem>()
        for (element in elements) {
            val e = element as? Map<*, *> ?: continue
            val id = e["id"].int() ?: continue
            val kind = e["type"].int() ?: continue
            val params = e["params"] as? Map<*, *>
            items[id] = EmodulMenuItem(
                type = type,
                id = id,
                parentId = e["parentId"].int() ?: 0,
                kind = kind,
                access = e["access"].flag(),
                txtId = TxtId(e["txtId"].int() ?: 0),
                format = params?.get("format").int(),
                value = params?.get("value").int(),
                min = params?.get("min").int(),
                max = params?.get("max").int(),
                unitTxtId = TxtId(params?.get("txtId").int() ?: 0),
                duringChange = e["duringChange"].flag(),
            )
        }
        return EmodulMenu(type, items)
    }

    /**
     * Słownik `i18n/{lang}`: `{"status":"success","data":{"<id>":"<tekst>"}}`. Z [ids] zwraca tylko podzbiór
     * (pełny słownik ma ~850 KB i nie jest przechowywany).
     */
    fun parseI18n(json: String, ids: Set<Int>? = null): Map<Int, String> {
        val root = parse(json) as? Map<*, *> ?: throw EmodulParseException()
        val data = root["data"] as? Map<*, *> ?: throw EmodulParseException()
        val result = HashMap<Int, String>()
        for ((key, value) in data) {
            val id = (key as? String)?.toIntOrNull() ?: continue
            if (ids != null && id !in ids) continue
            if (value is String) result[id] = value
        }
        return result
    }

    private fun parse(json: String): Any? = try {
        MiniJson.parse(json)
    } catch (e: WeatherParseException) {
        throw EmodulParseException(e)
    }

    private fun parseTile(t: Map<*, *>?): EmodulTile? {
        t ?: return null
        val id = t["id"].int() ?: return null
        val type = t["type"].int() ?: return null
        val parentId = t["parentId"].int() ?: 0
        val menuId = t["menuId"].int() ?: 0
        val orderId = t["orderId"].int()
        val visible = if (t.containsKey("visibility")) t["visibility"].flag() else true
        val p = t["params"] as? Map<*, *>
        val description = (p?.get("description") as? String).orEmpty().trim()
        val unknown = EmodulTile.Unknown(id, parentId, menuId, orderId, visible, type, description)
        p ?: return unknown
        fun has(vararg keys: String) = keys.all { p.containsKey(it) }
        return when (type) {
            1 -> if (!has("value")) unknown else EmodulTile.TemperatureSensor(
                id, parentId, menuId, orderId, visible,
                name = TxtId(p["txtId"].int() ?: 0),
                celsius = EmodulFormat.celsiusFromTenths(p["value"].int()),
                working = p["workingStatus"].flag(),
                battery = EmodulFormat.percent(p["batteryLevel"].int()),
                signal = EmodulFormat.percent(p["signalStrength"].int()),
            )
            2 -> if (!has("workingStatus")) unknown else EmodulTile.FireSensor(
                id, parentId, menuId, orderId, visible, working = p["workingStatus"].flag(), value = p["value"].int(),
            )
            3 -> if (!has("firingUp")) unknown else EmodulTile.FiringUp(
                id, parentId, menuId, orderId, visible, value = p["firingUp"].int(),
            )
            6 -> if (!has("widget1") && !has("widget2")) unknown else EmodulTile.Widgets(
                id, parentId, menuId, orderId, visible,
                icon = p["iconId"].int() ?: 0, w1 = widget(p["widget1"]), w2 = widget(p["widget2"]),
            )
            11 -> if (!has("workingStatus")) unknown else EmodulTile.Relay(
                id, parentId, menuId, orderId, visible,
                name = TxtId(p["txtId"].int() ?: 0), on = p["workingStatus"].flag(), icon = p["iconId"].int() ?: 0,
            )
            21 -> if (!has("workingStatus")) unknown else EmodulTile.Pump(
                id, parentId, menuId, orderId, visible,
                name = TxtId(p["txtId"].int() ?: 0), on = p["workingStatus"].flag(), number = p["pumpNumber"].int() ?: 0,
            )
            22 -> if (!has("workingStatus")) unknown else EmodulTile.Fan(
                id, parentId, menuId, orderId, visible,
                on = p["workingStatus"].flag(), gear = EmodulFormat.percent(p["gear"].int()), number = p["fanNumber"].int() ?: 0,
            )
            23, 24 -> if (!has("valveNumber")) unknown else EmodulTile.Valve(
                id, parentId, menuId, orderId, visible,
                builtIn = type == 23,
                number = p["valveNumber"].int() ?: 0,
                currentC = EmodulFormat.celsiusFromTenths(p["currentTemp"].int()),
                returnC = EmodulFormat.celsiusFromTenths(p["returnTemp"].int()),
                setC = EmodulFormat.setpointCelsius(p["setTemp"].int()),
                openingPercent = EmodulFormat.percent(p["openingPercentage"].int()),
                pumpOn = p["valvePump"].flag(),
                working = p["workingStatus"].flag(),
            )
            31 -> if (!has("percentage")) unknown else EmodulTile.Fuel(
                id, parentId, menuId, orderId, visible,
                percent = EmodulFormat.percent(p["percentage"].int()), hours = p["hours"].int()?.takeIf { it >= 0 },
            )
            32 -> if (!has("percentage")) unknown else EmodulTile.Disinfection(
                id, parentId, menuId, orderId, visible,
                name = TxtId(p["txtId"].int() ?: 0), percent = EmodulFormat.percent(p["percentage"].int()),
            )
            40 -> if (!has("headerId") && !has("statusId")) unknown else EmodulTile.Text(
                id, parentId, menuId, orderId, visible,
                header = TxtId(p["headerId"].int() ?: 0), status = TxtId(p["statusId"].int() ?: 0),
            )
            41 -> if (!has("year")) unknown else EmodulTile.ControllerDate(
                id, parentId, menuId, orderId, visible, dateTime = controllerDate(p),
            )
            50 -> if (p["version"] !is String) unknown else EmodulTile.Version(
                id, parentId, menuId, orderId, visible,
                name = (p["controllerName"] as? String).orEmpty().trim(), version = (p["version"] as String).trim(),
            )
            51 -> if (p["version"] !is String) unknown else EmodulTile.PeripheralVersion(
                id, parentId, menuId, orderId, visible,
                name = TxtId(p["txtId"].int() ?: 0), version = (p["version"] as String).trim(),
            )
            60 -> if (!has("txtId")) unknown else EmodulTile.Container(
                id, parentId, menuId, orderId, visible, name = TxtId(p["txtId"].int() ?: 0),
            )
            else -> unknown
        }
    }

    /** Widżet; `null` gdy brak, niepełny albo niewidoczny (`unit == -1`). */
    private fun widget(value: Any?): EmodulWidget? {
        val w = value as? Map<*, *> ?: return null
        val unit = w["unit"].int() ?: return null
        if (unit == -1) return null
        val raw = w["value"].int() ?: return null
        return EmodulWidget(TxtId(w["txtId"].int() ?: 0), raw, unit)
    }

    private fun controllerDate(p: Map<*, *>): LocalDateTime? = try {
        LocalDateTime.of(
            p["year"].int() ?: return null, p["month"].int() ?: return null, p["day"].int() ?: return null,
            p["hours"].int() ?: return null, p["minutes"].int() ?: return null,
        )
    } catch (_: DateTimeException) {
        null
    }

    private fun parseZone(z: Map<*, *>?): EmodulZone? {
        z ?: return null
        val zone = z["zone"] as? Map<*, *> ?: return null
        val id = zone["id"].int() ?: return null
        val flags = zone["flags"] as? Map<*, *>
        val relay = flags?.get("relayState")
        return EmodulZone(
            id = id,
            name = ((z["description"] as? Map<*, *>)?.get("name") as? String).orEmpty().trim(),
            currentC = EmodulFormat.celsiusFromTenths(zone["currentTemperature"].int()),
            setC = EmodulFormat.celsiusFromTenths(zone["setTemperature"].int()),
            heating = when (relay) {
                is String -> relay.equals("on", ignoreCase = true)
                is Boolean -> relay
                else -> null
            },
            humidity = zone["humidity"].int()?.takeIf { it in 1..100 },
            visible = if (zone.containsKey("visibility")) zone["visibility"].flag() else true,
        )
    }

    /** `id|flaga,id|flaga,…` (ostatni element pusty) → lista `id` w kolejności z eModul. */
    private fun parseOrder(text: String?): List<Int> =
        text.orEmpty().split(',').mapNotNull { it.substringBefore('|').trim().toIntOrNull() }

    /** `2026-10-13 17:37:24.733345+02` → [Instant]; błąd → `null`. */
    private fun parseInstant(text: String?): Instant? {
        text ?: return null
        val iso = text.trim().replace(' ', 'T').replace(Regex("([+-]\\d{2})$"), "$1:00")
        return try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (_: DateTimeException) {
            null
        }
    }

    /** Liczby z [MiniJson] to `Double`; całkowite tylko gdy bez części ułamkowej i w zakresie `Int`. */
    private fun Any?.int(): Int? {
        val d = this as? Double ?: return null
        return if (d == Math.floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }

    /** `true`, `"t"`, `"true"`, `"1"` albo liczba ≠ 0; reszta (w tym `null`) → `false`. */
    private fun Any?.flag(): Boolean = when (this) {
        is Boolean -> this
        is Double -> this != 0.0
        is String -> this == "t" || this.equals("true", ignoreCase = true) || this == "1"
        else -> false
    }
}
