package app.domik.integrations.emodul

import java.util.Base64

/** Oczyszczone fixtures eModul (`tools/emodul_probe/sanitize_fixtures.py`) i pomocnicze dane testowe. */
internal object EmodulFixtures {
    private fun resource(name: String): String =
        EmodulFixtures::class.java.getResource("/emodul/$name")!!.readText(Charsets.UTF_8)

    val modulesJson: String get() = resource("modules.json")
    val moduleJson: String get() = resource("module.json")
    val menuUserJson: String get() = resource("menu_MU.json")
    val menuInstallerJson: String get() = resource("menu_MI.json")
    val i18nJson: String get() = resource("i18n_pl.json")

    val snapshot: EmodulSnapshot by lazy { EmodulParser.parseModule(moduleJson) }
    val menus: Map<MenuType, EmodulMenu> by lazy {
        mapOf(
            MenuType.MU to EmodulParser.parseMenu(menuUserJson, MenuType.MU),
            MenuType.MI to EmodulParser.parseMenu(menuInstallerJson, MenuType.MI),
        )
    }
    val texts: EmodulTexts by lazy { EmodulTexts(EmodulParser.parseI18n(i18nJson), TestFallbacks) }

    inline fun <reified T : EmodulTile> tile(id: Int): T = snapshot.tiles.single { it.id == id } as T

    /** Teksty zastępcze w teście: łatwe do rozpoznania w asercjach. */
    object TestFallbacks : EmodulFallbacks {
        override fun text(kind: EmodulFallback, number: Int): String = when (kind) {
            EmodulFallback.BUILT_IN_VALVE -> "Zawór wbudowany (zastępczy)"
            EmodulFallback.VALVE_N -> "Zawór $number (zastępczy)"
            EmodulFallback.TILE_N -> "Kafelek $number"
            EmodulFallback.OUTPUT_N -> "Wyjście $number"
            EmodulFallback.PUMP_N -> "Pompa $number"
            EmodulFallback.SENSOR_N -> "Czujnik $number"
            else -> kind.name
        }
    }

    /** Syntetyczny klucz JWT (`user_id` zmyślone), nigdy prawdziwy. */
    fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun part(text: String) = encoder.encodeToString(text.toByteArray(Charsets.UTF_8))
        return part("""{"alg":"HS256","typ":"JWT"}""") + "." + part(payload) + "." + "c2lnbmF0dXJlLWZha2U"
    }

    fun menuJson(vararg elements: String): String =
        """{"status":"success","data":{"elements":[${elements.joinToString(",")}],"transaction_time":"x"}}"""

    fun element(id: Int, parentId: Int, type: Int, txtId: Int, params: String = "{}", extra: String = ""): String =
        """{"menuType":"MU","type":$type,"id":$id,"parentId":$parentId,"access":true,"txtId":$txtId,"params":$params$extra}"""
}
