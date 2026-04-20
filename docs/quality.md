# Qualität

## KI Prompt

Analysiere den Code hinsichtlich seiner Qualität.

Bitte bewerte die folgenden Metriken auf einer Skala von 1-10 und begründe die Bewertung:
- Lesbarkeit & Namensgebung: (Sind Variablen/Funktionen klar benannt?)
- Modularität & Struktur: (Zu komplex? Solid-Prinzipien eingehalten?)
- Wartbarkeit: (Ist der Code leicht erweiterbar?)
- Sicherheit: (Gibt es potenzielle Schwachstellen, z. B. Injection-Risiken?)

Zusatzaufgaben:
- Schlage konkrete Verbesserungen vor, um die Qualität zu erhöhen.


## Findings (Stand: 2026-04-19)

### Aktualisierung 19.04.2026 (fokussiert auf Codequalität)

| Metrik | Bewertung | Begründung |
| --- | ---: | --- |
| Lesbarkeit & Namensgebung | 8/10 | Domainnahe Namen und konsistente Terminologie; CLI, Runner und Ports sind gut lesbar. Verbesserungspotenzial durch weitere Kürzung großer Orchestrierungsblöcke. |
| Modularität & Struktur | 8/10 | Hexagonale Schichtung ist in der Breite stabil. Registry- und Interface-Layer sind klar getrennt. Einige Verantwortlichkeiten sind noch in einzelnen Runnern gebündelt. |
| Wartbarkeit | 7.5/10 | Hohe Testdisziplin und DI fördern Erweiterbarkeit. Potenzial in `DataTransferRunner`/`SchemaCompareRunner` für weitere Schrittzerlegung und wiederverwendbare Validierung. |
| Sicherheit | 6/10 | Prepared Statements und Identifier-Quoting sind korrekt eingesetzt, Credentials werden geschwärzt. Hauptprobleme: `--filter` als Trusted SQL-Input und weitere nicht parametrisierte DDL-Interpolation (CHECK/Partition/Trigger/Body-Kontexte). |

#### Aktive Risiken / nächste Prioritäten

- `--filter` im Transfer bleibt als roher SQL-Eingabepfad mit klarer „Trusted Input“-Dokumentation, aber weiterhin hohes Relevanzpotenzial.
- DDL-Kontexte, die Metadaten roh in SQL einbetten (`constraint.expression`, Trigger-Conditionen, Routine-Bodies, Partition-Teile), sind weiterhin ein systematischer Unsicherheitsfokus.
- DB-Sicherheitsdefaults in Connection/Hikari-Pfaden sollten stärker als Policy dokumentiert und gegen unsichere Optionen absichern.
- Große CLI/Runner-Methoden haben funktionale Schichtwechsel in einem Durchlauf; weitere Zerlegung erhöht Testschärfe und Fehlersuche.

#### Konkrete Maßnahmen (Top)

- `--filter` als `--unsafe-filter` mit expliziter Warnung führen oder in strukturierte, erlaubte DSL überführen.
- Einheitliches Validierungs- und Quoting/Parametrisierungs-Framework für alle SQL-Generationspfade aufbauen.
- Security-Policy für JDBC-Defaults definieren (TLS/Trust/Auth-Optionen, Verbot unsicherer Fallbacks).
- Datenfluss für Schema-/Checkpoint-Pfade auf Canonical-Path/Allowlist absichern und Symlink-/Traversal-Risiken gezielt testen.
- Runner-Orchestrierung in benannte, einzeln testbare Schritte weiter splitten.
- Routine-/DDL-Generatoren mit property-based und Fuzz-Tests auf Edge-Cases prüfen.

### Sicherheit

