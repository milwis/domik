#!/usr/bin/env python3
"""Generuje SYNTETYCZNE bazy testowe konfiguracji sterownika (nie zawierają żadnych danych prawdziwego domu).

Uruchamiać z katalogu repo:  python3 -I tools/gen_config_db.py

Tworzy w testdata/:
- config_synthetic.db3       – baza z tabelami outputs / template_rooms / template_levels o schemacie jak w bazie
                               sterownika (page_size 1024), wymyślone dane, trudne przypadki dla czytnika SQLite
- config_synthetic_4096.db3  – ta sama zawartość logiczna, page_size 4096
- sqlite_deep.db3            – mała baza (page_size 512) z drzewem B o głębokości >= 3 (strony wewnętrzne pod wewnętrznymi)
- config_synthetic.expected.json – oczekiwane wyniki liczone PRZEZ PRAWDZIWY SQLite (moduł sqlite3): zrzut wszystkich
                               tabel oraz model konfiguracji (HomeConfig) wyliczony zapytaniem CONFIG_QUERY (niżej)
                               i regułami mapowania z DeimicConfig (testy Kotlin porównują z tym plikiem)

Pokryte przypadki: wiele modułów, wszystkie typy wyjść (1, 4, 5, 7, 8, 123), rolety (5, adres = output_nr, osobny model
Shutter; kolumny output_relay / can_on / outputs_number nie są czytane), czujniki temperatury (126, osobna
przestrzeń adresowa – ten sam numer co przekaźnik nie jest duplikatem), nieznane typy (99 oraz bez adresu przekaźnika:
201, 121, 2001, ... – raz UNSUPPORTED_OUTPUT_TYPE, bez innych problemów), identyfikatory „brak pomieszczenia / piętra”
(id_room 1000, id_level 0 – bez UNKNOWN_ROOM / UNKNOWN_LEVEL), wiersze ukryte
(show_on_panel <> 1), wiersze niepoprawne (NULL nazwa, przekaźnik 200, ...), 400+ wierszy (strony wewnętrzne),
bardzo długa nazwa i duże bloby (strony przepełnienia, granice wzoru na lokalny rozmiar ładunku), usunięte wiersze
(lista wolnych stron, wolne bloki), nieskładniowe CREATE TABLE (cudzysłowy, komentarze, ograniczenia tabeli),
ALTER TABLE ADD COLUMN, aliasy rowid, WITHOUT ROWID, widok, wyzwalacz, indeksy.
"""

from __future__ import annotations

import hashlib
import json
import random
import sqlite3
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "testdata"
sys.dont_write_bytecode = True

# --- schemat identyczny z bazą sterownika (tylko definicje, żadnych danych) ---------------------------------------

SCHEMA_LEVELS = "CREATE TABLE template_levels (id INTEGER UNIQUE PRIMARY KEY , Name TEXT , id_template INTEGER , have_rooms INTEGER)"
SCHEMA_ROOMS = (
    "CREATE TABLE template_rooms (id INTEGER UNIQUE PRIMARY KEY , id_level INTEGER , id_template INTEGER , "
    "Name TEXT , id_room_group INTEGER)"
)
SCHEMA_OUTPUTS = (
    'CREATE TABLE "outputs" ("id" INTEGER PRIMARY KEY ,"id_device" INTEGER,"output_nr" INTEGER,"id_level" INTEGER,'
    '"id_room" INTEGER,"output_type" INTEGER,"Name" TEXT,"show_on_panel" INTEGER,"pin_lock" INTEGER DEFAULT (0) ,'
    '"icon_id" INTEGER,"can_on" INTEGER DEFAULT (1) ,"id_device_in" INTEGER DEFAULT (0) ,"output_nr_in" INTEGER DEFAULT (0), '
    '"output_relay" INTEGER DEFAULT -1, "outputs_number" INTEGER DEFAULT 0, "question" INTEGER DEFAULT 0)'
)
INSERT_OUTPUT = "INSERT INTO outputs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

# Zapytanie wybierające wiersze `outputs` pokazywane w panelu (show_on_panel = 1) oraz strefy grzewcze (typ 123)
CONFIG_QUERY = (
    "SELECT o.id, o.id_device, o.output_nr, o.output_relay, o.outputs_number, o.output_type, o.Name, "
    "o.icon_id, o.id_room, o.id_level, o.can_on FROM outputs o WHERE o.show_on_panel = 1 OR o.output_type = 123 "
    "ORDER BY o.id"
)

KINDS = {1: "ON_OFF", 4: "RGB", 7: "LED", 8: "DIMMER_ON_OFF"}
ZONE_TYPE = 123
SENSOR_TYPE = 126
SHUTTER_TYPE = 5  # roleta: osobny model (Shutter), adres = output_nr, nie przekaźnik
# Znaczniki „brak pomieszczenia / piętra” stosowane w bazach sterownika (do potwierdzenia) – jak NO_ROOM_ID /
# NO_LEVEL_ID w Kotlinie: wartość zostaje, ale nie ma problemu UNKNOWN_ROOM / UNKNOWN_LEVEL.
NO_ROOM_ID = 1000
NO_LEVEL_ID = 0
# Teksty zastępcze – jak ConfigTexts.Polish w Kotlinie
FALLBACK_LEVEL = "Poziom {}"
FALLBACK_ROOM = "Pomieszczenie {}"
FALLBACK_OUTPUT = "Wyjście {}"
FALLBACK_ZONE = "Strefa {}"
FALLBACK_SENSOR = "Czujnik {}"
FALLBACK_SHUTTER = "Roleta {}"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# --- dane syntetyczne -----------------------------------------------------------------------------------------------

LEVELS = [
    (1, "Parter (test)", 1, 1),
    (2, "Piętro (test)", 1, 1),
    (3, "  Poddasze \t", 1, 1),  # białe znaki – po przycięciu "Poddasze"
    (4, "", 1, 0),  # pusta nazwa -> tekst zastępczy
    (5, None, 1, 0),  # NULL -> tekst zastępczy
]

