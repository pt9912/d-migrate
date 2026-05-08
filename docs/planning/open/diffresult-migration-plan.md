# Implementierungsplan: `DiffResult` fuer diff-basierte Migrationen

> Status: Draft (2026-05-03), aktualisiert fuer 0.9.6
>
> Zweck: Planung fuer einen stabilen, migrationsfaehigen `DiffResult`-
> Vertrag als Grundlage fuer den 0.9.6-Migrationspfad `schema migrate` und
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
existierenden `SchemaDiff` und dem fuer 0.9.6 geplanten, wirklich
ausfuehrbaren Migrationspfad.

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
- Die mit 0.7.0 eingefuehrten Tool-Exports verwenden diesen full-state-Pfad
  bewusst weiter.

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

## 2. Abgrenzung zu 0.7.0 und Ziel 0.9.6

0.7.0 ist in diesem Dokument der historische baseline-/full-state-Pfad: Es
exportiert Artefakte aus einem einzelnen neutralen Schema:

```bash
d-migrate export flyway --source schema.yaml --target postgresql --output migrations
```

Dieser Pfad bleibt unveraendert.

`DiffResult` gehoert zum fuer 0.9.6 geplanten Migrationspfad:

```bash
d-migrate schema migrate --source desired.yaml --target db:staging --output migration.sql
```

Ziel fuer 0.9.6 ist, dass `migrate up/down` als zusammenhaengender Ablauf
funktioniert:

- `up`: Ist-Datenbank lesen, gegen Soll-Schema diffen, Up-DDL planen, rendern
  und wahlweise ausfuehren bzw. als SQL ausgeben.
- `down`: aus demselben Plan ein Rollback-Artefakt erzeugen und dieses
  Rollback gegen die Ziel-Datenbank ausfuehren koennen.

Nicht Teil von 0.9.6 sind fortgeschrittene Rollback-Varianten wie
versionierte `DiffResult`-Artefakte als CLI-Input, Teil-Rollbacks,
automatische Rename-Mappings oder Datei-zu-Datei-Migrationsplanung ohne
Live-Ist-Zustand.

Die fachliche Unterscheidung ist verbindlich:

| Pfad | Eingaben | Ergebnis | Grundlage |
|---|---|---|---|
| `schema generate` | ein Schema | full-state-DDL | `DdlGenerator.generate(schema)` |
| `export flyway|...` | ein Schema | Tool-Artefakt, full-state | `MigrationBundle` + `DdlResult` |
| `schema compare` | zwei Schemata/Operanden | Diagnose/Report | `SchemaDiff` + `DiffView` |
| `schema migrate` | Ist-Zustand + Soll-Schema | inkrementelles Up/Down | `DiffResult` |

Nicht akzeptabel:

- `DiffResult` in die historischen 0.7.0-Tool-Exports hineinzuziehen
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
    fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult
    fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult
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
- historische Tool-Export-Artefakte aus 0.7.0 ersetzen
- Rename-Detection erraten, solange es dafuer keine robuste Semantik gibt
- Datenmigrationen fuer geaenderte Spalteninhalte automatisch ableiten

### 4.2 Grobe Typform

Skizze:

```kotlin
data class DiffResult(
    val current: DiffEndpoint,
    val desired: DiffEndpoint,
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

Der erste interne Vertrag bettet `schemaDiff` bewusst ein:

- `SchemaDiff` bleibt die verlustarme strukturelle Diagnose.
- `DiffResult.operations` ist die ausfuehrbare Migrationsebene.
- Reports koennen beide Ebenen zeigen, ohne den Operationsplan zu
  ueberfrachten.
- Reine Schema-Metadaten-Diffs (`name`, `version`) erzeugen keine
  DDL-Operation. Fuer Live-DB-Operanden muessen reverse-generierte Marker vor
  Compare/Planning mit derselben Semantik wie `schema compare` normalisiert
  werden, damit synthetische `__dmigrate_reverse__`-Werte keine Scheinmigration
  ausloesen.
- Falls `DiffResult` spaeter als oeffentliches Artefakt serialisiert wird,
  kann diese Einbettung durch eine versionierte Projektion oder einen
  Fingerprint ersetzt werden. Das ist keine Entscheidung fuer den ersten
  internen Core-Vertrag.

### 4.2.1 Stabile Operation-IDs

Operation-IDs muessen deterministisch und artefaktfaehig sein. Sie duerfen
nicht von nicht stabilen Laufzeitdetails wie Objektidentitaet, HashMap-
Iteration oder zufaelligen UUIDs abhaengen.

Verbindlich fuer den ersten Slice:

- Eine Operation-ID wird aus stabiler fachlicher Semantik abgeleitet:
  - Planrichtung, zum Beispiel `current->desired`
  - Operationstyp, zum Beispiel `AddColumn`
  - `objectRef.type`
  - kanonisch normalisierte `objectRef.path`
  - kanonischer Payload-Fingerprint der fuer Rendering/Rollback relevanten
    Vorher-/Nachher-Werte
- Kollidieren zwei IDs, erhaelt die spaetere Operation nach deterministischer
  Sortierung einen stabilen Suffix wie `#2`, `#3`.
