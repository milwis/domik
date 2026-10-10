# NOTICE – informacje dodatkowe do licencji

Domik jest udostępniany na licencji **MIT** (plik [`LICENSE`](LICENSE)). Ten plik zawiera informacje, które nie
zmieniają tekstu licencji, a pomagają ją właściwie rozumieć.

## Charakter projektu

Domik to **projekt edukacyjny i hobbystyczny**, dla osób rozwijających zainteresowania smart home na sterownikach
Deimic 1. generacji. Jest **nieoficjalny i niezależny**. **Nie jest alternatywą ani zamiennikiem** oficjalnej
aplikacji producenta sterowników i nie powinien być używany jako jedyny sposób obsługi instalacji ani do funkcji
krytycznych dla bezpieczeństwa. Oprogramowanie jest udostępniane „tak jak jest”, bez gwarancji – zgodnie
z licencją MIT. Pełne [zastrzeżenia](README.md#zastrzeżenia) znajdziesz w pliku `README.md`.

## Znaki towarowe i brak związku z producentem

„Deimic”, „Deimic Master” i inne oznaczenia producenta sterowników są znakami (lub nazwami) należącymi do ich
właścicieli. Występują w tym projekcie wyłącznie opisowo – aby wskazać, z jakimi sterownikami Domik jest zgodny.
Projekt nie ma żadnego związku z producentem sterowników: nie jest przez niego tworzony, autoryzowany, sponsorowany,
zatwierdzony ani wspierany. Licencja MIT dotyczy wyłącznie kodu i materiałów tego projektu i **nie przyznaje żadnych
praw do znaków towarowych**, w tym do nazwy „Domik” i nazw osób trzecich.

„Android”, „Google Play” i „Jetpack Compose” są znakami towarowymi Google LLC. „Kotlin” jest znakiem towarowym
JetBrains s.r.o. Pozostałe nazwy należą do ich właścicieli.

## Zależności i ich licencje

Poniższa lista odpowiada plikom `android/gradle/libs.versions.toml` oraz `android/**/build.gradle.kts`. Każda
biblioteka zachowuje własną licencję i prawa autorskie swoich twórców; dołączane są także zależności tranzytywne
tych bibliotek (głównie AndroidX, również na licencji Apache-2.0).

### Biblioteki wchodzące do aplikacji

| Składnik | Licencja |
|---|---|
| Kotlin (biblioteka standardowa) – JetBrains | Apache-2.0 |
| kotlinx.coroutines (`kotlinx-coroutines-core`) – JetBrains | Apache-2.0 |
| AndroidX Core KTX (`androidx.core:core-ktx`) – Google | Apache-2.0 |
| AndroidX Activity Compose (`androidx.activity:activity-compose`) – Google | Apache-2.0 |
| AndroidX Lifecycle (`lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`) – Google | Apache-2.0 |
| Jetpack Compose (`compose-bom`, `ui`, `ui-graphics`, `ui-tooling-preview`) – Google | Apache-2.0 |
| Material 3 dla Compose (`androidx.compose.material3:material3`) – Google | Apache-2.0 |

Do aplikacji nie są dołączane żadne biblioteki reklamowe, analityczne ani śledzące, a pliki czcionek nie są
dołączane – używana jest czcionka systemowa. Odczyt bazy konfiguracji sterownika (SQLite) i odczyt odpowiedzi JSON
serwisu pogodowego są napisane własnym kodem.

### Narzędzia budowania i testów (nie trafiają do pliku APK)

| Składnik | Licencja |
|---|---|
| Android Gradle Plugin, Kotlin Gradle Plugin i wtyczka kompilatora Compose | Apache-2.0 |
| `androidx.compose.ui:ui-tooling` (tylko wersja debug) | Apache-2.0 |
| `kotlinx-coroutines-test` | Apache-2.0 |
| JUnit (Jupiter, Platform Launcher) | Eclipse Public License 2.0 |

### Ikony

Ikony interfejsu (`android/app/src/main/res/drawable/ic_*.xml`, poza `ic_launcher_foreground.xml`) pochodzą ze zbioru
**Material Icons** firmy Google (warianty Rounded i Outlined), udostępnianego na licencji **Apache-2.0**
(<https://github.com/google/material-design-icons>); w plikach usunięto jedynie atrybut `android:tint`. Pełny tekst
licencji znajduje się w `android/app/src/main/res/raw/notice_material_icons.txt`.

Tekst licencji Apache-2.0: <https://www.apache.org/licenses/LICENSE-2.0>.

## Dane pogodowe

Prognoza pogody i wyszukiwanie miejscowości pochodzą z serwisu **Open-Meteo.com** (<https://open-meteo.com>),
udostępniającego dane na licencji **CC BY 4.0** („Weather data by Open-Meteo.com”). Darmowy dostęp do API jest
przeznaczony do użytku niekomercyjnego. Zapytania do serwisu są wysyłane dopiero wtedy, gdy użytkownik wyszukuje
lub wybierze miejscowość (nigdy w trybie demo).

## Kontakt w sprawie praw

Jeśli jesteś podmiotem praw (np. producentem) i chcesz, żeby coś w tym projekcie zmienić lub usunąć – otwórz Issue
w repozytorium <https://github.com/milwis/domik/issues>.
