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


## Findings

- **Hoch**: 
  In mehreren Profiling-/Introspection-Adaptern werden **table, column und teils schema direkt in SQL interpoliert**. 
  Das ist eine echte Injection-Fläche, falls diese Werte nicht ausschließlich aus vertrauenswürdigen Metadaten kommen. 
  Beispiele: 
  adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/profiling/PostgresSchemaIntrospectionAdapter.kt:16, 
  adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/profiling/MysqlSchemaIntrospectionAdapter.kt:33,
  adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/profiling/MysqlProfilingDataAdapter.kt:18,
  adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/profiling/SqliteSchemaIntrospectionAdapter.kt:35.

- **Mittel**:
  Die Export-/DDL-Pfade akzeptieren bewusst rohe SQL-Fragmente. 
  DataExportHelpers.resolveFilter übernimmt --filter ungeprüft als WhereClause, 
  und die DDL-Generatoren setzen constraint.expression direkt in CHECK/EXCLUDE ein.
  Das ist funktional nachvollziehbar, aber sicherheitstechnisch nur bei vollständig vertrauenswürdigen Eingaben sauber. 
  Beispiele: 
  hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt:59,
  adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt:224, adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:238,
  adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt:294.

- **Mittel**:
  Einige zentrale Klassen sind zu groß und tragen zu viele Verantwortlichkeiten.
  DataImportRunner liegt bei 887 LOC, DataExportRunner bei 923, StreamingImporter bei 792, SchemaComparator bei 656.
  Das schwächt die lokale Verständlichkeit und erhöht das Regressionsrisiko bei Änderungen.
  Beispiel: 
  hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt:163,
  hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt:18.

- **Mittel**:
  Zwischen MySQL-, PostgreSQL- und SQLite-Profiling steckt viel ähnliche Logik mit leicht unterschiedlichen Escape-Strategien. Das erhöht Wartungsaufwand und macht Sicherheitsfehler wahrscheinlicher, weil
  Fixes leicht in nur einem Dialekt landen.

##  Bewertung
Die Bewertung basiert auf den Kernmodulen in hexagon/core, hexagon/application und den DB-/CLI-Adaptern, nicht auf jedem einzelnen File.

| Metrik                    | Bewertung | Begründung                                                                                                                                                                                                                                                                                                                                                    |
| ------------------------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Lesbarkeit & Namensgebung | 7/10      | Die Domänensprache ist gut. Namen wie `SchemaComparator`, `SchemaValidator`, `DataImportRequest` oder `CheckpointStore` sind klar und konsistent. Abzüge gibt es für sehr große Klassen, lange Parameterlisten und starke Kommentar-/Plan-ID-Dichte, die das eigentliche Verhalten schwerer scanbar macht.                                                    |
| Modularität & Struktur    | 7/10      | Die grobe Architektur ist stark. Die hexagonale Trennung ist sauber dokumentiert und im Build sichtbar (`docs/architecture.md:49`, `build.gradle.kts:29`). Innerhalb einzelner Use Cases bricht diese Qualität aber teilweise auf, weil Runner und Import-/Export-Pfade zu viel Orchestrierung, Validierung, Fehlerabbildung und Checkpoint-Handling bündeln. |
| Wartbarkeit               | 6/10      | Positiv sind die strengen Kover-Grenzen von meist `80-90%+` (`hexagon/core/build.gradle.kts:4`, `adapters/driving/cli/build.gradle.kts:150`). **Negativ wirken die Hotspots mit hoher Dateigröße, Dialekt-Duplizierung und mehrere bewusst provisorische Fallback-Pfade in den DDL-Generatoren, die statt ausführbarer DDL nur SQL-Kommentar-Platzhalter wie `-- TODO: ...` erzeugen.** |
| Sicherheit                | 5/10      | Es gibt viele gute Ansätze: Identifier werden oft korrekt gequotet, und viele DB-Zugriffe nutzen `PreparedStatement`. **Der Score sinkt wegen der direkten SQL-Interpolation in Profiling/Introspection sowie der absichtlich offenen Raw-SQL-Schnittstellen**.                                                                                               |

Belege für die Aussage zu provisorischen Fallback-Pfaden in DDL-Generatoren:

- `PostgresDdlGenerator` erzeugt bei nicht kompatiblen Views/Functions/Procedures/Triggers explizit SQL-Kommentar-Platzhalter wie `-- TODO: Rewrite ...` bzw. `-- TODO: Implement ...` statt lauffähiger DDL:
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt:342`,
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt:374`,
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt:390`,
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt:440`,
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt:456`,
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt:503`,
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt:521`.
- `MysqlDdlGenerator` enthält dieselbe provisorische Strategie für nicht unterstützte oder manuell nachzuarbeitende Fälle:
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:25`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:51`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:259`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:327`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:419`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:459`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:475`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:532`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:548`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:596`,
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt:612`.
- `SqliteDdlGenerator` nutzt ebenfalls solche SQL-Kommentar-Platzhalter bei manuell zu implementierenden bzw. umzuschreibenden Trigger-/View-Fällen:
  `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt:425`,
  `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt:520`,
  `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt:537`.

