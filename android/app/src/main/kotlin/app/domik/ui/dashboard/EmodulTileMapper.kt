package app.domik.ui.dashboard

import app.domik.data.TileId
import app.domik.integrations.emodul.EmodulFormat
import app.domik.integrations.emodul.EmodulLinks
import app.domik.integrations.emodul.EmodulModuleData
import app.domik.integrations.emodul.EmodulState
import app.domik.integrations.emodul.EmodulTile
import app.domik.integrations.emodul.EmodulZone
import app.domik.integrations.emodul.FormattedValue
import app.domik.integrations.emodul.WidgetRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Etykiety i krótkie teksty kafelków eModul (zasoby `strings.xml`; w testach – tekst zastępczy). */
enum class EmodulLabel {
    SETPOINT, RETURN, OPENING, PUMP, ON, OFF, RUNNING, STOPPED, ACTIVE, INACTIVE, GEAR, BATTERY, SIGNAL, HUMIDITY,
    RUN_TIME, DATE, VERSION, TYPE, VALUE, BY_CURVE, ZONE_N,

    /** Czas danych: „wczoraj” (słowo do złożenia z godziną). */
    YESTERDAY,

    /** Czas danych na kafelku: „Odczyt 14:32” (arg: gotowy fragment dnia i godziny). */
    READING,

    /** Dla czytnika ekranu: „Ostatni odczyt o 14:32” (arg: godzina). */
    SPOKEN_AT,

    /** Dla czytnika ekranu: „Ostatni odczyt wczoraj o 14:32” / „… 12.10 o 14:32” (args: dzień, godzina). */
    SPOKEN_DATE,
}

/** Teksty kafelków eModul; [args] zależą od [EmodulLabel] (liczby i gotowe teksty). */
fun interface EmodulUiTexts {
    fun text(label: EmodulLabel, vararg args: Any): String
}

/**
 * Czas danych kafelka (`tilesLastUpdate` sterownika): zawsze godzina z podpisem – „Odczyt 14:32” (dziś),
 * „Odczyt wczoraj 14:32”, starszy „Odczyt 12.10 14:32”. Wersja [compact] (ciasny kafelek 1×1) pomija słowo „Odczyt”.
 * Dzień liczony w strefie [zone] względem [nowMillis]. Czyste funkcje.
 */
object EmodulDataAge {
    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d.MM")

    private enum class Day { TODAY, YESTERDAY, OLDER }

    private fun dayOf(local: java.time.ZonedDateTime, nowMillis: Long, zone: ZoneId): Day {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val date = local.toLocalDate()
        return when {
            !date.isBefore(today) -> Day.TODAY // także dane z „przyszłości” (rozjechany zegar)
            date == today.minusDays(1) -> Day.YESTERDAY
            else -> Day.OLDER
        }
    }

    /** Podpis pod wartością kafelka: „Odczyt 14:32”, „Odczyt wczoraj 14:32”, „Odczyt 12.10 14:32”; [compact] – bez słowa „Odczyt”. */
    fun label(dataTime: Instant, nowMillis: Long, zone: ZoneId, texts: EmodulUiTexts, compact: Boolean = false): String {
        val local = dataTime.atZone(zone)
        val time = HOUR_MINUTE.format(local)
        val part = when (dayOf(local, nowMillis, zone)) {
            Day.TODAY -> time
            Day.YESTERDAY -> "${texts.text(EmodulLabel.YESTERDAY)} $time"
            Day.OLDER -> "${DAY_MONTH.format(local)} $time"
        }
        return if (compact) part else texts.text(EmodulLabel.READING, part)
    }

    /** Wersja dla czytnika ekranu: „Ostatni odczyt o 14:32” (wcześniejszy dzień – z dniem). */
    fun spoken(dataTime: Instant, nowMillis: Long, zone: ZoneId, texts: EmodulUiTexts): String {
        val local = dataTime.atZone(zone)
        val time = HOUR_MINUTE.format(local)
        return when (dayOf(local, nowMillis, zone)) {
            Day.TODAY -> texts.text(EmodulLabel.SPOKEN_AT, time)
            Day.YESTERDAY -> texts.text(EmodulLabel.SPOKEN_DATE, texts.text(EmodulLabel.YESTERDAY), time)
            Day.OLDER -> texts.text(EmodulLabel.SPOKEN_DATE, DAY_MONTH.format(local), time)
        }
    }
}

