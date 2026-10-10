package app.domik.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Kształty: promienie zaokrągleń kafelków, arkuszy, chipów i przycisków. */
object DomikShapes {
    /** Kafelek – 24 dp. */
    val tile = RoundedCornerShape(24.dp)

    /** Arkusz od dołu – 28 dp (górne rogi). */
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Chipy i pigułki – 999 dp. */
    val chip = RoundedCornerShape(percent = 50)

    /** Przyciski ± – okrągłe. */
    val round = CircleShape
}

/** Mapowanie na `MaterialTheme.shapes` (komponenty Material 3). */
internal val DomikMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Odstępy: padding wewnątrz kafelka i przerwa między kafelkami. */
object DomikSpacing {
    /** Wewnętrzny padding kafelka. */
    val tilePadding = 20.dp

    /** Węższy padding kafelka S w wąskich komórkach siatki (tablet pionowo, telefon). */
    val tilePaddingCompact = 14.dp

    /** Odstęp między kafelkami: telefon / tablet. */
    val gapPhone = 16.dp
    val gapTablet = 20.dp
}
