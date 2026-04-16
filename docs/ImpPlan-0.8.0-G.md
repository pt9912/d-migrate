# Implementierungsplan: Phase G - Tests und Dokumentation

> **Milestone**: 0.8.0 - Internationalisierung
> **Phase**: G (Tests und Dokumentation)
> **Status**: Implemented (2026-04-16)
> **Referenz**: `docs/implementation-plan-0.8.0.md` Abschnitt 2,
> Abschnitt 6 Phase G, Abschnitt 7, Abschnitt 8, Abschnitt 9;
> `docs/ImpPlan-0.8.0-A.md`; `docs/ImpPlan-0.8.0-B.md`;
> `docs/ImpPlan-0.8.0-C.md`; `docs/ImpPlan-0.8.0-D.md`;
> `docs/ImpPlan-0.8.0-E.md`; `docs/ImpPlan-0.8.0-F.md`;
> `docs/cli-spec.md`; `docs/design.md`;
> `docs/connection-config-spec.md`; `docs/guide.md`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/config/I18nSettingsResolverTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliI18nContextTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/output/MessageResolverTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/output/BundleCompletenessTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/output/OutputFormatterTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/output/ProgressRendererTest.kt`;
> `hexagon/application/src/test/kotlin/dev/dmigrate/cli/i18n/UnicodeNormalizerTest.kt`;
> `hexagon/application/src/test/kotlin/dev/dmigrate/cli/i18n/GraphemeCounterTest.kt`;
> `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataExportHelpersTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/ValueSerializerTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/ValueDeserializerTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/EncodingDetectorTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/csv/CsvChunkReaderTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/csv/CsvChunkWriterTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/json/JsonChunkReaderTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/yaml/YamlChunkReaderTest.kt`

---

## 1. Ziel

Phase G zieht die zuvor produktivierten 0.8.0-Bausteine in eine belastbare
Abschlussbasis aus Tests und konsistenter Dokumentation. Ergebnis der Phase ist
kein neues Feature, sondern ein geschlossener Nachweis dafuer, dass Locale-,
ResourceBundle-, Unicode-, Zeit- und BOM-Vertraege nicht nur implementiert,
sondern auch stabil beschrieben und regressionssicher getestet sind.

Der Teilplan liefert bewusst keine weiteren Fachfeatures. Er schliesst den
Milestone ab, indem er die in Phase B bis F eingefuehrten Regeln in
deterministische Tests und denselben Wortlaut in der Nutzerdokumentation
ueberfuehrt.

Nach Phase G soll klar und testbar gelten:

- die 0.8.0-I18n-Runtime ist ueber Resolver-, Kontext- und CLI-Tests
  abgesichert
- ResourceBundles, Formatter und Progress-Renderer verhalten sich fuer Englisch
  und Deutsch reproduzierbar
- Unicode-Normalisierung und Grapheme-Counting sind fachlich getrennt von
  Nutzdatenpayloads getestet
- die Zeitzonen-/Temporal-Policy aus Phase E ist fuer Serializer,
  Deserializer und `--since` regressionsfest abgedeckt
- die BOM-/Encoding-Regeln aus Phase F sind in Code, Tests und Doku
  deckungsgleich
- `cli-spec`, `design` und `connection-config-spec` sprechen denselben
  0.8.0-Vertrag; `guide` braucht fuer `--lang` noch explizite Nachschaerfung

---

## 2. Ausgangslage

### 2.1 Stand der Codebasis vor Phase G

Vor dem Abschluss der Phase war der technische Unterbau aus Phase B bis F
bereits weitgehend vorhanden:

- I18n-Settings-Resolution, CLI-Kontext und ResourceBundle-Ausgabe existierten
- ICU4J-basierte Utilities fuer Normalisierung und Grapheme-Counting waren
  produktiv
- die Temporal-Policy fuer `LocalDateTime`, `OffsetDateTime` und `--since`
  war implementiert
- der 0.4.0-Encoding-/BOM-Unterbau war in 0.8.0 inhaltlich wiederverwendbar

Offen war vor allem der letzte Milestone-Schritt:

- Mindest-Testmatrix fuer die verschiedenen Teilvertraege vollstaendig
  schliessen
- Doku-Dateien auf denselben finalen Wortlaut angleichen
- 0.8.0- und 0.9.0-Grenzen, besonders bei `--lang`, explizit konsistent
  festhalten

### 2.2 Stand der Codebasis nach Phase G

Mit Abschluss der Phase (Status „Implemented", 2026-04-16) gilt:

- Locale-/Timezone-/Normalization-Resolution ist durch
  `I18nSettingsResolverTest` und `CliI18nContextTest` abgedeckt
- der 0.8.0-Vertrag fuer `--lang` ist explizit festgezogen:
  - `D_MIGRATE_LANG`, `LC_ALL`, `LANG`, Config und System-Locale sind Teil der
    technischen Runtime-Basis
  - der finale CLI-Override-Vertrag fuer `--lang` bleibt 0.9.0 vorbehalten
  - die Root-CLI lehnt `--lang` in 0.8.0 bewusst mit lokalem Fehlerpfad ab
- `MessageResolverTest` und `BundleCompletenessTest` decken Bundle-Loading,
  Unsupported-Locale-Fallback, Missing-Key-zu-Key-String und Nicht-Leerheit ab
- der expliziter Nachweis fuer Root-Fallback bei einem nur im DE-Bundle
  fehlenden Key ist jetzt ebenfalls abgedeckt: `MessageResolverTest` enthaelt
  ein dediziertes Test-Bundle-Paar
  (`test-messages-phase-g/phasegmsg[_de].properties`), in dem `root.only.key`
  bewusst nur im Root-Bundle steht und unter `Locale.GERMAN` per
  `ResourceBundle`-Parent-Chain aufloesbar bleibt
- `OutputFormatterTest` und `ProgressRendererTest` decken englische und
  deutsche Ausgabe sowie die Sprachstabilitaet strukturierter JSON-/YAML-
  Ausgaben ab
- `UnicodeNormalizerTest` und `GraphemeCounterTest` decken kombinierte Zeichen,
  Emoji, ZWJ-Sequenzen und nicht-lateinische Inhalte ab, ohne Nutzdaten als
  still normalisiert zu behandeln
- die Temporal-Policy ist in `DataExportHelpersTest`, `ValueSerializerTest`
  und `ValueDeserializerTest` fuer lokale und offsethaltige Date/Time-Pfade
  abgesichert
- die BOM-/Encoding-Regeln aus Phase F sind ueber `EncodingDetectorTest`,
  `CsvChunkReaderTest`, `JsonChunkReaderTest`, `YamlChunkReaderTest` und
  `CsvChunkWriterTest` inklusive Unicode-Payloads abgedeckt
- `docs/cli-spec.md`, `docs/design.md` und `docs/connection-config-spec.md`
  spiegeln den 0.8.0-Wortlaut
- `docs/guide.md` ist fuer `--lang` auf den getesteten 0.8.0-Vertrag
  zurueckgezogen: der Eintrag verweist ausdruecklich darauf, dass `--lang`
  in 0.8.0 mit Exit 7 abgelehnt wird und die Sprachauflösung ueber
  `D_MIGRATE_LANG`, `LC_ALL`/`LANG` oder `i18n.default_locale` laeuft

---

## 3. Scope und Nicht-Ziele

In Scope fuer Phase G:

- Mindest-Testmatrix aus dem Masterplan fuer 0.8.0 schliessen
- Doku-Dateien auf denselben finalen Produktwortlaut angleichen
- Teilvertraege aus Phase B bis F als Regressionstests festziehen
- klare Abgrenzung zwischen 0.8.0-Technikbasis und 0.9.0-CLI-Vertrag
- deterministische Testausfuehrung unabhaengig von JVM-Defaults

Bewusst nicht in Scope:

- neue I18n-, Unicode-, Zeit- oder CSV-Features
- komplette Lokalisierung von Clikt-eigenen Help-/Usage-Texten
- grossflaechige Snapshot-Infrastruktur, wenn direkte deterministische
  Assertions denselben Vertrag praeziser absichern
- Aenderung der in Phase B bis F getroffenen Kernentscheidungen

---

## 4. Verbindliche Leitlinien

### 4.1 Tests pruefen Vertraege, nicht nur Codepfade

Phase G ist erst dann erfuellt, wenn die Tests den fachlichen 0.8.0-Vertrag
abbilden statt nur Implementierungsdetails zu beruehren.

Verbindliche Folge:

- Resolver-Tests pruefen Prioritaetsketten und Fallbacks
- Formatter-/Renderer-Tests pruefen sichtbare Wortlaute und Sprachtrennung
- Format-/Data-Tests pruefen die dokumentierte Zeit- und BOM-Semantik

### 4.2 Determinismus hat Vorrang vor Host-Defaults

I18n-/Timezone-Tests duerfen nicht implizit von JVM-Locale oder System-Zeit
abhaengen.

Verbindliche Folge:

- Tests injizieren Locale und Zone explizit
- Zahlen-, Zeit- und Message-Verhalten wird gegen stabile Erwartungen
  verglichen
- strukturierte Ausgaben bleiben sprachstabil englisch, auch unter deutscher
  Locale

### 4.3 0.8.0 dokumentiert die technische Basis, nicht den finalen `--lang`-Vertrag

Der Grenzverlauf zu 0.9.0 muss in Tests und Doku gleich klar sein.

Verbindliche Folge:

- ENV-/Config-/System-Resolution fuer Sprache ist Teil von 0.8.0
- `--lang` als final dokumentierte CLI-Override-Quelle bleibt 0.9.0
- der 0.8.0-Fehlerpfad fuer `--lang` wird nicht still aufgeweicht
- Doku darf `--lang` in 0.8.0 nicht breiter versprechen als die getestete CLI

### 4.4 ResourceBundles und Plain-Text koennen lokalisiert sein, strukturierte Payloads nicht

Phase Cs Grundentscheidung bleibt in Phase G explizit abgesichert.

Verbindliche Folge:

- Plain-Text-Formatter und Progress-Meldungen duerfen lokalisiert sein
- JSON-/YAML-Feldnamen und API-artige Statuswerte bleiben englisch
- Tests muessen beide Seiten getrennt pruefen

### 4.5 Unicode-, Zeit- und BOM-Tests sind Teil desselben I18n-Milestones

Internationalisierung endet in 0.8.0 nicht bei ResourceBundles.

Verbindliche Folge:

- Unicode-Utilities bleiben getrennt von Nutzdaten-Normalisierung getestet
- lokale und offsethaltige Zeitwerte werden unterschiedlich behandelt
- BOM-/Encoding-Pfade pruefen echte Unicode-Payloads statt nur ASCII

---

## 5. Arbeitspakete

### G.1 I18n-Runtime- und Resolver-Tests abschliessen

Ziel:

- Prioritaetsketten fuer Sprache, Config-Pfad, Timezone und Normalisierung
  regressionsfest pruefen
- Root-Fallback auf Englisch absichern
- 0.8.0-Verhalten von `--lang` explizit testen und dokumentieren

Ergebnis:

- `I18nSettingsResolverTest` deckt ENV-, Config-, System- und Fallback-Pfade ab
- `CliI18nContextTest` prueft den transportierten Runtime-Kontext und den
  0.8.0-Fehlerpfad fuer `--lang`
- `docs/guide.md` ist fuer den 0.8.0/0.9.0-Grenzverlauf bei `--lang`
  nachgezogen: der Option-Eintrag dokumentiert Exit 7 und die
  produktiven Resolutions-Quellen (`D_MIGRATE_LANG`, `LC_ALL`/`LANG`,
  `i18n.default_locale`)

### G.2 ResourceBundle-, Formatter- und Renderer-Vertrag schliessen

Ziel:

- Root-/DE-Bundles auf Vollstaendigkeit und Nicht-Leerheit pruefen
- Message-Fallback und sprachspezifische Wortlaute absichern
- Plain-Text- und strukturierte Ausgabe getrennt testen

Ergebnis:

- `BundleCompletenessTest` sichert die ueber `ResourceBundle` sichtbare
  Schluesselmenge und Nicht-Leerheit ab, nicht aber die physische
  Schluesselgleichheit der DE-Datei ohne Parent-Fallback
- `MessageResolverTest` prueft Bundle-Loading, Unsupported-Locale-Fallback auf
  das Root-Bundle und Missing-Key-zu-Key-String-Verhalten
- ein gezielter Test fuer "Key fehlt nur im DE-Bundle -> Root-Bundle" ist
  in `MessageResolverTest` ergaenzt und nutzt ein dediziertes
  Test-Bundle-Paar unter `src/test/resources/test-messages-phase-g/`, damit
  das Lueckenszenario auch gegenueber den (absichtlich schluesselgleichen)
  produktiven Bundles nachweisbar bleibt
- `OutputFormatterTest` und `ProgressRendererTest` decken englische und
  deutsche Ausgaben sowie englisch stabile JSON-/YAML-Payloads ab

### G.3 Unicode- und ICU4J-Vertrag regressionsfest machen

Ziel:

- Grapheme-Counting fuer kombinierte Zeichen, Emoji und ZWJ-Sequenzen
  absichern
- Normalisierung als explizite Utility statt stiller Nutzdatenmutation testen

Ergebnis:

- `UnicodeNormalizerTest` und `GraphemeCounterTest` decken die produktiven
  Utility-Vertraege ab

### G.4 Zeit- und `--since`-Vertrag aus Phase E schliessen

Ziel:

- lokale und offsethaltige Date/Time-Pfade getrennt absichern
- keine stille Default-Zonierung in `--since`
- Serializer-/Deserializer-Vertrag fuer `TIMESTAMP` und
  `TIMESTAMP WITH TIME ZONE` pruefen

Ergebnis:

- `DataExportHelpersTest`, `ValueSerializerTest` und
  `ValueDeserializerTest` decken den 0.8.0-Zeitvertrag ab

### G.5 BOM-/Encoding- und Doku-Konsolidierung aus Phase F abschliessen

Ziel:

- geteilten `EncodingDetector`-Vertrag ueber CSV, JSON und YAML absichern
- `--csv-bom`-Wortlaut in Help, Tests und Doku angleichen
- nicht-lateinische Payloads in den relevanten Reader-/Writer-Tests pruefen

Ergebnis:

- `EncodingDetectorTest`, `CsvChunkReaderTest`, `JsonChunkReaderTest`,
  `YamlChunkReaderTest` und `CsvChunkWriterTest` decken den finalen Vertrag ab
- `cli-spec`, `design`, `connection-config-spec` und `guide` sprechen
  denselben Wortlaut; die 0.8.0-/0.9.0-Grenzklaerung fuer `--lang` ist im
  `guide` jetzt ebenfalls explizit

---

## 6. Teststrategie

Die Phase schliesst die im Masterplan geforderte Mindestabdeckung mit einer
Kombination aus gezielten Unit- und CLI-nahen Vertragstests.

Abgedeckte Pflichtfaelle:

- Locale-Resolution:
  - ENV > Config > System > Fallback
  - Config-Pfad-Prioritaet `--config` > `D_MIGRATE_CONFIG` > Default-Datei
  - `--lang` bleibt in 0.8.0 bewusst ausserhalb des finalen CLI-Vertrags
- ResourceBundle-Fallback:
  - Englisches Root-Bundle
  - deutsches Bundle
  - Unsupported-Locale-Fallback auf Root
  - Missing-Key -> Key-String
  - ueber `ResourceBundle` sichtbare Bundle-Schluessel und Nicht-Leerheit
- OutputFormatter/ProgressRenderer:
  - englische und deutsche Plain-Text-Ausgaben
  - strukturierte JSON-/YAML-Ausgaben bleiben englisch
- Unicode-Integritaet:
  - kombinierte Zeichen
  - Emoji und ZWJ-Sequenzen
  - kyrillische und weitere nicht-lateinische Payloads
- Grapheme-Counting:
  - Basiszeichen
  - kombinierte Akzente
  - Familien-Emoji / Flaggen / Multi-Codepoint-Grapheme
- Zeitzonen:
  - `TIMESTAMP`
  - `TIMESTAMP WITH TIME ZONE`
  - `--since` mit und ohne Offset
  - keine Auto-Zonierung lokaler Literale
- CSV/BOM:
  - Export `--csv-bom` fuer UTF-8, UTF-16 BE und UTF-16 LE
  - Non-UTF-Encoding als No-op beim Writer
  - Import-Auto-Detection fuer UTF-8- und UTF-16-BOM
  - UTF-16-Input auf CSV-, JSON- und YAML-Pfad
  - Unicode-Payloads in Detector, Readern und CSV-Writer

Bewusste Testform:

- keine schwere Snapshot-Infrastruktur
- stattdessen direkte, deterministische Assertions auf Wortlaute, Felder und
  Render-Ergebnisse

---

## 7. Datei- und Codebasis-Betroffenheit

Primar betroffen:

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/config/...`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/output/...`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/...`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/...`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/...`
- `docs/cli-spec.md`
- `docs/design.md`
- `docs/connection-config-spec.md`
- `docs/guide.md`

