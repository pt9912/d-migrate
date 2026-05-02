# Refactor 1: Mikro-Komplexitaet und MySQL Secure Defaults

> **Status**: Umgesetzt
> **Prioritaet**: Mittel-Hoch
> **Erstellt**: 2026-04-23
> **Aktualisiert**: 2026-04-23

---

## Problem

Es gibt aktuell zwei getrennte, aber zusammenhaengende Technik-Themen:

1. Einige zentrale Produktionsklassen haben zu viel Verantwortung gebuendelt.
2. Die MySQL-JDBC-Defaults setzen `allowPublicKeyRetrieval=true` als
   Standardwert und sind damit nicht maximal konservativ.

Der erste Punkt verschlechtert Lesbarkeit, Reviewbarkeit und zielgerichtete
Tests. Der zweite Punkt ist kein akuter Exploit, aber ein klarer
`secure-by-default`-Nachteil.

## Aktueller Befund

### 1. Mikro-Komplexitaet in Kernklassen

| Datei | LOC | Beobachtung |
|-------|----:|-------------|
| `hexagon/application/.../DataImportRunner.kt` | 206 | Gegenueber dem Ausgangsstand stark reduziert; Preflight-Resolution, Checkpoint-/Resume-Planung, Streaming-Handoff und Completion/Reporting sind extrahiert. Im Runner verbleibt im Wesentlichen nur noch die Ablaufverdrahtung |
| `hexagon/application/.../DataImportHelpers.kt` | 227 | Reduziert auf Request-/Input-/Target-Aufloesung und Dialekt-Preflight; Completion-/Reporting-Logik liegt jetzt getrennt |
| `hexagon/application/.../ImportCompletionSupport.kt` | 86 | Extrahiert Completion-Bewertung, Checkpoint-Cleanup und Progress-Summary aus dem Import-Helper/Runner-Pfad |
| `hexagon/application/.../FilterDslParser.kt` | 256 | Der boolesche DSL-Parser ist jetzt auf Filter-/Praedikat-Parsing reduziert; Parser-State und Wertausdruckslogik liegen in eigenen Helfern |
| `hexagon/application/.../FilterDslParserSupport.kt` | 67 | Extrahiert Parser-Zustand und Fehlererzeugung aus dem Hauptparser |
| `hexagon/application/.../FilterDslValueParser.kt` | 150 | Extrahiert Wertausdruecke, qualifizierte Identifier und erlaubte Funktionsaufrufe aus der Filter-Grammatik |
| `hexagon/core/.../SchemaValidator.kt` | 46 | Validator ist jetzt Aggregations-Fassade; Struktur- und Spaltenregeln liegen in eigenen, direkt testbaren Regelkomponenten |
| `hexagon/core/.../SchemaStructureValidationRules.kt` | 179 | Neuer Regelblock fuer Tabellen-, Trigger- und View-Strukturpruefungen |
| `hexagon/core/.../SchemaColumnValidationRules.kt` | 248 | Regelblock fuer Typ-, FK-, Default- und Sequenzvalidierung; haelt den Rueckgabevertrag des Validators stabil und ist separat unit-testbar |
| `adapters/driven/driver-mysql/.../MysqlDdlGenerator.kt` | 247 | Die Hauptklasse ist jetzt echte Orchestrierungs-Fassade; Sequence-Emulation sowie Index-/Partition-DDL liegen in eigenen Helfern |
| `adapters/driven/driver-mysql/.../MysqlSequenceDdlSupport.kt` | 279 | Neuer, klar abgegrenzter Sequence-Emulationsblock fuer Support-Tabelle, Routinen, Trigger und zugehoerige Notes/Kollisionen |
| `adapters/driven/driver-mysql/.../MysqlIndexPartitionDdlHelper.kt` | 109 | Kleiner Helper fuer Index- und Partition-DDL; haelt Table-/Index-Details aus dem Generator heraus |
| `adapters/driven/driver-mysql/.../MysqlMetadataQueries.kt` | 258 | Tabellen-/Constraint-/Routine-Queries bleiben gebuendelt, aber der Sequence-Support-Block wurde in eine eigene Hilfskomponente verschoben |
| `adapters/driven/driver-mysql/.../MysqlSequenceSupportMetadataQueries.kt` | 297 | Neuer, fachlich abgegrenzter Sequence-Support-Queryblock; noch kompakt genug, aber klar als eigener Bereich testbar |
| `adapters/driven/driver-mysql/.../MysqlSequenceSupport.kt` | 215 | Orchestriert jetzt nur noch Scan/D2/D3; Row-Mapping sowie Trigger-/Notiz-Materialisierung liegen in eigenen Helfern |
| `adapters/driven/driver-mysql/.../MysqlSequenceSupportSnapshotHelpers.kt` | 256 | Extrahiert numerische Row-Coercion, Snapshot-Mapping und D2-Sequenzvalidierung |
| `adapters/driven/driver-mysql/.../MysqlSequenceSupportMaterialization.kt` | 239 | Extrahiert D3-Triggerauflösung sowie W116-Diagnostik- und Notizbildung |
| `adapters/driven/driver-common/.../ViewQueryTransformer.kt` | 170 | Orchestriert jetzt nur noch Transformationslauf und W111-Erkennung; Tokenisierung und Regelanwendung liegen getrennt |
| `adapters/driven/driver-common/.../ViewQueryTokenSupport.kt` | 133 | Extrahiert Tokenmodell und Tokenisierung der View-Query-Transformation |
| `adapters/driven/driver-common/.../ViewQueryTransformationRules.kt` | 291 | Extrahiert die regelbasierte Funktions-/Token-Transformation aus der Hauptklasse |
| `adapters/driven/driver-common/.../data/AbstractJdbcDataReader.kt` | 230 | Reader-Lifecycle und SQL-Aufbau bleiben zentral, aber Select-Query-Helfer und Chunk-Lifecycle sind ausgelagert |
| `adapters/driven/driver-common/.../data/JdbcSelectQuerySupport.kt` | 91 | Extrahiert Projektion, WHERE-Sammlung und Resume-Marker-Fragmentbau aus dem Reader |
| `adapters/driven/driver-common/.../data/JdbcChunkSequence.kt` | 128 | Extrahiert den JDBC-Chunk-Lifecycle und Empty-Table-Vertrag aus der Reader-Hauptklasse |
| `adapters/driven/formats/.../SchemaNodeParser.kt` | 30 | Reduziert auf Root-Orchestrierung; Struktur- und Objektparser sowie gemeinsame JsonNode-Helfer sind getrennt |
| `adapters/driven/formats/.../SchemaNodeStructureParsers.kt` | 206 | Extrahiert Tabellen, Spalten, Defaults, Referenzen, Constraints, Partitioning und Custom Types aus dem Parser-Hauptpfad |
| `adapters/driven/formats/.../SchemaNodeProgrammabilityParsers.kt` | 104 | Extrahiert Procedures, Functions, Views, Triggers, Sequences und Dependencies |
| `adapters/driven/formats/.../SchemaNodeParserSupport.kt` | 45 | Extrahiert gemeinsame JsonNode-Feld- und Objekt-Helfer |
| `adapters/driven/formats/.../SchemaNodeBuilder.kt` | 49 | Reduziert auf Root-Orchestrierung; Struktur- und Programmability-Builder sowie gemeinsame Typ-/Array-Helfer sind getrennt |
| `adapters/driven/formats/.../SchemaNodeStructureBuilders.kt` | 194 | Extrahiert Tabellen, Spalten, Defaults, Referenzen, Constraints, Partitioning und Custom Types aus dem Builder-Hauptpfad |
| `adapters/driven/formats/.../SchemaNodeProgrammabilityBuilders.kt` | 167 | Extrahiert Procedures, Functions, Views, Triggers, Sequences und Dependencies aus dem Builder-Hauptpfad |
| `adapters/driven/formats/.../SchemaNodeBuilderSupport.kt` | 83 | Extrahiert gemeinsame String-Array- und NeutralType-Bausteine fuer die Schema-Ausgabe |
| `adapters/driven/driver-postgresql/.../PostgresDataWriter.kt` | 139 | Reduziert auf `openTable`, Session-Bootstrap und Metadaten-Laden; die tabellenbezogene Write-Session liegt separat |
| `adapters/driven/driver-postgresql/.../PostgresTableImportSession.kt` | 287 | Extrahiert den eigentlichen PostgreSQL-Importpfad mit Insert-/Upsert-SQL, Value-Binding und Cleanup aus dem Writer |
| `adapters/driven/driver-postgresql/.../PostgresSchemaReader.kt` | 73 | Reduziert auf Read-Orchestrierung; Struktur-/Typreader und Programmability-Reader liegen getrennt |
| `adapters/driven/driver-postgresql/.../PostgresSchemaStructureReaders.kt` | 194 | Extrahiert Tabellen, Partitioning, Sequences und Custom Types aus dem Reader-Hauptpfad |
| `adapters/driven/driver-postgresql/.../PostgresSchemaProgrammabilityReaders.kt` | 130 | Extrahiert Views, Routinen, Trigger und Routine-Parameter-Mapping aus dem Reader-Hauptpfad |
| `adapters/driven/driver-sqlite/.../SqliteDdlGenerator.kt` | 70 | Reduziert auf Orchestrierung; Tabellen-/Spatial-/Index-DDL sowie Nicht-Unterstützungs- und Invertierungsfälle liegen getrennt |
| `adapters/driven/driver-sqlite/.../SqliteTableDdlSupport.kt` | 191 | Extrahiert Tabellen-DDL, SpatiaLite-Sonderpfad, Geometrie-Blocker und Index-DDL aus dem Generator |
| `adapters/driven/driver-sqlite/.../SqliteCapabilityDdlSupport.kt` | 171 | Extrahiert Custom-Type-/Sequence-Skips, zirkuläre FK-Hinweise und SQLite-spezifische Rollback-Inversion |
| `adapters/driven/streaming/.../TableImporter.kt` | 184 | Reduziert auf Reader-/Session-Lifecycle und Reporting; Binding- und Chunk-Loop-Logik liegen getrennt |
| `adapters/driven/streaming/.../TableImportBindingSupport.kt` | 112 | Extrahiert Header-/Target-Binding, Schema-Mismatch-Prüfung und Deserializer-Aufbau |
| `adapters/driven/streaming/.../TableImportLoopSupport.kt` | 271 | Extrahiert Chunk-Normalisierung, Write-/Rollback-/Commit-Loop und Error-Policy-Handling |
| `adapters/driven/formats/.../data/yaml/YamlChunkReader.kt` | 115 | Reduziert auf Reader-Orchestrierung; Event-Cursor und Schema-/Scalar-Support liegen getrennt |
| `adapters/driven/formats/.../data/yaml/YamlEventCursor.kt` | 193 | Extrahiert SnakeYAML-Prolog, Event-Cursor, Nested-Value-Lesen und Tail-Validierung |
| `adapters/driven/formats/.../data/yaml/YamlSchemaSupport.kt` | 45 | Extrahiert First-Row-Schema-Binding, Row-Normalisierung und YAML-Core-Scalar-Auflösung |
| `adapters/driven/driver-postgresql/.../PostgresMetadataQueries.kt` | 80 | Reduziert auf API-Fassade; Tabellen-/Constraint-, Typ- und Programmability-Queries liegen getrennt |
| `adapters/driven/driver-postgresql/.../PostgresTableMetadataQueries.kt` | 218 | Extrahiert Tabellen, Constraints, Indizes, Sequenzen, Partitioning und Extensions aus dem Metadaten-Hauptpfad |
| `adapters/driven/driver-postgresql/.../PostgresTypeMetadataQueries.kt` | 59 | Extrahiert Enum-, Domain- und Composite-Type-Queries |
| `adapters/driven/driver-postgresql/.../PostgresProgrammabilityMetadataQueries.kt` | 95 | Extrahiert Views, Funktions-/Prozedurmetadaten, Routine-Parameter, Trigger und View-Dependencies |
| `adapters/driven/driver-postgresql/.../PostgresDdlGenerator.kt` | 253 | Reduziert auf DDL-Orchestrierung fuer Tabellen, Constraints, Routinen und Trigger; Custom-Type-/Sequence-DDL liegt getrennt |
| `adapters/driven/driver-postgresql/.../PostgresTypeSequenceDdlSupport.kt` | 69 | Extrahiert PostgreSQL-Custom-Type- und Sequence-DDL aus dem Generator-Hauptpfad |
| `adapters/driven/driver-sqlite/.../SqliteDataWriter.kt` | 148 | Reduziert auf Writer-API, `openTable(...)` und Connection-/FK-Setup; die tabellenbezogene Import-Session liegt getrennt |
| `adapters/driven/driver-sqlite/.../SqliteTableImportSession.kt` | 210 | Extrahiert SQLite-spezifische Insert-/Upsert-Ausführung, Batch-/PK-Existenzprüfung und Finish-/Cleanup-Logik |
| `hexagon/application/.../ToolExportRunner.kt` | 266 | Reduziert auf Preflight, Export-Orchestrierung und Kollisions-/Note-Handling; Report-Sidecar-Daten und Rendering liegen getrennt |
| `hexagon/application/.../ToolExportReportSupport.kt` | 93 | Extrahiert Report-Sidecar-Datenmodell und YAML-Rendering aus dem Runner |
| `hexagon/application/.../SchemaGenerateRunner.kt` | 269 | Reduziert auf Preflight, Generator-Aufruf, Split-/Diagnostik-Checks und Output-Routing; die Output-Schicht liegt getrennt |
| `hexagon/application/.../SchemaGenerateOutputSupport.kt` | 110 | Extrahiert File-/Stdout-/Report-Ausgabe und Rollback-/Split-Dateischreiben aus dem Runner |
| `adapters/driven/driver-common/.../AbstractDdlGenerator.kt` | 211 | Reduziert auf die DDL-Orchestrierung; Topological Sort, View-Phasenklassifikation und Skip-/Phase-Helfer liegen getrennt |
| `adapters/driven/driver-common/.../DdlGenerationSupport.kt` | 126 | Extrahiert Topological Sort, View-Abhaengigkeitsauflosung und wiederverwendete Skip-/Phase-Helfer aus dem abstrakten Generator |
| `adapters/driven/driver-mysql/.../MysqlDataWriter.kt` | 125 | Reduziert auf `openTable(...)`, Zielmetadaten und FK-/Truncate-Setup; die tabellenbezogene Import-Session liegt getrennt |
| `adapters/driven/driver-mysql/.../MysqlTableImportSession.kt` | 197 | Extrahiert MySQL-spezifische Insert-/Upsert-Ausfuehrung, Value-Binding und Finish-/Cleanup-Logik |

