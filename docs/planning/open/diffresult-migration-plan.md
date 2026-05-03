# Implementierungsplan: `DiffResult` fuer diff-basierte Migrationen

> Status: Draft (2026-05-03)
>
> Zweck: Planung fuer einen stabilen, migrationsfaehigen `DiffResult`-
> Vertrag als Grundlage fuer spaetere `schema migrate`- und
> diff-basierte Rollback-Pfade.
>
> Referenzen:
> - `docs/planning/done/implementation-plan-0.7.0.md`
> - `spec/cli-spec.md` Abschnitt `schema migrate` / `schema rollback`
> - `spec/design.md` Abschnitt Migrations-Rollback
> - `spec/ddl-generation-rules.md` Abschnitt ALTER / SQLite-Rebuild
> - `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaDiff.kt`
> - `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`
> - `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareProjection.kt`
> - `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`

---

## 1. Ziel

Dieses Dokument beschreibt den fehlenden Zwischenvertrag zwischen dem heute
existierenden `SchemaDiff` und einem spaeteren, wirklich ausfuehrbaren
Migrationspfad.

Der heutige Stand reicht fuer `schema compare`, aber noch nicht fuer
`schema migrate`:

- `SchemaComparator.compare(left, right)` erzeugt ein strukturelles
  `SchemaDiff`.
- `schema compare` projiziert dieses `SchemaDiff` in eine stabile,
  primitive `DiffView` fuer CLI/MCP-Ausgaben.
- `DdlGenerator.generate(...)` erzeugt full-state-DDL aus genau einem
  Ziel-Schema.
- `DdlGenerator.generateRollback(...)` erzeugt full-state-Rollback-DDL aus
  genau einem Schema.
- 0.7.0-Tool-Exports verwenden diesen full-state-Pfad bewusst weiter.

Was fehlt:

- ein geplanter, geordneter, dialektbewusster Operationsplan aus
  `SchemaDiff`
- Reversibilitaets- und Risiko-Metadaten pro Operation
- eine Grundlage fuer inkrementelle Up-/Down-DDL
- ein klarer Vertrag fuer destruktive, nicht automatisch reversible und
  SQLite-Rebuild-Operationen

Der geplante `DiffResult` ist deshalb **nicht** einfach ein neuer Name fuer
`SchemaDiff`. Er ist ein migrationsfaehiger Plan, der aus `SchemaDiff`
abgeleitet wird.

---

## 2. Abgrenzung zu 0.7.0

0.7.0 exportiert baseline-/full-state-Artefakte aus einem einzelnen neutralen
Schema:

```bash
d-migrate export flyway --source schema.yaml --target postgresql --output migrations
```

Dieser Pfad bleibt unveraendert.

`DiffResult` gehoert zu einem spaeteren Pfad:

```bash
d-migrate schema migrate --source desired.yaml --target db:staging --output migration.sql
```

Die fachliche Unterscheidung ist verbindlich:

| Pfad | Eingaben | Ergebnis | Grundlage |
|---|---|---|---|
| `schema generate` | ein Schema | full-state-DDL | `DdlGenerator.generate(schema)` |
| `export flyway|...` | ein Schema | Tool-Artefakt, full-state | `MigrationBundle` + `DdlResult` |
| `schema compare` | zwei Schemata/Operanden | Diagnose/Report | `SchemaDiff` + `DiffView` |
| `schema migrate` | Ist-Zustand + Soll-Schema | inkrementelles Up/Down | `DiffResult` |

Nicht akzeptabel:

- `DiffResult` in 0.7.0-Tool-Exports hineinzuziehen
- `MigrationBundle` als Ersatz fuer `DiffResult` zu verwenden
- `SchemaDiff` direkt als DDL-Plan zu rendern
- destruktive Operationen ohne explizite Risiko- und Bestaetigungssemantik
  ausfuehrbar zu machen

---

## 3. Ausgangslage im Code

### 3.1 Bestehender Compare-Vertrag

