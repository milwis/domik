package app.domik.integrations.emodul

import java.util.Locale

/** Rola widżetu kafelka typu 6 po powiązaniu z menu (§4.3). */
enum class WidgetRole { SETPOINT, CURRENT, OTHER }

/**
 * Widżet gotowy do pokazania. [text] `null` = „—” (brak ważnej wartości). Dla [WidgetRole.SETPOINT] [text]
 * pochodzi z wartości menu, a nie z surowego widżetu (który bywa 0).
 */
data class EmodulWidgetView(val index: Int, val widget: EmodulWidget, val role: WidgetRole, val text: FormattedValue?)

/**
 * Powiązanie kafelków z menu sterownika – wyłącznie do ODCZYTU wartości zadanych (§4.3). Aplikacja niczego
 * nie ustawia: to tylko wybór, którą wartość pokazać jako „zadaną”.
 */
object EmodulLinks {
    private const val KIND_NUMBER = 1
    private const val KIND_TENTHS = 2
    private const val KIND_HEATING_CURVE = 30
    private const val VALVE_NAME_DEPTH = 2
    private const val MAX_DEPTH = 32

    /**
     * Element menu wskazany przez `tile.menuId`: najpierw MU, potem MI; gdy `id` jest w obu menu – `null`
     * (powiązanie niejednoznaczne).
     */
    fun menuItemFor(tile: EmodulTile, menus: Map<MenuType, EmodulMenu>): EmodulMenuItem? {
        if (tile.menuId <= 0) return null
        val user = menus[MenuType.MU]?.items?.get(tile.menuId)
        val installer = menus[MenuType.MI]?.items?.get(tile.menuId)
        return if (user != null && installer != null) null else user ?: installer
    }

    /**
     * Widżety kafelka typu 6 w kolejności 1, 2. Jeśli powiązany element menu jest liczbą (`kind` 1 albo 2,
     * `format == kind`, ma wartość) i któryś widżet ma ten sam `txtId`, ten widżet jest zadaną ([WidgetRole.SETPOINT],
     * wartość z menu: format 1 – °C całkowite, 2 – ×0,1), a drugi aktualną ([WidgetRole.CURRENT]). Bez dopasowania
     * widżet z `raw == 0` w kafelku z `menuId > 0` ma [EmodulWidgetView.text] = `null` („—”), reszta wg jednostki.
     */
    fun widgetViews(tile: EmodulTile.Widgets, menus: Map<MenuType, EmodulMenu>, locale: Locale): List<EmodulWidgetView> {
        val widgets = listOfNotNull(tile.w1?.let { 1 to it }, tile.w2?.let { 2 to it })
        val item = menuItemFor(tile, menus)?.takeIf { it.isNumber() }
        val setpoint = item?.let { menuItem -> widgets.firstOrNull { it.second.txtId == menuItem.txtId } }
        return widgets.map { (index, widget) ->
            when {
                setpoint != null && index == setpoint.first -> EmodulWidgetView(
                    index, widget, WidgetRole.SETPOINT, EmodulFormat.temperature(
                        if (item.kind == KIND_TENTHS) item.value!! / 10.0 else item.value!!.toDouble(),
                        if (item.kind == KIND_TENTHS) 1 else 0, locale,
                    ),
                )
                setpoint != null -> EmodulWidgetView(index, widget, WidgetRole.CURRENT, EmodulFormat.widget(widget.raw, widget.unit, locale))
                tile.menuId > 0 && widget.raw == 0 -> EmodulWidgetView(index, widget, WidgetRole.OTHER, null)
                else -> EmodulWidgetView(index, widget, WidgetRole.OTHER, EmodulFormat.widget(widget.raw, widget.unit, locale))
            }
        }
    }

    /** Zawór sterowany krzywą grzewczą: `tile.menuId` wskazuje w MI element `kind == 30` („wg krzywej grzewczej”). */
    fun usesHeatingCurve(tile: EmodulTile.Valve, menus: Map<MenuType, EmodulMenu>): Boolean =
        tile.menuId > 0 && menus[MenuType.MI]?.items?.get(tile.menuId)?.kind == KIND_HEATING_CURVE

    /**
     * Nazwa zaworu: tekst przodka-grupy na głębokości 2 (korzeń `id 0` = głębokość 0) elementu MI wskazanego przez
     * `tile.menuId`; gdy go brak – tekst zastępczy z [texts].
     */
    fun valveName(tile: EmodulTile.Valve, menus: Map<MenuType, EmodulMenu>, texts: EmodulTexts): String {
        val items = menus[MenuType.MI]?.items
        val group = if (tile.menuId > 0 && items != null) ancestorAtDepth(items, tile.menuId, VALVE_NAME_DEPTH) else null
        return group?.let { texts.text(it.txtId) } ?: texts.title(tile)
    }

    /** Przodek (albo sam element) na głębokości [depth] licząc od korzenia; `null`, gdy łańcuch jest zerwany lub za krótki. */
    private fun ancestorAtDepth(items: Map<Int, EmodulMenuItem>, id: Int, depth: Int): EmodulMenuItem? {
        val chain = ArrayList<EmodulMenuItem>()
        var current = items[id] ?: return null
        while (chain.size < MAX_DEPTH) {
            chain += current
            if (current.id == 0 || current.parentId == current.id) break
            current = items[current.parentId] ?: return null
        }
        if (chain.size >= MAX_DEPTH || chain.last().id != 0 && chain.last().parentId != chain.last().id) return null
        return chain.asReversed().getOrNull(depth)
    }

    private fun EmodulMenuItem.isNumber(): Boolean =
        (kind == KIND_NUMBER || kind == KIND_TENTHS) && format == kind && value != null
}
