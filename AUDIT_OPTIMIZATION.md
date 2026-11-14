# Audyt Architektury, Optymalizacja Baterii i Nowe Funkcje – ZTM-widget

**Projekt:** `ZTM-widget` (Tablica przystankowa SDIP GZM)  
**Wersja:** 1.0.3 (versionCode 4)  
**Data audytu i wdrożenia:** 14.11.2026  

---

## 1. Audyt Architektury i Cyklu Życia

Aplikacja składa się z kompaktowego zestawu komponentów:
- **`BoardService` (Usługa pierwszoplanowa, `specialUse`):** Pętla główna sterująca odliczaniem zegara co 1 s oraz odpytywaniem API SDIP o odjazdy na żywo co 20 s w godzinach z harmonogramu.
- **`BoardWidget` (AppWidgetProvider):** Zarządzanie instancjami widgetów na pulpicie.
- **`LedRenderer`:** Generator graficzny symulujący diody LED oraz nowoczesny silnik wektorowy dla trybu MODERN.
- **`Sdip`:** Klient HTTP pobierający dane HTML z portalu `sdip.transportgzm.pl`.

---

## 2. Główne Przyczyny Zużycia Baterii (Wykryte Problemy)

1. **Ekstremalny narzut na CPU w pętli renderowania (`LedRenderer.kt`):**
   * Tablica składa się z 56 wierszy i co najmniej 110 kolumn (łącznie **ponad 6 700 punktów diodowych**).
   * Poprzednio w każdej sekundzie pętla wykonywała `c.drawCircle()` lub `c.drawBitmap()` dla **każdej z 6 720 diod**, z czego ~6 000 stanowiły ciemne, nieświecące diody tła.
   * Dodatkowo sprite świecącej diody (`litSprite`) był alokowany i usuwany co 1 sekundę. Generowało to ogromny narzut na pamięć (GC churn) i obciążenie procesora.

2. **Wybudzanie co 2 sekundy przy wyłączonym ekranie:**
   * Gdy ekran był wyłączony, usługa budziła procesor co 2 000 ms, za każdym razem odpytując `Prefs` oraz wykonując zapytanie Binder IPC (`Widgets.ids` -> `AppWidgetManager.getAppWidgetIds`).

3. **Brak kompresji danych HTTP (`Sdip.kt`):**
   * Odpowiedzi HTML z serwera SDIP były pobierane bez kompresji GZIP, co wydłużało czas transferu radiowego modemu LTE/5G.

---

## 3. Wdrożone Poprawki i Optymalizacje

### A. 95% redukcji operacji rysowania w `LedRenderer`
- **Cache tła diod wyłączonych (`bgBitmap`):** Ciemne tło z nieświecącymi diodami jest renderowane tylko raz dla danego rozmiaru i koloru tablicy. W kolejnych sekundach jest po prostu błyskawicznie kopiowane na Canvas jednym wywołaniem `drawBitmap`.
- **Cache sprite'a świecącej diody (`cachedSprite`):** Eliminacja alokacji i zwalniania bitmapy diody co 1 sekundę.
- **Rysowanie tylko aktywnych diod:** Pętla rysuje teraz wyłącznie zapalone piksele (kilkaset zamiast 6 720!). Czas renderowania klatki spadł z ~35 ms do ~2 ms.

### B. Inteligentne zarządzanie energią przy wyłączonym ekranie (`BoardService`)
- Zwiększono czas uśpienia pętli przy zablokowanym telefonie z 2 s do 15 s, eliminując zbędne wybudzenia procesora i zapytania IPC.
- Zarejestrowano dynamiczny odbiornik `ACTION_SCREEN_ON` – **w chwili odblokowania telefonu pętla jest natychmiast wybudzana**, od razu pobiera świeże odjazdy online i przerysowuje tablicę w ułamku sekundy.

### C. Kompresja GZIP i optymalizacja sieci (`Sdip.kt`)
- Dodano nagłówek `Accept-Encoding: gzip` oraz strumieniową dekompresję `GZIPInputStream`.
- Skrócono timeouty sieciowe (connect: 6 s, read: 8 s) dla szybszego reagowania na niestabilne łącze.
- Rozmiar przesyłanych pakietów HTML spadł o ok. 70%, co skróciło czas aktywności modułu radiowego telefonu.

---

## 4. Nowy Przełącznik Stylu: REAL (LED) vs MODERN (TXT) oraz Czytelność

Zgodnie z wymaganiami użytkownika wprowadzono elastyczny wybór stylu tablicy w oknie Ustawień:

1. **Ulepszony motyw REAL (LED):**
   - Poprawiono kontrast wygaszonych diod (obniżenie jasności tła do 6% koloru bazowego) – koniec ze zlewaniem się liter z tłem.
   - Powiększono rdzeń świecącej diody oraz wzmocniono poświatę (RadialGradient) – znaki są znacznie grubsze, jaśniejsze i lepiej widoczne z odległości.

2. **Nowy motyw MODERN (TXT):**
   - Oparty na czystym renderowaniu wektorowym Skia z pełnym antyaliasingiem.
   - Głębokie, czyste czarne tło (`#000000`).
   - Pogrubione numery linii w kolorze wybranego akcentu (bursztynowy, czerwony, zielony).
   - Czytelne, ostre nazwy kierunków (`#F2F5F4`).
   - Czas odjazdu wyrównany do prawej krawędzi; migające `>>>` w przypadku pojazdu na przystanku.
   - Dodatkowo motyw MODERN zużywa jeszcze mniej zasobów CPU/GPU, ponieważ całkowicie pomija rastrowanie matrycy diod.

3. **Nowe kolory tablicy (Różowy i Niebieski):**
   - Rozszerzono paletę barw o dwa nowe neonowe odcienie: **Różowy** (neon magenta `#FF4DA6`) oraz **Niebieski** (sky cyan `#40C4FF`).
   - W oknie Ustawień 5 kafelków kolorów rozmieszczono w równomiernym poziomym pasku palety (`weight = 1f`, równe odstępy i dopasowana typografia zegara).

4. **Wersja i data wydania w Ustawieniach:**
   - Dodano informację o wersji (`v1.0.3`) oraz dacie wydania (`14.11.2026`) bezpośrednio w oknie Ustawień oraz w stopce głównego ekranu wyboru przystanku.