Aktuelle Kernbausteine:

- `SchemaComparator`
- `SchemaDiff`
- `TableDiff`
- `ColumnDiff`
- `ValueChange<T>`
- objektbezogene Diffs fuer Custom Types, Views, Sequences, Functions,
  Procedures und Triggers

`SchemaDiff` ist fuer strukturierte Unterschiede geeignet, aber noch nicht
ausreichend fuer Migrationen:

- keine Operationsreihenfolge
- keine Abhaengigkeitsgraphen
- keine DDL-Phasen
- keine Dialektfaehigkeiten
- keine Reversibilitaet pro Aenderung
- keine Destruktivitaetsklassifizierung
- keine Operation-IDs fuer Audit, Reports oder Rollback-Artefakte

### 3.2 Namenskollision im aktuellen Code

In `SchemaComparator` existiert bereits ein privater generischer Hilfstyp:

```kotlin
private data class DiffResult<N, D>(
    val added: List<N>,
    val removed: List<N>,
    val changed: List<D>,
)
```

Dieser Typ ist kein fachlicher Produktvertrag. Vor Einfuehrung eines
oeffentlichen `DiffResult` sollte er umbenannt werden, zum Beispiel in:

- `MapDiffResult`
- `ObjectMapDiff`
- `CollectionDiff`

Dadurch bleibt der neue Name `DiffResult` frei fuer den migrationsfaehigen
Vertrag.

### 3.3 Bestehender DDL-Vertrag

`DdlGenerator` kennt aktuell:

```kotlin
fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult
fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult
```

Dieser Vertrag bleibt fuer full-state-DDL richtig. Fuer `DiffResult` braucht es
einen separaten Port, der nicht so tut, als koenne ein einzelnes Ziel-Schema
inkrementelle Migrationen ersetzen.

Vorgeschlagene Richtung:

```kotlin
interface DiffDdlGenerator {
    fun generateMigration(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult
    fun generateRollback(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult
}
```

Die konkrete Typform ist Implementierungsdetail. Wichtig ist die Trennung vom
full-state-`DdlGenerator`.

---

## 4. Produktvertrag fuer `DiffResult`

### 4.1 Aufgaben von `DiffResult`

`DiffResult` soll:

- aus einem `SchemaDiff` und den beiden materialisierten Schema-Zustaenden
  abgeleitet werden
- eine deterministische Liste fachlicher Operationen enthalten
- Operationen in DDL-Phasen und Abhaengigkeitsreihenfolge bringen
- Reversibilitaet und Risiko explizit ausweisen
- dialektunabhaengige Semantik tragen
- dialektspezifische Renderentscheidungen vorbereiten, aber nicht selbst SQL
  enthalten

`DiffResult` soll nicht:

- ein CLI-/JSON-Ausgabeformat fuer `schema compare` ersetzen
- rohe SQL-Strings als primaere Semantik tragen
- Tool-Export-Artefakte aus 0.7.0 ersetzen
- Rename-Detection erraten, solange es dafuer keine robuste Semantik gibt
- Datenmigrationen fuer geaenderte Spalteninhalte automatisch ableiten

### 4.2 Grobe Typform

Skizze:

```kotlin
data class DiffResult(
    val source: DiffEndpoint,
    val target: DiffEndpoint,
    val schemaDiff: SchemaDiff,
    val operations: List<DiffOperation>,
    val diagnostics: List<DiffDiagnostic> = emptyList(),
)

data class DiffEndpoint(
    val schemaName: String,
    val schemaVersion: String?,
    val fingerprint: String?,
)
```

Die Einbettung von `schemaDiff` ist optional, aber sinnvoll:

- `SchemaDiff` bleibt die verlustarme strukturelle Diagnose.
- `DiffResult.operations` ist die ausfuehrbare Migrationsebene.
- Reports koennen beide Ebenen zeigen, ohne den Operationsplan zu
  ueberfrachten.

### 4.3 Operationen

