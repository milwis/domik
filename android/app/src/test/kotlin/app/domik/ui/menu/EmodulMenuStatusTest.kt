package app.domik.ui.menu

import app.domik.data.TileId
import app.domik.integrations.emodul.EmodulError
import app.domik.integrations.emodul.EmodulFixtures
import app.domik.integrations.emodul.EmodulModuleData
import app.domik.integrations.emodul.EmodulModuleInfo
import app.domik.integrations.emodul.EmodulSelection
import app.domik.integrations.emodul.EmodulState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Wiersz stanu integracji eModul w menu. */
class EmodulMenuStatusTest {
    private val info = EmodulModuleInfo("0123456789abcdef", "Kocioł", "pellet", "", online = true)

    private fun module(selection: EmodulSelection, enabled: Boolean = true) = EmodulModuleData(
        1, info, enabled, EmodulFixtures.snapshot, emptyMap(), EmodulFixtures.texts, 1L, selection,
    )

    @Test
    fun `stany bez danych`() {
        assertEquals(EmodulMenuStatus.NotConfigured, EmodulMenuStatus.of(EmodulState.NotConfigured))
        assertEquals(EmodulMenuStatus.Loading, EmodulMenuStatus.of(EmodulState.Loading))
        assertEquals(EmodulMenuStatus.KeyRejected, EmodulMenuStatus.of(EmodulState.KeyRejected))
        assertEquals(EmodulMenuStatus.Unavailable, EmodulMenuStatus.of(EmodulState.Failed(EmodulError.NETWORK)))
    }

    @Test
    fun `gotowe - liczba wybranych danych obecnych w sterowniku, bez wylaczonych modulow`() {
        val first = EmodulFixtures.snapshot.tiles.take(3).map { TileId.Emodul(1, it.id) }.toSet() + TileId.Emodul(1, 424242)
        val state = EmodulState.Ready(
            listOf(module(EmodulSelection(first, emptySet())), module(EmodulSelection(first, emptySet()), enabled = false)),
            error = null,
        )
        assertEquals(EmodulMenuStatus.Ready(3), EmodulMenuStatus.of(state))
    }

    @Test
    fun `gotowe bez wybranych danych to zero`() {
        assertEquals(EmodulMenuStatus.Ready(0), EmodulMenuStatus.of(EmodulState.Ready(listOf(module(EmodulSelection.EMPTY)), null)))
    }
}