Zur Priorisierung wird derzeit repo-weit ueber `*/src/main/kotlin/**/*.kt`
gezaehlt. Mit

```bash
find . -path '*/src/main/kotlin/*' -name '*.kt' -not -path '*/build/*' -print0 \
  | xargs -0 wc -l \
  | awk '$2 != "total" { if ($1>300) gt300++; if ($1>=400) ge400++ } END { print gt300, ge400 }'
```

ergab der Stand vom 2026-04-23: 0 Dateien `>300` LOC und 0 Dateien
`>=400` LOC. Wenn ein engerer Scope verwendet wird, muss die Zaehlmethode im
Dokument mitgenannt werden.

### 2. MySQL-Default `allowPublicKeyRetrieval=true`

Der Default wurde im Refactoring bereits an den beiden produktiven
Injektionsstellen entfernt:

| Datei | Rolle |
|-------|-------|
| `adapters/driven/driver-mysql/.../MysqlJdbcUrlBuilder.kt` | produktiver MySQL-URL-Builder |
| `adapters/driven/driver-common/.../HikariConnectionPoolFactory.kt` | Fallback-URL-Builder fuer MySQL |

Damit ist die fruehere Code-vs-Spec-Divergenz aufgeloest:
`spec/connection-config-spec.md` dokumentiert `allowPublicKeyRetrieval`
mit Default `false`, und der produktive Code folgt diesem Default jetzt.

