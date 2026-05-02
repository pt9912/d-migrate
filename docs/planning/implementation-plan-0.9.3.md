# Implementierungsplan: Milestone 0.9.3 - Beta: Filter-Haertung und MySQL-Sequence-Emulation (Generator)

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.3. Es fokussiert zwei bewusst gekoppelte Arbeiten:
> die sichtbare Haertung des rohen SQL-Filterpfads im Datenexport und
> die generatorseitige, opt-in MySQL-Emulation benannter Sequences.
>
> Status: Draft (2026-04-20)
> Referenzen: `docs/planning/roadmap.md` Abschnitt "Milestone 0.9.3",
> `docs/user/quality.md`,
> `docs/planning/mysql-sequence-emulation-plan.md`,
> `spec/ddl-generation-rules.md`,
> `spec/neutral-model-spec.md`,
> `spec/cli-spec.md`,
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DefaultValue.kt`,
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`,
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaValidator.kt`,
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerationOptions.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt`,
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`,
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`,
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`,
> `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`,
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt`,
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt`.

---

## 1. Ziel

Milestone 0.9.3 schliesst zwei verbleibende Beta-Luecken, die vor einem
stabilen 1.0-Vertrag nicht offen bleiben sollten:

- der heutige rohe SQL-Exportfilter wird durch einen sicheren
  `--filter`-Vertrag ersetzt
- MySQL kann benannte Sequences im DDL-Generator optional ueber
  kanonische Hilfsobjekte emulieren, statt sie immer nur mit `E056`
  zu ueberspringen

Der Milestone liefert damit drei konkrete Nutzerergebnisse:

- `data export` bekommt einen sicheren, parameterisierten
  `--filter`-Vertrag statt roher SQL-Interpolation
- `schema generate --target mysql` bekommt einen opt-in
  `helper_table`-Pfad fuer benannte Sequences
- das neutrale Modell kann Sequence-Nutzung an Spaltendefaults explizit
  ausdruecken, statt dies implizit in generischen Funktionsstrings zu
  verstecken

Bewusst noch nicht Teil dieses Milestones:

- Reverse-Engineering der MySQL-Emulation
- Compare-/Diff-Normalisierung gegen die Hilfsobjekte

Diese Themen folgen laut Roadmap in 0.9.4 oder spaeter.

---

## 2. Ausgangslage

### 2.1 Filter-Pfad

Stand im aktuellen Repo:

- `data export` bietet heute nur `--filter`
- `DataExportHelpers.resolveFilter(...)` behandelt den Wert bewusst als
  rohes SQL-Fragment und interpoliert ihn unveraendert in `WHERE ...`
- die Trust-Boundary ist zwar dokumentiert, aber nur in Help-Text,
  Kommentaren und Plan-/Quality-Dokumenten sichtbar
- Tests decken die Pass-through-Semantik breit ab
  (`CliDataExportTest`, `DataExportRunnerTest`, `DataExportE2EMysqlTest`)

Konsequenz:

- technisch ist das Verhalten bekannt und absichtlich
- produktseitig ist der Vertrag aber zu weich formuliert; fuer neue
  Nutzer wirkt `--filter` wie ein normaler, sicherer Query-Parameter

### 2.2 MySQL-Sequences

Stand im aktuellen Repo:

- `MysqlDdlGenerator.generateSequences(...)` erzeugt nur `E056`
- `DdlGenerationOptions` kennt noch keine MySQL-Sequence-Option
- `DefaultValue` kennt noch keinen expliziten Sequence-Default-Typ
- `AbstractDdlGenerator.columnSql(...)` delegiert pauschal an
  `TypeMapper.toDefaultSql(...)`
- Parser, Builder, Validator und Compare-Helfer kennen nur:
  - `StringLiteral`
  - `NumberLiteral`
  - `BooleanLiteral`
  - `FunctionCall`

Konsequenz:

- benannte Sequences koennen zwar fachlich im neutralen Schema
  definiert werden
- die Nutzung dieser Sequences durch Spalten ist aber nicht als
  neutraler Vertrag modelliert
- MySQL hat deshalb heute keinen sinnvollen Generatorpfad ausser
  `ACTION_REQUIRED`

### 2.3 Vorarbeit ist bereits vorhanden

Die grobe Produktarchitektur liegt bereits in
`docs/planning/mysql-sequence-emulation-plan.md` vor. Fuer 0.9.3 muss davon
bewusst nur der Generator-Teil umgesetzt werden:

- Phase A: Vertrag schaerfen
- Phase B: Generator und CLI-Option
- Phase C: Tests und Golden Masters

Reverse und Compare bleiben explizit fuer 0.9.4 offen.

---

## 3. Scope

### 3.1 In Scope

- `data export` behaelt den sichtbaren Flag-Namen `--filter`, fuehrt
  darunter aber eine sichere Filter-DSL ein
- die CLI akzeptiert unter `--filter` keine rohen SQL-Fragmente mehr
- der neue Filter-Vertrag ist eine eigene Anwendungskomponente
  (`FilterDslParser`, eigener AST, strukturierter Parse-Fehler),
  im Modul `hexagon/application`, nicht ein nur in der CLI verankerter
  Adapter-Helfer
- Help-Texte, CLI-Spec und Fehlermeldungen dokumentieren die sichere
  DSL und die Abgrenzung gegen bisherige Raw-SQL-Eingaben
- `DefaultValue.SequenceNextVal(sequenceName: String)` wird im
  neutralen Modell eingefuehrt
- YAML-/JSON-Schema-Codecs koennen den neuen Default-Typ lesen und
  schreiben
- `SchemaValidator` validiert Sequence-Defaults gegen existierende
  `schema.sequences`
- `schema generate` bekommt
  `--mysql-named-sequences action_required|helper_table`
- `DdlGenerationOptions` bekommt einen getypten MySQL-Sequence-Modus
- `MysqlDdlGenerator` erzeugt im `helper_table`-Modus:
  - `dmg_sequences`
  - Seed-Statements fuer definierte Sequences
  - `dmg_nextval(...)`
  - `dmg_setval(...)`
  - kanonische `BEFORE INSERT`-Trigger fuer
    `DefaultValue.SequenceNextVal(...)`
- Warning-Codes `W114` bis `W117` werden fuer den neuen Vertrag
  dokumentiert; generatorseitig in 0.9.3 werden davon `W114`, `W115`
  und `W117` emittiert, `W116` bleibt fuer 0.9.4 reserviert
- Unit-Tests, Golden Masters und MySQL-Integrationstests fuer beide
  Modi (`action_required`, `helper_table`)

### 3.2 Bewusst nicht Teil von 0.9.3

- ein frei formulierbarer Raw-SQL-Escape-Hatch neben `--filter`
- Reverse von `dmg_sequences`, Routinen oder Support-Triggern
- Compare-/Diff-Normalisierung der MySQL-Hilfsobjekte
- automatische Migration vorhandener, handgeschriebener
  Sequence-Loesungen
- SQLite-Sequence-Emulation
- Tool-Export-spezifische neue Oberflaechenoptionen jenseits des
  bestehenden `schema generate`-Pfads

---

## 4. Leitentscheidungen

### 4.1 `--filter` bleibt der sichtbare Name und wird sicher implementiert

Verbindliche Entscheidung:

- der sichtbare, dokumentierte Flag-Name bleibt `--filter`
- `--filter` akzeptiert ab 0.9.3 keine rohe SQL-WHERE-Klausel mehr,
  sondern eine allowlist-basierte Filter-DSL
- die CLI validiert die DSL vollstaendig in `DataExportCommand` bzw.
  `DataExportHelpers`, bevor ein `DataExportRequest` gebaut oder ein
  Resume-/Fingerprint-Pfad vorbereitet wird
- ungueltige oder offensichtlich alte Raw-SQL-Ausdruecke enden mit
  Exit 2 und klarer Migrationsfehlermeldung
- neue Dokumentation, Beispiele und Tests nutzen nur noch die sichere
  DSL unter `--filter`

Begruendung:

- das Roadmap-Ziel erlaubt explizit Filter-DSL als Haertungspfad
- ein zweiter unsicherer Flag-Name wuerde den eigentlichen
  Sicherheitsgewinn nur verschieben, nicht liefern

### 4.2 Resume-/Fingerprint-Semantik bleibt fuer die neue DSL stabil

Verbindliche Entscheidung:

- innerhalb der neuen DSL bleibt `ExportOptionsFingerprint` fuer
  denselben kanonischen Filterausdruck stabil
- der Preflight/Fingerprint arbeitet auf einer kanonischen,
  parserbasierten Repräsentation des Filters statt auf rohem SQL-Text
- Unterschiede nur in Whitespace, Keyword-Schreibweise oder
  Identifier-Kapitalisierung duerfen innerhalb der DSL **keine**
  unterschiedlichen Fingerprints erzeugen
- bestehende Checkpoints bleiben nur dann wiederaufnehmbar, wenn ihr
  gespeicherter Filter bereits der neuen DSL entspricht
- alte Checkpoints mit vormals gueltigem, aber nicht DSL-kompatiblem
  Raw-SQL-Filter werden explizit als inkompatibel abgewiesen und nicht
  still neu interpretiert

### 4.2a Rueckwaertskompatibilitaet ist bewusst partiell, nicht absolut

Verbindliche Entscheidung:

- "rueckwaertskompatibel" in 0.9.3 bedeutet fuer den Filterpfad
  **nicht**, dass alte Raw-SQL-`--filter`-Ausdruecke weiterlaufen
- "rueckwaertskompatibel" fuer den Sequence-Teil bedeutet:
  - bestehende CLI-Oberflaechen ausser `--filter` bleiben stabil
  - `--mysql-named-sequences action_required` behaelt den bisherigen
    DDL-Charakter fuer benannte Sequences (`E056`) bei
  - nicht betroffene Default-Typen (`StringLiteral`, `NumberLiteral`,
    `BooleanLiteral`, uebliche `FunctionCall`s wie
    `current_timestamp`/`gen_uuid`) bleiben unveraendert
- nicht rueckwaertskompatibel und deshalb bewusst mit Migrationsfehler:
  - alte Raw-SQL-`--filter`-Eingaben
  - alte oder programmgesteuerte
    `FunctionCall("nextval(...)")`-Schema-Notationen
- die Ablehnung von `FunctionCall("nextval(...)")` ist ein expliziter
  **Breaking Change** fuer bestehende Schema-Inputs ueber alle Dialekte,
  auch wenn die praktische Wirkung vor allem PostgreSQL-Schemas treffen
  kann, die diese historische Schreibweise heute noch verwenden

Begruendung:

- der Sicherheitsgewinn des Milestones lebt gerade davon, alte
  unsichere Notationen nicht still weiterzutragen
- fuer Reviews, Release Notes und Migrationstexte muss diese Grenze
  explizit bleiben

### 4.3 Der neue neutrale Default-Vertrag ist explizit und nicht pattern-basiert

Verbindliche Entscheidung:

- `DefaultValue` wird um `SequenceNextVal(sequenceName: String)`
  erweitert
- die kanonische Schema-Schreibweise in YAML/JSON ist ein explizites
  Objekt:

```yaml
default:
  sequence_nextval: invoice_number_seq
