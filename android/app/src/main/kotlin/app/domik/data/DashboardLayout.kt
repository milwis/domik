package app.domik.data

import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Output
import app.domik.deimic.config.OutputKind

/**
 * Rozmiar kafelka w komórkach siatki: [cols] kolumn × [rows] wierszy. Dowolne
 * wartości od 1×1; które są dozwolone, mówi rodzaj kafelka ([TileKind.allows]). Szerokość zapisana w układzie może być
 * większa niż liczba kolumn bieżącej siatki (np. po obrocie tabletu) – siatka ją wtedy zwęża przy układaniu.
 *
 * Zapis tekstowy: `2x4` ([code]); [parse] czyta też dawne kody liter z zapisów v2/v3 (`s`, `m`, `l`, `xl`, `c`).
 */
data class TileSize(val cols: Int, val rows: Int) {
    init {
        require(cols >= 1 && rows >= 1) { "tile size must be at least 1×1: ${cols}x$rows" }
    }

    val code: String get() = "${cols}x$rows"

    override fun toString(): String = code

    companion object {
        /** Najszersza siatka (tablet poziomo, `GridSpec.COLUMNS_TABLET_LANDSCAPE`) = najszerszy zapisywany kafelek. */
        const val MAX_COLS = 8

        val S = TileSize(1, 1)
        val M = TileSize(2, 1)
        val L = TileSize(2, 2)
        val XL = TileSize(4, 2)

        /**
         * Dawne kody rozmiarów (zapis v2/v3). `c` („kolumna”: przyklejona do lewej krawędzi, do dołu ekranu) nie ma
         * już odpowiednika – staje się wysokim kafelkiem 2×4.
         */
        private val LEGACY = mapOf("s" to S, "m" to M, "l" to L, "xl" to XL, "c" to TileSize(2, 4))

        /** `2x4` (zapis v4) albo dawny kod litery; `null` = nieznany zapis. */
        fun parse(code: String): TileSize? {
            LEGACY[code]?.let { return it }
            val parts = code.split('x')
            if (parts.size != 2) return null
            val cols = parts[0].toIntOrNull() ?: return null
            val rows = parts[1].toIntOrNull() ?: return null
            if (cols < 1 || rows < 1 || cols > 99 || rows > 99) return null
            return TileSize(cols, rows)
        }
    }
}

/**
 * Rodzaj kafelka i jego dozwolone rozmiary. Dwa typy reguł:
 *  - **lista stała** ([fixedSizes]): tylko te rozmiary (ogrzewanie, przełączniki, rolety);
 *  - **dowolny rozmiar** w zakresie od [minSize] do [TileSize.MAX_COLS] × [maxRows] (kalendarz, pogoda) – szerokość
 *    i tak jest ograniczana do liczby kolumn bieżącej siatki.
 *
 * [presets] to rozmiary, po których przełącza przycisk „rozmiar” (alternatywa dla przeciągania rogu, także dla
 * czytników ekranu), [defaultSize] – rozmiar nowego kafelka. Nowy rodzaj = nowy wpis tutaj + nowy podtyp [TileId].
 *
 * Roleta ([SHUTTER]) ma tylko 2×1: trzy przyciski ↑ ■ ↓ po co najmniej 48 dp nie mieszczą się na kwadracie 1×1,
 * a wyższa karta nic nie dodaje (decyzja użytkownika 2026-10-10). Ogrzewanie: 2×1 i 2×2; przełączniki: 1×1 i 2×1.
 */