Wesentliche Abschlussartefakte:

- Resolver- und CLI-Kontext-Tests fuer Locale/Timezone/Normalization
- Bundle-/Message-/Formatter-/Renderer-Tests fuer EN/DE
- Unicode- und Grapheme-Utility-Tests
- Temporal-/`--since`-Tests
- BOM-/Encoding-Tests fuer Detector, Reader und CSV-Writer
- konsolidierte Nutzerdokumentation

---

## 8. Risiken und offene Punkte

### R1 - Tests koennen bei impliziten Host-Defaults wieder fragil werden

Wenn spaetere Refactorings wieder auf JVM-Locale oder System-Zone zugreifen,
werden die Vertrags-Tests fragil oder irrefuehrend.

Mitigation:

- explizite Injektion beibehalten
- neue I18n-bezogene Tests nie gegen unkontrollierte Defaults schreiben

### R2 - Dokumentation kann bei Folgeaenderungen erneut driften

0.8.0 spannt ueber mehrere Teilvertraege; kleine Doku-Aenderungen koennen
leicht wieder inkonsistent werden.

Mitigation:

- `cli-spec`, `design`, `connection-config-spec` und `guide` gemeinsam pflegen
- bei Aenderungen an `--csv-bom`, `--encoding`, Zeit- oder Locale-Vertraegen
  immer Dokumentation und Tests zusammen anfassen