```

- freie String-Literale bleiben unveraendert String-Literale; ein
  Literal wie `"nextval(invoice_number_seq)"` darf **nicht** implizit
  als `SequenceNextVal` umgedeutet werden
- generische `FunctionCall("nextval(...)")`-Varianten sind **nicht**
  der kanonische Generatorvertrag fuer 0.9.3
- bestehende oder programmgesteuert erzeugte Defaults in der Form
  `FunctionCall("nextval(...)")` bekommen **keinen** stillen Compat-
  Shim; sie werden mit klarer Validierungs-/Generate-Fehlermeldung auf
  die neue Objektform `default.sequence_nextval` verwiesen
- dieser Bruch wird in 0.9.3 nicht als "interner Cleanup", sondern als
  sichtbare Breaking-Change-Migration behandelt; verpflichtend sind
  Release-Note, Migrationshinweis und Vorher/Nachher-Beispiel fuer
  betroffene PostgreSQL- und generische Schema-Dateien

Begruendung:

- ein expliziter Subtyp ist fuer Generator, Validator und spaeteren
  Reverse/Compare klarer als das Verstecken in einem freien String
- eine explizite Objektform vermeidet Mehrdeutigkeiten mit bestehenden
  `StringLiteral`-Defaults und macht Round-Trips ueber die Codecs
  verlustfrei

### 4.4 `--mysql-named-sequences` ist ein expliziter MySQL-spezifischer Opt-in

Verbindliche Entscheidung:

- neuer CLI-Vertrag:
  - `--mysql-named-sequences action_required`
  - `--mysql-named-sequences helper_table`
- Default ist `action_required`
- die Option ist nur zusammen mit `--target mysql` zulaessig
- eine explizite Nutzung bei PostgreSQL oder SQLite endet mit Exit 2

Begruendung:

- stilles Ignorieren einer explizit gesetzten Dialektoption ist fuer
  Automatisierungen zu fehlerfreundlich
- der Default bleibt konservativ, bis Reverse und Compare in 0.9.4
  nachgezogen sind

### 4.5 MySQL-Helper-Objekte bleiben kanonisch und nicht konfigurierbar

Verbindliche Entscheidung:

- 0.9.3 nutzt feste Support-Namen:
  - `dmg_sequences`
  - `dmg_nextval`
  - `dmg_setval`
  - `dmg_seq_<table16>_<column16>_<hash10>_bi`
- keine benutzerkonfigurierbaren Prefixe in diesem Milestone
- `table16` und `column16` sind die **ersten 16 Zeichen** des
  jeweiligen ASCII-lowercased Namens; kuerzere Namen werden ungekuerzt
  uebernommen; nicht-alphanumerische Zeichen ausser `_` werden vor der
  Kuerzung entfernt, um Kollisionen mit dem `_`-Trennzeichen im
  Triggernamen zu vermeiden
- `hash10` ist verbindlich definiert als die ersten 10 lowercase-
  Hex-Zeichen eines SHA-256 ueber den kanonischen Schluessel
  `<table-lower>\u0000<column-lower>`; dieselbe Regel gilt fuer Golden
  Masters, Rollback und spaeteres Reverse
- Namenskollisionen fuehren nicht zu stiller Umbenennung
- der Generator faellt in Konfliktfaellen auf strukturierte
  `ACTION_REQUIRED`-/`E056`-Diagnose statt auf "best effort" zurueck

Begruendung:

- nur ein kanonischer Namespace ist spaeter robust reverse-bar
- konfigurierbare Namen wuerden 0.9.4 unnoetig verkomplizieren

### 4.6 Supportobjekte respektieren den 0.9.2-Phasenvertrag

Verbindliche Entscheidung:

- `dmg_sequences` und zugehoerige Seed-Statements liegen in
  `PRE_DATA`
- Support-Routinen `dmg_nextval` / `dmg_setval` liegen in `POST_DATA`
- generierte Sequence-Support-Trigger liegen in `POST_DATA`
- nutzerdefinierte Trigger bleiben unveraendert nach den Support-
  Triggern im `POST_DATA`-Block

Begruendung:

- die Hilfstabelle ist strukturelle Vorbedingung fuer Routinen und
  Trigger
- importfreundliche `pre-post`-Artefakte bleiben konsistent mit dem
  bestehenden 0.9.2-Vertrag

### 4.7 Warning-Semantik wird jetzt festgezogen, aber nicht ueberladen

Verbindliche Entscheidung:

- `W114`: pro Sequence mit gesetztem `cache`, weil der Wert gespeichert,
  aber nicht als echte Preallocation emuliert wird
- `W115`: pro betroffener Spalte mit `SequenceNextVal`, weil MySQL im
  Triggerpfad explizites `NULL` nicht von "Wert ausgelassen" trennt
- `W117`: einmal pro DDL-Lauf im `helper_table`-Modus als globaler
  Hinweis auf transaktionsgebundene Sequence-Inkremente
- `W116` wird in 0.9.3 nur dokumentiert und im Ledger reserviert; die
  Emission folgt mit Reverse in 0.9.4

---

## 5. Zielarchitektur

### 5.1 Sicherer `--filter` ohne Funktionsumbau des Streaming-Kerns

Der Datenexport behaelt seinen technischen Kern:

- `DataExportHelpers.resolveFilter(...)` baut weiter einen
  `DataFilter`-Baum
- `AbstractJdbcDataReader` behaelt die bestehende `WHERE`-Integration
- `DataFilter.ColumnSubset` ist von 0.9.3 fachlich **nicht** betroffen;
  der Milestone aendert nur den Nutzerpfad, der heute `WhereClause`
  bzw. kuenftig `ParameterizedClause`/`Compound` befuellt
- die bestehende Komposition von `--filter` und `--since` ueber
  `DataFilter.Compound` bleibt erhalten; `--since` erzeugt weiterhin
  einen eigenen `ParameterizedClause`, der zusammen mit dem neuen
  DSL-basierten `ParameterizedClause` von `--filter` in einem
  `Compound` zusammengefuehrt wird

Geaendert wird die sichtbare API-Schicht:

- `DataExportCommand` behaelt genau eine Nutzeroption `--filter`
- `DataExportHelpers` delegiert den DSL-Input an einen
  anwendungsschichtnahen `FilterDslParser` und bildet daraus eine
  kanonische, parametrisierte Repräsentation
- der CLI-Pfad emittiert fuer `--filter` keinen
  `DataFilter.WhereClause` mehr, sondern nur noch sichere
  `ParameterizedClause`-/`Compound`-Formen
- `DataExportRequest` und `ExportOptionsFingerprint` arbeiten mit dem
  kanonischen DSL-Ausdruck oder dessen parserbasierter Normalform
- Preflight-Fehler und Help-Texte benennen den Filterpfad konsistent als
  "safe DSL", nicht als Trusted-Raw-SQL-Eingabe

### 5.1a DSL-Spezifikation fuer `--filter`

0.9.3 fuehrt bewusst eine abgeschlossene Filter-DSL ein. Sie ist
nicht "SQL light", sondern ein enges, parserbares Teilformat.

Verbindlicher Ausdrucksrahmen:

- erlaubt sind:
  - Vergleichsklauseln
  - `IN (...)`
  - `IS NULL` / `IS NOT NULL`
  - `AND`-Verkettung
  - `OR`
  - `NOT`
  - Klammern
  - Funktionsaufrufe (mit Allowlist)
  - Arithmetik
- nicht erlaubt sind:
  - freie SQL-Fragmente
  - dialektspezifische Operatoren

Zulaessige Grammatik auf hoher Ebene:

```text
filter_expr   := or_expr
or_expr       := and_expr (ws "OR" ws and_expr)*
and_expr      := not_expr (ws "AND" ws not_expr)*
not_expr      := "NOT" ws not_expr | predicate
predicate     := value_expr ws? operator ws? value_expr
               | value_expr ws "IN" ws? "(" value_expr ("," value_expr)* ")"
               | value_expr ws "IS" ws "NULL"
               | value_expr ws "IS" ws "NOT" ws "NULL"
               | "(" filter_expr ")"
