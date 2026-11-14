# Przystanek ZTM – widget tablicy przystankowej

Widget 4×2 na Androida, który wygląda jak tablica SDIP na przystanku ZTM (GZM): świecące diody LED na czarnym tle.
Pokazuje odjazdy na żywo: linia, kierunek, czas. Pojazd na przystanku to migające `>>>`.

## Instalacja
Najnowsze APK: **Releases → ZTMWidget.apk**.

## Jak używać
1. Dodaj widget „Tablica przystankowa” na ekranie głównym.
2. Wpisz nazwę przystanku, np. „Rondo”, i wybierz ją z listy. Mapka pokaże wszystkie słupki w okolicy:
   🚋 tramwaj, 🚌 autobus. Zielona obwódka oznacza wybraną nazwę.
3. Dotknij ikonki, a potem **👁 Obserwuj**. Widget jest gotowy.

Można mieć kilka widgetów z różnymi przystankami. Żeby zmienić przystanek: przytrzymaj widget i wybierz „Ustawienia/Zmień”, albo otwórz aplikację.

- **Dotknięcie widgetu** odświeża dane od razu.
- **Odświeżanie:** zegar i miganie co 1 s, odjazdy co 20 s. Pobiera tylko przy włączonym ekranie.
- **Ustawienia (⚙):**
  - **styl tablicy:** `REAL (LED)` (oryginalna matryca diodowa SDIP o zwiększonym kontraście) lub `MODERN (TXT)` (ostry, ultra-czytelny tekst wektorowy na czarnym tle),
  - kolor diod / akcentu: bursztynowy, czerwony, zielony, różowy, niebieski,
  - jasność,
  - wyłącznik monitorowania,
  - „tylko pon–pt”,
  - godziny działania,
  - informacja o wersji i dacie wydania.
- **Poza harmonogramem** tablica pokazuje „MONITOROWANIE WSTRZYMANE” i nic nie pobiera. Dotknięcie włącza ją na 5 minut.

## Optymalizacja baterii i audyt
Szczegółowy opis architektury, optymalizacji baterii (redukcja 95% draw calls, GZIP, natychmiastowe budzenie ekranem) oraz trybów wyświetlania znajduje się w pliku [AUDIT_OPTIMIZATION.md](AUDIT_OPTIMIZATION.md).

## Dane
Nieoficjalne API systemu SDIP: `sdip.transportgzm.pl`. Lista słupków jest pobierana raz na tydzień, odjazdy co 20 s.
Mapa: © OpenStreetMap. Font LED: Micro5 (SIL Open Font License).
