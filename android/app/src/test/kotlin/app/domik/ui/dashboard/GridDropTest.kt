package app.domik.ui.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GridDropTest {
    // Dwa rzędy po dwa kafelki 100×100 z odstępem 20: 0 | 1 w górze, 2 | 3 na dole.
    private val rects = listOf(
        PxRect(0f, 0f, 100f, 100f), PxRect(120f, 0f, 220f, 100f),
        PxRect(0f, 120f, 100f, 220f), PxRect(120f, 120f, 220f, 220f),
    )

    @Test
    fun `srodek nad innym kafelkiem daje jego indeks`() {
        assertEquals(1, dropTargetIndex(rects, dragged = 0, x = 170f, y = 50f))
        assertEquals(3, dropTargetIndex(rects, dragged = 0, x = 170f, y = 170f))
        assertEquals(0, dropTargetIndex(rects, dragged = 3, x = 10f, y = 10f))
    }

    @Test
    fun `nad samym soba i w lukach siatki nic sie nie zmienia`() {
        assertNull(dropTargetIndex(rects, dragged = 0, x = 50f, y = 50f))
        assertNull(dropTargetIndex(rects, dragged = 0, x = 110f, y = 50f)) // luka pionowa
        assertNull(dropTargetIndex(rects, dragged = 0, x = 50f, y = 110f)) // luka pozioma
        assertNull(dropTargetIndex(rects, dragged = 0, x = -30f, y = -30f)) // powyżej i na lewo od siatki
    }

    @Test
    fun `ponizej wszystkich kafelkow - przeniesienie na koniec`() {
        assertEquals(3, dropTargetIndex(rects, dragged = 0, x = 50f, y = 400f))
        assertNull(dropTargetIndex(rects, dragged = 3, x = 50f, y = 400f)) // ostatni już jest na końcu
    }

    @Test
    fun `nieprawidlowy indeks przeciaganego kafelka jest ignorowany`() {
        assertNull(dropTargetIndex(rects, dragged = -1, x = 50f, y = 50f))
        assertNull(dropTargetIndex(rects, dragged = 9, x = 50f, y = 50f))
        assertNull(dropTargetIndex(emptyList(), dragged = 0, x = 0f, y = 0f))
    }
}