ROOMS = [  # (id, id_level, id_template, Name, id_room_group)
    (1, 1, 1, "Salon (test)", 0),
    (2, 1, 1, "Kuchnia (test)", 0),
    (3, 1, 1, "Łazienka (test)", 0),
    (4, 2, 1, "Sypialnia", 0),
    (5, 2, 1, "Pokój dziecięcy", 0),
    (6, 2, 1, "Garderoba (test)", 0),
    (7, 1, 1, "Korytarz (test)", 0),
    (9, 3, 1, " Strych ", 0),  # przycięcie
    (11, None, 1, "Bez piętra", 0),  # id_level NULL
    (12, 2, 1, None, 0),  # NULL nazwa -> tekst zastępczy
    (13, 88, 1, "Pokój z nieznanym piętrem", 0),  # nieznane piętro -> UNKNOWN_LEVEL w template_rooms
    (14, "x", 1, "Pokój z błędnym piętrem", 0),  # tekst w kolumnie liczbowej -> pominięty
    (15, 0, 1, "Pokój bez piętra (test)", 0),  # id_level 0 = „brak piętra” -> bez UNKNOWN_LEVEL
]

LONG_NAME = ("Żółć gęślą jaźń – bardzo długa nazwa wyjścia, która musi trafić na strony przepełnienia. " * 40)[:2500]


def out(id_, dev, nr, level, room, typ, name, show=1, icon=0, can_on=1, relay=-1, num=0):
    """Wiersz outputs w kolejności kolumn tabeli."""
    return (id_, dev, nr, level, room, typ, name, show, 0, icon, can_on, 0, 0, relay, num, 0)


def explicit_outputs():
    rows = [
        # moduł 1: światła/gniazda (typ 1)
        out(1, 1, 0, 1, 1, 1, "Światło główne", icon=3, relay=0),
        out(2, 1, 0, 1, 1, 1, "Kinkiety", icon=3, relay=1),
        out(3, 1, 0, 1, 2, 1, "Światło nad blatem", icon=3, relay=2),
        out(4, 1, 0, 1, 2, 1, "Gniazdo ekspres", icon=7, can_on=0, relay=3),
        out(5, 1, 0, 1, 3, 1, "Lustro", icon=3, relay=4),
        out(6, 1, 0, 2, 4, 1, "Lampa nocna", icon=3, relay=5),
        out(7, 1, 0, 2, 6, 1, "Garderoba (test)", icon=3, relay=6),
        out(8, 1, 0, 1, 7, 1, "Schody (test)", icon=3, relay=7),
        out(9, 1, 0, 1, 1, 4, "Taśma RGB", icon=9, relay=8, num=3),
        out(10, 1, 0, 1, 1, 7, "LED sufit", icon=9, relay=9),
        out(11, 1, 0, 1, 1, 8, "Ściemniacz", icon=9, relay=10),
        out(12, 1, 0, 1, 1, 5, "Roleta salon", icon=5, relay=20, num=2),
        out(13, 1, 1, 1, 1, 5, "Roleta taras", icon=5, relay=22, num=2),
        # strefy grzewcze (typ 123): output_nr != output_relay
        out(20, 1, 0, 1, 1, 123, "Salon (test)", show=1, relay=25),
        out(21, 1, 1, 1, 2, 123, "Kuchnia (test)", show=0, relay=26),  # wybrany przez typ, mimo show_on_panel = 0
        out(22, 1, 2, 1, 3, 123, "Łazienka (test)", show=None, relay=27),
        out(23, 1, 3, 2, 4, 123, "   ", show=1, relay=28),  # pusta nazwa -> "Strefa 3"
        out(24, 1, 4, 2, 5, 123, "Pokój dziecięcy", show=1, relay=None),  # relay NULL nie przeszkadza strefie
        # moduł 3
        out(30, 3, 0, 1, 1, 1, "Taras", icon=3, relay=0),
        out(31, 3, 0, 1, 7, 1, "Wiatrołap", icon=3, relay=1),
        out(32, 3, 0, 1, 1, 1, "Ogród", icon=3, relay=2),
        out(33, 3, 0, 1, 1, 1, "Brama", icon=11, relay=3),
        out(34, 3, 0, 1, 1, 1, "Podjazd", icon=3, relay=4),
        out(35, 3, 0, 1, 1, 123, "Garaż", show=1, relay=25),
        out(36, 3, 1, 1, 1, 123, "Garaż 2", show=1, relay=26),
        # nieznane typy
        out(40, 1, 0, 1, 1, 99, "Nieznany typ", relay=50),
        out(41, 1, 0, 1, 1, 99, "Nieznany ukryty", show=0, relay=51),  # odfiltrowany
        out(42, 1, 0, 1, 1, 2, "Typ dwa", relay=52),
        # odfiltrowane przez show_on_panel
        out(50, 1, 0, 1, 1, 1, "Ukryte zero", show=0, relay=70),
        out(51, 1, 0, 1, 1, 1, "Ukryte NULL", show=None, relay=71),
        out(52, 1, 0, 1, 1, 1, "Ukryte dwa", show=2, relay=72),
        # białe znaki, puste nazwy
        out(60, 1, 0, 1, 1, 1, "  \tŚwiatło z odstępami  ", relay=80),
        out(61, 1, 0, 1, 1, 1, "", relay=81),  # -> "Wyjście 81"
        out(62, 1, 0, 1, 1, 1, "   ", relay=82),  # -> "Wyjście 82"
        # długa nazwa (strony przepełnienia)
        out(63, 1, 0, 1, 1, 1, LONG_NAME, relay=83),
        # odwołania do nieistniejących pomieszczeń / pięter oraz NULL
        out(64, 1, 0, 77, 99, 1, "Nieznany pokój", relay=84),
        out(65, 1, 0, None, None, 1, "Bez pokoju", relay=85),
        # duplikat adresu (1, 5) i strefy (1, 0)
        out(66, 1, 0, 1, 1, 1, "Duplikat adresu", relay=5),
        out(67, 1, 0, 1, 1, 123, "Duplikat strefy", relay=33),
        # granice zakresów
        out(70, 1, 0, 1, 1, 1, "Przekaźnik 100", relay=100),
        out(71, 1, 0, 1, 1, 1, "Przekaźnik 127", relay=127),
        out(72, 1, 31, 1, 1, 123, "Strefa 31", relay=None),
        # niepoprawne – pomijane z zapisem w issues
        out(80, 1, 0, 1, 1, 1, None, relay=90),  # NULL nazwa
        out(81, 1, 0, 1, 1, 1, "Przekaźnik 200", relay=200),
        out(82, 1, 0, 1, 1, 1, "Przekaźnik -1", relay=-1),
        out(83, 1, 0, 1, 1, 1, "Przekaźnik NULL", relay=None),
        out(84, None, 0, 1, 1, 1, "Bez modułu", relay=91),
        out(85, 0, 0, 1, 1, 1, "Moduł zero", relay=92),
        out(86, 1, 0, 1, 1, None, "Bez typu", relay=93),
        out(87, 1, 40, 1, 1, 123, "Strefa 40", relay=None),
        out(88, 1, None, 1, 1, 123, "Strefa bez numeru", relay=None),
        out(89, 1, 0, 1, 1, 123, None, relay=None),  # strefa bez nazwy
        out(90, 1, 0, 1, 1, 1, "Tekst w przekaźniku", relay="abc"),
        out(91, 1, 0, 1, 1, 1, "Ułamek w przekaźniku", relay=3.5),
        out(92, 1, 0, 1, "x", 1, "Tekst w pokoju", relay=94),
        out(93, 1, 0, 1, 1, 1, "Blob w ikonie", relay=95, icon=b"\x00\x01"),
        out(94, 1, 0, 1, 1, 1, 12345, relay=96),  # liczba w kolumnie TEXT -> SQLite zapisuje tekst "12345"
    ]
    return rows + sensor_and_unsupported_outputs() + shutter_outputs()