enum class TileKind(
    private val fixedSizes: List<TileSize>?,
    val minSize: TileSize,
    val maxRows: Int,
    val defaultSize: TileSize,
    val presets: List<TileSize>,
) {
    WEATHER(null, TileSize.S, maxRows = 2, TileSize.M, listOf(TileSize.S, TileSize.M, TileSize.L, TileSize.XL)),
    AGENDA(null, TileSize.L, maxRows = 8, TileSize.XL, listOf(TileSize.L, TileSize.XL, TileSize(2, 4), TileSize(4, 4))),
    ZONE(listOf(TileSize.M, TileSize.L), TileSize.M, maxRows = 2, TileSize.L, listOf(TileSize.M, TileSize.L)),
    SWITCH(listOf(TileSize.S, TileSize.M), TileSize.S, maxRows = 1, TileSize.S, listOf(TileSize.S, TileSize.M)),
    SHUTTER(listOf(TileSize.M), TileSize.M, maxRows = 1, TileSize.M, listOf(TileSize.M)),
    ;

    /** Czy rozmiar jest dowolny (w zakresie), a nie z listy. */
    val freeSize: Boolean get() = fixedSizes == null

    /**
     * Czy kafelek ma własne ustawienia (okno otwierane z trybu edycji – dotknięciem kafelka albo przyciskiem ⚙).
     * Na razie tylko pogoda (opcje szczegółów); nowy rodzaj z ustawieniami = dopisać tutaj i obsłużyć w `configure`.
     */
    val configurable: Boolean get() = this == WEATHER

    /** Czy kafelek w ogóle można zmieniać (więcej niż jeden rozmiar). */
    val resizable: Boolean get() = fixedSizes == null || fixedSizes.size > 1

    /** Czy [size] jest dozwolony (bez względu na liczbę kolumn bieżącej siatki – szerokość do [TileSize.MAX_COLS]). */
    fun allows(size: TileSize): Boolean = fixedSizes?.contains(size)
        ?: (size.cols in minSize.cols..TileSize.MAX_COLS && size.rows in minSize.rows..maxRows)

    /**
     * Najbliższy dozwolony rozmiar dla rozmiaru „z palca” [cols] × [rows] (ułamki komórek, np. z przeciągania rogu)
     * w siatce o [columns] kolumnach (szerokość nie większa niż siatka). Rozmiar dowolny: zaokrąglenie i obcięcie do
     * zakresu; lista stała: rozmiar o najmniejszej odległości (przy remisie – wcześniejszy na liście).
     */
    fun nearest(cols: Float, rows: Float, columns: Int = TileSize.MAX_COLS): TileSize {
        val widest = columns.coerceIn(1, TileSize.MAX_COLS)
        val fixed = fixedSizes
        if (fixed == null) {
            val maxCols = maxOf(widest, minSize.cols)
            val c = Math.round(cols).coerceIn(minSize.cols, maxCols)
            val r = Math.round(rows).coerceIn(minSize.rows, maxRows)
            return TileSize(c, r)
        }
        val fitting = fixed.filter { it.cols <= widest }.ifEmpty { fixed }
        return fitting.minBy { (it.cols - cols) * (it.cols - cols) + (it.rows - rows) * (it.rows - rows) }
    }

    /** [size] doprowadzony do dozwolonego (migracja starych zapisów): dozwolony zostaje, inny – najbliższy dozwolony. */
    fun coerce(size: TileSize): TileSize =
        if (allows(size)) size else nearest(size.cols.toFloat(), size.rows.toFloat())

    /**
     * Następny rozmiar przycisku „rozmiar” po [current] w siatce o [columns] kolumnach: kolejny z [presets] (zawijanie),
     * z pominięciem szerszych niż siatka. Rozmiar spoza listy (ustawiony przeciąganiem) przechodzi na pierwszy z listy
     * większy od niego (powierzchnią), a gdy takiego nie ma – na pierwszy. `null` = nie ma innego rozmiaru.
     */
    fun nextPreset(current: TileSize, columns: Int = TileSize.MAX_COLS): TileSize? {
        val options = presets.filter { it.cols <= columns }.ifEmpty { presets }
        val index = options.indexOf(current)
        val next = if (index >= 0) {
            options[(index + 1) % options.size]
        } else {
            options.firstOrNull { it.cols * it.rows > current.cols * current.rows } ?: options.first()
        }
        return next.takeIf { it != current }
    }
}

/** Identyfikator kafelka pulpitu – stabilny między uruchomieniami (zapis układu, klucze listy). */
sealed interface TileId {
    /** Krótki zapis tekstowy do pamięci ustawień. */
    val code: String

