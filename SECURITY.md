# Polityka bezpieczeństwa

## Jak zgłosić lukę

Jeśli znalazłeś lukę bezpieczeństwa w Domiku, zgłoś ją **prywatnie** przez GitHub:

1. Wejdź w zakładkę **Security** tego repozytorium.
2. Wybierz **Report a vulnerability** (bezpośredni adres:
   <https://github.com/milwis/domik/security/advisories/new>).
3. Opisz problem i kroki pozwalające go odtworzyć.

**Nie zgłaszaj luk w publicznych Issue ani w Pull Requestach** – dopóki poprawka nie jest gotowa, szczegóły
powinny pozostać prywatne.

## Co jest w zakresie

- Aplikacja Domik (kod w repozytorium) oraz jej komunikacja ze sterownikiem Deimic w sieci lokalnej (LAN),
  w tym przechowywanie ustawień połączenia na tablecie.
- Zgłaszając lukę, korzystaj z **trybu demo** albo własnego sprzętu we własnej sieci. Nie testuj cudzych
  sterowników ani urządzeń.

Poza zakresem są sam sterownik Deimic, jego oprogramowanie, protokół i chmura producenta. Takie sprawy
zgłaszaj producentowi.

## Czego nie wklejać

W zgłoszeniu (także prywatnym) nie podawaj numeru seryjnego sterownika, adresu IP, adresu MAC ani haseł
i kluczy z własnej instalacji. Jeśli potrzebny jest przykład, zamaskuj te dane. Nie dołączaj logów
z niezamaskowanymi danymi.

## Czego możesz się spodziewać

Domik to projekt hobbystyczny, utrzymywany w wolnym czasie. **Nie ma umowy poziomu usług (SLA)** ani gwarancji
terminu odpowiedzi czy poprawki: działam w trybie *best effort*. Poprawki trafiają do najnowszej wersji
aplikacji; starsze wersje nie są wspierane.
