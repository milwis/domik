# Regulamin aplikacji Domik

**Warunki korzystania** · Wersja 1.0 · Obowiązuje od: 10 października 2026

[English version](en/terms.md) · [Polityka prywatności](polityka-prywatnosci.md)

> **W skrócie** (streszczenie nie zastępuje Regulaminu)
>
> - Domik to darmowy, **nieoficjalny** projekt edukacyjny i hobbystyczny o otwartym kodzie źródłowym (licencja MIT),
>   niezwiązany z producentem sterowników Deimic.
> - Nie jest alternatywą ani zamiennikiem oficjalnej aplikacji producenta i nie jest przez niego wspierany ani
>   zatwierdzony.
> - Steruje Twoim sterownikiem bezpośrednio w Twojej sieci lokalnej, bez chmury. Autor nie zbiera żadnych danych.
> - Korzystasz z niej na własne ryzyko i bez gwarancji. Nie może być jedynym zabezpieczeniem ogrzewania, ochrony przed
>   zamarzaniem, pomp ani rolet.
> - Steruj tylko własną instalacją albo cudzą za zgodą właściciela. Chroń numer seryjny sterownika.
> - Pobieraj aplikację tylko z oficjalnego źródła: <https://github.com/milwis/domik/releases>.
> - Kontakt z autorem – wyłącznie przez GitHub: <https://github.com/milwis/domik/issues>.
> - Regulamin nie ogranicza praw, które przysługują Ci jako konsumentowi z mocy prawa.

## § 1. Autor i kontakt