/**
 * Mapper kafelków eModul (tylko odczyt): model integracji → [EmodulTileUi] wg tabeli z `notes/EMODUL.md` §7.5.
 * Czysty (bez Androida): teksty z [EmodulUiTexts] i słownika modułu, liczby wg [Locale]. Kafelki z wartością
 * zadaną pokazują aktualną **i** zadaną; niczego nie ustawiają.
 */
object EmodulTileMapper {
    /** txtId globalnego słownika TECH: „Temp. zadana CO” / „Temp. zadana CWU” – tylko do doboru ikony kafelka typu 6. */
    private const val TXT_BOILER_SETPOINT = 937
    private const val TXT_WATER_SETPOINT = 938

    /** Kafelki i strefy wszystkich włączonych modułów stanu [Ready][EmodulState.Ready]; inne stany – pusta mapa. */
    fun map(state: EmodulState, locale: Locale, ui: EmodulUiTexts): Map<TileId, EmodulTileUi> {
        val ready = state as? EmodulState.Ready ?: return emptyMap()
        val enabled = ready.modules.filter { it.enabled && it.snapshot != null }
        val multi = ready.modules.count { it.enabled } > 1
        return enabled.fold(emptyMap()) { acc, module -> acc + map(module, multi, locale, ui) }
    }

    /** Kafelki i strefy jednego modułu (po id kafelka). */
    fun map(module: EmodulModuleData, multiModule: Boolean, locale: Locale, ui: EmodulUiTexts): Map<TileId, EmodulTileUi> {
        val snapshot = module.snapshot ?: return emptyMap()
        val context = Context(module, multiModule, locale, ui)
        val result = LinkedHashMap<TileId, EmodulTileUi>()
        for (tile in snapshot.tiles) result[TileId.Emodul(module.slot, tile.id)] = context.tile(tile)
        for (zone in snapshot.zones) result[TileId.EmodulZone(module.slot, zone.id)] = context.zone(zone)
        return result
    }

