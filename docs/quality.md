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

- **Hoch**:
  PostgreSQL-DOMAIN-CHECK-Constraints werden in `PostgresDdlGenerator` direkt per `append(" CHECK (${typeDef.check})")` interpoliert.
  Ein bösartiges Schema könnte darüber beliebiges SQL in die generierte DDL einschleusen.
  Beispiel:
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt:49`.

- **Mittel** (dokumentierte Design-Entscheidung):
  `--filter` akzeptiert rohes SQL als WhereClause ohne Parametrisierung.
  Trust-Boundary ist die lokale Shell (nur CLI-Operator hat Zugriff).
  Dokumentiert in `DataExportCommand.kt:62-66` und `ImpPlan-0.9.1-A.md §4.3`.
  Seit letzter Analyse ergänzt: `containsLiteralQuestionMark()`-Validierung und expliziter Trusted-Input-Kommentar.
  Beispiel:
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt:57-72`.

- **Mittel** (teilweise behoben):
  MySQL-DDL-Generator enthält noch 4 `-- TODO`-Platzhalter als pseudo-ausführbare SQL-Kommentare
  (Composite Types, Sequences, EXCLUDE-Constraints, nicht unterstützte Index-Typen).
  Jetzt mit `ManualActionRequired`-Metadaten gekoppelt.
  PostgreSQL- und SQLite-DDL-Generatoren sind vollständig bereinigt.
  Beispiele:
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:31`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:51`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:247`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:315`.

- **Mittel** (verbessert):
  DataImportRunner (851 LOC) und DataExportRunner (758 LOC) bündeln weiterhin
  CLI-Parsing, Validierung, Connection-Management, Resume-Logik und Orchestrierung.
  Gegenüber letzter Analyse um 36 bzw. 165 LOC reduziert.
  Durch Constructor-Injection mit 12-17 Lambda-Parametern gut testbar,
  aber die Funktionslänge (z.B. `executeWithPool` ~476 LOC) erschwert lokale Verständlichkeit.
  Beispiele:
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`,
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`.

- **Niedrig**:
  Regex-basierte View-Query-Transformation in `ViewQueryTransformer.kt:9-34`
  könnte bei verschachtelten Funktionen oder SQL in String-Literalen Edge Cases verfehlen.
  Beispiel:
  `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/ViewQueryTransformer.kt:9-34`.

- **Erledigt** (gegenüber vorheriger Analyse):
  - SQL-Interpolation in Profiling-Adaptern: behoben.
    Adapter in eigene Module (`driver-*-profiling`) verschoben, nutzen jetzt parametrisierte Queries + `qi()`/`ql()`-Quoting via `SqlIdentifiers`.
  - SchemaComparator: von 656 auf 316 LOC reduziert (52% Reduktion).
  - Dialekt-Duplizierung in Profiling: durch separate Module und zentrale `SqlIdentifiers`-Utility entschärft.
  - TODO-Platzhalter in PostgreSQL- und SQLite-DDL: vollständig bereinigt.
  - Constraint-Expressions: `constraint.expression` jetzt als Trusted Input dokumentiert.


## Bewertung

Die Bewertung basiert auf den Kernmodulen in hexagon/core, hexagon/application und den DB-/CLI-Adaptern, nicht auf jedem einzelnen File.

| Metrik                    | Bewertung | Begründung |
| ------------------------- | --------- | ---------- |
| Lesbarkeit & Namensgebung | 8/10      | Exzellente Domänensprache (`SchemaDefinition`, `TableComparator`, `NeutralType` als sealed class mit data objects). Konsistente Benennung über alle Schichten. Idiomatisches Kotlin: sealed classes, Extension Functions, `?.let()`, Set-Algebra (`leftNames - rightNames`), data classes durchgängig. Abzug für zu lange Funktionen (`DataExportRunner.executeWithPool` ~476 LOC) und Konstruktoren mit 12-17 Parametern in den Runner-Klassen. |
| Modularität & Struktur    | 8/10      | Vorbildliche hexagonale Architektur, auf Gradle-Ebene erzwungen. Core-Modul hat **null externe Abhängigkeiten**. 18 sauber getrennte Module. Port-Interfaces sind fokussiert (kein God-Interface). `AbstractJdbcDataReader` als Template-Methode mit nur 31-38 LOC pro Dialekt-Override ist exzellent. Dependency Inversion sauber: Application hängt nur von Core und Ports ab, nie von Adaptern. Abzug: DataImportRunner (851 LOC) und DataExportRunner (758 LOC) bündeln noch zu viel Orchestrierung. |
| Wartbarkeit               | 8/10      | 90% Kover-Minimum auf 15 von 16 Modulen (CLI: 80%, Profiling: 85%). Testqualität hoch: verhaltensgetriebene Benennung, Kotest FunSpec, Edge Cases, Test Doubles statt übermäßiger Mocks. Fehlerbehandlung konsistent über sealed classes (`FinishTableResult.Success | PartialFailure`) und Result-Typen mit strukturierten Fehlercodes (E001-E121). Neuen Dialekt hinzufügen = 7 Port-Interfaces + 4 Zeilen Registration. Null FIXME/HACK/XXX-Marker im gesamten Produktionscode. |
| Sicherheit                | 7/10      | Gegenüber vorheriger Analyse deutlich gehärtet. Profiling-Adapter nutzen jetzt parametrisierte Queries + `qi()`/`ql()`-Quoting. PreparedStatements durchgängig. Zentrales Identifier-Quoting über `SqlIdentifiers`. Credential-Scrubbing via `LogScrubber`. Pfad-Validierung vorhanden. **Verbleibende Risiken:** (1) DOMAIN-CHECK-Constraints werden unvalidiert interpoliert (`PostgresDdlGenerator.kt:49`), (2) `--filter` akzeptiert rohes SQL (dokumentierte Trust-Boundary), (3) MySQL-DDL enthält noch 4 `-- TODO`-Platzhalter als pseudo-ausführbare Statements. |

