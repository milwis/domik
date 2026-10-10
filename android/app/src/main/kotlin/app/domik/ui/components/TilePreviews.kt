package app.domik.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.domik.ui.theme.AccentPalette
import app.domik.ui.theme.DomikBackground
import app.domik.ui.theme.DomikTheme
import app.domik.ui.theme.ThemeMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * @Preview każdego kafelka w czterech wariantach naraz: jasny/ciemny × Zieleń (domyślna) / Fiolet.
 * Dane są fikcyjne i ogólne (Salon, Kuchnia) – tylko do podglądu w Android Studio.
 */

private const val CELL_W = 360

/** Rysuje [content] w czterech motywach (siatka 2×2): wiersze = jasny/ciemny, kolumny = dwie palety. */
@Composable
private fun PreviewMatrix(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            Row {
                for (palette in listOf(AccentPalette.Green, AccentPalette.Violet)) {
                    DomikTheme(mode = mode, palette = palette) {
                        DomikBackground(Modifier.width(CELL_W.dp)) {
                            Box(Modifier.padding(16.dp)) { content() }
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "TileCard – stany", widthDp = CELL_W * 2, heightDp = 2 * 220)
@Composable
private fun TileCardStatesPreview() = PreviewMatrix {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((state, name) in listOf(TileState.On to "Włączony", TileState.Off to "Wyłączony")) {
                TileCard(state, Modifier.weight(1f).height(80.dp)) { tile ->
                    androidx.compose.material3.Text(name, color = tile.title)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileCard(TileState.Pending, Modifier.weight(1f).height(80.dp), pendingOn = true) { tile ->
                androidx.compose.material3.Text("Oczekuje", color = tile.title)
            }
            TileCard(TileState.Neutral, Modifier.weight(1f).height(80.dp)) { tile ->
                androidx.compose.material3.Text("Neutralny", color = tile.title)
            }
        }
    }
}

@Preview(name = "LightTile", widthDp = CELL_W * 2, heightDp = 2 * 220)
@Composable
private fun LightTilePreview() = PreviewMatrix {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LightTile("Sufit", "Salon", on = true, onToggle = {}, icon = DeviceIcon.Pendant, modifier = Modifier.weight(1f).aspectRatio(1f))
        LightTile("Kinkiet", "Salon", on = false, onToggle = {}, icon = DeviceIcon.Bulb, modifier = Modifier.weight(1f).aspectRatio(1f))
        LightTile("Gniazdko", "Taras", on = true, onToggle = {}, icon = DeviceIcon.Socket, pending = true, modifier = Modifier.weight(1f).aspectRatio(1f))
    }
}

private val sampleZone = HeatingTileState(
    name = "Salon",
    room = "Parter",
    currentTemp = 21.5f,
    setpoint = 22.0f,
    profile = HeatingProfile.Comfort,
    heating = true,
)

/** Roleta w gęstościach: Regular (148 dp), Compact (124 dp), Tight (108 dp – najmniejsza komórka siatki) i 2×2 (232 dp). */
@Preview(name = "ShutterTile", widthDp = CELL_W * 2, heightDp = 2 * 660)
@Composable
private fun ShutterTilePreview() = PreviewMatrix {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShutterTile("Okno", "Salon", ShutterDisplay.Unknown, onControl = {}, modifier = Modifier.fillMaxWidth().height(148.dp))
        ShutterTile("Drzwi tarasowe", "Salon", ShutterDisplay.MovingUp, onControl = {}, modifier = Modifier.fillMaxWidth().height(124.dp))
        ShutterTile(
            "Okno", "Sypialnia", ShutterDisplay.MovingDown, onControl = {}, pending = ShutterControl.Stop,
            modifier = Modifier.fillMaxWidth().height(108.dp),
        )
        ShutterTile("Okno", "Sypialnia", ShutterDisplay.Open, onControl = {}, modifier = Modifier.fillMaxWidth().height(232.dp))
    }
}

@Preview(name = "HeatingTile M", widthDp = CELL_W * 2, heightDp = 2 * 220)
@Composable
private fun HeatingTileMediumPreview() = PreviewMatrix {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HeatingTile(sampleZone, HeatingTileSize.Medium, Modifier.fillMaxWidth().height(100.dp))
        HeatingTile(
            sampleZone.copy(name = "Łazienka", profile = HeatingProfile.Off, heating = false),
            HeatingTileSize.Medium,
            Modifier.fillMaxWidth().height(100.dp),
        )
    }
}

@Preview(name = "HeatingTile L", widthDp = CELL_W * 2, heightDp = 2 * 300)
@Composable
private fun HeatingTileLargePreview() = PreviewMatrix {
    HeatingTile(
        sampleZone.copy(heating = false, profile = HeatingProfile.Eco, setpoint = 18.0f),
        HeatingTileSize.Large,
        Modifier.fillMaxWidth().height(260.dp),
    )
}

@Preview(name = "WeatherTile", widthDp = CELL_W * 2, heightDp = 2 * 220)
@Composable
private fun WeatherTilePreview() = PreviewMatrix {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WeatherTile(
            WeatherTileState(WeatherCondition.PartlyCloudy, 14.5f, "Warszawa", minTemp = 9f, maxTemp = 16f),
            Modifier.fillMaxWidth().height(100.dp),
        )
        WeatherTile(
            WeatherTileState(WeatherCondition.Rain, 8.0f, "Kraków"),
            Modifier.fillMaxWidth().height(100.dp),
        )
    }
}

private val previewNow: LocalDateTime = LocalDateTime.of(2026, 10, 10, 12, 30)

private val previewAgenda: List<AgendaDay> = run {
    val today = previewNow.toLocalDate()
    listOf(
        AgendaDay(
            today.minusDays(1),
            events = listOf(AgendaEvent("y1", LocalTime.of(18, 0), "Kolacja", LocalTime.of(20, 0))),
        ),
        AgendaDay(
            today,
            allDay = listOf(AgendaAllDayEvent("a1", "Urodziny babci")),
            events = listOf(
                AgendaEvent("t1", LocalTime.of(8, 0), "Siłownia", LocalTime.of(9, 0)),
                AgendaEvent("t2", LocalTime.of(12, 0), "Spotkanie zespołu", LocalTime.of(13, 0)),
                AgendaEvent("t3", LocalTime.of(18, 30), "Zakupy"),
            ),
        ),
        AgendaDay(
            today.plusDays(1),
            events = listOf(AgendaEvent("n1", LocalTime.of(10, 0), "Spacer", LocalTime.of(11, 30))),
        ),
        AgendaDay(LocalDate.of(2026, 10, 12)),
    )
}

@Preview(name = "CalendarAgendaTile", widthDp = CELL_W * 2, heightDp = 2 * 420)
@Composable
private fun CalendarAgendaTilePreview() = PreviewMatrix {
    CalendarAgendaTile(
        days = previewAgenda,
        now = previewNow,
        modifier = Modifier.fillMaxWidth().height(380.dp),
        idleReturnMillis = null,
    )
}

@Preview(name = "ConnectionBanner", widthDp = CELL_W * 2, heightDp = 2 * 90)
@Composable
private fun ConnectionBannerPreview() = PreviewMatrix {
    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.Transparent)) {
        ConnectionBanner(visible = true)
    }
}