- **Hoch — DDL-Interpolation von Schema-Metadaten**:
  Die DDL-Generatoren interpolieren mehrere Felder aus dem Schema-Modell direkt in generiertes SQL,
  ohne diese zu validieren oder zu escapen. Das betrifft nicht nur DOMAIN-CHECK-Constraints,
  sondern ein breiteres Muster:

  **CHECK-Constraints** (alle 3 Generatoren):
  `constraint.expression` wird überall roh in `CHECK (...)` eingesetzt.
  `PostgresDdlGenerator.kt:229`, `MysqlDdlGenerator.kt:234`, `SqliteDdlGenerator.kt:302`.
  Zusätzlich `typeDef.check` bei PostgreSQL-DOMAIN-Typen: `PostgresDdlGenerator.kt:49`.

  **Partitions-Ausdrücke** (PostgreSQL + MySQL):
  `partition.from`, `partition.to` und `partition.values` werden direkt interpoliert.
  `PostgresDdlGenerator.kt:260-269`, `MysqlDdlGenerator.kt:282-287`.

  **Trigger-Bedingungen** (PostgreSQL + SQLite):
  `trigger.condition` wird roh in `WHEN (...)` eingesetzt.
  `PostgresRoutineDdlHelper.kt:201`, `SqliteRoutineDdlHelper.kt:146`.

  **SpatiaLite-Funktionsaufrufe** (SQLite):
  Tabellen- und Spaltennamen werden ohne Quoting in `AddGeometryColumn()` interpoliert.
  `SqliteDdlGenerator.kt:201`.

  **View-/Funktions-/Prozedur-Bodies** (alle 3 Generatoren):
  Query-Bodies werden nach Regex-Transformation roh in `CREATE VIEW/FUNCTION/PROCEDURE` eingesetzt.
  `PostgresRoutineDdlHelper.kt:31-34`, `MysqlRoutineDdlHelper.kt:43`, `SqliteRoutineDdlHelper.kt:44`.

  **Trust-Boundary-Einordnung**: Die Daten stammen aus Schema-Reverse-Engineering (Quell-DB)
  oder aus Schema-YAML-Dateien. Der Angreifer müsste das Quellschema oder die YAML-Datei kontrollieren.
  Das ist ein schwächerer Angriffsvektor als direkte Nutzereingabe, aber bei Migration
  von nicht vertrauenswürdigen Datenbanken oder manipulierten Schema-Dateien real.

