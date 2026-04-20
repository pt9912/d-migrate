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
> `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/AbstractJdbcDataReader.kt`.

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
- alte, nicht DSL-konforme Raw-SQL-Filter und alte Raw-SQL-Checkpoints
  scheitern frueh, erklaerbar und testbar
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
  - alte, nicht DSL-konforme Raw-SQL-Filter
  - leere oder whitespace-only Filterwerte
  - nicht erlaubte Operatoren, Tokens und Funktionsformen
  - alte Raw-SQL-Checkpoints beim Resume
- Help-Text, Fehlermeldungen, CLI-Spec und Quality-Doku auf den neuen
  Vertrag ziehen
- gezielte Unit- und E2E-Tests fuer die neue DSL

### 3.2 Bewusst nicht Teil von 6.1

- ein SQL-Escape-Hatch neben `--filter`
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
- als inkompatibel gelten nur solche Ausdrucksformen, die nicht in den 0.9.3-DSL
  aufgenommen sind (z. B. Dialekt-Operatoren, freies SQL wie `LIMIT`, `ORDER BY`,
  `JOIN`, `EXISTS`, `UNION`, etc.)
- ungueltige oder eindeutig nicht DSL-konforme Raw-SQL-Ausdruecke enden mit
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
- die Fingerprint-Normalform ist von der spaeteren SQL-Identifier-
  Ausgabe getrennt; SQL-Emission arbeitet weiter mit der im AST
  erhaltenen Original-Schreibweise des unquoted Identifiers
- alte Checkpoints mit nicht DSL-kompatiblem Raw-SQL-Filterstatus sind
  bewusst inkompatibel und werden mit Exit 2 abgewiesen

### 4.3 Die DSL bleibt abgeschlossen

Verbindliche Folge:

- erlaubt sind nur:
  - Vergleichsklauseln
  - `IN (...)`
  - `IS NULL` / `IS NOT NULL`
  - `AND`-Verkettung
  - `OR`
  - `NOT`
  - Klammern
  - Funktionsaufrufe
  - Arithmetik
- nicht erlaubt sind:
  - freie SQL-Fragmente
  - dialektspezifische Operatoren

### 4.4 Leere Filterwerte sind ungueltig

Verbindliche Folge:

- `--filter ""` und whitespace-only Werte sind Exit 2
- "kein Filter" bedeutet ausschliesslich, dass `--filter` nicht gesetzt
  ist

### 4.5 `null` ist nur ueber `IS NULL` / `IS NOT NULL` erlaubt

Verbindliche Folge:

jede Verwendung von `null` als Literal ist ungueltig, insbesondere:

- `name = null`
- `name != null`
- `name > null` / `name >= null` / `name < null` / `name <= null`
- `name IN (null)`

Diese Formen sind explizit syntaxfehlerhaft und muessen eine gezielte
Fehlermeldung mit Hinweis auf `IS NULL` / `IS NOT NULL` liefern.

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

### 4.8 Handgeschriebener Recursive-Descent-Parser ohne externe Library

Verbindliche Folge:

- `FilterDslParser` wird als handgeschriebener Recursive-Descent-Parser
  in Kotlin implementiert
- keine externe Parser-Library (kein ANTLR, kein better-parse, kein
  Parsek)
- eine Methode pro Vorrangstufe (`parseOrExpr`, `parseAndExpr`,
  `parseNotExpr`, `parsePredicate`, `parseAddExpr`, `parseMulExpr`,
  `parseUnaryExpr`, `parseAtom`)

Begruendung:

- die Grammatik umfasst ~15 Produktionsregeln; das rechtfertigt keine
  externe Abhaengigkeit