    val kind: TileKind

    data object Weather : TileId {
        override val code: String = "w"
        override val kind: TileKind = TileKind.WEATHER
    }

    data object Agenda : TileId {
        override val code: String = "a"
        override val kind: TileKind = TileKind.AGENDA
    }

    /** Strefa ogrzewania: moduł i `output_nr`. */
    data class Zone(val module: Int, val outputNr: Int) : TileId {
        override val code: String get() = "z:$module:$outputNr"
        override val kind: TileKind get() = TileKind.ZONE
    }

    /** Przełącznik (światło, gniazdko, LED, ściemniacz on/off): moduł i `output_relay`. */
    data class Switch(val module: Int, val relay: Int) : TileId {
        override val code: String get() = "s:$module:$relay"
        override val kind: TileKind get() = TileKind.SWITCH
    }

    /** Roleta: moduł i numer rolety (`output_nr`, nie `output_relay`, którym sterowniki adresują światła). */
    data class Shutter(val module: Int, val outputNr: Int) : TileId {
        override val code: String get() = "r:$module:$outputNr"
        override val kind: TileKind get() = TileKind.SHUTTER
    }

    companion object {
        fun decode(code: String): TileId? {
            val parts = code.split(':')
            return when (parts[0]) {
                "w" -> if (parts.size == 1) Weather else null
                "a" -> if (parts.size == 1) Agenda else null
                "z" -> pair(parts)?.let { (module, number) -> Zone(module, number) }
                "s" -> pair(parts)?.let { (module, number) -> Switch(module, number) }
                "r" -> pair(parts)?.let { (module, number) -> Shutter(module, number) }
                else -> null
            }
        }

        private fun pair(parts: List<String>): Pair<Int, Int>? {
            if (parts.size != 3) return null
            val first = parts[1].toIntOrNull() ?: return null
            val second = parts[2].toIntOrNull() ?: return null
            return first to second
        }
    }
}

/** Czy wyjście ma kafelek-przełącznik na pulpicie (typy 1, 7, 8). RGB ma osobny kafelek (później); rolety to osobny model. */
val Output.isSwitch: Boolean
    get() = kind == OutputKind.ON_OFF || kind == OutputKind.LED || kind == OutputKind.DIMMER_ON_OFF

/** Kafelek z rozmiarem; [size] musi być dozwolony dla rodzaju kafelka. */
data class TileEntry(val id: TileId, val size: TileSize) {
    init {
        require(id.kind.allows(size)) { "size $size is not allowed for ${id.kind}" }
    }
}

/**
 * Zapis układu odczytany z pamięci ustawień ([DashboardLayout.load]).
 *
 * @property alarmMigrationPending zapis pochodzi sprzed wersji 3 (czyli sprzed „wyjścia alarmowe są domyślnie
 *   ukryte”): przy uzgadnianiu ([DashboardLayout.reconcile]) jednorazowo przenosimy widoczne wyjścia alarmowe do
 *   ukrytych. Flaga nie jest częścią układu ani zapisu – po pierwszej zmianie w trybie edycji układ zapisuje się
 *   w bieżącej wersji ([DashboardLayout.VERSION]) i flaga znika, więc wyjście alarmowe dodane potem z powrotem przez użytkownika zostaje na pulpicie.
 */
data class StoredLayout(val layout: DashboardLayout, val alarmMigrationPending: Boolean)

/**
 * Układ pulpitu: kafelki widoczne w kolejności układania w siatce ([tiles]) i kafelki ukryte przez użytkownika
 * ([hidden], z zapamiętanym rozmiarem, do ponownego dodania). Układ jest niezmienny; operacje edycji zwracają nowy.
 *
 * Zapis ([encode] / [decode]) jest wersjonowany ([VERSION]); starsze zapisy (v3 i v2 z kodami liter oraz sama lista
 * kolejności bez rozmiarów) są odczytywane i migrowane. Nowe i usunięte urządzenia uzgadnia [reconcile].
 */