Die dazugehoerigen Tests wurden ebenfalls umgestellt:

| Datei | Neuer Fokus |
|-------|--------------------|
| `adapters/driven/driver-mysql/.../MysqlJdbcUrlBuilderTest.kt` | Default enthaelt den Parameter nicht, explizites Opt-in wird uebernommen |
| `adapters/driven/driver-common/.../HikariJdbcUrlTest.kt` | Fallback-Builder injiziert den Parameter nicht implizit, Opt-in bleibt erhalten |
| `test/integration-mysql/.../*.kt` | reale MySQL-Tests setzen den Parameter dort explizit, wo Non-TLS-Setups ihn benoetigen |

## Zielbild

### Architektur

- Kleine, klar abgegrenzte Komponenten statt grosser Sammelklassen
- Reine Logik frueher aus Orchestrierungs- und Adapterklassen extrahieren
- Mehr Unit-Tests auf Regel- oder Helper-Ebene, weniger Verhalten nur ueber
  grosse End-to-End-Pfade absichern

### Security Default

- `allowPublicKeyRetrieval` soll **nicht** mehr implizit aktiv sein
- Der Parameter soll nur noch explizit per `ConnectionConfig.params` oder
  bewusst dokumentiertem Opt-in gesetzt werden
- TLS-faehige Pfade sollen der bevorzugte Default bleiben

## Vorschlag

### A. Mikro-Komplexitaet schrittweise abbauen

#### A1. `DataImportRunner`

Die erste Entflechtung ist bereits erfolgt:

- `ImportPreflightValidator`
- `ImportCheckpointManager`
- `ImportResumeCoordinator`

Im Zuge dieses Refactorings wurden bereits weitere, kleine Schritte
umgesetzt:

