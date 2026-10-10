package app.domik.integrations.weather

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Ostatnia pobrana prognoza: surowa odpowiedź dostawcy ([raw]) i miejsce, którego dotyczy. Dzięki temu po
 * ponownym uruchomieniu bez internetu kafelek pokazuje ostatnią pogodę (oznaczoną jako nieaktualna), a nie pustkę.
 * To dane pogodowe miejscowości, nie dane osobowe.
 */
class CachedForecast(val latitude: Double, val longitude: Double, val fetchedAtMillis: Long, val raw: String) {
    fun isFor(place: WeatherPlace): Boolean = latitude == place.latitude && longitude == place.longitude
}

/** Pamięć podręczna ostatniej prognozy (jedna pozycja). Wywołania mogą blokować (dysk). */
interface WeatherCache {
    fun read(): CachedForecast?

    fun write(entry: CachedForecast)

    fun clear()
}

/** Bez pamięci podręcznej (testy). */
object NoWeatherCache : WeatherCache {
    override fun read(): CachedForecast? = null

    override fun write(entry: CachedForecast) = Unit

    override fun clear() = Unit
}

/**
 * Pamięć podręczna w pliku [file] (prywatny katalog aplikacji). Format: pierwsza linia
 * `1 <czas pobrania ms> <szerokość> <długość>`, dalej surowy JSON. Zapis przez plik tymczasowy i zmianę nazwy,
 * więc przerwany zapis nie psuje poprzedniej wersji. Błędy odczytu/zapisu są ignorowane (pamięć jest dodatkiem).
 */
class FileWeatherCache(private val file: File) : WeatherCache {
    override fun read(): CachedForecast? = try {
        val text = file.readText(Charsets.UTF_8)
        val newline = text.indexOf('\n')
        val header = if (newline > 0) text.substring(0, newline).split(' ') else emptyList()
        if (header.size == 4 && header[0] == VERSION) {
            val fetchedAt = header[1].toLongOrNull()
            val latitude = header[2].toDoubleOrNull()
            val longitude = header[3].toDoubleOrNull()
            if (fetchedAt != null && latitude != null && longitude != null) {
                CachedForecast(latitude, longitude, fetchedAt, text.substring(newline + 1))
            } else {
                null
            }
        } else {
            null
        }
    } catch (_: IOException) {
        null
    }

    override fun write(entry: CachedForecast) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            val header = String.format(Locale.ROOT, "%s %d %.2f %.2f\n", VERSION, entry.fetchedAtMillis, entry.latitude, entry.longitude)
            temp.writeText(header + entry.raw, Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // Brak pamięci podręcznej nie jest błędem.
        }
    }

    override fun clear() {
        file.delete()
    }

    private companion object {
        const val VERSION = "1"
    }
}