- **Mittel — `--filter` akzeptiert rohes SQL** (dokumentierte Design-Entscheidung):
  WhereClause wird ohne Parametrisierung übernommen.
  Trust-Boundary ist die lokale Shell (nur CLI-Operator hat Zugriff).
  Dokumentiert in `DataExportCommand.kt:62-66` und `ImpPlan-0.9.1-A.md §4.3`.
  Seit v1 ergänzt: `containsLiteralQuestionMark()`-Validierung und expliziter Trusted-Input-Kommentar.
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt:57-72`.

- **Mittel — MySQL-DDL enthält 4 `-- TODO`-Platzhalter** (teilweise behoben):
  Composite Types, Sequences, EXCLUDE-Constraints, nicht unterstützte Index-Typen.
  Jetzt mit `ManualActionRequired`-Metadaten gekoppelt.
  PostgreSQL- und SQLite-DDL-Generatoren sind vollständig bereinigt.
  `MysqlDdlGenerator.kt:31`, `:51`, `:247`, `:315`.

- **Niedrig — Regex-basierte View-Query-Transformation**:
  `ViewQueryTransformer.kt:9-34` könnte bei verschachtelten Funktionen
  oder SQL in String-Literalen Edge Cases verfehlen.

### Struktur & Komplexität

- ~~**Mittel — Runner-Methoden zu lang**~~:
  ✅ Behoben in 0.9.2 AP 6.6: `DataExportRunner.executeWithPool()` von 477 auf 26 LOC,
  `DataImportRunner.executeWithPool()` von 446 auf 24 LOC — jeweils in benannte
  Schrittfunktionen zerlegt.

- ~~**Mittel — Große Parameterverträge**~~:
  ✅ Behoben in 0.9.2 AP 6.6: `ExportExecutor.execute()` von 16 auf 4 Parameter
  (DTOs: `ExportExecutionContext`, `ExportExecutionOptions`, `ExportResumeState`,
  `ExportCallbacks`), `ImportExecutor.execute()` von 14 auf 4 analog.

- **Niedrig — ValueDeserializer.dispatch() hat 16 when-Branches**:
  Fachlich begründet (JDBC-Type-Dispatch), aber die Funktion ist mit 59 LOC und
  verschachtelten when-Ausdrücken (3 Ebenen in `toOther()`) schwer zu scannen.

### Testabdeckung

- **Mittel — RoutineDdlHelper ohne direkte Unit-Tests**:
  `PostgresRoutineDdlHelper.kt`, `MysqlRoutineDdlHelper.kt`, `SqliteRoutineDdlHelper.kt`
  haben keine eigenen Tests. Dort liegt die sicherheitskritische View-/Trigger-/Prozedur-Generierung.

- **Niedrig — Neue `ports-*`-Module haben niedrige Test-Ratio**:
  `ports-common` (11 Main / 1 Test), `ports-read` (14 / 1), `ports-write` (26 / 4).
  Erklärbar: überwiegend Interfaces und data classes. Aber kein expliziter Kover-Threshold gesetzt —
  sie erben nur den Root-Threshold von 90%.

- **Niedrig — Kein End-to-End-Round-Trip-Test**:
  Es gibt keinen Test, der DB→Export→Format→Import→DB→Vergleich durchspielt.
  Integrationstests decken DataReader/DataWriter/SchemaSync einzeln ab,
  nicht den vollen Kreislauf.

### Erledigt (gegenüber v1-Analyse)

- SQL-Interpolation in Profiling-Adaptern: behoben.
  Adapter in eigene Module (`driver-*-profiling`) verschoben, nutzen jetzt parametrisierte Queries + `qi()`/`ql()`-Quoting via `SqlIdentifiers`.
- SchemaComparator: von 656 auf 316 LOC reduziert (52% Reduktion).
- Dialekt-Duplizierung in Profiling: durch separate Module und zentrale `SqlIdentifiers`-Utility entschärft.
- TODO-Platzhalter in PostgreSQL- und SQLite-DDL: vollständig bereinigt.
- Constraint-Expressions: `constraint.expression` als Trusted Input dokumentiert.


## Bewertung

Die Bewertung basiert auf den Kernmodulen in hexagon/core, hexagon/application und den DB-/CLI-Adaptern.

| Metrik                    | Bewertung | Trend | Begründung |
| ------------------------- | --------- | ----- | ---------- |
| Lesbarkeit & Namensgebung | 8/10      | = | Exzellente Domänensprache (`SchemaDefinition`, `SchemaComparator`, `TableComparator`) und konsistente Benennung über alle Schichten. Kotlin-idiomatischer Stil mit `sealed`/`data class` und klaren Ports. Leicht abfällig durch noch große Orchestrierungsmethoden im Transfer- und Compare-Flow. |
| Modularität & Struktur    | 8/10      | ↑ | Hexagonale Architektur und Modultrennung sind konsistent, Kern bleibt testbar und entkoppelt. Stabile Interfaces (`ports-*`) und klarer Runner-Adapter-Fluss. Abzugspotenzial bleibt bei einigen zentralen Flows, die noch viel Inline-Logik kombinieren. |
| Wartbarkeit               | 7.5/10    | = | 90% Kover auf Kernmodulen, klare DI, gute Testkultur und gute Fehlermodelle. Verbesserte Runner-Schrittabstraktion bereits umgesetzt. Restpotenzial liegt weiterhin bei weiteren Refaktorings in zentralen Flows und weniger impliziten Annahmen bei Config-/Path-Pfaden. |
| Sicherheit                | 6/10      | ↓ | Prepared Statements und Identifier-Quoting sind stark umgesetzt, Credentials werden geschwärzt. Kritische Restbereiche: roher `--filter` als Trusted-Input (auch nach Zusatzvalidierung) und uneinheitlich parametrisierte/escaped DDL-Pfade (CHECK/Partition/Trigger/Routine-Bodies). |

Dateien mit 200+ LOC (potenzielle Hotspots, sortiert nach LOC absteigend, Stand 2026-04-19):

| Datei | LOC | Längste Funktion | Einschätzung |
| ----- | --- | ---------------- | ------------ |
| ValueDeserializer.kt | 647 | dispatch: 59 LOC, 16 Branches | Akzeptabel (Type-Dispatch). |
| SqliteDataWriter.kt | 525 | — | Dialekt-spezifisch; erwartbar. |
| PostgresDataWriter.kt | 523 | — | Dialekt-spezifisch; erwartbar. |
| MysqlDataWriter.kt | 509 | — | Dialekt-spezifisch; erwartbar. |
| AbstractJdbcDataReader.kt | 489 | streamTableInternal: 58 LOC | Shared Template; gut. |
| DataImportRunner.kt | 473 | executeWithPool: 24 LOC (zerlegt in 8 Schritte) | Kernpipeline stabil, Klasse bleibt groß; gute Schrittabstraktion, weitere Trennung einzelner Verantwortungen sinnvoll. |
| SchemaNodeParser.kt | 463 | — | Single Responsibility (Parsing). |
| SchemaCompareRunner.kt | 403 | — | Fokussierter Use Case. |
| DataExportRunner.kt | 273 | executeWithPool: 26 LOC (zerlegt in 10 Schritte) | Kernpipeline stabil, Klasse bleibt groß; gute Schrittabstraktion, weitere Trennung einzelner Verantwortungen sinnvoll. |
| DataTransferRunner.kt | 208 | execute / Hilfsorchestrierung | Klasse kleiner geworden, weiterhin Transferfluss-Logik im Fokus für weitere fachliche Zerlegung. |


## Konkrete Verbesserungen

### Offen

### Erledigt

- **`--filter` gehärtet** (Milestone: 0.9.3, AP 6.1):
  `--filter` akzeptiert seit 0.9.3 nur noch eine geschlossene DSL (Vergleiche, `IN`, `IS NULL`,
  `AND`/`OR`/`NOT`, Klammern, Arithmetik, Funktions-Allowlist). Alle Literale werden als
  JDBC-Bind-Parameter gebunden. Rohes SQL wird nicht mehr akzeptiert (Exit 2).
  `FilterDslParser` und `FilterDslTranslator` liegen in `hexagon/application`.
  M-R5 (`?`-Check) entfällt, da keine `WhereClause` mehr erzeugt wird.

- ~~E2E-Round-Trip-Test.~~
  Umgesetzt in 0.9.2 AP 6.7: `E2ERoundTripPostgresTest` — Export→Import→Schema/Daten-Vergleich
  gegen zwei PostgreSQL-Testcontainer.

- ~~Fehlercodes E006-E121 gegen Validierungsmatrix testen.~~
  Umgesetzt in 0.9.2 AP 6.7: [`error-code-ledger-0.9.2.yaml`](../ledger/error-code-ledger-0.9.2.yaml) (28 Einträge, alle active oder
  not_applicable) + [`warn-code-ledger-0.9.2.yaml`](../ledger/warn-code-ledger-0.9.2.yaml) (W113, W120).
  Schema: [`code-ledger-0.9.2.schema.json`](../ledger/code-ledger-0.9.2.schema.json).
  Golden-Master-Ausnahmen: [`ddl-single-exceptions-0.9.2.yaml`](../ledger/ddl-single-exceptions-0.9.2.yaml).

- ~~DDL-Interpolation systematisch absichern.~~
  Umgesetzt in 0.9.2 AP 6.5: SpatiaLite-Identifier korrekt escaped,
  Trusted-Input-Grenze für CHECK/Partition/Trigger/Bodies dokumentiert.

- ~~Runner-Zerlegung.~~
  Umgesetzt in 0.9.2 AP 6.6: `DataExportRunner.executeWithPool()` von 477 auf 26 LOC,
  `DataImportRunner.executeWithPool()` von 446 auf 24 LOC — jeweils in benannte Schrittfunktionen zerlegt.

- ~~Executor-Parameter gruppieren.~~
  Umgesetzt in 0.9.2 AP 6.6: `ExportExecutor` von 16 auf 4 Parameter,
  `ImportExecutor` von 14 auf 4 Parameter — über Kontext-DTOs.

- ~~MySQL-TODO-Platzhalter eliminieren.~~
  Umgesetzt in 0.9.2 AP 6.5: Alle 4 `-- TODO`-Kommentare im `MysqlDdlGenerator`
  durch strukturierte `ACTION_REQUIRED`/`WARNING`-Diagnosen ersetzt.

- ~~Vereinheitliche Identifier- und SQL-Erzeugung in einer zentralen Utility pro Dialekt.~~
  Umgesetzt: `SqlIdentifiers` als zentrale Utility; Profiling-Adapter nutzen `qi()`/`ql()` durchgängig.

- ~~Ersetze direkte String-Interpolation in Metadatenabfragen durch PreparedStatement.~~
  Umgesetzt: Alle Profiling-Adapter nutzen parametrisierte Queries + Identifier-Quoting.

- ~~Ziehe gemeinsame Profiling-Logik in abstrakte Basisklassen oder Query-Builder-Helfer hoch.~~
  Umgesetzt: Profiling-Adapter in eigene Module extrahiert; `SqlIdentifiers` als gemeinsame Basis.

- ~~Ersetze TODO-Platzhalter in PostgreSQL- und SQLite-DDL-Generatoren.~~
  Umgesetzt: Beide Generatoren bereinigt; nur MySQL hat noch 4 verbleibende Stellen.

- ~~SchemaComparator zerlegen.~~
  Umgesetzt: Von 656 auf 316 LOC reduziert; `TableComparator` extrahiert.

- ~~Constraint-Expressions dokumentieren.~~
  Umgesetzt: `constraint.expression` und `--filter` als Trusted Input dokumentiert mit Design-Referenz.

- ~~Ports-Modul nach Lese-/Schreib-Verantwortung aufteilen.~~
  Umgesetzt (Phase G-2): `hexagon:ports` → `ports-common` + `ports-read` + `ports-write`.
  Alte Aggregator-Modul (`hexagon:ports`) bleibt als Bridge mit `api()`-Re-Exports.

- ~~RoutineDdlHelper testen.~~
  Umgesetzt: 27 direkte Unit-Tests für alle 3 RoutineDdlHelper (PG/MySQL/SQLite).

- ~~Kover-Schwellen für `ports-*`-Module explizit setzen.~~
  Umgesetzt: minBound(90) für ports-common, ports-read, ports-write.
  Interface-Excludes konfiguriert. Umfangreiche Tests für alle data classes,
  enums, sealed classes und companion-Logik.


## In Summe

Starke Architektur auf Makroebene (21 Module, saubere hexagonale Grenzen, Ports-Split nach Lese-/Schreib-Verantwortung),
branchenführende Testdisziplin (90% pro Kernmodul) und klare Domänensprache.
Die vertiefte Sicherheitsanalyse zeigt jedoch ein systematisches Muster:
Schema-Metadaten (CHECK-Ausdrücke, Partition-Werte, Trigger-Bedingungen, Routine-Bodies)
fließen unvalidiert in DDL-Output. Der Angriffsvektor ist schwächer als bei Nutzereingaben
(Angreifer muss Quellschema kontrollieren), aber die fehlende Absicherung ist konsistent und breit.
Die größten Qualitätshebel liegen in der DDL-Sanitization, der Runner-Zerlegung
und der Testabdeckung für die RoutineDdlHelper.