- Format-Erkennung und Input-Aufloesung liegen in `DataImportHelpers`
- die Preflight-Assemblierung liegt in `ImportPreflightResolver`
- CLI-Flag-Validierung liegt ebenfalls in `DataImportHelpers`
- `executeWithPool()` plant den Lauf ueber `ImportExecutionPlanner`
- der Streaming-Handoff samt Exception-Mapping liegt in
  `ImportStreamingInvoker`
- Exit-Klassifikation, Checkpoint-Cleanup und Progress-Summary wurden aus dem
  Runner in Helperlogik verschoben

Der naechste Schritt ist daher **nicht** eine zweite parallele Preflight- oder
Checkpoint-Abstraktionsrunde, sondern das Praezisieren der verbleibenden
Hotspots im Runner:

| Bereich | Aktueller Hotspot | Moegliche Entlastung |
|--------|-------------------|---------------------|
| `ImportPreflightResolver` | neue Preflight-Naht fuer CLI/Input/Target-Aufloesung | vor allem Regressionen und Randfaelle absichern |
| `executeWithPool()` | jetzt weitgehend nur noch Plan -> Streaming -> Finalisierung | weitgehend erledigt; vor allem Regressionen gegen die neuen Naehte absichern |
| `finalizeAndReport()` | bereits auf Helper delegiert | nur noch Regressionen und Randfaelle absichern |
| `resolveImportInput()` / `resolveFormat()` | bereits nach `DataImportHelpers` extrahiert | nur noch Regressionen und Grenzfaelle absichern |

Ziel ist, die bereits extrahierten Komponenten zu erhalten und den Runner um
die noch verbliebenen Mischverantwortungen herum schlanker zu machen.

#### A2. `SchemaValidator`

Der erste fachliche Schnitt ist bereits umgesetzt:

- Spaltenregeln liegen jetzt in `SchemaColumnValidationRules`
- Tabellen-, Trigger- und View-Strukturregeln liegen jetzt in
  `SchemaStructureValidationRules`
- `SchemaValidator` aggregiert nur noch diese beiden Regelbloecke in den
  bestehenden `ValidationResult`
- die neuen Regeln sind direkt mit `SchemaColumnValidationRulesTest`
  und `SchemaStructureValidationRulesTest` abgesichert

Der naechste Schritt ist, weitere inhaltlich dichte Bloecke in dieselbe
Richtung zu schneiden, ohne den Ergebnisvertrag zu aendern, z. B.:

```kotlin
interface SchemaValidationRule {
    fun validate(schema: SchemaDefinition): ValidationResult
}
```

Moegliche Gruppen:

- Tabellen-/Spaltenregeln
- Referenz- und Constraintregeln
- Default-/Sequenzregeln
- Dialekt- bzw. Migrationskompatibilitaet

Wichtig dabei: Der bestehende Rueckgabevertrag von `SchemaValidator` bleibt
stabil. Das Refactoring darf weder die `ValidationResult`-Shape noch die
Trennung in `ValidationError` und `ValidationWarning`, noch die bestehenden
Codes und ihre Error-/Warning-Semantik aendern. Die Ledger- und
Code-gebundenen Tests bleiben damit explizite Guardrails.

#### A3. `MysqlDdlGenerator`

Nach Objektarten oder DDL-Phasen splitten:

- Tabellen
- Constraints und Indizes
- Views / Routinen / Trigger
- Sequences / Support-Objekte
- Rollback-Erzeugung

Der erste Split ist bereits umgesetzt:

- Sequence-Emulation inklusive Support-Tabelle, Support-Routinen,
  Support-Triggern, Kollisionen und Laufzustand liegt jetzt in
  `MysqlSequenceDdlSupport`
- Index- und Partition-DDL liegen jetzt in
  `MysqlIndexPartitionDdlHelper`
- `MysqlDdlGenerator` bleibt die Orchestrierungs-Fassade fuer Tabellen,
  Indizes, Routinen, Trigger und Rollback

#### A4. `MysqlMetadataQueries`

Query-Sammelobjekt in kleinere Bereiche trennen:

- Tabellen- und Spaltenmetadaten
- Constraint- und Indexqueries
- View-/Routinequeries
- Sequence-Support-Checks

Der erste Split ist bereits umgesetzt:

- Sequence-Support-Checks liegen jetzt in
  `MysqlSequenceSupportMetadataQueries`
- `MysqlMetadataQueries` bleibt als stabile API-Fassade fuer bestehende
  Aufrufer erhalten

Das Ziel ist nicht maximale Fragmentierung, sondern kleinere, lokal
verstehbare Dateien mit engerem Testfokus.

#### A5. `MysqlSequenceSupport`

Der naechste groessere MySQL-Hotspot ist ebenfalls bereits in zwei Richtungen
geschnitten:

- `MysqlSequenceSupport` ist auf die Orchestrierung von Scan, D2 und D3
  reduziert
- numerische Row-Coercion, Snapshot-Mapping und D2-Kandidatenbewertung liegen
  in `MysqlSequenceSupportSnapshotHelpers`
- Triggerauflosung, Default-Anreicherung und W116-Diagnostik liegen in
  `MysqlSequenceSupportMaterialization`

Falls weitere Schnitte noetig werden, sollten sie entlang dieser bereits
liegenden Trennlinien erfolgen und nicht wieder alles in die Hauptklasse
zurueckziehen.

#### A6. `FilterDslParser`

Auch der Filter-DSL-Parser ist bereits entlang der fachlichen Grenze zwischen
boolescher Grammatik und Wertausdruecken geschnitten:

- `FilterDslParser` enthaelt nur noch die Filter-/Praedikat-Grammatik
- `FilterDslParserSupport` kapselt Parser-Zustand und Fehleraufbereitung
- `FilterDslValueParser` kapselt Arithmetik, Identifier und erlaubte
  Funktionsaufrufe

Der naechste Schritt ist hier nicht weitere Zerlegung um der Zerlegung
willen, sondern vor allem die vorhandene Testabdeckung als Guardrail zu
halten und nur bei echtem fachlichem Nutzen weiter zu schneiden.

#### A7. `ViewQueryTransformer`

