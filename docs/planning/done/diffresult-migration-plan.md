# Implementierungsplan: `DiffResult` fuer diff-basierte Migrationen

> Status: Done (2026-05-12), 0.9.7-Scope abgeschlossen.
>
> Phasen A-H sind im Code. Phase A-F: Spec, Core-Vertrag, Planner,
> Renderer fuer Postgres/MySQL/SQLite inkl. RebuildTable, CLI-Runner
> mit Up/Down/Execute, Golden-DDL-Tests, Round-Trip-Smokes pro
> Dialekt, Recovery-Pfad, Edge-Cases. Phase G (Dialect-Hardening)
> deckt die in 0.9.7 verbindlichen DoDs: G.1 (SQLite-Cast-Matrix;
> Live-DB-Daten-Preflights Carve-Out 0.9.8+), G.2 (MySQL-
> `VIEW_TABLE_USAGE`-Privilege-Preflight; `VIEW_ROUTINE_USAGE`-Variante
> Carve-Out 0.9.8+), G.3a (PostgreSQL-ReplaceView-Strict-Split bei
> Dependency-Column-Konflikt; Visible-Spaltensignatur-Compatibility =
> G.3b ist Carve-Out 0.9.8+). Phase H (SQLite-Rebuild-Vertrag
> formalisieren): H.1a/H.1b (`SqliteRebuildPlan`-Struct + Planner
> produziert / Renderer konsumiert), H.2 (Temp-Namen-Kollision mit
> `__2`/`__3`-Fallback), H.3a (Drop+Recreate abhaengiger Views/Trigger
> + simpleOps-Absorption), H.4 (6-Punkte-Preflight-Liste mit per-Kind-
> Outcome), H.3b (`SqliteRebuildEmissionMode.EXECUTE` emittiert
> `dmigrate:runner-hook=…`-Marker; `JdbcMigrationExecutor` parsed sie
> und liest/restored prior FK-State via `PRAGMA foreign_keys;`;
> `DdlGenerationOptions.executionMode` schaltet zwischen STANDALONE
> und EXECUTE).
>
> Carve-Outs auf 0.9.8+ (VIEW_ROUTINE_USAGE-Vollabdeckung, SQLite
> Live-Daten-Preflight fuer Casts, PostgreSQL ReplaceView Visible-
> Spaltensignatur-Compatibility, F.4-1/F.4-2 Artefakt-/Runner-
> Vertraege) sind in
> [`docs/planning/in-progress/diffresult-migration-plan-2.md`](../in-progress/diffresult-migration-plan-2.md)
> verfolgt; dessen Workstreams G.1 und A.1 sind bereits umgesetzt
> (2026-05-12).
>
> Zweck: Planung fuer einen stabilen, migrationsfaehigen `DiffResult`-
> Vertrag als Grundlage fuer den 0.9.7-Migrationspfad `schema migrate`
> und diff-basierte Rollback-Pfade.
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
existierenden `SchemaDiff` und dem fuer 0.9.7 geplanten, wirklich
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

## 2. Abgrenzung zu 0.7.0 und Ziel 0.9.7

0.7.0 ist in diesem Dokument der historische baseline-/full-state-Pfad: Es
exportiert Artefakte aus einem einzelnen neutralen Schema:

```bash
d-migrate export flyway --source schema.yaml --target postgresql --output migrations
```

Dieser Pfad bleibt unveraendert.

`DiffResult` gehoert zum fuer 0.9.7 geplanten Migrationspfad:

```bash
d-migrate schema migrate --source desired.yaml --target db:staging --output migration.sql
```

Ziel fuer 0.9.7 ist, dass `migrate up/down` als zusammenhaengender Ablauf
funktioniert:

- `up`: Ist-Zustand aus Datenbank oder Schema-Datei lesen, gegen Soll-Schema
  diffen, Up-DDL planen, rendern und bei DB-Targets wahlweise ausfuehren bzw.
  als SQL ausgeben.
- `down`: aus demselben Plan ein Rollback-Artefakt erzeugen und dieses
  Rollback gegen die Ziel-Datenbank ausfuehren koennen.

Nicht Teil von 0.9.7 sind fortgeschrittene Rollback-Varianten wie
versionierte `DiffResult`-Artefakte als CLI-Input, Teil-Rollbacks,
automatische Rename-Mappings oder automatische Datenrekonstruktion nach
destruktiven Operationen.

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
    val risks: OperationRisks
}

data class DiffObjectRef(
    val type: DiffObjectType,
    val path: List<String>,
) {
    val displayName: String = path.joinToString(".")
}
```

`risks` ist bewusst richtungsabhaengig:

- `risks.up` beschreibt die Operation in Planrichtung `current -> desired`.
- `risks.down` beschreibt den automatisch renderbaren inversen Schritt, falls
  es ihn gibt.
- Bei `NOT_REVERSIBLE` oder `MANUAL_REQUIRED` darf `risks.down` fehlen; der
  Blocker entsteht dann aus `reversibility` und den Generator-Diagnosen.
- Ein `AddColumn` kann deshalb Up-seitig nicht destruktiv sein, aber fuer den
  Down-Schritt ein destruktives `DropColumn`-Risiko tragen.

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
| `CONSTRAINTS` | PK/FK/Unique; Check/Exclude erst, wenn `SchemaDiff` sie verlustfrei liefert |
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
  Teil-Rollbacks oder manuell ergaenzte Down-Schritte sind nicht Bestandteil
  dieses Plans.
- Der Runner muss den Nutzer ueber nicht reversible Operationen informieren.

### 4.6 Risiko- und Bestaetigungsmodell

Destruktive Operationen muessen maschinenlesbar markiert werden.

Skizze:

```kotlin
data class OperationRisks(
    val up: OperationRisk,
    val down: OperationRisk? = null,
)

data class OperationRisk(
    val destructive: Boolean,
    val dataLossPossible: Boolean,
    val requiresTableRewrite: Boolean,
    val requiresManualConfirmation: Boolean,
    val notes: List<DiffDiagnostic> = emptyList(),
)
```

`OperationRisk` beschreibt immer genau eine Ausfuehrungsrichtung. Der
Up-Generator wertet `operation.risks.up` aus. Der Down-Generator erzeugt
inverse Operationen bzw. inverse Dialektschritte und muss deren Risiko aus
`operation.risks.down` oder aus der neu geplanten Down-Richtung ableiten. Ein
Down-Schritt darf nicht still das Up-Risiko wiederverwenden.

Beispiele fuer `requiresManualConfirmation = true`:

- `DropTable`
- `DropColumn`
- potentiell verlustbehaftetes `AlterColumnType`
- `AlterColumnNullability` von nullable auf not null ohne beweisbare
  Datenvorbedingung
- SQLite-Rebuild mit nicht trivialer Datenkopie

Der CLI-Vertrag bekommt einen expliziten Schalter:

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
  `primaryBlockedReason = DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION`;
  die vollstaendige Blocker-Liste enthaelt alle betroffenen Operationen. Es
  wird kein ausfuehrbares Up-SQL geschrieben.
- `--execute` darf destruktive oder bestaetigungspflichtige Operationen nur mit
  `--allow-destructive` ausfuehren.
- Destruktive Down-Operationen, die aus einem reversiblen Up-Plan entstehen,
  blockieren die Erzeugung des Down-SQL-Artefakts nicht allein wegen fehlendem
  `--allow-destructive`. Sie werden im Down-Artefakt und im Report
  maschinenlesbar markiert. Die Freigabe wird erst bei
  `schema rollback --execute` verlangt.

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

- `statements` mit Rueckverweis auf die Operation-IDs, aus denen ein Statement
  entstanden ist
- `operationsRendered`
- `operationsSkipped`
- `manualActions`
- `destructiveOperations`
- `nonReversibleOperations`
- `requiresConfirmation`
- `blockers`
- optional `primaryBlockedReason` fuer kompakte CLI-Fehler

Die Statements duerfen nicht als nackte SQL-Strings modelliert werden. Der
Runner braucht pro gerendertem Statement mindestens:

```kotlin
data class MigrationDdlStatement(
    val sql: String,
    val operationIds: Set<String>,
    val risk: OperationRisk,
    val phase: DiffPhase,
    val notes: List<DiffDiagnostic> = emptyList(),
)
```

Damit koennen Ausfuehrungsfehler, Reports und Rollback-Metadaten praezise auf
die fachlichen Operationen zurueckverweisen. Ein einzelnes SQL-Statement darf
mehrere Operation-IDs tragen, zum Beispiel bei zusammengefassten `ALTER TABLE`-
Statements oder SQLite-Rebuild-Schritten. Ein einzelner fachlicher
`DiffOperation` darf umgekehrt mehrere SQL-Statements erzeugen.

`blockers` ist eine Liste, weil ein Plan gleichzeitig mehrere Ursachen haben
kann, zum Beispiel destruktive Up-Operationen, nicht rollbackfaehige
Operationen und eine dialektseitig nicht renderbare Operation. Ein optionaler
`primaryBlockedReason` darf fuer knappe CLI-Ausgaben genutzt werden, darf aber
die vollstaendige Blocker-Liste nicht ersetzen.

`MigrationBlockedReason` trennt mindestens:

- `DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION`
- `ROLLBACK_NOT_POSSIBLE`
- `MANUAL_ACTION_REQUIRED`
- `TARGET_STATE_MISMATCH`
- `TARGET_DIALECT_MISMATCH`
- `DIALECT_UNSUPPORTED_OPERATION`

Damit kann ein Runner unterscheiden, ob der Up-Plan wegen fehlendem
`--allow-destructive` blockiert ist, ob nur `--generate-rollback` wegen
`NOT_REVERSIBLE` oder manueller Arbeit scheitert, ob `schema rollback` gegen
einen unerwarteten Zielzustand oder falschen Dialekt laufen wuerde, oder ob der
Ziel-Dialekt eine Operation nicht rendern kann.

Skizze:

```kotlin
data class MigrationBlocker(
    val reason: MigrationBlockedReason,
    val operationIds: Set<String> = emptySet(),
    val diagnostics: List<DiffDiagnostic> = emptyList(),
)
```

Ausfuehrungsfehler gehoeren nicht in `blockers`. Sobald der Runner
begonnen hat, DDL gegen eine Ziel-Datenbank auszufuehren, sind Fehler
terminaler Migrationszustand und werden separat berichtet, zum Beispiel mit:

- `executionStarted`
- `executionCompleted`
- `statementsAttempted`
- `lastStatementOperationIds`
- `transactionRolledBack`
- `sideEffectsPossible`
- `executionError`

Solche Fehler werden als `MIGRATION_ERROR` behandelt, nicht als Planungs-,
Render- oder Dialektblocker.

### 6.2 PostgreSQL

Erste Zieloperationen:

- `CREATE TABLE`
- `DROP TABLE`
- `ALTER TABLE ADD COLUMN`
- `ALTER TABLE DROP COLUMN`
- `ALTER TABLE ALTER COLUMN TYPE` nur fuer Typaenderungen, die der PostgreSQL-
  Generator als implizit castbar und ohne `USING` renderbar klassifizieren kann
- `ALTER TABLE ALTER COLUMN SET/DROP DEFAULT`
- `ALTER TABLE ALTER COLUMN SET/DROP NOT NULL`
- `ALTER TABLE ADD/DROP CONSTRAINT` fuer PK/FK/Unique
- `CREATE/DROP INDEX`
- `CREATE OR REPLACE VIEW` nur fuer kompatible View-Aenderungen
- `DROP VIEW` + `CREATE VIEW` fuer View-Replacements, die vor
  Tabellen-/Spaltenaenderungen entfernt und danach wieder aufgebaut werden
- `CREATE/DROP TYPE` fuer einfache PostgreSQL-Enums, soweit sie als
  `CustomTypeDefinition` verlustfrei im Schema vorliegen und ihre Abhaengigkeit
  zu Tabellen/Spalten eindeutig planbar ist

`CHECK`- und `EXCLUDE`-Constraints gehoeren nicht zur ersten PostgreSQL-
Rendermatrix, solange der Compare-Kern sie nicht verlustfrei als `SchemaDiff`
liefert. Der aktuelle Comparator normalisiert diese Constraint-Arten fuer den
Compare-Pfad weg. Dadurch sind Aenderungen an diesen Constraints nach dem
normalisierten `SchemaDiff` nicht mehr beobachtbar. Phase A/B muss diese Luecke
deshalb verbindlich schliessen, bevor `schema migrate` auf Tabellen mit
`CHECK`-/`EXCLUDE`-Constraints als renderbar gelten darf:

- Bevorzugt erweitert Phase A/B `SchemaDiff`/`TableComparator` so, dass
  `CHECK`-/`EXCLUDE`-Constraints verlustfrei diffbar sind.
- Falls diese Erweiterung nicht im ersten Slice umgesetzt wird, braucht der
  Planner einen separaten Vor-Normalisierungs-Detector, der betroffene Tabellen
  als nicht vollstaendig diffbar markiert und mit blockierender Diagnose
  ausweist.
- Ein blosses Ausschliessen aus der renderbaren Matrix reicht nicht, wenn die
  Aenderung dadurch still verschwindet. Unbeobachtete Constraint-Diffs duerfen
  nicht zu SQL fuer dieselbe Tabelle fuehren.

`AlterColumnType` ist im ersten PostgreSQL-Slice bewusst eng begrenzt. Der
Generator darf die Operation nur rendern, wenn er anhand einer expliziten,
getesteten Cast-Matrix belegen kann, dass PostgreSQL die Aenderung ohne
`USING`-Ausdruck akzeptiert. Aenderungen, die einen `USING`-Ausdruck,
datenabhaengige Transformationen oder eine Nutzerentscheidung ueber
Konvertierungssemantik brauchen, werden als `MANUAL_REQUIRED` oder
`DIALECT_UNSUPPORTED_OPERATION` blockiert. Der Planner darf dafuer kein
generisches `USING` erfinden.

Views sind im ersten Slice nur fuer einfache, nicht materialisierte Views
enthalten, deren Abhaengigkeiten im Plan eindeutig aufloesbar sind. Der Planner
muss betroffene Views vor abhaengigen Drop-/Alter-Operationen entfernen bzw.
nach den referenzierten Tabellen, Spalten und Routinen wieder erzeugen. Views
mit nicht aufloesbaren Routine- oder Materialized-View-Abhaengigkeiten werden
diagnostiziert statt blind gerendert.

Die View-Strategie ist fuer PostgreSQL zweigeteilt:

- `ReplaceView` darf als `CREATE OR REPLACE VIEW` gerendert werden, wenn die
  Aenderung ohne Drop semantisch kompatibel ist, insbesondere keine
  inkompatible Aenderung der sichtbaren Spaltenform benoetigt und keine
  vorgelagerte Tabellen-/Spaltenoperation blockiert.
- Muss eine View wegen `DropColumn`, `AlterColumnType`,
  `AlterColumnNullability`, `DropTable` oder wegen inkompatibler View-
  Signaturaenderung entfernt werden, plant der Dialektgenerator explizite
  `DROP VIEW`- und `CREATE VIEW`-Schritte in dependency-sicherer Reihenfolge.
  Ein blosses `CREATE OR REPLACE VIEW` reicht fuer diesen Fall nicht.

Fuer PostgreSQL ist dafuer vor der View-Migration mindestens ein belastbarer
Tabellen-/Spalten-Dependency-Index noetig, zum Beispiel ueber `pg_depend` /
`pg_rewrite` oder eine gleichwertige Projektion. Der heute vorhandene
Function-Dependency-Anteil reicht fuer `DropColumn`/`AlterColumn`/`DropTable`
nicht aus. Fehlt dieser Index im ersten Slice, muessen betroffene
View-Replacements und schemaaendernde Operationen mit
`DIALECT_UNSUPPORTED_OPERATION` bzw. einer spezifischen Planner-Diagnose
blockieren, statt SQL mit unbekannten Abhaengigkeiten zu erzeugen.

Bewusst nicht in der ersten PostgreSQL-Zielmatrix:

- `ALTER TYPE` fuer nicht trivial kompatible Enum-/Domain-Aenderungen
- `CREATE/ALTER/DROP SEQUENCE`
- `CREATE OR REPLACE FUNCTION`
- `CREATE OR REPLACE PROCEDURE`
- `CREATE OR REPLACE TRIGGER`

Diese Operationen bleiben als `DiffOperation`-Kategorien vorgesehen, werden im
ersten DDL-Slice aber nur geplant bzw. als nicht renderbar diagnostiziert.

Offene Punkte:

- Locking-/Transactional-DDL-Hinweise
- erweiterte Typkonvertierungen mit `USING`
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
- `ALTER TABLE ADD PRIMARY KEY` / `ALTER TABLE DROP PRIMARY KEY`, soweit die
  Primaerschluessel-Aenderung ohne Rebuild- oder Datenmigrationsannahmen
  renderbar ist
- `ALTER TABLE ADD CONSTRAINT` fuer vom Compare-Kern verlustfrei gelieferte
  FK-/Unique-Constraints
- `ALTER TABLE DROP FOREIGN KEY`
- View-Replacement mit bestehenden Helpern

Auch fuer MySQL gilt: View-Replacement ist im ersten Slice auf einfache Views
mit belastbar aufloesbaren Abhaengigkeiten begrenzt. MySQL liefert fuer den
Live-DB-Pfad im ersten Slice nur eine tabellenbezogene View-Abhaengigkeitsquelle
ueber `information_schema.VIEW_TABLE_USAGE` (ab MySQL 8.0.13) und eine
Routine-Quelle ueber `information_schema.VIEW_ROUTINE_USAGE`. Eine
spaltenpraezise `VIEW_COLUMN_USAGE`-Quelle darf nicht vorausgesetzt werden.

Die Konsequenz fuer MySQL ist verbindlich:

- `DropTable` und Tabellen-Replacements duerfen table-level View-Dependencies
  aus `VIEW_TABLE_USAGE` nutzen.
- `DropColumn`, `AlterColumnType` und `AlterColumnNullability` duerfen bei
  Views auf derselben Tabelle nur gerendert werden, wenn spaltenbezogene
  Abhaengigkeiten aus einer expliziten Schema-Datei oder einer gleichwertigen
  Adapter-Projektion bekannt sind.
- Gibt es nur table-level Dependencies, muss der Planner solche
  spaltenveraendernden Operationen mit einer Diagnose blockieren. SQL auf Basis
  einer Query-Text-Heuristik ist nicht zulaessig.
- Auf MySQL-Versionen ohne `VIEW_TABLE_USAGE` sind abhaengige View-Replacements
  im ersten Slice nicht renderbar.
- Der Adapter muss die Vollstaendigkeit der `VIEW_TABLE_USAGE`-/
  `VIEW_ROUTINE_USAGE`-Projektion als Preflight behandeln. MySQL zeigt dort nur
  Objekte, fuer die der introspektierende Benutzer ausreichende Privilegien
  besitzt. Kann der Adapter nicht belegen, dass die noetigen View- und
  Tabellenprivilegien fuer den Migrationsumfang vorhanden sind, gelten
  abhaengige View-Replacements und spaltenveraendernde Operationen unter Views
  als nicht renderbar und muessen mit Diagnose blockieren.

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

SQLite-Rebuilds werden im ersten Slice vollstaendig geplant. Sie duerfen nicht
als einzelne SQL-Zeilen versteckt werden. Der dialektneutrale `DiffResult`
bleibt bei den fachlichen Operationen (`AlterColumnType`, `DropConstraint`,
`AddConstraint`, usw.). Der SQLite-Generator muss daraus einen
dialektspezifischen `DialectMigrationPlan` mit expliziten Rebuild-Schritten
ableiten.

```kotlin
data class DialectMigrationPlan(
    val dialect: DatabaseDialect,
    val sourceOperationIds: List<String>,
    val steps: List<DialectMigrationStep>,
    val diagnostics: List<DiffDiagnostic> = emptyList(),
)

