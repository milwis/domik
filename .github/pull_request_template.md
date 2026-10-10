## Opis zmiany

<!-- Co zmieniasz i dlaczego? Jeśli PR zamyka zgłoszenie, dopisz np. „Closes #123”. -->

## Jak sprawdzono

<!-- Emulator czy tablet, wersja Androida; tryb demo czy prawdziwy sterownik (bez podawania jego danych). -->

## Lista kontrolna

- [ ] Testy przechodzą lokalnie: `cd android && ./gradlew test :app:testDebugUnitTest`.
- [ ] Nowa logika ma testy jednostkowe, a zmiana w protokole (komunikacji ze sterownikiem) jest poparta testem na wektorach z `testdata/`.
- [ ] Interfejs jest po polsku: wszystkie teksty są w zasobach `values/` (opcjonalnie tłumaczenie w `values-en/`), w kodzie nie ma angielskich napisów na sztywno.
- [ ] W kodzie, testach, opisie PR ani zrzutach ekranu nie ma danych osobowych ani sekretów: adresu IP, numeru seryjnego sterownika, adresu MAC, kluczy, haseł ani nazw pomieszczeń z prawdziwego domu. Zrzuty ekranu pochodzą wyłącznie z trybu demo.
- [ ] Nie dodaję kodu, zasobów, grafik ani materiałów z oficjalnej aplikacji Deimic, z jej dekompilacji ani z chmury producenta.
- [ ] Testy nie wysyłają poleceń zapisujących (zmieniających stan) do prawdziwego sterownika.
- [ ] Nie dodaję zależności spoza Google/JetBrains (albo jest o tym dyskusja w Issue: #…).
- [ ] Mam prawa do tego wkładu i zgadzam się na jego licencję MIT (jak cały projekt).