value_expr    := unary_expr (("+"|"-") unary_expr)*
unary_expr    := ("*"|"/") atom_expr | atom_expr
atom_expr     := identifier | literal | function_call | "(" value_expr ")"
function_call := allowed_name "(" value_expr ("," value_expr)* ")"
operator      := "=" | "!=" | ">" | ">=" | "<" | "<="
```

Tokenisierungs-/Whitespace-Vertrag:

- zwischen Keywords, Identifiern, Operatoren und Literalen ist beliebige
  ASCII-Whitespace zulaessig; Parser und Fingerprint behandeln mehrere
  Leerzeichen, Tabs oder aehnliche Zwischenraeume als aequivalent
- vor und nach dem Gesamtausdruck ist Whitespace erlaubt und wird
  abgeschnitten
- ein nach dem Trim leerer `--filter`-Wert (`""`, `"   "`) ist
  **ungueltig** und endet mit Exit 2; "kein Filter" bleibt weiterhin
  nur der Fall, dass `--filter` gar nicht gesetzt ist
- innerhalb von Identifiern, Zahlen oder Keywords ist kein Whitespace
  erlaubt
- nach einer erfolgreich erkannten Klausel duerfen keine "restlichen"
  freien Tokens uebrig bleiben; insbesondere fuehren doppelte Operatoren,
  haengende Kommata oder nachgestellte SQL-Fragmente zu Exit 2

Identifier-Vertrag:

- unquoted Identifiers folgen dem bestehenden konservativen Muster
  `[A-Za-z_][A-Za-z0-9_]*`
- qualifizierte Identifiers mit genau einem Punkt sind erlaubt:
  `<table>.<column>`
- es gibt in 0.9.3 **kein** Identifier-Escaping in der DSL selbst:
  keine Backticks, keine doppelten Quotes, keine eckigen Klammern, keine
  Escape-Sequenzen
- keine freien SQL-Quotes, Backticks oder doppelten Quotes in der
  Nutzereingabe; Dialekt-Quoting erfolgt erst nach erfolgreichem Parse
  intern ueber die vorhandenen Identifier-Utilities
- Spalten oder Tabellen, die nur ueber Dialekt-Quoting adressierbar
  waeren, sind mit der 0.9.3-DSL bewusst **nicht** adressierbar; das ist
  Teil des Sicherheits- und Einfachheitsvertrags
- Identifier ausserhalb dieser Allowlist fuehren zu Exit 2

Literal-Vertrag:

- Zahlen:
  - ganzzahlig: `-?[0-9]+`
  - dezimal: `-?[0-9]+\\.[0-9]+`
- Booleans: `true | false` (case-insensitive)
- `null` ist nur ueber `IS NULL` / `IS NOT NULL` erlaubt, nicht als
  Vergleichsliteral
- Vergleichsformen wie `name = null`, `name != null` oder
  `name IN (null)` sind explizit syntaxfehlerhaft und bekommen eine
  gezielte DSL-Fehlermeldung mit Hinweis auf `IS NULL` / `IS NOT NULL`
- Strings:
  - single-quoted
  - Escape nur ueber verdoppelte Quotes (`'O''Reilly'`)
  - keine Backslash-Escapes
  - kein implizites Concatenation-Verhalten ueber benachbarte Literale
- Datums-/Zeitwerte laufen in 0.9.3 ebenfalls als String-Literale und
  werden nicht gesondert syntaktisch ausgezeichnet
- `IN (...)` enthaelt mindestens ein Literal; leere Listen sind
  unzulaessig
- die Reihenfolge der gebundenen Parameter folgt strikt der
  Links-nach-rechts-Reihenfolge der Literale im geparsten Ausdruck

Parser-/Normalisierungsvertrag:

- Keywords der DSL sind case-insensitive (`and`, `AND`, `In`, ...)
- der Fingerprint arbeitet auf einer Normalform:
  - Keywords uppercased
  - ueberfluessige Leerzeichen entfernt
  - Identifier segmentweise ASCII-lowercased
    (`Orders.Customer_ID` -> `orders.customer_id`)
  - diese Identifier-Kanonisierung gilt **fuer Fingerprint und Resume**,
    nicht als Vorgabe fuer spaetere Dialekt-Quoting-Ausgabe
  - Literalwerte semantisch unveraendert
- damit bleiben Fingerprints stabil bei reiner Umformatierung,
  Keyword-Case-Aenderung oder Identifier-Kapitalisierung
- erfolgreiche Parse-Ergebnisse werden ausschliesslich als
  `ParameterizedClause` bzw. `Compound` weitergereicht
- kein erfolgreicher DSL-Pfad darf einen rohen `WhereClause` aus
  Nutzereingaben erzeugen

Nicht erlaubt in 0.9.3:

- freie SQL-Fragmente (`EXISTS (...)`, `1=1`, Subqueries)
- dialektspezifische Operatoren (`ILIKE`, `LIKE`, `BETWEEN`,
  JSON-Operatoren, Regex)
- nicht in der Allowlist enthaltene Funktionsnamen

### 5.1b Parser-/AST-Vertrag fuer `--filter`

Der DSL-Vertrag ist als eigenstaendige Parser-Stufe implementiert:

- eigener Parser:
  - `FilterDslParser.parse(raw: String): FilterDslParseResult`
- eigener Fehler-Typ:
  - `FilterDslParseError(message: String, token: String?, index: Int?)`
  - enthält die erste gefundene Fehlerstelle fuer präzise Exit-2-Meldungen
- eigener AST, der vor SQL-Emission vollständig getrennt bleibt:
  - `FilterDslExpression` (AND-Liste)
  - `FilterClause` (`ComparisonClause`, `InListClause`, `NullCheckClause`)
  - `FilterLiteral` (`StringLiteral`, `NumberLiteral`, `BooleanLiteral`)
- Schichtvertrag:
  - `FilterDslParser`, AST und Normalisierungslogik liegen in einer
    von CLI und Anwendungslogik gemeinsam nutzbaren Schicht
    innerhalb von `hexagon/application`
  - `DataExportHelpers` darf diese Komponente direkt nutzen, ohne eine
    Abhaengigkeit auf `adapters/driving/cli` einzufuehren
  - die CLI bleibt fuer Argumententgegennahme und Exit-2-Diagnosen
    zustaendig, nicht fuer die eigentliche DSL-Implementierung

Der normale Pfad:

- parse -> AST -> canonical SQL + Parameterliste -> `DataFilter.ParameterizedClause`
- kein rohes Nutzereingabe-SQL wird direkt in `WhereClause` weitergegeben
- der Parser liefert die Fingerprint-Normalform mit deterministischer
  Parameternormalisierung und Tokenisierung
- die Uebersetzung AST -> SQL + Parameterliste liegt in einer eigenen
  Methode innerhalb von `FilterDslParser` oder einer zugehoerigen
  Companion-Klasse im selben Paket (`hexagon/application`)
- diese Methode ist **nicht** dialektspezifisch; sie erzeugt
  generisches SQL mit `?`-Platzhaltern und unquoted Identifiern
- Dialekt-Quoting der Identifier erfolgt erst in `DataExportHelpers`
  ueber die bestehenden `quoteQualifiedIdentifier()`-Utilities, bevor
  der fertige `ParameterizedClause` an den Reader weitergereicht wird

Fehlervertrag:

- nicht parsbare Eingaben enden mit Exit 2
- die Fehlermeldung nennt:
  - die erste fehlerhafte Stelle oder das fehlerhafte Token
  - die erlaubte DSL-Teilmenge
  - einen kurzen Migrationshinweis fuer fruehere Raw-SQL-Nutzung

### 5.2 Neutralmodell und Schema-Codecs

`DefaultValue` bekommt einen neuen Subtyp:

```kotlin
sealed class DefaultValue {
    data class StringLiteral(val value: String) : DefaultValue()
    data class NumberLiteral(val value: Number) : DefaultValue()
    data class BooleanLiteral(val value: Boolean) : DefaultValue()
    data class FunctionCall(val name: String) : DefaultValue()
    data class SequenceNextVal(val sequenceName: String) : DefaultValue()
}
```

Folgen:

- `SchemaNodeParser.parseDefault(...)` mappt
  `default.sequence_nextval: <name>` auf
  `SequenceNextVal("<name>")`
- da der Parser bisher nur **skalare** YAML-Nodes als Defaults
  verarbeitet (Boolean, Number, String), wird die Fallunterscheidung
  um **Map-Nodes** erweitert: ein Map-Node mit dem einzigen Schluessel
  `sequence_nextval` wird als `SequenceNextVal` interpretiert;
  unbekannte Map-Schluessel fuehren zu einem strukturierten Parse-Fehler
- die Implementierung lehnt sich bewusst an das bereits existierende
  Parse-Muster fuer reservierte Default-Funktionsnamen wie
  `current_timestamp` und `gen_uuid` an: reservierte Spezialfaelle
  werden frueh im Codec erkannt, aber `nextval(...)` bleibt **kein**
  freier Pattern-Match und wird nur ueber `default.sequence_nextval`
  kanonisch modelliert
- `SchemaNodeBuilder.buildDefault(...)` schreibt `SequenceNextVal`
  als explizites Map-Objekt `{ "sequence_nextval": "<name>" }` statt
  als skalaren Wert; dies ist ein Strukturwechsel gegenueber den
  bisherigen skalaren `put()`-Aufrufen fuer die anderen Default-Typen
- `SchemaValidator.isDefaultCompatible(...)` akzeptiert
  `SequenceNextVal` nur fuer numerische/identifier-aehnliche Spalten
  und nur, wenn die referenzierte Sequence existiert
- `SchemaValidator` oder ein aequivalenter frueher Vorpruefpfad lehnt
  alte `FunctionCall("nextval(...)")`-Defaults explizit ab und liefert
  eine migrationsfaehige Fehlermeldung mit Verweis auf
  `default.sequence_nextval`
- verbindliche Ablehnungsreihenfolge fuer Legacy-`nextval(...)`:
  1. Schema-Codec/Parser darf die historische Form hoechstens noch als
     generisches `FunctionCall` einlesen, aber **nicht** als
     `SequenceNextVal` umdeuten
  2. `SchemaValidator` ist der zentrale, nutzernahe harte
     Migrationsabbruchpunkt mit konsistenter Fehlermeldung
  3. Generator-/TypeMapper-Pfade behalten zusaetzlich defensive Guards,
     duerfen aber keinen stillen Fallback oder Rewrite mehr enthalten
- `SchemaCompareHelpers.defaultValueToString(...)` rendert
  `sequence_nextval(<name>)` oder eine aequivalent klar als Sequence-
  Bezug erkennbare Diff-Darstellung; entscheidend ist die eindeutige
  Trennung von freien String-Literalen
- diese Diff-/Report-Darstellung ist ausdruecklich eine
  lesbare Kurzform und **keine** kanonische Eingabeform fuer YAML/JSON;
  die Eingabeform bleibt `default.sequence_nextval`

Nicht Teil von 0.9.3:

- DB-Reverse von PostgreSQL-`nextval('...')` oder MySQL-Support-
  Triggern auf diesen Subtyp

### 5.3 Generator-Optionen

`DdlGenerationOptions` wird erweitert um:

- `mysqlNamedSequenceMode: MysqlNamedSequenceMode?`

Neues Enum:

- `ACTION_REQUIRED`
- `HELPER_TABLE`

CLI- und Runner-Pfad:

- `SchemaGenerateCommand` parst die Option
- `SchemaGenerateRequest` traegt entweder den expliziten Override oder
  den bereits aufgeloesten Enum-Wert
- `SchemaGenerateRunner` validiert die Kombination mit `--target`
- JSON und Report machen den effektiven Modus fuer MySQL sichtbar

Verbindlicher Output-Vertrag:

- die Erweiterung ist additiv; bestehende Felder wie `ddl`,
  `split_mode`, `ddl_parts`, `notes` und `skipped_objects` bleiben
  unveraendert
- das neue Optionsfeld erscheint **nur** bei `target = mysql`
- fuer PostgreSQL und SQLite bleibt das Feld vollstaendig absent,
  nicht `null` und nicht mit einem MySQL-Defaultwert befuellt
- technische Regel dafuer:
  - in `DdlGenerationOptions` ist das Feld nullable
  - fuer Nicht-MySQL-Ziele bleibt es `null`
  - JSON-Serializer und Report-Renderer muessen `null` konsequent
    unterdruecken statt einen Default wie `action_required`
    auszugeben
- die Default-Aufloesung auf `action_required` passiert erst im
  MySQL-spezifischen Runner-/Generatorpfad, nicht im gemeinsamen
  Options-DTO fuer alle Targets

Empfohlene Serialisierung fuer MySQL:

- JSON:
  - `generator_options.mysql_named_sequences`
- Report:
  - `target.mysql_named_sequences`

### 5.3a Versionsvertrag fuer 0.9.3

Der Versionsnachzug ist in 0.9.3 ein eigener, expliziter Vertrag und
nicht nur impliziter Nachlauf der Generatorarbeit.

Pflichtstellen:

- `AbstractDdlGenerator.getVersion()` -> `0.9.3`
- `TransformationReportWriter`-Header/Generatorstring -> `0.9.3`
- `SchemaGenerateHelpers.formatJsonOutput()` bzw. aequivalente JSON-
  Metadaten -> `d-migrate 0.9.3`
- alle davon abhaengigen Golden Masters und Snapshot-Tests werden im
  selben Milestone aktualisiert

Akzeptanzkriterium:

- es verbleibt keine user-visible `0.9.2`-Versionsausgabe in DDL,
  Report oder JSON-Output des `schema generate`-Pfads

### 5.4 Generatorstruktur fuer `helper_table`

Die Implementierung soll den bestehenden 0.9.2-Phasenschnitt nutzen,
nicht umgehen.

Empfohlene Aufteilung:

- `MysqlDdlGenerator.generateSequences(...)`
  - `action_required`: bisheriges Verhalten
  - `helper_table`: Support-Tabelle + Seed-Statements + W114
- `MysqlDdlGenerator.generateFunctions(...)` oder ein neuer,
  generatorinterner Support-Helfer
  - emittiert `dmg_nextval`
  - emittiert `dmg_setval`
- `MysqlDdlGenerator.generateTriggers(...)`
  - emittiert zuerst generierte Sequence-Support-Trigger fuer
    `SequenceNextVal`
  - emittiert danach die normalen User-Trigger ueber
    `MysqlRoutineDdlHelper`

Bewusste Designgrenze:

- `TypeMapper.toDefaultSql(...)` bleibt fuer Literal- und
  Funktionsdefaults zustaendig
- `SequenceNextVal` wird **nicht** als normaler SQL-Default im
  `TypeMapper` aufgeloest, weil MySQL dafuer Trigger braucht und
  PostgreSQL Identifier-/Regclass-Quoting benoetigt
- der Interception-Punkt liegt in `AbstractDdlGenerator.columnSql()`:
  vor dem Aufruf von `typeMapper.toDefaultSql(...)` wird geprueft, ob
  der Default ein `SequenceNextVal` ist; in diesem Fall delegiert
  `columnSql()` an eine neue, ueberschreibbare Methode
  (z.B. `resolveSequenceDefault(col, seqName): String?`), die pro
  Dialekt entscheidet:
  - PostgreSQL: gibt `DEFAULT nextval('<seq>')` zurueck
  - MySQL `helper_table`: gibt `null` zurueck (kein DEFAULT, Trigger
    uebernimmt)
  - MySQL `action_required` / SQLite: gibt `null` zurueck und
    registriert eine strukturierte Diagnose
- `TypeMapper.toDefaultSql(...)` bekommt `SequenceNextVal` damit
  **nie** zu sehen und muss den Fall nicht behandeln

### 5.5 DDL-Vertrag pro Dialekt

PostgreSQL:

- `SequenceDefinition` -> natives `CREATE SEQUENCE`
- `SequenceNextVal(seq)` -> `DEFAULT nextval('<seq>')`

MySQL `action_required`:

- `SequenceDefinition` bleibt `E056`
- Spalten mit `SequenceNextVal` erzeugen keine stille DEFAULT-Interpolation
- betroffene Spalte erhaelt eine strukturierte `ACTION_REQUIRED`-Note
  mit Bezug auf die fehlende Emulation

MySQL `helper_table`:

- `SequenceDefinition` -> `dmg_sequences`-Zeile
- `SequenceNextVal(seq)` -> kanonischer `BEFORE INSERT`-Trigger
- `cache` bleibt Metadatum in `dmg_sequences`, aber ohne echte
  Preallocation

SQLite:

- bleibt in 0.9.3 auf manuellem Pfad
- `SequenceNextVal` fuehrt wie MySQL `action_required` zu
  strukturierter Diagnose statt zu stiller SQL-Erzeugung

Abgrenzung fuer die Implementierung:

- die Verantwortung fuer PostgreSQL-/SQLite-Verhalten wird in 0.9.3 in
  den jeweiligen Dialektmodulen geklaert und getestet
- das Arbeitspaket "MySQL-Generator" in Abschnitt 6.4 umfasst dagegen
  ausschliesslich die neuen MySQL-Supportobjekte und deren Triggerpfad

### 5.6 Rollback

Im Single-Output mit `--generate-rollback` muss der neue MySQL-Pfad
vollstaendig rueckwaerts erzeugbar bleiben.

Rollback-Reihenfolge:

1. generierte Sequence-Support-Trigger
2. Support-Routinen `dmg_nextval`, `dmg_setval`
3. Seed-/Supportobjekt `dmg_sequences`

Da `AbstractDdlGenerator.generateRollback(...)` heute rein auf
Statement-Inversion via `invertStatement()` basiert und DELIMITER-
Bloecke nicht versteht, wird der Rollback fuer MySQL-Supportobjekte
ueber einen eigenen Pfad in `MysqlDdlGenerator` erzeugt:

- `MysqlDdlGenerator` ueberschreibt oder ergaenzt den Rollback-Pfad
  mit einer dedizierten Methode (z.B. `generateSupportObjectRollback`),
  die die Drop-Statements fuer Trigger, Routinen und `dmg_sequences`
  in fester Reihenfolge erzeugt
- diese Methode arbeitet nicht ueber `invertStatement()`, sondern
  emittiert direkte `DROP TRIGGER IF EXISTS`, `DROP FUNCTION IF EXISTS`
  und `DROP TABLE IF EXISTS`-Statements
- die resultierenden Drop-Statements werden **vor** den invertierten
  regulaeren Statements in den Rollback-Block eingefuegt

Pflicht-Akzeptanzkriterien:

- die Drop-Reihenfolge ist fest und wird in Tests explizit assertet:
  1. Trigger
  2. Routinen
  3. Support-Tabelle
- jeder Drop ist idempotent formuliert (`IF EXISTS` oder aequivalente
  sichere Form)
- ein kompletter Up->Down-Lauf im `helper_table`-Modus darf an
  Supportobjekten nicht an Reihenfolgefehlern scheitern

---

## 6. Konkrete Arbeitspakete

Abhaengigkeiten:

1. `6.1` kann parallel zu den Sequence-Arbeiten laufen
2. `6.2` muss vor `6.3` und `6.4` liegen
3. `6.3` muss vor der eigentlichen MySQL-Generierung fertig sein
4. `6.4` und `6.5` koennen teilweise parallel vorbereitet werden
5. `6.6` schliesst den Milestone ab

### 6.1 Filter-Haertung im Datenexport

- `DataExportCommand` behaelt genau eine Nutzeroption `--filter`
- `DataExportHelpers` delegiert an `FilterDslParser` (5.1b) und bekommt
  dessen parsebare Repräsentation
- Literale aus der DSL werden in
  `DataFilter.ParameterizedClause`/`Compound` ueberfuehrt statt als
  rohes SQL interpoliert
- `DataExportRequest` traegt statt eines rohen Filter-Strings die
  geparste DSL-Repraesentation oder den daraus abgeleiteten
  `ParameterizedClause`
- `DataTransferRunner` reicht den Filter als `ParameterizedClause`
  bzw. `Compound` an den Reader weiter; die Aenderung betrifft die
  Stelle, an der heute `DataFilter.WhereClause` aus dem Request
  entnommen und an `AbstractJdbcDataReader` uebergeben wird
- `ExportOptionsFingerprint` wird auf eine kanonische DSL-
  Repräsentation umgestellt
- Eingaben, die erkennbar dem alten Raw-SQL-Stil entsprechen, enden mit
  Exit 2 und einer Migrationshilfe statt mit stiller Weiterverwendung
- `spec/cli-spec.md`, `docs/user/quality.md` und Nutzerbeispiele werden auf
  die DSL unter `--filter` aktualisiert
- `FilterDslParser` wird in einer nicht-CLI-gebundenen Schicht
  im Modul `hexagon/application` eingefuehrt, so dass
  `DataExportHelpers` ihn direkt ohne
  Adapter-Abhaengigkeit nutzen kann
- Resume mit altem Checkpoint, dessen gespeicherter Filter nur im
  frueheren Raw-SQL-Vertrag gueltig war, endet gezielt mit Exit 2 und
  einer Migrationshilfe statt mit einem generischen Resume-Fehler

Ergebnis:

- der CLI-Filterpfad ist fuer Nutzerwerte sicher und parameterisiert
- Resume-Fingerprints bleiben innerhalb der DSL stabil

### 6.2 Sequence Phase A1: Vertrag, Enum und Code-Ledger festziehen

- `MysqlNamedSequenceMode` einfuehren
- `DdlGenerationOptions` erweitern
- `SchemaGenerateCommand` und `SchemaGenerateRunner` verdrahten
  `--mysql-named-sequences`
- Output-Vertrag fuer JSON/Report festziehen
- `spec/ddl-generation-rules.md` und `spec/cli-spec.md` um:
  - die neue MySQL-Option
  - `W114` bis `W117`
  - die `helper_table`-Semantik
  erweitern
- den Versionsvertrag aus 5.3a explizit nachziehen:
  - `AbstractDdlGenerator.getVersion()`
  - Report-Generator-Header in `TransformationReportWriter`
  - JSON-Metadaten in `SchemaGenerateHelpers.formatJsonOutput()`
  - davon abhaengige Golden Masters
- `ledger/warn-code-ledger-0.9.3.yaml` und bei Bedarf
  `ledger/error-code-ledger-0.9.3.yaml` anlegen
- falls Filter-DSL-Parsefehler und Legacy-Checkpoint-Inkompatibilitaet
  als strukturierte, user-visible Diagnosen auftreten, bekommen sie
  eigene 0.9.3-Error-Codes im Error-Ledger; kein dauerhafter
  stderr-only-Sonderpfad ohne Ledger-Eintrag
- die harte Ablehnung historischer `FunctionCall("nextval(...)")`-
  Eingaben wird in derselben Phase als Breaking Change fuer Release
  Notes/Migrationsdoku markiert; der Ledger-/Diagnosepfad muss diesen
  Fall stabil und wiedererkennbar benennen
- `ledger/code-ledger-0.9.3.schema.json` anlegen **oder**
  `CodeLedgerValidationTest` auf eine versionstolerante Ledger-Aufloesung
  umstellen; in jedem Fall muss die bestehende Ledger-Validierung die
  0.9.3-Dateien aktiv pruefen
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`
  ist dafuer explizit Teil des Milestones

