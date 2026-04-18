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


## Findings (Stand: 2026-04-18)

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

- **Mittel — Runner-Methoden zu lang**:
  `DataExportRunner.executeWithPool()` umfasst 477 LOC mit 16 distinkten Phasen
  (Reader-Init, Table-Enumeration, Filter-Resolution, Resume-Kontext, Checkpoint-Lifecycle,
  Staging-Redirect, Streaming-Ausführung, Fehlerbehandlung, Progress-Summary).
  `DataImportRunner.executeWithPool()` umfasst 446 LOC mit 15 distinkten Phasen.
  Beide enthalten 3-fach verschachtelte Kontrollstrukturen (if → if → try/catch).

- **Mittel — Große Parameterverträge**:
  `ExportExecutor.execute()` hat 17 Parameter, `ImportExecutor.execute()` hat 14 Parameter.
  Die Runner-Konstruktoren haben 12-17 Lambda-Parameter für DI.

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
| Lesbarkeit & Namensgebung | 8/10      | = | Exzellente Domänensprache (`SchemaDefinition`, `TableComparator`, `NeutralType` als sealed class mit data objects). Konsistente Benennung über alle Schichten. Idiomatisches Kotlin: sealed classes, Extension Functions, `?.let()`, Set-Algebra (`leftNames - rightNames`), data classes durchgängig, `data object` (Kotlin 1.9+). Keine Java-Style-Antipatterns (kein `lateinit` im Produktionscode, keine manuellen equals/hashCode). Abzug für `executeWithPool` (477/446 LOC), Konstruktoren mit 12-17 Parametern, und `ExportExecutor.execute()` mit 17 Parametern. |
| Modularität & Struktur    | 8/10      | ↑ | Vorbildliche hexagonale Architektur, auf Gradle-Ebene erzwungen. **21 Module** (vorher 18) nach Ports-Split in `ports-common`/`ports-read`/`ports-write`. Core-Modul hat null externe Abhängigkeiten. Dependency Inversion sauber: Application hängt nur von Core und Ports ab, nie von Adaptern. Port-Interfaces fokussiert (kein God-Interface). `AbstractJdbcDataReader` als Template-Methode mit nur 31-38 LOC pro Dialekt-Override. `consumer-read-probe` kann jetzt nur `ports-read` beziehen (Ziel des Splits). Abzug: DataImportRunner (851 LOC) und DataExportRunner (758 LOC) bündeln noch zu viel Orchestrierung. |
| Wartbarkeit               | 7/10      | ↓ | 90% Kover-Minimum auf Kernmodulen (core, alle Drivers, formats, streaming). Testqualität hoch: verhaltensgetriebene Benennung, Kotest FunSpec, Edge Cases, Test Doubles. Fehlerbehandlung konsistent über sealed classes und strukturierte Fehlercodes (E001-E121). Neuen Dialekt hinzufügen = 7 Port-Interfaces + 4 Zeilen Registration. Null FIXME/HACK/XXX-Marker. **Abzug gegenüber v2:** (1) RoutineDdlHelper (3 Dateien, sicherheitskritisch) haben keine Unit-Tests, (2) neue `ports-*`-Module ohne explizite Coverage-Schwellen, (3) kein E2E-Round-Trip-Test, (4) Fehlercodes E006-E121 nicht systematisch gegen Validierungsmatrix getestet. |
| Sicherheit                | 6/10      | ↓ | Profiling-Adapter gehärtet (parametrisierte Queries + Quoting). PreparedStatements in Datenpfaden durchgängig. Zentrales Identifier-Quoting via `SqlIdentifiers`. Credential-Scrubbing via `LogScrubber`. Pfad-Validierung vorhanden. **Abzug gegenüber v2:** Vertiefte Analyse zeigt ein breiteres Interpolations-Muster in DDL-Generierung: CHECK-Constraints (alle 3 Dialekte), Partitions-Ausdrücke (PG+MySQL), Trigger-Bedingungen (PG+SQLite), SpatiaLite-Funktionsaufrufe, View-/Funktions-Bodies. Zwar mit schwächerem Angriffsvektor (Angreifer muss Quellschema kontrollieren), aber systematisch ungeprüft. |