data class DashboardLayout(val tiles: List<TileEntry>, val hidden: List<TileEntry> = emptyList()) {
    init {
        val all = tiles.map { it.id } + hidden.map { it.id }
        require(all.size == all.toSet().size) { "tile ids must be unique" }
    }

    val ids: List<TileId> get() = tiles.map { it.id }

    operator fun contains(id: TileId): Boolean = tiles.any { it.id == id }

    fun size(id: TileId): TileSize? = tiles.firstOrNull { it.id == id }?.size

    /**
     * Rozmiar po naciśnięciu przycisku „rozmiar” w siatce o [columns] kolumnach ([TileKind.nextPreset]);
     * `null` = kafelka nie ma na pulpicie albo nie ma innego rozmiaru.
     */
    fun nextSize(id: TileId, columns: Int = TileSize.MAX_COLS): TileSize? {
        val current = size(id) ?: return null
        return id.kind.nextPreset(current, columns)
    }

    /** Przesuwa kafelek [id] na pozycję [toIndex] (pozostałe się przesuwają); nieznany kafelek – bez zmian. */
    fun moved(id: TileId, toIndex: Int): DashboardLayout {
        val from = tiles.indexOfFirst { it.id == id }
        if (from < 0) return this
        val target = toIndex.coerceIn(0, tiles.lastIndex)
        if (from == target) return this
        val list = tiles.toMutableList()
        list.add(target, list.removeAt(from))
        return copy(tiles = list)
    }

    /** Zmienia rozmiar kafelka; rozmiar niedozwolony dla tego rodzaju albo nieznany kafelek – bez zmian. */
    fun resized(id: TileId, size: TileSize): DashboardLayout {
        if (!id.kind.allows(size) || tiles.none { it.id == id && it.size != size }) return this
        return copy(tiles = tiles.map { if (it.id == id) TileEntry(id, size) else it })
    }

    /** Przełącza kafelek na kolejny rozmiar przycisku „rozmiar” ([nextSize]). */
    fun cycled(id: TileId, columns: Int = TileSize.MAX_COLS): DashboardLayout =
        nextSize(id, columns)?.let { resized(id, it) } ?: this

    /** Ukrywa kafelek (zapamiętuje jego rozmiar); nieznany – bez zmian. */
    fun hide(id: TileId): DashboardLayout {
        val entry = tiles.firstOrNull { it.id == id } ?: return this
        return DashboardLayout(tiles - entry, hidden + entry)
    }

    /** Dodaje ukryty kafelek na koniec pulpitu (z zapamiętanym rozmiarem); nieukryty – bez zmian. */
    fun show(id: TileId): DashboardLayout {
        val entry = hidden.firstOrNull { it.id == id } ?: return this
        return DashboardLayout(tiles + entry, hidden - entry)
    }

    /** Zapis: `v4;+ w 2x1;+ a 2x4;+ z:1:0 2x2;- s:1:5 1x1` (`+` widoczny, `-` ukryty; id, kolumny×wiersze). */
    fun encode(): String = buildString {
        append("v").append(VERSION)
        tiles.forEach { append(";+ ").append(it.id.code).append(' ').append(it.size.code) }
        hidden.forEach { append(";- ").append(it.id.code).append(' ').append(it.size.code) }
    }