Ergebnis:

- alle sichtbaren Vertrage sind dokumentiert, bevor der Generatorcode
  folgt

### 6.3 Sequence Phase A2: Neutralmodell und Audit aller `DefaultValue`-Verzweigungen

- `DefaultValue.SequenceNextVal` einfuehren
- alle exhaustiven `when (default)`-/`when (dv)`-Stellen anpassen,
  insbesondere:
  - `SchemaValidator.isDefaultCompatible(...)`
  - `SchemaNodeParser.parseDefault(...)`
  - `SchemaNodeBuilder.buildDefault(...)`
  - `SchemaCompareHelpers.defaultValueToString(...)`
  - `MysqlTypeMapper.toDefaultSql(...)`
  - `PostgresTypeMapper.toDefaultSql(...)`
  - `SqliteTypeMapper.toDefaultSql(...)`
  - zugehoerige Tests
- dabei explizit festhalten:
  - PostgreSQL kennt einen nativen Pfad
  - MySQL/SQLite duerfen `SequenceNextVal` nicht still als freie
    Funktion behandeln
  - bestehende `FunctionCall("nextval(...)")`-Faelle werden bewusst
    **hart** mit klarer Migrationsfehlermeldung auf
    `default.sequence_nextval` umgestellt; kein stilles Rewrite, kein
    Warning-only-Pfad
  - dieser Migrationsfehler ist als dialect-uebergreifender Breaking
    Change dokumentiert; betroffene PostgreSQL-Schemas werden nicht
    weich grandfathered
  - TypeMapper-Faelle koennen fuer nicht unterstuetzte Dialekte
    absichtlich `error(...)`/gesonderte Generatorpfade oder
    strukturiertes Abfangen im Aufrufer verwenden