Auch die View-Query-Transformation ist jetzt entlang ihrer natuerlichen
Trennlinien geschnitten:

- `ViewQueryTransformer` orchestriert nur noch den Transformationslauf und
  die W111-Erkennung
- `ViewQueryTokenSupport` kapselt Tokenmodell und Tokenisierung
- `ViewQueryTransformationRules` kapselt die eigentlichen Regelklassen und
  Hilfsfunktionen fuer Token-Umschreibungen

Die verbleibenden echten Rest-Hotspots liegen damit zunehmend in anderen
Bereichen wie JDBC-Readern, Format-Parsing und PostgreSQL-Adaptern.

#### A8. `AbstractJdbcDataReader`

Auch der gemeinsame JDBC-Reader ist jetzt entlang seiner inneren
Lebenszyklus-Grenzen geschnitten:

- `AbstractJdbcDataReader` enthaelt nur noch den Reader-Lifecycle und den
  Aufbau des finalen SELECTs
- `JdbcSelectQuerySupport` kapselt Projektion, WHERE-Fragment-Sammlung und
  Resume-Marker-Fragmentbau
- `JdbcChunkSequence` kapselt den JDBC-Chunk-Lifecycle inklusive Empty-Table-
  Vertrag und Cleanup

Die verbleibenden echten Rest-Hotspots liegen damit noch klarer in den
Format- und PostgreSQL-spezifischen Komponenten.

#### A9. `SchemaNodeParser`

Auch der gemeinsame Schema-Parser im Formats-Modul ist jetzt entlang seiner
natuerlichen Parse-Grenzen geschnitten:

- `SchemaNodeParser` orchestriert nur noch den Root-Parse von
  `SchemaDefinition`
- `SchemaNodeStructureParsers` kapselt Tabellen, Spalten, Defaults,
  Referenzen, Constraints, Partitioning und Custom Types
- `SchemaNodeProgrammabilityParsers` kapselt Procedures, Functions, Views,
  Triggers, Sequences und Dependencies
- `SchemaNodeParserSupport` kapselt die wiederverwendeten `JsonNode`-Hilfen

Die verbleibenden echten Rest-Hotspots liegen damit jetzt vor allem bei
PostgreSQL-Adaptern, `SchemaNodeBuilder`, SQLite-DDL und Streaming-Import.

#### A10. `PostgresDataWriter`

Auch der PostgreSQL-Schreibpfad ist jetzt entlang der natuerlichen Grenze
zwischen Writer-Bootstrap und tabellenbezogener Import-Session geschnitten:

- `PostgresDataWriter` enthaelt nur noch `openTable`, Session-Bootstrap und
  das Laden der benoetigten Zielmetadaten
- `PostgresTableImportSession` kapselt den eigentlichen Write-Pfad fuer
  Insert, Upsert, Value-Binding, Trigger-Cleanup und Sequence-Reseeding

Damit verschwindet `PostgresDataWriter` aus den groessten Rest-Hotspots; im
PostgreSQL-Bereich bleiben vor allem `PostgresSchemaReader`,
`PostgresMetadataQueries` und `PostgresDdlGenerator`.

#### A11. `SchemaNodeBuilder`

Auch der gemeinsame Schema-Builder im Formats-Modul ist jetzt entlang der
natuerlichen Grenze zwischen Root-Orchestrierung, Struktur-Bausteinen und
Programmability-Bausteinen geschnitten:

- `SchemaNodeBuilder` orchestriert nur noch den Root-Aufbau von
  `SchemaDefinition`
- `SchemaNodeStructureBuilders` kapselt Tabellen, Spalten, Defaults,
  Referenzen, Constraints, Partitioning und Custom Types
- `SchemaNodeProgrammabilityBuilders` kapselt Procedures, Functions, Views,
  Triggers, Sequences und Dependencies
- `SchemaNodeBuilderSupport` kapselt wiederverwendete Array- und
  NeutralType-Bausteine

Damit sind Parser und Builder im Formats-Modul jetzt symmetrisch geschnitten;
der naechste klare Grossblock liegt vor allem bei `PostgresSchemaReader`.

#### A12. `PostgresSchemaReader`

Auch der PostgreSQL-Read-Pfad ist jetzt entlang der natuerlichen Grenze
zwischen Read-Orchestrierung, Struktur-/Typmetadaten und programmierbaren
Objekten geschnitten:

- `PostgresSchemaReader` orchestriert nur noch den Gesamtlauf und die
  optionale Einbeziehung einzelner Objektarten
- `PostgresSchemaStructureReaders` kapselt Tabellen, Partitioning,
  Sequences und Custom Types
- `PostgresSchemaProgrammabilityReaders` kapselt Views, Routinen, Trigger
  und das Mapping der Routine-Parameter

Damit ist kein Produktionsfile mehr `>=400` LOC; die verbleibenden groesseren
Rest-Hotspots liegen jetzt vor allem bei `SqliteDdlGenerator`,
`TableImporter`, `YamlChunkReader`, `PostgresMetadataQueries` und den
groesseren CLI-/Writer-Adaptern.

#### A13. `SqliteDdlGenerator`

Auch der SQLite-DDL-Pfad ist jetzt entlang der natuerlichen Grenze zwischen
Generator-Orchestrierung, Tabellen-/Spatial-Logik und SQLite-spezifischen
Nicht-Unterstuetzungsfaellen geschnitten:

- `SqliteDdlGenerator` orchestriert nur noch die Generierungsphasen
- `SqliteTableDdlSupport` kapselt Tabellen-DDL, SpatiaLite-Sonderpfad,
  Geometrie-Blocker und Index-DDL
- `SqliteCapabilityDdlSupport` kapselt Custom-Type-/Sequence-Skips,
  zirkulaere FK-Hinweise und SQLite-spezifische Rollback-Inversion

Damit verschwindet auch `SqliteDdlGenerator` aus den groessten Rest-Hotspots;
als naechster klarer Block bleibt jetzt vor allem `TableImporter`.

#### A14. `TableImporter`