    companion object {
        /**
         * Wersja zapisu. 4 = dowolne rozmiary `2x4` (wcześniej kody liter `s/m/l/xl/c`). 3 = po wprowadzeniu domyślnie
         * ukrytych wyjść alarmowych (v3 i v2 odczytujemy z migracją rozmiarów; wyjścia alarmowe z zapisu v2 są
         * jednorazowo ukrywane; v1 – sama lista kolejności – tak samo).
         */
        const val VERSION = 4

        /** Ostatnia wersja z kodami liter (`s/m/l/xl/c`), już po ukrywaniu alarmów. */
        private const val VERSION_LETTER_SIZES = 3

        /** Najstarsza wersja z rekordami `+/-` (bez ukrywania alarmów), nadal odczytywana. */
        private const val VERSION_BEFORE_ALARM_HIDING = 2

        /** Od ilu kolumn domyślny układ ma kolumnę boczną (pogoda nad kalendarzem po lewej); poniżej – kafelki jeden pod drugim. */
        const val SIDE_COLUMN_MIN_COLUMNS = 7

        /** Od ilu kolumn pogoda w układzie „jeden pod drugim” jest kwadratowa (2×2) obok kalendarza 4×2. */
        const val SQUARE_WEATHER_MIN_COLUMNS = 6

        /** Kalendarz w kolumnie bocznej (tablet poziomo): 2 kolumny × 4 wiersze pod pogodą 2×1. */
        val AGENDA_SIDE_COLUMN = TileSize(2, 4)

        private fun entry(id: TileId) = TileEntry(id, id.kind.defaultSize)

        private fun configTiles(config: HomeConfig?): List<TileId> {
            val zones = config?.heatingZones.orEmpty().map { TileId.Zone(it.module, it.outputNr) }
            val switches = config?.outputs.orEmpty().filter { it.isSwitch }.map { TileId.Switch(it.module, it.relay) }
            val shutters = config?.shutters.orEmpty().map { TileId.Shutter(it.module, it.outputNr) }
            return zones + switches + shutters
        }

        /**
         * Układ domyślny dla siatki o [columns] kolumnach; strefy, przełączniki i rolety w kolejności bazy.
         *  - od [SIDE_COLUMN_MIN_COLUMNS] kolumn (tablet poziomo): po lewej pogoda 2×1 nad kalendarzem 2×4
         *    ([AGENDA_SIDE_COLUMN]), na prawo strefy 2×2, potem światła 1×1;
         *  - [SQUARE_WEATHER_MIN_COLUMNS] (tablet pionowo): u góry pogoda 2×2 obok kalendarza 4×2;
         *  - mniej (telefon): pogoda 2×1 nad kalendarzem 4×2 (zwężonym do liczby kolumn).
         * Dalej zawsze strefy (2×2), po nich przełączniki (1×1), a na końcu rolety (2×1 – szerokie karty ↑ ■ ↓);
         * wyjścia alarmowe są ukryte ([alarmTileIds]).
         */
        fun default(config: HomeConfig?, columns: Int = SIDE_COLUMN_MIN_COLUMNS): DashboardLayout {
            val head = when {
                columns >= SIDE_COLUMN_MIN_COLUMNS ->
                    listOf(TileEntry(TileId.Weather, TileSize.M), TileEntry(TileId.Agenda, AGENDA_SIDE_COLUMN))
                columns >= SQUARE_WEATHER_MIN_COLUMNS ->
                    listOf(TileEntry(TileId.Weather, TileSize.L), TileEntry(TileId.Agenda, TileSize.XL))
                else -> listOf(
                    TileEntry(TileId.Weather, TileSize.M),
                    TileEntry(TileId.Agenda, TileKind.AGENDA.nearest(TileSize.XL.cols.toFloat(), TileSize.XL.rows.toFloat(), columns)),
                )
            }
            // Wyjścia alarmowe (icon_id 124) nie trafiają same na pulpit – ukryte, do dodania przyciskiem „Dodaj”.
            val alarmIds = alarmTileIds(config)
            val (alarm, regular) = configTiles(config).partition { it in alarmIds }
            return DashboardLayout((head + regular.map(::entry)).distinctBy { it.id }, alarm.map(::entry))
        }

        /**
         * Odczyt zapisu z [encode] albo migracja starego zapisu (lista kolejności bez rozmiarów).
         * Brak zapisu, uszkodzony zapis albo zapis z nowszej wersji aplikacji = `null` (wtedy układ domyślny).
         * Elementy z nieznanym id (np. rodzaj kafelka z nowszej wersji) są pomijane, nieczytelny rozmiar zastępowany
         * domyślnym, a niedozwolony dla rodzaju kafelka – najbliższym dozwolonym ([TileKind.coerce]; np. dawna
         * „kolumna” kalendarza `c` = 2×4, roleta L = 2×1).
         */
        fun decode(text: String?): DashboardLayout? = load(text)?.layout

        /** Jak [decode], ale z informacją, czy zapis jest sprzed ukrywania wyjść alarmowych ([StoredLayout]). */
        fun load(text: String?): StoredLayout? {
            if (text.isNullOrBlank()) return null
            val trimmed = text.trim()
            return if (trimmed.startsWith("v")) decodeVersioned(trimmed) else decodeLegacy(trimmed)?.let { StoredLayout(it, true) }
        }

        private fun decodeVersioned(text: String): StoredLayout? {
            val records = text.split(';')
            val legacyAlarms = when (records[0]) {
                "v$VERSION", "v$VERSION_LETTER_SIZES" -> false
                "v$VERSION_BEFORE_ALARM_HIDING" -> true
                else -> return null // nieznana (nowsza) wersja
            }
            val visible = ArrayList<TileEntry>()
            val hidden = ArrayList<TileEntry>()
            val seen = HashSet<TileId>()
            for (record in records.drop(1)) {
                val fields = record.trim().split(' ')
                if (fields.size != 3 || (fields[0] != "+" && fields[0] != "-")) return null // uszkodzony zapis
                val id = TileId.decode(fields[1]) ?: continue
                if (!seen.add(id)) continue
                val size = TileSize.parse(fields[2])?.let(id.kind::coerce) ?: id.kind.defaultSize
                (if (fields[0] == "+") visible else hidden).add(TileEntry(id, size))
            }
            return StoredLayout(DashboardLayout(visible, hidden), legacyAlarms)
        }

        /** Wersja 1: `w,a,z:1:0,s:1:5` – tylko kolejność widocznych kafelków; brakująca pogoda/kalendarz = ukryte. */
        private fun decodeLegacy(text: String): DashboardLayout? {
            val ids = text.split(',').mapNotNull { TileId.decode(it.trim()) }.distinct()
            if (ids.isEmpty()) return null
            val hidden = listOf(TileId.Weather, TileId.Agenda).filter { it !in ids }.map(::entry)
            return DashboardLayout(ids.map(::entry), hidden)
        }

        /**
         * Uzgadnia zapisany układ [saved] z bieżącą konfiguracją: zostają kafelki (widoczne i ukryte), które nadal
         * istnieją; znikają usunięte urządzenia; nowe urządzenia (nieobecne w zapisie) trafiają na **koniec** widocznych
         * w kolejności bazy (nowe wyjścia alarmowe – do ukrytych). Brak zapisu = układ domyślny dla [columns].
         * Gdy [config] jest nieznana, zapis wraca bez zmian.
         *
         * @param migrateAlarms zapis jest sprzed ukrywania wyjść alarmowych ([StoredLayout.alarmMigrationPending]):
         *   widoczne wyjścia alarmowe są przenoszone do ukrytych (zachowują rozmiar). Tylko dla takiego zapisu –
         *   zapis od wersji 3 jest wiarygodny (alarm na pulpicie = wybór użytkownika).
         */
        fun reconcile(
            saved: DashboardLayout?,
            config: HomeConfig?,
            columns: Int = SIDE_COLUMN_MIN_COLUMNS,
            migrateAlarms: Boolean = false,
        ): DashboardLayout {
            if (saved == null) return default(config, columns)
            if (config == null) return saved
            val existing = (listOf(TileId.Weather, TileId.Agenda) + configTiles(config)).toSet()
            val alarmIds = alarmTileIds(config)
            var tiles = saved.tiles.filter { it.id in existing }
            var hidden = saved.hidden.filter { it.id in existing }
            if (migrateAlarms) {
                val (alarm, regular) = tiles.partition { it.id in alarmIds }
                tiles = regular
                hidden = hidden + alarm
            }
            val known = (tiles + hidden).map { it.id }.toSet()
            val (newAlarm, newRegular) = (listOf(TileId.Weather, TileId.Agenda) + configTiles(config))
                .filter { it !in known }
                .partition { it in alarmIds } // nowe wyjście alarmowe trafia do ukrytych, nie na pulpit
            return DashboardLayout(tiles + newRegular.map(::entry), hidden + newAlarm.map(::entry))
        }
    }
}