sealed interface DialectMigrationStep {
    val sourceOperationIds: Set<String>
    val risk: OperationRisk
}

data class RebuildTable(
    override val sourceOperationIds: Set<String>,
    val tableName: String,
    val oldTable: TableDefinition,
    val newTable: TableDefinition,
    val newTableTempName: String,
    val preservedColumns: List<ColumnCopyMapping>,
    val addedColumns: List<AddedColumnFill>,
    val droppedColumns: List<String>,
    val dependentViewsToRecreate: List<NamedView>,
    val dependentTriggersToRecreate: List<NamedTrigger>,
    val indexesToRecreate: List<IndexDefinition>,
    val preflight: List<SqliteRebuildPreflight>,
    override val risk: OperationRisk,
) : DialectMigrationStep

data class ColumnCopyMapping(
    val sourceColumn: String,
    val targetColumn: String,
    val expressionSql: String,
)

data class AddedColumnFill(
    val columnName: String,
    val expressionSql: String,
)

enum class SqliteRebuildPreflight {
    TABLE_EXISTS,
    TEMP_NAME_AVAILABLE,
    SOURCE_COLUMNS_EXIST,
    DEPENDENCIES_KNOWN,
    ADDED_COLUMNS_FILLABLE,
    FOREIGN_KEYS_CHECKABLE,
}
```

Verbindliche Rebuild-Regeln:

- Ein `RebuildTable` gruppiert alle fachlichen Operationen, die dieselbe
  Tabelle betreffen und fuer SQLite nicht direkt per `ALTER TABLE` renderbar
  sind.
- Der Core-`DiffResult` bleibt dialektneutral. `RebuildTable` ist kein
  `DiffOperation`, sondern ein SQLite-spezifischer Folgeplan mit Rueckverweis
  auf die ausloesenden Operation-IDs.
- Direkt renderbare SQLite-Operationen duerfen neben Rebuild-Schritten stehen,
  muessen aber dependency-sicher vor oder nach dem Rebuild sortiert werden.

Spaltenmapping:

- Unveraenderte Spalten werden 1:1 kopiert:
  `sourceColumn = targetColumn`, `expressionSql = quote(sourceColumn)`.
- `AlterColumnType` darf nicht allein aufgrund gleicher oder bestimmbarer
  SQLite-Zielaffinitaet automatisch mit `CAST` gerendert werden. SQLite kann
  Werte bei `CAST` still normalisieren oder verlustbehaftet veraendern. Ein
  automatischer Cast ist im ersten Slice nur erlaubt, wenn eine explizite,
  getestete Quell-/Ziel-Cast-Matrix die Konvertierung als sicher klassifiziert
  und der Generator die noetigen Daten-Preflights ausfuehren kann. Fehlt eine
  solche Matrix oder ein noetiger Preflight, wird die Operation
  `MANUAL_REQUIRED` und blockiert automatisches Up/Down-SQL.
- `AlterColumnDefault` und Constraint-/PK-Aenderungen aendern nur die
  Zieltabellen-Definition; bestehende Werte werden 1:1 kopiert.
- Hinzugefuegte nullable Spalten werden mit `NULL` gefuellt, sofern kein
  Default existiert.
- Hinzugefuegte Spalten mit Default werden mit dem gerenderten Default-Ausdruck
  gefuellt.
- Hinzugefuegte `NOT NULL`-Spalten ohne Default sind im Rebuild
  `MANUAL_REQUIRED`; der Planner darf kein SQL erfinden.
- Entfernte Spalten werden in `droppedColumns` ausgewiesen. Der Up-Plan ist
  destruktiv; ein automatisches Down-SQL ist dafuer `NOT_REVERSIBLE`.
- Rename-Operationen bleiben ausserhalb des ersten Slice. Entfernen plus
  Hinzufuegen darf nicht automatisch als Spaltenmapping interpretiert werden.

Temporaere Namen:

- `newTableTempName` wird deterministisch aus Tabellenname und dem stabilen
  Fingerprint der gruppierten Operation-IDs gebildet, zum Beispiel
  `__dmigrate_rebuild_orders_4f8c2a1b`.
- Der Name muss mit bestehenden Tabellen, Views, Indizes und Triggern der
  Ziel-Datenbank kollisionsfrei sein. Bei Kollision wird deterministisch ein
  Suffix `__2`, `__3`, ... vergeben.
- Temporaere Namen werden im Report und im SQL-Metadatenblock ausgewiesen.
- Es gibt keinen dauerhaft sichtbaren Old-Table-Namen. Der bevorzugte Ablauf
  erstellt die neue Tabelle unter dem temporaeren Namen, kopiert Daten, droppt
  die alte Tabelle und benennt die neue Tabelle auf den Originalnamen um.

Rebuild-SQL-Ablauf:

1. Preflight pruefen:
   - erwartete Tabelle existiert
   - temporaerer Name ist frei
   - alle Quellspalten fuer `preservedColumns` existieren
   - keine unbekannten abhaengigen Views/Trigger blockieren den Drop
   - `NOT NULL`-/Default-Regeln fuer hinzugefuegte Spalten sind erfuellt
2. Vor der Transaktion `PRAGMA foreign_keys=OFF` setzen, wenn der Rebuild
   FK-bezogene Drops/Renames braucht. Der Runner muss den vorherigen Zustand
   merken und nach Commit/Rollback wiederherstellen.
3. `BEGIN IMMEDIATE`.
4. Abhaengige Views und Trigger droppen, deren Definitionen in
   `dependentViewsToRecreate` bzw. `dependentTriggersToRecreate` gespeichert
   sind.
5. Neue Tabelle unter `newTableTempName` aus `newTable` erzeugen.
6. Daten mit expliziter Spaltenliste kopieren:
   `INSERT INTO temp(target...) SELECT expression... FROM old`.
7. Alte Tabelle droppen.
8. Temporaere Tabelle auf den Originalnamen umbenennen.
9. Indizes aus `indexesToRecreate` neu erzeugen.
10. Trigger und Views aus den gespeicherten Definitionen neu erzeugen.
11. `PRAGMA foreign_key_check` ausfuehren, wenn Foreign Keys beteiligt sind.
12. `COMMIT`.
13. Den vorherigen `foreign_keys`-Zustand wiederherstellen.

Fehlerverhalten:

- Der Runner fuehrt einen Rebuild als unteilbare Einheit aus. Tritt zwischen
  `BEGIN IMMEDIATE` und `COMMIT` ein Fehler auf, muss `ROLLBACK` ausgefuehrt
  werden.
- Schlaegt `ROLLBACK` selbst fehl oder ist der Verbindungszustand unklar, endet
  der Lauf mit einem lokalen Fehler und einem Report, der die letzte bekannte
  Rebuild-Phase nennt.
- Der Runner darf nach einem fehlgeschlagenen Rebuild nicht mit weiteren
  Migration-Schritten fortfahren.
- SQL-Artefakte muessen die Rebuild-Grenzen kommentieren und den
  Metadatenblock so schreiben, dass ein Mensch die betroffene Tabelle,
  Operation-IDs, Temp-Namen und Risiko-Klassifizierung erkennt.

Down-Rebuild:

- Fuer reversible Rebuilds wird ein eigener inverser `RebuildTable`-Plan
  erzeugt. `oldTable` und `newTable` des Up-Rebuilds sind dafuer nur die
  Quellinformation fuer die Down-Tabellendefinitionen; die richtungsabhaengigen
  Felder duerfen nicht durch blosses Vertauschen wiederverwendet werden.
- Das inverse `RebuildTable` muss `preservedColumns`, `addedColumns`,
  `droppedColumns`, `dependentViewsToRecreate`, `dependentTriggersToRecreate`,
  `indexesToRecreate`, Preflights und Risiko aus der Down-Richtung neu
  bestimmen. Alternativ darf der Generator den Down-Rebuild aus den inversen
  fachlichen Operationen komplett neu planen.
- Das Down-Mapping ist nur automatisch erlaubt, wenn alle Up-Schritte
  reversibel sind und keine verworfenen Daten rekonstruiert werden muessen.
- Enthaelt der Up-Rebuild `droppedColumns` oder manuelle Casts, blockiert
  `--generate-rollback` mit `ROLLBACK_NOT_POSSIBLE` oder
  `MANUAL_ACTION_REQUIRED`.

---

## 7. CLI-Vertrag fuer 0.9.7

### 7.1 `schema migrate`

Vorgeschlagener Zielvertrag:

```bash
d-migrate schema migrate \
  --source desired.yaml \
  --target db:staging \
  --output migration.sql \
  --generate-rollback \
  --rollback-output rollback.sql \
  --report migration-report.yaml