- Eine reine Listenposition nach Toposortierung reicht nicht als ID, darf aber
  als Tie-Breaker fuer Kollisionssuffixe verwendet werden.
- Die IDs bleiben innerhalb eines Plan-Artefakts und seiner Up-/Down-/Report-
  Artefakte referenzierbar. Eine langfristige, release-uebergreifende
  Kompatibilitaetsgarantie fuer IDs entsteht erst, wenn `DiffResult`
  oeffentlich serialisiert wird.

### 4.3 Operationen

Vorgeschlagene Basiskategorien:

```kotlin
sealed interface DiffOperation {
    val id: String
    val objectRef: DiffObjectRef
    val objectType: DiffObjectType
        get() = objectRef.type
    val phase: DiffPhase
    val dependencies: Set<String>
    val reversibility: Reversibility
    val risk: OperationRisk
}

data class DiffObjectRef(
    val type: DiffObjectType,
    val path: List<String>,
) {
    val displayName: String = path.joinToString(".")
}
```

`objectRef.path` ist die qualifizierte fachliche Identitaet:

- Tabelle: `["orders"]`
- Spalte: `["orders", "status"]`
- Constraint: `["orders", "fk_orders_customer"]`
- Index: `["orders", "idx_orders_created_at"]`
- Schemaweites Objekt: `["status_enum"]`

Die konkrete Operation muss ausserdem den fuer Rendering und Rollback
notwendigen Payload tragen. Beispiele:

- `CreateTable`: neue `TableDefinition`
- `DropTable`: alte `TableDefinition`
- `AddColumn`: Tabellenname, Spaltenname, neue `ColumnDefinition`
- `DropColumn`: Tabellenname, Spaltenname, alte `ColumnDefinition`
- `AlterColumnType`: Tabellenname, Spaltenname, alter und neuer Typ
- `AlterColumnDefault`: Tabellenname, Spaltenname, alter und neuer Default
- `AddConstraint`: Tabellenname, neue `ConstraintDefinition`
- `DropConstraint`: Tabellenname, alte `ConstraintDefinition`
- `ReplaceView`: alte und neue `ViewDefinition`

Damit bleibt `DiffResult` ein ausfuehrbarer Plan und wird nicht zu einem
reinen Report ueber Objektarten und Namen.

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

Die Phasen sind jedoch nur der Standard-Tie-Breaker fuer Operationen ohne
direkte Abhaengigkeit. Verbindlich ist:

1. Der Planner erzeugt explizite Dependencies zwischen Operationen.
2. Die endgueltige Reihenfolge entsteht durch topologische Sortierung.
3. `DiffPhase` sortiert nur Operationen, die dependency-seitig unabhaengig
   sind.
4. Wenn Phasen- und Dependency-Reihenfolge widersprechen, gewinnt die
   Dependency-Reihenfolge.

Das ist besonders wichtig fuer Drop-Operationen. Beispiele:

- `DropTable(orders)` haengt von `DropView(...)`, `DropTrigger(...)`,
  `DropIndex(...)` und `DropConstraint(...)` ab, soweit diese Objekte auf
  `orders` zeigen.
- `DropColumn(orders.customer_id)` haengt von dem Entfernen betroffener
  Constraints, Indizes, Views und Trigger ab.
- Create-Operationen laufen in der natuerlichen Richtung: referenzierte Typen,
  Tabellen und Spalten vor Constraints, Indizes, Views und Triggern.

View-Abhaengigkeiten duerfen dabei nicht aus reinen Namens-Heuristiken geraten
werden. Der erste Slice braucht entweder explizite Abhaengigkeitsdaten aus dem
Reverse-/Reader-Pfad oder eine klare Diagnose mit Blocker fuer betroffene
Operationen. Konkret:

- `ViewDefinition.dependencies` muss fuer planungsrelevante Tabellen- und
  Spaltenabhaengigkeiten ausreichen oder durch einen separaten
  Dependency-Index ergaenzt werden.
- Kann der Planner bei `DropColumn`, `AlterColumnType`,
  `AlterColumnNullability` oder `DropTable` nicht beweisen, welche Views
  betroffen sind, darf der Generator diese Operation nicht blind rendern.
- Routine-Abhaengigkeiten von Views bleiben separat zu behandeln; fehlende
  Routine-Migration darf nicht zu einem unvollstaendigen View-Replacement
  fuehren.

