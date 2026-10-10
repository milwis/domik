# Polityka prywatności aplikacji Domik

Wersja 1.0 · Obowiązuje od: 10 października 2026

[English version](en/privacy-policy.md) · [Regulamin](regulamin.md)

> **W skrócie:** Autor aplikacji Domik nie otrzymuje żadnych Twoich danych. Nie ma kont, analityki, reklam ani
> serwera autora. Wszystko, co aplikacja zapisuje, zostaje na Twoim telefonie lub tablecie. Sterownikiem steruje
> bezpośrednio w Twojej sieci lokalnej. Jeśli korzystasz z kafelka pogody, wysyła do serwisu pogodowego Open-Meteo
> tylko wpisaną nazwę miejscowości (przy wyszukiwaniu) i przybliżone współrzędne wybranej miejscowości.

Pojęcia pisane wielką literą (np. Aplikacja, Sterownik, Numer seryjny, Sieć lokalna, Repozytorium, Oficjalne źródło)
mają znaczenie nadane im w [Regulaminie](regulamin.md).

## 1. Autor i kontakt

Aplikację udostępnia autor występujący pod pseudonimem „milwis” (konto w serwisie GitHub: <https://github.com/milwis>)
(„Autor”). W sprawach prywatności można kontaktować się z Autorem przez zakładkę Issues Repozytorium
(<https://github.com/milwis/domik/issues>). Zgłoszenia w Issues są publiczne – nie umieszczaj w nich Numeru
seryjnego, adresu IP Sterownika ani innych danych, których nie chcesz ujawniać. Luki bezpieczeństwa zgłaszaj poufnie przez funkcję Security advisories Repozytorium
(<https://github.com/milwis/domik/security/advisories/new>).

## 2. Aplikacja nie zbiera danych

1. Aplikacja nie wysyła Autorowi żadnych danych – ani osobowych, ani technicznych. Autor nie prowadzi serwera,
   z którym Aplikacja by się łączyła.
2. Aplikacja nie ma kont ani logowania, nie zawiera analityki, reklam ani mechanizmów śledzenia, ani bibliotek (SDK)
   firm zewnętrznych służących do takich celów. Korzysta wyłącznie z bibliotek open source Google (AndroidX, Jetpack
   Compose) i JetBrains (Kotlin).
3. Autor nie ma dostępu do danych zapisanych przez Aplikację na Twoim Urządzeniu, do Twoich kalendarzy ani do Twojej
   Instalacji.
4. Poza Twoją Sieć lokalną Aplikacja wysyła wyłącznie dane potrzebne do wyświetlenia pogody – i tylko wtedy, gdy
   wyszukujesz lub wskażesz miejscowość dla kafelka pogody (pkt 4 ust. 3–4).
5. Kod źródłowy Aplikacji jest publiczny (Repozytorium), więc każdy może sprawdzić, jakie dane i dokąd Aplikacja
   wysyła.

## 3. Dane zapisywane tylko na Twoim Urządzeniu

Aby działać, Aplikacja zapisuje na Urządzeniu, w swojej prywatnej pamięci:

| Dane | Cel | Jak usunąć |
|---|---|---|
| Adres IP Sterownika | połączenie ze Sterownikiem | „Usuń zapisany sterownik” w ustawieniach Aplikacji lub usunięcie Aplikacji |
| Numer seryjny – zaszyfrowany kluczem przechowywanym w systemowym magazynie kluczy Android Keystore | nawiązanie połączenia ze Sterownikiem | „Usuń zapisany sterownik” w ustawieniach Aplikacji lub usunięcie Aplikacji |
| Kopia konfiguracji Sterownika (np. nazwy pomieszczeń, stref, wyjść) | wyświetlanie Instalacji i sterowanie nią | przez usunięcie Aplikacji; odświeżana ze Sterownika |
| Układ pulpitu, wybór ikon i elementów kafelków, wybór wyświetlanych kalendarzy, ustawienia wyglądu i trybu tabletu | działanie funkcji Aplikacji | w Aplikacji (np. „Przywróć układ domyślny”) lub przez jej usunięcie |
| Miejscowość wskazana dla pogody i jej przybliżone współrzędne | wyświetlanie pogody | „Usuń miejscowość” w menu Aplikacji lub usunięcie Aplikacji |
| Ostatnio pobrana prognoza pogody wraz z czasem pobrania i przybliżonymi współrzędnymi miejscowości (pamięć podręczna) | wyświetlanie ostatniej pogody, np. po ponownym uruchomieniu bez dostępu do Internetu | „Usuń miejscowość” w menu Aplikacji lub usunięcie Aplikacji; zastępowana przy każdym pobraniu |
| Wersja zaakceptowanego Regulaminu | potwierdzenie akceptacji, ponowne pytanie po zmianie Regulaminu | przez usunięcie Aplikacji |

1. Bieżący stan Instalacji (np. temperatury, stany wyjść) Aplikacja odczytuje ze Sterownika na potrzeby wyświetlania
   i nie przesyła go poza Twoją Sieć lokalną.
2. Wydarzenia z kalendarzy Aplikacja odczytuje z systemowego kalendarza Urządzenia (za Twoją zgodą, pkt 5) tylko po to,
   by pokazać je na kafelku. Nie zmienia ich, nie zapisuje i nie przesyła poza Urządzenie.
3. Wszystkie te dane przetwarzasz Ty – na swoim Urządzeniu i na własne potrzeby. Autor ich nie otrzymuje i nie ma do
   nich dostępu.
4. Usunięcie Aplikacji usuwa wszystkie zapisane przez nią dane, w tym zaszyfrowany Numer seryjny i klucz szyfrujący.
5. Dane Aplikacji nie są dołączane do kopii zapasowej w chmurze ani przenoszone na nowe urządzenie przy przenoszeniu
   danych.
6. Jeśli Urządzenie jest dostępne dla innych osób (np. tablet na ścianie), widzą one to, co pokazuje Aplikacja –
   w tym stan Instalacji i wydarzenia z kalendarza. To Ty decydujesz, które kafelki tam umieścić.
7. Tryb demonstracyjny korzysta wyłącznie z przykładowych danych wbudowanych w Aplikację (także przykładowego
   kalendarza i pogody) i nie łączy się z żadnym urządzeniem ani serwerem.

## 4. Połączenia sieciowe

1. **Sterownik.** Aplikacja łączy się ze Sterownikiem pod adresem IP podanym przez Ciebie, w Twojej Sieci lokalnej.
   Połączenie jest szyfrowane (TLS), a Aplikacja sprawdza certyfikat Sterownika. Poziom bezpieczeństwa tego połączenia
   zależy także od Sterownika i jego oprogramowania, na które Autor nie ma wpływu. Aplikacja nie łączy się z chmurą
   Producenta Sterownika ani z serwerami Autora.
2. **Kalendarze.** Synchronizację kalendarzy (np. kalendarza Google na koncie dodanym w Urządzeniu) wykonuje system
   Android i aplikacje dostawców kalendarzy, na ich zasadach. Aplikacja nie łączy się z ich serwerami.
3. **Wyszukiwanie miejscowości.** Gdy wpisujesz nazwę miejscowości dla pogody, Aplikacja wyszukuje ją w serwisie
   geokodowania Open-Meteo (<https://open-meteo.com>) już w trakcie pisania – po wpisaniu co najmniej 2 znaków
   i krótkiej przerwie w pisaniu. Do serwisu trafia wpisany tekst i kod języka, w którym mają być podane nazwy wyników
   (np. „pl”). Aplikacja nie prosi o dostęp do Twojej lokalizacji i z niej nie korzysta.
4. **Pogoda.** Aby pobrać pogodę i prognozę, Aplikacja wysyła do serwisu Open-Meteo zapytanie zawierające przybliżone
   współrzędne wybranej miejscowości (zaokrąglone do 2 miejsc po przecinku, czyli do ok. 1 km) – dokładniejsze
   współrzędne nie opuszczają Urządzenia. Prognoza jest pobierana po wybraniu miejscowości, a potem odświeżana co
   ok. 30 minut, gdy Aplikacja jest otwarta na ekranie, oraz po powrocie do Aplikacji, jeśli dane są starsze niż
   15 minut. Połączenie jest szyfrowane (HTTPS). Jak przy każdym połączeniu z Internetem, serwis otrzymuje też adres IP
   Twojego Urządzenia i standardowe dane techniczne połączenia. Zapytanie nie zawiera żadnych identyfikatorów
   Użytkownika, Urządzenia ani Instalacji. Open-Meteo przetwarza te dane na własnych zasadach, opisanych w serwisie.
   Dopóki nie wyszukujesz ani nie wybierzesz miejscowości, Aplikacja nie łączy się z serwisem pogodowym; po usunięciu
   miejscowości („Usuń miejscowość”) przestaje się z nim łączyć. W trybie demonstracyjnym nie łączy się z nim wcale
   (pkt 3 ust. 7).
5. **Linki.** Elementy otwierane przez Ciebie w innych aplikacjach (np. wydarzenie otwarte w aplikacji kalendarza,
   strona otwarta w przeglądarce) działają na zasadach danej aplikacji lub serwisu (pkt 8).

## 5. Uprawnienia

| Uprawnienie | Po co | Uwagi |
|---|---|---|
| Internet i stan sieci | połączenie ze Sterownikiem w Sieci lokalnej, pobieranie pogody, wykrywanie utraty i zmiany sieci | nadawane przy instalacji |
| Odczyt kalendarza | wyświetlanie wydarzeń na kafelku kalendarza; tylko odczyt | Aplikacja pyta dopiero po stuknięciu „Pokaż mój kalendarz”; bez zgody kafelek kalendarza nie pokazuje Twoich wydarzeń |
| Dostęp do urządzeń w sieci lokalnej (jeśli wersja Androida tego wymaga) | połączenie ze Sterownikiem | obecna wersja Aplikacji o to uprawnienie nie prosi; jeśli będzie go wymagać, bez zgody zadziała tylko tryb demonstracyjny |

Uprawnienia można w każdej chwili zmienić w Ustawieniach systemu (Ustawienia → Aplikacje → Domik → Uprawnienia).
Aplikacja nie prosi o dostęp do lokalizacji, kontaktów, zdjęć, aparatu ani mikrofonu i nie wysyła powiadomień.

Opcja „Domik jako ekran główny” (tryb tabletu ściennego) nie jest uprawnieniem: po jej włączeniu i wybraniu Domika
w ustawieniach systemu jako ekranu głównego Android sam otwiera Aplikację po ponownym uruchomieniu Urządzenia.

## 6. Raporty awarii i statystyki

1. Aplikacja nie ma mechanizmów raportowania błędów ani statystyk i nie przekazuje Autorowi żadnych raportów.
2. GitHub udostępnia Autorowi zbiorcze statystyki Repozytorium (np. liczbę pobrań plików wydań, liczbę odwiedzin
   Repozytorium). Autor nie otrzymuje w nich danych pozwalających zidentyfikować osoby pobierające Aplikację.

## 7. Kiedy Autor przetwarza Twoje dane osobowe

Działanie Aplikacji nie wiąże się z przetwarzaniem Twoich danych osobowych przez Autora. Autor przetwarza je tylko
wtedy, gdy kontaktujesz się z nim przez serwis GitHub (np. tworzysz zgłoszenie lub komentarz w zakładce Issues albo
zgłoszenie Security advisory).

### 7.1 Kontakt przez GitHub

- **Administrator:** Autor (pseudonim „milwis”), kontakt jak w pkt 1.
- **Dane:** nazwa użytkownika w serwisie GitHub i inne informacje z Twojego profilu, które GitHub pokazuje przy
  zgłoszeniu, treść zgłoszenia lub komentarza oraz załączniki (np. zrzuty ekranu).
- **Cel i podstawa prawna:** odpowiedź na zgłoszenie oraz rozpatrzenie zgłoszenia lub reklamacji (art. 6 ust. 1 lit. b
  RODO – działania związane z umową o korzystanie z Aplikacji), a także komunikacja z osobami kontaktującymi się
  z Autorem oraz ewentualne ustalenie, dochodzenie lub obrona roszczeń (art. 6 ust. 1 lit. f RODO – prawnie
  uzasadniony interes Autora).
- **Jawność:** zgłoszenia i komentarze w zakładce Issues są publiczne i pozostają w Repozytorium jako część historii
  projektu. Zgłoszenia Security advisories są poufne – widzą je tylko Autor i osoby przez niego zaproszone.
- **Okres przechowywania:** do czasu załatwienia sprawy, a następnie nie dłużej niż 12 miesięcy. Korespondencja
  dotycząca reklamacji – do upływu terminu przedawnienia związanych z nią roszczeń. Na Twoje żądanie Autor usunie lub
  zredaguje Twoje zgłoszenie lub komentarz w zakresie, w jakim pozwala na to serwis GitHub; własne komentarze możesz
  też edytować lub usuwać samodzielnie.
- **Odbiorcy:** GitHub, Inc. – dostawca serwisu, w którym prowadzone jest Repozytorium; dane z Twojego konta GitHub
  przetwarza on także jako odrębny administrator, na własnych zasadach (pkt 8). Jeżeli dane są przetwarzane poza
  Europejskim Obszarem Gospodarczym, odbywa się to na podstawie mechanizmów przewidzianych w RODO (np. decyzji Komisji
  Europejskiej stwierdzającej odpowiedni stopień ochrony lub standardowych klauzul umownych).
- **Dobrowolność:** kontakt jest dobrowolny, ale bez konta w serwisie GitHub nie można utworzyć zgłoszenia.
- **Nie przesyłaj Numeru seryjnego** ani innych danych dostępowych. Przed dodaniem zrzutu ekranu lub dziennika (logu)
  sprawdź, czy nie widać w nim Numeru seryjnego, adresu IP Sterownika, wydarzeń z kalendarza ani innych informacji,
  których nie chcesz upubliczniać.

### 7.2 Twoje prawa

W zakresie danych z pkt 7.1 masz prawo do dostępu do danych, ich sprostowania, usunięcia, ograniczenia
przetwarzania, przenoszenia (w zakresie danych przetwarzanych na podstawie umowy) oraz prawo sprzeciwu wobec
przetwarzania opartego na prawnie uzasadnionym interesie. Żądanie możesz zgłosić w sposób opisany w pkt 1 – nie
podawaj w nim więcej danych, niż to konieczne.

Przysługuje Ci także prawo wniesienia skargi do Prezesa Urzędu Ochrony Danych Osobowych (ul. Stawki 2, 00-193
Warszawa, <https://uodo.gov.pl>) lub do organu nadzorczego w państwie Twojego zwykłego pobytu.

Autor nie podejmuje decyzji opartych wyłącznie na zautomatyzowanym przetwarzaniu, w tym profilowania.

Danych z pkt 3 Autor nie posiada, więc nie może ich udostępnić ani usunąć – kontrolujesz je w pełni na swoim
Urządzeniu.

## 8. Usługi innych podmiotów

- **GitHub** – Repozytorium, pliki instalacyjne APK (Releases) oraz zgłoszenia (Issues, Security advisories) są
  udostępniane przez GitHub, Inc., który jest odrębnym administratorem danych. GitHub może zapisywać adresy IP osób
  odwiedzających Repozytorium i pobierających pliki, m.in. ze względów bezpieczeństwa, oraz stosuje własne pliki
  cookies – zgodnie z GitHub General Privacy Statement:
  <https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement>. Autor nie prowadzi
  własnej strony internetowej i nie stosuje własnych plików cookies, analityki ani narzędzi śledzących.
- **Open-Meteo** – dostawca danych pogodowych i wyszukiwarki miejscowości (pkt 4 ust. 3–4), działający na własnych
  zasadach: <https://open-meteo.com>.
- **Google** – system Android oraz synchronizacja kalendarzy Google na Urządzeniu (pkt 4 ust. 2) działają na zasadach
  Google, które jest odrębnym administratorem danych: <https://policies.google.com/privacy>. Aplikacja nie przekazuje
  Google żadnych danych.
- **Producent Sterownika** – Aplikacja nie przekazuje mu żadnych danych. Sterownik oraz usługi Producenta Sterownika
  (np. jego aplikacja lub chmura), jeśli z nich korzystasz, działają na jego zasadach, niezależnie od Aplikacji.

## 9. Dzieci

Aplikacja nie jest skierowana do dzieci i nie zbiera danych, w tym danych dzieci. Osoby niepełnoletnie powinny
korzystać z Aplikacji za zgodą rodzica lub opiekuna oraz osoby uprawnionej do Instalacji. Jeśli dziecko utworzyło
zgłoszenie lub komentarz w Repozytorium, rodzic lub opiekun może zażądać jego usunięcia.

## 10. Zmiany Polityki prywatności

1. Nowa wersja Polityki prywatności, z datą wejścia w życie, jest publikowana w Repozytorium (katalog `legal/`)
   i udostępniana w Aplikacji. Poprzednie wersje są dostępne w historii zmian Repozytorium.
2. Przed zmianą sposobu przekazywania danych (np. zmianą dostawcy pogody) lub rozpoczęciem przekazywania nowych danych
   Autor zaktualizuje Politykę prywatności. Aplikacja poinformuje o takiej zmianie przed pierwszym przekazaniem danych
   i – gdy wymagają tego przepisy – poprosi o zgodę.