##  Konkrete Verbesserungen

- Vereinheitliche Identifier- und SQL-Erzeugung in einer zentralen Utility pro Dialekt.
  Alle Profiling-/Introspection-Adapter sollten nur noch darüber bauen.

- Ersetze direkte String-Interpolation in Metadatenabfragen durch PreparedStatement, wo Werte als Literale eingebracht werden, 
  und durch konsequentes Identifier-Quoting, wo Namen eingebracht werden müssen.

- Zerlege DataImportRunner in kleinere Dienste, 
  z. B. ImportRequestValidator, ImportSourceResolver, ResumeCoordinator, ManifestCoordinator, ImportExecutionService.

- Ziehe gemeinsame Profiling-Logik in abstrakte Basisklassen oder kleine Query-Builder-Helfer hoch, 
  damit PostgreSQL/MySQL/SQLite nicht dieselben Fehler unabhängig reproduzieren.

- Reduziere Plan-/Milestone-Kommentare im Produktionscode. Solche Historie gehört eher in docs/; 
  im Code sollten vor allem why und Invarianten stehen.

- Härte die unsicheren Schnittstellen explizit ab: --filter z. B. als --unsafe-filter kennzeichnen 
  oder eine kleine Filter-DSL anbieten. 
  Für constraint.expression sollte mindestens klar dokumentiert sein, dass dies Trusted Input ist.
  
- Ergänze Sicherheitstests mit absichtlich bösartigen Tabellen-/Spaltennamen, damit Escape-/Quoting-Fehler sofort auffallen.

- Ersetze die provisorischen SQL-Kommentar-Platzhalter `-- TODO: ...` in den DDL-Generatoren durch einen fachlich sauberen Ergebnisweg:
  nicht als `DdlStatement.sql`, sondern als strukturierte `SkippedObject`-/`TransformationNote`- oder besser `ManualActionRequired`-Einträge.
  So bleibt generierte DDL ausführbar, und manuelle Nacharbeit wird separat ausgewiesen.

- Trenne im DDL-Ergebnis explizit zwischen ausführbaren Statements und manuellen Nacharbeiten,
  z. B. über getrennte Listen wie `generatedStatements` und `manualActionsRequired`.
  Zusätzlich sollten die Ursachen fachlich getrennt behandelt werden:
  `sourceDialect != targetDialect` als Rewrite-Fall,
  „Feature vom Dialekt nicht unterstützt“ als Capability-Fall
  und `body == null` zumindest daraufhin geprüft werden, ob der Fall bereits im Modell- oder Validierungspfad früher abgefangen werden sollte.

- Führe explizite Dialekt-Capabilities ein, damit Generatoren konsistent entscheiden können,
  ob ein Objekt generiert, konvertiert, übersprungen oder als manuelle Nacharbeit gemeldet wird.

- Schneide die DDL-Generatoren feiner nach Objektarten,
  z. B. in `ViewDdlGenerator`, `FunctionDdlGenerator`, `ProcedureDdlGenerator` und `TriggerDdlGenerator`,
  statt die Fallback- und Rewrite-Logik in großen monolithischen Generator-Dateien zu bündeln.
  Das reduziert Kopplung, vereinfacht Tests und macht Capability-/Rewrite-Regeln lokaler verständlich.

- Verbessere die Ausgabe für Nutzer:
  Die eigentliche SQL-Datei sollte nur ausführbare Statements enthalten.
  Manuelle Nacharbeiten sollten stattdessen in einem separaten Report-Block oder einer separaten Ergebnisliste erscheinen,
  z. B. als `manualActionsRequired` mit Objekt, Grund und Hinweis zur Nacharbeit.

- Ergänze Tests dafür, dass Unsupported-/Rewrite-Fälle nicht mehr als pseudo-ausführbare DDL erscheinen,
  sondern ausschließlich in den strukturierten Nacharbeits-/Skipped-Ergebnissen und der separaten Nutzer-Ausgabe auftauchen.


## In Summe
Gute Architektur auf Makroebene, starke Testkultur und klare Domänensprache. 
Die größten Qualitätshebel liegen nicht im Gesamtdesign, sondern in Sicherheits-Härtung 
und im Zerlegen der großen Orchestrierungs- und Dialektklassen.
