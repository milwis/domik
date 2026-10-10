# Domik – instalacja i konfiguracja tabletu ściennego

Domik to aplikacja na Androida do sterowników **Deimic 1. generacji** („Deimic Master”). Sterownik obsługuje
wyłącznie w Twojej sieci lokalnej: łączy się z nim bezpośrednio (TLS, port 60110), bez chmury i bez zbierania danych.
Nazwy pomieszczeń, świateł i stref grzewczych pobiera ze sterownika. Jedyne połączenie z internetem to opcjonalna
pogoda z serwisu Open-Meteo (patrz [Pogoda](#pogoda)).

> **Projekt edukacyjny, nieoficjalny.** Domik jest hobbystycznym projektem niezwiązanym z producentem sterowników
> Deimic i **nie zastępuje oficjalnej aplikacji** – szczegóły w sekcji
> [Zastrzeżenia](../README.md#zastrzeżenia) w pliku README.

> Wersja **0.1.0**. Ograniczenia: patrz [na końcu](#ograniczenia-wersji-010).

---

## 1. Czego potrzebujesz

- Tablet z Androidem 8.0 lub nowszym. Instrukcja jest napisana dla **Samsung Galaxy Tab** (One UI 7/8,
  Android 15/16); na innych tabletach nazwy ustawień mogą się trochę różnić.
- Sterownik Deimic 1. generacji w tej samej sieci Wi-Fi/LAN co tablet.
- **Adres IP sterownika** (np. `192.168.1.50`) – sprawdzisz go w routerze (lista urządzeń) albo w Konfiguratorze
  Deimic.
- **Numer seryjny sterownika** (zapis szesnastkowy, np. `1A2B3C4D`) – ten sam, którego używa oficjalna aplikacja
  Deimic do połączenia lokalnego. Jeśli go nie znasz, zapytaj instalatora.
  Numer seryjny jest jedynym „kluczem” do sterownika – nie udostępniaj go nikomu.

### Zalecenie dla sieci
W routerze ustaw **stały adres (rezerwację DHCP)** dla sterownika – inaczej po restarcie routera sterownik może
dostać inny adres i Domik straci połączenie. Warto to samo zrobić dla tabletu.

---

## 2. Instalacja aplikacji

Aplikacja nie jest w Google Play – instaluje się plik APK. Krótszy opis kroków oraz sprawdzanie sumy kontrolnej
i odcisku podpisu – w [README](../README.md#instalacja-apk).

**Sposób A – plik APK na tablecie**
1. Pobierz plik `domik-0.1.0.apk` na tablet (z [GitHub Releases](https://github.com/milwis/domik/releases/latest)
   – razem z plikiem `SHA256SUMS`, albo przez kabel / dysk).
2. Otwórz go w aplikacji **Moje pliki**. Android poprosi o zgodę na instalowanie aplikacji z tego źródła:
   **Ustawienia → Zezwól z tego źródła** (włącz), wróć i stuknij **Zainstaluj**.
3. Po instalacji możesz tę zgodę z powrotem wyłączyć:
   *Ustawienia → Aplikacje → (⋮) Dostęp specjalny → Instalowanie nieznanych aplikacji → Moje pliki → wyłącz.*
4. Google Play Protect może pokazać ostrzeżenie o nieznanej aplikacji – wybierz **Zainstaluj mimo to**.

**Sposób B – z komputera kablem (dla zaawansowanych)**
1. Na tablecie włącz *Opcje programisty* (patrz §4.1, krok 2) i w nich **Debugowanie USB**.
2. Podłącz tablet, zaakceptuj na nim komputer, a na komputerze:
   ```bash
   adb install -r domik-0.1.0.apk
   ```

---

## 3. Pierwsze uruchomienie Domika

1. Przeczytaj i zaakceptuj **regulamin** (przy zmianie regulaminu w nowej wersji aplikacja zapyta ponownie).
2. Wybierz **Połącz ze swoim sterownikiem** (albo **Wypróbuj tryb demo** – fikcyjny dom, bez sterownika).
3. Wpisz:
   - **Adres** – IP sterownika,
   - **Port** – zostaw puste (domyślnie `60110`),
   - **Numer seryjny** – szesnastkowo.
4. Stuknij **Testuj połączenie**. Powinna pojawić się wersja oprogramowania sterownika. Typowe błędy:
   - *nic nie nasłuchuje / brak odpowiedzi* – zły adres, tablet w innej sieci (np. sieć gości), sterownik wyłączony;
   - *inny certyfikat* – to nie jest sterownik Deimic 1. generacji albo zły adres;
   - *sterownik nie odpowiada na komendy* – najczęściej zły numer seryjny.
5. **Zapisz**. Domik pobierze konfigurację domu ze sterownika (kilka sekund) i pokaże pulpit.

Numer seryjny jest przechowywany tylko na tym tablecie, zaszyfrowany kluczem w sprzętowym magazynie kluczy Androida.
Nie trafia do kopii zapasowych w chmurze. Usuniesz go w menu ☰ → **Ustawienia połączenia** → **Usuń zapisany
sterownik**.

### Wygląd
Menu ☰ → **Wygląd**: motyw jasny / ciemny / systemowy / **wg godzin** (np. ciemny od 21:00 do 7:00 – polecane na
ścianę) oraz kolor akcentu (9 palet).

### Pogoda
1. **Wybór miejscowości.** Bez wybranej miejscowości kafelek pogody pokazuje **Wybierz miejscowość** – stuknij go
   (albo otwórz menu ☰ → **Pogoda**) i zacznij wpisywać nazwę. Wyniki (nazwa, region, kraj) pojawiają się w trakcie
   pisania; stuknij właściwy, a kafelek od razu wczyta pogodę. Domik nie korzysta z lokalizacji tabletu (GPS) i nie
   prosi o nią – miejscowość wskazujesz sam. Zmiana: wyszukaj inną; **Usuń miejscowość** kasuje wybór razem
   z zapisaną prognozą i kończy zapytania o pogodę.
2. **Co pokazuje kafelek.** Zawsze ikonę, temperaturę i nazwę miejscowości. Domyślnie także pasek najbliższych godzin,
   pasek kolejnych dni i szansę opadów. Menu ☰ → **Pogoda** → **Co pokazywać na kafelku…** (albo stuknięcie kafelka
   w trybie edycji) pozwala włączyć lub wyłączyć: prognozę godzinową, prognozę dzienną, szansę opadów, sumę opadów,
   temperaturę odczuwalną, wiatr i porywy, wilgotność, indeks UV oraz wschód i zachód słońca.
3. **Rozmiar.** Kafelek pokazuje wybrane elementy tak, jak pozwala na to jego rozmiar: szerszy kafelek mieści więcej
   godzin i dni (do 48 godzin i 14 dni), wyższy (najwyżej 2 rzędy) – oba paski i szczegóły. Rozmiar zmienisz w trybie
   edycji (ikona ołówka, przeciągnięcie rogu kafelka).
4. **Odświeżanie.** Co 30 minut, gdy Domik jest na ekranie, oraz po powrocie do Domika, jeśli dane są starsze niż
   15 minut. Bez internetu kafelek pokazuje ostatnią zapamiętaną prognozę – gdy od jej pobrania minie 45 minut,
   z dopiskiem **Nieaktualne** i godziną pobrania. Jeśli nie ma żadnych danych, pokazuje **Brak pogody**; stuknięcie ponawia próbę.
5. **Co wysyła.** Do Open-Meteo trafia wpisywany tekst wyszukiwania (od 2 znaków), a po wyborze tylko współrzędne
   miejscowości zaokrąglone do 2 miejsc po przecinku (ok. 1 km). Podpis dostawcy danych jest na dole sekcji
   **Pogoda** w menu.
6. **Tryb demo** pokazuje pogodę przykładową i nie łączy się z internetem; można w nim tylko wybrać elementy kafelka.

---

## 4. Konfiguracja tabletu jako panelu ściennego (Samsung)

Cel: ekran zawsze włączony z Domikiem na wierzchu, tablet stale na ładowarce, bez usypiania aplikacji i bez
niespodzianek po nocy. Wykonaj kroki po kolei; nazwy dotyczą One UI 7/8 po polsku.

Część rzeczy Domik robi sam – w menu ☰ → **Tablet ścienny** są cztery pozycje: *Ekran zawsze włączony przy
zasilaniu*, *Pełny ekran*, *Domik jako ekran główny* i przycisk *Przypnij Domika*. Poniżej: co każda z nich daje i
co trzeba ustawić w samym tablecie.

### 4.1. Ekran zawsze włączony
Domik zadba o to sam: menu ☰ → **Tablet ścienny** → **Ekran zawsze włączony przy zasilaniu** (włączone domyślnie).
Dopóki tablet jest podłączony do ładowarki, a Domik jest na wierzchu, ekran nie przygasa i nie gaśnie. Po odłączeniu
zasilania wraca zwykłe wygaszanie ekranu, więc bateria nie wyczerpie się „na ścianie”.

> Dlaczego nie wystarczy systemowe **Opcje programisty → Pozostaw ekran włączony** (*Stay awake*)? Po upływie czasu
> wygaszenia ekran tylko się przyciemnia (zostaje włączony, ale ciemny). Przełącznik w Domiku tego nie robi – ekran
> świeci normalnie.

1. Opcjonalnie: **Ustawienia → Wyświetlacz → Wygaszenie ekranu → 10 minut**. To tylko zabezpieczenie na wypadek
   odłączenia zasilania albo wyjścia z Domika do innej aplikacji.
2. Opcje programisty (potrzebne tylko do instalacji kablem, §2B): **Ustawienia → Informacje o tablecie →
   Informacje o oprogramowaniu** → stuknij 7 razy **Numer wersji** (potwierdź PIN-em, jeśli zapyta).
3. Jasność: **Ustawienia → Wyświetlacz → Jasność adaptacyjna** – włącz (w nocy ekran sam się przyciemni).
   Na noc warto też włączyć **Komfort dla oczu** z harmonogramem i w Domiku motyw **wg godzin**.

> Tablety Galaxy Tab A mają ekran LCD – stale włączony obraz nie grozi wypaleniem jak w OLED.

### 4.2. Stałe ładowanie bez niszczenia baterii
- **Ustawienia → Bateria → Ochrona baterii → Maksymalnie** (ładowanie do 80 %). Przy tablecie wiszącym stale na
  zasilaczu to najważniejsze ustawienie dla żywotności baterii.
- Zasilacz co najmniej 15 W i dobry kabel (najlepiej kątowy USB-C, prowadzony w ścianie lub listwie). Zostaw
  tabletowi trochę miejsca na odprowadzanie ciepła – nie zamykaj go szczelnie w ramce.

### 4.3. Domik nie może być usypiany
1. **Ustawienia → Aplikacje → Domik → Bateria → Bez ograniczeń.**
2. **Ustawienia → Bateria → Limity użycia w tle**:
   - wyłącz **Usypiaj nieużywane aplikacje**,
   - w **Aplikacje nigdy nieusypiane** dodaj **Domik**.
3. **Ustawienia → Aplikacje → Domik** → wyłącz **Wstrzymaj aktywność, jeśli nieużywana** (*Pause app activity
   if unused*).

### 4.4. Bez ekranu blokady
Na ścianie wygodniej bez PIN-u: **Ustawienia → Ekran blokady i AOD → Typ blokady ekranu → Brak**
(albo *Przeciągnięcie*). Pamiętaj: każdy, kto podejdzie do tabletu, może sterować domem – tak jak wyłącznikami.
Nie loguj się na tym tablecie do kont z wrażliwymi danymi (bank, poczta służbowa).

### 4.5. Domik zawsze na wierzchu (przypinanie aplikacji)
1. W systemie włącz przypinanie: **Ustawienia → Bezpieczeństwo i prywatność → Więcej ustawień zabezpieczeń →
   Przypnij aplikacje** (u Samsunga bywa też **Przypinanie okien** / **Przypnij aplikację**; najłatwiej: **Ustawienia →
   lupa → wpisz „przypn”**). Opcjonalnie wyłącz „Pytaj o PIN przed odpinaniem”, skoro tablet nie ma blokady.
2. W Domiku: menu ☰ → **Tablet ścienny** → **Przypnij Domika** i potwierdź komunikat systemu. (Można też przez
   przycisk **ostatnich aplikacji**: stuknij **ikonę Domika** nad jego oknem → **Przypnij tę aplikację**.)
   Gdy przypinanie jest wyłączone w systemie, Domik pokaże, gdzie je włączyć.
3. Odpinanie: menu ☰ → **Odepnij Domika** (przycisk zmienia się, gdy Domik jest przypięty). Zawsze działa też gest:
   przytrzymaj jednocześnie przyciski **Wstecz** i **Ostatnie** (przy nawigacji gestami – przesuń od dołu i przytrzymaj).
   Jeśli przypięto z ostatnich aplikacji, system może nie pozwolić Domikowi odpiąć się samemu – użyj wtedy gestu.
4. Po **restarcie** tabletu przypięcie znika (to cecha Androida) – trzeba przypiąć ponownie (patrz §4.7 i §4.11).

### 4.6. Pasek nawigacji i pasek stanu
Domik ma tryb **pełnoekranowy**: menu ☰ → **Tablet ścienny** → **Pełny ekran** (włączone domyślnie) chowa pasek
stanu i pasek nawigacji. Przesunięcie od krawędzi ekranu pokazuje je na chwilę. Żeby na ścianie nic nie wystawało:
1. **Ustawienia → Wyświetlacz → Pasek nawigacji → Gesty przesuwania**, a w opcjach gestów wyłącz **Wskazówka gestów**
   (cienka kreska u dołu ekranu).
2. **Ustawienia → Wyświetlacz → Pasek zadań** – wyłącz (pasek aplikacji u dołu tabletu).
3. Odpinanie przy nawigacji gestami: przesuń palcem od dołu i przytrzymaj.

### 4.7. Domik jako ekran główny (autostart po restarcie)
Domyślnie po restarcie tabletu pokazuje się ekran główny Samsunga. Żeby tablet **sam uruchamiał Domika** po restarcie
(i po stuknięciu przycisku „Ekran główny”), ustaw Domika jako aplikację ekranu głównego:
1. Menu ☰ → **Tablet ścienny** → włącz **Domik jako ekran główny**. Domik otworzy ustawienia systemu.
2. Wybierz **Domik** jako aplikację ekranu głównego. Na Samsungu ta lista to zwykle **Ustawienia → Aplikacje →
   Wybierz aplikacje domyślne → Aplikacja ekranu głównego** (nazwy różnią się między wersjami One UI). Jeśli
   ustawienia nie otworzyły się same albo nie wybrałeś Domika, pod przełącznikiem pojawi się przycisk **Otwórz
   ustawienia ekranu głównego**.
3. Gotowe: po restarcie tablet startuje prosto w Domika. Dobrze połączyć to z przypięciem (§4.5) – po restarcie
   wystarczy wtedy raz stuknąć **Przypnij Domika**.

**Jak wrócić do zwykłego ekranu głównego:** menu ☰ → **Tablet ścienny** → wyłącz **Domik jako ekran główny**.
Domik przestanie być ekranem głównym, a system wróci do ekranu głównego Samsunga (**One UI Home**); gdyby tak
się nie stało, wybierz go ręcznie w tych samych ustawieniach (Domik otworzy je sam). Jeśli Domik jest przypięty, najpierw go odepnij (§4.5).

### 4.8. Orientacja ekranu
Zablokuj obrót w położeniu, w jakim tablet wisi: rozwiń panel szybkich ustawień i wyłącz **Autoobracanie**
(ustaw wcześniej tablet poziomo – układ poziomy pokazuje kalendarz i pogodę z lewej, strefy i światła z prawej).

### 4.9. Wi-Fi zawsze połączone
- Tablet w **tej samej sieci** co sterownik (nie w sieci gości, która często izoluje urządzenia).
- **Ustawienia → Połączenia → Wi-Fi → (⋮) Inteligentne Wi-Fi** – wyłącz **Przełączaj na dane mobilne** / automatyczne
  przełączanie sieci (jeśli są) oraz opcje oszczędzania energii Wi-Fi.
- Najlepiej pasmo 2,4 GHz lub 5 GHz z dobrym zasięgiem w miejscu montażu; po zawieszeniu tabletu sprawdź siłę
  sygnału.

### 4.10. Spokój w nocy i bez wyskakujących okienek
- **Ustawienia → Powiadomienia → Nie przeszkadzać** – włącz na stałe albo według harmonogramu; na ścianie
  powiadomienia innych aplikacji tylko przeszkadzają.
- Odinstaluj lub wyłącz zbędne aplikacje (gry, sklepy z powiadomieniami).
- **Ustawienia → Aktualizacja oprogramowania** – wyłącz **Pobieraj automatycznie przez Wi-Fi** albo instaluj
  aktualizacje ręcznie, gdy masz czas (aktualizacja restartuje tablet).

### 4.11. Automatyczne ponowne uruchamianie
**Ustawienia → Pielęgnacja urządzenia → Automatyczna optymalizacja → Uruchom ponownie w razie potrzeby** – tę opcję
możesz zostawić **włączoną**, jeśli Domik jest ustawiony jako ekran główny (§4.7): po nocnym restarcie tablet sam
wróci do Domika. Pozostanie tylko ponowne **przypięcie** (Domik → menu ☰ → **Przypnij Domika**). Bez ustawienia
Domika jako ekranu głównego opcję lepiej wyłączyć, bo po restarcie trzeba by otwierać Domika ręcznie.

### 4.12. Kontrola po montażu
- Ekran świeci jasno po kilkunastu minutach bez dotykania, gdy tablet stoi na ładowarce? (§4.1)
- Pasek nawigacji i pasek stanu są schowane, a po restarcie tablet startuje w Domiku? (§4.6, §4.7)
- Pulpit pokazuje aktualne temperatury, a zapalenie światła wyłącznikiem na ścianie po chwili zmienia kafelek?
- Po wyłączeniu i włączeniu routera Domik sam wraca do połączenia (pasek „ponawiam…” znika)?

---

## 5. Budowanie ze źródeł (dla programistów)

Wymagania: Android Studio (zawiera JDK) i Android SDK (platforma 37).
```bash
cd android
./gradlew :app:assembleDebug
```
APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Własny `applicationId` i podpis release – zobacz
`local.properties.example` i `keystore.properties.example` (pliki z kluczami trzymaj poza gitem).
Testy: `./gradlew test :app:testDebugUnitTest`.

---

## Ograniczenia wersji 0.1.0
- **Pogoda** wymaga internetu i ręcznego wskazania miejscowości (bez lokalizacji tabletu); dane Open-Meteo mają
  charakter wyłącznie informacyjny, a bez internetu kafelek pokazuje ostatnią zapamiętaną prognozę (oznaczoną jako
  nieaktualna). **Kalendarz** jest prawdziwy (kalendarze z tabletu,
  np. Google; wymaga zgody na odczyt kalendarza), a **układ pulpitu można edytować** (ikona ołówka: przeciąganie,
  rozmiar, ukrywanie, przywracanie układu domyślnego).
- **RGB i ściemnianie** – jeszcze bez sterowania (obsługiwane: włączniki, oświetlenie LED/ściemniacze jako
  włącz/wyłącz, rolety ↑ ■ ↓, strefy grzewcze ± i profil). Sterownik 1. generacji nie podaje położenia rolety –
  Domik pokazuje tylko to, co roleta sama zgłosiła.
- **Auto-wyłączanie** (np. garderoba po 30 min) jest gotowe w kodzie, ale jeszcze nie ma ustawień w aplikacji.
- **Przypięcie** Domika nie przetrwa restartu tabletu (ograniczenie Androida bez zarządzania urządzeniem) – po
  restarcie trzeba przypiąć ponownie.
- Wybór Domika jako ekranu głównego (§4.7) działa przez ustawienia systemu; nazwy pozycji różnią się między
  wersjami One UI.
- Plik APK jest podpisany kluczem autora; odcisk SHA-256 certyfikatu podpisu to `71:0C:AB:67:52:6A:DE:DB:C3:03:3F:44:00:EC:4C:EF:01:E0:C7:A9:C8:0C:2A:B4:54:F8:74:F2:97:9D:C9:59` (jak go
  sprawdzić – [README](../README.md#weryfikacja-pobranego-pliku)). Kolejne wersje będą podpisane tym samym kluczem,
  więc aktualizacja nad poprzednią wersją zachowuje ustawienia. Wcześniejsze wersje testowe podpisane kluczem
  debug trzeba raz odinstalować (ustawienia wpisujesz wtedy ponownie).
- Domik jest nieoficjalną aplikacją, niezwiązaną z producentem sterowników Deimic, i nie zastępuje oficjalnej
  aplikacji. Używasz jej na własną odpowiedzialność, bez gwarancji – patrz
  [Zastrzeżenia](../README.md#zastrzeżenia).