Dateien mit >400 LOC (potenzielle Hotspots):

| Datei | LOC | Einschätzung |
| ----- | --- | ------------ |
| DataImportRunner.kt | 851 | Runner-Zerlegung empfohlen |
| DataExportRunner.kt | 758 | Runner-Zerlegung empfohlen |
| ValueDeserializer.kt | 647 | Single Responsibility (Type-Dispatch); akzeptabel |
| SqliteDataWriter.kt | 525 | Dialekt-spezifisch; erwartbar |
| PostgresDataWriter.kt | 523 | Dialekt-spezifisch; erwartbar |
| MysqlDataWriter.kt | 509 | Dialekt-spezifisch; erwartbar |
| AbstractJdbcDataReader.kt | 489 | Shared Template; gut |
| SchemaNodeParser.kt | 462 | Single Responsibility (Parsing); akzeptabel |
| SqliteDdlGenerator.kt | 458 | Dialekt-spezifisch; erwartbar |
| MysqlDdlGenerator.kt | 446 | Dialekt-spezifisch; erwartbar |
| SchemaCompareRunner.kt | 403 | Fokussierter Use Case |


## Konkrete Verbesserungen

### Offen

- **DOMAIN-CHECK-Validierung**: `typeDef.check` in `PostgresDdlGenerator` gegen Whitelist sicherer Operatoren prüfen
  oder durch SQL-AST-Parsing absichern, damit bösartige Schemata kein SQL einschleusen können.

- **Runner-Zerlegung**: `DataExportRunner.executeWithPool()` (~476 LOC) in Schrittfunktionen aufteilen,
  z.B. `validateInputs()`, `resolveResume()`, `executeStreaming()`, `finalizeOutput()`.
  Analog für `DataImportRunner`.

- **Konstruktor-Parameter gruppieren**: Die 12-17 DI-Parameter der Runner in Service-DTOs bündeln
  (z.B. `ResolverServices`, `CheckpointServices`), um Lesbarkeit zu verbessern ohne Testbarkeit zu opfern.

- **MySQL-TODO-Platzhalter eliminieren**: Die verbleibenden 4 `-- TODO`-Kommentare in `MysqlDdlGenerator`
  durch rein strukturierte `ManualActionRequired`-Einträge ersetzen,
  sodass generierte DDL ausschließlich ausführbare Statements enthält.

- **`--filter` härten** (optional): Entweder als `--unsafe-filter` umbenennen oder eine minimale Filter-DSL anbieten.
  Alternativ: Operator-Whitelist-Validierung als optionalen Strict-Mode.

- **KDoc auf Implementierungen ergänzen**: Port-Interfaces sind exzellent dokumentiert;
  Implementierungsklassen (SchemaReader, DdlGenerator pro Dialekt) fehlt inline-Dokumentation.

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


## In Summe

Starke Architektur auf Makroebene, branchenführende Testdisziplin (90% pro Modul) und klare Domänensprache.
Die Codequalität hat sich seit der vorherigen Analyse signifikant verbessert:
die kritischsten Sicherheitslücken (SQL-Interpolation in Profiling) sind behoben,
die größten Klassen substantiell verkleinert, und die Testabdeckung konsequent angehoben.
Die verbleibenden Hebel sind die DOMAIN-CHECK-Interpolation,
die Runner-Zerlegung und die letzten MySQL-DDL-Platzhalter.