def sensor_and_unsupported_outputs():
    """Czujniki temperatury (126), typy nieobsługiwane i znaczniki „brak pomieszczenia / piętra”."""
    return [
        # czujniki: osobna przestrzeń adresowa – output_relay powtarza numer (jak w prawdziwej bazie) i koliduje
        # z przekaźnikami (1:0, 1:2, 1:5, 3:0), a mimo to nie ma DUPLICATE_ADDRESS
        out(500, 1, 0, 1, 1, SENSOR_TYPE, "Czujnik salon (test)", can_on=0, relay=0),
        out(501, 1, 2, 1, 2, SENSOR_TYPE, "Czujnik kuchnia (test)", can_on=0, relay=2),
        out(502, 3, 0, 1, 7, SENSOR_TYPE, "Czujnik korytarz (test)", can_on=0, relay=0),
        out(503, 1, 5, 2, 4, SENSOR_TYPE, "Czujnik sypialnia (test)", can_on=0, relay=5),
        out(504, 1, 0, 1, 1, SENSOR_TYPE, "Czujnik powtórzony (test)", can_on=0, relay=0),  # duplikat czujnika 1:0
        out(505, 1, 7, 1, 1, SENSOR_TYPE, "  ", can_on=0, relay=7),  # pusta nazwa -> "Czujnik 7"
        out(506, 1, 6, 1, 1, SENSOR_TYPE, None, relay=6),  # NULL nazwa -> pominięty
        out(507, 1, None, 1, 1, SENSOR_TYPE, "Czujnik bez numeru (test)", relay=0),  # NULL output_nr -> pominięty
        out(508, 1, 128, 1, 1, SENSOR_TYPE, "Czujnik numer 128 (test)", relay=0),  # poza zakresem -> pominięty
        out(509, 1, 8, 1, 1, SENSOR_TYPE, "Czujnik ukryty (test)", show=0, relay=8),  # odfiltrowany (show_on_panel = 0)
        out(510, 1, 127, NO_LEVEL_ID, NO_ROOM_ID, SENSOR_TYPE, "Czujnik bez pomieszczenia (test)", relay=-1),
        out(511, 0, 9, 1, 1, SENSOR_TYPE, "Czujnik bez modułu (test)", relay=9),  # id_device 0 -> pominięty
        out(512, 1, 10, 1, 55, SENSOR_TYPE, "Czujnik w nieznanym pokoju (test)", relay=10),  # UNKNOWN_ROOM
        out(513, 1, 11, 1, 1, SENSOR_TYPE, "Czujnik z ikoną-tekstem (test)", icon="x", can_on="x", relay=-1),  # nieczytane kolumny
        # typy nieobsługiwane bez adresu przekaźnika (output_relay = -1 itp.): raz UNSUPPORTED_OUTPUT_TYPE, nic więcej
        out(520, 515, 0, 0, 0, 201, "Wirtualne wejście 0 (test)"),
        out(521, 515, 1, 0, 0, 201, "Wirtualne wejście 1 (test)"),
        out(522, 515, 2, 0, 0, 201, None),  # NULL nazwa nie robi z tego SKIPPED_MISSING_VALUE
        out(523, 0, 0, NO_LEVEL_ID, NO_ROOM_ID, 121, "Typ 121 bez modułu (test)"),  # id_device 0
        out(524, 2, -1, NO_LEVEL_ID, NO_ROOM_ID, 2001, "Typ 2001 (test)"),
        out(525, 3, None, 9, 99, 2001, "Typ 2001 z NULL (test)", relay=None),
        out(526, 1, 0, 1, 1, 99, None, relay=None),  # nieznany typ, NULL nazwa i przekaźnik
        out(527, 1, 0, 1, 1, 99, "Nieznany typ ze złym przekaźnikiem (test)", relay=200),
        # nieznany typ z poprawnym adresem: zachowany jako UNKNOWN, ale bez innych problemów (pokój / piętro / duplikat)
        out(528, 1, 0, 77, 99, 99, "Nieznany typ w nieznanym pokoju (test)", relay=53),
        out(529, 1, 0, 1, 1, 99, "Nieznany typ jak lampa (test)", relay=1),  # ten sam adres co wyjście 1:1 – nie duplikat
        # „brak pomieszczenia / piętra” (1000 / 0) na obsługiwanych typach: bez UNKNOWN_ROOM / UNKNOWN_LEVEL
        out(530, 1, 0, NO_LEVEL_ID, NO_ROOM_ID, 1, "Światło bez pomieszczenia (test)", relay=120),
        out(531, 1, 6, NO_LEVEL_ID, NO_ROOM_ID, ZONE_TYPE, "Strefa bez pomieszczenia (test)", relay=None),
        out(532, 1, 4, NO_LEVEL_ID, NO_ROOM_ID, 5, "Roleta bez pomieszczenia (test)", relay=121),
        out(533, 1, 0, 1, NO_ROOM_ID, 1, "Tylko znacznik pomieszczenia (test)", relay=122),
        out(534, 1, 0, NO_LEVEL_ID, 1, 1, "Tylko znacznik piętra (test)", relay=123),
    ]


