package app.domik.ui.dashboard

/** Prostokąt w pikselach (współrzędne płótna pulpitu) – do wyznaczania celu upuszczenia przeciąganego kafelka. */
data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * Cel upuszczenia przeciąganego kafelka: indeks kafelka z [rects], nad którym jest punkt ([x], [y]) (środek
 * przeciąganego kafelka), a gdy punkt jest poniżej wszystkich kafelków – ostatni indeks (przeniesienie na koniec).
 * Zwraca `null`, gdy nic nie trzeba zmieniać: punkt nad samym kafelkiem [dragged] albo poza kafelkami
 * (w lukach siatki, powyżej pierwszego kafelka).
 */
fun dropTargetIndex(rects: List<PxRect>, dragged: Int, x: Float, y: Float): Int? {
    if (dragged !in rects.indices) return null
    if (rects[dragged].contains(x, y)) return null
    val hit = rects.indices.firstOrNull { it != dragged && rects[it].contains(x, y) }
    if (hit != null) return hit
    val lowest = rects.maxOf { it.bottom }
    return if (y >= lowest && dragged != rects.lastIndex) rects.lastIndex else null
}