Auch der Streaming-Importpfad ist jetzt entlang der natuerlichen Grenze
zwischen Ressourcen-Orchestrierung, Binding-/Deserializer-Aufbau und dem
eigentlichen Chunk-Loop geschnitten:

- `TableImporter` orchestriert nur noch Reader-/Session-Lifecycle,
  Resume-Offset, Finish-Handling und Reporting
- `TableImportBindingSupport` kapselt Header-/Target-Binding,
  Schema-Mismatch-Pruefung und den `ValueDeserializer`-Aufbau
- `TableImportLoopSupport` kapselt Chunk-Normalisierung, Write-/Rollback-/
  Commit-Loop und `OnError`-basierte Fehlerbehandlung

Damit verschwindet auch `TableImporter` aus den groessten Rest-Hotspots;
als naechste klaren Bloecke bleiben jetzt vor allem `YamlChunkReader`,
`PostgresMetadataQueries`, `SqliteDataWriter` und die groesseren CLI-
Adapter.

#### A15. `YamlChunkReader`

Auch der YAML-Read-Pfad ist jetzt entlang der natuerlichen Grenze zwischen
Reader-Orchestrierung, SnakeYAML-Event-Cursor und Schema-/Scalar-Logik
geschnitten:

- `YamlChunkReader` orchestriert nur noch Chunk-Bildung, First-Row-Schema
  und die Sequenzgrenzen
- `YamlEventCursor` kapselt SnakeYAML-Prolog, Nested-Value-Lesen,
  Alias-Blocker und Tail-Validierung
- `YamlSchemaSupport` kapselt First-Row-Schema-Binding, Row-Normalisierung
  und YAML-Core-Scalar-Aufloesung

Damit verschwindet auch `YamlChunkReader` aus den groessten Rest-Hotspots;
als naechste klaren Bloecke bleiben jetzt vor allem
`PostgresMetadataQueries`, `SqliteDataWriter`, `ToolExportRunner` und
`SchemaGenerateRunner`.

#### A16. `PostgresMetadataQueries`

Auch der PostgreSQL-Metadatenpfad ist jetzt entlang der natuerlichen Grenze
zwischen API-Fassade, Tabellen-/Constraint-Queries, benutzerdefinierten
Typen und programmierbaren Objekten geschnitten:

- `PostgresMetadataQueries` kapselt nur noch die stabile oeffentliche API
- `PostgresTableMetadataQueries` kapselt Tabellen, Constraints, Indizes,
  Sequenzen, Partitioning und Extensions
- `PostgresTypeMetadataQueries` kapselt Enum-, Domain- und Composite-Typen
- `PostgresProgrammabilityMetadataQueries` kapselt Views, View-Dependencies,
  Funktionen, Prozeduren, Parameter und Trigger

Damit verschwindet auch `PostgresMetadataQueries` aus den groessten
Rest-Hotspots; als naechste klaren Bloecke bleiben jetzt vor allem
`SqliteDataWriter`, `ToolExportRunner`, `SchemaGenerateRunner` und
`AbstractDdlGenerator`.

#### A17. `SqliteDataWriter`

Auch der SQLite-Write-Pfad ist jetzt entlang der natuerlichen Grenze
zwischen Writer-API/Connection-Setup und der tabellenbezogenen
Import-Session geschnitten:

- `SqliteDataWriter` kapselt nur noch Dialekt-API, `openTable(...)`,
  Target-/PK-Metadaten und FK-/Truncate-Setup
- `SqliteTableImportSession` kapselt SQLite-spezifische Insert-/Upsert-
  Ausfuehrung, Batch-/PK-Existenzpruefung sowie Finish-/Cleanup-Logik

Damit verschwindet auch `SqliteDataWriter` aus den groessten
Rest-Hotspots; als naechste klaren Bloecke bleiben jetzt vor allem
`ToolExportRunner`, `SchemaGenerateRunner`, `AbstractDdlGenerator` und
`MysqlDataWriter`.

#### A18. `ToolExportRunner`

Auch der Tool-Export-Pfad ist jetzt entlang der natuerlichen Grenze zwischen
Runner-Orchestrierung und Report-Sidecar-Schicht geschnitten:

- `ToolExportRunner` kapselt nur noch Preflight, DDL-/Exporter-Aufruf,
  Kollisionserkennung, Artefakt-Write und Note-Ausgabe
- `ToolExportReportSupport` kapselt das Report-Datenmodell und das
  YAML-Rendering des optionalen Sidecars

Damit verschwindet auch `ToolExportRunner` aus den groessten
Rest-Hotspots; als naechste klaren Bloecke bleiben jetzt vor allem
`SchemaGenerateRunner`, `AbstractDdlGenerator`, `MysqlDataWriter` und
`DataImportHelpers`.

#### A19. `SchemaGenerateRunner`

Auch der Schema-Generate-Pfad ist jetzt entlang der natuerlichen Grenze
zwischen Runner-Orchestrierung und Output-Schicht geschnitten:

- `SchemaGenerateRunner` kapselt nur noch Preflight, Generator-Aufruf,
  Split-/Diagnostik-Pruefung, Note-Ausgabe und Output-Routing
- `SchemaGenerateOutputSupport` kapselt File-/Stdout-/Report-Ausgabe sowie
  Rollback- und Split-Dateischreiben

Damit verschwindet auch `SchemaGenerateRunner` aus den groessten
Rest-Hotspots; als naechste klaren Bloecke bleiben jetzt vor allem
`AbstractDdlGenerator`, `MysqlDataWriter`, `DataImportHelpers` und
`PostgresDdlGenerator`.

#### A20. `AbstractDdlGenerator`

Auch der gemeinsame DDL-Basistyp ist jetzt entlang der natuerlichen Grenze
zwischen DDL-Orchestrierung und wiederverwendeten Sort-/Phasen-Helfern
geschnitten:

- `AbstractDdlGenerator` kapselt nur noch den Generierungsablauf fuer
  Tabellen, Views, Rollback, Skip-Handling und Phasenverdrahtung