def shutter_outputs():
    """Rolety (5): adres = output_nr (0..63); output_relay, can_on i outputs_number nie są czytane."""
    return [
        out(540, 1, 2, 1, 2, SHUTTER_TYPE, "Roleta sypialnia (test)", icon=5, relay=24, num=2),
        out(541, 1, 3, 1, 3, SHUTTER_TYPE, "  ", icon=None, relay=None, num=None),  # pusta nazwa -> "Roleta 3", relay NULL ok
        out(542, 1, 0, 1, 1, SHUTTER_TYPE, "Roleta powtórzona (test)", relay=26, num=2),  # duplikat rolety 1:0
        out(543, 1, None, 1, 1, SHUTTER_TYPE, "Roleta bez numeru (test)", relay=28),  # NULL output_nr -> pominięta
        out(544, 1, 64, 1, 1, SHUTTER_TYPE, "Roleta numer 64 (test)", relay=30),  # poza zakresem -> pominięta
        out(545, 1, 63, 1, 1, SHUTTER_TYPE, "Roleta numer 63 (test)", relay=-1),  # granica zakresu
        out(546, 1, 7, 1, 1, SHUTTER_TYPE, None, relay=32),  # NULL nazwa -> pominięta
        out(547, 0, 8, 1, 1, SHUTTER_TYPE, "Roleta bez modułu (test)", relay=34),  # id_device 0 -> pominięta
        out(548, 1, 9, 1, 1, SHUTTER_TYPE, "Roleta ukryta (test)", show=0, relay=36),  # odfiltrowana (show_on_panel = 0)
        out(549, 1, 5, 1, 55, SHUTTER_TYPE, "Roleta w nieznanym pokoju (test)", relay=38),  # UNKNOWN_ROOM
        out(550, 1, 6, 1, 1, SHUTTER_TYPE, "Roleta z nieczytanymi kolumnami (test)", can_on="x", relay="abc", num="x"),
        out(551, 1, 10, 1, 1, SHUTTER_TYPE, "Roleta z ikoną-tekstem (test)", icon="x", relay=40),  # ikona czytana -> pominięta
        out(552, 3, 0, 1, 7, SHUTTER_TYPE, "Roleta garaż (test)", relay=6, num=2),  # ten sam numer, inny moduł
    ]


def bulk_outputs(rng: random.Random):
    """Wiersze masowe: moduły 10..14 po 72 wyjścia, adresy unikalne. Ok. 25 % ukrytych."""
    bases = ["Światło", "Gniazdo", "Lampa", "Kinkiet", "Taśma LED", "Oświetlenie schodów", "Żyrandol", "Zasilacz"]
    rows = []
    next_id = 100
    for module in range(10, 15):
        for relay in range(72):
            typ = rng.choice([1, 1, 1, 1, 1, 1, 4, 5, 7, 8])
            show = 0 if rng.random() < 0.25 else 1
            room = rng.choice([1, 2, 3, 4, 5, 6, 7, None])
            level = None if room is None else {1: 1, 2: 1, 3: 1, 4: 2, 5: 2, 6: 2, 7: 1}[room]
            name = f"{rng.choice(bases)} {module}/{relay}"
            can_on = rng.choice([1, 1, 1, 0, None])
            icon = rng.randrange(0, 20)
            number = rng.choice([0, 0, 2, 3])
            if typ == SHUTTER_TYPE and relay >= 64:
                typ = 1  # numer rolety 0..63; wyższe przekaźniki to zwykłe wyjścia
            rows.append(out(next_id, module, relay if typ == SHUTTER_TYPE else 0, level, room, typ, name, show, icon,
                            can_on, relay, number))
            next_id += 1
    # kilka stref w modułach masowych
    for module in (10, 11):
        for nr in range(4):
            rows.append(out(next_id, module, nr, 1, 1, 123, f"Strefa masowa {module}/{nr}", 1, 0, 1, 30 + nr, 0))
            next_id += 1
    return rows


def filler_outputs():
    """Wiersze wstawiane i od razu usuwane – tworzą listę wolnych stron i wolne bloki."""
    return [out(5000 + i, 99, 0, 1, 1, 1, f"Wypełniacz {i} " + "z" * 40, 1, 0, 1, i % 100, 0) for i in range(180)]


# --- tabele pomocnicze do testów czytnika SQLite --------------------------------------------------------------------

ZOO_VALUES = [
    None, 0, 1, -1, 2, 127, -128, 128, -129, 255, 32767, -32768, 32768, -32769, 65535, 8388607, -8388608, 8388608,
    -8388609, 2147483647, -2147483648, 2147483648, -2147483649, 140737488355327, -140737488355328, 140737488355328,
    -140737488355329, 9223372036854775807, -9223372036854775808,
    0.0, -0.0, 1.5, -2.25, 3.141592653589793, 1e300, -1e-300, 5e-324, float("inf"), float("-inf"),
    "", "a", "abc", "Zażółć gęślą jaźń", "Ünïcödé € 😀 \u0000 po NUL-u", "x" * 57, "y" * 58, "z" * 200,
    b"", b"\x00", b"\x00\x01\xff", bytes(range(256)), bytes(300),
]


def utf8_varint_len(value: int) -> int:
    """Długość zmiennej długości liczby całkowitej SQLite (varint) dla wartości dodatniej."""
    if value < 0:
        return 9
    for length in range(1, 9):
        if value < (1 << (7 * length)):
            return length
    return 9


def overflow_targets(page_size: int) -> list[int]:
    """Rozmiary ładunku P wokół granic wzoru na lokalny rozmiar ładunku liścia tabeli (strona bez zarezerwowanych bajtów)."""
    usable = page_size
    max_local = usable - 35  # X
    min_local = ((usable - 12) * 32) // 255 - 23  # M
    step = usable - 4
    targets = {max_local - 1, max_local, max_local + 1, max_local + 2, min_local + 1, min_local + step}
    for j in (1, 2) if page_size <= 1024 else (1,):
        targets.add(min_local + step * j + (max_local - min_local))  # K == X  -> lokalnie K
        targets.add(min_local + step * j + (max_local - min_local) + 1)  # K == X + 1 -> lokalnie M
    return sorted(targets)


