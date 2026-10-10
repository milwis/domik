package app.domik.integrations.weather

import java.time.Instant

/** Odpowiedzi zmyślone ręcznie, w kształcie Open-Meteo (nie pochodzą z prawdziwych zapytań). */
internal object WeatherFixtures {
    /**
     * Prognoza dla fikcyjnego miejsca w strefie +02:00. Zawiera celowe luki (`null` w godzinach i dniach), żeby
     * sprawdzić, że parser pomija pojedyncze punkty, a nie całą prognozę.
     */
    const val FORECAST = """
    {
      "latitude": 50.06, "longitude": 19.94, "generationtime_ms": 0.12,
      "utc_offset_seconds": 7200, "timezone": "Europe/Warsaw", "timezone_abbreviation": "GMT+2",
      "elevation": 210.0,
      "current_units": {"time": "iso8601", "temperature_2m": "°C"},
      "current": {
        "time": "2026-10-10T14:00", "interval": 900,
        "temperature_2m": 13.4, "apparent_temperature": 11.9,
        "weather_code": 2, "is_day": 1, "wind_speed_10m": 14.6
      },
      "hourly": {
        "time": ["2026-10-10T13:00", "2026-10-10T14:00", "2026-10-10T15:00", "2026-10-10T16:00", "2026-10-10T17:00", "2026-10-10T18:00"],
        "temperature_2m": [12.8, 13.4, 13.9, null, 12.5, 11.0],
        "weather_code": [3, 2, 2, 61, 61, 3],
        "is_day": [1, 1, 1, 1, 1, 0]
      },
      "daily": {
        "time": ["2026-10-10", "2026-10-11", "2026-10-12"],
        "weather_code": [2, 61, 95],
        "temperature_2m_max": [14.1, 12.0, null],
        "temperature_2m_min": [7.2, 6.5, 5.0]
      }
    }
    """

    /** Chwila, w której miejsce z [FORECAST] ma 14:20 czasu lokalnego (UTC+2 = 12:20 UTC). */
    val NOW_MILLIS: Long = Instant.parse("2026-10-10T12:20:00Z").toEpochMilli()

    /** Wyniki wyszukiwania: dwie różne miejscowości, duplikat po zaokrągleniu i dwa wpisy do pominięcia. */
    const val GEOCODING = """
    {
      "results": [
        {"id": 1, "name": "Kraków", "latitude": 50.06143, "longitude": 19.93658, "country": "Polska", "country_code": "PL",
         "admin1": "Małopolskie", "admin2": "Kraków"},
        {"id": 2, "name": "Krakówek", "latitude": 51.1, "longitude": 17.0, "country": "Polska",
         "admin1": "Dolnośląskie", "admin2": "Wrocław"},
        {"id": 3, "name": "Kraków", "latitude": 50.0614, "longitude": 19.9366, "country": "Polska", "admin1": "Małopolskie"},
        {"id": 4, "name": "  ", "latitude": 1.0, "longitude": 2.0},
        {"id": 5, "name": "Poza zakresem", "latitude": 95.0, "longitude": 2.0}
      ],
      "generationtime_ms": 0.5
    }
    """

    /** Brak pola `results` – tak odpowiada Open-Meteo, gdy nic nie znaleziono. */
    const val GEOCODING_EMPTY = """{"generationtime_ms": 0.3}"""
}