- offene `--lang`-Breitformulierung im `guide` nicht als bereits erledigt
  verbuchen

### R3 - 0.8.0/0.9.0-Grenze bei `--lang` bleibt kommunikativ heikel

Die technische Basis ist da, der finale Nutzervertrag aber noch nicht.

Mitigation:

- 0.8.0-Doku bleibt explizit
- 0.9.0 bleibt der Ort fuer den produktiven CLI-Override-Vertrag

### R4 - Bundle-Fallback-Tests koennen mehr suggerieren als sie direkt beweisen

Durch `ResourceBundle`-Vererbung und einen nicht spezialisierten Fallback-Key
kann eine Testbasis staerker wirken als ihr direkter Nachweis.

Mitigation:

- Aussagen auf den wirklich getesteten Umfang begrenzen
- gezielter Test fuer "Key fehlt nur in DE" ist in Phase G ergaenzt:
  `MessageResolverTest` nutzt ein dediziertes Test-Bundle-Paar unter
  `src/test/resources/test-messages-phase-g/`, in dem `root.only.key`
  bewusst nur im Root-Bundle liegt, und beweist den Parent-Chain-Fallback
  unter `Locale.GERMAN`

### Offene Frage O1

Sollen Clikt-eigene Help- und Usage-Texte spaeter systematisch lokalisiert
werden?

Empfehlung:

- nicht in 0.8.0 erzwingen
- erst nach der stabilen 0.9.0-Klaerung fuer `--lang` ausbauen

---

## 9. Entscheidungsempfehlung

Empfohlen war fuer 0.8.0 ein bewusst fokussierter I18n-Milestone. Phase G
schliesst diesen Vertrag produktiv ab:

- die technische I18n-Basis ist getestet
- die sichtbare EN/DE-CLI-Ausgabe ist regressionsfest abgesichert
- Unicode-, Zeit- und BOM-Vertraege sind nicht nur dokumentiert, sondern
  konkret pruefbar
- `cli-spec`, `design`, `connection-config-spec` und `guide` sprechen
  denselben Wortlaut; die 0.8.0-/0.9.0-Grenzklaerung fuer `--lang` ist im
  `guide` ebenfalls explizit
- der finale `--lang`-Nutzervertrag bleibt bewusst 0.9.0

Damit endet 0.8.0 nicht mit verstreuten Einzelimplementierungen, sondern mit
einem zusammenhaengenden Plattform-Milestone: internationalisierungsfaehig,
testbar und dokumentarisch konsistent.
