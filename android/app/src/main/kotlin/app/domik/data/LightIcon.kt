package app.domik.data

import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Output
import app.domik.deimic.config.OutputKind

/**
 * Rodzaj ikony kafelka-przełącznika. [key] to stabilny identyfikator w zapisie wyboru użytkownika
 * ([IconChoices]) – nie zmieniać go ani nie używać ponownie dla innej ikony.
 */
enum class LightIcon(val key: String) {
    BULB("bulb"),
    SCONCE("sconce"),
    PENDANT("pendant"),
    FLOOR("floor"),
    SPOT("spot"),
    LED("led"),
    OUTDOOR("outdoor"),
    SOCKET("socket"),
    FAN("fan"),
    PUMP("pump"),
    ALARM("alarm"),
    SWITCH("switch"),
    ;

    companion object {
        fun fromKey(key: String): LightIcon? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Automatyczny dobór ikony wyjścia i kryterium „alarm” – czyste funkcje, bez Androida.
 *
 * Kolejność doboru: (1) `icon_id` z bazy sterownika, (2) słowa w nazwie, (3) typ wyjścia (taśma LED), (4) żarówka.
 * Użytkownik może wybrać ikonę ręcznie ([IconChoices]); wybór ma pierwszeństwo przed doborem automatycznym.
 */
object LightIcons {
    /** `icon_id` wyjść sterujących alarmem (uzbrajanie stref, „przycisk paniki”). */
    const val ALARM_ICON_ID: Int = 124

    private const val SOCKET_ICON_ID = 1003
    private const val PUMP_ICON_ID = 1064

    private val byDatabaseIconId: Map<Int, LightIcon> = mapOf(
        SOCKET_ICON_ID to LightIcon.SOCKET,
        PUMP_ICON_ID to LightIcon.PUMP,
        ALARM_ICON_ID to LightIcon.ALARM,
    )

    /**
     * Ikona wyjścia o `icon_id` [iconId], nazwie [name] i typie [kind]. Nazwa jest porównywana bez względu na wielkość
     * liter i polskie znaki, po początkach słów (odmiana: „kinkiet”, „kinkiety”, „kinkiecie”). Przy kilku słowach
     * wygrywa urządzenie przed miejscem: pompa, wentylator, gniazdko, kinkiet, żyrandol, lampa stojąca, oczko, taśma
     * LED – a dopiero na końcu miejsce na zewnątrz (ogród, taras…).
     */
    fun automatic(iconId: Int?, name: String, kind: OutputKind): LightIcon {
        iconId?.let { byDatabaseIconId[it] }?.let { return it }
        fromName(name)?.let { return it }
        return if (kind == OutputKind.LED) LightIcon.LED else LightIcon.BULB
    }

    /** Czy wyjście o `icon_id` [iconId] steruje alarmem – domyślnie ukryte na pulpicie i zawsze z potwierdzeniem. */
    fun isAlarmOutput(iconId: Int?): Boolean = iconId == ALARM_ICON_ID

    /**
     * Czy dotknięcie kafelka wymaga potwierdzenia: wyjście alarmowe z bazy (nawet gdy użytkownik wybrał dla niego inną
     * ikonę) albo kafelek, któremu użytkownik wybrał ikonę alarmu ([icon] to ikona faktycznie pokazana).
     */
    fun requiresConfirmation(iconId: Int?, icon: LightIcon): Boolean = isAlarmOutput(iconId) || icon == LightIcon.ALARM

    private fun fromName(name: String): LightIcon? {
        val words = fold(name).split(NON_WORD).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        fun begins(vararg prefixes: String) = words.any { word -> prefixes.any(word::startsWith) }
        return when {
            begins("pomp") -> LightIcon.PUMP
            begins("wentyl") || "went" in words -> LightIcon.FAN
            begins("gniazd") -> LightIcon.SOCKET
            begins("kinki") -> LightIcon.SCONCE
            begins("zyrandol") || (begins("lamp") && begins("wisz")) -> LightIcon.PENDANT
            begins("lamp") && begins("stoj", "podlog") -> LightIcon.FLOOR
            begins("oczk", "oczek", "spot", "halogen") -> LightIcon.SPOT
            "led" in words || begins("tasm") -> LightIcon.LED
            begins("ogrod", "taras", "zewn", "elewac") -> LightIcon.OUTDOOR
            else -> null
        }
    }

    private val NON_WORD = Regex("[^a-z0-9]+")

    /** Małe litery bez polskich znaków diakrytycznych. */
    private fun fold(text: String): String = buildString(text.length) {
        for (ch in text.lowercase()) {
            append(
                when (ch) {
                    'ą' -> 'a'
                    'ć' -> 'c'
                    'ę' -> 'e'
                    'ł' -> 'l'
                    'ń' -> 'n'
                    'ó' -> 'o'
                    'ś' -> 's'
                    'ź', 'ż' -> 'z'
                    else -> ch
                },
            )
        }
    }
}

/** Ikona dobrana automatycznie dla wyjścia (bez wyboru użytkownika). */
fun Output.automaticIcon(): LightIcon = LightIcons.automatic(iconId, name, kind)

/** Czy wyjście steruje alarmem (`icon_id` 124): domyślnie nie ma kafelka na pulpicie, a jego dotknięcie wymaga potwierdzenia. */
val Output.isAlarm: Boolean get() = LightIcons.isAlarmOutput(iconId)

/** Kafelki wyjść alarmowych z [config] – nie trafiają same na pulpit (układ domyślny, uzgadnianie nowych urządzeń). */
fun alarmTileIds(config: HomeConfig?): Set<TileId> =
    config?.outputs.orEmpty().filter { it.isSwitch && it.isAlarm }.map { TileId.Switch(it.module, it.relay) }.toSet()