    private class Context(
        val module: EmodulModuleData,
        val multiModule: Boolean,
        val locale: Locale,
        val ui: EmodulUiTexts,
    ) {
        private val texts = module.texts

        /** Wiersz szczegółów: [short] na kafelku, [spoken] w opisie dla czytnika ekranu. */
        private class Row(val label: String, val short: String, val spoken: String = short)

        private class Content(
            val title: String,
            val icon: EmodulIcon,
            val primary: FormattedValue? = null,
            val rows: List<Row> = emptyList(),
            val progress: Float? = null,
            val active: Boolean = false,
            val note: String? = null,
        )

        fun tile(tile: EmodulTile): EmodulTileUi = build(content(tile))

        fun zone(zone: EmodulZone): EmodulTileUi {
            val set = zone.setC?.let { temperature(it, 1) }
            val rows = listOfNotNull(
                set?.let { Row(ui.text(EmodulLabel.SETPOINT), it.short, it.spoken) },
                zone.humidity?.takeIf { it > 0 }?.let { percentRow(EmodulLabel.HUMIDITY, it) },
            )
            return build(
                Content(
                    title = zone.name.ifBlank { ui.text(EmodulLabel.ZONE_N, zone.id) },
                    icon = EmodulIcon.THERMOMETER,
                    primary = zone.currentC?.let { temperature(it, 1) } ?: plain(DASH),
                    rows = rows,
                    active = zone.heating == true,
                ),
            )
        }

        private fun content(tile: EmodulTile): Content = when (tile) {
            is EmodulTile.TemperatureSensor -> Content(
                title = texts.title(tile), icon = EmodulIcon.THERMOMETER,
                primary = tile.celsius?.let { temperature(it, 1) } ?: plain(DASH),
                rows = listOfNotNull(
                    tile.battery?.let { percentRow(EmodulLabel.BATTERY, it) },
                    tile.signal?.let { percentRow(EmodulLabel.SIGNAL, it) },
                ),
            )
            is EmodulTile.Widgets -> widgets(tile)
            is EmodulTile.Relay -> Content(
                title = texts.title(tile), icon = EmodulIcon.RELAY, primary = workingValue(tile.on), active = tile.on,
            )
            is EmodulTile.Pump -> Content(
                title = texts.title(tile), icon = EmodulIcon.PUMP, primary = workingValue(tile.on), active = tile.on,
            )
            is EmodulTile.Fan -> Content(
                title = texts.title(tile), icon = EmodulIcon.FAN, primary = workingValue(tile.on), active = tile.on,
                rows = listOfNotNull(EmodulFormat.percent(tile.gear)?.let { percentRow(EmodulLabel.GEAR, it) }),
            )
            is EmodulTile.Valve -> valve(tile)
            is EmodulTile.Fuel -> {
                val percent = EmodulFormat.percent(tile.percent)
                Content(
                    title = texts.title(tile), icon = EmodulIcon.FUEL,
                    primary = percent?.let { percentValue(it) } ?: plain(DASH),
                    rows = listOfNotNull(
                        tile.hours?.takeIf { it >= 0 }?.let {
                            Row(ui.text(EmodulLabel.RUN_TIME), "≈ $it h", "$it h")
                        },
                    ),
                    progress = percent?.let { it / 100f },
                )
            }
            is EmodulTile.Disinfection -> {
                val percent = EmodulFormat.percent(tile.percent)
                Content(
                    title = texts.title(tile), icon = EmodulIcon.WATER,
                    primary = percent?.let { percentValue(it) }, progress = percent?.let { it / 100f },
                )
            }
            is EmodulTile.Text -> Content(
                title = texts.title(tile), icon = EmodulIcon.INFO,
                primary = texts.text(tile.status)?.let { FormattedValue(it, it) },
            )
            is EmodulTile.ControllerDate -> Content(
                title = texts.title(tile), icon = EmodulIcon.CLOCK,
                primary = tile.dateTime?.let { plain(EmodulFormat.clock(it.hour * 60 + it.minute)) },
                rows = listOfNotNull(
                    tile.dateTime?.let { Row(ui.text(EmodulLabel.DATE), "%02d.%02d.%d".format(Locale.ROOT, it.dayOfMonth, it.monthValue, it.year)) },
                ),
            )
            is EmodulTile.Version -> Content(
                title = texts.title(tile), icon = EmodulIcon.INFO,
                primary = tile.name.takeIf { it.isNotBlank() }?.let { FormattedValue(it, it) },
                rows = listOfNotNull(tile.version.takeIf { it.isNotBlank() }?.let { Row(ui.text(EmodulLabel.VERSION), it) }),
            )
            is EmodulTile.PeripheralVersion -> Content(
                title = texts.title(tile), icon = EmodulIcon.INFO,
                primary = tile.version.takeIf { it.isNotBlank() }?.let { FormattedValue(it, it) },
            )
            is EmodulTile.FireSensor -> Content(
                title = texts.title(tile), icon = EmodulIcon.FLAME,
                primary = plain(ui.text(if (tile.working) EmodulLabel.ACTIVE else EmodulLabel.INACTIVE)),
                rows = listOfNotNull(tile.value?.let { Row(ui.text(EmodulLabel.VALUE), it.toString()) }),
                active = tile.working,
            )
            is EmodulTile.FiringUp -> Content(
                title = texts.title(tile), icon = EmodulIcon.FLAME,
                primary = tile.value?.let { plain(firingUpText(it)) },
            )
            is EmodulTile.Container -> Content(title = texts.title(tile), icon = EmodulIcon.GENERIC)
            is EmodulTile.Unknown -> Content(
                title = texts.title(tile), icon = EmodulIcon.GENERIC,
                rows = listOf(Row(ui.text(EmodulLabel.TYPE), tile.type.toString())),
            )
        }

        /**
         * Typ 6: tytuł i wartość główna z widżetu „aktualnego” (powiązany z menu widżet wartości zadanej jest
         * pokazany jako „zadana”; bez powiązania – pierwszy widżet z ważną wartością), pozostałe widżety jako szczegóły.
         */
        private fun widgets(tile: EmodulTile.Widgets): Content {
            val views = EmodulLinks.widgetViews(tile, module.menus, locale)
            val setpoint = views.firstOrNull { it.role == WidgetRole.SETPOINT }
            val current = views.firstOrNull { it.role == WidgetRole.CURRENT }
                ?: views.firstOrNull { it.role != WidgetRole.SETPOINT && it.text != null }
                ?: views.firstOrNull { it.role != WidgetRole.SETPOINT }
            val rows = buildList {
                if (setpoint != null) {
                    add(Row(ui.text(EmodulLabel.SETPOINT), setpoint.text?.short ?: DASH, setpoint.text?.spoken ?: DASH))
                }
                views.filter { it !== setpoint && it !== current }.forEach { view ->
                    val label = texts.text(view.widget.txtId).orEmpty()
                    add(Row(label, view.text?.short ?: DASH, view.text?.spoken ?: DASH))
                }
            }
            val setpointTxt = setpoint?.widget?.txtId?.value
            return Content(
                title = current?.let { texts.text(it.widget.txtId) } ?: texts.title(tile),
                icon = when (setpointTxt) {
                    TXT_WATER_SETPOINT -> EmodulIcon.WATER
                    TXT_BOILER_SETPOINT -> EmodulIcon.BOILER
                    else -> EmodulIcon.GENERIC
                },
                primary = current?.let { it.text ?: plain(DASH) },
                rows = rows,
            )
        }

        private fun valve(tile: EmodulTile.Valve): Content {
            val set = tile.setC?.let { EmodulFormat.temperature(it.toDouble(), 0, locale) }
            val returned = tile.returnC?.let { temperature(it, 1) }
            val opening = EmodulFormat.percent(tile.openingPercent)
            val curve = EmodulLinks.usesHeatingCurve(tile, module.menus)
            return Content(
                title = EmodulLinks.valveName(tile, module.menus, texts), icon = EmodulIcon.VALVE,
                primary = tile.currentC?.let { temperature(it, 1) } ?: plain(DASH),
                rows = listOfNotNull(
                    set?.let { Row(ui.text(EmodulLabel.SETPOINT), it.short, it.spoken) },
                    returned?.let { Row(ui.text(EmodulLabel.RETURN), it.short, it.spoken) },
                    opening?.let { percentRow(EmodulLabel.OPENING, it) },
                    Row(ui.text(EmodulLabel.PUMP), ui.text(if (tile.pumpOn) EmodulLabel.ON else EmodulLabel.OFF)),
                ),
                progress = opening?.let { it / 100f },
                note = if (curve) ui.text(EmodulLabel.BY_CURVE) else null,
            )
        }

        private fun build(content: Content): EmodulTileUi {
            val info = module.info
            val parts = buildList {
                add(content.title)
                content.primary?.let { add(it.spoken) }
                content.rows.forEach { add("${it.label} ${it.spoken}".trim()) }
                content.note?.let { add(it) }
            }
            val snapshot = module.snapshot
            return EmodulTileUi(
                title = content.title,
                module = info.name,
                icon = content.icon,
                primary = content.primary?.short,
                primaryLabel = null,
                details = content.rows.map { it.label to it.short },
                progress = content.progress?.coerceIn(0f, 1f),
                active = content.active,
                note = content.note,
                description = parts.joinToString(", "),
                multiModule = multiModule,
                dataTime = snapshot?.lastUpdate ?: module.fetchedAtMillis?.let(Instant::ofEpochMilli),
                fetchedAtMillis = module.fetchedAtMillis,
                moduleOnline = info.online,
            )
        }

        /**
         * Typ 3: API nie podaje tekstu statusu (kafelek łączy się z dialogiem „Wygaszanie” bez `txtId` stanu), więc
         * 0 = nieaktywne, 1 = aktywne; inna, nieznana wartość zostaje surowa (nic nie jest zgadywane).
         */
        private fun firingUpText(value: Int): String = when (value) {
            0 -> ui.text(EmodulLabel.INACTIVE)
            1 -> ui.text(EmodulLabel.ACTIVE)
            else -> value.toString()
        }

        private fun temperature(celsius: Double, decimals: Int) = EmodulFormat.temperature(celsius, decimals, locale)

        private fun percentValue(percent: Int) = plain("$percent %")

        private fun percentRow(label: EmodulLabel, percent: Int) = Row(ui.text(label), "$percent %")

        private fun workingValue(on: Boolean) = plain(ui.text(if (on) EmodulLabel.RUNNING else EmodulLabel.STOPPED))

        private fun plain(text: String) = FormattedValue(text, text)
    }

    private const val DASH = "—"
}