Ein Generator darf diese Reihenfolge nicht rein nach Phase neu sortieren.

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
- `MANUAL_REQUIRED` blockiert im ersten Slice die automatische Down-Erzeugung.
  Teil-Rollbacks oder manuell ergaenzte Down-Schritte sind spaeterer Scope.
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

Ohne diesen Schalter ist die Semantik verbindlich getrennt:

- `--plan-only` darf einen Plan- und Risiko-Report erzeugen und mit Exit `0`
  enden, solange Planning selbst erfolgreich war.
- Ein normaler Dry-Run ohne `--execute` versucht bereits, ein
  ausfuehrbares Up-SQL-Artefakt zu rendern. Enthaelt der Plan destruktive oder
  bestaetigungspflichtige Operationen und fehlt `--allow-destructive`, endet
  der Lauf mit Exit `8` und
  `blockedReason = DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION`; es wird kein
  ausfuehrbares Up-SQL geschrieben.
- `--execute` darf destruktive oder bestaetigungspflichtige Operationen nur mit
  `--allow-destructive` ausfuehren.

Damit bleibt ein Risiko-Report ohne Freigabe moeglich, aber ein SQL-Artefakt,
das wie eine freigegebene Migration aussieht, entsteht nicht versehentlich.

---

## 5. Ableitungspipeline

### 5.1 Vorgeschlagene Architektur