Vorgeschlagene Basiskategorien:

```kotlin
sealed interface DiffOperation {
    val id: String
    val objectType: DiffObjectType
    val objectName: String
    val phase: DiffPhase
    val dependencies: Set<String>
    val reversibility: Reversibility
    val risk: OperationRisk
}
```

Beispiele:

- `CreateTable`
- `DropTable`
- `AddColumn`
- `DropColumn`
- `AlterColumnType`
- `AlterColumnNullability`
- `AlterColumnDefault`
- `AddPrimaryKey`
- `DropPrimaryKey`
- `AddConstraint`
- `DropConstraint`
- `AddIndex`
- `DropIndex`
- `CreateCustomType`
- `AlterCustomType`
- `DropCustomType`
- `CreateSequence`
- `AlterSequence`
- `DropSequence`
- `CreateView`
- `ReplaceView`
- `DropView`
- `CreateFunction`
- `ReplaceFunction`
- `DropFunction`
- `CreateProcedure`
- `ReplaceProcedure`
- `DropProcedure`
- `CreateTrigger`
- `ReplaceTrigger`
- `DropTrigger`

Rename-Operationen bleiben zunaechst bewusst ausserhalb des automatischen
Plans. Ein Entfernen plus Hinzufuegen kann in Reports als moeglicher Rename
diagnostiziert werden, darf aber ohne explizite Nutzerentscheidung nicht als
`RenameTable` oder `RenameColumn` gerendert werden.

### 4.4 Phasen

Der Operationsplan braucht Phasen, weil Reihenfolge nicht rein alphabetisch
sein darf.

Vorgeschlagene Phasen:

| Phase | Zweck |
|---|---|
| `PREPARE` | temporaere Hilfsobjekte, SQLite-Rebuild-Vorbereitung |
| `TYPES` | Custom Types / Domains / Enums |
| `TABLES` | Tabellen erzeugen oder entfernen |
| `COLUMNS` | Spalten hinzufuegen, aendern, entfernen |
| `CONSTRAINTS` | PK/FK/Unique/Check/Exclude |
| `INDEXES` | Indizes |
| `SEQUENCES` | Sequences und Sequence-Metadaten |
| `ROUTINES` | Functions / Procedures |
| `VIEWS` | Views / materialized Views |
| `TRIGGERS` | Trigger |
| `CLEANUP` | temporaere Objekte, Rebuild-Aufraeumen |

Diese Phasen sind ein Planungsmodell. Der konkrete Generator darf fuer einen
Dialekt zusammenfassen, wenn die Semantik erhalten bleibt.

### 4.5 Reversibilitaet

Jede Operation muss eine Reversibilitaet tragen:

```kotlin
enum class Reversibility {
    AUTOMATIC,
    AUTOMATIC_WITH_DATA_RISK,
    MANUAL_REQUIRED,
    NOT_REVERSIBLE,
}
```

Beispiele:

| Up-Operation | Down-Operation | Reversibilitaet |
|---|---|---|
| `CreateTable` | `DropTable` | `AUTOMATIC_WITH_DATA_RISK` |
| `AddColumn` nullable ohne Default | `DropColumn` | `AUTOMATIC_WITH_DATA_RISK` |
| `AddColumn` not null mit Default | `DropColumn` | `AUTOMATIC_WITH_DATA_RISK` |
| `AlterColumnDefault` | alter Default | `AUTOMATIC` |
| `AlterColumnType` | alter Typ | `AUTOMATIC_WITH_DATA_RISK` oder `MANUAL_REQUIRED` |
| `DropColumn` | nicht automatisch | `NOT_REVERSIBLE` |
| `DropTable` | nicht automatisch | `NOT_REVERSIBLE` |
| `ReplaceView` | alte View-Definition | `AUTOMATIC` |
| `ReplaceFunction` | alter Function-Body | `AUTOMATIC` |

Wichtig:

- `NOT_REVERSIBLE` verhindert nicht zwingend den Up-Plan.
- `--generate-rollback` darf fuer solche Operationen aber kein falsches
  Down-SQL erfinden.
- Der Runner muss den Nutzer ueber nicht reversible Operationen informieren.

### 4.6 Risiko- und Bestaetigungsmodell

Destruktive Operationen muessen maschinenlesbar markiert werden.

Skizze:

```kotlin
data class OperationRisk(
    val destructive: Boolean,
    val dataLossPossible: Boolean,
    val requiresTableRewrite: Boolean,
    val requiresManualConfirmation: Boolean,
    val notes: List<DiffDiagnostic> = emptyList(),
)
```

Beispiele fuer `requiresManualConfirmation = true`:

- `DropTable`
- `DropColumn`
- potentiell verlustbehaftetes `AlterColumnType`
- `AlterColumnNullability` von nullable auf not null ohne beweisbare
  Datenvorbedingung
- SQLite-Rebuild mit nicht trivialer Datenkopie

Der CLI-Vertrag sollte spaeter einen expliziten Schalter bekommen, zum
Beispiel:

```bash
d-migrate schema migrate ... --allow-destructive
```

Ohne diesen Schalter erzeugt der Runner bei destruktiven Operationen nur einen
Fehler bzw. einen Plan-Report, aber keine ausfuehrbare Migration.

---

## 5. Ableitungspipeline

### 5.1 Vorgeschlagene Architektur

```text
source operand  -> materialisiertes Ist-Schema
target schema   -> materialisiertes Soll-Schema
                         |
                         v
                 SchemaComparator
                         |
                         v
                    SchemaDiff
                         |
                         v
                 DiffPlanner
                         |
                         v
                    DiffResult
                         |
                         v
               DiffDdlGenerator pro Dialekt
                         |
                         v
                 MigrationDdlResult
```

Der neue fachliche Kern ist `DiffPlanner`.

Der Planner:

- konsumiert `SchemaDiff`
- nutzt bei Bedarf beide kompletten Schema-Zustaende
- erzeugt Operationen mit stabilen IDs
- sortiert Operationen deterministisch
- markiert Risiken und Reversibilitaet
- erzeugt Diagnosen fuer nicht planbare oder manuelle Schritte

Der Dialektgenerator:

- konsumiert `DiffResult`
- prueft Dialektfaehigkeiten
- rendert Up-DDL
- rendert optional Down-DDL aus invertierbaren Operationen
- erzeugt `SkippedObject`/Diagnostics fuer nicht renderbare Operationen

### 5.2 Warum `SchemaDiff` nicht genuegt

Beispiel: `tablesChanged.columnsRemoved` sagt nur, dass eine Spalte entfernt
wurde. Fuer Migrationen braucht der Plan zusaetzlich:

- gehoert zur Tabelle `orders`
- ist destruktiv
- kann automatisch nicht verlustfrei zurueckgerollt werden
- blockiert `--generate-rollback`, wenn kein manueller Down-Schritt erlaubt ist
- muss vor oder nach Constraint-/Index-Operationen laufen
- braucht fuer SQLite je nach Version einen Tabellen-Rebuild

Diese Informationen gehoeren nicht in `SchemaDiff`, weil `SchemaDiff` auch
fuer reine Diagnose und Reports stabil bleiben soll.

---

## 6. DDL- und Dialektvertrag

### 6.1 Neuer Port statt Erweiterung von `DdlGenerator`

Der full-state-Generator bleibt unveraendert.

Vorgeschlagene neue Ports:

```kotlin
interface DiffPlanner {
    fun plan(source: SchemaDefinition, target: SchemaDefinition, diff: SchemaDiff): DiffResult
}

interface DiffDdlGenerator {
    fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult
    fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult
}
```

`MigrationDdlResult` sollte `DdlResult` nicht blind ersetzen. Es braucht
zusaetzliche Felder:

- `operationsRendered`
- `operationsSkipped`
- `manualActions`
- `destructiveOperations`
- `nonReversibleOperations`
- `requiresConfirmation`