def text_for_payload(target: int) -> str:
    """Tekst ASCII o takiej długości, żeby rekord (id INTEGER PRIMARY KEY, t TEXT) miał ładunek dokładnie `target` bajtów."""
    for n in range(max(0, target - 8), target):
        header = 1 + 1 + utf8_varint_len(13 + 2 * n)
        if header + n == target:
            return "".join(chr(97 + (i * 7 + i // 13) % 26) for i in range(n))
    raise AssertionError(target)


def create_helper_tables(con: sqlite3.Connection, page_size: int, rng: random.Random) -> None:
    # wartości o wszystkich typach serialnych (kolumna bez afiniczności -> typ wartości zachowany)
    con.execute("CREATE TABLE type_zoo (id INTEGER PRIMARY KEY, v)")
    for index, value in enumerate(ZOO_VALUES, start=1):
        con.execute("INSERT INTO type_zoo VALUES (?, ?)", (index, value))
    con.execute("INSERT INTO type_zoo VALUES (?, ?)", (len(ZOO_VALUES) + 1, bytes(rng.randrange(256) for _ in range(9000))))
    con.execute("INSERT INTO type_zoo VALUES (?, ?)", (len(ZOO_VALUES) + 2, "Zażółć " * 500))

    # ładunki o rozmiarach na granicach wzoru lokalnego rozmiaru
    con.execute("CREATE TABLE overflow_probe (id INTEGER PRIMARY KEY, t TEXT)")
    for index, target in enumerate(overflow_targets(page_size), start=1):
        con.execute("INSERT INTO overflow_probe VALUES (?, ?)", (index, text_for_payload(target)))

    # dziwne CREATE TABLE: cudzysłowy, komentarze, ograniczenia tabeli, nawiasy w wartościach domyślnych
    con.execute(
        'CREATE TABLE "weird ""name""" ("a b" INTEGER PRIMARY KEY, `c` TEXT NOT NULL DEFAULT \'x,y)(\', '
        '[d e] REAL CHECK ("a b" > 0 AND (1+1)=2), "f" NUMERIC(10,5) -- komentarz, z przecinkiem (\n'
        ', g VARCHAR(10) COLLATE NOCASE, /* komentarz, ( */ '
        'UNIQUE ("c", [d e]), CONSTRAINT fk FOREIGN KEY (c) REFERENCES outputs(Name) ON DELETE CASCADE, CHECK (g <> \'\'))'
    )
    for index in range(1, 8):
        con.execute('INSERT INTO "weird ""name""" VALUES (?,?,?,?,?)', (index, f"c{index}", index / 4, index * 1.5, "G" * index))

    # ALTER TABLE ADD COLUMN: stare wiersze mają krótsze rekordy (brakujące wartości = NULL, bez DEFAULT)
    con.execute("CREATE TABLE altered (id INTEGER PRIMARY KEY, a TEXT)")
    for index in range(1, 6):
        con.execute("INSERT INTO altered VALUES (?, ?)", (index, f"a{index}"))
    con.execute("ALTER TABLE altered ADD COLUMN b INTEGER")
    con.execute('ALTER TABLE altered ADD COLUMN "c d" TEXT')
    for index in range(6, 10):
        con.execute("INSERT INTO altered VALUES (?, ?, ?, ?)", (index, f"a{index}", index * 100, f"cd{index}"))
    con.execute("UPDATE altered SET b = 7 WHERE id = 2")

    # ALTER TABLE ADD COLUMN z DEFAULT: wiersze zapisane wcześniej zwracają wartość domyślną (z afiniczością kolumny)
    con.execute("CREATE TABLE altered_defaults (id INTEGER PRIMARY KEY, a TEXT)")
    for index in range(1, 4):
        con.execute("INSERT INTO altered_defaults (id, a) VALUES (?, ?)", (index, f"a{index}"))
    default_columns = [
        "i1 INTEGER DEFAULT 5", "i2 INTEGER DEFAULT -7", "i3 INTEGER DEFAULT +3", "i4 INTEGER DEFAULT '9'",
        "i5 INTEGER DEFAULT 2.0", "i6 INTEGER DEFAULT 0x1F", "i7 INTEGER DEFAULT NULL", "i8 INTEGER DEFAULT TRUE",
        "r1 REAL DEFAULT 3", "r2 REAL DEFAULT 1.5", "r3 REAL DEFAULT '2'", "t1 TEXT DEFAULT 'it''s'",
        "t2 TEXT DEFAULT 42", "t3 VARCHAR(10) DEFAULT abc", "n1 NUMERIC DEFAULT '12'", "n2 NUMERIC DEFAULT '1.25'",
        "n3 DEFAULT 4", "n4 DEFAULT 'abc'", "b1 BLOB DEFAULT x'0a0b'", "c1 INTEGER NOT NULL DEFAULT 6",
        "c2 INTEGER DEFAULT 8 NOT NULL", "c3 INTEGER CHECK (c3 > 0) DEFAULT 11", "c4 INTEGER DEFAULT (4)",
        "c5 INTEGER DEFAULT 1e1", "c6 REAL DEFAULT -1.5e-1", "c7 BOOLEAN DEFAULT FALSE", "c8 INTEGER DEFAULT -0x10",
        "c9 INTEGER DEFAULT 9223372036854775807", "c10 INTEGER DEFAULT 99999999999999999999",
        "c11 TEXT DEFAULT \"dq\"", "c12 DECIMAL(10,2) DEFAULT 7", "c13 INTEGER DEFAULT 4.5", "c14 INTEGER",
    ]
    for column in default_columns[:12]:
        con.execute(f"ALTER TABLE altered_defaults ADD COLUMN {column}")
    for index in range(4, 6):  # rekordy z pierwszą partią kolumn, bez drugiej
        con.execute("INSERT INTO altered_defaults (id, a) VALUES (?, ?)", (index, f"a{index}"))
    for column in default_columns[12:]:
        con.execute(f"ALTER TABLE altered_defaults ADD COLUMN {column}")
    con.execute("INSERT INTO altered_defaults (id, a) VALUES (6, 'a6')")  # pełny rekord z wartościami domyślnymi
    con.execute("INSERT INTO altered_defaults (id, a, i1, i2, t1, c1) VALUES (7, 'a7', NULL, NULL, NULL, 99)")  # jawne NULL nie jest zastępowane

    # aliasy rowid
    con.execute("CREATE TABLE alias_lower (id integer primary key autoincrement, v TEXT)")  # alias + AUTOINCREMENT
    con.execute("CREATE TABLE alias_asc (id INTEGER PRIMARY KEY ASC, v TEXT)")  # alias
    con.execute('CREATE TABLE alias_table_pk ("id" INTEGER, v TEXT, PRIMARY KEY ("id"))')  # alias przez ograniczenie tabeli
    con.execute("CREATE TABLE no_alias_desc (k INTEGER PRIMARY KEY DESC, v TEXT)")  # NIE alias (DESC)
    con.execute("CREATE TABLE no_alias_int (k INT PRIMARY KEY, v TEXT)")  # NIE alias (typ INT)
    con.execute("CREATE TABLE no_alias_composite (a INTEGER, b INTEGER, v TEXT, PRIMARY KEY (a, b))")  # NIE alias
    for table, key in (("alias_lower", "id"), ("alias_asc", "id"), ("alias_table_pk", "id"),
                       ("no_alias_desc", "k"), ("no_alias_int", "k")):
        for index in range(1, 5):
            con.execute(f"INSERT INTO {table} ({key}, v) VALUES (?, ?)", (index * 1000 + 7, f"v{index}"))
    for index in range(1, 5):
        con.execute("INSERT INTO no_alias_composite VALUES (?, ?, ?)", (index, index * 2, f"v{index}"))
    # id INTEGER PRIMARY KEY bez wartości: SQLite przydziela rowid
    con.execute("INSERT INTO alias_asc (v) VALUES ('auto')")

    # WITHOUT ROWID – czytnik ma zgłosić czytelny błąd
    con.execute("CREATE TABLE without_rowid_table (k TEXT PRIMARY KEY, v INTEGER) WITHOUT ROWID")
    con.execute("INSERT INTO without_rowid_table VALUES ('a', 1), ('b', 2)")

    # AUTOINCREMENT tworzy sqlite_sequence; indeksy, widok i wyzwalacz mają mieć rootpage 0 / być pomijane
    con.execute("CREATE INDEX idx_outputs_device ON outputs (id_device, output_nr)")
    con.execute("CREATE INDEX idx_outputs_name ON outputs (Name)")
    con.execute("CREATE UNIQUE INDEX idx_rooms_name ON template_rooms (id, Name)")
    con.execute("CREATE VIEW view_panel AS SELECT id, Name FROM outputs WHERE show_on_panel = 1")
    con.execute("CREATE TABLE trigger_log (id INTEGER PRIMARY KEY, what TEXT)")
    con.execute(
        "CREATE TRIGGER trg_levels AFTER INSERT ON template_levels BEGIN INSERT INTO trigger_log (what) VALUES ('level'); END"
    )


def create_deep_tree(path: Path) -> dict:
    """Baza z tabelą tak dużą względem page_size 512, że drzewo B ma co najmniej 3 poziomy."""
    if path.exists():
        path.unlink()
    rng = random.Random(512)
    con = sqlite3.connect(path, isolation_level=None)
    con.execute("PRAGMA page_size = 512")
    con.execute("CREATE TABLE bulk_numbers (id INTEGER PRIMARY KEY, v INTEGER, t TEXT)")
    con.execute("BEGIN")
    total, count = 0, 6000
    for index in range(1, count + 1):
        value = rng.choice([0, 1, rng.randrange(-100, 100), rng.randrange(-30000, 30000), rng.randrange(-(2**40), 2**40)])
        total += value
        con.execute("INSERT INTO bulk_numbers VALUES (?, ?, ?)", (index * 2, value, "t" * (index % 5)))
    con.execute("COMMIT")
    root = con.execute("SELECT rootpage FROM sqlite_master WHERE name = 'bulk_numbers'").fetchone()[0]
    con.close()
    data = path.read_bytes()
    depth = btree_depth(data, 512, root)
    assert depth >= 3, depth
    return {
        "file": path.name, "pageSize": 512, "sha256": sha256(data), "table": "bulk_numbers", "rowCount": count,
        "valueSum": total, "treeDepth": depth, "firstRowId": 2, "lastRowId": 2 * count,
    }


def btree_depth(data: bytes, page_size: int, root: int) -> int:
    depth, page = 1, root
    while True:
        base = (page - 1) * page_size + (100 if page == 1 else 0)
        kind = data[base]
        if kind == 0x0D:
            return depth
        assert kind == 0x05, hex(kind)
        cell_offset = struct.unpack(">H", data[base + 12:base + 14])[0]
        page = struct.unpack(">I", data[(page - 1) * page_size + cell_offset:(page - 1) * page_size + cell_offset + 4])[0]
        depth += 1


# --- budowa pliku -----------------------------------------------------------------------------------------------------

def build_database(path: Path, page_size: int) -> None:
    if path.exists():
        path.unlink()
    rng = random.Random(20261010)
    con = sqlite3.connect(path, isolation_level=None)
    con.execute(f"PRAGMA page_size = {page_size}")
    con.execute("PRAGMA journal_mode = DELETE")
    con.execute("BEGIN")
    con.execute(SCHEMA_LEVELS)
    con.execute(SCHEMA_ROOMS)
    con.execute(SCHEMA_OUTPUTS)
    con.executemany("INSERT INTO template_levels VALUES (?,?,?,?)", LEVELS)
    con.executemany("INSERT INTO template_rooms VALUES (?,?,?,?,?)", ROOMS)
    rows = explicit_outputs() + bulk_outputs(rng)
    con.executemany(INSERT_OUTPUT, rows)
    create_helper_tables(con, page_size, rng)
    # wypełniacze: wstaw i usuń (lista wolnych stron + wolne bloki wewnątrz stron)
    con.executemany(INSERT_OUTPUT, filler_outputs())
    con.execute("COMMIT")
    con.execute("BEGIN")
    con.execute("DELETE FROM outputs WHERE id >= 5000")
    con.execute("DELETE FROM outputs WHERE id BETWEEN 110 AND 118 OR id IN (140, 141, 200, 300)")  # dziury w środku
    con.execute("DELETE FROM type_zoo WHERE id IN (3, 5)")
    con.execute("COMMIT")
    assert con.execute("PRAGMA freelist_count").fetchone()[0] > 0
    assert con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    con.close()
    for suffix in ("-journal", "-wal", "-shm"):
        Path(str(path) + suffix).unlink(missing_ok=True)


# --- oczekiwane wyniki ----------------------------------------------------------------------------------------------

def encode_value(value):
    if value is None:
        return None
    if isinstance(value, bool):
        raise AssertionError("bool")
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return {"real": struct.pack(">d", value).hex()}
    if isinstance(value, str):
        raw = value.encode("utf-8")
        return value if len(value) <= 300 else {"text": {"len": len(raw), "sha256": sha256(raw)}}
    if isinstance(value, bytes):
        return {"blob": {"len": len(value), "sha256": sha256(value)}}
    raise AssertionError(type(value))


def dump_tables(con: sqlite3.Connection) -> tuple[list[str], dict]:
    names = [r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type = 'table' AND rootpage > 0 ORDER BY rowid")]
    tables = {}
    for name in names:
        quoted = '"' + name.replace('"', '""') + '"'
        columns = [r[1] for r in con.execute(f"PRAGMA table_info({quoted})")]
        sql = con.execute("SELECT sql FROM sqlite_master WHERE name = ?", (name,)).fetchone()[0]
        without_rowid = "WITHOUT ROWID" in sql.upper().split(")")[-1]
        entry = {"columns": columns, "withoutRowid": without_rowid}
        if not without_rowid:
            entry["rows"] = [
                [encode_value(v) for v in row]
                for row in con.execute(f"SELECT rowid, * FROM {quoted} ORDER BY rowid")
            ]
        tables[name] = entry
    return names, tables


class Skip(Exception):
    def __init__(self, column: str, missing: bool):
        self.column, self.missing = column, missing


INT_MIN, INT_MAX = -(2**31), 2**31 - 1


def expect_int(value, column: str, low=INT_MIN, high=INT_MAX, required=True):
    """Liczba całkowita w zakresie Int; NULL -> None (albo Skip, gdy wymagana); inny typ lub zakres -> Skip."""
    if value is None:
        if required:
            raise Skip(column, True)
        return None
    if isinstance(value, float) and value.is_integer():
        value = int(value)  # liczba całkowita zapisana jako REAL
    if not isinstance(value, int) or isinstance(value, bool) or value < low or value > high:
        raise Skip(column, False)
    return value


def check_name(value) -> str:
    """Nazwa wyjścia: NULL -> Skip (brak), nie-tekst -> Skip (niepoprawna), tekst -> przycięty (może być pusty)."""
    if value is None:
        raise Skip("Name", True)
    if not isinstance(value, str):
        raise Skip("Name", False)
    return value.strip()


def issue(kind: str, table: str, row_id: int, column=None) -> dict:
    return {"kind": kind, "table": table, "rowId": row_id, "column": column}


def expected_config(con: sqlite3.Connection) -> dict:
    issues = []

    levels = []
    for (id_, name) in con.execute("SELECT id, Name FROM template_levels ORDER BY id"):
        try:
            expect_int(id_, "id")
            text = clean_name_optional(name, FALLBACK_LEVEL.format(id_))
        except Skip as skip:
            issues.append(issue("SKIPPED_INVALID_VALUE", "template_levels", id_, skip.column))
            continue
        levels.append({"id": id_, "name": text})
    level_ids = {level["id"] for level in levels}

    rooms = []
    for (id_, level_id, name) in con.execute("SELECT id, id_level, Name FROM template_rooms ORDER BY id"):
        try:
            expect_int(id_, "id")
            level = expect_int(level_id, "id_level", required=False)
            text = clean_name_optional(name, FALLBACK_ROOM.format(id_))
        except Skip as skip:
            issues.append(issue("SKIPPED_INVALID_VALUE", "template_rooms", id_, skip.column))
            continue
        rooms.append({"id": id_, "levelId": level, "name": text})
        if level is not None and level != NO_LEVEL_ID and level not in level_ids:
            issues.append(issue("UNKNOWN_LEVEL", "template_rooms", id_, "id_level"))
    room_ids = {room["id"] for room in rooms}

    outputs, zones, sensors, shutters = [], [], [], []
    # osobna przestrzeń adresowa dla przekaźników, stref, rolet i czujników – nie porównujemy ich ze sobą
    seen_outputs, seen_zones, seen_sensors, seen_shutters = set(), set(), set(), set()

    def check_place(row_id, room, level):
        if room is not None and room != NO_ROOM_ID and room not in room_ids:
            issues.append(issue("UNKNOWN_ROOM", "outputs", row_id, "id_room"))
        if level is not None and level != NO_LEVEL_ID and level not in level_ids:
            issues.append(issue("UNKNOWN_LEVEL", "outputs", row_id, "id_level"))

    def read_output(row, typ):
        """Wyjście przekaźnikowe (typ znany lub nieznany, ale z adresem przekaźnika); Skip dla złej wartości."""
        row_id, device, output_nr, relay, number, _, name, icon, room, level, can_on = row
        device = expect_int(device, "id_device", low=1)
        raw_name = check_name(name)
        relay = expect_int(relay, "output_relay", low=0, high=127)
        icon = expect_int(icon, "icon_id", required=False)
        room = expect_int(room, "id_room", required=False)
        level = expect_int(level, "id_level", required=False)
        can_on = expect_int(can_on, "can_on", required=False)
        number = expect_int(number, "outputs_number", required=False)
        return {
            "dbId": row_id, "module": device, "relay": relay, "kind": KINDS.get(typ, "UNKNOWN"), "rawType": typ,
            "name": raw_name or FALLBACK_OUTPUT.format(relay), "roomId": room, "levelId": level, "iconId": icon,
            "canOn": True if can_on is None else can_on != 0, "outputsNumber": number,
        }

    for row in con.execute(CONFIG_QUERY):
        row_id, device, output_nr, relay, number, typ, name, icon, room, level, can_on = row
        try:
            typ = expect_int(typ, "output_type")
            if typ == ZONE_TYPE:
                device = expect_int(device, "id_device", low=1)
                raw_name = check_name(name)
                output_nr = expect_int(output_nr, "output_nr", low=0, high=31)
                expect_int(icon, "icon_id", required=False)  # nieużywane, ale zła wartość pomija wiersz
                room = expect_int(room, "id_room", required=False)
                level = expect_int(level, "id_level", required=False)
                expect_int(can_on, "can_on", required=False)
                expect_int(number, "outputs_number", required=False)
                zones.append({
                    "dbId": row_id, "module": device, "outputNr": output_nr, "name": raw_name or FALLBACK_ZONE.format(output_nr),
                    "roomId": room, "levelId": level,
                })
                if (device, output_nr) in seen_zones:
                    issues.append(issue("DUPLICATE_ADDRESS", "outputs", row_id))
                seen_zones.add((device, output_nr))
                check_place(row_id, room, level)
            elif typ == SHUTTER_TYPE:
                device = expect_int(device, "id_device", low=1)
                raw_name = check_name(name)
                output_nr = expect_int(output_nr, "output_nr", low=0, high=63)
                icon = expect_int(icon, "icon_id", required=False)
                room = expect_int(room, "id_room", required=False)
                level = expect_int(level, "id_level", required=False)
                shutters.append({
                    "dbId": row_id, "module": device, "outputNr": output_nr, "name": raw_name or FALLBACK_SHUTTER.format(output_nr),
                    "roomId": room, "levelId": level, "iconId": icon,
                })
                if (device, output_nr) in seen_shutters:
                    issues.append(issue("DUPLICATE_ADDRESS", "outputs", row_id))
                seen_shutters.add((device, output_nr))
                check_place(row_id, room, level)
            elif typ == SENSOR_TYPE:
                device = expect_int(device, "id_device", low=1)
                raw_name = check_name(name)
                output_nr = expect_int(output_nr, "output_nr", low=0, high=127)
                room = expect_int(room, "id_room", required=False)
                level = expect_int(level, "id_level", required=False)
                sensors.append({
                    "dbId": row_id, "module": device, "number": output_nr, "name": raw_name or FALLBACK_SENSOR.format(output_nr),
                    "roomId": room, "levelId": level,
                })
                if (device, output_nr) in seen_sensors:
                    issues.append(issue("DUPLICATE_ADDRESS", "outputs", row_id))
                seen_sensors.add((device, output_nr))
                check_place(row_id, room, level)
            elif typ not in KINDS:
                # nieobsługiwany typ: raz UNSUPPORTED_OUTPUT_TYPE i nic więcej; zachowany (UNKNOWN) tylko z adresem przekaźnika
                issues.append(issue("UNSUPPORTED_OUTPUT_TYPE", "outputs", row_id, "output_type"))
                try:
                    outputs.append(read_output(row, typ))
                except Skip:
                    pass
            else:
                output = read_output(row, typ)
                outputs.append(output)
                if (output["module"], output["relay"]) in seen_outputs:
                    issues.append(issue("DUPLICATE_ADDRESS", "outputs", row_id))
                seen_outputs.add((output["module"], output["relay"]))
                check_place(row_id, output["roomId"], output["levelId"])
        except Skip as skip:
            issues.append(issue("SKIPPED_MISSING_VALUE" if skip.missing else "SKIPPED_INVALID_VALUE", "outputs", row_id, skip.column))

    modules = sorted(
        {o["module"] for o in outputs} | {z["module"] for z in zones} | {r["module"] for r in shutters}
        | {t["module"] for t in sensors}
    )
    return {
        "levels": levels, "rooms": rooms, "modules": modules, "outputs": outputs, "heatingZones": zones,
        "temperatureSensors": sensors, "shutters": shutters,
        "issues": sorted(issues, key=lambda i: (i["table"], i["rowId"], i["kind"])),
        "selectedRowCount": con.execute(
            "SELECT COUNT(*) FROM outputs WHERE show_on_panel = 1 OR output_type = 123").fetchone()[0],
    }


def clean_name_optional(value, fallback: str) -> str:
    """Nazwa piętra / pomieszczenia: NULL i puste -> tekst zastępczy; nie-tekst -> pominięcie wiersza."""
    if value is None:
        return fallback
    if not isinstance(value, str):
        raise Skip("Name", False)
    value = value.strip()
    return value if value else fallback


def file_info(path: Path, page_size: int) -> dict:
    data = path.read_bytes()
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    info = {
        "pageSize": page_size, "sha256": sha256(data), "fileSize": len(data),
        "pageCount": con.execute("PRAGMA page_count").fetchone()[0],
        "freelistCount": con.execute("PRAGMA freelist_count").fetchone()[0],
    }
    con.close()
    assert info["pageSize"] == struct.unpack(">H", data[16:18])[0]
    return info


def to_json(document: dict) -> str:
    """JSON z jednym kluczem najwyższego poziomu na wiersz (zwięźle, ale czytelnie w diffie)."""
    lines = [
        f"{json.dumps(key)}: {json.dumps(value, ensure_ascii=False, separators=(',', ':'))}"
        for key, value in document.items()
    ]
    return "{\n" + ",\n".join(lines) + "\n}\n"


def main() -> None:
    main_path = OUT / "config_synthetic.db3"
    large_path = OUT / "config_synthetic_4096.db3"
    build_database(main_path, 1024)
    build_database(large_path, 4096)
    deep = create_deep_tree(OUT / "sqlite_deep.db3")

    expected = {
        "note": "Dane syntetyczne – wygenerowane przez tools/gen_config_db.py; nie zawierają danych prawdziwego domu.",
        "files": {
            main_path.name: file_info(main_path, 1024),
            large_path.name: file_info(large_path, 4096),
        },
        "deep": deep,
    }
    # oba pliki mają tę samą zawartość logiczną, poza tabelą overflow_probe (rozmiary zależą od page_size)
    results = []
    for path in (main_path, large_path):
        con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        names, tables = dump_tables(con)
        expected["files"][path.name]["overflowProbe"] = tables.pop("overflow_probe")
        results.append((names, tables, expected_config(con)))
        con.close()
    assert results[0] == results[1], "pliki 1024 i 4096 mają różną zawartość logiczną"
    names, tables, config = results[0]
    expected["tableNames"] = names
    expected["tables"] = tables
    expected["config"] = config
    (OUT / "config_synthetic.expected.json").write_text(to_json(expected), encoding="utf-8")
    print(f"outputs: {len(tables['outputs']['rows'])} wierszy, zaznaczonych {config['selectedRowCount']}, "
          f"wyjść {len(config['outputs'])}, stref {len(config['heatingZones'])}, "
          f"rolet {len(config['shutters'])}, czujników {len(config['temperatureSensors'])}, problemów {len(config['issues'])}")
    for name, info in expected["files"].items():
        print(f"{name}: {info['fileSize']} B, stron {info['pageCount']}, wolnych {info['freelistCount']}")
    print(f"sqlite_deep.db3: głębokość drzewa {deep['treeDepth']}")


if __name__ == "__main__":
    main()