Ergebnis:

- das Repo ist wieder exhaustiv-kompilierbar
- der neue Subtyp ist nicht nur im Generator, sondern in allen
  relevanten Infrastrukturpfaden bekannt

### 6.4 Sequence Phase B: MySQL-Generator fuer `helper_table`

- generatorinterne Support-Helfer fuer:
  - Support-Tabelle
  - Seed-Statements
  - Routinen
  - Trigger-Namensbildung
- `MysqlDdlGenerator.generateSequences(...)` schaltet zwischen
  `ACTION_REQUIRED` und `HELPER_TABLE`
- der MySQL-spezifische Tabellenbau behandelt `SequenceNextVal` nur fuer
  MySQL:
  - `helper_table`: kein SQL-DEFAULT, stattdessen Trigger-Spez
  - `action_required`: strukturierte Diagnose
- PostgreSQL-/SQLite-spezifische Behandlung von `SequenceNextVal` wird
  in 6.3 bzw. den jeweiligen Dialektmodulen separat nachgezogen und ist
  **nicht** Teil der MySQL-Supportobjekt-Arbeit
- kanonische Marker-Kommentare und feste Objektform laut
  `docs/planning/mysql-sequence-emulation-plan.md`
- `W114`, `W115`, `W117` werden an den vorgesehenen Stellen erzeugt
- Kollisionen mit reservierten Hilfsnamen erzeugen keinen stillen
  Fallback