### 6.2 PostgreSQL

Erste Zieloperationen:

- `CREATE TABLE`
- `DROP TABLE`
- `ALTER TABLE ADD COLUMN`
- `ALTER TABLE DROP COLUMN`
- `ALTER TABLE ALTER COLUMN TYPE`
- `ALTER TABLE ALTER COLUMN SET/DROP DEFAULT`
- `ALTER TABLE ALTER COLUMN SET/DROP NOT NULL`
- `ALTER TABLE ADD/DROP CONSTRAINT`
- `CREATE/DROP INDEX`
- `CREATE/ALTER/DROP SEQUENCE`
- `CREATE OR REPLACE VIEW`
- `CREATE OR REPLACE FUNCTION`
- `CREATE OR REPLACE PROCEDURE`
- `CREATE OR REPLACE TRIGGER` nur falls die neutrale Semantik eindeutig
  renderbar ist, sonst Drop/Create mit Risiko-Diagnose

Offene Punkte:

- Locking-/Transactional-DDL-Hinweise
- Typkonvertierungen mit `USING`
- Extension-/Spatial-Abhaengigkeiten
- Materialized View Refresh/Dependencies

### 6.3 MySQL

Erste Zieloperationen:

- `ALTER TABLE ADD COLUMN`
- `ALTER TABLE DROP COLUMN`
- `MODIFY COLUMN`
- `ALTER TABLE ADD/DROP INDEX`
- `ALTER TABLE ADD CONSTRAINT`
- `ALTER TABLE DROP FOREIGN KEY`
- View/Routine-Replacement mit bestehenden Helpern

Besondere Risiken:

- MySQL braucht oft vollstaendige Spaltendefinitionen bei `MODIFY COLUMN`
- Foreign-Key-Drop verwendet Constraint-Namen
- CHECK-Semantik und Enforcement haengen von Version/Engine ab
- Sequence-Emulation darf nicht mit nativen Sequence-Annahmen vermischt werden

### 6.4 SQLite

SQLite braucht explizite Rebuild-Semantik.

Direkt renderbar:

- `ADD COLUMN` unter SQLite-Einschraenkungen
- `DROP COLUMN` nur fuer ausreichend moderne SQLite-Versionen und nur wenn
  keine blockierenden Constraints/Indizes/Trigger betroffen sind
- `CREATE/DROP INDEX`
- `CREATE/DROP VIEW`
- einfache Create/Drop-Operationen fuer Tabellen

Rebuild-pflichtig:

- `ALTER COLUMN TYPE`
- viele Constraint-Aenderungen
- PK-Aenderungen
- bestimmte Drop-Column-Faelle

Rebuild-Operationen sollten im `DiffResult` nicht als einzelne SQL-Zeilen
versteckt werden. Sie brauchen eine eigene Operation oder Markierung:

```kotlin
data class RebuildTable(
    val tableName: String,
    val oldTable: TableDefinition,
    val newTable: TableDefinition,
    val columnMapping: Map<String, String>,
    ...
)
```

Offene Entscheidung:

- Wird `RebuildTable` direkt vom Planner erzeugt, wenn Ziel-Dialekt SQLite ist?
- Oder bleibt `DiffResult` dialektneutral und der SQLite-Generator hebt
  einzelne Operationen in einen Rebuild-Plan?

Empfehlung:

- `DiffResult` bleibt dialektneutral.
- Ein nachgelagerter `DialectMigrationPlan` darf Operationen fuer SQLite zu
  Rebuild-Schritten gruppieren.

---

## 7. CLI-Vertrag fuer spaeteren Milestone

### 7.1 `schema migrate`

Vorgeschlagener Zielvertrag:

```bash
d-migrate schema migrate \
  --source desired.yaml \
  --target db:staging \
  --output migration.sql \
  --generate-rollback \
  --report migration-report.yaml
```