```text
current schema  -> materialisiertes Ist-Schema
desired schema  -> materialisiertes Soll-Schema
                         |
                         v
         reverse marker / metadata normalization
                         |
                         v
         SchemaComparator.compare(current, desired)
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
- erhaelt bereits normalisierte Schema-Zustaende, wenn ein Operand aus
  Reverse/Live-Introspection stammt
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
- rendert Down-DDL in inverser, dependency-sicherer Reihenfolge. Die
  Up-Toposortierung darf nicht einfach wiederverwendet werden; zuerst muessen
  abhaengige Down-Operationen laufen, danach ihre Voraussetzungen. Beispiel:
  Down fuer `CreateTable` + `CreateView` muss `DROP VIEW` vor `DROP TABLE`
  rendern.

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
    fun plan(current: SchemaDefinition, desired: SchemaDefinition, diff: SchemaDiff): DiffResult
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
- `blockedReason`

`blockedReason` trennt mindestens:

- `DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION`
- `ROLLBACK_NOT_POSSIBLE`
- `MANUAL_ACTION_REQUIRED`
- `TARGET_STATE_MISMATCH`
- `DIALECT_UNSUPPORTED_OPERATION`

Damit kann ein Runner unterscheiden, ob der Up-Plan wegen fehlendem
`--allow-destructive` blockiert ist, ob nur `--generate-rollback` wegen
`NOT_REVERSIBLE` oder manueller Arbeit scheitert, ob `schema rollback` gegen
einen unerwarteten Zielzustand laufen wuerde, oder ob der Ziel-Dialekt eine
Operation nicht rendern kann.

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
- `CREATE OR REPLACE VIEW`

Views sind im ersten Slice nur fuer einfache, nicht materialisierte Views
enthalten, deren Abhaengigkeiten im Plan eindeutig aufloesbar sind. Der Planner
muss betroffene Views vor abhaengigen Drop-/Alter-Operationen entfernen bzw.
nach den referenzierten Tabellen, Spalten und Routinen wieder erzeugen. Views
mit nicht aufloesbaren Routine- oder Materialized-View-Abhaengigkeiten werden
diagnostiziert statt blind gerendert.

Fuer PostgreSQL ist dafuer vor der View-Migration mindestens ein belastbarer
Tabellen-/Spalten-Dependency-Index noetig, zum Beispiel ueber `pg_depend` /
`pg_rewrite` oder eine gleichwertige Projektion. Der heute vorhandene
Function-Dependency-Anteil reicht fuer `DropColumn`/`AlterColumn`/`DropTable`
nicht aus. Fehlt dieser Index im ersten Slice, muessen betroffene
View-Replacements und schemaaendernde Operationen mit
`DIALECT_UNSUPPORTED_OPERATION` bzw. einer spezifischen Planner-Diagnose
blockieren, statt SQL mit unbekannten Abhaengigkeiten zu erzeugen.

Bewusst nicht in der ersten PostgreSQL-Zielmatrix:

- `CREATE/ALTER/DROP SEQUENCE`
- `CREATE OR REPLACE FUNCTION`
- `CREATE OR REPLACE PROCEDURE`
- `CREATE OR REPLACE TRIGGER`

Diese Operationen bleiben als `DiffOperation`-Kategorien vorgesehen, werden im
ersten DDL-Slice aber nur geplant bzw. als nicht renderbar diagnostiziert.

Offene Punkte:

- Locking-/Transactional-DDL-Hinweise
- Typkonvertierungen mit `USING`
- Extension-/Spatial-Abhaengigkeiten
- Materialized View Refresh/Dependencies

### 6.3 MySQL

Erste Zieloperationen:

- `CREATE TABLE`
- `DROP TABLE`
- `ALTER TABLE ADD COLUMN`
- `ALTER TABLE DROP COLUMN`
- `MODIFY COLUMN`
- `ALTER TABLE ADD/DROP INDEX`
- `ALTER TABLE ADD CONSTRAINT`
- `ALTER TABLE DROP FOREIGN KEY`
- View-Replacement mit bestehenden Helpern

Auch fuer MySQL gilt: View-Replacement ist im ersten Slice auf einfache Views
mit aufloesbaren Tabellen-/Spaltenabhaengigkeiten begrenzt.

Fuer MySQL muss die Abhaengigkeitsquelle ebenfalls explizit sein, zum Beispiel
ueber `information_schema.VIEW_TABLE_USAGE` /
`information_schema.VIEW_COLUMN_USAGE`, soweit fuer die Zielversion verfuegbar.
Wenn der Dialekt oder die Version diese Information nicht belastbar liefern
kann, sind abhaengige View-Replacements im ersten Slice nicht renderbar.

Bewusst nicht in der ersten MySQL-Zielmatrix:

- Routine-Migration
- Trigger-Migration
- Sequence-Emulation-Migration

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

SQLite-Rebuilds sollten nicht als einzelne SQL-Zeilen versteckt werden. Der
dialektneutrale `DiffResult` bleibt jedoch bei den fachlichen Operationen
(`AlterColumnType`, `DropConstraint`, `AddConstraint`, usw.). Erst ein
nachgelagerter, dialektspezifischer Folgeplan darf daraus einen
`RebuildTable`-Schritt bilden:

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

## 7. CLI-Vertrag fuer 0.9.6

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
| `--plan-only` | Nein | Boolean | nur stabilen Plan-/Risiko-Report schreiben, kein SQL |
| `--report` | Nein | Pfad | strukturierter Plan-/Risiko-Report |
| `--execute` | Nein | Boolean | Up-DDL nach erfolgreichem Rendern gegen `--target` ausfuehren |
| `--dry-run` | Nein | Boolean | Plan/SQL erzeugen, aber nichts ausfuehren |

Die CLI-Namen folgen dem bestehenden Stub in `spec/cli-spec.md`:
`--source` bezeichnet das Soll-Schema, `--target` die Ist-Datenbank. Intern
soll der Runner diese Werte sofort auf die eindeutigen Begriffe `desired` und
`current` abbilden. `SchemaComparator.compare(current, desired)` ist die
verbindliche Richtung fuer den Operationsplan.

Fuer 0.9.6 muss `schema migrate` nicht nur SQL schreiben, sondern einen
ausfuehrbaren Up-Pfad tragen:

1. Ist-Zustand aus `--target` introspektieren.
2. Soll-Zustand aus `--source` laden und validieren.
3. Reverse-generierte Marker und synthetische Metadaten beider Operanden
   normalisieren, bevor verglichen wird. Der Codepfad soll die bestehende
   `schema compare`-Semantik teilen statt eine zweite Normalisierung zu
   erfinden.
4. `DiffResult` in Richtung `current -> desired` planen.
5. Up-DDL rendern, sofern kein Risiko-, Rollback- oder Dialektblocker greift.
6. Bei `--generate-rollback` Down-DDL aus demselben Plan rendern.
7. Bei `--execute` Up-DDL gegen `--target` ausfuehren.
8. Nach Ausfuehrung den Zielzustand erneut introspektieren und gegen
   `desired` vergleichen.

`--dry-run` ist der Default, solange `--execute` nicht gesetzt ist. Damit kann
der gleiche Befehl zuerst den Plan und beide SQL-Artefakte erzeugen und danach
bewusst ausgefuehrt werden. Diese Aussage gilt fuer renderbare Plaene. Ist der
Plan destruktiv oder bestaetigungspflichtig und fehlt `--allow-destructive`,
endet der normale Dry-Run mit Exit `8` nach Plan-/Report-Erzeugung, aber ohne
ausfuehrbares Up-SQL. Wer nur den Risiko-Report ohne Freigabe sehen will, nutzt
`--plan-only`.

Exit-Codes sollten sich an bestehenden Mustern orientieren:

| Exit | Bedeutung |
|---|---|
| `0` | Erfolg |
| `2` | ungueltige CLI-Argumente |
| `3` | Schema-Validierungsfehler |
| `4` | Verbindungsfehler |
| `7` | I/O-, Planungs- oder Renderfehler |
| `8` | Migration durch Risiko-, Rollback- oder Dialektblocker nicht renderbar |

Exit `8` ist ein neuer geplanter CLI-Code fuer blockierte Migrationen. Phase A
muss deshalb nicht nur den lokalen `schema migrate`-Abschnitt, sondern auch die
globale Exit-Code-Tabelle in `spec/cli-spec.md` erweitern. Bis diese
Spezifikationsaenderung erfolgt ist, darf keine Implementierung den Code
stillschweigend verwenden.

Exit `0` gilt auch fuer erfolgreiche No-op-Laeufe, wenn kein Diff vorhanden
ist, und fuer erfolgreiche `--plan-only`-Laeufe. Das unterscheidet
`schema migrate` bewusst von `schema compare`, wo Exit `1` "Unterschiede
gefunden" bedeutet. Ein Plan mit Risiken bleibt nur dann erfolgreich, wenn er
ausschliesslich als Plan-/Risiko-Report angefordert wurde. Sobald der Lauf ein
ausfuehrbares Up- oder Down-SQL-Artefakt rendern oder ausfuehren soll, fuehren
fehlende Freigaben, nicht moeglicher Rollback oder nicht renderbare
Dialektoperationen zu Exit `8`.

Exit `8` muss im strukturierten Fehler mindestens folgende Faelle
unterscheiden:

- destruktive Up-Operation ohne `--allow-destructive`
- `--generate-rollback` angefordert, aber mindestens eine Operation ist
  `NOT_REVERSIBLE`
- `--generate-rollback` angefordert, aber mindestens eine Operation ist
  `MANUAL_REQUIRED`
- `schema rollback --execute` angefordert, aber die Ziel-Datenbank entspricht
  nicht dem erwarteten Post-Up-/Soll-Fingerprint
- Ziel-Dialekt kann eine geplante Operation nicht rendern

Die konkrete Exit-Code-Matrix muss vor Implementierung mit `spec/cli-spec.md`
abgeglichen werden.

### 7.2 `schema rollback`

`schema rollback` ist fuer 0.9.6 der Down-Ausfuehrungspfad fuer das von
`schema migrate --generate-rollback` erzeugte Down-SQL. Er sollte keine Magie
aus einer Live-Datenbank erraten.

Fuer 0.9.6 verbindlich:

```bash
d-migrate schema rollback --source rollback.sql --target db:staging --execute --allow-destructive
```

Flag-Skizze:

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | von `schema migrate --generate-rollback` erzeugtes Down-SQL |
| `--target` | Ja | Operand | Ziel-Datenbank |
| `--execute` | Nein | Boolean | Down-SQL gegen `--target` ausfuehren |
| `--dry-run` | Nein | Boolean | Validierung/Preview, keine Ausfuehrung |
| `--allow-destructive` | Nein | Boolean | destruktive Down-Operationen erlauben |

Der Runner:

1. liest ausschliesslich das gespeicherte Down-SQL aus `--source`,
2. liest daraus den von `schema migrate --generate-rollback` erzeugten
   maschinenlesbaren `d-migrate`-Metadatenblock,
3. introspektiert bei `--execute` den aktuellen Zielzustand und vergleicht ihn
   mit dem im Metadatenblock erwarteten Post-Up-/Soll-Fingerprint,
4. bricht bei abweichendem Zielzustand mit Exit `8` und
   `blockedReason = TARGET_STATE_MISMATCH` ab,
5. verlangt `--allow-destructive`, wenn der Metadatenblock destruktive
   Down-Operationen ausweist,
6. fuehrt es bei `--execute` gegen `--target` aus,
7. protokolliert ausgefuehrte Statements und Fehler,
8. fuehrt ohne `--execute` nur Validierung/Preview aus.

Der Metadatenblock ist kein oeffentliches `DiffResult`-Artefakt. Er ist ein
schmaler Header im SQL-Artefakt, der mindestens enthaelt:

- Formatkennung und Version, zum Beispiel `d-migrate rollback-sql v1`
- Fingerprint des Ist-Zustands vor Up
- Fingerprint des Soll-Zustands
- Fingerprint des Zielzustands nach Up, wenn `schema migrate --execute` genutzt
  wurde; ohne Ausfuehrung ist dies der erwartete Soll-Fingerprint
- Operation-IDs, aus denen Down-SQL gerendert wurde
- Risiko-Zusammenfassung fuer Down, insbesondere `destructive` und
  `dataLossPossible`
- Ziel-Dialekt

Fehlt dieser Metadatenblock, darf `schema rollback` das SQL im ersten Slice nur
als Preview/Validierung behandeln. Eine Ausfuehrung ohne Metadatenblock ist
spaeterer Scope oder braucht einen eigenen expliziten Unsafe-Schalter.

Spaeter belastbare Variante:

1. Rollback aus gespeichertem `DiffResult`/Plan-Artefakt:

   ```bash
   d-migrate schema rollback --source migration-plan.yaml --target db:staging
   ```

Diese Variante setzt voraus, dass `DiffResult` serialisierbar und versioniert ist.
Das sollte erst nach Stabilisierung des internen Vertrags als Nutzervertrag
freigegeben werden.

### 7.3 0.9.6 Up/Down-Artefaktvertrag

Ein erfolgreicher 0.9.6-Up/Down-Lauf besteht aus zusammenpassenden Artefakten:

- Up-SQL aus `--output`
- Down-SQL aus `--rollback-output`, wenn `--generate-rollback` gesetzt ist
- strukturierter Report aus `--report`

Bei einem durch Risiken blockierten Dry-Run ohne `--allow-destructive` darf der
Report geschrieben werden, das Up-SQL-Artefakt aber nicht. Ein vorhandener
Ausgabepfad darf nicht mit teilweise gerendertem SQL ueberschrieben werden.

Der Report muss mindestens enthalten:

- Fingerprint des Ist-Zustands vor Up
- Fingerprint des Soll-Zustands
- Fingerprint des Zielzustands nach Up, wenn `--execute` genutzt wurde
- Pfade zu Up- und Down-SQL
- Liste der Operationen, aus denen Up und Down gerendert wurden
- Blocker fuer Down, falls `--generate-rollback` nicht moeglich ist

Down-SQL darf nur erzeugt werden, wenn alle fuer Down benoetigten Operationen
automatisch renderbar sind. Fuer `NOT_REVERSIBLE` bleibt das Verhalten strikt:
Exit `8` mit `blockedReason = ROLLBACK_NOT_POSSIBLE`.

Die Reihenfolge des Down-SQL entsteht aus den inversen Operationen und deren
umgekehrten Abhaengigkeiten. Sie ist nicht identisch mit der Up-Reihenfolge.
Akzeptanzbeispiel: Wenn Up eine Tabelle und danach eine View erzeugt, muss Down
zuerst die View und danach die Tabelle entfernen.

Fuer `MANUAL_REQUIRED` gilt im ersten Slice ebenfalls strikt: Es wird kein
automatisches Down-SQL erzeugt. Der Lauf endet mit Exit `8` und
`blockedReason = MANUAL_ACTION_REQUIRED`. Manuelle Down-Bloecke oder partielle
Rollback-Artefakte bleiben spaeterer Scope.

Automatisch renderbar heisst nicht automatisch risikofrei. Down-Schritte wie
`DropTable` oder `DropColumn`, die aus reversiblen Up-Operationen entstehen,
werden im Down-Artefakt weiterhin als destruktiv markiert. `schema rollback`
darf solche Artefakte bei `--execute` nur mit `--allow-destructive`
ausfuehren.

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

- Ist- und Soll-Fingerprint (`current` / `desired`)
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
- globale Exit-Code-Tabelle in `spec/cli-spec.md` um den geplanten
  Migrations-Blocker-Code `8` ergaenzen oder einen bestehenden Code verbindlich
  wiederverwenden
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
- `DiffObjectRef`
- `DiffPhase`
- `DiffObjectType`
- `Reversibility`
- `OperationRisk`
- `DiffDiagnostic`
- stabile Operation-IDs
- deterministische ID-Bildung aus Operationstyp, Objektpfad und
  Payload-Fingerprint
- Operation-Payloads fuer Rendering und Rollback
- gemeinsamer Normalizer fuer reverse-generierte Schema-Metadaten, den
  `schema compare` und `schema migrate` nutzen
- planungsfaehiger Dependency-Vertrag fuer Views, mindestens Tabellen- und
  Spaltenabhaengigkeiten fuer `DropTable`, `DropColumn` und `AlterColumn*`
- Tests fuer leere Diffs, deterministische Sortierung, Dependency-Sortierung,
  inverse Down-Sortierung, Payload-Mapping, Risiko-Mapping und
  Reverse-Marker-Normalisierung

### Phase C - Planner

- `DiffPlanner` implementieren
- Mapping von `SchemaDiff` zu Operationen
- Dependency-/Phasen-Sortierung
- inverse Dependency-/Phasen-Sortierung fuer Down-Operationen spezifizieren
  und testen
- View-Abhaengigkeiten aus Reader-/Dependency-Daten nutzen; bei fehlender
  belastbarer Abhaengigkeitsinformation blockierende Diagnosen erzeugen
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
- Sequence-Migrationen, inklusive Sequence-Emulationen
- automatische Daten-Transformationen

### Phase E - CLI-Runner

- `SchemaMigrateRunner`
- Operand-Aufloesung fuer Soll-Schema und Ist-Datenbank
- Reverse des Ist-Zustands
- Normalisierung reverse-generierter Schema-Metadaten vor Compare/Planning
- Compare
- Planner
- Dialekt-DDL
- `--plan-only`
- `--allow-destructive`
- `--generate-rollback`
- `--rollback-output`
- `--execute`
- `--dry-run` als Default ohne Ausfuehrung
- Up-DDL gegen `--target` ausfuehren, wenn `--execute` gesetzt ist
- Down-SQL-Artefakt erzeugen, wenn `--generate-rollback` gesetzt ist
- `SchemaRollbackRunner` fuer Down-SQL-Ausfuehrung
- Zielzustands-Pruefung vor `schema rollback --execute`
- Rollback-SQL gegen `--target` ausfuehren, wenn `schema rollback --execute`
  genutzt wird
- `--allow-destructive` auch fuer destruktive Down-SQL-Ausfuehrung auswerten
- Nach-Compare nach Up-Ausfuehrung gegen das Soll-Schema
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
  - MySQL Up + Down fuer die erste reversible Operationsmatrix
  - SQLite Up
  - SQLite Up + Down fuer direkt reversible Operationen ohne Rebuild
- Roundtrip-Smoke:
  - Ausgangsschema in DB erzeugen
  - Zielschema migrieren
  - Rollback-Artefakt aus demselben Plan erzeugen
  - reverse
  - compare nach Up gegen Zielschema
  - Down ausfuehren
  - compare nach Down gegen Ausgangsschema

---

## 10. Akzeptanzkriterien

Ein erster `DiffResult`-Milestone ist belastbar, wenn gilt:

- `SchemaDiff` bleibt als Compare-Kernvertrag erhalten.
- Der neue `DiffResult` enthaelt deterministisch sortierte Operationen.
- Jede Operation hat Phase, ID, qualifizierte `DiffObjectRef`, Risiko,
  Reversibilitaet und den fuer Rendering/Rollback notwendigen Payload.
- Dependency-Sortierung gewinnt gegen reine Phasenreihenfolge, insbesondere bei
  Drop-Operationen fuer Views, Trigger, Constraints, Indizes, Spalten und
  Tabellen.
- Down-DDL wird in inverser, dependency-sicherer Reihenfolge gerendert; abhaengige
  Down-Operationen laufen vor ihren Voraussetzungen.
- Reverse-generierte Schema-Metadaten werden vor Compare/Planning normalisiert,
  sodass synthetische `name`-/`version`-Werte keine Migrationsoperationen
  ausloesen.
- View-Abhaengigkeiten auf Tabellen und Spalten sind fuer Drop-/Alter-Planung
  entweder belastbar bekannt oder die betroffene Migration wird mit Diagnose
  blockiert.
- Destruktive Operationen werden ohne Freigabe nicht als ausfuehrbares SQL
  gerendert oder ausgefuehrt; `--plan-only` darf weiterhin einen Risiko-Report
  erzeugen.
- `--generate-rollback` erzeugt keine falschen Down-Schritte fuer
  `NOT_REVERSIBLE` oder `MANUAL_REQUIRED`.
- Operation-IDs sind deterministisch aus fachlicher Semantik abgeleitet und in
  Up-, Down- und Report-Artefakten referenzierbar.
- `schema migrate --execute` wendet Up-DDL gegen die Ziel-Datenbank an.
- Ein durch fehlendes `--allow-destructive` blockierter Dry-Run ueberschreibt
  kein `--output`-Artefakt mit teilweise gerendertem SQL.
- `schema migrate --generate-rollback --rollback-output ...` erzeugt ein zum
  Up-Plan passendes Down-SQL-Artefakt mit maschinenlesbarem
  `d-migrate`-Metadatenblock.
- `schema rollback --source rollback.sql --target ... --execute` wendet
  nicht destruktives Down-SQL gegen die Ziel-Datenbank an.
- `schema rollback --source rollback.sql --target ... --execute` prueft vor der
  Ausfuehrung, dass der aktuelle Zielzustand zum im Metadatenblock erwarteten
  Post-Up-/Soll-Fingerprint passt, und bricht bei Drift ab.
- `schema rollback --source rollback.sql --target ... --execute` mit
  `--allow-destructive` wendet destruktives Down-SQL nur dann an, wenn der
  Metadatenblock diese Freigabe verlangt und der Nutzer sie explizit setzt.
- Nach `migrate --execute` vergleicht ein Smoke den Zielzustand gegen das
  Soll-Schema.
- Nach `schema rollback --execute` vergleicht ein Smoke den Zielzustand gegen
  das Ausgangsschema.
- PostgreSQL, MySQL und SQLite haben jeweils mindestens einen echten
  Up-Smoke.
- Mindestens PostgreSQL hat einen Up+Down-Smoke.
- `schema compare`-Output bleibt rueckwaertskompatibel und serialisiert nicht
  ploetzlich das interne `DiffResult`.
- 0.7.0-Tool-Exports bleiben full-state und unveraendert.

---

## 11. Entscheidungen fuer den ersten Slice

Der erste `DiffResult`-Slice soll bewusst eng bleiben. Er muss den fachlichen
Kernvertrag stabilisieren, ohne gleichzeitig alle spaeteren Migrationsvarianten
als Nutzervertrag freizugeben.

Zur Vermeidung von Missverstaendnissen ist die Milestone-Grenze:

| Milestone | Enthalten | Nicht enthalten |
|---|---|---|
| 0.7.0 full-state | `schema generate`, Tool-Exports, full-state Rollback-Artefakte | diff-basierte `schema migrate`-Ausfuehrung |
| 0.9.6 erster `DiffResult`-Slice | Datei-zu-DB `schema migrate`, Up-DDL-Ausfuehrung, Down-SQL-Erzeugung, `schema rollback` aus Down-SQL, Risiko-/Rollback-Blocker | gespeicherter `DiffResult` als Rollback-Input, Teil-Rollbacks, Rename-Mappings, Datei-zu-Datei-Planung |
| nach 0.9.6: Rollback-Erweiterungen | versionierte Plan-Artefakte, `schema rollback` aus Plan, optionale Partial-/Manual-Workflows | automatische Datenrekonstruktion nach destruktiven Operationen |

Damit ist `migrate up/down` verbindlicher Bestandteil von 0.9.6: Up wird aus
dem Diff geplant und gegen die Ziel-Datenbank ausgefuehrt, Down wird als
Rollback-SQL aus demselben Plan erzeugt und ueber `schema rollback` wieder
gegen die Ziel-Datenbank ausgefuehrt.

Verbindlich fuer den ersten Slice:

- `schema migrate` unterstuetzt zunaechst Datei-zu-DB:
  - `--source` ist das Soll-Schema als Datei.
  - `--target` ist die Ist-Datenbank.
  - Datei-zu-Datei als reiner SQL-Plan bleibt spaeterer Scope.
- Reverse-generierte Schema-Metadaten werden vor Compare/Planning mit dem
  gemeinsamen Compare-Normalizer neutralisiert.
- `DiffResult` wird nicht als oeffentliches Input-Artefakt serialisiert.
  Stattdessen gibt es einen stabilen Report-Vertrag.
- `--generate-rollback` ist streng:
  - enthaelt der Plan mindestens eine `NOT_REVERSIBLE`-Operation, bricht
    Rollback-Erzeugung mit Exit `8` und `blockedReason = ROLLBACK_NOT_POSSIBLE`
    ab.
  - enthaelt der Plan mindestens eine `MANUAL_REQUIRED`-Operation, bricht
    Rollback-Erzeugung mit Exit `8` und `blockedReason = MANUAL_ACTION_REQUIRED`
    ab.
  - Teil-Rollbacks mit Warn-/Manual-Blocks sind spaeterer Scope.
- `schema rollback` unterstuetzt im ersten Slice die Ausfuehrung von
  gespeichertem Down-SQL aus `--rollback-output`.
- `schema rollback --execute` introspektiert vor der Ausfuehrung den aktuellen
  Zielzustand und vergleicht ihn mit dem im Down-SQL-Metadatenblock erwarteten
  Post-Up-/Soll-Fingerprint. Bei Abweichung endet der Lauf mit Exit `8` und
  `blockedReason = TARGET_STATE_MISMATCH`.
- Destruktive Down-SQL-Ausfuehrung braucht ebenfalls explizit
  `--allow-destructive`; die Entscheidung basiert auf dem Metadatenblock im
  erzeugten Down-SQL-Artefakt.
- Destruktive Up-DDL braucht explizit `--allow-destructive`.
  Ohne diesen Schalter endet ein SQL-rendernder Lauf mit Exit `8` und
  `blockedReason = DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION`; es wird kein
  Up-SQL-Artefakt geschrieben. `--plan-only` bleibt als reiner Risiko-Report
  erlaubt.
- Non-TTY-Betrieb nutzt keine interaktive Rueckfrage. Die Bestaetigung erfolgt
  ausschliesslich ueber explizite Flags wie `--allow-destructive`.
- Rename-Hints bleiben reine Diagnose. Es gibt kein automatisches Rename und
  keine `RenameTable`-/`RenameColumn`-Operation im ersten Slice.
- SQLite-Rebuild bleibt dialektspezifischer Folgeplan und wird nicht als
  Kernoperation im dialektneutralen `DiffResult` modelliert.

Bewusst spaeter zu entscheiden:

- versionierte `DiffResult`-Serialisierung als moeglicher Input fuer
  `schema rollback`
- `--allow-partial-rollback` oder ein aequivalenter Vertrag fuer bewusst
  unvollstaendige Down-Artefakte
- Datei-zu-Datei-Planung ohne Live-Datenbank als eigener CLI-Modus
- explizite Rename-Operationen mit Nutzer-Mapping
- konkrete Ausgestaltung des SQLite-`DialectMigrationPlan`, insbesondere:
  - Spaltenmapping
  - temporaere Namen
  - Index-/Constraint-Wiederaufbau
  - Fehler-Rollback bei abgebrochenem Rebuild
