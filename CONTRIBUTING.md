# Współtworzenie Domika

Dziękuję, że chcesz pomóc! Domik to **hobbystyczny, nieoficjalny projekt edukacyjny** – panel ścienny na tablet
z Androidem do sterowników Deimic 1. generacji. Nie jest związany z producentem sterowników. Poprawki, zgłoszenia
błędów i pomysły są mile widziane, ale odpowiadam w wolnych chwilach, więc proszę o cierpliwość.

## Jak zgłosić poprawkę

Każda zmiana trafia do gałęzi `main` **wyłącznie przez Pull Request** i musi zostać przejrzana (review)
przez właściciela repozytorium.

1. **Fork**: zrób fork repozytorium na swoje konto.
2. **Gałąź**: utwórz w forku osobną gałąź na jedną zmianę, np. `git switch -c poprawka-kalendarza`.
3. **Zmiana**: wprowadź poprawkę, dopisz testy (patrz niżej) i uruchom je lokalnie.
4. **Pull Request**: otwórz PR z gałęzi w forku do `main` tego repozytorium i wypełnij szablon (lista kontrolna).
   Jedna zmiana = jeden PR. Większe lub sporne zmiany najpierw omów w Issue.
5. **Review**: właściciel przejrzy PR, może poprosić o poprawki. Dopisuj je jako kolejne commity w tej samej
   gałęzi. Po nowych commitach wcześniejsze zatwierdzenie przestaje obowiązywać i trzeba je powtórzyć.
   Wszystkie komentarze w PR muszą zostać rozwiązane.
6. **Scalenie**: PR jest scalany metodą *squash*, czyli trafia do `main` jako jeden commit.

Gałąź `main` jest chroniona: bezpośrednie wypychanie (push) jest zablokowane, wymagany jest Pull Request
z zatwierdzeniem właściciela, nie wolno robić force-push ani usuwać gałęzi, a historia pozostaje liniowa.
Workflow CI (testy) dla PR-ów od osób spoza repozytorium uruchamia właściciel ręcznym zatwierdzeniem, po wstępnym przejrzeniu zmian.

## Budowanie i testy

Budowanie ze źródeł opisuje `android/INSTRUKCJA.md`, §5 (wymagane: JDK 17 i Android SDK z platformą 37).
W skrócie:

```bash
cd android
./gradlew :app:assembleDebug                  # budowa APK debug
./gradlew test :app:testDebugUnitTest         # testy jednostkowe (muszą przechodzić przed PR)
```

Nowa logika powinna mieć testy jednostkowe. Zmiana w protokole (komunikacji ze sterownikiem) musi być poparta
testem na nagranych wektorach w `testdata/`.

## Zasady projektu

- **Projekt nieoficjalny i edukacyjny.** Nie dołączaj kodu, zasobów, grafik ani innych materiałów z oficjalnej
  aplikacji Deimic, z jej dekompilacji ani z chmury producenta. Wkład ma powstawać na podstawie własnej pracy.
- **Zależności.** Nie dodawaj bibliotek spoza Google i JetBrains (plus JUnit do testów) bez wcześniejszej
  dyskusji w Issue.
- **Interfejs po polsku.** Teksty widoczne dla użytkownika trafiają do zasobów `values/` (nie na sztywno do
  kodu). Tłumaczenie angielskie w `values-en/` jest opcjonalne.
- **Commity** po polsku lub po angielsku: krótkie i konkretne (co i dlaczego).
- **Testy na prawdziwym sterowniku** wykonuj tylko na własnym urządzeniu i we własnej sieci. Zaczynaj od
  odczytów; polecenia zmieniające stan (światła, temperatura, profile) wysyłaj dopiero świadomie i na wyjściu,
  którego stan łatwo przywrócić. Automatyczne testy w repozytorium nigdy nie wysyłają poleceń zapisujących
  do prawdziwego sterownika.
- **Bądź uprzejmy.** Rozmawiamy rzeczowo i życzliwie.

## Prywatność

Nigdy nie wklejaj w Issue ani w Pull Requeście danych swojego domu: numeru seryjnego sterownika, adresu IP,
adresu MAC, nazw pomieszczeń, świateł i stref ani niezamaskowanych logów. Zrzuty ekranu rób w **trybie demo**.
Jeśli taka informacja przez pomyłkę trafi do zgłoszenia lub PR-a, edytuj go lub usuń od razu i daj znać
właścicielowi (pamiętaj, że historia edycji może być widoczna). Luki bezpieczeństwa zgłaszaj prywatnie, zgodnie
z `SECURITY.md`.

## Licencja wkładu

Projekt jest udostępniany na licencji **MIT** (plik `LICENSE`). Wysyłając Pull Request, potwierdzasz, że masz prawo
przekazać swój wkład i zgadzasz się, aby został udostępniony na tej samej licencji MIT.

---

## Contributing in English

Domik is an unofficial, educational hobby project (a wall-panel Android app for first-generation Deimic
controllers). Fixes are welcome, but every change must go through a Pull Request into `main` and be reviewed by
the maintainer; direct pushes and force-pushes are blocked. Fork the repo, create a branch, run
`cd android && ./gradlew test :app:testDebugUnitTest`, and open a PR using the template. The user interface is
Polish-first (English is an optional second language), and please do not include any code or assets from the
official Deimic app or its cloud. Never paste your serial number, IP or MAC addresses, room names, or unmasked logs
into issues or PRs, and take screenshots in demo mode. By submitting a PR you agree that your contribution is
licensed under the MIT license.