Flag-Skizze:

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Operand | Soll-Schema, zunaechst Datei |
| `--target` | Ja | Operand | Ist-Datenbank oder spaeter Ist-Schema |
| `--output` | Nein | Pfad | Up-SQL-Ausgabe |
| `--rollback-output` | Nein | Pfad | Down-SQL-Ausgabe, wenn getrennt |
| `--generate-rollback` | Nein | Boolean | Down-Plan erzeugen |
| `--allow-destructive` | Nein | Boolean | destruktive Operationen erlauben |
| `--plan-only` | Nein | Boolean | nur DiffResult/Report schreiben, kein SQL |
| `--report` | Nein | Pfad | strukturierter Plan-/Risiko-Report |

Exit-Codes sollten sich an bestehenden Mustern orientieren:

| Exit | Bedeutung |
|---|---|
| `0` | Erfolg |
| `1` | keine Migration erzeugt, weil kein Diff vorhanden ist oder plan-only ohne Fehler |
| `2` | ungueltige CLI-Argumente |
| `3` | Schema-Validierungsfehler |
| `4` | Verbindungsfehler |
| `7` | I/O-, Planungs- oder Renderfehler |
| `8` | destruktive/nicht reversible Operation ohne Freigabe |

Die konkrete Exit-Code-Matrix muss vor Implementierung mit `spec/cli-spec.md`
abgeglichen werden.

### 7.2 `schema rollback`

`schema rollback` sollte keine Magie aus einer Live-Datenbank erraten.

Zwei belastbare Varianten:

1. Rollback aus gespeichertem Down-SQL:

   ```bash
   d-migrate schema rollback --source rollback.sql --target db:staging
   ```

2. Rollback aus gespeichertem `DiffResult`/Plan-Artefakt:

   ```bash
   d-migrate schema rollback --source migration-plan.yaml --target db:staging
   ```

Variante 2 setzt voraus, dass `DiffResult` serialisierbar und versioniert ist.
Das sollte erst nach Stabilisierung des internen Vertrags als Nutzervertrag
freigegeben werden.

---

## 8. Serialisierung und Reports

`DiffResult` sollte intern zuerst als Kotlin-Vertrag stabilisiert werden. Eine
oeffentliche YAML/JSON-Serialisierung ist nuetzlich, aber riskanter, weil sie
langfristig kompatibel bleiben muss.

Empfohlene Stufen:

1. Interner `DiffResult`-Vertrag im Core.
2. Stabiler Report-Vertrag fuer CLI/MCP, nicht identisch mit allen
   Implementierungsdetails.
3. Optional spaeter: versioniertes `DiffResult`-Artefakt als Input fuer
   `schema rollback`.

Report-Inhalte:

- Quell- und Ziel-Fingerprint
- Anzahl Operationen nach Typ/Phase
- destruktive Operationen
- nicht reversible Operationen
- manuelle Aktionen
- Dialekt-Warnings
- erzeugte Artefakte
- ausgelassene Operationen

---

## 9. Arbeitspakete

### Phase A - Spezifikation und Namensbereinigung

- `spec/cli-spec.md` fuer `schema migrate`/`schema rollback` schaerfen
- `spec/design.md` um `DiffResult` als Zwischenvertrag ergaenzen
- private `SchemaComparator.DiffResult<N, D>` umbenennen
- klare Begriffe festlegen:
  - `SchemaDiff` = struktureller Unterschied
  - `DiffView` = stabiler Compare-Output
  - `DiffResult` = migrationsfaehiger Operationsplan
  - `MigrationDdlResult` = gerenderte Up-/Down-DDL

### Phase B - Core-Vertrag

- `DiffResult`
- `DiffOperation`
- `DiffPhase`
- `DiffObjectType`
- `Reversibility`
- `OperationRisk`
- `DiffDiagnostic`
- stabile Operation-IDs
- Tests fuer leere Diffs, deterministische Sortierung und Risiko-Mapping

### Phase C - Planner