- Rollback-Inversion fuer Supportobjekte absichern
- feste Drop-Assertions fuer Trigger -> Routinen -> `dmg_sequences`
  werden in Unit- und Integrationstests verankert

Ergebnis:

- `schema generate --target mysql --mysql-named-sequences helper_table`
  erzeugt produktive DDL statt nur `E056`

### 6.5 Doku- und Fixture-Nachzug

- `spec/neutral-model-spec.md` ergaenzen:
  - `default.sequence_nextval: invoice_number_seq`
  - MySQL-Opt-in-Modus
- `spec/schema-reference.md` im selben Zug aktualisieren, weil dort die
  nutzerorientierte `default:`-Syntax beschrieben wird
- Release-Notes/Migrationsdoku muessen den harten Wechsel von
  `FunctionCall("nextval(...)")` auf `default.sequence_nextval` als
  expliziten Breaking Change hervorheben:
  - vor/nach-Migrationsbeispiel
  - Hinweis, dass alle Dialekte betroffen sind, auch wenn bestehende
    PostgreSQL-Schemas praktisch am haeufigsten getroffen werden koennen
  - klare Such-/Ersetzungsheuristik fuer bestehende Schema-Dateien
- `docs/planning/roadmap.md` und ggf. `docs/planning/mysql-sequence-emulation-plan.md`
  mit Verweis auf den umgesetzten Generator-Scope aktualisieren