Dateien mit >400 LOC (potenzielle Hotspots):

| Datei | LOC | Längste Funktion | Einschätzung |
| ----- | --- | ---------------- | ------------ |
| DataImportRunner.kt | 851 | executeWithPool: 446 LOC, 15 Phasen | Zerlegung empfohlen |
| DataExportRunner.kt | 758 | executeWithPool: 477 LOC, 16 Phasen | Zerlegung empfohlen |
| ValueDeserializer.kt | 647 | dispatch: 59 LOC, 16 Branches | Akzeptabel (Type-Dispatch) |
| SqliteDataWriter.kt | 525 | — | Dialekt-spezifisch; erwartbar |
| PostgresDataWriter.kt | 523 | — | Dialekt-spezifisch; erwartbar |
| MysqlDataWriter.kt | 509 | — | Dialekt-spezifisch; erwartbar |
| AbstractJdbcDataReader.kt | 489 | streamTableInternal: 58 LOC | Shared Template; gut |
| SchemaNodeParser.kt | 462 | — | Single Responsibility (Parsing) |
| SqliteDdlGenerator.kt | 458 | — | Dialekt-spezifisch; erwartbar |
| MysqlDdlGenerator.kt | 446 | — | Dialekt-spezifisch; erwartbar |
| SchemaCompareRunner.kt | 403 | — | Fokussierter Use Case |


## Konkrete Verbesserungen

### Offen

- **DDL-Interpolation systematisch absichern**: Die verschiedenen Interpolations-Stellen
  (CHECK-Constraints, Partitions-Ausdrücke, Trigger-Bedingungen, SpatiaLite-Aufrufe,
  View-/Funktions-Bodies) sollten einheitlich behandelt werden.
  Vorschlag: Schema-Metadaten, die in DDL-Output fließen, über eine zentrale
  `DdlSanitizer`- oder `TrustedExpression`-Abstraktion leiten.
  Mindestens: SpatiaLite-Identifier quoten (`SqliteDdlGenerator.kt:201`),
  Partition-Werte validieren, Trigger-Bedingungen auf bekannte Muster prüfen.

- **Runner-Zerlegung**: `executeWithPool()` in beiden Runnern in Schrittfunktionen aufteilen,
  z.B. `resolveResumeContext()`, `buildCallbacks()`, `executeStreaming()`, `finalizeAndReport()`.
  Reduziert die 477/446-LOC-Methoden auf je ~50-80 LOC und eliminiert 3-fach-Verschachtelung.

- **Executor-Parameter gruppieren**: `ExportExecutor.execute()` (17 Params) und
  `ImportExecutor.execute()` (14 Params) über Kontext-DTOs entschärfen,
  z.B. `ExportContext(pool, reader, lister, factory)` und `ExportCallbacks(onTable, onChunk)`.

- **MySQL-TODO-Platzhalter eliminieren** (Milestone: 0.9.2):
  Die verbleibenden 4 `-- TODO`-Kommentare in `MysqlDdlGenerator`
  durch rein strukturierte `ManualActionRequired`-Einträge ersetzen.

- **E2E-Round-Trip-Test** (Milestone: 0.9.2, verankert in `docs/roadmap.md`):
  Einen Integrationstest ergänzen, der den vollen Kreislauf
  DB→Export→Format→Import→DB→Schema-Vergleich durchspielt.

- **Fehlercodes E006-E121 gegen Validierungsmatrix testen** (Milestone: 0.9.2):
  Systematisch prüfen, dass jeder dokumentierte Fehlercode mindestens
  einen Test hat, der ihn auslöst.

- **`--filter` härten** (Milestone: 1.0.0, optional):
  Als `--unsafe-filter` umbenennen oder minimale Filter-DSL anbieten.

### Erledigt

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