- `DdlGenerationSupport` kapselt den Topological Sort fuer Tabellen,
  die View-Abhaengigkeitsauflosung sowie die wiederverwendeten
  Skip-/Phasen-Helfer

Damit verschwindet auch `AbstractDdlGenerator` aus den groessten
Rest-Hotspots; als naechste klaren Bloecke bleiben jetzt vor allem
`MysqlDataWriter`, `DataImportHelpers` und `PostgresDdlGenerator`.

#### A21. `MysqlDataWriter`

Auch der MySQL-Write-Pfad ist jetzt entlang der natuerlichen Grenze
zwischen Writer-API/Connection-Setup und der tabellenbezogenen
Import-Session geschnitten:

- `MysqlDataWriter` kapselt nur noch Dialekt-API, `openTable(...)`,
  Ziel-/PK-Metadaten und FK-/Truncate-Setup
- `MysqlTableImportSession` kapselt MySQL-spezifische Insert-/Upsert-
  Ausfuehrung, Value-Binding sowie Finish-/Cleanup-Logik

Damit verschwindet auch `MysqlDataWriter` aus den groessten
Rest-Hotspots; als naechste klaren Bloecke bleiben jetzt vor allem
`DataImportHelpers` und `PostgresDdlGenerator`.

#### A22. `PostgresDdlGenerator`

Auch der PostgreSQL-DDL-Pfad ist jetzt entlang der natuerlichen Grenze
zwischen Generator-Orchestrierung und Custom-Type-/Sequence-DDL geschnitten:

- `PostgresDdlGenerator` kapselt nur noch Tabellen, Constraints,
  Partitioning, Views, Routinen, Trigger und zirkulaere FK-Nachzuegler
- `PostgresTypeSequenceDdlSupport` kapselt ENUM-/COMPOSITE-/DOMAIN-DDL
  sowie `CREATE SEQUENCE`

Damit verschwindet auch `PostgresDdlGenerator` aus den groessten
Rest-Hotspots; als letzter klarer Block blieb danach nur noch
`DataImportHelpers`.

#### A23. `DataImportHelpers`

Auch der verbliebene Import-Helper-Bucket ist jetzt entlang der natuerlichen
Grenze zwischen Request-/Input-Aufloesung und Completion-/Reporting-Logik
geschnitten:

- `DataImportHelpers` kapselt nur noch Format-/Input-/Charset-/Target-
  Aufloesung sowie CLI-/Dialekt-Preflight
- `ImportCompletionSupport` kapselt Completion-Bewertung,
  Checkpoint-Cleanup und die Import-Progress-Summary

Damit gibt es im aktuell geprueften Repo-Stand keine Produktionsdateien mehr
ueber `300` LOC.

### B. MySQL-Default haerten

#### B1. Default aendern

Erledigt. In `MysqlJdbcUrlBuilder` und im MySQL-Zweig des
`FallbackJdbcUrlBuilder` wurde `allowPublicKeyRetrieval=true` aus den
Defaults entfernt.

#### B2. Opt-in erhalten

Erledigt. Explizite Benutzer-Parameter werden weiterhin durchgereicht:

```kotlin
ConnectionConfig(
    ...,
    params = mapOf("allowPublicKeyRetrieval" to "true")
)
```

#### B3. Tests anpassen

Erledigt. Die Tests wurden entsprechend angepasst:

- Builder-Tests pruefen nicht mehr auf implizites
  `allowPublicKeyRetrieval=true`
- Stattdessen wird abgesichert:
  - Default enthaelt den Parameter nicht
  - explizites Opt-in wird korrekt uebernommen
- Fallback-Builder-Tests sind analog umgestellt
- MySQL-Integrationstests setzen das Opt-in explizit in den betroffenen
  `ConnectionConfig`-Fixtures

#### B4. Spec-Alignment und Folgeartefakte

Hier ging es primaer um eine **Code-an-Spec-Angleichung**, nicht um eine
neue Spezifikation: `spec/connection-config-spec.md` dokumentiert den MySQL-
Default bereits als `false`.

Angepasst wurden:

- dieses Refactoring-Dokument
- betroffene MySQL-KDocs, insbesondere der Hinweis in
  `MysqlDataReaderIntegrationTest.kt`

Optional nachzuziehen:

- `docs/user/quality-report.md`, falls das Review-Artefakt mit dem Code-Stand
  synchron gehalten werden soll
- ggf. MySQL-bezogene Integrations- oder Betriebsdoku

Die Doku sollte klar sagen:

- Default ist konservativ
- ohne TLS kann fuer bestimmte MySQL-8-Setups ein explizites
  `allowPublicKeyRetrieval=true` noetig sein
- bevorzugter Pfad ist TLS statt Lockerung des Connectors

## Reihenfolge