```

Flag-Skizze:

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Operand | Soll-Schema, zunaechst Datei |
| `--target` | Ja | Operand | Ist-Datenbank oder Ist-Schema-Datei |
| `--dialect` | Bedingt | Dialekt | Zieldialekt fuer SQL-Rendering; Pflicht bei Datei-zu-Datei, bei DB-Target aus der Connection ableitbar |
| `--output` | Nein | Pfad | Up-SQL-Ausgabe |
| `--rollback-output` | Bedingt | Pfad | Down-SQL-Ausgabe; Pflicht, wenn `--generate-rollback` gesetzt ist und nicht `--plan-only` genutzt wird |
| `--generate-rollback` | Nein | Boolean | Down-Plan erzeugen |
| `--allow-destructive` | Nein | Boolean | destruktive Operationen erlauben |
| `--plan-only` | Nein | Boolean | nur stabilen Plan-/Risiko-Report schreiben, kein SQL |
| `--report` | Bedingt | Pfad | strukturierter Plan-/Risiko-Report; Pflicht bei `--execute` |
| `--execute` | Nein | Boolean | Up-DDL nach erfolgreichem Rendern gegen ein DB-Target ausfuehren |
| `--dry-run` | Nein | Boolean | Plan/SQL erzeugen, aber nichts ausfuehren |

Die CLI-Namen folgen dem bestehenden Stub in `spec/cli-spec.md`:
`--source` bezeichnet das Soll-Schema, `--target` den Ist-Zustand. Intern soll
der Runner diese Werte sofort auf die eindeutigen Begriffe `desired` und
`current` abbilden. `SchemaComparator.compare(current, desired)` ist die
verbindliche Richtung fuer den Operationsplan.

Unterstuetzte Zielmodi im ersten Slice:

| Modus | `--source` | `--target` | `--dialect` | `--execute` |
|---|---|---|---|---|
| Datei-zu-DB | Soll-Schema-Datei | `db:<url-or-alias>` | optional, muss zur DB passen wenn gesetzt | erlaubt |
| Datei-zu-Datei | Soll-Schema-Datei | `file:<current.yaml>` oder Pfad | Pflicht | nicht erlaubt |

Datei-zu-Datei erzeugt einen vollstaendigen Plan, Up-SQL, optional Down-SQL und
optional einen Report ohne Live-Datenbank. Der Modus darf keine
Zielzustands-Introspektion und keine Ausfuehrung versuchen. `--execute` mit
Datei-Target ist ein CLI-Fehler mit Exit `2`. Der Report, falls geschrieben,
und der SQL-Metadatenblock enthalten in diesem Modus den Fingerprint der
aktuellen Schema-Datei als erwarteten Vorzustand und den Fingerprint des
Soll-Schemas als erwarteten Post-Up-Zustand.

Wenn `--generate-rollback` gesetzt ist und der Lauf SQL-Artefakte erzeugen
soll, ist `--rollback-output` im ersten Slice Pflicht. Es gibt keinen
impliziten Default-Pfad und kein Einbetten des Down-SQL in das
Up-SQL-Artefakt. Fehlt `--rollback-output`, endet der Lauf als ungueltiger
CLI-Aufruf mit Exit `2`.

Bei `--plan-only --generate-rollback` wird dagegen nur die Down-Renderbarkeit
und Rollback-Risiko-Lage in den Plan-/Risiko-Report aufgenommen. Es wird kein
Down-SQL geschrieben, `--rollback-output` ist in dieser Kombination
unzulaessig und endet mit Exit `2`.

Ausgabeziele sind im ersten Slice bewusst explizit:

- Ohne `--output` wird Up-SQL bei einem renderbaren Dry-Run nach `stdout`
  geschrieben. Plain-Status, Warnungen und Blocker gehen nach `stderr`.
- Mit `--output` wird Up-SQL in diese Datei geschrieben und nicht zusaetzlich
  nach `stdout` dupliziert.
- `--report` ist optional, ausser bei `--execute`. Ist es gesetzt, wird der
  strukturierte Report an diesen Pfad geschrieben. Ist es nicht gesetzt, wird
  kein impliziter Report-Pfad abgeleitet.
- Bei `--plan-only` gibt es kein SQL. Ohne `--report` wird der strukturierte
  Plan-/Risiko-Report nach `stdout` geschrieben; mit `--report` wird er in
  diese Datei geschrieben.
- Wenn `--generate-rollback` gesetzt ist und der Lauf nicht `--plan-only` ist,
  ist `--rollback-output` immer der einzige Zielpfad fuer Down-SQL. Down-SQL
  wird nie nach `stdout` geschrieben.
- `--execute` ohne `--output` ist zulaessig: die Migration wird ausgefuehrt,
  aber kein Up-SQL-Artefakt persistiert.
- `--execute` verlangt im ersten Slice zwingend `--report`. Ein DB-seitiger
  Schemawechsel darf nicht ohne dauerhaftes Audit-Artefakt aus Plan,
  Fingerprints, Operation-IDs, Risiko- und Ausfuehrungsstatus erfolgen. Fehlt
  `--report`, endet der Lauf als ungueltiger CLI-Aufruf mit Exit `2`.
- `--execute` und `--dry-run` sind gegenseitig exklusiv. Die Kombination endet
  als ungueltiger CLI-Aufruf mit Exit `2`.

Alle Datei-Ausgaben werden erst nach erfolgreichem Planning, vollstaendigem
Rendering und erfolgreicher Blocker-Pruefung finalisiert:

- Up-SQL, Down-SQL und Report-Dateien werden in eine temporaere Datei im
  Zielverzeichnis geschrieben und erst danach atomar auf den Zielpfad
  verschoben.
- Existierende Zielpfade duerfen bei Render-, Planungs-, Blocker- oder
  Ausfuehrungsfehlern nicht mit Teilinhalten ueberschrieben werden.
- Bei `--execute --generate-rollback` gilt zusaetzlich: das finale Down-SQL
  darf erst nach erfolgreichem Up, Nach-Introspection und Nach-Compare
  finalisiert werden.
- Schlaegt `--execute --generate-rollback` nach erfolgreicher Up-Ausfuehrung
  beim Nach-Introspection-, Nach-Compare- oder Rollback-Finalisierungsschritt
  fehl, ist der Zielzustand bereits veraendert. In diesem Fall darf ein
  bestehendes `--rollback-output` weiterhin nicht ueberschrieben werden. Der
  Runner darf stattdessen nur dann ein separates Recovery-Artefakt mit
  eindeutigem Suffix wie `.recovery.<timestamp>.rollback.sql` in dasselbe
  Zielverzeichnis schreiben, wenn kein beobachteter Post-Up-Fingerprint dem
  Soll-Fingerprint widerspricht. Dieses Artefakt muss im Metadatenblock als
  `recovery = true` markiert sein.
  `postUpVerified` ist nur dann `true`, wenn ein beobachteter
  Post-Up-Fingerprint aus erfolgreicher Nach-Introspection vorliegt und der
  Nach-Compare gegen `desired` erfolgreich war. Das Artefakt darf von
  `schema rollback --execute` nur nach erneuter Zielzustandspruefung gegen
  `allowedPostUpFingerprints` akzeptiert werden. Ist ein beobachteter
  Post-Up-Fingerprint vorhanden, der nicht zum Soll-Fingerprint passt, darf der
  Runner kein automatisch ausfuehrbares Recovery-Rollback-Artefakt
  finalisieren; der strukturierte Fehler muss dann den beobachteten Fingerprint
  und `rollbackFinalized = false` ausweisen.
  Kann ein nach diesen Regeln zulaessiges Recovery-Artefakt nicht geschrieben
  werden, endet der Lauf mit Exit `7` und einem strukturierten lokalen Fehler,
  der klar ausweist, dass Up bereits ausgefuehrt wurde und kein finalisiertes
  Rollback-Artefakt vorliegt.
- Falls das Dateisystem keine atomare Ersetzung im Zielverzeichnis erlaubt,
  endet der Lauf mit Exit `7`, bevor ein bestehendes Artefakt veraendert wird.
- Reports sind ebenfalls atomar zu schreiben. Bei einem terminalen
  Ausfuehrungsfehler darf der Fehlerreport finalisiert werden, solange er
  eindeutig `status = failed` und den Ausfuehrungszustand enthaelt. Up-SQL- und
  Down-SQL-Artefakte bleiben in diesem Fall unveraendert, sofern sie nicht schon
  vor der Ausfuehrung erfolgreich finalisiert wurden.

Fuer 0.9.7 muss `schema migrate` nicht nur SQL schreiben, sondern einen
ausfuehrbaren Up-Pfad fuer DB-Targets und einen vollstaendigen Plan-/Renderpfad
fuer Datei-Targets tragen:

1. Ist-Zustand aus `--target` aufloesen:
   - DB-Target: introspektieren.
   - Datei-Target: Schema-Datei laden und validieren.
2. Soll-Zustand aus `--source` laden und validieren.
3. Reverse-generierte Marker und synthetische Metadaten beider Operanden
   normalisieren, bevor verglichen wird. Der Codepfad soll die bestehende
   `schema compare`-Semantik teilen statt eine zweite Normalisierung zu
   erfinden.
4. `DiffResult` in Richtung `current -> desired` planen.
5. Zieldialekt bestimmen:
   - DB-Target: aus der Connection, optional gegen `--dialect` validiert.
   - Datei-Target: aus dem Pflichtflag `--dialect`.
6. Up-DDL rendern, sofern kein Up-Risiko- oder Dialektblocker greift. Rollback-
   Blocker wirken nur, wenn `--generate-rollback` gesetzt ist.
7. Bei `--generate-rollback` die Down-Renderbarkeit aus demselben Plan pruefen.
   Bei `--plan-only` werden nur Rollback-Faehigkeit, Risiken und Blocker
   berichtet. In SQL-rendernden Laeufen wird das Down-SQL vorbereitet; ohne
   `--execute` wird es sofort mit dem erwarteten Soll-Fingerprint als
   Post-Up-Fingerprint nach `--rollback-output` geschrieben.
8. Bei `--execute` Up-DDL gegen das DB-Target ausfuehren.
9. Nach Ausfuehrung den Zielzustand erneut introspektieren und gegen
   `desired` vergleichen.
10. Bei `--execute --generate-rollback` das Down-SQL erst nach erfolgreicher
    Nach-Introspection atomar nach `--rollback-output` schreiben. Der
    Metadatenblock enthaelt dann den tatsaechlich beobachteten
    Post-Up-Fingerprint.

Ausfuehrungsfehler sind ein eigener Vertrag, getrennt von Planning und
Rendering:

- Der Dialektgenerator bzw. Runner muss vor Ausfuehrung deklarieren, ob der
  erzeugte Up-Plan als eine transaktionale Einheit ausgefuehrt werden kann.
  PostgreSQL soll eine Transaktion verwenden, sofern keine nicht
  transaktionsfaehige Anweisung im Plan vorkommt. MySQL-DDL darf nicht als
  transaktional angenommen werden. SQLite-Rebuilds gelten jeweils als
  unteilbare Rebuild-Einheit gemaess §6.4, nicht automatisch als globale
  Migrationstransaktion.
- Schlaegt eine DDL-Anweisung nach Beginn von `--execute` fehl, endet der Lauf
  mit Exit `5` (`MIGRATION_ERROR`). Der Report enthaelt mindestens
  `executionStarted = true`, `executionCompleted = false`,
  `statementsAttempted`, die Operation-IDs der letzten gestarteten Anweisung,
  `transactionRolledBack`, `sideEffectsPossible` und die Fehlerdiagnose.
- Wenn der Runner beweisen kann, dass die gesamte Ausfuehrung zurueckgerollt
  wurde und keine DB-seitigen Seiteneffekte verbleiben, wird
  `sideEffectsPossible = false` berichtet. Andernfalls ist
  `sideEffectsPossible = true`; der Nutzer muss den Zielzustand pruefen.
- Ein normaler oder Recovery-`--rollback-output` darf nach partieller oder
  unklarer Up-Ausfuehrung nicht finalisiert werden. Der vorbereitete Down-Plan
  beschreibt den Soll-Post-Up-Zustand und ist fuer einen unbekannten
  Zwischenzustand nicht automatisch gueltig. Automatische Rekonstruktion eines
  partiellen Zwischenzustands ist nicht Bestandteil des ersten Slice.
- Der in diesem Dokument beschriebene Recovery-Rollback-Fall gilt nur, wenn Up
  vollstaendig ausgefuehrt wurde und danach Nach-Introspection, Nach-Compare oder
  finale Rollback-Artefakt-Finalisierung fehlschlaegt.

`--dry-run` ist der Default, solange `--execute` nicht gesetzt ist. Damit kann
der gleiche Befehl zuerst den Plan und beide SQL-Artefakte erzeugen und danach
bewusst ausgefuehrt werden. Diese Aussage gilt fuer renderbare Plaene. Ist der
Plan destruktiv oder bestaetigungspflichtig und fehlt `--allow-destructive`,
endet der normale Dry-Run mit Exit `8` nach Plan-/Report-Erzeugung, aber ohne
ausfuehrbares Up-SQL. Wer nur den Risiko-Report ohne Freigabe sehen will, nutzt
`--plan-only`.

Ein destruktiver Down-Plan, der aus einem reversiblen Up entsteht, ist an
dieser Stelle kein Render-Blocker. Er wird im Down-SQL-Metadatenblock und im
Report als destruktiv markiert. `--allow-destructive` wird fuer diese
Down-Schritte erst von `schema rollback --execute` ausgewertet.

Exit-Codes sollten sich an bestehenden Mustern orientieren:

| Exit | Bedeutung |
|---|---|
| `0` | Erfolg |
| `2` | ungueltige CLI-Argumente |
| `3` | Schema-Validierungsfehler |
| `4` | Verbindungsfehler |
| `5` | DDL-Ausfuehrungsfehler nach Beginn von `--execute` |
| `7` | lokale I/O-, Planungs-, Render- oder Artefaktfehler |
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
ausfuehrbares Up-SQL-Artefakt rendern oder Up-DDL ausfuehren soll, fuehren
fehlende Up-Freigaben oder nicht renderbare Dialektoperationen zu Exit `8`.
Nicht moeglicher Rollback fuehrt nur dann zu Exit `8`, wenn
`--generate-rollback` angefordert wurde. Fehlende Freigaben fuer destruktive
Down-Schritte blockieren dagegen erst `schema rollback --execute`, nicht die
Erzeugung eines korrekt markierten Down-SQL-Artefakts.

Exit `8` muss im strukturierten Fehler eine vollstaendige `blockers`-Liste und
einen optionalen `primaryBlockedReason` enthalten. Die Blocker muessen
mindestens folgende Faelle unterscheiden:

- destruktive Up-Operation ohne `--allow-destructive`
- `--generate-rollback` angefordert, aber mindestens eine Operation ist
  `NOT_REVERSIBLE`
- `--generate-rollback` angefordert, aber mindestens eine Operation ist
  `MANUAL_REQUIRED`
- `schema rollback --execute` angefordert, aber die Ziel-Datenbank entspricht
  nicht dem erwarteten Post-Up-/Soll-Fingerprint
- `schema rollback --execute` angefordert, aber der Ziel-Dialekt entspricht
  nicht dem im Rollback-Artefakt gespeicherten Dialekt
- Ziel-Dialekt kann eine geplante Operation nicht rendern

Die konkrete Exit-Code-Matrix muss vor Implementierung mit `spec/cli-spec.md`
abgeglichen werden.

Exit `5` gilt fuer Fehler waehrend der tatsaechlichen DDL-Ausfuehrung nach
Beginn von `--execute`, inklusive partieller oder unklarer Seiteneffekte. Der
strukturierte Fehler muss den Ausfuehrungszustand gemaess §7.1 ausweisen. Fehler
vor Beginn der Ausfuehrung bleiben je nach Ursache Exit `2`, `3`, `4`, `7` oder
`8`.

### 7.2 `schema rollback`

`schema rollback` ist fuer 0.9.7 der Down-Ausfuehrungspfad fuer das von
`schema migrate --generate-rollback` erzeugte Down-SQL. Er sollte keine Magie
aus einer Live-Datenbank erraten.

Fuer 0.9.7 verbindlich:

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

Auch fuer `schema rollback` sind `--execute` und `--dry-run` gegenseitig
exklusiv. Die Kombination endet als ungueltiger CLI-Aufruf mit Exit `2`.

Der Runner:

1. liest ausschliesslich das gespeicherte Down-SQL aus `--source`,
2. liest daraus den von `schema migrate --generate-rollback` erzeugten
   maschinenlesbaren `d-migrate`-Metadatenblock,
3. prueft in allen Modi den Metadatenblock, die Pflichtfelder und den
   `artifactHash` des kanonischen Header-/Body-Artefakts strikt,
4. bestimmt bei `--execute` den Dialekt des Ziel-Connectors und vergleicht ihn
   mit dem im Metadatenblock gespeicherten Dialekt,
5. bricht bei Dialektabweichung mit Exit `8`,
   `primaryBlockedReason = TARGET_DIALECT_MISMATCH` und einem entsprechenden
   Blocker ab,
6. introspektiert bei `--execute` den aktuellen Zielzustand und vergleicht ihn
   bei normalen Artefakten mit `postUpFingerprint`, bei Recovery-Artefakten mit
   `allowedPostUpFingerprints`,
7. bricht bei fehlender Uebereinstimmung mit Exit `8`,
   `primaryBlockedReason = TARGET_STATE_MISMATCH` und einem entsprechenden
   Blocker ab,
8. verlangt `--allow-destructive`, wenn der Metadatenblock destruktive
   Down-Operationen ausweist,
9. fuehrt es bei `--execute` gegen `--target` aus,
10. protokolliert ausgefuehrte Statements und Fehler,
11. fuehrt ohne `--execute` nur lokale Validierung/Preview aus.

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

Der Metadatenblock braucht trotzdem einen stabilen technischen Vertrag, weil
`schema rollback` ihm fuer Artefaktvalidierung und bei `--execute` zusaetzlich
fuer Drift-Schutz und Freigabepruefung vertraut:

- Er steht am Anfang des Down-SQL-Artefakts vor dem ersten ausfuehrbaren
  Statement.
- Er ist als SQL-Kommentarblock mit eindeutigen Begrenzern codiert:
  `-- d-migrate rollback-sql v1 begin` und
  `-- d-migrate rollback-sql v1 end`.
- Der Block enthaelt genau eine Nutzzeile. Diese Nutzzeile ist ein Kommentar
  mit einem kanonischen JSON-Objekt. Der Parser entfernt nur das fuehrende
  SQL-Kommentarpraefix und parst danach JSON. Freitext-Parsing von
  `key=value`-Zeilen ist nicht zulaessig.
- Das JSON-Objekt enthaelt mindestens:
  `format`, `formatVersion`, `dialect`, `currentFingerprint`,
  `desiredFingerprint`, `postUpFingerprint`, `operationIds`,
  `risk`, `createdByVersion`, `fingerprintAlgorithm`, `artifactHashAlgorithm`,
  `artifactHash`, `recovery` und `postUpVerified`.
- `artifactHash` bindet den Metadatenblock und den SQL-Body aneinander. Der Hash
  wird ueber kanonische Bytes aus zwei Teilen gebildet:
  1. das kanonische JSON-Objekt des Metadatenblocks ohne das Feld
     `artifactHash`, aber inklusive sicherheitsrelevanter Header-Felder wie
     `dialect`, Fingerprints, `operationIds`, `risk`, `recovery` und
     `postUpVerified`;
  2. alle Zeilen nach dem End-Begrenzer als kanonischer SQL-Body.
  Zeilenenden werden als LF normalisiert, der Generator schreibt genau eine
  finale Newline, und der Hash deckt Kommentare sowie ausfuehrbare Statements im
  Body ab. Eine manuelle Aenderung des Risikoblocks, der Fingerprints, des
  Dialekts oder des SQL-Bodys muss dadurch den Hash brechen.
- `artifactHashAlgorithm` benennt den Algorithmus und die kanonische Byte-Regel,
  zum Beispiel `sha256-rollback-artifact-v1`. `schema rollback` muss den Hash in
  Preview-, Validierungs- und Execute-Pfaden neu berechnen, bevor es das
  Artefakt als gueltig behandelt. Bei `--execute` passiert diese Pruefung vor
  jeder Zielzustands- oder Dialektpruefung. Eine Abweichung macht das Artefakt
  ungueltig und endet ohne DB-Zugriff mit Exit `7`.
- Dieser Hash ist ein Integritaets- und Drift-Schutz fuer versehentlich oder
  manuell veraenderte Artefakte, keine kryptografische Signatur gegen einen
  Angreifer, der Header und Body gemeinsam neu schreiben kann. Signierte
  Artefakte oder HMACs sind nicht Bestandteil des ersten Slice.
- `recovery` ist bei normalen Rollback-Artefakten `false` und bei Recovery-
  Artefakten `true`. `postUpVerified` ist nur dann `true`, wenn
  `postUpFingerprint` aus einer erfolgreichen Nach-Introspection nach
  ausgefuehrtem Up stammt. Bei Dry-Run-/Datei-zu-Datei-Artefakten ist
  `postUpVerified = false`, weil `postUpFingerprint` dort der erwartete
  Soll-Fingerprint ist.
- Fuer `recovery = true` enthaelt das JSON zusaetzlich
  `allowedPostUpFingerprints` als nicht leere Liste. Konnte nach vollstaendigem
  Up kein Post-Up-Fingerprint beobachtet werden, darf die Liste den erwarteten
  Soll-Fingerprint enthalten; `postUpVerified` bleibt dann `false`. Wurde ein
  Post-Up-Fingerprint beobachtet und passt er zum Soll-Fingerprint, enthaelt
  die Liste den beobachteten Fingerprint. Wurde ein Post-Up-Fingerprint
  beobachtet, der nicht zum Soll-Fingerprint passt, darf kein automatisch
  ausfuehrbares Recovery-Rollback-Artefakt finalisiert werden, weil der
  vorbereitete Down-Plan fuer den erwarteten Soll-Zustand nicht nachweislich
  zum beobachteten Zielzustand passt. `schema rollback --execute` akzeptiert
  Recovery-Artefakte nur, wenn der aktuelle Zielzustand zu einem der erlaubten
  Fingerprints passt.
- `risk` enthaelt mindestens `destructive`, `dataLossPossible`,
  `requiresManualConfirmation` und die betroffenen Operation-IDs.
- Der Parser ist strikt: fehlende Pflichtfelder, unbekannte `formatVersion`,
  syntaktisch ungueltiges JSON, unbekannte `artifactHashAlgorithm`, abweichender
  `artifactHash`, widerspruechliche Dialekt-/Fingerprint-Felder oder mehrere
  Metadatenbloecke machen das Artefakt fuer `--execute` ungueltig. Bei
  `recovery = true` sind fehlende oder leere
  `allowedPostUpFingerprints` ebenfalls ungueltig. Preview/Validierung muss
  dieselben lokalen Artefaktpruefungen ausfuehren und darf den Fehler berichten,
  aber nicht ausfuehren. DB-Zustands- und Dialektpruefung bleiben dagegen
  `--execute`-gebunden.
- Fingerprints werden aus derselben kanonischen Schema-Projektion gebildet, die
  auch der Nach-Compare verwendet: reverse-generierte Marker und synthetische
  Metadaten werden vorher normalisiert, Map-/Listenreihenfolgen werden
  deterministisch sortiert, und die verwendete Algorithmus-ID wird im Block
  gespeichert.
- Der Metadatenblock darf keine Secrets, unmaskierten Connection-URLs oder
  absolute lokale Pfade enthalten. Solche Angaben gehoeren in den Report und
  muessen dort den bestehenden Scrubbing-Regeln folgen.

Fehlt dieser Metadatenblock, darf `schema rollback` das SQL im ersten Slice nur
als Preview/Validierung behandeln. Eine Ausfuehrung ohne Metadatenblock ist
nicht Bestandteil dieses Plans.

Bei `schema migrate --execute --generate-rollback` darf das finale
Rollback-Artefakt erst nach erfolgreicher Up-Ausfuehrung und Nach-Compare
geschrieben werden, weil der Metadatenblock den tatsaechlichen
Post-Up-Fingerprint referenziert. Ein vorbereiteter Down-Plan darf vor der
Ausfuehrung nur in Memory oder in einer temporaeren Datei existieren und muss
vor dem finalen Schreiben erneut mit dem beobachteten Zielzustand verbunden
werden.

Fehler nach bereits ausgefuehrtem Up sind ein eigener Recovery-Fall:

- Schlaegt die Nach-Introspection, der Nach-Compare oder das atomare Schreiben
  des finalen `--rollback-output` fehl, darf der Runner nicht so tun, als sei
  der gesamte Befehl ohne Seiteneffekt fehlgeschlagen.
- Der Report und der strukturierte Fehler muessen `upExecuted = true`,
  `rollbackFinalized = false` und die letzte erfolgreich abgeschlossene Phase
  ausweisen.
- Soweit der vorbereitete Down-Plan renderbar war und kein beobachteter
  Post-Up-Fingerprint dem erwarteten Soll-Fingerprint widerspricht, wird ein
  separates Recovery-Rollback-Artefakt geschrieben. Wenn kein beobachteter
  Fingerprint verfuegbar ist, enthaelt `allowedPostUpFingerprints` den
  erwarteten Soll-Fingerprint. Wenn ein beobachteter Fingerprint verfuegbar ist
  und zum Soll passt, enthaelt `allowedPostUpFingerprints` den beobachteten
  Fingerprint. Das Artefakt ist nicht das normale `--rollback-output`, sondern
  ein eindeutig markiertes Recovery-Artefakt.
- Wurde ein abweichender Post-Up-Fingerprint beobachtet, wird kein automatisch
  ausfuehrbares Recovery-Rollback-Artefakt geschrieben. Der Report und der
  strukturierte Fehler muessen dann `upExecuted = true`,
  `rollbackFinalized = false`, den beobachteten Fingerprint und eine klare
  manuelle Pruefpflicht ausweisen.
- `schema rollback --execute` darf ein solches Recovery-Artefakt nur ausfuehren,
  wenn der Metadatenblock strikt parsebar ist, `recovery = true` enthaelt und
  die aktuelle Ziel-Datenbank mit einem der im Artefakt erlaubten
  Post-Up-Fingerprints uebereinstimmt. Bei fehlender Uebereinstimmung gilt
  weiterhin `TARGET_STATE_MISMATCH`.
- Kann kein Recovery-Artefakt geschrieben werden, ist das ein lokaler Fehler
  nach Side Effect. Der Exit-Code bleibt `7`; die Meldung muss den Nutzer darauf
  hinweisen, dass manuelle Sicherung/Pruefung der Ziel-Datenbank erforderlich
  ist.

Nicht Bestandteil dieses Plans:

1. Rollback aus gespeichertem `DiffResult`/Plan-Artefakt:

   ```bash
   d-migrate schema rollback --source migration-plan.yaml --target db:staging
   ```

Diese Variante setzt voraus, dass `DiffResult` serialisierbar und versioniert ist.
Das sollte erst nach Stabilisierung des internen Vertrags als Nutzervertrag
freigegeben werden.

### 7.3 0.9.7 Up/Down-Artefaktvertrag

Ein erfolgreicher 0.9.7-Up/Down-Lauf besteht, soweit die jeweiligen
Ausgabeziele angefordert wurden, aus zusammenpassenden Artefakten:

- Up-SQL aus `--output` oder aus `stdout`, wenn kein `--output` gesetzt ist
  und der Lauf nicht `--execute`-only ist
- Down-SQL aus `--rollback-output`, wenn `--generate-rollback` gesetzt ist und
  der Lauf nicht `--plan-only` ist
- strukturierter Report aus `--report`

Bei `--execute` ist `--report` Pflicht. Der Report ist dann nicht nur ein
optionales Begleitartefakt, sondern das Audit-Artefakt des DB-seitigen
Schemawechsels.

`--rollback-output` ist bei `--generate-rollback` verbindlich, sobald der Lauf
SQL-Artefakte erzeugt. Bei `--plan-only --generate-rollback` wird nur
Rollback-Faehigkeit berichtet; `--rollback-output` ist dort unzulaessig. Der
Runner darf keinen impliziten Dateinamen ableiten und darf Down-SQL nicht in
das Up-SQL-Artefakt mischen.

Bei einem durch Risiken blockierten Dry-Run ohne `--allow-destructive` darf der
Report geschrieben werden, das Up-SQL-Artefakt aber nicht. Ein vorhandener
Ausgabepfad darf nicht mit teilweise gerendertem SQL ueberschrieben werden.
Dasselbe gilt fuer Rollback-Artefakte: Bei Blockern oder fehlgeschlagener
Up-Ausfuehrung darf ein vorhandenes `--rollback-output` nicht mit einem
unvollstaendigen oder fingerprint-falschen Down-SQL ueberschrieben werden.
Der in §7.1 beschriebene temporaere Schreibpfad mit atomarer Finalisierung gilt
fuer Up-SQL, Down-SQL und Report-Dateien gleichermassen.

Der Report muss mindestens enthalten:

- Fingerprint des Ist-Zustands vor Up
- Fingerprint des Soll-Zustands
- Fingerprint des Zielzustands nach Up, wenn `--execute` genutzt wurde
- Pfade zu Up- und Down-SQL, soweit diese Artefakte als Dateien geschrieben
  wurden
- Operand-Modus (`file-to-db` oder `file-to-file`) und Zieldialekt
- Liste der Operationen, aus denen Up und Down gerendert wurden
- bei `--execute`: Ausfuehrungsstatus mit `executionStarted`,
  `executionCompleted`, `statementsAttempted`, `transactionRolledBack`,
  `sideEffectsPossible` und Fehlerdiagnose bei Abbruch
- Blocker fuer Down, falls `--generate-rollback` nicht moeglich ist

Down-SQL darf nur erzeugt werden, wenn alle fuer Down benoetigten Operationen
automatisch renderbar sind. Fuer `NOT_REVERSIBLE` bleibt das Verhalten strikt:
Exit `8` mit `primaryBlockedReason = ROLLBACK_NOT_POSSIBLE` und mindestens
einem Blocker fuer die betroffenen Operationen.

Die Reihenfolge des Down-SQL entsteht aus den inversen Operationen und deren
umgekehrten Abhaengigkeiten. Sie ist nicht identisch mit der Up-Reihenfolge.
Akzeptanzbeispiel: Wenn Up eine Tabelle und danach eine View erzeugt, muss Down
zuerst die View und danach die Tabelle entfernen.

Fuer `MANUAL_REQUIRED` gilt im ersten Slice ebenfalls strikt: Es wird kein
automatisches Down-SQL erzeugt. Der Lauf endet mit Exit `8` und
`primaryBlockedReason = MANUAL_ACTION_REQUIRED`; die Blocker-Liste weist die
betroffenen Operationen aus. Manuelle Down-Bloecke oder partielle
Rollback-Artefakte sind nicht Bestandteil dieses Plans.

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
3. Nicht Bestandteil dieses Plans: versioniertes `DiffResult`-Artefakt als
   Input fuer `schema rollback`.

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

- [x] `spec/cli-spec.md` fuer `schema migrate`/`schema rollback` schaerfen
- [x] globale Exit-Code-Tabelle in `spec/cli-spec.md` um den geplanten
  Migrations-Blocker-Code `8` ergaenzen oder einen bestehenden Code verbindlich
  wiederverwenden
- [x] `spec/design.md` um `DiffResult` als Zwischenvertrag ergaenzen
- [x] private `SchemaComparator.DiffResult<N, D>` umbenennen
- [x] Comparator-Luecke fuer `CHECK`-/`EXCLUDE`-Constraints entscheiden:
  entweder `SchemaDiff`/`TableComparator` so erweitern, dass diese Constraints
  verlustfrei diffbar sind, oder einen Vor-Normalisierungs-Detector einfuehren,
  der betroffene Tabellen als nicht vollstaendig diffbar blockiert. Ein
  stilles Wegnormalisieren mit anschliessendem SQL-Rendering fuer dieselbe
  Tabelle ist nicht zulaessig.
- [x] klare Begriffe festlegen:
  - `SchemaDiff` = struktureller Unterschied
  - `DiffView` = stabiler Compare-Output
  - `DiffResult` = migrationsfaehiger Operationsplan
  - `MigrationDdlResult` = gerenderte Up-/Down-DDL
- [x] CLI-Ausgabeziele fuer `schema migrate`/`schema rollback` verbindlich
  spezifizieren: stdout-Fallback fuer Up-SQL, kein impliziter Report-Sidecar,
  `--rollback-output` als einziger Down-SQL-Pfad fuer SQL-rendernde
  Rollback-Laeufe, `--plan-only --generate-rollback` ohne Down-SQL-Artefakt
  und `--execute --dry-run` als Exit `2`
- [x] `--execute` als auditpflichtigen Pfad spezifizieren: ohne explizites
  `--report` Exit `2`, bei DDL-Ausfuehrungsfehlern Exit `5` mit strukturiertem
  Ausfuehrungsstatus statt Exit `7` oder `8`

### Phase B - Core-Vertrag

- [x] `DiffResult`
- [x] `DiffOperation`
- [x] `DiffObjectRef`
- [x] `DiffPhase`
- [x] `DiffObjectType`
- [x] `Reversibility`
- [x] `OperationRisks`
- [x] `OperationRisk`
- [x] `DiffDiagnostic`
- [x] stabile Operation-IDs
- [x] deterministische ID-Bildung aus Operationstyp, Objektpfad und
  Payload-Fingerprint
- [x] Operation-Payloads fuer Rendering und Rollback
- [x] gemeinsamer Normalizer fuer reverse-generierte Schema-Metadaten, den
  `schema compare` und `schema migrate` nutzen
- [x] kanonische Fingerprint-Projektion fuer Migrations-Reports,
  SQL-Metadatenblock, Nach-Compare und `schema rollback`-Driftpruefung
- [x] planungsfaehiger Dependency-Vertrag fuer Views, mindestens Tabellen- und
  Spaltenabhaengigkeiten fuer `DropTable`, `DropColumn` und `AlterColumn*`
- [x] Tests fuer leere Diffs, deterministische Sortierung, Dependency-Sortierung,
  inverse Down-Sortierung, Payload-Mapping, Up-/Down-Risiko-Mapping und
  Reverse-Marker-Normalisierung

### Phase C - Planner

- [x] `DiffPlanner` implementieren
- [x] Mapping von `SchemaDiff` zu Operationen
- [x] Dependency-/Phasen-Sortierung
- [x] inverse Dependency-/Phasen-Sortierung fuer Down-Operationen spezifizieren
  und testen
- [x] View-Abhaengigkeiten aus Reader-/Dependency-Daten nutzen; bei fehlender
  belastbarer Abhaengigkeitsinformation blockierende Diagnosen erzeugen
- [x] Reversibilitaetsklassifizierung
- [x] destruktive Operationen markieren
- [x] Rename-Kandidaten nur diagnostizieren, nicht automatisch migrieren
- [x] `CHECK`-/`EXCLUDE`-Constraint-Aenderungen nur planen, wenn der Compare-Kern
  sie verlustfrei liefert; andernfalls muss ein Vor-Normalisierungs-Detector
  betroffene Tabellen als nicht vollstaendig diffbar blockieren statt die
  Aenderung still verschwinden zu lassen

### Phase D - Dialekt-DDL fuer erste Matrix

Erste realistische Matrix:

- [x] PostgreSQL: Tabellen, Spalten, PK/FK/Unique-Constraints, Indizes, Views mit
  getrennter Strategie fuer kompatibles `CREATE OR REPLACE VIEW` und explizites
  Drop/Recreate, `AlterColumnType` nur fuer getestete implizite Casts ohne
  `USING`, einfache Enum-Custom-Types ohne nicht triviale `ALTER TYPE`-Semantik
- [x] MySQL: Tabellen, Spalten, PK/FK/Unique-Constraints, Indizes, Views nur mit
  explizit belegbaren table-level Dependencies; spaltenveraendernde Operationen
  unter Views nur mit expliziten column-level Dependencies und ausreichenden
  Introspection-Privilegien
- [x] SQLite: Tabellen, Spalten, Indizes, einfache Views, vollstaendige
  RebuildTable-Planung fuer SQLite-pflichtige Table-Rebuilds

`CHECK`- und `EXCLUDE`-Constraints sind in dieser Matrix nur dann enthalten,
wenn Phase A/B den Compare-Kern so erweitert, dass diese Aenderungen als
verlustfreier `SchemaDiff` vorliegen. Ohne diese Erweiterung gehoeren sie nicht
zur renderbaren ersten Matrix; betroffene Tabellen muessen dann vor der
Constraint-Normalisierung erkannt und mit blockierender Diagnose aus der
SQL-Erzeugung ausgeschlossen werden.

Zusaetzlich fuer SQLite verbindlich:

- [x] `DialectMigrationPlan` aus `DiffResult` ableiten
- [x] Rebuild-Gruppierung pro Tabelle implementieren
- [x] deterministische Temp-Namen und Kollisionssuffixe erzeugen
- [x] Spaltenmapping inklusive `CAST`, Default-/NULL-Fill und Blocker fuer
  nicht automatisch fuellbare `NOT NULL`-Spalten rendern; automatische
  SQLite-Casts fuer `AlterColumnType` nur mit expliziter, getesteter
  Quell-/Ziel-Cast-Matrix und den noetigen Daten-Preflights erlauben
- [x] alte/neue Tabellenconstraints in `CREATE TABLE` korrekt abbilden
- [x] Indizes, Trigger und bekannte abhaengige Views nach dem Rebuild wieder
  erzeugen
- [x] `PRAGMA foreign_keys`-Handling, `foreign_key_check`, `BEGIN IMMEDIATE`,
  `COMMIT` und `ROLLBACK` als Runner-Vertrag abbilden
- [x] Down-Rebuild aus reversiblem Up-Rebuild als eigenen inversen Rebuild-Plan
  erzeugen, richtungsabhaengige Felder neu bestimmen und bei Datenverlust- oder
  Manual-Faellen blockieren

Nicht in der ersten Matrix:

- vollstaendige Routine-Migration
- vollstaendige Trigger-Migration
- Sequence-Migrationen, inklusive Sequence-Emulationen
- automatische Daten-Transformationen

### Phase E - CLI-Runner

- [x] `SchemaMigrateRunner`
- [x] Operand-Aufloesung fuer Soll-Schema, Ist-Datenbank und Ist-Schema-Datei
- [x] Reverse des Ist-Zustands bei DB-Target
- [x] Normalisierung reverse-generierter Schema-Metadaten vor Compare/Planning
- [x] Datei-zu-Datei-Planung ohne Live-Datenbank
- [x] `--dialect`-Pflicht und Dialektvalidierung fuer Datei-Targets
- [x] Compare
- [x] Planner
- [x] Dialekt-DDL
- [x] `--plan-only`
- [x] `--allow-destructive`
- [x] `--generate-rollback`
- [x] `--rollback-output` als Pflichtausgabe fuer SQL-rendernde
  `--generate-rollback`-Laeufe
- [x] `--plan-only --generate-rollback` als reine Rollback-Faehigkeitspruefung ohne
  Down-SQL-Artefakt behandeln und mit `--rollback-output` als Exit `2` ablehnen
- [x] `--execute`
- [x] `--execute` ohne `--report` als Exit `2` ablehnen
- [x] `--dry-run` als Default ohne Ausfuehrung
- [x] `--execute --dry-run` fuer `schema migrate` und `schema rollback` als
  Exit `2` ablehnen
- [x] `--execute` mit Datei-Target als Exit `2` ablehnen
- [x] Up-DDL gegen `--target` ausfuehren, wenn `--execute` gesetzt ist
- [x] Dialektbezogene Transaktions-/Autocommit-Semantik vor Ausfuehrung bestimmen
  und im Report ausweisen
- [x] DDL-Ausfuehrungsfehler nach Start von `--execute` als Exit `5` abbilden,
  inklusive `executionStarted`, `executionCompleted`, `statementsAttempted`,
  `lastStatementOperationIds`, `transactionRolledBack` und
  `sideEffectsPossible`
- [x] Down-SQL-Artefakt erzeugen, wenn `--generate-rollback` gesetzt ist; bei
  `--execute` erst nach erfolgreichem Nach-Compare final schreiben
- [x] Recovery-Fall fuer `--execute --generate-rollback` nach bereits
  ausgefuehrtem Up abbilden: Nach-Introspection-/Nach-Compare-/
  Finalisierungsfehler duerfen bestehende Rollback-Pfade nicht ueberschreiben;
  ein markiertes Recovery-Rollback-Artefakt darf nur entstehen, wenn kein
  beobachteter Post-Up-Fingerprint dem Soll-Fingerprint widerspricht
- [x] gemeinsamer Artefakt-Writer fuer Up-SQL, Down-SQL und Reports mit temporaerer
  Datei im Zielverzeichnis und atomarer Finalisierung
- [x] `SchemaRollbackRunner` fuer Down-SQL-Ausfuehrung
- [x] strikter Parser fuer den `d-migrate rollback-sql v1`-Metadatenblock:
  Begrenzungskommentare, kanonisches JSON, Pflichtfelder,
  Fingerprint-Algorithmus, `artifactHashAlgorithm`, `artifactHash`,
  Integritaetspruefung ueber kanonischen Header ohne `artifactHash` plus
  SQL-Body und Secret-Scrubbing
- [x] Zielzustands-Pruefung vor `schema rollback --execute`
- [x] Zieldialekt-Pruefung vor `schema rollback --execute`; Abweichungen vom
  Metadatenblock enden mit `TARGET_DIALECT_MISMATCH`
- [x] Rollback-SQL gegen `--target` ausfuehren, wenn `schema rollback --execute`
  genutzt wird
- [x] `--allow-destructive` auch fuer destruktive Down-SQL-Ausfuehrung auswerten
- [x] SQLite-Rebuild-Schritte als unteilbare Ausfuehrungseinheit behandeln und bei
  Fehlern abbrechen, rollbacken und im Report die letzte Rebuild-Phase
  ausweisen
- [x] Nach-Compare nach Up-Ausfuehrung gegen das Soll-Schema
- [x] Report-Ausgabe
- [x] sauberes Exit-Code-Mapping

### Phase F - Tests und Smokes

Wird in Sub-Slices ausgeliefert. Reihenfolge approximativ — die
ersten drei sind Voraussetzung fuer alles Weitere, danach kann je
nach Risiko-/Nutzenabwaegung gepriorisiert werden.

#### F.1 — Golden-Master-DDL-Tests pro Dialekt ✅ (2026-05-09)

- [x] Up-DDL-Goldens fuer Postgres / MySQL / SQLite pro Szenario
  (`add-table`, `add-column`, `drop-column`, `alter-column-type-safe`).
- [x] Renderer-Aenderung ohne Golden-Update faellt laut.
- [x] Down-Goldens und SQLite-Rebuild-Spezialfaelle (Temp-Namen-
  Determinismus, Spaltenmapping-Edge-Cases) folgen in spaeteren
  F.x-Slices oder gemeinsam mit den Round-Trip-Smokes.

#### F.2 — Round-Trip-Smoke PostgreSQL ✅ (2026-05-10)

- [x] Testcontainers-basiert (existierende `test/integration-postgresql/`-
  Infrastruktur wiederverwenden).
- [x] Ausgangsschema in DB einrichten.
- [x] `schema migrate --execute` mit `--generate-rollback` und
  `--rollback-output`.
- [x] Reverse + Compare gegen Ziel-Schema.
- [x] `schema rollback --execute`.
- [x] Reverse + Compare gegen Ausgangsschema.

Beim Verdrahten ist eine zuvor unentdeckte Fingerprint-/Drift-Schwachstelle
aufgefallen: `MigrationFingerprint.project()` enthielt `name=`/`version=`,
weshalb post-`--execute` Drift-Pruefungen und `verifyTargetMatchesArtefact`
fuer reale YAML-vs-Reverse-Roundtrips niemals geglueckt waeren. Im selben
Slice mitgefixt:

- `MigrationFingerprint` ist jetzt ein reiner Content-Hash; Algorithmus-ID
  bleibt `schema-fingerprint-v1` (es existieren noch keine produktiven
  Artefakte).
- `SchemaMigrateRunner.runPostCompare` vergleicht ueber Fingerprints statt
  `SchemaDiff.isEmpty()`, symmetrisch zu `SchemaRollbackRunner`.

#### F.3 — Round-Trip-Smoke MySQL ✅ (2026-05-10)

- [x] Analog zu F.2 fuer die erste reversible Operationsmatrix
  (Plan §6.3); `AlterColumnNullability` bleibt Carve-Out.

MySQL-spezifische Deltas zu F.2: Reverse-Reader setzt `required = false`
auf PK-Spalten und fuellt `metadata = TableMetadata(engine = "InnoDB")`
fuer alle Tabellen — Soll/Ausgangsschema im Test muessen das exakt
spiegeln, sonst Fingerprint-Mismatch. Carve-Out gewahrt: Test fasst nur
AddColumn (Up) / DropColumn (Down) an, kein `AlterColumnNullability`.

#### F.4 — Round-Trip-Smoke SQLite ✅ (2026-05-10)

- [x] Direkt-reversible Operationen ohne Rebuild (in-memory; kein
  Testcontainers noetig). — F.4.a `AddColumn`-Round-Trip via direktem
  `ALTER TABLE`.
- [x] Mindestens ein echter Table-Rebuild im Pfad, um die
  RebuildTable-Pipeline gegen eine echte SQLite-Engine zu fahren
  (Spaltenmapping, foreign_key_check, BEGIN IMMEDIATE / COMMIT). —
  F.4.b `AlterColumnNullability`-Round-Trip ueber den 9-Statement-
  Rebuild-Pfad.

F.4 hat zwei produktive Bugs offengelegt, die im selben Slice gefixt
sind:

1. **`JdbcMigrationExecutor.runAll`-Tx-Modell-Bruch fuer SQLite-Rebuild.**
   `autoCommit = false` + Trailing `conn.commit()` kollidiert mit den
   vom `SqliteRebuildRenderer` emittierten expliziten `BEGIN IMMEDIATE`
   /`COMMIT` (xerial-sqlite: `cannot start a transaction within a
   transaction`). Fix: per-Stream-Dispatch zwischen "Runner-owned tx"
   (PG/MySQL/SQLite-direkt) und "Stream-owned tx" (SQLite-Rebuild),
   detektiert ueber das Vorhandensein eines expliziten
   `BEGIN`-Statements im Stream. Der Stream-owned-Pfad bleibt auf
   `autoCommit = true` und ueberlaesst die Tx-Fuehrung dem SQL-Body.
2. **`SchemaRollbackRunner` buendelte den ganzen Multi-Statement-
   Artefakt-Body in ein einziges `MigrationDdlStatement.sql`.** xerial-
   sqlite (und andere JDBC-Driver) fuehren multi-statement Strings via
   `Statement.execute(...)` nicht aus — alles nach dem ersten `;` wurde
   stillschweigend uebersprungen. Fix: Body wieder in einzelne
   Statements splitten (Separator `\n\n`, identisch zur
   `RollbackArtefactBuilder.canonicalBody`-Joining-Logik).

Test-Fixture-Mirror `executeAgainstPool` 1:1 nachgezogen.

#### F.5 — Recovery-Rollback-Artefakt ✅ (2026-05-10)

Wird in Sub-Slices ausgeliefert. Das nominelle Vertragsbild fuer den
Recovery-Pfad steht in §7.1 (Zeilen 1135-1166 und 1445-1474); diese
Sub-Slices verdrahten es schrittweise. F.5.i ist redundanter
Bestaetigungstest fuer eine bereits in E.5 verdrahtete Stelle.

- [x] **F.5.a** Observed Post-Up-Fingerprint in `runPostCompare`
  capturen und an die Artefakt-Builder-Pfade durchreichen, statt
  weiterhin nur einen Drift-Boolean zurueckzugeben (heutiger Stand:
  `desiredFp` als Platzhalter).
- [x] **F.5.b** Happy-Path-Artefakt (`schema migrate --execute
  --generate-rollback` ohne Drift) verwendet observed FP als
  `postUpFingerprint`, setzt `postUpVerified=true`. Erfuellt §10
  "Metadatenblock enthaelt den beobachteten Post-Up-Fingerprint".
- [x] **F.5.c** Report-Felder `upExecuted` / `rollbackFinalized` in
  `SchemaMigrateExecutionView` ergaenzen und in `finalize` populieren.
  Erfuellt den ersten Halbsatz der §10-Side-Effect-Akzeptanz.
- [x] **F.5.d** Recovery-Artefakt-Naming `<output>.recovery.<timestamp>.rollback.sql`,
  atomic write zum gleichen Verzeichnis. Bestehender `--rollback-output`
  wird nie ueberschrieben (auch im Recovery-Pfad).
- [x] **F.5.e** Recovery Case A — Post-Introspection schlaegt nach
  erfolgreichem Up fehl: Recovery-Artefakt mit
  `allowedPostUpFingerprints=[desiredFp]`, `postUpVerified=false`
  schreiben.
- [x] **F.5.f** Recovery Case B — Atomic-Write des regulaeren
  `--rollback-output` schlaegt bei sauberem Post-Compare fehl:
  Recovery-Artefakt mit `allowedPostUpFingerprints=[observedFp]`,
  `postUpVerified=true` schreiben.
- [x] **F.5.g** Recovery Case C (Negativ) — Drift detected
  (observed != desired): KEIN automatisch ausfuehrbares Recovery-
  Artefakt; Exit `5`; observed FP + `rollbackFinalized=false` im
  Report. (Negativ-Test im F.5-Scope.)
- [x] **F.5.h** Recovery-Artefakt-Schreib-Fehler (FS-Race, kein
  atomares Replace im Zielverzeichnis): Exit `7` mit strukturierter
  "Up bereits ausgefuehrt, manuelle Sicherung der Ziel-Datenbank
  erforderlich"-Meldung.
- [x] **F.5.i** `SchemaRollbackRunner.verifyTargetMatchesArtefact`
  ist bereits seit E.5 verdrahtet (Recovery-Pfad akzeptiert
  `allowedPostUpFingerprints`-Whitelist). Bestaetigungstest mit
  Multi-FP `allowedPostUpFingerprints`-Artefakt, das gegen einen der
  zugelassenen Zustaende erfolgreich executet.
- [x] **F.5.j** Test fuer Case C im Migrate-Runner: Drift-Szenario,
  kein File geschrieben (auch nicht im `.recovery.<ts>.sql`-Pfad);
  Report carries `upExecuted=true`, `rollbackFinalized=false`,
  observed FP, manuelle Pruefpflicht-Note.

#### F.6 — Edge-Cases und Hardening ✅ (2026-05-10)

Wird in Sub-Slices ausgeliefert. Die F.6-Bullets aus der ersten
Plan-Version werden auf sechs DoD-Punkte aufgebohrt; Reihenfolge
nach Aufwand (kleinste Slice zuerst):

- [x] **F.6.d** Erweiterte Metadatenblock-Tests — **Secret-Scrubbing**
  (Header carries no JDBC URL, secret-keyword shapes, OS-paths,
  SSH-private-key markers — pinned via einer broaderen
  `FORBIDDEN_HEADER_SUBSTRINGS`-Konstante) und **Round-Trip-
  Tampering** ueber alle Metadata-Felder
  (`recovery=false→true`, `dialect`, `formatVersion`,
  `risk.destructive`, `operationIds[]`, `artifactHash`-self via
  recompute-not-echo). Die strukturellen Parser-Faelle (doppelte
  Bloecke via E.5; `UNKNOWN_FORMAT_VERSION`,
  `UNKNOWN_ARTIFACT_HASH_ALGORITHM`, `MISSING_FIELD_*`,
  `TYPE_MISMATCH_*`, `MALFORMED_HEADER_PREFIX`, JSON-Syntax-Fehler
  via F.6.d-Follow-up) sind in `RollbackArtefactParserTest`
  abgedeckt. Whitespace-only-Tampering ist als positive Lenient-
  Parse-Strict-Canonical-Hash-Invariante gepinnt.
- [x] **F.6.a** Atomic-Writer-Edge-Cases — Tests dass Render-/
  Blocker-/Execution-Fehler bestehende `--output` /
  `--rollback-output` / `--report` NICHT ueberschreiben. Pinnt
  §10-Akzeptanz "Up-SQL-, Down-SQL- und Report-Dateien werden erst
  nach vollstaendigem Rendering und erfolgreicher Blocker-Pruefung
  atomar finalisiert; bestehende Artefakte bleiben bei Fehlern
  unveraendert." Pinned in `SchemaMigrateRunnerArtefactProtectionTest`:
  Execute-Error / Destructive-Blocker / Down-Render-Blocker /
  Validation-Failure — pre-existing `--output` und
  `--rollback-output` Bytes bleiben unveraendert UND `atomicWriter`
  wird fuer diese Pfade NIE aufgerufen. `--report` ist bewusst
  ausserhalb des Contracts (Runner finalisiert IMMER ein Report).
- [x] **F.6.e** Erweiterte CLI-Exit-Code-Tests — stdout-vs-file-
  Ausgabeziele, fehlende implizite Report-Sidecars, Flag-
  Kombinationen, die in E.1-E.6-Lueken stehen. Die Hauptpfade sind
  bereits durch Unit-Tests abgedeckt; F.6.e schliesst die Edge-
  Lueken. Pinned in `SchemaMigrateRunnerCliExitCodeTest`:
  `--dry-run+--execute` Exit 2, `--execute+--plan-only` Exit 2,
  `--execute` mit File-Target Exit 2, Up-SQL-stdout-Echo wenn
  `--output` fehlt (positive), `--report` Pfad-Kollision Exit 2,
  invalid operand parsing Exit 2, KEIN implizites Report-Sidecar
  an einem Default-Pfad bei fehlendem `--report`.
- [x] **F.6.f** Mid-DDL-Ausfuehrungsfehler — `schema migrate
  --execute` mit DDL-Failure NACH erfolgreichem ersten Statement
  plus rollback-Failure (`sideEffectsPossible=true`); strukturierter
  Trace beweist "halb angewendet" und unterscheidet sich vom Trace
  mit `transactionRolledBack=true`. E.4 hat den Pfad implementiert;
  F.6.f deckt die Variante mit halb angewendetem Up plus rollback-
  Failure ab. Pinned in `SchemaMigrateRunnerExecuteTest`
  ("F.6.f — half-applied Up + rollback failure …"): Multi-
  Statement-Render mit Mid-Statement-Failure, Trace
  `sideEffectsPossible=true`, `transactionRolledBack=false`,
  `upExecuted=true`, `lastStatementOperationIds=[op-2]`,
  `rollbackFinalized=null`, kein Down-Artefakt geschrieben.
  Distinktion vom Clean-Rollback-Pfad
  (`transactionRolledBack=true`/`upExecuted=false`) bleibt durch
  den bestehenden "executor failure surfaces executionError +
  exit 5"-Test im selben Spec gepinnt.
- [x] **F.6.b** MySQL View-Dependency-Block — Tests dass column-
  altering Ops (`DropColumn`, `AlterColumnType`, `AlterColumnNullability`)
  unter Views OHNE explizite column-level Dependencies aus einer
  Schema-Datei oder Adapter-Projektion mit Diagnose blocken. Plan
  §6.3-Mandate; betrifft den Live-DB-Pfad weil MySQL keine
  spaltenpraezise `VIEW_COLUMN_USAGE`-Quelle liefert. Implementiert
  in `DiffPlanner.detectViewColumnDepsBlockers(...)` als dialect-
  agnostische Check-Stufe: View mit `dependencies.tables` aber
  ohne `dependencies.columns[T]` → BLOCKER-Diagnose
  `VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS` an die jeweilige
  Spalten-Op gebunden. PostgreSQL-Adapter mit pg_depend-Projektion
  liefert column-level Deps und triggert nicht; MySQL-Adapter
  triggert defaultmaessig solange keine explizite Schema-Datei
  column-level Deps liefert. Pinned in `DiffPlannerTest`:
  DropColumn / AlterColumnType / AlterColumnNullability blocken
  bei table-level-only Deps; AddColumn blockt nicht; column-level
  Deps schalten den Block aus; Views nur in `current` (slated for
  DropView) blocken weiterhin weil sie zur Execute-Zeit live sind.
- [x] **F.6.c** SQLite-Rebuild-Atomic-Execution Integration-Test —
  Pinnt dass der Rebuild als unteilbare Einheit ausgefuehrt wird:
  bei Mid-Rebuild-Fehler (z.B. INSERT-SELECT failed) bleibt die DB
  im Pre-Rebuild-Zustand (kein gedroppter Original-Table, keine
  Halb-Migration). Erweiterung des F.4.b-Smokes mit einer Failure-
  Variante. Pinned in `SqliteMigrateRoundTripIntegrationTest`
  ("F.6.c — mid-rebuild failure …"): NULL→NOT NULL Transition
  mit pre-populated NULL row triggert NOT NULL Violation in der
  INSERT-SELECT (Statement 4 des 9-Statement-Pipelines), Stream-
  owned Tx-Modell macht ROLLBACK, Exit 5, Trace
  `transactionRolledBack=true`/`sideEffectsPossible=false`,
  Schema-Fingerprint vor und nach dem Failure identisch, Original-
  Row erhalten, kein orphan `__dmg_rebuild_*` Table im
  `sqlite_master`.

Coverage-Punkte aus dem urspruenglichen Phase-F-Bullet-Set, die NICHT
im Sub-Slice-Plan stehen, sind in den vorhergehenden Phasen bereits
abgedeckt:

- Core-Planner-Tests → Phase C (`DiffPlannerTest` etc.).
- CLI-Flag- / Exit-Code-Tests → E.1-E.6 unit tests.
- Metadatenblock-Tests (Hauptfaelle) → E.5
  (`RollbackArtefactParserTest`).

### Phase G - Dialect-Hardening

Schliesst die drei verbliebenen §10-Akzeptanzkriterien, die Phase F
nicht abdeckt, weil es Renderer- bzw. Adapter-Implementierung
braucht (nicht nur Tests). Reihenfolge nach Aufwand aufsteigend:

- [x] **G.1** SQLite `AlterColumnType` Cast-Matrix ✅ (2026-05-11) —
  `SqliteRebuildRenderer` pruefte das `INSERT-SELECT` bisher mit
  einer Type-Affinity-basierten `CAST`-Heuristik. Ersetzt durch
  eine explizite Whitelist sicherer `(source, target)`-Paare in
  `SqliteCastMatrix`; Paare ausserhalb der Matrix blockieren als
  `MANUAL_ACTION_REQUIRED` + Diagnose `SQLITE_CAST_NOT_WHITELISTED`.
  Whitelist (im Code dokumentiert):
  - Integer-Familie (`SmallInt`/`Integer`/`BigInteger`) — alle 6
    geordneten Paare; SQLite-Storage ist nativ INTEGER ohne Width-
    Constraint, daher CAST = identity; Range-Truncation ist
    Downstream-Dialekt-Verantwortung.
  - Text-Familie mit nicht-verkleinerndem Laengen-Constraint
    (`Text(a)→Text(b)`, `Char(a)→Char(b)`, `Char(a)→Text(b)`,
    `Text(a)→Char(b)` jeweils mit `b ≥ a` bzw. `b == null`).
  - `Date → DateTime(tz=false)` — Date ist Teilmenge von DateTime;
    Storage beidseitig TEXT (ISO-8601).
  Explizit blockiert: `Float`↔`Decimal` (Praezision), `Boolean`↔
  Integer-Familie (Bestandsdaten-Mehrdeutigkeit), `DateTime`-TZ-
  und Komponenten-Loss, `Text`→`Integer`/`Float`/`Uuid` (silent
  0/0.0/invalid), `Integer`/`Float`→`Text` (downstream-riskant),
  `Binary`↔`Text` (offene Frage L2006), verkleinernde Laengen
  (Truncation), `Identifier` (`AUTOINCREMENT`-Semantik), `Email`-
  Mappings (semantische 254-char-Invariante ausserhalb der
  strukturellen Matrix). Daten-Preflights (zB
  `SELECT COUNT(*) WHERE CAST(...) IS NULL` fuer would-be unsafe
  Casts) bleiben Carve-Out auf 0.9.8+, weil sie eine Live-DB
  voraussetzen und Phase G den Renderer-Layer hardened.
  Adressiert §10-DoD "SQLite-`AlterColumnType` nutzt automatische
  `CAST`-Ausdruecke nur mit expliziter, getesteter Quell-/Ziel-
  Cast-Matrix" (L2040-2043).

- [x] **G.2** MySQL `VIEW_TABLE_USAGE` Privilege-Preflight ✅ (2026-05-11) —
  `MysqlSchemaReader` liest jetzt `VIEW_TABLE_USAGE` (zusaetzlich zu
  `VIEW_ROUTINE_USAGE`) und befuellt `ViewDefinition.dependencies.tables`
  fuer MySQL-Views. Detektion stiller Unvollstaendigkeit: wenn
  `VIEW_TABLE_USAGE` 0 Rows fuer einen existierenden View liefert
  (Permission-Denied wirft, aber fehlende SHOW VIEW-Privilegien auf
  abhaengigen Tabellen sind silent), setzt der Reader
  `DependencyInfo.projectionComplete = false`. Andere Adapter
  (PostgreSQL via `pg_depend`, SQLite via `sqlite_master`-Deparse,
  schema-file-Loader) defaulten auf `true`.

  `DiffPlanner.detectIncompleteViewProjections` (analog F.6.b)
  blockt zwei Operations-Klassen mit `VIEW_DEPENDENCY_PROJECTION_INCOMPLETE`:
  - `ReplaceView` fuer die betroffene View — der Renderer wuerde die
    DDL neu generieren, aber die Cascade-Effekte auf versteckte
    Dependencies sind unbekannt.
  - `DropColumn`/`AlterColumnType`/`AlterColumnNullability` auf einer
    Tabelle in `dependencies.tables` der incomplete View — die
    `tables`-Liste ist Adapter-trusted, auch wenn die Projektion
    als Ganzes incomplete ist.

  Spaltenveraendernde Ops auf Tabellen ausserhalb der listed-Tables
  bleiben bewusst ungeblockt — ein konservativer All-or-Nothing-
  Block waere unactionable. Die Incompleteness der `tables`-Liste
  selbst wird ueber den ReplaceView-Block abgefangen.

  Adressiert §10-DoD "MySQL behandelt fehlende oder nicht belegbare
  Privilegien fuer `VIEW_TABLE_USAGE`/`VIEW_ROUTINE_USAGE` als
  unvollstaendige Dependency-Projektion" (L2096-L2099).

- [x] **G.3** PostgreSQL `ReplaceView` Compatibility-Decision ✅ (2026-05-11) —
  Strict-Variante: `DiffPlanner.splitReplaceViewsForColumnConflicts`
  laeuft zwischen `OperationMapper.map` und `DependencyAnalyzer.attach`.
  Pro `ReplaceView`-Op sammelt der Planner die `(table, column)`-Paare
  aus `view.before.dependencies.columns` und `view.after.dependencies.
  columns` und prueft sie gegen `columnAlteringTarget(op)` aller
  anderen Ops im Plan (deckt `DropColumn`, `AlterColumnType`,
  `AlterColumnNullability` ab). Bei Konflikt:
  - `ReplaceView` wird durch `DropView` (mit der `before`-Definition)
    + `CreateView` (mit der `after`-Definition) ersetzt; IDs sind
    deterministisch aus `OperationIdFactory.makeId` plus
    `::g3-split`-Suffix.
  - Conflicting Spalten-Ops bekommen `dependencies += dropView.id`
    — laufen NACH dem Drop.
  - `CreateView` bekommt `dependencies += conflictingOp.id` fuer
    jeden konfligierende Spalten-Op — laeuft NACH allen.

  Topologischer Sorter respektiert diese expliziten Dep-Edges
  und ordnet `DropView → DropColumn → CreateView` (cross-phase).
  Reine Body-Aenderungen ohne Tabellen-Impact bleiben `ReplaceView`
  — der renderer-`CREATE OR REPLACE VIEW`-Pfad bleibt aktiv, weil
  er idempotenter ist (kein Berechtigungs-/Owner-/Grants-Drift).

  Views ohne `dependencies.columns` werden vom Split ignoriert
  (kein Signal, welche Spalten relevant sind) — die F.6.b-Diagnose
  `VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS` blockt diesen Fall
  unabhaengig im Detection-Pfad des Planners. G.3 und F.6.b sind
  komplementaer: F.6.b blockt bei fehlender Information, G.3
  splittet bei vorhandener Information.

  Implementierung ist dialect-agnostisch im Planner-Layer (siehe
  Plan §6.2 — PostgreSQL-View-Vertrag; §6.4 ist der SQLite-Rebuild-
  Vertrag): SQLite rendert `ReplaceView` ohnehin als Drop+Create
  (kein PG-Aequivalent), MySQL profitiert ebenso wie PostgreSQL.

  **Carve-Out (auf 0.9.8+ verschoben)**: Spaltensignatur-Compatibility
  (view-eigene Spaltenanzahl/-reihenfolge/-typen) braucht eine
  `ViewColumn`-Modellebene oder Pre-Render-Probe; ohne diese
  Information kann die strict variante false-negatives produzieren,
  wenn die View ihre Spaltensignatur aendert ohne dass Tables-
  referenzierte Spalten beruehrt werden.

  Adressiert §10-DoD "PostgreSQL rendert `ReplaceView` nur dann
  als `CREATE OR REPLACE VIEW`, wenn die View-Aenderung
  kompatibel ist" (L2078-L2083) — Strict-Variante. Spaltensignatur-
  Compatibility bleibt §10-Carve-Out auf 0.9.8+.

### Phase H - SQLite-Rebuild-Vertrag formalisieren

Schliesst die strukturellen Luecken im SQLite-Rebuild-Vertrag, die
beim Audit der §10-DoD "SQLite-Rebuilds werden durch einen
expliziten `DialectMigrationPlan` geplant" aufgedeckt wurden:
Bucket-Klassifikation + canonical 9-Statement-Sequence + Atomicity
sind funktional erfuellt, aber Plan-§6.4 verlangt zusaetzlich ein
formales Plan-Struct mit `sourceOperationIds`/`risk`/Drop+Recreate-
Trennung, Temp-Namen-Kollisionsprueffung und eine 6-Punkte-Preflight-
Liste. Der heutige Code hat stattdessen `Classification(rebuildBuckets,
simpleOps)` + Inline-SQL-Erzeugung im Renderer.

Reihenfolge nach Abhaengigkeit aufsteigend — H.1 ist Voraussetzung
fuer H.3/H.4:

- [x] **H.1a** `SqliteRebuildPlan`-Datenstruktur anlegen ✅ (2026-05-11) —
  Neuer Typ `SqliteRebuildPlan` mit den in §6.4 (L884-936)
  geforderten Feldern. **§6.4 modelliert `RebuildTable` als
  `DialectMigrationStep`**; die zwei Step-Vertragsfelder
  `sourceOperationIds: Set<String>` und `risk: OperationRisk` MUESSEN
  in den Plan, sonst geht Attribution (welche fachlichen Ops loest
  der Rebuild aus), Risikoauswertung (BlockSet im Migrate-Report)
  und Rollback-Verknuepfung verloren.

  Vollstaendige Plan-Felder:

  - `sourceOperationIds: Set<String>` — Vereinigung der Bucket-Op-IDs.
  - `risk: OperationRisk` — pre-aggregiertes Bucket-Risiko (richtungs-
    abhaengig; Planner bekommt direction beim Build).
  - `originalTableName`, `oldTable`, `newTable`, `newTableTempName`
  - `preservedColumns: List<ColumnCopyMapping>` mit **strukturierten**
    Feldern pro Eintrag (`sourceColumn: String`, `targetColumn: String`,
    `expressionSql: String`) — nicht nur ein opaker `selectExpression`-
    String. H.4 `SOURCE_COLUMNS_EXIST` braucht `sourceColumn` als
    eigenes Feld, sonst muesste der Check SQL-Expressions parsen.
  - `addedColumns: List<AddedColumnFill>` (`targetColumn`,
    `expressionSql`), `droppedColumns: List<String>` (source-only
    column names).
  - `indexesToRecreate`
  - `dependentViewsToDrop: List<NamedViewDefinition>` —
    aus `current.views` gefiltert auf Refs zur rebuilt-Table
  - `dependentViewsToRecreate: List<NamedViewDefinition>` —
    aus `desired.views` gefiltert auf Refs zur rebuilt-Table
  - `dependentTriggersToDrop: List<NamedTriggerDefinition>` —
    aus `current.triggers` gefiltert auf rebuilt-Table
  - `dependentTriggersToRecreate: List<NamedTriggerDefinition>` —
    aus `desired.triggers` gefiltert auf rebuilt-Table
  - `preflight: List<SqliteRebuildPreflightCheck>`

  Inklusive Carrier-Klassen (`ColumnCopyMapping`, `AddedColumnFill`,
  `NamedView`, `NamedTrigger`, `SqliteRebuildPreflight`-Enum) und
  Column-Mapping-Modell. H.1a bedeutet **nur das Struct steht**;
  Planner und Renderer sind noch nicht umgestellt. Sub-Ziel: die
  anderen H-Slices haben einen Andock-Punkt.

- [x] **H.1b** Planner produziert / Renderer konsumiert Plan ✅ (2026-05-11) —
  `SqliteRebuildPlanner.planRebuild(table, bucket, source, target)`
  als Factory; `SqliteRebuildRenderer.render(plan, ctx)` als pure
  Konsumption (statt heute `renderRebuild(table, bucket, source,
  target, ctx)`). Reines Refactoring: die emittierte SQL-Sequenz
  bleibt bit-identisch zu pre-H.1b, weil die Plan-Feldwerte exakt
  die Inputs sind, die der Renderer heute inline berechnet. Tests
  pinnen die Plan-Form pro Rebuild-Szenario (Type-Change,
  PK-Reshape, Constraint-Add); bestehende Renderer-Tests bleiben
  unveraendert gruen.

- [x] **H.2** Temp-Namen-Kollisionsprueffung ✅ (2026-05-11) —
  `SqliteRebuildPlanner.tempTableName` ist heute ein
  deterministischer Hash; eine Kollision mit bereits in der
  Ziel-DB existierenden Tabellen/Views/Indizes/Triggern wird nicht
  geprueft. §6.4 (L975-977) verlangt: bei Kollision Suffix `__2`,
  `__3`, ... deterministisch vergeben.

  **Vertrag: der `LiveCatalogSnapshot` wird VOR `planRebuild`
  eingespeist** (analog zum `current: SchemaDefinition`-Input). H.1b
  pinnt den Plan als frozen-after-build; der Renderer ist pure
  Konsumption und darf den `newTableTempName` nicht nachtraeglich
  aendern. Der Catalog-Snapshot ist daher kein Runner-side Probe-
  Resultat, sondern Planner-Input.

  Catalog-Snapshot-Quelle haengt vom Plan-Pfad ab:

  - **Datei-zu-Datei-Planning** (`schema migrate --plan-only` ohne
    Connection): es gibt kein live `sqlite_master`. Der Snapshot
    wird aus `current: SchemaDefinition` synthetisiert (tables, views,
    triggers, alle Index-Namen). Ad-hoc-Objekte ausserhalb des Modells
    (z.B. nicht importierte temporaere Tabellen aus frueheren
    Migrationen) bleiben ausserhalb dieser Garantie. Der
    `--plan-only`-Output kommt mit einem Header-Kommentar
    "Temp-Name-Probe nur gegen Schema-Modell; Live-DB-Catalog kann
    zusaetzliche Kollisionen haben — verifiziere via `d-migrate
    schema migrate --execute`".
  - **Execute-Pfad** (`schema migrate --execute`): heute nutzt
    `SchemaMigrateRunner` denselben Schema-synthetisierten Snapshot
    wie der `--plan-only`-Pfad — die zusaetzliche Live-`sqlite_master`-
    Probe ist als Folge-Slice **H.2.2** auf 0.9.8+ verfolgt. Vorlage:
    `SqliteCatalogSnapshot.union(otherFromLiveProbe)` ist schon
    vorbereitet; was fehlt ist ein `SqliteCatalogSnapshot.fromSqliteMaster(conn)`-
    Loader plus das CLI-Wiring beim Bootstrap. Ad-hoc-Objekte in der
    Live-DB (nicht-importierte Indices/Tabellen) ausserhalb des
    Schema-Modells laufen im Execute-Pfad heute in dasselbe
    `--plan-only`-Risiko: Kollision mit ihnen wird nicht plan-time
    erkannt, sondern slaegt erst beim CREATE-temp-Statement fehl
    (BLOCKER mit `transactionRolledBack`-Trace).

  Bei Kollision: deterministischer Suffix-Fallback `__2`, `__3`, ...
  bis frei — komplett im Planner berechnet. Die fortlaufende Nummer
  ist Plan-Output und wird im Metadatenblock ausgewiesen. Test:
  Planner mit simuliertem Catalog-Kollisions-Set → Plan emittiert
  `__2`-Suffix (rein Plan-Time-deterministisch; Execute-Pfad
  unterscheidet sich nur durch die Snapshot-Quelle, nicht durch die
  Plan-Logik).

- [x] **H.3a** Drop+Recreate abhaengiger Views/Trigger (Plan-/ ✅ (2026-05-11)
  Renderer-Concern) —
  `SqliteRebuildRenderer.kt:65-70` dokumentiert explizit
  "User-defined triggers attached to the rebuilt table are dropped
  … and not recreated"; §6.4 (L995, L1004) verlangt das
  Drop+Recreate aus gespeicherten Definitionen. **Zwei separate
  Mengen** (nicht eine wie ursprueglich im Plan):
  `dependentObjectsToDrop` aus `current.views`/`current.triggers`
  gefiltert auf rebuilt-Table-Refs und `dependentObjectsToRecreate`
  aus `desired.views`/`desired.triggers` analog. Begruendung:
  Mengen koennen divergieren — eine View, die im selben Plan
  entfernt wird, ist in `current.views` aber nicht in
  `desired.views`; eine View, die so geaendert wird dass sie die
  Tabelle nicht mehr referenziert, faellt in `current` an aber
  nicht in `desired`. Renderer emittiert die Drop-Statements vor
  `DROP TABLE` (aus `*ToDrop`) und die Create-Statements nach
  RENAME (aus `*ToRecreate`).

  **Absorption-Vertrag fuer simpleOps**: heute laesst
  `SqliteRebuildPlanner.classify` View- und Trigger-Ops
  (`CreateView`/`DropView`/`ReplaceView`/`CreateTrigger`/
  `DropTrigger`/`ReplaceTrigger`) immer in `simpleOps`, und der
  Generator rendert simpleOps **nach** den Rebuilds. Mit H.3a
  fuehrt das zu Doppel-Emission: eine `desired`-only View, die
  durch `dependentViewsToRecreate` im Rebuild bereits ein
  `CREATE VIEW` erhaelt, wuerde anschliessend nochmal von der
  `CreateView`-simpleOp gerendert; analog wuerde eine
  `current`-only View doppelt gedroppt. **H.3a absorbiert daher
  View/Trigger-Ops aus simpleOps, sobald ihre Table-Referenz im
  `rebuildTables`-Set ist** — die Drop/Recreate-Logik kommt
  ausschliesslich aus dem Plan. `ReplaceView` auf eine rebuilt-
  Table-Referenz wird in dieselben zwei Mengen gesplittet (drop-
  aus-before-state, recreate-aus-after-state). Die absorbierten
  Op-IDs werden in die `sourceOperationIds` des Rebuild-Plans
  aufgenommen, damit Attribution erhalten bleibt.

  Tests:
  - Renderer-Test: Rebuild mit View+Trigger in `current`+`desired`
    bringt beide nach Rebuild zurueck.
  - Renderer-Test: View nur in `current` (im selben Plan removed)
    wird gedroppt aber nicht recreated.
  - Renderer-Test: View nur in `desired` (im selben Plan added)
    wird recreated aber nicht gedroppt.
  - Planner-Test: View/Trigger-Op auf rebuilt-Table wird aus
    simpleOps absorbiert; sourceOperationIds enthaelt die Op-ID;
    Renderer emittiert nichts doppelt.

  **Bekannte Limitation (H.3a-Limitation, dokumentiert)**: Eine View,
  die *mehrere* rebuilt-Tables referenziert, wird vom Planner
  alphabetisch in **einen** Bucket absorbiert. Die anderen Buckets
  droppen ihre Table ohne paired DROP VIEW; SQLite tolerated das per
  lazy resolution, aber `PRAGMA foreign_key_check` kann temporaere
  Inkonsistenzen melden, wenn die View vor dem Rebuild-2 noch die
  alte Definition haelt. Multi-Bucket-Absorption mit emit-dedup
  (View landet in allen relevanten Buckets, View-Drop wird einmal
  beim alphabetisch ersten emittiert, View-Create einmal beim
  alphabetisch letzten) wuerde das sauberer loesen — als Folge-Slice
  H.3a.2 fuer 0.9.8+ verfolgt.

- [x] **H.3b** FK-Pragma-Restore ✅ (2026-05-11) —
  Renderer emittiert via `SqliteRebuildEmissionMode.EXECUTE` die
  runner-hook-Marker (`-- dmigrate:runner-hook=save-fk-state-before-
  pragma-off` vor `PRAGMA = OFF`, `-- dmigrate:runner-hook=restore-fk-
  state` statt des pauschalen `PRAGMA = ON`). STANDALONE-Default ist
  bit-identisch zu pre-H.3b.

  Runner-Layer-Vertrag (`RunnerHookHandler` in hexagon:application,
  geteilt zwischen `JdbcMigrationExecutor` und Test-Fixture
  `MigrationExecutorTestSupport`): parsed die Marker; bei
  `save-fk-state-before-pragma-off` liest `PRAGMA foreign_keys;` und
  cached den Wert in der per-Stream `State`; bei `restore-fk-state`
  emittiert `PRAGMA foreign_keys = <saved>;` (Default 1 wenn kein
  prior save — defensiv).

  **Rollback-Pfad-Restore (Order-of-Operations-Vertrag)**:
  `JdbcMigrationExecutor.runStreamOwnedTransaction` reicht im catch-
  Pfad einen `postRollback`-Callback an `rollbackTrace(...)`, der
  `tryRestoreFkStateAfterRollback(conn, hookState)` **nach** dem
  expliziten `ROLLBACK;`-Statement aufruft. Reihenfolge ist
  vertragsrelevant, nicht kosmetisch: SQLite ignoriert
  `PRAGMA foreign_keys = ...` innerhalb einer offenen Transaktion —
  Restore-vor-Rollback waere ein no-op und liesse die Rebuild-
  Zwischenwerte `OFF`/`ON` die Tx-Grenze ueberleben. Der
  `postRollback`-Hook feuert nur bei erfolgreichem Rollback
  (`rolledBack == true`); ein gescheiterter Rollback laesst die
  Connection in undefiniertem Zustand, weitere PRAGMA-Emissionen
  wuerden weiteren Drift riskieren. Restore selbst ist best-effort
  (sekundaere Exceptions werden geschluckt damit die primaere
  SQLException sichtbar bleibt). Test-Fixture
  `MigrationExecutorTestSupport.runStreamOwnedTransaction` spiegelt
  die Reihenfolge byte-identisch — Drift waere ein stiller
  Vertragsbruch in den Application-Layer-Smoke-Tests. §6.4 L992/L1007
  verlangt Save/Restore nach Commit/**Rollback** — vor dem Fix lief
  Restore in offener Tx und blieb wirkungslos.

  Caller-Wiring (`SchemaMigrateRunner`): wenn `request.execute = true`,
  wird `DdlGenerationOptions(executionMode = EXECUTE)` an
  `renderer.generateUp(...)` gereicht — der SQLite-Diff-Generator
  setzt `plan.emissionMode = EXECUTE` und der Renderer emittiert die
  Marker. Down-Rendering (`--generate-rollback`) bleibt STANDALONE,
  weil Rollback-Artefakte self-contained sind.

  `DdlGenerationOptions` bekommt das neue `executionMode:
  ExecutionMode = STANDALONE`-Feld; der `STANDALONE`-Default haelt
  bestehende Pfade (`--plan-only`, Rollback-Artefakt, alte Caller)
  bit-identisch.

  Tests: `JdbcMigrationExecutorH3bTest` deckt parseHook +
  executeOrApply ueber MockK + Embedded-SQLite-End-to-Ends: prior
  `OFF` wird auf `OFF` restored; prior `ON` bleibt `ON`; "two
  rebuilds in one stream" pinnt den State-Carrier-Vertrag;
  `assert-foreign-keys-clean` wirft bei FK-Violation; FK-State wird
  auch nach Rollback restored.

  **Per-statement fresh JDBC Statement**: der Hook-Pfad nutzt
  Statement-internes `executeQuery("PRAGMA foreign_keys;")`, das
  in xerial-sqlite das outer Statement nach ResultSet-Close
  finalisiert. Loop iteriert daher `conn.createStatement().use { ... }`
  pro statement — vermeidet "The prepared statement has been
  finalized" beim folgenden Loop-Schritt.

  Das pauschale `PRAGMA foreign_keys = ON;` am Ende der Sequence
  ist ungenuegend, wenn der prior State `OFF` war. PRAGMA-State
  ist verbindungs- und execute-time-abhaengig und kann nicht
  statisch in den Plan eingefroren werden.

  - **Execute-Pfad (`schema migrate --execute`)**: der Renderer
    emittiert das `PRAGMA foreign_keys = OFF;` mit einem
    Phase-Marker, der den d-migrate-Runner verpflichtet, vor der
    Ausfuehrung den aktuellen Wert via `PRAGMA foreign_keys;` zu
    lesen, fuer die Migration auf OFF zu setzen und nach
    Commit/Rollback wiederherzustellen. Das `PRAGMA foreign_keys
    = ON;` am Ende der Sequence entfaellt zugunsten eines
    Runner-Calls.

  - **Standalone-SQL-Pfad (`--plan-only` SQL-Artefakt)**: das
    erzeugte SQL muss self-contained ausfuehrbar bleiben — externe
    Runner werten Phase-Marker nicht aus. Daher behaelt der
    Standalone-Output das pauschale `PRAGMA foreign_keys = ON;`
    am Ende mit einem Header-Kommentar: "Standalone-Ausfuehrung
    laesst FK auf ON; prior `OFF`-State wird nicht restored —
    Runner-Pfad ist fuer Round-Trip-State-Compat erforderlich".
    Plan-Diagnostic, wenn das Schema-Modell signalisiert dass
    prior `OFF` zu erwarten ist (z.B. via Adapter-Hint).

  Tests:
  - Integration (`:test:integration-sqlite`): Runner-Vertrag mit
    `PRAGMA foreign_keys = OFF` als Initial-State validiert Restore
    auf `OFF` nach `--execute`.
  - Unit-Test: Standalone-SQL-Output enthaelt Header-Kommentar und
    `PRAGMA foreign_keys = ON;` am Ende.

- [x] **H.4** Vollstaendige Preflight-Liste ✅ (2026-05-11) —
  §6.4 Typentwurf L928-934 nennt **6** Preflight-Checks (die
  Ablauf-Beschreibung L985-990 konsolidiert `TABLE_EXISTS` und
  `DEPENDENCIES_KNOWN` in einem Bullet und nennt deshalb nur 5).
  Pro Check festgelegt ist die Ausfuehrungs-Form:

  | Check | Ausfuehrung | Begruendung |
  |---|---|---|
  | `TABLE_EXISTS` | Plan-time statisch | Diff hat `current.tables[name]`; Plan-Diagnostic, wenn fehlend. |
  | `TEMP_NAME_AVAILABLE` | Plan-time statisch via Catalog-Snapshot aus H.2 | H.2 prueft gegen `sqlite_master`-Snapshot; bei Kollision `__2`/`__3`-Fallback im Plan. |
  | `SOURCE_COLUMNS_EXIST` | Plan-time statisch | Check operiert NICHT ueber `source.columns.keys ∩ target.columns.keys` (das waere tautologisch — eine fehlende source-Spalte landet dann gar nicht erst in `preservedColumns`), sondern ueber `plan.mapping.preservedColumns.map { it.sourceColumn }` (strukturiertes Feld aus H.1a's `ColumnCopyMapping`, NICHT der opake `expressionSql`-String) des **bereits gebauten** Plans gegen die `oldTable.columns.keys`. Damit erkennt der Check Mapping-Bugs (z.B. ColumnCopyMapping-Eintraege mit ungueltigen sourceColumn-Verweisen) ohne SQL-Expression-Parsing. Plan-Diagnostic bei Verletzung. |
  | `DEPENDENCIES_KNOWN` | Plan-time statisch via F.6.b + G.2 | View/Trigger-Dependencies-Projektion ist Adapter-Output; F.6.b/G.2 blocken bei Unvollstaendigkeit unabhaengig. |
  | `ADDED_COLUMNS_FILLABLE` | Plan-time statisch | NOT NULL-Backfill + Cast-Matrix; heute schon im Mapping, nur Umverdrahtung auf das `preflight`-Feld. |
  | `FOREIGN_KEYS_CHECKABLE` | **Runner-Vertrag (execute-time)** | FK-Constraint-Integritaet ist live-DB-abhaengig; Renderer emittiert das `PRAGMA foreign_key_check;` (heute schon vorhanden) mit Phase-Marker, der den Runner verpflichtet, einen Violation-Output als Abbruch zu behandeln statt als Informational. |

  Statisch ausgewertete Checks landen als Plan-Diagnostics (nicht
  als Vorlauf-Statements), weil ein `SELECT 1 FROM <table>` keinen
  garantierten Abbruch bei Verletzung produziert. Runner-seitige
  Checks haben einen Phase-Marker und einen erwarteten Failure-
  Modus. Test pro Check: Positiv- und Negativ-Pfad.

---

## 10. Akzeptanzkriterien

Ein erster `DiffResult`-Milestone ist belastbar, wenn gilt:

- [x] `SchemaDiff` bleibt als Compare-Kernvertrag erhalten.
- [x] Der neue `DiffResult` enthaelt deterministisch sortierte Operationen.
- [x] Jede Operation hat Phase, ID, qualifizierte `DiffObjectRef`,
  richtungsabhaengige Up-/Down-Risiken, Reversibilitaet und den fuer
  Rendering/Rollback notwendigen Payload.
- [x] Dependency-Sortierung gewinnt gegen reine Phasenreihenfolge, insbesondere bei
  Drop-Operationen fuer Views, Trigger, Constraints, Indizes, Spalten und
  Tabellen.
- [x] Down-DDL wird in inverser, dependency-sicherer Reihenfolge gerendert; abhaengige
  Down-Operationen laufen vor ihren Voraussetzungen.
- [x] Reverse-generierte Schema-Metadaten werden vor Compare/Planning normalisiert,
  sodass synthetische `name`-/`version`-Werte keine Migrationsoperationen
  ausloesen.
- [x] View-Abhaengigkeiten auf Tabellen und Spalten sind fuer Drop-/Alter-Planung
  entweder belastbar bekannt oder die betroffene Migration wird mit Diagnose
  blockiert.
- [x] PostgreSQL splittet `ReplaceView` in explizite `DROP VIEW`-/
  `CREATE VIEW`-Schritte in dependency-sicherer Reihenfolge, wenn die
  View `dependencies.columns` referenziert, die in derselben Migration
  veraendert werden. (Phase G.3a ✅ — `DiffPlanner.
  splitReplaceViewsForColumnConflicts` — Dependency-Column-Konflikte.)
- [ ] PostgreSQL splittet `ReplaceView` zusaetzlich, wenn die
  sichtbare View-Spaltenform (Spaltenanzahl/-reihenfolge/-typen) sich
  aendert — auch ohne Tabellen-/Spalten-Konflikt am Unterbau, weil PG
  `CREATE OR REPLACE VIEW` nur bei identischer Visible-Signature
  akzeptiert. (Phase G.3b — Visible-Signature-Compatibility,
  Carve-Out auf 0.9.8+; braucht eine `ViewColumn`-Modellebene oder
  einen Pre-Render-Probe gegen das Live-Schema.)
- [x] PostgreSQL rendert einfache Enum-Custom-Types nur, wenn sie verlustfrei im
  Schema vorliegen und ihre Abhaengigkeiten zu Tabellen/Spalten eindeutig
  planbar sind; nicht triviale `ALTER TYPE`-Faelle werden diagnostiziert statt
  blind gerendert.
- [x] PostgreSQL rendert `AlterColumnType` im ersten Slice nur fuer getestete
  implizite Casts ohne `USING`; alle anderen Typaenderungen werden als
  `MANUAL_REQUIRED` oder `DIALECT_UNSUPPORTED_OPERATION` blockiert.
- [x] MySQL setzt fuer Live-DB-Operanden keine spaltenpraezise
  `VIEW_COLUMN_USAGE`-Quelle voraus; `DropColumn` und `AlterColumn*` unter
  Views werden ohne explizite column-level Dependencies blockiert. (F.6.b
  via `DiffPlanner.detectViewColumnDepsBlockers` →
  `VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS`-Diagnose.)
- [x] MySQL behandelt fehlende oder nicht belegbare Privilegien fuer
  `VIEW_TABLE_USAGE` als unvollstaendige Dependency-Projektion und
  blockiert betroffene View-Replacements oder spaltenveraendernde
  Operationen mit Diagnose. (Phase G.2 ✅ —
  `DependencyInfo.projectionComplete` + `MysqlRoutineReader.readViews`
  detektiert leere `VIEW_TABLE_USAGE`-Projektion;
  `DiffPlanner.detectIncompleteViewProjections` blockt mit
  `VIEW_DEPENDENCY_PROJECTION_INCOMPLETE`-Diagnose.)
- [ ] MySQL behandelt fehlende oder nicht belegbare Privilegien fuer
  `VIEW_ROUTINE_USAGE` analog als unvollstaendige Dependency-Projektion.
  (Carve-Out auf 0.9.8+. Heute: `MysqlMetadataQueries.listViewRoutineUsage`
  faengt fehlende Tabelle / Privilegien-Denied per try-catch und liefert
  `emptyMap()`. Eine View mit gefuellter `VIEW_TABLE_USAGE` aber
  versteckten Routine-Deps wird heute faelschlich als
  `projectionComplete=true` markiert. Erfordert ein zweites
  `routineProjectionComplete`-Flag oder eine Konsolidierung mit
  `projectionComplete` plus dialect-internem Tracking, ob die Routine-
  Projektion belegbar gelesen werden konnte.)
- [x] `CHECK`- und `EXCLUDE`-Constraint-Aenderungen werden nur als renderbare
  Operationen akzeptiert, wenn der Compare-Kern sie verlustfrei in `SchemaDiff`
  abbildet; andernfalls muss ein Vor-Normalisierungs-Detector betroffene Tabellen
  blockieren. Die Aenderung darf weder still verschwinden noch darf fuer dieselbe
  Tabelle SQL aus einem unvollstaendigen Diff entstehen.
- [x] SQLite-Rebuilds werden durch einen expliziten `DialectMigrationPlan` geplant:
  Spaltenmapping, temporaere Namen, Index-/Constraint-/Trigger-/View-
  Wiederaufbau, Preflight, Transaktionsgrenzen und Fehler-Rollback sind
  deterministisch beschrieben und getestet. (Phase H ✅ —
  `SqliteRebuildPlan`-Struct mit `sourceOperationIds`/`risk`/
  `ColumnCopyMapping`/`dependentViews{ToDrop,ToRecreate}`/
  `dependentTriggers{ToDrop,ToRecreate}`/`preflight` (H.1a) +
  `SqliteRebuildPlanner.planRebuild` + pure `SqliteRebuildRenderer.render`
  (H.1b) + `SqliteCatalogSnapshot` mit `__2`/`__3`-Fallback (H.2) +
  View/Trigger-Drop+Recreate mit simpleOps-Absorption (H.3a) +
  6-Punkte-Preflight-Liste mit per-Kind-Outcome (H.4).
  H.3b ✅: `JdbcMigrationExecutor.executeOrApplyHook` parsed die
  `dmigrate:runner-hook=…`-Marker; `SchemaMigrateRunner` setzt
  `DdlGenerationOptions.executionMode = EXECUTE` fuer den
  `--execute`-Pfad; `DdlGenerationOptions` ist um `ExecutionMode`
  erweitert (Default `STANDALONE`).)
- [x] SQLite-`AlterColumnType` nutzt automatische `CAST`-Ausdruecke nur mit
  expliziter, getesteter Quell-/Ziel-Cast-Matrix. Zielaffinitaet allein
  reicht nicht als Sicherheitsnachweis; sonst blockiert die Operation als
  `MANUAL_REQUIRED`. (Phase G.1 ✅ — `SqliteCastMatrix` mit Whitelist +
  `SQLITE_CAST_NOT_WHITELISTED`-Diagnose. **Hinweis**: §5/§6.4 fordern
  zusaetzlich Live-DB-Daten-Preflights vor jedem Cast; diese sind nach
  Plan-§G.1 explizit Carve-Out auf 0.9.8+, weil sie eine Live-DB
  voraussetzen und Phase G den Renderer-Layer hardened. Das narrowt
  diesen DoD auf "matrix only, no live-data preflights" — der DoD im
  vollen Wortlaut bleibt formal offen, siehe naechster Punkt.)
- [ ] SQLite-`AlterColumnType` fuehrt vor jedem whitelisted Cast einen
  Live-DB-Daten-Preflight aus, der nicht-konvertierbare Bestandsdaten
  erkennt und die Operation als `MANUAL_REQUIRED` blockiert.
  (Carve-Out auf 0.9.8+. Erfordert Connection zur Source-DB zum
  Plan-/Pre-Render-Zeitpunkt; passt nicht in den heutigen
  Datei-zu-Datei-Planning-Pfad und muesste als Pre-Render-Probe oder
  Runner-Vertrag modelliert werden.)
- [x] SQLite-Down-Rebuilds werden als eigene inverse Rebuild-Plaene erzeugt; ein
  blosses Vertauschen von `oldTable` und `newTable` reicht nicht als
  Down-Vertrag.
- [x] Destruktive Up-Operationen werden ohne Freigabe nicht als ausfuehrbares
  Up-SQL gerendert oder ausgefuehrt; `--plan-only` darf weiterhin einen
  Risiko-Report erzeugen.
- [x] Destruktive Down-Operationen aus einem reversiblen Up-Plan blockieren die
  Down-SQL-Erzeugung nicht, werden aber im Metadatenblock markiert und bei
  `schema rollback --execute` nur mit `--allow-destructive` ausgefuehrt.
- [x] `--generate-rollback` erzeugt keine falschen Down-Schritte fuer
  `NOT_REVERSIBLE` oder `MANUAL_REQUIRED`.
- [x] Operation-IDs sind deterministisch aus fachlicher Semantik abgeleitet und in
  Up-, Down- und Report-Artefakten referenzierbar.
- [x] Datei-zu-Datei-Planung ohne Live-Datenbank erzeugt Plan, Up-SQL, optional
  Down-SQL und optional einen Report, wenn `--dialect` gesetzt ist.
- [x] Datei-zu-Datei mit `--execute` endet mit Exit `2`.
- [x] `schema migrate --execute --dry-run` endet mit Exit `2`.
- [x] `schema rollback --execute --dry-run` endet mit Exit `2`.
- [x] Ein renderbarer Dry-Run ohne `--output` schreibt Up-SQL nach `stdout`; mit
  `--output` schreibt er Up-SQL nur in diese Datei.
- [x] `--report` erzeugt nur dann ein Report-Artefakt, wenn es explizit gesetzt
  ist; es gibt keinen impliziten Report-Sidecar.
- [x] `--plan-only --generate-rollback` erzeugt kein Down-SQL-Artefakt, berichtet
  aber Down-Renderbarkeit, Rollback-Risiken und Rollback-Blocker.
- [x] `--plan-only --generate-rollback --rollback-output ...` endet mit Exit `2`.
- [x] `schema migrate --execute` wendet Up-DDL gegen die Ziel-Datenbank an.
- [x] `schema migrate --execute` verlangt ein explizites `--report`; ohne Report-
  Pfad endet der Lauf mit Exit `2`.
- [x] Schlaegt `schema migrate --execute` nach Beginn der DDL-Ausfuehrung fehl,
  endet der Lauf mit Exit `5`, berichtet den Ausfuehrungszustand strukturiert
  und unterscheidet beweisbar zurueckgerollt von moeglicherweise partiell
  angewendet.
- [x] Ein durch fehlendes `--allow-destructive` blockierter Dry-Run ueberschreibt
  kein `--output`-Artefakt mit teilweise gerendertem SQL.
- [x] Up-SQL-, Down-SQL- und Report-Dateien werden erst nach vollstaendigem
  Rendering und erfolgreicher Blocker-Pruefung atomar finalisiert; bestehende
  Artefakte bleiben bei Fehlern unveraendert.
- [x] `schema migrate --generate-rollback --rollback-output ...` erzeugt ein zum
  Up-Plan passendes Down-SQL-Artefakt mit maschinenlesbarem
  `d-migrate`-Metadatenblock.
- [x] Der Down-SQL-Metadatenblock hat stabile Begrenzungskommentare, enthaelt ein
  kanonisches JSON-Objekt mit Pflichtfeldern und wird von `schema rollback`
  strikt geparst.
- [x] Der Down-SQL-Metadatenblock enthaelt `artifactHashAlgorithm` und
  `artifactHash`; der Rollback-Runner berechnet den Hash ueber kanonischen
  Header ohne `artifactHash` plus SQL-Body in Preview-, Validierungs- und
  Execute-Pfaden neu und lehnt veraenderte Artefakte ohne DB-Zugriff ab.
- [x] Der Metadatenblock nutzt dieselbe kanonische Fingerprint-Projektion wie
  Nach-Compare und Driftpruefung und enthaelt die verwendete
  Fingerprint-Algorithmus-ID.
- [x] Der Metadatenblock enthaelt `recovery` und `postUpVerified`; Recovery-
  Artefakte enthalten zusaetzlich nicht leere `allowedPostUpFingerprints`.
- [x] `schema migrate --generate-rollback` ohne `--rollback-output` endet in
  SQL-rendernden Laeufen mit Exit `2`; ausgenommen ist
  `--plan-only --generate-rollback`, weil dort kein Down-SQL-Artefakt entsteht.
- [x] `schema migrate --execute --generate-rollback --rollback-output ...` schreibt
  das finale Down-SQL-Artefakt erst nach erfolgreichem Up und Nach-Compare; der
  Metadatenblock enthaelt den beobachteten Post-Up-Fingerprint. (F.5.b)
- [x] Schlaegt `schema migrate --execute --generate-rollback` nach erfolgreichem Up,
  aber vor finalisiertem Rollback-Artefakt fehl, wird der Side Effect
  strukturiert ausgewiesen (`upExecuted = true`, `rollbackFinalized = false`).
  Ein bestehendes `--rollback-output` bleibt unveraendert. Ein markiertes
  Recovery-Rollback-Artefakt wird nur geschrieben, wenn kein beobachteter
  Post-Up-Fingerprint dem Soll-Fingerprint widerspricht. (F.5.c/e/f/g/h)
- [x] `schema rollback --source rollback.sql --target ... --execute` wendet
  nicht destruktives Down-SQL gegen die Ziel-Datenbank an.
- [x] `schema rollback --source rollback.sql --target ... --execute` prueft vor der
  Ausfuehrung, dass der aktuelle Zielzustand zum im Metadatenblock erwarteten
  Post-Up-/Soll-Fingerprint passt, und bricht bei Drift ab.
- [x] `schema rollback --source rollback.sql --target ... --execute` prueft vor der
  Ausfuehrung, dass der Ziel-Dialekt zum im Metadatenblock gespeicherten
  Dialekt passt, und bricht bei Abweichung mit `TARGET_DIALECT_MISMATCH` ab.
- [x] `schema rollback --source rollback.sql --target ... --execute` mit
  `--allow-destructive` wendet destruktives Down-SQL nur dann an, wenn der
  Metadatenblock diese Freigabe verlangt und der Nutzer sie explizit setzt.
- [x] Nach `migrate --execute` vergleicht ein Smoke den Zielzustand gegen das
  Soll-Schema. (PostgreSQL via F.2, MySQL via F.3, SQLite via F.4.)
- [x] Nach `schema rollback --execute` vergleicht ein Smoke den Zielzustand gegen
  das Ausgangsschema. (PostgreSQL via F.2, MySQL via F.3, SQLite via F.4.)
- [x] PostgreSQL, MySQL und SQLite haben jeweils mindestens einen echten
  Up-Smoke. (PostgreSQL ✅ via F.2, MySQL ✅ via F.3, SQLite ✅ via F.4.a/F.4.b.)
- [x] Mindestens PostgreSQL und SQLite haben je einen Up+Down-Smoke. Der
  SQLite-Smoke enthaelt mindestens einen echten Table-Rebuild.
  (PostgreSQL ✅ via F.2, SQLite ✅ via F.4.b RebuildTable-Pipeline.)
- [x] `schema compare`-Output bleibt rueckwaertskompatibel und serialisiert nicht
  ploetzlich das interne `DiffResult`.
- [x] 0.7.0-Tool-Exports bleiben full-state und unveraendert.

---

## 11. Entscheidungen fuer den ersten Slice

Der erste `DiffResult`-Slice soll den fuer `migrate up/down` benoetigten
Vertrag vollstaendig festlegen, ohne zusaetzliche Produktvarianten als
Nutzervertrag freizugeben.

Zur Vermeidung von Missverstaendnissen ist die Milestone-Grenze:

| Milestone | Enthalten | Nicht enthalten |
|---|---|---|
| 0.7.0 full-state | `schema generate`, Tool-Exports, full-state Rollback-Artefakte | diff-basierte `schema migrate`-Ausfuehrung |
| 0.9.7 erster `DiffResult`-Slice | Datei-zu-DB `schema migrate`, Datei-zu-Datei-Planung ohne Live-Datenbank, Up-DDL-Ausfuehrung fuer DB-Targets, Down-SQL-Erzeugung, `schema rollback` aus Down-SQL, Risiko-/Rollback-Blocker | gespeicherter `DiffResult` als Rollback-Input, Teil-Rollbacks, Rename-Mappings |
| Nach 0.9.7 separat zu entscheiden | noch kein verbindlicher Umfang | versionierte Plan-Artefakte, `schema rollback` aus Plan, optionale Partial-/Manual-Workflows, automatische Datenrekonstruktion nach destruktiven Operationen |

Damit ist `migrate up/down` verbindlicher Bestandteil von 0.9.7: Up wird aus
dem Diff geplant und fuer den Zieldialekt gerendert. Bei DB-Targets wird Up
gegen die Ziel-Datenbank ausgefuehrt; bei Datei-Targets endet der Pfad nach
Plan-/SQL-Erzeugung und optionaler Report-Erzeugung. Down wird als Rollback-SQL
aus demselben Plan erzeugt und kann ueber `schema rollback` gegen eine
Ziel-Datenbank ausgefuehrt werden.

Verbindlich fuer den ersten Slice:

- `schema migrate` unterstuetzt Datei-zu-DB:
  - `--source` ist das Soll-Schema als Datei.
  - `--target` ist die Ist-Datenbank.
- `schema migrate` unterstuetzt Datei-zu-Datei:
  - `--source` ist das Soll-Schema als Datei.
  - `--target` ist das aktuelle/Ist-Schema als Datei.
  - `--dialect` ist Pflicht.
  - `--execute` ist unzulaessig und endet mit Exit `2`.
  - Der Modus erzeugt Plan, Up-SQL, optional Down-SQL und optional Report ohne
    Introspection.
- `schema migrate` nutzt explizite Ausgabeziele:
  - ohne `--output` wird renderbares Up-SQL nach `stdout` geschrieben;
  - mit `--output` wird Up-SQL nur in diese Datei geschrieben;
  - `--report` erzeugt nur bei gesetztem Flag ein Report-Artefakt;
  - `--rollback-output` ist bei SQL-rendernden `--generate-rollback`-Laeufen der
    einzige Down-SQL-Pfad;
  - `--plan-only --generate-rollback` schreibt kein Down-SQL-Artefakt und
    berichtet nur die Rollback-Faehigkeit.
- `schema migrate --execute --dry-run` ist unzulaessig und endet mit Exit `2`.
- `schema migrate --execute` ist auditpflichtig und verlangt `--report`; ohne
  expliziten Report-Pfad endet der Lauf mit Exit `2`.
- Fehler nach Beginn der DDL-Ausfuehrung enden mit Exit `5` und strukturiertem
  Ausfuehrungsstatus. Ein vorbereiteter Down-Plan darf bei partieller oder
  unklarer Up-Ausfuehrung nicht als Rollback-Artefakt finalisiert werden.
- Reverse-generierte Schema-Metadaten werden vor Compare/Planning mit dem
  gemeinsamen Compare-Normalizer neutralisiert.
- `DiffResult` wird nicht als oeffentliches Input-Artefakt serialisiert.
  Stattdessen gibt es einen stabilen Report-Vertrag.
- `CHECK`- und `EXCLUDE`-Constraints sind kein renderbarer erster Slice, solange
  der Compare-Kern sie nicht verlustfrei diffen kann. Ohne Compare-Erweiterung
  muss ein Vor-Normalisierungs-Detector betroffene Tabellen blockieren, damit
  solche Aenderungen nicht still verschwinden.
- PostgreSQL-Views nutzen `CREATE OR REPLACE VIEW` nur fuer kompatible
  Replacements; dependency-bedingte oder signaturinkompatible Replacements
  werden als explizites Drop/Recreate geplant oder blockiert.
- PostgreSQL rendert im ersten Slice einfache Enum-Custom-Types, soweit sie
  verlustfrei diffbar und dependency-sicher planbar sind; nicht triviale
  `ALTER TYPE`-Faelle bleiben blockierende Diagnosen.
- PostgreSQL rendert `AlterColumnType` im ersten Slice nur fuer explizit
  getestete implizite Casts ohne `USING`; andere Typaenderungen bleiben
  blockierende Diagnosen oder manuelle Schritte.
- MySQL-View-Dependency-Daten gelten nur dann als belastbar, wenn der Adapter
  ausreichende Privilegien fuer die relevante `VIEW_TABLE_USAGE`-/
  `VIEW_ROUTINE_USAGE`-Projektion belegen kann.
- `--generate-rollback` ist streng:
  - enthaelt der Plan mindestens eine `NOT_REVERSIBLE`-Operation, bricht
    Rollback-Erzeugung mit Exit `8`,
    `primaryBlockedReason = ROLLBACK_NOT_POSSIBLE` und Blockern fuer die
    betroffenen Operationen ab.
  - enthaelt der Plan mindestens eine `MANUAL_REQUIRED`-Operation, bricht
    Rollback-Erzeugung mit Exit `8`,
    `primaryBlockedReason = MANUAL_ACTION_REQUIRED` und Blockern fuer die
    betroffenen Operationen ab.
  - Teil-Rollbacks mit Warn-/Manual-Blocks sind nicht Bestandteil dieses Plans.
- `schema rollback` unterstuetzt im ersten Slice die Ausfuehrung von
  gespeichertem Down-SQL aus `--rollback-output`.
- `schema rollback --execute --dry-run` ist unzulaessig und endet mit Exit `2`.
- Das Down-SQL enthaelt einen strikt parsebaren
  `d-migrate rollback-sql v1`-Metadatenblock mit kanonischem JSON,
  Pflichtfeldern, Fingerprint-Algorithmus-ID, Artifact-Hash ueber Header und
  Body,
  `recovery`, `postUpVerified` und ohne Secrets. Recovery-Artefakte enthalten
  zusaetzlich nicht leere `allowedPostUpFingerprints`.
- `schema rollback --execute` introspektiert vor der Ausfuehrung den aktuellen
  Zielzustand und vergleicht ihn mit dem im Down-SQL-Metadatenblock erwarteten
  Post-Up-/Soll-Fingerprint. Bei Abweichung endet der Lauf mit Exit `8` und
  `primaryBlockedReason = TARGET_STATE_MISMATCH`.
- `schema rollback --execute` prueft vor der Ausfuehrung den Ziel-Dialekt gegen
  den im Metadatenblock gespeicherten Dialekt. Bei Abweichung endet der Lauf mit
  Exit `8` und `primaryBlockedReason = TARGET_DIALECT_MISMATCH`.
- Destruktive Down-SQL-Ausfuehrung braucht ebenfalls explizit
  `--allow-destructive`; die Entscheidung basiert auf dem Metadatenblock im
  erzeugten Down-SQL-Artefakt.
- Wenn `schema migrate --execute --generate-rollback` nach ausgefuehrtem Up, aber
  vor finalem Rollback-Artefakt fehlschlaegt, bleibt ein bestehendes
  `--rollback-output` unveraendert. Der Runner schreibt nur dann ein markiertes
  Recovery-Rollback-Artefakt, wenn kein beobachteter Post-Up-Fingerprint dem
  Soll-Fingerprint widerspricht, und meldet den Side Effect strukturiert.
- Destruktive Up-DDL braucht explizit `--allow-destructive`.
  Ohne diesen Schalter endet ein SQL-rendernder Lauf mit Exit `8` und
  `primaryBlockedReason = DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION`; die
  Blocker-Liste weist alle betroffenen Operationen aus. Es wird kein
  Up-SQL-Artefakt geschrieben. `--plan-only` bleibt als reiner Risiko-Report
  erlaubt.
- Non-TTY-Betrieb nutzt keine interaktive Rueckfrage. Die Bestaetigung erfolgt
  ausschliesslich ueber explizite Flags wie `--allow-destructive`.
- Rename-Hints bleiben reine Diagnose. Es gibt kein automatisches Rename und
  keine `RenameTable`-/`RenameColumn`-Operation im ersten Slice.
- SQLite-Rebuild bleibt dialektspezifischer Folgeplan und wird nicht als
  Kernoperation im dialektneutralen `DiffResult` modelliert. Der
  SQLite-`DialectMigrationPlan` selbst ist aber verbindlicher Bestandteil
  dieses Plans und des ersten Slice.
- SQLite-Rebuilds muessen vollstaendig geplant werden:
  - deterministisches Spaltenmapping
  - deterministische temporaere Namen mit Kollisionsbehandlung
  - Wiederaufbau von Tabellenconstraints, Indizes, Triggern und bekannten
    abhaengigen Views
  - Preflight vor Ausfuehrung
  - transaktionale Ausfuehrung mit `BEGIN IMMEDIATE`, `COMMIT` und
    `ROLLBACK` bei Fehlern
  - Wiederherstellung des vorherigen `PRAGMA foreign_keys`-Zustands
  - Down-Rebuild nur, wenn keine verworfenen Daten oder manuellen Casts
    rekonstruiert werden muessen

Bewusst nicht Voraussetzung fuer den ersten Slice:

- versionierte `DiffResult`-Serialisierung als moeglicher Input fuer
  `schema rollback`
- `--allow-partial-rollback` oder ein aequivalenter Vertrag fuer bewusst
  unvollstaendige Down-Artefakte
- explizite Rename-Operationen mit Nutzer-Mapping

### 11.1 Phase-A-Entscheidungen (2026-05-09)

**CHECK-/EXCLUDE-Constraint-Diffbarkeit** — `TableComparator.normalize`
verwirft heute `CHECK`- und `EXCLUDE`-Constraints stillschweigend
(`hexagon/core/.../TableComparator.kt:182`). Das ist fuer `schema compare`
unkritisch (der Plan-Output ist deklariert "primitive-only"), wuerde aber
fuer `schema migrate` einen unvollstaendigen Plan rendern: ein Tabellen-
Update koennte einen `CHECK`-Constraint vergessen, weil der Comparator ihn
nie gesehen hat.

**Entscheidung**: Pre-Normalization-Detector statt Comparator-Erweiterung.

- Vor dem `DiffPlanner`-Lauf prueft eine kleine Detector-Funktion alle
  Tabellen aus `current` und `desired` auf vorhandene `CHECK`- oder
  `EXCLUDE`-Constraints.
- Werden welche gefunden, gibt `DiffPlanner` einen Blocker
  `CONSTRAINT_NOT_DIFFABLE` mit der Liste der betroffenen Tabellennamen
  zurueck. `schema migrate` endet mit Exit `8` und einem strukturierten
  Fehler, der die betroffenen Tabellen ausweist.
- `schema compare` bleibt unveraendert: der Diff-View darf weiterhin ohne
  CHECK-/EXCLUDE-Erkennung gerendert werden, weil sein Vertrag das so
  zulaesst (operandseitige `W`-Notes existieren bereits fuer aehnliche
  Faelle).

Begruendung: TableComparator-Erweiterung wuerde ein paralleles Diff-
Modell fuer arbitraery SQL-Expressions verlangen (Kanonisierung von
`age >= 0` vs. `0 <= age` vs. dialektspezifische Schreibweisen). Das ist
fuer 0.9.7 ueber Ziel; das stille Wegnormalisieren mit anschliessendem
Render ist aber inakzeptabel. Der Detector-Pfad markiert die Luecke
explizit, statt sie zu kaschieren — ein nachfolgender Milestone kann den
Detector durch echte Diffbarkeit ersetzen.

Implementierung verbleibt in Phase B/C; Phase A dokumentiert nur die
Entscheidung.

**`SchemaComparator.DiffResult<N, D>` umbenannt** — der private generische
Hilfstyp wurde zu `CollectionDiff<N, D>` umbenannt
(`hexagon/core/.../diff/SchemaComparator.kt`), damit der Name `DiffResult`
fuer den oeffentlichen Migrate-Vertrag in Phase B frei ist.

**Begriffsabgrenzung in `spec/design.md` §7.2 erfasst**: `SchemaDiff` /
`DiffView` / `DiffResult` / `MigrationDdlResult`.

**CLI-Vertrag in `spec/cli-spec.md` §6.1 erfasst**: `schema migrate` und
`schema rollback` mit Flag-Tabelle, Modus-Matrix, Ausgabe-Vertrag und
Exit-Code-Tabelle (neuer Code `8 = MIGRATION_BLOCKED`).

### 11.2 Phase-F.4-Carve-outs (2026-05-10)

F.4 hat zwei Heuristik-basierte Fixes verdrahtet, die im aktuellen
Renderer-Scope korrekt sind, aber an stillschweigende Annahmen
gekoppelt bleiben. Beide gehoeren als bewusste Carve-outs ins
Verzeichnis, damit F.5 / F.6 / Phase G sie nicht aus Versehen brechen.

**Carve-out F.4-1: BEGIN-Detection ueber SQL-Content statt
`transactionScope`-Feld.**

`JdbcMigrationExecutor.runAll` und `MigrationExecutorTestSupport.executeAgainstPool`
detektieren den "Stream-owned tx"-Pfad heute via
`statements.any { isExplicitBeginStatement(it.sql) }` — also durch
String-Praefix-Pruefung auf `BEGIN`. Korrekt, solange:

- nur `SqliteRebuildRenderer` explizite Tx-Marker emittiert, und
- kein anderer Diff-Renderer eine `MigrationDdlStatement.sql` mit
  `BEGIN ` als fuehrendem Token erzeugt (insbesondere keine
  Routinen-Bodies, die `BEGIN ... END`-Bloecke enthalten — heute
  durch `markUnsupported` in PG/MySQL geblockt, kommt aber in
  Phase G).

Sobald ein zukuenftiger Renderer eine Routinen-Body-Emission via
`MigrationDdlStatement` schaltet, wird der gesamte Stream
faelschlich als stream-owned klassifiziert und der Runner-managed
JDBC-Tx fuer PG/MySQL still abgeschaltet — Atomicity weg, ohne
Test-Signal. Der Renderer-Doc bei `SqliteRebuildRenderer.kt:60-62`
hat den richtigen Long-Term-Fix bereits benannt:

> A future `transactionScope` field on
> [dev.dmigrate.driver.migration.MigrationDdlStatement] is the
> long-term fix; until then the phase tag is the canonical signal.

**Entscheidung**: F.4 verdrahtet die Heuristik. Ein `transactionScope:
TransactionScope`-Feld auf `MigrationDdlStatement` (Werte etwa
`RUNNER_OWNED` / `STREAM_OWNED`) wird in einem nachfolgenden Slice
als Vertragsfeld nachgezogen, bevor ein Diff-Renderer
Routinen-Bodies emittiert.

**Carve-out F.4-2: Multi-Statement-Artefakt-Body via `\n\n`-Split
statt strukturierter Serialisierung.**

`SchemaRollbackRunner.splitArtefactBody` reverst die Joining-Logik
von `RollbackArtefactBuilder.canonicalBody`
(`stmts.joinToString("\n\n") { … }`) durch `split("\n\n")`. Korrekt,
solange:

- kein `MigrationDdlStatement.sql` selbst eine `\n\n`-Sequenz
  enthaelt (alle aktuellen Renderer emittieren Single-Line oder
  `,\n`-getrennte Multi-Line ohne Leerzeile), und
- der Joining-Vertrag im `RollbackArtefactBuilder` stabil bleibt.

Sobald ein Renderer einen multi-line View / Function / Trigger Body
mit absichtlicher Leerzeile rendert, mis-cuttet der Split — die
Rollback-Ausfuehrung waere stillschweigend unvollstaendig (genau das
Failure-Mode, dass F.4 bei Bug 2 aufgedeckt hat, nur in einer neuen
Form). Das aktuelle Artefakt-Format hat keinen Escape-Mechanismus.

**Entscheidung**: F.4 verdrahtet den Split. Eine strukturierte
Statement-Serialisierung im Artefakt (z.B. ein JSON-Array mit
Per-Statement-Metadaten statt nur SQL-Body) wird in einem
nachfolgenden Slice nachgezogen, idealerweise gemeinsam mit dem
`transactionScope`-Feld aus Carve-out F.4-1. Damit waere auch die
heute verworfene Per-Statement-`DiffPhase` round-trip-fest.

Beide Carve-outs sind als Future-Risiko, nicht als Sofort-Bug, in
diese Liste aufgenommen — der aktuelle Renderer-Scope deckt sie
nicht. Wer F.5 / F.6 / Phase G arbeitet, sollte beim Hinzufuegen
einer Statement-Quelle (insbesondere Routinen / Funktionen / Trigger
mit Body) erst diese beiden Carve-outs aufloesen.