- neue Schema-Fixtures fuer sequence-basierte Defaults anlegen
- neue Golden Masters fuer:
  - PostgreSQL native `nextval(...)`
  - MySQL `action_required`
  - MySQL `helper_table`
  - Split `pre-data` / `post-data`

### 6.6 Phase C: Tests und Verifikation

Unit-Tests:

- `DataExportRunnerTest`, `CliDataExportTest`:
  - `FilterDslParserTest` ist eigenständig und deckt Parser-Lexing,
    AST-Baum, Normalform und Fehlerposition/-token ab
  - gueltige `--filter`-DSL wird erfolgreich in parametrisierte
    `DataFilter`-Formen ueberfuehrt
  - leerer oder whitespace-only `--filter` -> Exit 2
  - nicht erlaubte Raw-SQL-Formen unter `--filter` -> Exit 2
  - Resume-/Fingerprint-Pfad bleibt fuer kanonisch gleiche DSL stabil
  - Resume mit altem Checkpoint und vormals gueltigem Raw-SQL-Filter
    wird explizit mit erklaerbarer Exit-2-Migrationsfehlermeldung
    abgewiesen
  - Kombination `--filter` + `--since` erzeugt einen korrekten
    `Compound` aus zwei `ParameterizedClause`-Teilen und bindet
    Parameter in der richtigen Reihenfolge
  - erlaubte Konstrukte (positive Abdeckung):
    - `OR`, `NOT`, Klammern, Funktionsaufrufe (Allowlist),
      Arithmetik werden korrekt geparst und parameterisiert
  - Token-/Literal-Fehlerpfade fuer:
    - ungueltige Identifier
    - kaputte String-Literale
    - `= null` / `!= null` / `IN (null)` mit Verweis auf
      `IS NULL` / `IS NOT NULL`
    - unerlaubte Operatoren und dialektspezifische Schluesselwoerter
    - nicht erlaubte Funktionsnamen und falsche Arität
- `DataExportE2EMysqlTest`:
  - bestehende `--filter`-E2E-Faelle werden von Raw-SQL-Syntax auf die
    0.9.3-DSL umgestellt
  - mindestens ein erfolgreicher E2E-Lauf prueft die DSL gegen echte
    JDBC-Bind-Parameter im Exportpfad
  - mindestens ein E2E-Fall prueft, dass ein frueher zulaessiger
    Raw-SQL-Filter nun mit klarer Migrationsdiagnose fehlschlaegt
- `SchemaValidatorTest`:
  - `SequenceNextVal` mit existierender Sequence ok
  - fehlende Sequence -> Validierungsfehler
  - nicht kompatibler Spaltentyp -> Validierungsfehler
  - altes `FunctionCall("nextval(...)")` -> klarer Migrationsfehler mit
    Verweis auf `default.sequence_nextval`
  - Generator-/TypeMapper-nahe Tests bestaetigen zusaetzlich, dass
    derselbe Legacy-Fall keinen stillen Fallback mehr bekommt, falls er
    den Validator irrtuemlich umgehen sollte
- `YamlSchemaCodecTest`:
  - Round-Trip fuer `default.sequence_nextval`
- `MysqlDdlGeneratorTest`:
  - `action_required` behaelt `E056`
  - `helper_table` erzeugt Tabelle, Seed, Routinen und Trigger
  - Warning-Semantik W114/W115/W117
  - Konfliktfall fuer reservierte Namen
