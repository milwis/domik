# Termometry pokojowe Xiaomi w Domiku – przygotowanie krok po kroku

Ta instrukcja prowadzi od termometru wyjętego z pudełka do termometru, który Domik widzi i może przypisać do strefy
ogrzewania. Napisana dla osób, które nigdy tego nie robiły. Każdy termometr przygotowuje się raz, ok. 20–30 minut na sztukę
(pierwszy dłużej, kolejne szybciej).

**Po co to jest:** czujnik temperatury Deimic zwykle siedzi w puszce przy włączniku światła, czyli przy drzwiach.
Bezprzewodowy termometr postawisz tam, gdzie temperatura naprawdę się liczy (np. przy łóżku albo w zimnym narożniku),
a Domik pokaże jego odczyt obok temperatury zadanej i porówna go z czujnikiem Deimic.

> Domik nie jest powiązany z firmą Xiaomi ani Deimic. Wgrywasz na termometr oprogramowanie open source
> [pvvx/ATC_MiThermometer](https://github.com/pvvx/ATC_MiThermometer) – robisz to na własną odpowiedzialność.
> Zmiana oprogramowania może skutkować utratą gwarancji, a w rzadkich przypadkach uszkodzeniem termometru (patrz „Ryzyko” niżej).

---

## Spis treści

0. [Czego potrzebujesz](#0-czego-potrzebujesz)
1. [Rozpakowanie i włączenie](#1-rozpakowanie-i-włączenie)
2. [Sprawdzenie modelu i oprogramowania (bez zmian)](#2-sprawdzenie-modelu-i-oprogramowania-bez-zmian)
3. [Dodanie termometrów do aplikacji Xiaomi Home](#3-dodanie-termometrów-do-aplikacji-xiaomi-home)
4. [Odczytanie kluczy termometru](#4-odczytanie-kluczy-termometru)
5. [Logowanie do termometru na stronie flashera](#5-logowanie-do-termometru-na-stronie-flashera)
6. [Wgranie oprogramowania pvvx](#6-wgranie-oprogramowania-pvvx)
7. [Ustawienia termometru (WAŻNE)](#7-ustawienia-termometru-ważne)
8. [Usunięcie z Xiaomi Home](#8-usunięcie-z-xiaomi-home)
9. [Gdzie postawić termometr](#9-gdzie-postawić-termometr)
10. [Termometr w Domiku](#10-termometr-w-domiku)
11. [Kolejne termometry](#11-kolejne-termometry)
12. [Gdy coś nie działa](#12-gdy-coś-nie-działa)
13. [Powrót do oprogramowania Xiaomi](#13-powrót-do-oprogramowania-xiaomi)
14. [Prywatność i bezpieczeństwo](#14-prywatność-i-bezpieczeństwo)

---

## 0. Czego potrzebujesz

**Termometr.** Instrukcja jest sprawdzona na:

| Model (na pudełku / naklejce) | Nazwa handlowa | Uwagi |
|---|---|---|
| **MJWSD06MMC** | Xiaomi Smart Temperature and Humidity Monitor 3 **Mini** | Sprawdzony z Domikiem (HW V1.3, fabryczne oprogramowanie 2.1.1_0009). Wszystkie kroki 1–14. |
| **MJWSD05MMC** | Xiaomi Monitor 3 (dwa przyciski, BHR9041GL) | Sprawdzony z Domikiem, 6 sztuk. **Nie wymaga Xiaomi Home ani kluczy** – wgrywasz według skróconej procedury z ramki poniżej (pomijasz kroki 3, 4, 5 i 8). |

Inne modele obsługiwane przez pvvx (np. LYWSD03MMC) też zadziałają z Domikiem, ale procedura wgrania różni się
szczegółami – korzystaj wtedy z [README pvvx](https://github.com/pvvx/ATC_MiThermometer#flashing-or-updating-the-firmware-ota)
i wróć tu od kroku 7.

> ### MJWSD05MMC – skrócona procedura (bez Xiaomi Home)
>
> Termometru **MJWSD05MMC nie trzeba dodawać do aplikacji Xiaomi Home** ani odczytywać jego kluczy (kroki 3, 4, 5 i 8
> tej instrukcji dotyczą tylko Mini MJWSD06MMC). Wystarczy:
>
> 1. Wyjmij plastikowy pasek spod klapki baterii (krok 1) i otwórz w Chrome stronę flashera (krok 2).
> 2. Kliknij **Connect**, wybierz termometr (`MJWSD05MMC`) i **Połącz**.
> 3. Kliknij **Do Activation** i poczekaj, aż w logu pojawi się informacja o zakończeniu.
> 4. Kliknij propozycję strony **Custom Firmware** w wersji **5.9** (od 6.0 Domik jeszcze nie czyta formatu)
>    → **Start Flashing**. Nie ruszaj komputera ani termometru przez 1–2 minuty.
> 5. Po restarcie połącz się ponownie z nowo widocznym urządzeniem i ustaw format reklam **PVVX (Custom)** według
>    [kroku 7](#7-ustawienia-termometru-ważne).
>
> **Jeśli łączenie albo aktywacja nie działa:** naciśnij na termometrze **przycisk łączenia Bluetooth** (przycisk „Connect”)
> na moment **tuż przed** kliknięciem **Połącz** i spróbuj ponownie.
>
> Nie przytrzymuj przycisku długo – długie przytrzymanie resetuje termometr.

**Komputer albo telefon z przeglądarką Google Chrome** (albo Edge / Opera) i Bluetooth:
- Mac lub komputer z Windows – najwygodniej;
- albo telefon z Androidem (Chrome) – czasem łączy się z termometrem lepiej niż komputer.
Safari i Firefox **nie** obsługują Bluetooth na stronach – nie zadziałają.

**Telefon z aplikacją Xiaomi Home** (Android lub iPhone) i **konto Xiaomi** (darmowe; jeśli go nie masz, aplikacja
pozwoli je założyć).

**Dostęp do internetu** na komputerze.

**Czas i spokój:** ok. 20–30 minut na pierwszy termometr. Termometr ma leżeć blisko komputera (do 1 m).

### ⚠️ Jedna zasada na cały czas pracy

**Nie przytrzymuj przycisku na termometrze**, dopóki nie skończysz kroku 6. Długie przytrzymanie resetuje termometr
i unieważnia klucze z kroku 4 – trzeba by zaczynać od kroku 3. (Krótkie, przypadkowe dotknięcie nic nie psuje.)

### Ryzyko – przeczytaj przed startem

- Wgranie trwa ok. minuty i zwykle się udaje. Na forum pvvx są jednak pojedyncze zgłoszenia egzemplarzy Mini, które po
  wgraniu zawiesiły się albo przestały się łączyć (głównie z niektórych partii produkcyjnych). Naprawa wymaga wtedy
  otwarcia obudowy i przewodowego programatora – to już zadanie dla majsterkowicza.
- Dlatego: **jeśli masz kilka termometrów, zrób najpierw jeden** i dopiero gdy działa – kolejne.

---

## 1. Rozpakowanie i włączenie

1. Wyjmij termometr z pudełka.
2. Wyciągnij **plastikowy pasek** wystający spod klapki baterii – to włącza termometr.
3. Po kilku sekundach ekran pokaże temperaturę i wilgotność. Jeśli ekran jest pusty – sprawdź, czy bateria (CR2032)
   jest włożona plusem do góry, ewentualnie wymień ją na nową.
4. Odwróć termometr i przepisz z naklejki **model** (np. `MJWSD06MMC`). Jeśli to inny model – patrz tabela w kroku 0.

---

## 2. Sprawdzenie modelu i oprogramowania (bez zmian)

Ten krok niczego nie zmienia w termometrze – tylko odczytuje, z czym mamy do czynienia.

1. Na komputerze otwórz **Google Chrome**.
2. Wejdź na stronę: **<https://pvvx.github.io/ATC_MiThermometer/TelinkMiFlasher.html>**
   (to „flasher” – strona do wgrywania oprogramowania na termometry; dalej tak ją nazywamy).
3. Kliknij niebieski przycisk **Connect**.
4. Pojawi się okienko z listą urządzeń Bluetooth. Wybierz **`MJWSD06MMC`** i kliknij **Połącz** (lub **Pair**).
   - Jeśli lista jest pusta: sprawdź, czy Bluetooth w komputerze jest włączony; na Macu zezwól Chrome na Bluetooth
     (Ustawienia systemowe → Prywatność i ochrona → Bluetooth). Jeśli dalej pusto – wyczyść pole
     **„BLE device name prefix filter(s)”** na stronie i kliknij Connect jeszcze raz.
5. Na dole strony, w dzienniku (logu), pojawią się linie podobne do tych:
   ```
   Connecting to: MJWSD06MMC
   Hardware Revision String: V1.3
   Firmware Revision String: 0009
   Detected Mi device
   Connected
   ```
6. Na stronie może się pojawić ostrzeżenie:
   **„Warning: Firmware MJWSD06MMC version 2.1.1_0009 requires registration in MI-Home and obtaining IDs”**
   oraz pola **Device known id**, **Mi Token**, **Mi Bind Key**. To normalne dla tego modelu – oznacza, że termometr
   trzeba najpierw dodać do Xiaomi Home i odczytać jego klucze (kroki 3–4).
7. **Na razie nic więcej nie klikaj** (ani Login, ani Do Activation, ani Start Flashing).
   Kliknij **Disconnect** i **zamknij Chrome całkowicie** (na Macu: Cmd+Q; w Windows: zamknij wszystkie okna Chrome).
   Póki Chrome jest połączony z termometrem, termometr nie łączy się z niczym innym.

---

## 3. Dodanie termometrów do aplikacji Xiaomi Home

> **Dotyczy tylko Mini (MJWSD06MMC).** MJWSD05MMC pomiń kroki 3–5 i 8 – patrz ramka w [kroku 0](#0-czego-potrzebujesz).

> **Masz kilka termometrów? Dodaj w tym kroku od razu wszystkie, jeden po drugim.** Wtedy program z kroku 4 uruchamiasz
> tylko raz i wypisze klucze wszystkich termometrów naraz. Dalej (kroki 5–7) wgrywasz je już po kolei, jeden po drugim.

1. Na telefonie zainstaluj aplikację **Xiaomi Home** (dawniej „Mi Home”) ze sklepu Google Play / App Store.
2. Zaloguj się na konto Xiaomi (albo je załóż).
   Przy wyborze regionu / serwera wybierz **Europa** (dla Polski) – i **zapamiętaj** ten wybór, przyda się w kroku 4.
3. W aplikacji stuknij **+** (dodaj urządzenie) i postępuj zgodnie z instrukcjami na ekranie, trzymając termometr przy
   telefonie. Po chwili termometr pojawi się w aplikacji i pokaże temperaturę.
4. **Nie aktualizuj oprogramowania termometru**, jeśli aplikacja to zaproponuje – odmów.
5. Przy kilku termometrach: zaraz po dodaniu **zmień jego nazwę** w Xiaomi Home na pokój, do którego trafi
   (np. „Sypialnia 1”), i **przyklej na spód karteczkę** z tą samą nazwą. Dopiero potem dodawaj następny.
   Wszystkie fabrycznie nazywają się tak samo, więc bez tego w kroku 4 nie odróżnisz ich kluczy.
6. Powtórz punkty 3–5 dla każdego termometru.
7. Gotowe. Od tej chwili **nie usuwaj termometrów z Xiaomi Home** aż do kroku 8 – usunięcie unieważnia klucze.

---

## 4. Odczytanie kluczy termometru

Strona flashera potrzebuje dwóch kluczy termometru: **Token** i **Bind Key**. Xiaomi trzyma je na Twoim koncie.
Odczytuje je bezpłatne narzędzie open source
[Xiaomi Cloud Tokens Extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor) (autor: Piotr Machowski).
Narzędzie działa na Twoim komputerze i łączy się wyłącznie z serwerami Xiaomi.

### 4a. Windows

1. Pobierz plik **token_extractor.exe**:
   <https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor/releases/latest/download/token_extractor.exe>
2. Uruchom go dwuklikiem. Jeśli Windows pokaże „System Windows ochronił ten komputer” – kliknij **Więcej informacji** →
   **Uruchom mimo to** (program nie ma płatnego podpisu cyfrowego, stąd ostrzeżenie).
3. Przejdź do punktu **4c**.

### 4b. Mac

1. Otwórz aplikację **Terminal** (Launchpad → Inne → Terminal, albo Cmd+Spacja i wpisz „Terminal”).
2. Wklej poniższe polecenia **po jednym** (każde zatwierdź klawiszem Enter i poczekaj, aż się wykona):
   ```bash
   cd ~/Downloads
   curl -L -o token_extractor.zip https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor/releases/latest/download/token_extractor.zip
   unzip token_extractor.zip
   cd token_extractor
   python3 -m venv .venv
   .venv/bin/pip install -r requirements.txt
   .venv/bin/python token_extractor.py
   ```
   - Jeśli po `python3` macOS zaproponuje instalację „narzędzi wiersza poleceń” – zgódź się, poczekaj na koniec
     instalacji i wpisz to polecenie jeszcze raz.
3. Przejdź do punktu **4c**.

### 4c. Logowanie i odczyt kluczy

1. Program zapyta o sposób logowania: `p/q:`
   - wpisz **`q`** i Enter – logowanie **kodem QR** (najprostsze, bez wpisywania hasła). Program pokaże adres w rodzaju
     `http://127.0.0.1:31415` – otwórz go w przeglądarce na komputerze, pojawi się kod QR. Zeskanuj go aplikacją
     **Xiaomi Home** na telefonie (ekran **Profil** → ikona skanera w górnym rogu) i potwierdź logowanie w telefonie;
   - albo wpisz **`p`** – wtedy podajesz e‑mail/ID konta Xiaomi i hasło (hasła nie widać podczas pisania – to normalne).
     Xiaomi może przysłać kod weryfikacyjny e‑mailem (sprawdź też SPAM).
2. Program zapyta o serwer (region). Wciśnij po prostu **Enter** – sprawdzi wszystkie regiony. (Albo wpisz `de` dla
   Europy.)
3. Program wypisze listę Twoich urządzeń Xiaomi – wszystkie dodane termometry naraz. Każdy wygląda mniej więcej tak:
   ```
   NAME:     Inteligentny monitor temperatury i wilgotności Xiaomi 3 Mini   ← nazwa z Xiaomi Home
   ID:       blt.4.xxxxxxxxxxxxx
   BLE KEY:  xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx      ← 32 znaki
   MAC:      A4:C1:38:xx:xx:xx
   TOKEN:    xxxxxxxxxxxxxxxxxxxxxxxx              ← 24 znaki
   MODEL:    xiaomi.sensor_ht.minigl
   ```
   Przy kilku termometrach rozpoznasz właściwy po **MAC**: po wgraniu (krok 6) termometr nazywa się `MJ6_` + trzy
   ostatnie bajty MAC (np. MAC `…:12:AB:CD` → `MJ6_12ABCD`). Zapisz MAC razem z nazwą pokoju.
   Jeśli nie zmieniłeś nazw w Xiaomi Home i nie wiesz, który klucz jest czyj – nic się nie stanie: w kroku 5 klucze
   cudzego termometru po prostu nie zadziałają (Login się nie uda), wtedy spróbuj kluczy następnego z listy.
4. **Skopiuj** TOKEN i BLE KEY każdego termometru (zaznacz myszką i Cmd+C / Ctrl+C) do notatnika, podpisane nazwą pokoju. Sprawdź długość: TOKEN = **24** znaki, BLE KEY = **32**
   znaki (cyfry 0–9 i litery a–f).
5. Zamknij program. Na Macu możesz potem usunąć katalog `~/Downloads/token_extractor` i plik `token_extractor.zip`.

> Klucze są potrzebne tylko do jednorazowego wgrania oprogramowania. Nie wysyłaj ich nikomu i nie wklejaj na forach.
> Po kroku 8 przestają mieć znaczenie.

---

## 5. Logowanie do termometru na stronie flashera

1. Na telefonie **zatrzymaj aplikację Xiaomi Home**, żeby nie łączyła się z termometrem:
   - Android: Ustawienia → Aplikacje → Xiaomi Home → **Wymuś zatrzymanie**; dodatkowo możesz na chwilę wyłączyć Bluetooth
     w telefonie;
   - iPhone: przesuń aplikację w górę w przełączniku aplikacji i na chwilę wyłącz Bluetooth w Centrum sterowania.
2. Termometr połóż blisko komputera (do 1 m).
3. Otwórz Chrome i stronę flashera: <https://pvvx.github.io/ATC_MiThermometer/TelinkMiFlasher.html>
4. Kliknij **Connect** → wybierz **`MJWSD06MMC`** → **Połącz**. Poczekaj, aż w logu pojawi się **`Connected`**
   (bywa, że trwa to 20–30 sekund).
5. Wypełnij pola pod ostrzeżeniem:
   - **Device known id** – zostaw **puste**,
   - **Mi Token** – wklej **TOKEN** (24 znaki),
   - **Mi Bind Key** – wklej **BLE KEY** (32 znaki).
   Uważaj, żeby nie zamienić ich miejscami i nie skopiować spacji na początku lub końcu.
6. Kliknij **Login**.
7. W logu powinno pojawić się:
   ```
   Send Login, please wait...
   Received device infos are correct
   Login successful
   ```
   (Komunikat **„Repeat login!”** też oznacza sukces.)
   Jeśli zamiast tego widzisz **„not registered”** – patrz [krok 12](#12-gdy-coś-nie-działa).

---

## 6. Wgranie oprogramowania pvvx

1. Po udanym logowaniu na stronie pojawi się kilka przycisków z plikami. Kliknij **tylko**:
   **`Custom Firmware: MJ6_v59.bin`**
   - **Nie** klikaj „Zigbee Firmware”, „ZB Firmware” ani „Original_OTA_…”.
   - Jeśli zamiast `MJ6_v59.bin` jest wersja **6.0 lub nowsza** (np. `MJ6_v60.bin`) – zatrzymaj się. Od wersji 6.0 pvvx
     nadaje tylko w formacie BTHome, którego Domik jeszcze nie czyta. Zgłoś to w zakładce Issues projektu Domik.
2. Kliknij **Start Flashing**.
3. Przez **1–2 minuty nie ruszaj** komputera ani termometru: nie zamykaj karty, nie usypiaj komputera, nie wyjmuj baterii.
   W logu zobaczysz postęp, a na końcu:
   ```
   Update done after 60.373 seconds
   Disconnected.
   ```
4. Termometr sam się uruchomi ponownie, ekran znów pokaże temperaturę. Od teraz w Bluetooth nazywa się
   **`MJ6_xxxxxx`** (sześć znaków to końcówka jego adresu).
5. **Zamknij Chrome całkowicie** (Cmd+Q na Macu).

---

## 7. Ustawienia termometru (WAŻNE)

Po wgraniu termometr nadaje w formacie **BTHome**. Domik czyta format **PVVX (Custom)** – trzeba go przestawić.

1. Otwórz Chrome i stronę flashera, kliknij **Connect**. Jeśli na liście nie ma `MJ6_xxxxxx`, wyczyść pole
   **„BLE device name prefix filter(s)”** i kliknij Connect ponownie.
2. Wybierz **`MJ6_xxxxxx`** → **Połącz**. Pojawi się panel ustawień termometru.
3. Ustaw:

   | Ustawienie | Wartość |
   |---|---|
   | **Advertising type** | **`PVVX (Custom)`** ← najważniejsze |
   | Advertising interval | `2500` ms (domyślne) |
   | Measure interval | `4` (pomiar co 10 s) |
   | Encrypted beacon | **odznaczone** |
   | Temperature offset (korekta temperatury) | `0` |
   | Temperatura | `°C` |
   | RF TX Power (moc nadawania) | bez zmian (wyższa moc szybciej rozładowuje baterię) |

4. Kliknij **Send Config**. Jeśli w panelu jest przycisk zapisu ustawień w pamięci (np. „Save … in flash”) – kliknij
   go także.
5. **Zapisz adres MAC** termometru, widoczny w panelu (12 znaków, np. `A4:C1:38:xx:xx:xx`). Przyda się w Domiku, gdy
   będziesz mieć kilka termometrów. Możesz też nakleić na spód termometru karteczkę z końcówką adresu i nazwą pokoju.
6. Kliknij **Disconnect** i **zamknij Chrome całkowicie** (Cmd+Q). Dopóki flasher jest połączony, termometr nie nadaje
   odczytów i Domik go nie zobaczy.

---

## 8. Usunięcie z Xiaomi Home

> **Dotyczy tylko Mini (MJWSD06MMC)** – MJWSD05MMC nie był w Xiaomi Home, więc nie ma czego usuwać.

Termometr z oprogramowaniem pvvx nie działa już z aplikacją Xiaomi Home. Usuń go tam: otwórz Xiaomi Home → stuknij
termometr → menu (⋯) → **Usuń urządzenie**. Konto Xiaomi i aplikacja nie są dalej potrzebne (chyba że przygotowujesz
kolejne termometry – patrz krok 11).

---

## 9. Gdzie postawić termometr

- W miejscu, którego temperatura ma się liczyć (np. strefa dzienna pokoju, przy łóżku), **na wysokości ok. 1–1,5 m**.
- **Z dala** od grzejnika, kominka, okna, słońca, lampy, telewizora i drzwi wejściowych.
- Nie w zamkniętej szafce. Termometr nie powinien stać na metalowej półce (osłabia sygnał Bluetooth).
- **Zasięg:** tablet z Domikiem odbiera termometr przez Bluetooth. W domu to zwykle jeden–dwa pokoje (przez ściany
  mniej). Gdy Domik pokazuje słaby sygnał albo przerwy w odczytach – przybliż termometr lub tablet.
- Baterii CR2032 wystarcza zwykle na wiele miesięcy; Domik ostrzega, gdy jest na wyczerpaniu.

---

## 10. Termometr w Domiku

Na tablecie, na którym działa Domik (ten, który wisi na ścianie i jest połączony ze sterownikiem):

1. Otwórz menu **☰** → **Termometry pokojowe** → **Otwórz termometry pokojowe**.
2. Za pierwszym razem Domik poprosi o zgodę na **wyszukiwanie urządzeń w pobliżu (Bluetooth)** – stuknij **Zezwól**.
   (Na starszym Androidzie 11 i niższym system nazywa to „lokalizacją” – Domik nie ustala położenia, Android wymaga tej
   zgody do wyszukiwania Bluetooth.)
3. W zakładce **Termometry** pojawi się sekcja **Wykryte termometry** z odczytem temperatury, baterią i sygnałem.
   Jeśli lista jest pusta – podejdź z tabletem bliżej termometru i poczekaj kilka sekund; sprawdź krok 7 (format
   **PVVX (Custom)** i zamknięty Chrome na komputerze).
4. Przy termometrze stuknij **Przypisz do strefy**, wybierz strefę ogrzewania (pokój), opcjonalnie wpisz nazwę termometru
   i, jeśli Domik o to poprosi, wskaż **czujnik Deimic w tym pokoju**. Stuknij **Przypisz**.
5. Włącz **Funkcja włączona** oraz **To urządzenie kalibruje czujniki** – tylko na **jednym** urządzeniu w domu (tablet
   na ścianie). Domik poprosi o zgodę na Bluetooth i powiadomienia (stałe powiadomienie „Domik monitoruje termometry
   pokojowe” jest potrzebne, żeby Android nie przerywał odbioru w tle).
6. Od teraz Domik porównuje termometr z czujnikiem Deimic w tym pokoju. W zakładkach **Kalibracja** i **Dziennik** widać
   różnicę temperatur i to, jak często kalibracja czujnika Deimic byłaby zmieniana.

> W bieżącej wersji Domik działa w **trybie próbnym**: nic nie wysyła do sterownika, tylko pokazuje i zapisuje w dzienniku,
> co by zrobił. Zapis kalibracji do sterownika jest w tej wersji wyłączony.

---

## 11. Kolejne termometry

> **MJWSD05MMC:** każdy termometr wgrywasz osobno według ramki w [kroku 0](#0-czego-potrzebujesz) – bez kluczy i bez Xiaomi Home.
> Poniższe dotyczy Mini (MJWSD06MMC).

Każdy termometr ma **własne klucze** (TOKEN i BLE KEY). Najprościej:

1. W kroku 3 dodaj do Xiaomi Home **wszystkie** termometry (każdy z nazwą pokoju i karteczką).
2. W kroku 4 uruchom program **raz** – wypisze klucze wszystkich.
3. Kroki 5–7 rób dla **jednego termometru na raz**: włóż baterię tylko do tego, który wgrywasz (pozostałe bez baterii albo
   w innym pokoju), wpisz **jego** klucze, wgraj, ustaw, zamknij Chrome – i następny.
4. Na koniec usuń wszystkie wgrane termometry z Xiaomi Home (krok 8).

Jeśli dokładasz termometr później: dodaj go do Xiaomi Home i uruchom program z kroku 4 ponownie (na Macu wystarczy wejść
do katalogu `token_extractor` i wykonać ostatnie polecenie, jeśli go nie usunąłeś).

---

## 12. Gdy coś nie działa

| Objaw | Co zrobić |
|---|---|
| Na liście w oknie **Connect** nie ma termometru | Zamknij Chrome całkowicie i otwórz ponownie. Zatrzymaj Xiaomi Home na telefonie i wyłącz w nim Bluetooth. Wyczyść pole „BLE device name prefix filter(s)”. Wyjmij baterię na 10 s i włóż z powrotem (to tylko restart – nie kasuje kluczy ani ustawień). |
| MJWSD05MMC: łączenie lub **Do Activation** nie działa | Naciśnij na termometrze **przycisk łączenia Bluetooth** na moment tuż przed kliknięciem **Połącz** (albo przed ponowną próbą aktywacji). Nie przytrzymuj go długo. |
| Łączenie trwa długo albo się nie udaje | Kliknij **Disconnect**, potem **Reconnect**. Połóż termometr tuż obok komputera. Pomaga też użycie innego urządzenia – np. Chrome na telefonie z Androidem (patrz niżej). |
| **„not registered”** po kliknięciu Login | Klucze nie pasują do termometru. Przy kilku termometrach najpierw sprawdź, czy to nie klucze innego z listy (spróbuj kolejnych). W przeciwnym razie najczęściej to skutek przytrzymania przycisku (reset) albo usunięcia z Xiaomi Home. Usuń termometr z Xiaomi Home, dodaj go ponownie (krok 3) i odczytaj **nowe** klucze (krok 4). |
| Strona ostrzega o długości klucza | Token i Bind Key są zamienione miejscami albo skopiowane ze spacją. Token = 24 znaki, Bind Key = 32 znaki. |
| Błąd „GATT operation failed” | Problem z Bluetooth komputera. Spróbuj ponownie od Connect albo z telefonu z Androidem. |
| Program z kroku 4 nie może się zalogować | Użyj logowania kodem QR (`q`). Wyłącz blokery reklam w sieci (AdGuard, Pi‑hole) i niestandardowy DNS. Xiaomi pozwala na 3–5 kodów weryfikacyjnych dziennie – w razie limitu spróbuj następnego dnia. |
| Program nie pokazuje termometru | Sprawdź, czy w kroku 3 termometr pojawił się w Xiaomi Home i czy wybrałeś ten sam region. Przy pytaniu o serwer wciśnij Enter (wszystkie regiony). |
| Po wgraniu Domik nie widzi termometru | Format reklam nie jest ustawiony na **PVVX (Custom)** (krok 7) albo Chrome na komputerze nadal jest połączony z termometrem – zamknij go całkowicie. |
| Po wgraniu ekran termometru jest pusty lub zamarznięty | Wyjmij baterię na 30 s i włóż ponownie. Jeśli to nie pomoże – opisz problem w Issues projektu [pvvx/ATC_MiThermometer](https://github.com/pvvx/ATC_MiThermometer/issues) (model, wersja HW, log ze strony). Naprawa może wymagać programatora przewodowego. |

**Wgrywanie z telefonu z Androidem** (gdy komputer nie chce się połączyć):
1. Wymuś zatrzymanie Xiaomi Home; Bluetooth w telefonie zostaw **włączony**.
2. W Chrome na telefonie wpisz w pasku adresu: `chrome://flags/#enable-experimental-web-platform-features`,
   ustaw **Enabled** i stuknij **Uruchom ponownie**.
3. Otwórz stronę flashera i wykonaj kroki 5–7 tak samo jak na komputerze (klucze najłatwiej przesłać sobie na telefon
   bezpiecznym kanałem, np. własną notatką – nie publicznym komunikatorem).

---

## 13. Powrót do oprogramowania Xiaomi

Gdybyś chciał wrócić do oryginału: połącz się z termometrem na stronie flashera (Connect → `MJ6_xxxxxx`), kliknij
przycisk **`Original_OTA_Xiaomi_MJWSD06MMC_v2.1.1_0009.bin`** → **Start Flashing**. Po restarcie termometr znów działa
z Xiaomi Home (trzeba go dodać ponownie).

---

## 14. Prywatność i bezpieczeństwo

- **Domik** odbiera od termometru tylko jego nadawane odczyty (temperatura, wilgotność, bateria) przez Bluetooth na
  tablecie. Nic nie wysyła do internetu ani do Xiaomi.
- **Strona flashera** działa w Twojej przeglądarce: łączy się z termometrem przez Bluetooth, a z internetu pobiera tylko
  plik oprogramowania z GitHuba. Klucze wpisane na stronie nie są nigdzie wysyłane.
- **Program z kroku 4** loguje się na Twoje konto Xiaomi (serwery Xiaomi) i wyświetla klucze Twoich urządzeń. Działa
  lokalnie; hasła i kluczy nie zapisuje w żadnej usłudze zewnętrznej. Jeśli wolisz, użyj logowania kodem QR – wtedy nie
  wpisujesz hasła wcale.
- Kluczy (TOKEN, BLE KEY) nie publikuj. Po wgraniu pvvx i usunięciu termometru z Xiaomi Home nie są już do niczego
  potrzebne.

---

*Podziękowania: oprogramowanie termometru – [pvvx/ATC_MiThermometer](https://github.com/pvvx/ATC_MiThermometer);
odczyt kluczy – [Xiaomi Cloud Tokens Extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)
(Piotr Machowski). Xiaomi, Mi Home i Xiaomi Home są znakami towarowymi Xiaomi Inc.; Deimic – znakiem swojego właściciela.*
