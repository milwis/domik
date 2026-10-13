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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.domik.data.TileSize
import app.domik.ui.dashboard.EmodulBanner
import app.domik.ui.dashboard.EmodulIcon
import app.domik.ui.dashboard.EmodulTile
import app.domik.ui.dashboard.EmodulTileUi
import app.domik.ui.dashboard.ResourcesEmodulUiTexts
import app.domik.integrations.emodul.EmodulAttention
import app.domik.ui.theme.AccentPalette
import app.domik.ui.theme.DomikBackground
import app.domik.ui.theme.DomikTheme
import app.domik.ui.theme.ThemeMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

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

/** Roleta 1×1 (bez przycisków; dotknięcie otwiera okno) – komórki 108 i 140 dp. */
@Preview(name = "ShutterTile S", widthDp = CELL_W * 2, heightDp = 2 * 190)
@Composable
private fun ShutterTileSmallPreview() = PreviewMatrix {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShutterTile("Okno", "Salon", ShutterDisplay.Open, onControl = {}, compact = true, modifier = Modifier.weight(1f).height(140.dp))
        ShutterTile(
            "Drzwi tarasowe", "Salon", ShutterDisplay.MovingUp, onControl = {}, compact = true, pending = ShutterControl.Up,
            modifier = Modifier.weight(1f).height(108.dp),
        )
    }
}

@Preview(name = "HeatingTile S", widthDp = CELL_W * 2, heightDp = 2 * 190)
@Composable
private fun HeatingTileSmallPreview() = PreviewMatrix {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeatingTile(sampleZone, HeatingTileSize.Small, Modifier.weight(1f).height(140.dp))
        HeatingTile(
            sampleZone.copy(name = "Łazienka", profile = HeatingProfile.Off, heating = false),
            HeatingTileSize.Small,
            Modifier.weight(1f).height(108.dp),
        )
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

// eModul (tylko odczyt): typy 6 (status z zadaną), 23 (zawór), 31 (paliwo) i 40 (tekst) w rozmiarach S, M i L.

private fun emodulSample(
    title: String,
    icon: EmodulIcon,
    primary: String,
    details: List<Pair<String, String>> = emptyList(),
    progress: Float? = null,
    note: String? = null,
    minutesOld: Long = 3,
    active: Boolean = false,
) = EmodulTileUi(
    title = title, module = "Kocioł", icon = icon, primary = primary, primaryLabel = null, details = details,
    progress = progress, active = active, note = note, description = "$title, $primary", multiModule = false,
    dataTime = Instant.now().minusSeconds(minutesOld * 60), fetchedAtMillis = System.currentTimeMillis(), moduleOnline = true,
)

private val emodulSamples = listOf(
    emodulSample("Temperatura CO", EmodulIcon.BOILER, "64,3°", listOf("zadana" to "65°")),
    emodulSample(
        "Zawór wbudowany", EmodulIcon.VALVE, "34,5°",
        listOf("zadana" to "35°", "powrót" to "30,1°", "otwarcie" to "40 %", "pompa" to "zał."),
        progress = 0.4f, note = "wg krzywej grzewczej",
    ),
    emodulSample("Zasobnik paliwa", EmodulIcon.FUEL, "64 %", listOf("czas pracy" to "≈ 41 h"), progress = 0.64f),
    emodulSample("Stan sterownika", EmodulIcon.INFO, "Praca"),
)

@Composable
private fun EmodulTiles(size: TileSize, samples: List<EmodulTileUi>, width: Int, height: Int) {
    val texts = ResourcesEmodulUiTexts(LocalResources.current)
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        samples.chunked(if (size == TileSize.S) 3 else 1).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { EmodulTile(it, size, now, zone, texts, Modifier.width(width.dp).height(height.dp)) }
            }
        }
    }
}

@Preview(name = "EmodulTile S", widthDp = CELL_W * 2, heightDp = 2 * 270)
@Composable
private fun EmodulTileSmallPreview() = PreviewMatrix {
    EmodulTiles(TileSize.S, emodulSamples + emodulSample("Temp. zewn.", EmodulIcon.THERMOMETER, "8,4°", minutesOld = 40, active = false) +
        emodulSample("Zawór 1", EmodulIcon.VALVE, "41,2°", listOf("zadana" to "42°"), minutesOld = 600), 104, 104)
}

@Preview(name = "EmodulTile M", widthDp = CELL_W * 2, heightDp = 2 * 460)
@Composable
private fun EmodulTileMediumPreview() = PreviewMatrix {
    EmodulTiles(TileSize.M, emodulSamples, 216, 104)
}

@Preview(name = "EmodulTile L", widthDp = CELL_W * 2, heightDp = 2 * 920)
@Composable
private fun EmodulTileLargePreview() = PreviewMatrix {
    EmodulTiles(TileSize.L, emodulSamples, 216, 216)
}

@Preview(name = "EmodulBanner", widthDp = CELL_W * 2, heightDp = 2 * 260)
@Composable
private fun EmodulBannerPreview() = PreviewMatrix {
    val now = System.currentTimeMillis()
    val since = Instant.ofEpochMilli(now - 40 * 60_000L)
    Column {
        EmodulBanner(EmodulAttention.ControllerSilent(since), now, ZoneId.systemDefault())
        EmodulBanner(EmodulAttention.CloudUnreachable(since), now, ZoneId.systemDefault())
        EmodulBanner(EmodulAttention.KeyRejected, now, ZoneId.systemDefault())
    }
}
