# Implementierungsplan: Phase F - CSV-Encoding-/BOM-Konsolidierung

> **Milestone**: 0.8.0 - Internationalisierung
> **Phase**: F (CSV-Encoding-/BOM-Konsolidierung)
> **Status**: Implemented (2026-04-16)
> **Referenz**: `docs/planning/implementation-plan-0.8.0.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 6 Phase F, Abschnitt 7, Abschnitt 8, Abschnitt 9;
> `docs/planning/ImpPlan-0.8.0-A.md`; `docs/planning/ImpPlan-0.8.0-B.md`;
> `docs/planning/roadmap.md` Milestone 0.8.0; `spec/design.md` Abschnitt 6.2,
> Abschnitt 6.3, Abschnitt 9.4; `spec/cli-spec.md` (`data export`,
> `data import`); `docs/user/guide.md`; `docs/planning/implementation-plan-0.4.0.md`
> Abschnitt 6.9; `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/EncodingDetector.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkReader.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/json/JsonChunkReader.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/yaml/YamlChunkReader.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkWriter.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/format/data/ExportOptions.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/ImportOptions.kt`

---

## 1. Ziel

Phase F bettet den bereits seit 0.4.0 vorhandenen BOM-/Encoding-Unterbau
sauber in den 0.8.0-I18n-Vertrag ein. Ergebnis der Phase ist kein neues
Encoding-Feature, sondern ein konsolidierter, testbarer und dokumentierter
Vertrag dafuer, wie CSV-Export sowie die importseitig von CSV, JSON und YAML
geteilte UTF-/BOM-Behandlung fachlich zu lesen sind.

Der Teilplan liefert bewusst keinen universellen Charset-Detector und keine
neue Heuristik fuer legacy Encodings. Er zieht die bereits getroffenen 0.4.0-
Entscheidungen in die 0.8.0-Dokumentations- und Testbasis nach.

Nach Phase F soll klar und testbar gelten:

- BOM-/Encoding-Unterstuetzung ist in 0.8.0 eine Konsolidierung des
  vorhandenen 0.4.0-Unterbaus, kein neu erfundenes Feature
- BOM-Auto-Detection bleibt strikt auf UTF-8 sowie UTF-16 BE/LE begrenzt
- es gibt keine heuristische Erkennung anderer Encodings
- `--encoding auto` und explizites `--encoding <charset>` bleiben sauber
  getrennte Pfade
- das CSV-Writer-Verhalten von `--csv-bom` ist explizit dokumentiert und
  stimmt mit Code und Tests ueberein
- UTF-8/UTF-16- und BOM-Pfade sind mit nicht-lateinischen Inhalten abgesichert

---

## 2. Ausgangslage

### 2.1 Stand der Codebasis **vor** Phase F

- der zentrale BOM-/Encoding-Unterbau existiert bereits seit 0.4.0 in
  `EncodingDetector`
- `EncodingDetector.detectOrFallback(...)` bildet den `--encoding auto`-Pfad:
  - erkennt UTF-8 BOM
  - erkennt UTF-16 BE BOM
  - erkennt UTF-16 LE BOM
  - lehnt UTF-32 BOM explizit ab
  - faellt ohne BOM auf UTF-8 zurueck
- `EncodingDetector.wrapWithExplicit(...)` bildet den expliziten
  `--encoding <charset>`-Pfad:
  - keine Auto-Detection
  - passendes BOM wird konsumiert
  - nicht passendes BOM bleibt im Stream stehen
- der gemeinsame Reader-Unterbau wird nicht nur von CSV, sondern auch von JSON
  und YAML genutzt:
  - `CsvChunkReader` nutzt `EncodingDetector`
  - `JsonChunkReader` nutzt denselben Detector vor dem UTF-8-Transcoding fuer
    DSL-JSON
  - `YamlChunkReader` nutzt denselben Detector vor dem UTF-8-Transcoding
- im Export-Pfad existiert ein eigenes CSV-BOM-Verhalten:
  - `DataExportCommand` exponiert `--csv-bom`
  - `ExportOptions.csvBom` transportiert das Flag
  - `CsvChunkWriter` schreibt vor dem ersten Byte BOM-Bytes fuer UTF-8,
    UTF-16 BE und UTF-16 LE

Bestehende Testabdeckung vor der Phase:

- `EncodingDetectorTest` deckt UTF-8-, UTF-16- und UTF-32-Faelle ab
- `CsvChunkReaderTest` deckt UTF-8 BOM und explizites ISO-8859-1 ab
- `JsonChunkReaderTest` deckt UTF-8 BOM und UTF-16 LE bereits ab
- `YamlChunkReaderTest` deckt UTF-8 BOM ab
- `CsvChunkWriterTest` deckt `--csv-bom` vor Phase F nur fuer UTF-8 ab

Relevante Doku-Ist-Lage vor der Phase:

- `docs/planning/implementation-plan-0.8.0.md` beschreibt Phase F explizit als
  Konsolidierung vorhandener 0.4.0-Funktionalitaet
- `spec/design.md` beschreibt Textformate mit UTF-8 als Standard und
  automatischer Erkennung fuer UTF-16-/BOM-markierte Dateien
- `spec/cli-spec.md` und `docs/user/guide.md` beschreiben `--csv-bom` vor Phase F
  als reines UTF-8-BOM-Flag

Konkrete Luecken vor Phase F:

- die 0.8.0-Doku beschreibt die 0.4.0-Encoding-Regeln noch nicht ueberall
  gleich praezise
- die importseitige BOM-Detection ist vorhanden, aber als Teil des
  I18n-Vertrags noch nicht konsolidiert beschrieben
- beim Export gibt es eine Spannung zwischen Doku und Code:
  - Doku beschreibt `--csv-bom` als UTF-8-BOM
  - `CsvChunkWriter` schreibt BOMs passend zu UTF-8 und UTF-16
- die vorhandene Testbasis deckt BOM-/Encoding-Pfade bereits gut ab, aber
  nicht durchgaengig mit nicht-lateinischen Payloads

### 2.2 Stand der Codebasis **nach** Phase F

Mit Abschluss der Phase (Status „Implemented", 2026-04-16) gilt:

- Code-Vertrag fuer `--csv-bom` ist explizit festgezogen (D1): BOM passend
  zum `--encoding` — UTF-8 (`EF BB BF`), UTF-16 BE (`FE FF`), UTF-16 LE
  (`FF FE`); Non-UTF-Encodings sind No-op. `CsvChunkWriter.writeBomBytes()`
  dokumentiert dies inline; `ExportOptions.csvBom`-KDoc verweist auf D1.
- CLI-Help fuer `--csv-bom` im `DataExportCommand` spiegelt denselben Vertrag.
- `EncodingDetector` bleibt unangetastet und ist weiterhin die geteilte
  Import-BOM-Quelle fuer CSV, JSON und YAML.
- Tests decken den Vertrag und Unicode-Stabilitaet ab:
  - `CsvChunkWriterTest`: UTF-8/UTF-16 BE/UTF-16 LE BOM + ISO-8859-1 No-op
    mit kyrillischen, CJK- und Emoji-Inhalten
  - `EncodingDetectorTest`: UTF-8/UTF-16 BE/UTF-16 LE BOM mit
    nicht-lateinischen Payloads plus expliziter UTF-16-BE-Pfad
  - `CsvChunkReaderTest`: UTF-8 BOM mit kyrillisch/CJK/Emoji, UTF-16 BE
    und UTF-16 LE BOM mit Unicode
  - `JsonChunkReaderTest`: UTF-8 BOM + Unicode, UTF-16 BE BOM + Unicode
  - `YamlChunkReaderTest`: UTF-8 BOM + Unicode, UTF-16 LE BOM + Unicode
- Doku gleicht den Vertrag konsistent ab:
  - `spec/cli-spec.md`: Export-`--csv-bom` und Import-`--encoding`
  - `docs/user/guide.md`: Option-Tabellen fuer Export und Import
  - `spec/design.md` §6.2, §6.3 und §9.3
  - `docs/planning/implementation-plan-0.8.0.md` Phase-F-Abschnitt dokumentiert D1
  - `docs/planning/roadmap.md` Phase F ✅

---

## 3. Scope und Nicht-Ziele

In Scope fuer Phase F:

- explizite Einordnung des 0.4.0-Encoding-/BOM-Unterbaus in den 0.8.0-Vertrag
- Dokumentationskonsolidierung fuer den importseitig geteilten BOM-/Encoding-
  Unterbau sowie fuer CSV-Export und BOM-Verhalten
- unangetastete Wiederverwendung von `EncodingDetector`
- explizite Klaerung des `--csv-bom`-Vertrags
- gezielte Tests fuer UTF-8/UTF-16-/BOM-Pfade mit nicht-lateinischen Inhalten
- klare Dokumentation, dass BOM-Auto-Detection nur fuer UTF-8/UTF-16 gilt

Bewusst nicht in Scope:

- neuer universeller Charset-Detector
- Heuristiken fuer BOM-lose Non-UTF-Dateien
- neue Encoding-Konfigurationsquellen in der CLI
- Umbau der Reader-/Writer-Architektur
- neue CSV-Features jenseits des bestehenden `--csv-bom`- und `--encoding`-
  Vertrags

---

## 4. Verbindliche Leitlinien

### 4.1 0.8.0 wiederverwendet den 0.4.0-Unterbau statt ihn neu zu entwerfen

Phase F ist eine Konsolidierungsphase, keine Neuimplementierung.

Verbindliche Folge:

- `EncodingDetector` bleibt die produktive Quelle fuer BOM-basierte
  Auto-Detection
- die 0.4.0-Regeln werden in 0.8.0 uebernommen statt neu formuliert
- bestehende Import-Pfade werden nur dann geaendert, wenn ein echter
  Widerspruch zu Doku oder Tests besteht

### 4.2 BOM-Auto-Detection bleibt eng begrenzt

Die Unterstuetzung ist bewusst schmal und regelbasiert.

Verbindliche Folge:

- Auto-Detection gilt nur fuer UTF-8, UTF-16 BE und UTF-16 LE
- UTF-32 BOM bleibt ein expliziter Fehlerpfad
- andere Encodings werden nicht heuristisch erkannt
- "Encoding-Unterstuetzung" darf in der Doku nicht als universelle
  Encoding-Erkennung missverstanden werden

### 4.3 Explizites `--encoding` ueberschreibt keine Mismatch-Realitaet

Der explizite Encoding-Pfad bleibt autoritativ.

Verbindliche Folge:

- bei `--encoding auto` darf BOM-Sniffing laufen
- bei explizitem `--encoding <charset>` laeuft keine Auto-Detection
- ein nicht passendes BOM wird nicht still konsumiert oder uminterpretiert
- Doku und Tests muessen diesen Unterschied klar transportieren

### 4.4 `--csv-bom` braucht einen eindeutigen, gespiegelt dokumentierten Vertrag

Phase F muss die bestehende Unklarheit zwischen Doku und Implementierung
beenden.

Verbindliche Folge:

- fuer `--csv-bom` gibt es am Ende genau einen dokumentierten Vertrag
- `DataExportCommand`-Help, `cli-spec`, `guide`, `ExportOptions`,
  `CsvChunkWriter` und Tests sprechen denselben Wortlaut
- es bleibt nicht bei einem impliziten "Code macht etwas, Doku sagt etwas
  anderes"

### 4.5 Nicht-lateinische Inhalte sind Teil des Vertrags, nicht nur ASCII

Phase F gehoert in den Internationalisierungs-Milestone und darf deshalb nicht
nur ASCII-Payloads absichern.

Verbindliche Folge:

- UTF-8- und UTF-16-Pfade werden mit nicht-lateinischen Inhalten getestet
- BOM- und Encoding-Tests pruefen reale Unicode-Payloads statt nur `hello`
  oder `hi`
- CSV-Import und CSV-Export muessen dabei dieselbe Zeichenstabilitaet zeigen,
  die 0.8.0 fuer den I18n-Unterbau beansprucht

---

## 5. Arbeitspakete

### F.1 0.4.0-Encoding-Vertrag in 0.8.0-Doku uebernehmen

Phase F soll die bestehende Semantik in die aktuelle Dokumentationslandschaft
ziehen.

Ziel:

- `spec/cli-spec.md`, `spec/design.md` und `docs/user/guide.md` angleichen
- klar festhalten:
  - Auto-Detection nur fuer UTF-8/UTF-16 mit BOM
  - kein Heuristik-Pfad fuer Non-BOM-/Non-UTF-Dateien
  - UTF-32 BOM ist nicht supported
- Sprachgebrauch auf "Encoding-Unterstuetzung" statt "beliebige Detection"
  schaerfen, wo noetig

### F.2 Import-BOM-Detection explizit als wiederverwendeter Unterbau festziehen

Die Reader-Schicht soll lesbar als reuse statt als neue 0.8.0-Logik
erkennbar sein.

Ziel:

- `EncodingDetector` unangetastet weiterverwenden
- Reader-Doku und ggf. Kommentare angleichen, wo Phase F den Vertrag sichtbarer
  machen soll
- ausdruecklich festhalten, dass derselbe Unterbau CSV, JSON und YAML
  importseitig bedient, auch wenn der Phase-Name CSV/BOM betont

### F.3 `--csv-bom`-Vertrag konsolidieren

Hier liegt die wichtigste inhaltliche Klaerung der Phase.

Ziel:

- die aktuelle Abweichung zwischen Doku und Implementierung explizit
  aufloesen
- entscheiden und dann durchziehen, ob `--csv-bom` fuer 0.8.0:
  - nur UTF-8 meint
  - oder allgemein "zum ausgewaehlten UTF-Encoding passendes BOM" meint
- nach der Entscheidung:
  - CLI-Help angleichen
  - `cli-spec` angleichen
  - `guide` angleichen
  - `CsvChunkWriterTest` passend erweitern
  - ggf. Implementierung von `CsvChunkWriter.writeBomBytes()` angleichen

### F.4 Nicht-lateinische BOM-/Encoding-Tests ergaenzen

Die vorhandene Testbasis ist gut, aber noch nicht internationalisierungsnah
genug.

Ziel:

- `EncodingDetectorTest` mit Unicode-Payloads absichern
- `CsvChunkReaderTest` fuer UTF-8 BOM und UTF-16 mit nicht-lateinischen Daten
  erweitern
- `JsonChunkReaderTest` fuer Unicode-BOM-/UTF-16-Pfade mitziehen, weil der
  Detector auch dort produktiv verwendet wird
- `YamlChunkReaderTest` mindestens fuer einen Unicode-BOM-Pfad mitziehen, weil
  derselbe Unterbau genutzt wird
- `CsvChunkWriterTest` fuer den finalen `--csv-bom`-Vertrag und Unicode-
  Inhalte absichern

### F.5 Nutzervertrag fuer Defaults und Grenzen klarziehen

Phase F soll fuer Anwender nicht nur Implementierungsdetails, sondern die
Nutzersicht konsolidieren.

Ziel:

- Export:
  - `--encoding` default `utf-8`
  - `--csv-bom` default aus
- Import:
  - `--encoding` default `auto`
  - Auto-Pfad bedeutet BOM-Sniff + UTF-8-Fallback
- klar dokumentieren, wann Nutzer fuer ISO-8859-1/Windows-1252/andere
  Encodings explizit `--encoding` setzen muessen

---

## 6. Teststrategie

Phase F baut auf der vorhandenen 0.4.0-Testbasis auf und schliesst gezielt
die I18n-relevanten Luecken.

Pflichtfaelle:

- `EncodingDetectorTest`:
  - UTF-8 BOM mit nicht-lateinischem Payload
  - UTF-16 BE/LE BOM mit nicht-lateinischem Payload
  - UTF-32 BOM bleibt klarer Fehlerpfad
  - explizites `--encoding` mit BOM-Mismatch laesst Bytes stehen
- `CsvChunkReaderTest`:
  - UTF-8 BOM mit Unicode-CSV-Inhalt
  - UTF-16 Input mit BOM und Unicode-Inhalt
  - explizites Non-UTF-Encoding bleibt ueber `ImportOptions.encoding`
    moeglich
- `JsonChunkReaderTest`:
  - UTF-8 BOM mit Unicode-JSON-Inhalt
  - UTF-16 Input mit BOM und Unicode-Inhalt
  - der UTF-8-Transcoding-Pfad bleibt fuer den geteilten Detector explizit
    abgesichert
- `YamlChunkReaderTest`:
  - mindestens ein Unicode-BOM-Fall, weil der geteilte Unterbau sonst
    nicht voll abgesichert ist
- `CsvChunkWriterTest`:
  - finaler `--csv-bom`-Vertrag fuer die unterstuetzten Encodings
  - Unicode-Inhalte bleiben byte- und zeichenstabil
- CLI-/Runner-Ebene bei Bedarf:
  - Fehlermeldungen fuer unbekannte Encodings bleiben klar
  - Help-/Spec-Wortlaut zu `--csv-bom` passt zur finalen Entscheidung

Zusatznutzen:

- Phase F sollte moeglichst keine grossen neuen E2E-Suiten aufziehen
- der Fokus liegt auf deterministischen Reader-/Writer- und CLI-Vertrags-
  Tests, nicht auf Infrastruktur

---

## 7. Datei- und Codebasis-Betroffenheit

Voraussichtlich betroffen:

- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/EncodingDetector.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkReader.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/json/JsonChunkReader.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/yaml/YamlChunkReader.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkWriter.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/format/data/ExportOptions.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/ImportOptions.kt`
- `spec/cli-spec.md`
- `spec/design.md`
- `docs/user/guide.md`