| Schritt | Thema | Risiko | Kommentar |
|--------|-------|--------|-----------|
| 1 | MySQL-Default und zugehoerige Tests/Doku | erledigt | Default gehaertet, Tests und KDoc angepasst |
| 2 | `DataImportRunner` weiter entflechten | erledigt | Runner liegt jetzt bei 206 LOC; Preflight-, Planner- und Streaming-Schnitt sind extrahiert und durch direkte Guardrails abgesichert |
| 3 | `MysqlMetadataQueries` aufteilen | erledigt | API-Fassade und Sequence-Support-Queries sind getrennt; direkte Regressionen sichern Query-Fallback und Filterverhalten ab |
| 4 | `MysqlDdlGenerator` modularisieren | erledigt | Generator-Fassade, Sequence-Emulation sowie Index/Partition sind getrennt; direkte Regressionen sichern den zustandsbehafteten Sequence-Support ab |
| 5 | `SchemaValidator` regelbasiert schneiden | erledigt | Struktur- und Spaltenregeln sind ausgelagert; Rueckgabevertrag und Codes bleiben ueber direkte Validator-/Regeltests abgesichert |
| 6 | `MysqlSequenceSupport` modularisieren | erledigt | Hauptklasse ist jetzt Orchestrierung; Snapshot-Helper und Materialisierung sind getrennt und ueber direkte Guardrails abgesichert |
| 7 | `FilterDslParser` schneiden | erledigt | Filter-Grammatik, Parser-State und Wertausdruecke sind getrennt; Parser-Vertrag bleibt ueber direkte DSL-Tests abgesichert |
| 8 | `ViewQueryTransformer` schneiden | erledigt | Hauptklasse, Tokenisierung und Regelanwendung sind getrennt; direkte Transformations-Tests sichern den Vertrag ab |
| 9 | `AbstractJdbcDataReader` schneiden | erledigt | Reader, Select-Helfer und Chunk-Lifecycle sind getrennt; direkte Reader-/Chunk-Tests sichern den Lifecycle ab |
| 10 | `SchemaNodeParser` schneiden | erledigt | Root-Orchestrierung, Strukturparser, Objektparser und JsonNode-Helfer sind getrennt; direkte Parser-Regressionen decken Struktur und Programmability ab |
| 11 | `PostgresDataWriter` schneiden | erledigt | Writer-Bootstrap und tabellenbezogene Import-Session sind getrennt; direkte Writer-/Session-Tests sichern den Vertrag ab |
| 12 | `SchemaNodeBuilder` schneiden | erledigt | Root-Orchestrierung, Struktur-Builder, Objekt-Builder und gemeinsame Typ-/Array-Helfer sind getrennt; direkte Builder-Regressionen sichern Root-Order und Nested-Emission |
| 13 | `PostgresSchemaReader` schneiden | erledigt | Read-Orchestrierung, Struktur-/Typreader und Programmability-Reader sind getrennt; direkte Reader-Tests sichern den Gesamtvertrag ab |
| 14 | `SqliteDdlGenerator` schneiden | erledigt | Generator-Orchestrierung, Tabellen-/Spatial-Logik und SQLite-spezifische Nicht-Unterstuetzungsfaelle sind getrennt; direkte DDL-Tests sichern den Vertrag ab |
| 15 | `TableImporter` schneiden | erledigt | Import-Orchestrierung, Binding-Support und Chunk-Loop sind getrennt; direkte Importer-Regressionen sichern Resume- und Partial-Finish-Pfade ab |
| 16 | `YamlChunkReader` schneiden | erledigt | Reader-Orchestrierung, Event-Cursor und Schema-/Scalar-Support sind getrennt; direkte Reader- und Edge-Case-Tests sichern den Vertrag ab |
| 17 | `PostgresMetadataQueries` schneiden | erledigt | API-Fassade, Tabellen-/Constraint-Queries, Typ-Queries und Programmability-Queries sind getrennt; direkte Query-Tests sichern die Fassade ab |
| 18 | `SqliteDataWriter` schneiden | erledigt | Writer-API/Connection-Setup und tabellenbezogene Import-Session sind getrennt; direkte Writer-/Session-Tests sichern den Vertrag ab |
| 19 | `ToolExportRunner` schneiden | erledigt | Runner-Orchestrierung und Report-Sidecar-Schicht sind getrennt; direkte Runner-Tests sichern Orchestrierung und Report-Pfad ab |
| 20 | `SchemaGenerateRunner` schneiden | erledigt | Runner-Orchestrierung und Output-Schicht sind getrennt; direkte Runner-Tests sichern Fehler- und Output-Pfade ab |
| 21 | `AbstractDdlGenerator` schneiden | erledigt | DDL-Orchestrierung und `DdlGenerationSupport` sind getrennt; direkte DDL-Tests sichern Topology- und View-Phasen-Verhalten ab |
| 22 | `MysqlDataWriter` schneiden | erledigt | Writer-API/Connection-Setup und tabellenbezogene Import-Session sind getrennt; direkte Writer-/Session-Tests sichern den Vertrag ab |
| 23 | `PostgresDdlGenerator` schneiden | erledigt | Generator-Orchestrierung und Type/Sequence-Support sind getrennt; direkte DDL-Tests sichern Tabellen-, Routine- und Type/Sequence-Pfade ab |
| 24 | `DataImportHelpers` schneiden | erledigt | Request-Aufloesung und Completion-/Reporting-Support sind getrennt; direkte Helper-Tests sichern die extrahierten Pfade ab |
| 25 | verbleibende Rest-Hotspots priorisieren | erledigt | aktueller Repo-Stand: 0 Produktionsdateien `>300` LOC und 0 `>=400` LOC |

## Risiken

- Der MySQL-Defaultwechsel kann bestehende Non-TLS-Setups brechen, die sich
  bisher auf das implizite `allowPublicKeyRetrieval=true` verlassen haben.
- Ein uebereiltes Architektur-Refactoring kann Testcode und Fehlerpfade
  verschlechtern, wenn nur umorganisiert statt wirklich entkoppelt wird.
- Zu feine Zerlegung ohne klares Ziel produziert nur mehr Dateien, aber nicht
  mehr Verstaendlichkeit.

## Akzeptanzkriterien

### Architektur

- Die priorisierten Grossklassen sind in kleinere Einheiten mit klarer
  Verantwortung zerlegt.
- Neue Helper oder Regelobjekte sind direkt unit-testbar.
- Kein Rueckgang der bestehenden Testabdeckung in den betroffenen Modulen.
- Fuer `SchemaValidator` bleiben `ValidationResult`, die Error/Warning-
  Aufteilung, die bestehenden Codes und ihre Ledger-verankerte Semantik
  stabil.

### Security Default

- Generierte MySQL-JDBC-URLs enthalten `allowPublicKeyRetrieval` nicht mehr
  implizit.
- Ein explizit gesetzter Parameter wird weiterhin korrekt uebernommen.
- Tests und Doku spiegeln das neue Default-Verhalten konsistent wider.

## Nicht Teil dieses Refactorings

- Die bereits separat behobenen Query-Praezedenzfehler in
  `MysqlMetadataQueries` und `SqliteProfilingDataAdapter`
- Allgemeine Compilerwarnungen ausserhalb dieses Scopes
- Vollstaendige Neuorganisation aller grossen Klassen in einem einzigen Schritt