- `SchemaGenerateRunnerTest` / `SchemaGenerateHelpersTest` /
  `TransformationReportWriterTest`:
  - Option validiert nur fuer MySQL
  - JSON/Report enthalten den effektiven Modus nur fuer MySQL
  - JSON/Report bleiben fuer PostgreSQL und SQLite ohne
    `mysql_named_sequences`-Feld
  - `DdlGenerationOptions.mysqlNamedSequenceMode = null` fuehrt bei
    Nicht-MySQL-Ausgaben zu vollstaendig absentem Feld, nicht zu
    `null`-Serialisierung und nicht zu implizitem `action_required`
  - Diff-/Report-Kurzform fuer `sequence_nextval(<name>)` wird als
    Lesedarstellung getestet, nicht als YAML-Eingabevertrag
- Golden-Master-/Header-Checks:
  - DDL-Header und Report-Generatorstring zeigen `0.9.3`
- `CodeLedgerValidationTest`:
  - prueft aktiv die 0.9.3-Ledgerdateien bzw. den verallgemeinerten
    Ledger-Resolver

Golden Masters:

- neue DDL-Files fuer MySQL `helper_table`
- Split-Assertions: `dmg_sequences` nur in `pre-data`,
  Routinen/Support-Trigger nur in `post-data`

Integrationstests (MySQL):

- `dmg_nextval('invoice_seq')` liefert monoton steigende Werte
- parallele Aufrufe erzeugen keine Duplikate
- Rollback einer Transaktion zieht den Inkrement-Schritt zurueck
  und wird genau deshalb mit `W117` dokumentiert
- `INSERT` ohne Spaltenwert fuellt die Sequence-Spalte ueber den
  Support-Trigger
- explizites `NULL` triggert denselben Pfad und belegt damit die
  lossy-Semantik hinter `W115`
- Up->Down-Lauf prueft explizit die feste Drop-Reihenfolge:
  Trigger vor Routinen vor `dmg_sequences`

---

## 7. Verifikationsstrategie

Der Milestone gilt als abgeschlossen, wenn die folgenden Aussagen
gleichzeitig erfuellt sind:

- `data export` dokumentiert und testet `--filter` nur noch als sichere,
  parameterisierte DSL
- eine eigene `FilterDslParser`-Einheit existiert inkl. eigenem
  Parse-Error-Typ und AST im Modul `hexagon/application`, und die
  Parser-Tests laufen grün
- der CLI-Pfad baut fuer `--filter` keine rohen `WhereClause`-Fragmente
  mehr aus Nutzereingaben
- Resume mit alten Raw-SQL-Checkpoints scheitert gezielt,
  migrationsfaehig und reproduzierbar statt diffus im spaeteren Lauf
- die user-visible Versionsangaben in DDL, Report und JSON stehen
  konsistent auf `0.9.3`
- der Breaking Change fuer historische
  `FunctionCall("nextval(...)")`-Schema-Notationen ist in Release-
  Notes/Migrationsdoku sichtbar, mit klarer Vorher/Nachher-Anleitung
- `DefaultValue.SequenceNextVal` ist in allen exhaustiven `when`-Pfaden
  beruecksichtigt
- PostgreSQL rendert sequence-basierte Defaults nativ
- MySQL `action_required` bleibt fuer den bisherigen DDL-Pfad
  rueckwaertskompatibel; die neue strikte Sequence-Notation ist davon
  bewusst ausgenommen
- MySQL `helper_table` erzeugt stabile Golden Masters
- Split-Output trennt Hilfstabelle und Support-Routinen/Trigger
  korrekt nach Phasen
- JSON, Report und stderr-Diagnosen zeigen den neuen Modus und die
  neuen Warnings konsistent
- Nicht-MySQL-Ausgaben serialisieren kein
  `mysql_named_sequences`-Feld, auch nicht implizit ueber DTO-Defaults

Zusatz fuer Freigabe:

- mindestens ein echter MySQL-Integrationstest muss den atomaren
  `dmg_nextval(...)`-Pfad gegen konkurrierende Aufrufe pruefen
- mindestens ein Integrationstest muss das lossy-`NULL`-Verhalten
  sichtbar belegen

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `docs/planning/roadmap.md`
- `docs/user/quality.md`
- `spec/neutral-model-spec.md`
- `spec/schema-reference.md`
- `spec/ddl-generation-rules.md`
- `spec/cli-spec.md`
- `ledger/code-ledger-0.9.3.schema.json`
- `ledger/warn-code-ledger-0.9.3.yaml`
- `ledger/error-code-ledger-0.9.3.yaml` (falls neue Error-Codes noetig)
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DefaultValue.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaValidator.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerationOptions.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ExportOptionsFingerprint.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- neuer `FilterDslParser` in
  `hexagon/application/src/main/kotlin/...` (nicht unter
  `adapters/driving/cli`)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt`

Voraussichtlich testseitig betroffen:

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt`
- `hexagon/application/src/test/kotlin/.../FilterDslParserTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/DataExportE2EMysqlTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpersTest.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodecTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/TransformationReportWriterTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/SchemaValidatorTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/...`

---

## 9. Risiken und offene Punkte

### 9.1 DSL-Scope fuer `--filter`

Offen bleibt nur, wie klein die erste DSL genau gezogen wird. Fuer
0.9.3 ist ein bewusst enger Scope richtig; eine zu breite Phase-1-DSL
wuerde denselben Risiko-Raum wie Raw-SQL nur unter neuem Namen
reproduzieren.

### 9.2 Schema-Syntax fuer Sequence-Defaults

0.9.3 nutzt bewusst eine explizite Objektform statt eines freien
Strings. Das kostet etwas Kompaktheit, verhindert aber den
Mehrdeutigkeitsfehler zwischen Sequence-Aufruf und String-Literal.
Quoted oder komplexere Sequence-Referenzen koennen spaeter auf dieser
expliziten Objektbasis erweitert werden.

### 9.3 Trigger-Reihenfolge auf MySQL

Der Support-Trigger muss mit eventuell vorhandenen User-Triggern
zusammenspielen. Die 0.9.3-Variante soll deterministische Namen und
Emissionsreihenfolge haben, aber noch kein vollstaendiges
`PRECEDES`-/`FOLLOWS`-Management einfuehren.

### 9.4 Extra-Hilfstabelle im Zielschema

`dmg_sequences` ist im `helper_table`-Modus ein reales Tabellenobjekt.
Das ist fachlich gewollt, muss aber in Doku, Golden Masters und
spaeterem Reverse/Compare exakt als Supportobjekt behandelt werden.

### 9.5 W116 bleibt vorerst nur reserviert

Das ist beabsichtigt und kein Lueckenfehler:

- 0.9.3 liefert den Generatorvertrag
- 0.9.4 liefert den Reverse-Vertrag, der `W116` ueberhaupt erst sauber
  erzeugen kann

---

## 10. Entscheidungsempfehlung

0.9.3 sollte bewusst den kleinen, klaren Schnitt liefern:

- sichere `--filter`-DSL statt weiterem Trusted-Raw-SQL-Vertrag
- `DefaultValue.SequenceNextVal` als sauberer Modellanker
- MySQL `helper_table` nur im Generatorpfad und nur opt-in

Diese Kombination schliesst den letzten sichtbaren Security-Footgun im
CLI-Vertrag, liefert echten MySQL-Mehrwert fuer `schema generate` und
haelt gleichzeitig Reverse/Compare-Komplexitaet aus dem Milestone
heraus. Das ist der richtige Umfang fuer einen Beta-Release vor 0.9.4.
