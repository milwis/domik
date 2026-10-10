package app.domik.ui.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeaderLayoutTest {
    // Wartości w dp, zmierzone na Galaxy S25 (360 dp) w trybie edycji: ikona + tytuł ≈ 190, ⟲ + „Dodaj” + „Gotowe” ≈ 260.
    private val title = 190
    private val actions = 260
    private val gap = 8

    @Test
    fun `tytul i przyciski obok siebie tylko gdy mieszcza sie w calosci`() {
        // tablet (≥ 600 dp, minus marginesy)
        assertEquals(EditHeaderMode.SINGLE_ROW, HeaderLayout.editMode(available = 1200, titleWidth = title, actionsWidth = actions, gap = gap))
        assertEquals(EditHeaderMode.SINGLE_ROW, HeaderLayout.editMode(available = 568, titleWidth = title, actionsWidth = actions, gap = gap))
        // telefon: 360 dp − 2 × 16 dp marginesu = 328 dp, mniej niż 190 + 8 + 260
        assertEquals(EditHeaderMode.TWO_ROWS, HeaderLayout.editMode(available = 328, titleWidth = title, actionsWidth = actions, gap = gap))
    }

    @Test
    fun `granica - dokladnie tyle miejsca ile trzeba to jeszcze jeden rzad, o piksel mniej to dwa`() {
        val needed = title + gap + actions
        assertEquals(EditHeaderMode.SINGLE_ROW, HeaderLayout.editMode(needed, title, actions, gap))
        assertEquals(EditHeaderMode.TWO_ROWS, HeaderLayout.editMode(needed - 1, title, actions, gap))
    }

    @Test
    fun `duza czcionka przesuwa granice - ten sam ekran moze wymagac dwoch rzedow`() {
        val available = 568 // szerokość 600 dp po odjęciu marginesów
        assertEquals(EditHeaderMode.SINGLE_ROW, HeaderLayout.editMode(available, title, actions, gap))
        val scale = 1.6f
        assertEquals(
            EditHeaderMode.TWO_ROWS,
            HeaderLayout.editMode(available, (title * scale).toInt(), (actions * scale).toInt(), gap),
        )
    }

    @Test
    fun `plakietka tylko gdy miesci sie obok pelnego tytulu`() {
        // „Dom demonstracyjny” ≈ 208 dp, plakietka „Demo” ≈ 47 dp, odstęp 12 dp
        assertTrue(HeaderLayout.showBadge(available = 400, titleWidth = 208, badgeWidth = 47, gap = 12))
        assertTrue(HeaderLayout.showBadge(available = 267, titleWidth = 208, badgeWidth = 47, gap = 12))
        assertFalse(HeaderLayout.showBadge(available = 266, titleWidth = 208, badgeWidth = 47, gap = 12))
        // telefon 360 dp: na nazwę zostaje ≈ 202 dp – plakietka znika, nazwa dostaje wszystko
        assertFalse(HeaderLayout.showBadge(available = 202, titleWidth = 208, badgeWidth = 47, gap = 12))
    }
}
