# Implementierungsplan: 0.9.3 - Arbeitspaket 6.1 `Filter-Haertung im Datenexport`

> **Milestone**: 0.9.3 - Beta: Filter-Haertung und
> MySQL-Sequence-Emulation (Generator)
> **Arbeitspaket**: 6.1 (`Filter-Haertung im Datenexport`)
> **Status**: Draft (2026-04-20)
> **Referenz**: `docs/implementation-plan-0.9.3.md` Abschnitt 4.1,
> Abschnitt 4.2, Abschnitt 4.2a, Abschnitt 5.1, Abschnitt 5.1a,
> Abschnitt 5.1b, Abschnitt 6.1, Abschnitt 6.6, Abschnitt 7 und
> Abschnitt 8;
> `docs/cli-spec.md`;
> `docs/quality.md`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ExportOptionsFingerprint.kt`;
> `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractJdbcDataReader.kt`.

---

## 1. Ziel

Arbeitspaket 6.1 ersetzt den bisherigen Trusted-Raw-SQL-Vertrag von
`data export --filter` durch einen kleinen, sicheren DSL-Vertrag:

- `--filter` bleibt der sichtbare Flag-Name
- Nutzereingaben werden geparst, normalisiert und parameterisiert
- der Exportpfad erzeugt aus Nutzereingaben keine rohen
  `WhereClause`-Fragmente mehr
- Resume und Fingerprint arbeiten auf einer stabilen kanonischen
  Filter-Repräsentation

6.1 liefert bewusst noch keinen Sequence-Beitrag. Das Arbeitspaket ist
der eigenstaendige Filter-Teil von 0.9.3.

Nach 6.1 soll klar gelten:

- `data export --filter` ist eine enge DSL und kein SQL-Passthrough mehr
- die Trust-Boundary ist im technischen Vertrag selbst sichtbar
- alte Raw-SQL-Filter und alte Raw-SQL-Checkpoints scheitern frueh,
  erklaerbar und testbar
- der Streaming-Kern bleibt erhalten; geaendert wird die
  Nutzer-Eingabeschicht

---

## 2. Ausgangslage

Der aktuelle Exportpfad behandelt `--filter` als rohes SQL-Fragment:

- `DataExportCommand` kennt heute nur die sichtbare Nutzeroption
  `--filter`
- `DataExportHelpers.resolveFilter(...)` baut daraus bisher bewusst
  einen `DataFilter.WhereClause`
- `AbstractJdbcDataReader` haengt diesen Inhalt an `WHERE ...` an
- Tests decken die heutige Pass-through-Semantik breit ab
  (`CliDataExportTest`, `DataExportRunnerTest`,
  `DataExportE2EMysqlTest`)

Das ist technisch bekannt, aber fuer 0.9.3 nicht mehr tragfaehig:

- der Nutzervertrag wirkt wie ein normaler, sicherer Filterparameter
- Resume/Fingerprint basieren auf rohem Texteingang statt auf
  semantischer Normalform
- alte SQL-Fragmente sind nicht kontrolliert auf eine kleine,
  parserbare Teilmenge begrenzt

Gleichzeitig soll 6.1 den bestehenden Exportkern nicht unnoetig
umbauen:

- `AbstractJdbcDataReader` behaelt die vorhandene `WHERE`-Integration
- `DataFilter.ColumnSubset` ist fachlich nicht betroffen
- die bestehende Komposition von `--filter` und `--since` ueber
  `DataFilter.Compound` bleibt erhalten

---

## 3. Scope fuer 6.1

### 3.1 In Scope

- `--filter` bleibt sichtbar bestehen, bekommt aber eine feste DSL
- eigener `FilterDslParser` inklusive AST und Parse-Fehler-Typ
- Parser, Normalisierung und Fingerprint-Kanonisierung im Modul
  `hexagon/application`
- Uebersetzung DSL -> `DataFilter.ParameterizedClause` bzw.
  `DataFilter.Compound`
- harte Exit-2-Fehler fuer:
  - alte Raw-SQL-Filter
  - leere oder whitespace-only Filterwerte
  - nicht erlaubte Operatoren, Tokens und Funktionsformen
  - alte Raw-SQL-Checkpoints beim Resume
- Help-Text, Fehlermeldungen, CLI-Spec und Quality-Doku auf den neuen
  Vertrag ziehen
- gezielte Unit- und E2E-Tests fuer die neue DSL

### 3.2 Bewusst nicht Teil von 6.1

- ein SQL-Escape-Hatch neben `--filter`
- eine breite SQL-Light-Sprache mit `OR`, Klammern oder Funktionen
- Sequence- oder DDL-Generator-Arbeit
- neue Export-Flags jenseits des bestehenden `--filter`

Praezisierung:

6.1 loest "wie wird der Exportfilter sicher gemacht?", nicht "wie wird
der gesamte Exportpfad fachlich neu designt?".

---

## 4. Leitentscheidungen

### 4.1 `--filter` bleibt der sichtbare Nutzervertrag

Verbindliche Folge:

- der sichtbare Flag-Name bleibt `--filter`
- ab 0.9.3 akzeptiert `--filter` keine rohe SQL-WHERE-Klausel mehr
- ungueltige oder offensichtlich alte Raw-SQL-Ausdruecke enden mit
  Exit 2 und Migrationshinweis

Begruendung:

- der Sicherheitsgewinn soll im eigentlichen Nutzervertrag liegen
- ein zweiter "unsicherer" Flag-Name wuerde das Problem nur verschieben

### 4.2 Resume und Fingerprint arbeiten auf Normalform

Verbindliche Folge:

- `ExportOptionsFingerprint` arbeitet auf parserbasierter Normalform
  statt auf rohem Filtertext
- Unterschiede nur in Whitespace, Keyword-Schreibweise oder
  Identifier-Kapitalisierung duerfen keine unterschiedlichen
  Fingerprints erzeugen
- alte Checkpoints mit nicht DSL-kompatiblem Raw-SQL-Filter sind
  bewusst inkompatibel und werden mit Exit 2 abgewiesen

### 4.3 Die DSL bleibt klein und abgeschlossen

Verbindliche Folge:

- erlaubt sind nur:
  - Vergleichsklauseln
  - `IN (...)`
  - `IS NULL` / `IS NOT NULL`
  - `AND`-Verkettung
- nicht erlaubt sind:
  - `OR`
  - `NOT`
  - Klammern
  - Funktionsaufrufe
  - Arithmetik
  - freie SQL-Fragmente
  - dialektspezifische Operatoren

### 4.4 Leere Filterwerte sind ungueltig

Verbindliche Folge:

- `--filter ""` und whitespace-only Werte sind Exit 2
- "kein Filter" bedeutet ausschliesslich, dass `--filter` nicht gesetzt
  ist

### 4.5 `null` ist nur ueber `IS NULL` / `IS NOT NULL` erlaubt

Verbindliche Folge:

- `name = null`
- `name != null`
- `name IN (null)`

sind explizit syntaxfehlerhaft und muessen eine gezielte Fehlermeldung
mit Hinweis auf `IS NULL` / `IS NOT NULL` liefern.

### 4.6 `DataFilter.ColumnSubset` bleibt unberuehrt

Verbindliche Folge:

- 6.1 aendert nur den Pfad, der bisher `WhereClause` befuellt
- `ColumnSubset` bleibt fachlich und technisch ausserhalb dieses
  Arbeitspakets

### 4.7 `--since` komponiert weiter ueber `DataFilter.Compound`

Verbindliche Folge:

- `--since` bleibt ein eigener `ParameterizedClause`
- der neue `--filter`-Clause wird damit in `DataFilter.Compound`
  zusammengefuehrt
- 6.1 fuehrt keinen konkurrierenden zweiten Kombinationspfad ein

---

## 5. Zielarchitektur

### 5.1 Parser- und Schichtvertrag

Der neue DSL-Pfad besteht aus einer eigenen Parser-Stufe:

- `FilterDslParser.parse(raw: String): FilterDslParseResult`
- `FilterDslParseError(message: String, token: String?, index: Int?)`
- AST-Typen:
  - `FilterDslExpression`
  - `FilterClause`
  - `FilterLiteral`

Schichtregel:

- Parser, AST und Normalisierung liegen in `hexagon/application`
- `DataExportHelpers` darf diese Komponente direkt nutzen
- `adapters/driving/cli` bleibt fuer Argumententgegennahme und
  Exit-2-Diagnosen zustaendig, nicht fuer die eigentliche
  DSL-Implementierung

### 5.2 DSL-Vertrag

Zulaessige Grammatik:

```text
filter        := clause (ws "AND" ws clause)*
clause        := comparison | in_list | null_check
comparison    := identifier ws? operator ws? literal
in_list       := identifier ws? "IN" ws? "(" literal ("," literal)* ")"
null_check    := identifier ws? "IS NULL" | identifier ws? "IS NOT NULL"
operator      := "=" | "!=" | ">" | ">=" | "<" | "<="
```

Identifier-Vertrag:

- `[A-Za-z_][A-Za-z0-9_]*`
- optional qualifiziert als `<table>.<column>`
- kein Identifier-Escaping in der DSL selbst
- Dialekt-Quoting erfolgt erst nach erfolgreichem Parse ueber die
  bestehenden Identifier-Utilities

Literal-Vertrag:

- Integer und Dezimalzahlen
- `true | false` case-insensitive
- Strings single-quoted mit verdoppelten Quotes als einzigem Escape
- `null` nur ueber `IS NULL` / `IS NOT NULL`
- `IN (...)` mit mindestens einem Literal

### 5.3 Normalisierung und SQL-Erzeugung

Der normale Pfad lautet:

- parse
- AST
- kanonische Normalform fuer Fingerprint/Resume
- SQL + Parameterliste
- `DataFilter.ParameterizedClause`

Normalform-Regeln:

- Keywords uppercased
- ueberfluessige Leerzeichen entfernt
- Identifier segmentweise ASCII-lowercased
- Literalwerte semantisch unveraendert

SQL-Erzeugung:

- generisches SQL mit `?`-Platzhaltern
- keine direkte Weitergabe roher Nutzereingaben als `WhereClause`
- Dialekt-Quoting der Identifier erfolgt in `DataExportHelpers`
  ueber bestehende `quoteQualifiedIdentifier()`-Hilfen

### 5.4 Request-, Runner- und Reader-Pfad

Der Exportkern bleibt erhalten, aber der Filtertransport aendert sich:

- `DataExportRequest` traegt keinen rohen Filterstring mehr, sondern die
  geparste oder bereits abgeleitete parametrisierte Repräsentation
- `DataTransferRunner` reicht `ParameterizedClause` bzw. `Compound`
  weiter
- `AbstractJdbcDataReader` behaelt die bestehende `WHERE`-Integration,
  bekommt aber keinen rohen DSL-Text mehr aus Nutzereingaben

---

## 6. Konkrete Arbeitsschritte

### 6.1 Parser und AST einfuehren

- `FilterDslParser` in `hexagon/application` anlegen
- AST-Typen und Parse-Fehlertyp definieren
- Tokenisierung, Grammar-Parse und Fehlerpositionen implementieren

### 6.2 DSL -> `DataFilter` uebersetzen

- Uebersetzung AST -> SQL + Parameterliste kapseln
- nur `ParameterizedClause` und `Compound` erzeugen
- keine erfolgreiche Nutzer-Eingabe darf in `WhereClause` enden

### 6.3 CLI- und Runner-Pfad umstellen

- `DataExportCommand` behaelt die sichtbare Option `--filter`
- `DataExportHelpers` delegiert an den Parser
- `DataExportRequest` und `DataTransferRunner` auf die neue
  Repräsentation umstellen
- `--filter` plus `--since` weiter ueber `Compound` zusammenfuehren

### 6.4 Fingerprint und Resume nachziehen

- `ExportOptionsFingerprint` auf kanonische DSL-Normalform umstellen
- alte Raw-SQL-Checkpoints gezielt mit Exit 2 und Migrationshinweis
  ablehnen
- Resume fuer semantisch gleiche DSL-Ausdruecke stabil halten

### 6.5 Sichtbare Vertrage aktualisieren

- `docs/cli-spec.md`
- `docs/quality.md`
- Help-Text und Fehlertexte im Command-/Runner-Pfad

Alle muessen denselben Vertrag sprechen:

- sichere DSL statt Raw SQL
- kein leerer Filter
- kein `= null`
- kein stilles Weiterverwenden alter Raw-SQL-Eingaben

---

## 7. Tests und Verifikation

### 7.1 Unit-Tests

- `FilterDslParserTest`:
  - Parser-Lexing
  - AST-Baum
  - Normalform
  - Fehlerposition/-token
- `DataExportRunnerTest`, `CliDataExportTest`:
  - gueltige DSL -> `ParameterizedClause`
  - leerer oder whitespace-only `--filter` -> Exit 2
  - nicht erlaubte Raw-SQL-Formen -> Exit 2
  - `= null` / `!= null` / `IN (null)` -> Exit 2 mit Hinweis auf
    `IS NULL` / `IS NOT NULL`
  - Resume-/Fingerprint-Stabilitaet fuer kanonisch gleiche DSL
  - Resume mit altem Raw-SQL-Checkpoint -> Exit 2 mit Migrationshilfe

### 7.2 E2E-Tests

- `DataExportE2EMysqlTest` wird von Raw-SQL-Syntax auf die 0.9.3-DSL
  umgestellt
- mindestens ein erfolgreicher E2E-Lauf prueft echte JDBC-Bind-
  Parameter im Exportpfad
- mindestens ein E2E-Fall prueft, dass ein frueher zulaessiger
  Raw-SQL-Filter nun mit klarer Migrationsdiagnose fehlschlaegt

### 7.3 Akzeptanzkriterien

6.1 gilt als abgeschlossen, wenn gleichzeitig gilt:

- `data export` dokumentiert und testet `--filter` nur noch als sichere
  DSL
- der CLI-Pfad erzeugt aus Nutzereingaben keine rohen
  `WhereClause`-Fragmente mehr
- `FilterDslParser` liegt in `hexagon/application`
- Fingerprints bleiben stabil bei reiner Umformatierung,
  Keyword-Case-Aenderung und Identifier-Kapitalisierung
- alte Raw-SQL-Checkpoints scheitern gezielt und reproduzierbar
- `--since` und `--filter` komponieren weiter korrekt ueber
  `DataFilter.Compound`

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `docs/cli-spec.md`
- `docs/quality.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ExportOptionsFingerprint.kt`
- neuer `FilterDslParser` in
  `hexagon/application/src/main/kotlin/...`

Voraussichtlich testseitig betroffen:

- `hexagon/application/src/test/kotlin/.../FilterDslParserTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/DataExportE2EMysqlTest.kt`

---

## 9. Offene Punkte

### 9.1 DSL-Scope bleibt bewusst eng

0.9.3 soll keine breite SQL-Light-Sprache einfuehren. Wenn spaeter mehr
Ausdrucksformen noetig werden, muessen sie als explizite
Vertragserweiterung mit eigener Testmatrix eingefuehrt werden.

### 9.2 Alte Raw-SQL-Checkpoints bleiben ein bewusster Bruch

Der Resume-Bruch fuer alte Raw-SQL-Filter ist beabsichtigt. Wichtig ist
nicht, ihn zu vermeiden, sondern ihn frueh, klar und reproduzierbar zu
kommunizieren.