Neue oder angepasste Tests voraussichtlich in:

- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/EncodingDetectorTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/csv/CsvChunkReaderTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/json/JsonChunkReaderTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/yaml/YamlChunkReaderTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/csv/CsvChunkWriterTest.kt`
- ggf. `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`

---

## 8. Risiken und offene Punkte

### R1 - "Encoding-Unterstuetzung" wird als Heuristik fehlverstanden

Wenn die Doku unpraezise bleibt, erwarten Nutzer automatisch Erkennung fuer
Latin-1, Windows-1252 oder sonstige Non-UTF-Dateien.

Mitigation:

- 0.4.0-Wortlaut uebernehmen
- Detection strikt als BOM-basierte UTF-Erkennung beschreiben

### R2 - `--csv-bom` bleibt zwischen Doku und Code inkonsistent

Das waere fuer einen Konsolidierungs-Meilenstein genau die falsche Richtung.

Mitigation:

- eine explizite Produktentscheidung treffen
- Doku, Help, Tests und Implementierung in derselben Phase synchronisieren

### R3 - ASCII-lastige Tests verdecken echte I18n-Probleme

Mit rein englischen Payloads bleiben Fehler bei Unicode-Bytes oder
Transcoding-Stellen leicht unsichtbar.

Mitigation:

- Unicode-Payloads in Reader-/Writer-Tests ergaenzen
- UTF-8- und UTF-16-Pfade explizit mit nicht-lateinischen Zeichen fahren

### Entscheidung D1 (ehem. Offene Frage O1)

`--csv-bom` schreibt in 0.8.0 offiziell das BOM passend zum
`--encoding` — also `EF BB BF` fuer UTF-8, `FE FF` fuer UTF-16 BE und
`FF FE` fuer UTF-16 LE. Fuer Non-UTF-Encodings (ISO-8859-1,
Windows-1252, ...) ist das Flag ein No-op, weil ISO-8859 kein
definiertes BOM hat.

Begruendung:

- spiegelt den produktiven `CsvChunkWriter.writeBomBytes()`-Code
  zurueck in Doku, CLI-Help und Tests — keine stille Abweichung mehr
- symmetrisch zum Importpfad (`EncodingDetector` erkennt UTF-8 und
  UTF-16 BE/LE)
- Nutzer, die Windows-1252 exportieren, bekommen eine ehrliche
  BOM-freie Datei — kein UTF-8-BOM vor Non-UTF-Inhalt

Umsetzung in Code und Doku:

- `DataExportCommand.csvBom`-Help: "Prefix CSV output with a BOM
  matching --encoding (UTF-8, UTF-16 BE/LE); no-op for other
  encodings"
- `spec/cli-spec.md`, `docs/user/guide.md`, `spec/design.md` §6.2/§9.3
  angeglichen
- `ExportOptions.csvBom`-KDoc zieht D1 explizit nach
- `CsvChunkWriterTest` deckt alle drei UTF-BOMs und den Non-UTF-No-op
  mit Unicode-Inhalten ab

### Offene Frage O2

Wie weit soll Phase F JSON-/YAML-seitige Testangleiche mitziehen, obwohl der
Milestone-Titel CSV/BOM betont?

Empfehlung:

- nur so weit wie noetig
- mindestens den geteilten `EncodingDetector`-Vertrag auch ueber einen
  JSON- und einen YAML-Pfad mit absichern

---

## 9. Entscheidungsempfehlung

Empfohlen wird fuer 0.8.0 eine bewusst konservative BOM-/Encoding-Policy mit
folgendem Vertrag:

- der 0.4.0-Unterbau bleibt die Grundlage
- BOM-Auto-Detection gilt nur fuer UTF-8 und UTF-16 BE/LE
- es gibt keine Heuristik fuer andere Encodings
- explizites `--encoding` bleibt autoritativ und konsumiert nur passende BOMs
- derselbe Detector-Vertrag gilt importseitig konsistent fuer CSV, JSON und
  YAML
- `--csv-bom` wird in Code, Tests und Doku auf genau einen klaren Vertrag
  festgezogen
- Unicode- und nicht-lateinische Payloads werden in den relevanten Tests
  explizit abgesichert

Damit macht Phase F aus bestehender Funktionalitaet einen belastbaren
0.8.0-Bestandteil: keine neue Encoding-Magie, sondern ein sauber
eingebetteter, klar dokumentierter und internationalisierungsfaehig
abgesicherter CSV-/BOM-Vertrag.