- `DiffPlanner` implementieren
- Mapping von `SchemaDiff` zu Operationen
- Dependency-/Phasen-Sortierung
- Reversibilitaetsklassifizierung
- destruktive Operationen markieren
- Rename-Kandidaten nur diagnostizieren, nicht automatisch migrieren

### Phase D - Dialekt-DDL fuer erste Matrix

Erste realistische Matrix:

- PostgreSQL: Tabellen, Spalten, Constraints, Indizes, Views
- MySQL: Tabellen, Spalten, Constraints, Indizes, Views
- SQLite: Tabellen, Spalten, Indizes, einfache Views, Rebuild-Diagnose

Nicht in der ersten Matrix:

- vollstaendige Routine-Migration
- vollstaendige Trigger-Migration
- Sequence-Emulation-Migrationen
- automatische Daten-Transformationen

### Phase E - CLI-Runner

- `SchemaMigrateRunner`
- Operand-Aufloesung fuer Soll-Schema und Ist-Datenbank
- Reverse des Ist-Zustands
- Compare
- Planner
- Dialekt-DDL
- `--plan-only`
- `--allow-destructive`
- `--generate-rollback`
- Report-Ausgabe
- sauberes Exit-Code-Mapping

### Phase F - Tests und Smokes

- Core-Planner-Tests
- DDL-Golden-Tests pro Dialekt
- CLI-Tests fuer Flags, Exit-Codes und Reports
- Docker-Smokes:
  - PostgreSQL Up
  - PostgreSQL Up + Down
  - MySQL Up
  - SQLite Up
- Roundtrip-Smoke:
  - Ausgangsschema in DB erzeugen
  - Zielschema migrieren
  - reverse
  - compare gegen Zielschema

---

## 10. Akzeptanzkriterien

Ein erster `DiffResult`-Milestone ist belastbar, wenn gilt:

- `SchemaDiff` bleibt als Compare-Kernvertrag erhalten.
- Der neue `DiffResult` enthaelt deterministisch sortierte Operationen.
- Jede Operation hat Phase, ID, Risiko und Reversibilitaet.
- Destruktive Operationen werden ohne Freigabe nicht ausfuehrbar gerendert.
- `--generate-rollback` erzeugt keine falschen Down-Schritte fuer
  `NOT_REVERSIBLE`.
- PostgreSQL, MySQL und SQLite haben jeweils mindestens einen echten
  Up-Smoke.
- Mindestens PostgreSQL hat einen Up+Down-Smoke.
- `schema compare`-Output bleibt rueckwaertskompatibel und serialisiert nicht
  ploetzlich das interne `DiffResult`.
- 0.7.0-Tool-Exports bleiben full-state und unveraendert.

---

## 11. Offene Entscheidungen

- Soll der erste `schema migrate`-Slice nur Datei-zu-DB unterstuetzen oder
  direkt auch Datei-zu-Datei als SQL-Plan?
- Wird `DiffResult` selbst serialisiert oder nur ein stabiler Report?
- Wie streng soll `--generate-rollback` bei teilweise nicht reversiblen
  Operationen sein: harter Fehler oder Down-Datei plus Warn-/Manual-Blocks?
- Wie wird Nutzerbestaetigung fuer destruktive Operationen im Non-TTY-Betrieb
  modelliert?
- Soll SQLite-Rebuild als dialektneutraler Operationstyp oder als
  dialektspezifischer Folgeplan abgebildet werden?
- Ab wann werden Rename-Hints zu expliziten Rename-Operationen?

Empfehlung fuer den ersten Slice:

- kein automatisches Rename
- kein serialisierter `DiffResult` als oeffentlicher Input
- `--allow-destructive` als Pflicht fuer destruktive Up-DDL
- harter Fehler bei `--generate-rollback`, wenn nicht reversible Operationen
  enthalten sind, ausser der Nutzer setzt spaeter einen expliziten
  `--allow-partial-rollback`
- SQLite-Rebuild zunaechst als Dialektfolgeplan, nicht als Kernoperation