- gezielte Fehlermeldungen mit Position und Token (z. B. "null ist kein
  Literal, verwende IS NULL", "Funktion X nicht erlaubt") sind im
  Recursive-Descent-Flow direkt formulierbar; bei Combinator-Libraries
  oder Generatoren muesste das Error-Recovery-System der Library
  konfiguriert werden
- die Funktions-Allowlist und die `null`-Sonderregeln sind semantische
  Pruefungen, die sich im Parser-Flow natuerlich einfuegen
- kein Build-Plugin, kein Codegenerator, keine transitive Dependency

---

## 5. Zielarchitektur

### 5.1 Parser- und Schichtvertrag

Der neue DSL-Pfad besteht aus einem handgeschriebenen
Recursive-Descent-Parser (siehe 4.8):

- `FilterDslParser.parse(raw: String): FilterDslParseResult`
- `FilterDslParseError(message: String, token: String?, index: Int?)`
- AST-Typen:
  - `FilterExpr` (OR, AND, NOT, Predicate, GroupedExpr)
  - `ValueExpr` (Literal, Identifier, Arithmetic, FunctionCall, GroupedValue)
  - `FilterLiteral` (Integer, Decimal, Bool, StringLit)

Schichtregel:

- Parser, AST und Normalisierung liegen in `hexagon/application`
- `DataExportHelpers` darf diese Komponente direkt nutzen
- `adapters/driving/cli` bleibt fuer Argumententgegennahme und
  Exit-2-Diagnosen zustaendig, nicht fuer die eigentliche
  DSL-Implementierung

### 5.2 DSL-Vertrag

Zulaessige Grammatik:

```text
-- Boolsche Ebene (Vorrang: NOT > AND > OR)
filter_expr   := or_expr
or_expr       := and_expr (ws "OR" ws and_expr)*
and_expr      := not_expr (ws "AND" ws not_expr)*
not_expr      := "NOT" ws not_expr | predicate

-- Praedikate
predicate     := value_expr ws? operator ws? value_expr
               | value_expr ws "IN" ws? "(" ws? value_expr (ws? "," ws? value_expr)* ws? ")"
               | value_expr ws "IS" ws "NULL"
               | value_expr ws "IS" ws "NOT" ws "NULL"
               | "(" ws? filter_expr ws? ")"
operator      := "=" | "!=" | ">" | ">=" | "<" | "<="

-- Wertausdruecke (Vorrang: unary > * / > + -)
value_expr    := add_expr
add_expr      := mul_expr (ws? ("+" | "-") ws? mul_expr)*
mul_expr      := unary_expr (ws? ("*" | "/") ws? unary_expr)*
unary_expr    := "-" ws? atom | atom
atom          := literal
               | function_call
               | qualified_identifier
               | "(" ws? value_expr ws? ")"

-- Funktionsaufrufe (feste Allowlist)
function_call := allowed_fn ws? "(" ws? value_expr (ws? "," ws? value_expr)* ws? ")"
allowed_fn    := "LOWER" | "UPPER" | "TRIM" | "LENGTH"
               | "ABS" | "ROUND" | "COALESCE"

-- Identifier
identifier    := [A-Za-z_][A-Za-z0-9_]*
qualified_identifier := identifier ("." identifier)?

-- Literale
literal       := bool_lit | numeric_lit | string_lit
numeric_lit   := integer_lit | decimal_lit
integer_lit   := "0" | [1-9][0-9]*
decimal_lit   := ("0" | [1-9][0-9]*) "." [0-9]+
bool_lit      := "true" | "false"    (case-insensitive)
string_lit    := "'" ( [^'] | "''" )* "'"
ws            := [ \t]+
```

Alle Keywords (`AND`, `OR`, `NOT`, `IN`, `IS`, `NULL`, `true`, `false`,
Funktionsnamen) und Operatoren sind beim Parsen case-insensitive.

Vorrang-Uebersicht:

- Arithmetik: unaeres `-` > `*` `/` > `+` `-`
- Boolesch: `NOT` > `AND` > `OR`
- Klammern ueberschreiben beide Vorrang-Ebenen:
  `(filter_expr)` auf Praedikat-Ebene, `(value_expr)` auf Atom-Ebene

Funktions-Allowlist:

Die erlaubten Funktionsnamen sind abschliessend festgelegt. Ein nicht
gelisteter Funktionsname ist ein Parse-Fehler mit gezielter Meldung.
Funktionsaufrufe werden zusätzlich arity-validiert (z. B. per zentraler
Allowlist-Metadata):
- `LOWER`, `UPPER`, `TRIM`, `LENGTH`, `ABS` -> genau 1 Argument
- `ROUND` -> 1 oder 2 Argumente
- `COALESCE` -> mindestens 2 Argumente
Die Liste kann in spaeteren Versionen erweitert werden, ohne die
Grammatik zu aendern.

Identifier-Vertrag:

- `[A-Za-z_][A-Za-z0-9_]*` (identisch mit `identifier` in der Grammatik)
- optional qualifiziert als **genau eine** Ebene `<table>.<column>`
- bewusst nicht unterstützt: weitere Ebenen wie `schema.table.column`;
  ein Identifier mit mehr als einem Punkt ist ein Parse-Fehler mit
  gezielter Meldung
- kein Identifier-Escaping in der DSL selbst
- Dialekt-Quoting erfolgt erst nach erfolgreichem Parse ueber die
  bestehenden Identifier-Utilities
- case-sensitive bzw. nur ueber Quoting adressierbare Identifier sind in
  0.9.3 bewusst **nicht** Teil des DSL-Vertrags; der Parser akzeptiert
  nur konservative unquoted Identifiers

Literal-Vertrag:

- Integer und Dezimalzahlen
- `true | false` case-insensitive
- Strings single-quoted mit verdoppelten Quotes als einzigem Escape
- `null` nur ueber `IS NULL` / `IS NOT NULL`
- unaeres `-` ist als Vorzeichen in `unary_expr` modelliert, nicht
  mehr als Teil des Literal-Tokens; `-1` ist damit `unary_expr("-",
  integer_lit(1))`
- `IN (...)` mit mindestens einem `value_expr`; Arithmetik und
  Funktionsaufrufe sind innerhalb der Liste zulaessig
- fuehrende Nullen werden fuer numerische Literale bewusst nicht als
  alternative Schreibweise akzeptiert:
  - `0` ist erlaubt
  - `01`, `00`, `01.5` sind ungueltig
- Integer- und Dezimalliterale bleiben im Fingerprint absichtlich
  getrennt; `1` und `1.0` gelten in 0.9.3 nicht als dieselbe kanonische
  Schreibweise

- Keywords und Operatoren sind beim Parsen case-insensitive (siehe
  Grammatik); fuer die Normalform werden sie uppercased (`AND`, `OR`,
  `NOT`, `IN`, `IS NULL`, `IS NOT NULL`, Funktionsnamen,
  Vergleichs- und Arithmetik-Operatoren)

### 5.3 Normalisierung und SQL-Erzeugung

Der normale Pfad lautet:

- parse
- AST
- kanonische Normalform fuer Fingerprint/Resume
- SQL + Parameterliste
- `DataFilter.ParameterizedClause`

Normalform-Regeln:

- Keywords und Funktionsnamen uppercased
- Arithmetik- und Vergleichsoperatoren in kanonischer Schreibweise
- ueberfluessige Leerzeichen entfernt
- fuer den Fingerprint werden Identifier segmentweise
  ASCII-lowercased
- fuer die spaetere SQL-Erzeugung bleibt die originale
  Identifier-Schreibweise aus dem AST erhalten und wird erst danach
  dialektspezifisch gequotet
- Bool-Literale werden im Fingerprint auf `true` bzw. `false`
  kanonisiert
- Integer-Literale werden im Fingerprint in kanonischer dezimaler
  Schreibweise ohne fuehrende Nullen gehalten
- Dezimal-Literale bleiben als Dezimal-Literale erhalten; `1` und `1.0`
  werden bewusst nicht zusammengezogen
- Klammern, die durch Vorrangregeln redundant sind, bleiben im
  Fingerprint **erhalten**; der Parser entfernt keine Klammern, um die
  kanonische Form einfach und vorhersehbar zu halten.
  Dadurch bleiben semantisch äquivalente Varianten wie `a = b`
  und `(a = b)` in der Fingerprint-Repräsentation unterscheidbar.

SQL-Erzeugung:

- Literale werden als `?`-Platzhalter mit Bind-Parametern erzeugt
- Identifier, Arithmetik-Operatoren, Funktionsnamen und boolsche
  Operatoren werden als SQL-Text emittiert (nicht parameterisiert)
- keine direkte Weitergabe roher Nutzereingaben als `WhereClause`
- Dialekt-Quoting der Identifier erfolgt in `DataExportHelpers`
  ueber bestehende `quoteQualifiedIdentifier()`-Hilfen
- dieses Quoting arbeitet auf der originalen, im AST erhaltenen
  Identifier-Schreibweise; die lowercased Fingerprint-Form wird **nicht**
  direkt zur SQL-Erzeugung wiederverwendet
- Funktionsaufrufe werden 1:1 als SQL-Funktionsnamen emittiert;
  die Allowlist stellt sicher, dass nur bekannte, sichere Funktionen
  im erzeugten SQL erscheinen

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

- `FilterDslParser` als handgeschriebenen Recursive-Descent-Parser in
  `hexagon/application` anlegen (siehe Leitentscheidung 4.8)
- AST-Typen (`FilterExpr`, `ValueExpr`, `FilterLiteral`) und
  Parse-Fehlertyp definieren
- eine Methode pro Vorrangstufe: `parseOrExpr`, `parseAndExpr`,
  `parseNotExpr`, `parsePredicate`, `parseAddExpr`, `parseMulExpr`,
  `parseUnaryExpr`, `parseAtom`
- Funktions-Allowlist mit Arity-Metadata als zentrale Konstante pflegen

### 6.2 DSL -> `DataFilter` uebersetzen

- Uebersetzung AST -> SQL + Parameterliste kapseln
- nur `ParameterizedClause` und `Compound` erzeugen
- keine erfolgreiche Nutzer-Eingabe darf in `WhereClause` enden
- Funktionsaufrufe beim Übersetzen ebenfalls arity-validieren und bei
  Verstoß klar begruenden

### 6.3 CLI- und Runner-Pfad umstellen

- `DataExportCommand` behaelt die sichtbare Option `--filter`
- `DataExportHelpers` delegiert an den Parser
- `DataExportRequest` und `DataTransferRunner` auf die neue
  Repräsentation umstellen
- `--filter` plus `--since` weiter ueber `Compound` zusammenfuehren

### 6.4 Fingerprint und Resume nachziehen

- `ExportOptionsFingerprint` auf kanonische DSL-Normalform umstellen
- der Resume-Pfad bekommt in `DataExportRunner` einen expliziten
  vorgeschalteten Kompatibilitaetscheck:
  - Checkpoint laden
  - gespeicherten Filterstatus/Optionen validieren
  - altes Raw-SQL-Format oder nicht DSL-kompatible gespeicherte
    Filtertexte erkennen
  - **vor** tieferer Runner-/Parser-Hydratisierung gezielt Exit 2 mit
    Migrationshinweis ausgeben
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
  - AST-Baum fuer alle Ausdruecke (Vergleich, IN, IS NULL,
    Arithmetik, Funktionsaufrufe, NOT, AND, OR, Klammern)
  - Operator-Vorrang: `NOT` > `AND` > `OR`; unaeres `-` > `*` `/` > `+` `-`
  - Klammern ueberschreiben Vorrang korrekt
  - Normalform
  - Fehlerposition/-token
  - Trennung zwischen Fingerprint-Identifierform und originaler
    AST-Identifierschreibweise
  - numerische Literale mit fuehrenden Nullen -> Parse-Fehler
  - `= null` / `!= null` / `IN (null)` -> Parse-Fehler mit Hinweis
    auf `IS NULL` / `IS NOT NULL`
  - Identifier mit mehr als einem Punkt -> Parse-Fehler
  - nicht gelisteter Funktionsname -> Parse-Fehler mit Hinweis auf
    erlaubte Funktionen
  - ungültige Funktions-Arity -> Fehler mit konkreter Arity-Meldung
  - Arithmetik in IN-Listen und auf beiden Seiten eines Vergleichs
- `DataExportRunnerTest`, `CliDataExportTest`:
  - gueltige DSL -> `ParameterizedClause`
  - leerer oder whitespace-only `--filter` -> Exit 2
  - nicht erlaubte Raw-SQL-Formen -> Exit 2
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
- Fingerprints bleiben stabil bei reiner Umformatierung, Keyword-Case-
  Aenderung und Identifier-Kapitalisierung; unterschiedliche reine
  Klammerstruktur bleibt absichtlich unterscheidbar
- SQL-Erzeugung verwendet weiterhin die originale
  Identifier-Schreibweise des AST; Fingerprint-Normalisierung und
  SQL-Identifierausgabe sind sauber getrennt
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
  (enthaelt auch `DataExportRequest`)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ExportOptionsFingerprint.kt`
- neuer `FilterDslParser` in
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/FilterDslParser.kt`

Voraussichtlich testseitig betroffen:

- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/FilterDslParserTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataExportE2EMysqlTest.kt`

---

## 9. Offene Punkte

### 9.1 Alte Raw-SQL-Checkpoints bleiben ein bewusster Bruch

Der Resume-Bruch fuer alte Raw-SQL-Filter ist beabsichtigt. Wichtig ist
nicht, ihn zu vermeiden, sondern ihn frueh, klar und reproduzierbar zu
kommunizieren.

### 9.2 Klarheit zum Bruchumfang

Nicht jeder klassische SQL-Ausdruck ist automatisch inkompatibel. Als
Bruch gelten nur Ausdruecke ausserhalb der explizit definierten DSL
(`filter_expr`) oder unparsebare Speicherstände in Checkpoints.
Filter wie `status = 'OPEN'`, `created_at >= 0` oder `name IN ('A','B')`
sind DSL-konform und bleiben lauffähig.
