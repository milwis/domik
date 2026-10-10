package app.domik.ui.dashboard

import app.domik.data.TileKind
import app.domik.data.TileSize

/**
 * Zmiana rozmiaru kafelka przeciąganiem prawego-dolnego rogu w trybie edycji – obliczenia bez Compose (testy JVM).
 * Palec wyznacza rozmiar „ciągły” w dp, który przeliczamy na ułamek komórek i przyciągamy do najbliższego rozmiaru
 * dozwolonego dla rodzaju kafelka ([TileKind.nearest]); szerokość nie większa niż liczba kolumn siatki.
 */
object GridResize {
    /** Ile komórek (ułamkowo) zajmuje odcinek [lengthDp] w siatce o komórce [cellDp] i odstępie [gapDp]. */
    fun cells(lengthDp: Float, cellDp: Float, gapDp: Float): Float = (lengthDp + gapDp) / (cellDp + gapDp)

    /** Rozmiar dla kafelka [kind] przeciągniętego do [widthDp] × [heightDp] w siatce [geometry]. */
    fun snap(kind: TileKind, geometry: GridGeometry, widthDp: Float, heightDp: Float): TileSize = kind.nearest(
        cols = cells(widthDp, geometry.cellWidth, geometry.gap),
        rows = cells(heightDp, geometry.rowHeight, geometry.gap),
        columns = geometry.columns,
    )

    /**
     * Gdzie wyląduje kafelek nr [index] po zmianie rozmiaru na [size] (podgląd w czasie przeciągania): ułożenie
     * [GridPacker] z nowym rozmiarem. Kafelki dalej na liście nie wpływają na jego miejsce, więc wystarczy początek listy.
     */
    fun preview(items: List<PackItem>, index: Int, size: TileSize, columns: Int): GridRect {
        require(index in items.indices) { "index out of range" }
        return GridPacker.pack(items.take(index) + PackItem(size.cols, size.rows), columns).last()
    }
}