1. Aplikację udostępnia jej autor występujący pod pseudonimem „milwis” (konto w serwisie GitHub:
   <https://github.com/milwis>) – osoba fizyczna działająca prywatnie, nieodpłatnie i poza działalnością gospodarczą
   (dalej: „Autor”).
2. Kontakt z Autorem odbywa się za pośrednictwem serwisu GitHub:
   1. pytania, zgłoszenia błędów i reklamacje – w zakładce Issues Repozytorium:
      <https://github.com/milwis/domik/issues>;
   2. zgłoszenia luk bezpieczeństwa – poufnie, przez funkcję Security advisories Repozytorium:
      <https://github.com/milwis/domik/security/advisories/new>.
3. Regulamin jest udostępniany nieodpłatnie przed rozpoczęciem korzystania z Aplikacji – w Repozytorium
   (<https://github.com/milwis/domik/blob/main/legal/regulamin.md>) w sposób umożliwiający jego pobranie, zapisanie
   i wydrukowanie, a w Aplikacji – na ekranie akceptacji wyświetlanym przy pierwszym uruchomieniu (streszczenie wraz
   z adresem pełnej treści).

## § 2. Definicje

- **Aplikacja** – program komputerowy Domik na urządzenia z systemem Android, wraz z aktualizacjami.
- **Użytkownik** – każda osoba korzystająca z Aplikacji.
- **Konsument** – Użytkownik będący osobą fizyczną, który korzysta z Aplikacji w celu niezwiązanym bezpośrednio z jego
  działalnością gospodarczą lub zawodową. Postanowienia dotyczące Konsumenta stosuje się także do osoby fizycznej
  zawierającej umowę bezpośrednio związaną z jej działalnością gospodarczą, gdy z treści tej umowy wynika, że nie ma
  ona dla tej osoby charakteru zawodowego.
- **Sterownik** – sterownik automatyki budynkowej marki Deimic 1. generacji, z którym Aplikacja współpracuje.
- **Producent Sterownika** – podmiot, który produkuje Sterownik lub wprowadza go do obrotu pod marką Deimic, oraz
  podmioty z nim powiązane.
- **Instalacja** – instalacja obsługiwana przez Sterownik wraz z przyłączonymi urządzeniami (np. oświetleniem,
  ogrzewaniem, gniazdami, pompami, roletami).
- **Numer seryjny** – numer seryjny Sterownika, potrzebny do nawiązania z nim połączenia.
- **Sieć lokalna** – sieć (LAN / Wi-Fi), do której podłączony jest Sterownik.
- **Urządzenie** – smartfon lub tablet z systemem Android, na którym Użytkownik korzysta z Aplikacji.
- **Repozytorium** – publiczne repozytorium projektu prowadzone przez Autora w serwisie GitHub:
  <https://github.com/milwis/domik>, zawierające kod źródłowy Aplikacji, niniejszy Regulamin i Politykę prywatności.
- **Oficjalne źródło** – miejsce, z którego Autor udostępnia Aplikację: sekcja wydań (Releases) Repozytorium
  (<https://github.com/milwis/domik/releases>), w tym opublikowane tam pliki instalacyjne APK. Aplikacja nie jest
  obecnie dostępna w sklepie Google Play.
- **Regulamin** – niniejsze warunki korzystania z Aplikacji.

## § 3. Charakter Aplikacji

1. Aplikacja jest nieoficjalnym, niezależnym projektem Autora. **Nie jest produktem Producenta Sterownika, nie jest
   z nim powiązana i nie jest przez niego autoryzowana, sponsorowana, zatwierdzona ani wspierana.**
2. Aplikacja jest projektem edukacyjnym i hobbystycznym, przeznaczonym dla osób rozwijających zainteresowania
   automatyką domową (smart home) opartą na Sterownikach. **Nie jest alternatywą ani zamiennikiem oficjalnej aplikacji
   ani innego oprogramowania Producenta Sterownika** i nie ma na celu ich zastąpienia.
3. Producent Sterownika nie odpowiada za Aplikację i nie udziela do niej pomocy. Pytania i zgłoszenia dotyczące
   Aplikacji należy kierować wyłącznie do Autora (§ 1 ust. 2).
4. Aplikacja została napisana od podstaw, niezależnie od oprogramowania Producenta Sterownika, w celu współdziałania
   ze Sterownikiem. Nie zawiera oprogramowania Producenta Sterownika.
5. „Deimic” i inne oznaczenia Producenta Sterownika mogą być znakami towarowymi ich właścicieli. W Aplikacji, w jej
   opisie i w Repozytorium są używane wyłącznie opisowo – aby wskazać, z jakimi urządzeniami Aplikacja współpracuje.
   Android jest znakiem towarowym Google LLC, a GitHub – znakiem towarowym GitHub, Inc. Wszystkie znaki towarowe należą
   do ich właścicieli.
6. Aplikacja jest bezpłatna. Nie zawiera reklam ani zakupów w aplikacji.
7. Kod źródłowy Aplikacji jest publicznie dostępny w Repozytorium na licencji MIT (§ 7 ust. 1).

## § 4. Funkcje Aplikacji

1. W zakresie, na jaki pozwalają Sterownik i jego konfiguracja, Aplikacja umożliwia w szczególności:
   1. odczyt stanu Instalacji (np. temperatur, stanu oświetlenia i innych wyjść);
   2. sterowanie ogrzewaniem (np. zmianę temperatury zadanej lub profilu w strefach);
   3. włączanie i wyłączanie oświetlenia i innych wyjść;
   4. sterowanie roletami (podnoszenie, opuszczanie, zatrzymanie);
   5. układanie własnego pulpitu z kafelkami, w tym pracę w trybie stale włączonego tabletu (np. na ścianie);
   6. wyświetlanie wydarzeń z kalendarzy dostępnych na Urządzeniu – tylko do odczytu i za zgodą Użytkownika;
   7. wyświetlanie bieżącej pogody i prognozy dla miejscowości wskazanej przez Użytkownika;
   8. tryb demonstracyjny na przykładowych danych, bez łączenia się z jakimkolwiek urządzeniem.
2. Do sterowania Instalacją Aplikacja łączy się bezpośrednio ze Sterownikiem w Sieci lokalnej (połączenie szyfrowane
   TLS). Nie korzysta z chmury Producenta Sterownika ani z serwerów Autora. Z Internetem Aplikacja łączy się wyłącznie
   w celu wyszukania wskazanej miejscowości i pobrania prognozy pogody z zewnętrznego serwisu (obecnie Open-Meteo) –
   szczegóły w Polityce prywatności.
3. Aplikacja odczytuje ze Sterownika jego konfigurację (np. nazwy pomieszczeń, stref i wyjść), aby ją wyświetlić.
   Nie wgrywa do Sterownika oprogramowania (firmware) i nie zmienia zapisanej w nim konfiguracji.
4. Dane pogodowe i kalendarzowe pochodzą od podmiotów trzecich i mają charakter wyłącznie informacyjny. Nie należy
   na ich podstawie podejmować decyzji wpływających na bezpieczeństwo ludzi, zwierząt lub mienia. Dane pogodowe są
   udostępniane na warunkach ich dostawcy, wskazanych w Aplikacji (sekcja „Pogoda” w menu).
5. Zakres funkcji może się zmieniać w kolejnych wersjach Aplikacji (§ 13). Aktualny opis funkcji zawierają Repozytorium
   i sama Aplikacja.

## § 5. Wymagania techniczne

1. Do korzystania z Aplikacji (poza trybem demonstracyjnym) potrzebne są:
   1. Sterownik Deimic 1. generacji – Aplikacja nie jest przeznaczona do współpracy z innymi sterownikami;
   2. Urządzenie z systemem Android w wersji 8.0 lub nowszej;
   3. połączenie Urządzenia z Siecią lokalną, w której działa Sterownik, oraz zgoda na dostęp Aplikacji do sieci
      lokalnej, jeśli system jej wymaga;
   4. adres IP Sterownika w Sieci lokalnej (zalecany stały adres, np. rezerwacja DHCP w routerze) i Numer seryjny;
   5. dla kafelka pogody – dostęp do Internetu; dla kafelka kalendarza – zgoda na dostęp do kalendarza.
2. Instalacja z pliku APK wymaga zezwolenia w systemie Android na instalowanie aplikacji z wybranego źródła.
3. Zdalne łączenie się z Siecią lokalną (np. przez VPN) Użytkownik organizuje i zabezpiecza samodzielnie, na własną
   odpowiedzialność.
4. Autor nie gwarantuje, że Aplikacja będzie działać z każdą wersją oprogramowania i każdą konfiguracją Sterownika ani
   na każdym Urządzeniu. Producent Sterownika może zmienić jego oprogramowanie w sposób, który ograniczy lub
   uniemożliwi działanie Aplikacji.

## § 6. Zawarcie i rozwiązanie umowy

1. Umowa o korzystanie z Aplikacji na warunkach Regulaminu zostaje zawarta z chwilą akceptacji Regulaminu na ekranie
   wyświetlanym przy pierwszym uruchomieniu Aplikacji. Umowa jest nieodpłatna i zawarta na czas nieoznaczony.
2. Użytkownik może w każdej chwili, bez podawania przyczyny i bez kosztów, rozwiązać umowę, usuwając Aplikację
   z Urządzenia. Zasady usuwania danych zapisanych przez Aplikację opisuje Polityka prywatności.
3. Zasady zakończenia rozwoju i udostępniania Aplikacji przez Autora określa § 13.

## § 7. Licencja

1. Aplikacja – zarówno jej kod źródłowy, jak i pliki instalacyjne APK publikowane w Oficjalnym źródle – jest
   udostępniana na licencji MIT, której treść znajduje się w Repozytorium (plik LICENSE). Licencja MIT pozwala
   w szczególności korzystać z Aplikacji, kopiować ją, zmieniać i rozpowszechniać, pod warunkami w niej określonymi
   (m.in. zachowania informacji o prawach autorskich i treści licencji). **Regulamin nie ogranicza uprawnień
   wynikających z licencji MIT.**
2. Regulamin określa zasady korzystania z Aplikacji do sterowania Instalacją, w tym obowiązki Użytkownika
   i ograniczenia związane z bezpieczeństwem (§§ 8–10), oraz zasady odpowiedzialności Autora (§ 11).
3. Korzystanie z serwisu GitHub (w tym przeglądanie Repozytorium i pobieranie z niego plików) podlega warunkom GitHub.
   Nie zmieniają one Regulaminu w relacji między Użytkownikiem a Autorem.
4. Użytkownik może przekazywać innym osobom odnośnik do Oficjalnego źródła lub do Repozytorium.
5. Użytkownikowi nie wolno:
   1. korzystać z Aplikacji w sposób sprzeczny z prawem lub Regulaminem, w szczególności z § 8;
   2. rozpowszechniać zmienionych wersji Aplikacji pod nazwą „Domik” lub w inny sposób wprowadzający w błąd co do
      ich pochodzenia, w szczególności sugerujący, że pochodzą od Autora;
   3. przedstawiać Aplikacji ani jej zmienionych wersji jako produktu Producenta Sterownika;
   4. przekazywać Autorowi treści o charakterze bezprawnym (np. w zgłoszeniach w Repozytorium).
6. Ust. 5 nie ogranicza uprawnień, które przysługują Użytkownikowi na podstawie bezwzględnie obowiązujących przepisów
   prawa ani licencji MIT, o której mowa w ust. 1.
7. Prawa autorskie do Aplikacji przysługują Autorowi, z wyjątkiem elementów pochodzących od osób trzecich, wskazanych
   w Repozytorium (jeśli występują).

## § 8. Uprawnienie do sterowania Instalacją

1. Łącząc Aplikację ze Sterownikiem, Użytkownik oświadcza, że jest właścicielem Instalacji lub osobą uprawnioną do
   korzystania z niej (np. domownikiem, najemcą) albo działa za zgodą takiej osoby.
2. Zabronione jest łączenie się za pomocą Aplikacji z cudzym Sterownikiem lub cudzą Instalacją bez zgody osoby
   uprawnionej, w tym z użyciem Numeru seryjnego uzyskanego bez jej wiedzy. Takie działanie może naruszać przepisy
   prawa, w tym prawa karnego.

## § 9. Obowiązki Użytkownika i bezpieczeństwo

1. **Numer seryjny działa jak klucz do Instalacji.** Każdy, kto go zna i ma dostęp do Sieci lokalnej, może odczytywać
   stan Instalacji i nią sterować – także bez Aplikacji. Dlatego Użytkownik powinien:
   1. nie ujawniać Numeru seryjnego osobom nieuprawnionym – również na forach, na zrzutach ekranu i w zgłoszeniach
      w Repozytorium (zgłoszenia w zakładce Issues są publiczne);
   2. zabezpieczyć Sieć lokalną: silne hasło Wi-Fi, aktualne oprogramowanie routera, oddzielna sieć dla gości, brak
      udostępniania Sterownika do Internetu (np. przez przekierowanie portów);
   3. chronić Urządzenie (blokada ekranu, aktualizacje systemu). Osoba z dostępem do odblokowanego Urządzenia może
      sterować Instalacją. Urządzenie stale dostępne dla innych osób (np. tablet na ścianie) pozwala sterować
      Instalacją każdemu, kto znajdzie się w jego pobliżu – o takim użyciu decyduje Użytkownik.
2. **Aplikację należy pobierać wyłącznie z Oficjalnego źródła.** Plik instalacyjny z innego źródła mógł zostać
   zmieniony i zawierać złośliwe oprogramowanie zdolne przejąć kontrolę nad Instalacją. Jeśli Autor publikuje sumę
   kontrolną pliku, warto ją sprawdzić.
3. **Podstawowym sposobem obsługi Instalacji pozostaje sterowanie fizyczne** (przyciski, panele) i narzędzia
   Producenta Sterownika. Użytkownik powinien utrzymywać je w sprawności i umieć z nich korzystać, także na wypadek
   niedostępności Aplikacji.
4. Korzystanie z oprogramowania i sieci wiąże się z typowymi zagrożeniami, m.in.: nieuprawnionym dostępem do Sieci
   lokalnej lub Sterownika, złośliwym oprogramowaniem, przechwyceniem Numeru seryjnego oraz podszywaniem się pod inne
   osoby (phishing). **Autor nigdy nie prosi o Numer seryjny ani o hasła.**

## § 10. Funkcje krytyczne – ważne ograniczenia

1. **Aplikacja nie może być jedynym ani podstawowym zabezpieczeniem funkcji, od których zależy bezpieczeństwo ludzi,
   zwierząt lub mienia**, takich jak ochrona instalacji przed zamarzaniem, praca pomp, ogrzewanie, rolety i inne
   napędy. Takie funkcje powinny być zabezpieczone niezależnie od Aplikacji (np. w konfiguracji Sterownika, przez
   instalatora, osobnymi urządzeniami).
2. Polecenia – w tym polecenia wykonywane automatycznie (np. harmonogramy lub automatyczne wyłączanie), jeśli
   Aplikacja takie funkcje oferuje – mogą nie zostać wykonane lub zostać wykonane z opóźnieniem, w szczególności gdy:
   1. Urządzenie nie jest połączone z Siecią lokalną;
   2. system operacyjny lub funkcje oszczędzania energii wstrzymały, ograniczyły lub zamknęły Aplikację;
   3. Urządzenie jest wyłączone, rozładowane, ponownie uruchamiane lub aktualizowane;
   4. wystąpi awaria Sieci lokalnej, zasilania lub Sterownika;
   5. nie działa urządzenie wykonujące reguły (np. tablet na ścianie lub domowy serwer), jeśli Użytkownik z niego
      korzysta;
   6. Producent Sterownika zmienił oprogramowanie Sterownika.

   Polecenia mogą też nie zostać wykonane wskutek błędu Aplikacji.
3. Funkcje automatyczne wykonywane przez Urządzenie (np. tablet na ścianie), jeśli Aplikacja je oferuje, działają
   tylko wtedy, gdy to Urządzenie jest włączone, połączone ze Sterownikiem i Aplikacja na nim działa.
4. Stan Instalacji widoczny w Aplikacji może różnić się od rzeczywistego, np. przy opóźnieniu lub utracie połączenia.
   Gdy ma to znaczenie dla bezpieczeństwa, Użytkownik powinien sprawdzić stan na miejscu.
5. Przed zdalnym uruchomieniem rolet, napędów lub urządzeń grzewczych Użytkownik powinien upewnić się, że nie stworzy
   to zagrożenia dla osób lub zwierząt.
6. Aplikacja może ograniczać zakres ustawień (np. temperatury) ze względów bezpieczeństwa. Nie zwalnia to Użytkownika
   z oceny skutków wydawanych poleceń.

## § 11. Wyłączenie gwarancji i odpowiedzialność

1. Aplikacja jest udostępniana nieodpłatnie, w stanie, w jakim się znajduje („tak jak jest”). W granicach
   dopuszczalnych przez prawo Autor nie udziela gwarancji ani zapewnień, w szczególności że Aplikacja będzie działać
   nieprzerwanie i bezbłędnie, z każdym Sterownikiem, każdą wersją jego oprogramowania i na każdym Urządzeniu, ani że
   spełni oczekiwania Użytkownika.
2. Z uwagi na nieodpłatny charakter Aplikacji Autor odpowiada za niewykonanie lub nienależyte wykonanie umowy tylko
   wtedy, gdy szkoda została wyrządzona umyślnie lub wskutek rażącego niedbalstwa – z zastrzeżeniem ust. 4.
3. W granicach dopuszczalnych przez prawo i z zastrzeżeniem ust. 4 Autor nie odpowiada za szkody wynikłe z:
   1. błędnej konfiguracji Sterownika lub Instalacji;
   2. awarii lub niedostępności Sieci lokalnej, Internetu, zasilania lub Urządzenia;
   3. zmian oprogramowania Sterownika wprowadzonych przez Producenta Sterownika;
   4. działań lub zaniechań Producenta Sterownika, Google, GitHub lub innych osób trzecich, za które Autor nie
      odpowiada;
   5. ujawnienia Numeru seryjnego lub korzystania z Aplikacji niezgodnie z Regulaminem, w szczególności z §§ 8–10;
   6. niewykonania lub opóźnienia poleceń z przyczyn wymienionych w § 10 ust. 2 pkt 1–6;
   7. nieścisłości lub niedostępności danych pochodzących od podmiotów trzecich (np. prognozy pogody, kalendarzy);
   8. korzystania z wersji Aplikacji pobranych spoza Oficjalnego źródła lub zmienionych przez osoby trzecie;
   9. stanu technicznego, montażu i zasilania Urządzenia (np. tabletu pracującego stale na ścianie).
4. **Żadne postanowienie Regulaminu nie wyłącza ani nie ogranicza odpowiedzialności Autora:**
   1. za szkodę wyrządzoną umyślnie lub wskutek rażącego niedbalstwa;
   2. za szkodę na osobie (śmierć, uszkodzenie ciała, rozstrój zdrowia);
   3. wobec Konsumenta – w zakresie, w jakim byłoby to sprzeczne z przepisami chroniącymi konsumentów, w szczególności
      z przepisami o niedozwolonych postanowieniach umownych;
   4. w innych przypadkach, w których wyłączenie lub ograniczenie odpowiedzialności jest niedopuszczalne na podstawie
      bezwzględnie obowiązujących przepisów prawa (np. przepisów o odpowiedzialności za produkt niebezpieczny, jeżeli
      mają zastosowanie).
5. Wobec Użytkowników niebędących Konsumentami Autor, w granicach dopuszczalnych przez prawo, nie odpowiada za
   utracone korzyści.
6. Jeżeli do umowy mają zastosowanie przepisy ustawy o prawach konsumenta dotyczące dostarczania treści cyfrowych,
   Konsumentowi przysługują wynikające z nich uprawnienia, a Regulamin ich nie ogranicza.

## § 12. Gwarancja Producenta Sterownika

1. Aplikacja nie wgrywa do Sterownika oprogramowania (firmware) i nie zmienia zapisanej w nim konfiguracji. Wysyła do
   Sterownika wyłącznie polecenia odczytu i sterowania (np. odczytaj stan, włącz lub wyłącz wyjście, ustaw
   temperaturę lub profil ogrzewania, podnieś, opuść lub zatrzymaj roletę).
2. Warunki gwarancji Sterownika określa gwarant (np. Producent Sterownika). Autor nie ma wpływu na to, jak gwarant
   oceni korzystanie z Aplikacji. Użytkownik powinien sam zapoznać się z warunkami gwarancji i ocenić, czy korzystanie
   z Aplikacji może mieć na nie wpływ.
3. W razie usterki Sterownika lub Instalacji warto przed zgłoszeniem jej gwarantowi lub instalatorowi sprawdzić, czy
   występuje ona również bez użycia Aplikacji (np. przy sterowaniu przyciskami lub oprogramowaniem Producenta
   Sterownika).

## § 13. Aktualizacje i zakończenie rozwoju

1. Autor rozwija Aplikację nieodpłatnie, w miarę swoich możliwości, i może udostępniać aktualizacje, które zmieniają,
   dodają lub usuwają funkcje – w szczególności w celu usunięcia błędów, poprawy bezpieczeństwa lub dostosowania do
   zmian w systemie Android lub Sterowniku. Autor zaleca instalowanie aktualizacji, bo mogą zawierać poprawki
   bezpieczeństwa.
2. Aplikacja nie aktualizuje się samodzielnie. Nową wersję Użytkownik pobiera z Oficjalnego źródła; Autor informuje
   o nowych wersjach w Repozytorium (sekcja wydań – Releases).
3. Autor może w każdej chwili wstrzymać lub zakończyć rozwój Aplikacji, zaprzestać udostępniania pliku instalacyjnego
   lub zarchiwizować Repozytorium. Nie pozbawia to Użytkownika możliwości korzystania z już zainstalowanej Aplikacji,
   ale może uniemożliwić jej ponowne pobranie i otrzymywanie aktualizacji. O zakończeniu rozwoju Autor poinformuje,
   w miarę możliwości, w Repozytorium.
4. Aplikacja może przestać działać w całości lub w części wskutek zmian niezależnych od Autora, w szczególności zmian
   oprogramowania Sterownika lub systemu Android.

## § 14. Reklamacje i kontakt

1. Pytania, zgłoszenia błędów i reklamacje dotyczące Aplikacji można zgłaszać w zakładce Issues Repozytorium
   (<https://github.com/milwis/domik/issues>). Zgłoszenia w zakładce Issues są publiczne – widzi je każdy, kto odwiedza
   Repozytorium. Luki bezpieczeństwa należy zgłaszać poufnie przez funkcję Security advisories Repozytorium
   (<https://github.com/milwis/domik/security/advisories/new>), a nie w publicznych Issues.
2. Aby ułatwić rozpatrzenie zgłoszenia, warto podać: opis problemu i sposób jego odtworzenia, wersję Aplikacji, model
   Urządzenia i wersję systemu, a jeśli to możliwe – wersję oprogramowania Sterownika. Brak tych informacji nie
   powoduje pozostawienia reklamacji bez rozpatrzenia.
3. **Nie należy przesyłać Numeru seryjnego** ani innych danych dostępowych. Autor nigdy o nie nie prosi. Przed
   wklejeniem do zgłoszenia zrzutu ekranu lub dziennika (logu) warto usunąć z niego Numer seryjny, adres IP Sterownika
   i inne informacje, których Użytkownik nie chce upubliczniać.
4. Autor odpowiada na reklamację w terminie 14 dni od jej otrzymania – w tym samym zgłoszeniu w Repozytorium.
5. Ze względu na nieodpłatny charakter Aplikacji rozpatrzenie reklamacji polega w szczególności na wyjaśnieniu
   problemu, wskazaniu sposobu jego obejścia lub usunięciu błędu w jednej z kolejnych wersji Aplikacji. Nie ogranicza
   to uprawnień przysługujących Konsumentowi z mocy prawa.
6. Sprawy dotyczące konta w serwisie GitHub i działania tego serwisu rozpatruje GitHub, Inc.

## § 15. Serwis GitHub

1. Umowa o korzystanie z Aplikacji jest zawierana między Użytkownikiem a Autorem. GitHub, Inc. i podmioty z nią
   powiązane nie są jej stronami i nie odpowiadają za Aplikację ani jej treść – także wtedy, gdy Aplikacja została
   pobrana z Repozytorium.
2. GitHub nie ma obowiązku świadczenia pomocy technicznej ani usług serwisowych dotyczących Aplikacji. Zgłoszenia
   i roszczenia dotyczące Aplikacji należy kierować do Autora (§ 14).

## § 16. Prywatność

Aplikacja nie przesyła Autorowi żadnych danych. Jedyne dane wysyłane poza Sieć lokalną służą do wyświetlenia pogody.
Szczegóły zawiera [Polityka prywatności](polityka-prywatnosci.md).

## § 17. Zmiany Regulaminu

1. Autor może zmienić Regulamin z ważnych przyczyn, którymi są:
   1. zmiana przepisów prawa, orzeczenie sądu lub decyzja organu wpływające na treść Regulaminu;
   2. zmiana wymagań systemu Android lub warunków serwisu GitHub;
   3. zmiana funkcji Aplikacji;
   4. potrzeba zapewnienia bezpieczeństwa Użytkowników lub Instalacji;
   5. zmiany Sterownika lub jego oprogramowania wpływające na działanie Aplikacji;
   6. zmiana danych kontaktowych Autora lub Oficjalnego źródła;
   7. potrzeba usunięcia niejasności, błędów lub omyłek.
2. Nowa wersja Regulaminu, z numerem wersji i datą wejścia w życie, jest publikowana w Repozytorium (katalog `legal/`)
   i udostępniana w Aplikacji. Zmiany nie działają wstecz.
3. Zmiany wpływające na prawa lub obowiązki Użytkownika wiążą go od chwili ich akceptacji. Aplikacja wyświetla nową
   wersję Regulaminu do akceptacji przy pierwszym uruchomieniu po aktualizacji; do tego czasu obowiązuje dotychczasowa
   wersja. Użytkownik, który nie akceptuje zmian, może w każdej chwili bez kosztów rozwiązać umowę, usuwając
   Aplikację.
4. Zmiany, które nie wpływają na prawa ani obowiązki Użytkownika (np. zmiana danych kontaktowych, poprawki
   redakcyjne), wchodzą w życie z dniem publikacji nowej wersji Regulaminu.
5. Poprzednie wersje Regulaminu są dostępne w historii zmian Repozytorium, w tym w oznaczonych wydaniach (tagach).

## § 18. Prawo właściwe i spory

1. Do umowy i Regulaminu stosuje się prawo polskie. Wybór prawa nie pozbawia Konsumenta ochrony przyznanej mu przez
   bezwzględnie obowiązujące przepisy państwa, w którym ma on miejsce zwykłego pobytu.
2. Spory rozstrzyga sąd właściwy zgodnie z obowiązującymi przepisami. Regulamin nie zmienia ustawowej właściwości sądu
   w sprawach z udziałem Konsumenta.
3. Konsument może skorzystać z pozasądowych sposobów rozwiązywania sporów, m.in.:
   1. zwrócić się o bezpłatną pomoc do miejskiego lub powiatowego rzecznika konsumentów albo do organizacji
      konsumenckiej (np. Federacji Konsumentów);
   2. wystąpić do wojewódzkiego inspektora Inspekcji Handlowej z wnioskiem o wszczęcie postępowania w sprawie
      pozasądowego rozwiązania sporu albo do stałego polubownego sądu konsumenckiego;
   3. w sporach transgranicznych w UE – zwrócić się do Europejskiego Centrum Konsumenckiego.

   Informacje o tych sposobach i wykaz uprawnionych podmiotów publikuje Prezes Urzędu Ochrony Konkurencji i Konsumentów
   (<https://uokik.gov.pl>). Udział w postępowaniu pozasądowym jest dobrowolny dla obu stron.
4. Autor zachęca, aby w razie problemu najpierw zgłosić go w zakładce Issues Repozytorium
   (<https://github.com/milwis/domik/issues>).

## § 19. Postanowienia końcowe

1. Regulamin sporządzono w języku polskim. Wersja angielska jest tłumaczeniem; w razie rozbieżności rozstrzyga wersja
   polska, chyba że bezwzględnie obowiązujące przepisy stanowią inaczej.
2. Nieważność lub bezskuteczność jednego z postanowień Regulaminu nie wpływa na ważność pozostałych. W miejsce takiego
   postanowienia stosuje się odpowiednie przepisy prawa.
3. W sprawach nieuregulowanych w Regulaminie stosuje się przepisy prawa polskiego, w szczególności Kodeksu cywilnego,
   ustawy o prawie autorskim i prawach pokrewnych oraz – w zakresie, w jakim mają zastosowanie – ustawy o prawach
   konsumenta i ustawy o świadczeniu usług drogą elektroniczną.
