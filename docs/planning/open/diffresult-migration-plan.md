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

- `up`: Ist-Zustand aus Datenbank oder Schema-Datei lesen, gegen Soll-Schema
  diffen, Up-DDL planen, rendern und bei DB-Targets wahlweise ausfuehren bzw.
  als SQL ausgeben.
- `down`: aus demselben Plan ein Rollback-Artefakt erzeugen und dieses
  Rollback gegen die Ziel-Datenbank ausfuehren koennen.

Nicht Teil von 0.9.6 sind fortgeschrittene Rollback-Varianten wie
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
- `ALTER TABLE ALTER COLUMN TYPE`
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
Compare-Pfad weg; Phase A/B muss diese Luecke explizit schliessen, bevor
`AddConstraint`/`DropConstraint` fuer `CHECK` oder `EXCLUDE` als renderbar gelten
duerfen. Bis dahin werden solche Aenderungen entweder nicht geplant oder als
Planner-/Comparator-Gap diagnostiziert, aber nicht als SQL gerendert.

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
- `AlterColumnType` kopiert standardmaessig mit `CAST`, wenn der
  Typmapper eine sichere SQLite-Zielaffinitaet bestimmen kann. Ist kein
  sicherer Cast moeglich, wird die Operation `MANUAL_REQUIRED` und blockiert
  automatisches Up/Down-SQL.
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

## 7. CLI-Vertrag fuer 0.9.6

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
| `--rollback-output` | Bedingt | Pfad | Down-SQL-Ausgabe; Pflicht, wenn `--generate-rollback` gesetzt ist |
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

Wenn `--generate-rollback` gesetzt ist, ist `--rollback-output` im ersten Slice
Pflicht. Es gibt keinen impliziten Default-Pfad und kein Einbetten des
Down-SQL in das Up-SQL-Artefakt. Fehlt `--rollback-output`, endet der Lauf als
ungueltiger CLI-Aufruf mit Exit `2`.

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
- Wenn `--generate-rollback` gesetzt ist, ist `--rollback-output` immer der
  einzige Zielpfad fuer Down-SQL. Down-SQL wird nie nach `stdout` geschrieben.
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
  Runner schreibt stattdessen, soweit technisch moeglich, ein separates
  Recovery-Artefakt mit eindeutigem Suffix wie
  `.recovery.<timestamp>.rollback.sql` in dasselbe Zielverzeichnis. Dieses
  Artefakt muss im Metadatenblock als `recovery = true` markiert sein.
  `postUpVerified` richtet sich danach, ob bereits ein beobachteter
  Post-Up-Fingerprint aus erfolgreicher Nach-Introspection vorliegt. Das
  Artefakt darf von `schema rollback --execute` nur nach erneuter
  Zielzustandspruefung gegen `allowedPostUpFingerprints` akzeptiert werden.
  Kann auch dieses Recovery-Artefakt nicht geschrieben werden, endet der
  Lauf mit Exit `7` und einem strukturierten lokalen Fehler, der klar ausweist,
  dass Up bereits ausgefuehrt wurde und kein finalisiertes Rollback-Artefakt
  vorliegt.
- Falls das Dateisystem keine atomare Ersetzung im Zielverzeichnis erlaubt,
  endet der Lauf mit Exit `7`, bevor ein bestehendes Artefakt veraendert wird.
- Reports sind ebenfalls atomar zu schreiben. Bei einem terminalen
  Ausfuehrungsfehler darf der Fehlerreport finalisiert werden, solange er
  eindeutig `status = failed` und den Ausfuehrungszustand enthaelt. Up-SQL- und
  Down-SQL-Artefakte bleiben in diesem Fall unveraendert, sofern sie nicht schon
  vor der Ausfuehrung erfolgreich finalisiert wurden.

Fuer 0.9.6 muss `schema migrate` nicht nur SQL schreiben, sondern einen
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
7. Bei `--generate-rollback` die Down-Renderbarkeit aus demselben Plan pruefen
   und das Down-SQL vorbereiten. Ohne `--execute` wird es sofort mit dem
   erwarteten Soll-Fingerprint als Post-Up-Fingerprint nach
   `--rollback-output` geschrieben.
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

Auch fuer `schema rollback` sind `--execute` und `--dry-run` gegenseitig
exklusiv. Die Kombination endet als ungueltiger CLI-Aufruf mit Exit `2`.

Der Runner:

1. liest ausschliesslich das gespeicherte Down-SQL aus `--source`,
2. liest daraus den von `schema migrate --generate-rollback` erzeugten
   maschinenlesbaren `d-migrate`-Metadatenblock,
3. prueft in allen Modi den Metadatenblock, die Pflichtfelder und den
   `bodyHash` des SQL-Bodys strikt,
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
  `risk`, `createdByVersion`, `fingerprintAlgorithm`, `bodyHashAlgorithm`,
  `bodyHash`, `recovery` und `postUpVerified`.
- `bodyHash` bindet den Metadatenblock an den tatsaechlichen SQL-Body. Der Hash
  wird ueber die kanonischen Bytes aller Zeilen nach dem End-Begrenzer gebildet:
  Zeilenenden werden als LF normalisiert, der Generator schreibt genau eine
  finale Newline, und der Hash deckt Kommentare sowie ausfuehrbare Statements im
  Body ab. Der Metadatenblock selbst ist nicht Teil des Hashes.
- `bodyHashAlgorithm` benennt den Algorithmus und die kanonische Byte-Regel, zum
  Beispiel `sha256-lf-body-v1`. `schema rollback` muss den Hash in Preview-,
  Validierungs- und Execute-Pfaden neu berechnen, bevor es das Artefakt als
  gueltig behandelt. Bei `--execute` passiert diese Pruefung vor jeder
  Zielzustands- oder Dialektpruefung. Eine Abweichung macht das Artefakt
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
  `allowedPostUpFingerprints` als nicht leere Liste. Sie enthaelt den
  beobachteten Post-Up-Fingerprint, soweit verfuegbar, und kann den erwarteten
  Soll-Fingerprint enthalten, wenn die Nach-Introspection nicht erfolgreich
  abgeschlossen wurde. `schema rollback --execute` akzeptiert Recovery-
  Artefakte nur, wenn der aktuelle Zielzustand zu einem dieser Fingerprints
  passt.
- `risk` enthaelt mindestens `destructive`, `dataLossPossible`,
  `requiresManualConfirmation` und die betroffenen Operation-IDs.
- Der Parser ist strikt: fehlende Pflichtfelder, unbekannte `formatVersion`,
  syntaktisch ungueltiges JSON, unbekannte `bodyHashAlgorithm`, abweichender
  `bodyHash`, widerspruechliche Dialekt-/Fingerprint-Felder oder mehrere
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
- Soweit der vorbereitete Down-Plan renderbar war, wird ein separates
  Recovery-Rollback-Artefakt geschrieben, das den erwarteten Soll-Fingerprint
  und, falls verfuegbar, den beobachteten Post-Up-Fingerprint enthaelt. Das
  Artefakt ist nicht das normale `--rollback-output`, sondern ein eindeutig
  markiertes Recovery-Artefakt.
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

### 7.3 0.9.6 Up/Down-Artefaktvertrag

Ein erfolgreicher 0.9.6-Up/Down-Lauf besteht, soweit die jeweiligen
Ausgabeziele angefordert wurden, aus zusammenpassenden Artefakten:

- Up-SQL aus `--output` oder aus `stdout`, wenn kein `--output` gesetzt ist
  und der Lauf nicht `--execute`-only ist
- Down-SQL aus `--rollback-output`, wenn `--generate-rollback` gesetzt ist
- strukturierter Report aus `--report`

Bei `--execute` ist `--report` Pflicht. Der Report ist dann nicht nur ein
optionales Begleitartefakt, sondern das Audit-Artefakt des DB-seitigen
Schemawechsels.

`--rollback-output` ist bei `--generate-rollback` verbindlich. Der Runner darf
keinen impliziten Dateinamen ableiten und darf Down-SQL nicht in das
Up-SQL-Artefakt mischen.

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

- `spec/cli-spec.md` fuer `schema migrate`/`schema rollback` schaerfen
- globale Exit-Code-Tabelle in `spec/cli-spec.md` um den geplanten
  Migrations-Blocker-Code `8` ergaenzen oder einen bestehenden Code verbindlich
  wiederverwenden
- `spec/design.md` um `DiffResult` als Zwischenvertrag ergaenzen
- private `SchemaComparator.DiffResult<N, D>` umbenennen
- Comparator-Luecke fuer `CHECK`-/`EXCLUDE`-Constraints entscheiden:
  entweder `SchemaDiff`/`TableComparator` so erweitern, dass diese Constraints
  verlustfrei diffbar sind, oder sie explizit aus der ersten renderbaren
  Constraint-Matrix ausschliessen
- klare Begriffe festlegen:
  - `SchemaDiff` = struktureller Unterschied
  - `DiffView` = stabiler Compare-Output
  - `DiffResult` = migrationsfaehiger Operationsplan
  - `MigrationDdlResult` = gerenderte Up-/Down-DDL
- CLI-Ausgabeziele fuer `schema migrate`/`schema rollback` verbindlich
  spezifizieren: stdout-Fallback fuer Up-SQL, kein impliziter Report-Sidecar,
  `--rollback-output` als einziger Down-SQL-Pfad und
  `--execute --dry-run` als Exit `2`
- `--execute` als auditpflichtigen Pfad spezifizieren: ohne explizites
  `--report` Exit `2`, bei DDL-Ausfuehrungsfehlern Exit `5` mit strukturiertem
  Ausfuehrungsstatus statt Exit `7` oder `8`

### Phase B - Core-Vertrag

- `DiffResult`
- `DiffOperation`
- `DiffObjectRef`
- `DiffPhase`
- `DiffObjectType`
- `Reversibility`
- `OperationRisks`
- `OperationRisk`
- `DiffDiagnostic`
- stabile Operation-IDs
- deterministische ID-Bildung aus Operationstyp, Objektpfad und
  Payload-Fingerprint
- Operation-Payloads fuer Rendering und Rollback
- gemeinsamer Normalizer fuer reverse-generierte Schema-Metadaten, den
  `schema compare` und `schema migrate` nutzen
- kanonische Fingerprint-Projektion fuer Migrations-Reports,
  SQL-Metadatenblock, Nach-Compare und `schema rollback`-Driftpruefung
- planungsfaehiger Dependency-Vertrag fuer Views, mindestens Tabellen- und
  Spaltenabhaengigkeiten fuer `DropTable`, `DropColumn` und `AlterColumn*`
- Tests fuer leere Diffs, deterministische Sortierung, Dependency-Sortierung,
  inverse Down-Sortierung, Payload-Mapping, Up-/Down-Risiko-Mapping und
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
- `CHECK`-/`EXCLUDE`-Constraint-Aenderungen nur planen, wenn der Compare-Kern
  sie verlustfrei liefert; andernfalls blockierende Diagnose statt SQL-
  Rendering

### Phase D - Dialekt-DDL fuer erste Matrix

Erste realistische Matrix:

- PostgreSQL: Tabellen, Spalten, PK/FK/Unique-Constraints, Indizes, Views mit
  getrennter Strategie fuer kompatibles `CREATE OR REPLACE VIEW` und explizites
  Drop/Recreate, einfache Enum-Custom-Types ohne nicht triviale `ALTER TYPE`-
  Semantik
- MySQL: Tabellen, Spalten, PK/FK/Unique-Constraints, Indizes, Views nur mit
  explizit belegbaren table-level Dependencies; spaltenveraendernde Operationen
  unter Views nur mit expliziten column-level Dependencies und ausreichenden
  Introspection-Privilegien
- SQLite: Tabellen, Spalten, Indizes, einfache Views, vollstaendige
  RebuildTable-Planung fuer SQLite-pflichtige Table-Rebuilds

`CHECK`- und `EXCLUDE`-Constraints sind in dieser Matrix nur dann enthalten,
wenn Phase A/B den Compare-Kern so erweitert, dass diese Aenderungen als
verlustfreier `SchemaDiff` vorliegen. Ohne diese Erweiterung gehoeren sie nicht
zur renderbaren ersten Matrix.

Zusaetzlich fuer SQLite verbindlich:

- `DialectMigrationPlan` aus `DiffResult` ableiten
- Rebuild-Gruppierung pro Tabelle implementieren
- deterministische Temp-Namen und Kollisionssuffixe erzeugen
- Spaltenmapping inklusive `CAST`, Default-/NULL-Fill und Blocker fuer
  nicht automatisch fuellbare `NOT NULL`-Spalten rendern
- alte/neue Tabellenconstraints in `CREATE TABLE` korrekt abbilden
- Indizes, Trigger und bekannte abhaengige Views nach dem Rebuild wieder
  erzeugen
- `PRAGMA foreign_keys`-Handling, `foreign_key_check`, `BEGIN IMMEDIATE`,
  `COMMIT` und `ROLLBACK` als Runner-Vertrag abbilden
- Down-Rebuild aus reversiblem Up-Rebuild als eigenen inversen Rebuild-Plan
  erzeugen, richtungsabhaengige Felder neu bestimmen und bei Datenverlust- oder
  Manual-Faellen blockieren

Nicht in der ersten Matrix:

- vollstaendige Routine-Migration
- vollstaendige Trigger-Migration
- Sequence-Migrationen, inklusive Sequence-Emulationen
- automatische Daten-Transformationen

### Phase E - CLI-Runner

- `SchemaMigrateRunner`
- Operand-Aufloesung fuer Soll-Schema, Ist-Datenbank und Ist-Schema-Datei
- Reverse des Ist-Zustands bei DB-Target
- Normalisierung reverse-generierter Schema-Metadaten vor Compare/Planning
- Datei-zu-Datei-Planung ohne Live-Datenbank
- `--dialect`-Pflicht und Dialektvalidierung fuer Datei-Targets
- Compare
- Planner
- Dialekt-DDL
- `--plan-only`
- `--allow-destructive`
- `--generate-rollback`
- `--rollback-output` als Pflichtausgabe fuer `--generate-rollback`
- `--execute`
- `--execute` ohne `--report` als Exit `2` ablehnen
- `--dry-run` als Default ohne Ausfuehrung
- `--execute --dry-run` fuer `schema migrate` und `schema rollback` als
  Exit `2` ablehnen
- `--execute` mit Datei-Target als Exit `2` ablehnen
- Up-DDL gegen `--target` ausfuehren, wenn `--execute` gesetzt ist
- Dialektbezogene Transaktions-/Autocommit-Semantik vor Ausfuehrung bestimmen
  und im Report ausweisen
- DDL-Ausfuehrungsfehler nach Start von `--execute` als Exit `5` abbilden,
  inklusive `executionStarted`, `executionCompleted`, `statementsAttempted`,
  `lastStatementOperationIds`, `transactionRolledBack` und
  `sideEffectsPossible`
- Down-SQL-Artefakt erzeugen, wenn `--generate-rollback` gesetzt ist; bei
  `--execute` erst nach erfolgreichem Nach-Compare final schreiben
- Recovery-Fall fuer `--execute --generate-rollback` nach bereits
  ausgefuehrtem Up abbilden: Nach-Introspection-/Nach-Compare-/
  Finalisierungsfehler duerfen bestehende Rollback-Pfade nicht ueberschreiben
  und muessen, soweit moeglich, ein markiertes Recovery-Rollback-Artefakt plus
  strukturierten Fehler mit `upExecuted = true` erzeugen
- gemeinsamer Artefakt-Writer fuer Up-SQL, Down-SQL und Reports mit temporaerer
  Datei im Zielverzeichnis und atomarer Finalisierung
- `SchemaRollbackRunner` fuer Down-SQL-Ausfuehrung
- strikter Parser fuer den `d-migrate rollback-sql v1`-Metadatenblock:
  Begrenzungskommentare, kanonisches JSON, Pflichtfelder,
  Fingerprint-Algorithmus, `bodyHashAlgorithm`, `bodyHash`, SQL-Body-
  Integritaetspruefung und Secret-Scrubbing
- Zielzustands-Pruefung vor `schema rollback --execute`
- Zieldialekt-Pruefung vor `schema rollback --execute`; Abweichungen vom
  Metadatenblock enden mit `TARGET_DIALECT_MISMATCH`
- Rollback-SQL gegen `--target` ausfuehren, wenn `schema rollback --execute`
  genutzt wird
- `--allow-destructive` auch fuer destruktive Down-SQL-Ausfuehrung auswerten
- SQLite-Rebuild-Schritte als unteilbare Ausfuehrungseinheit behandeln und bei
  Fehlern abbrechen, rollbacken und im Report die letzte Rebuild-Phase
  ausweisen
- Nach-Compare nach Up-Ausfuehrung gegen das Soll-Schema
- Report-Ausgabe
- sauberes Exit-Code-Mapping

### Phase F - Tests und Smokes

- Core-Planner-Tests
- DDL-Golden-Tests pro Dialekt
- SQLite-Rebuild-Golden-Tests fuer Temp-Namen, Spaltenmapping,
  Index-/Constraint-/Trigger-/View-Wiederaufbau und Down-Rebuild
- CLI-Tests fuer Flags, Exit-Codes und Reports, inklusive Datei-zu-Datei,
  `--dialect`-Pflicht, `--execute`-Ablehnung bei Datei-Target,
  `schema migrate --execute --dry-run` und
  `schema rollback --execute --dry-run` als Exit `2`,
  `schema migrate --execute` ohne `--report` als Exit `2`,
  stdout-/Datei-Ausgabeziele und fehlende implizite Report-Sidecars
- Ausfuehrungsfehler-Tests fuer `schema migrate --execute`: Fehler nach Beginn
  der DDL-Ausfuehrung endet mit Exit `5`, enthaelt strukturierten
  Ausfuehrungsstatus, ueberschreibt keine unfertigen SQL-Artefakte und
  unterscheidet beweisbar zurueckgerollt von moeglicherweise partiell angewendet
- Artefakt-Writer-Tests fuer atomare Finalisierung und unveraenderte
  bestehende Zielpfade bei Planungs-, Render-, Blocker- und
  Ausfuehrungsfehlern
- Recovery-Tests fuer `schema migrate --execute --generate-rollback`, bei denen
  Up erfolgreich war, aber Nach-Compare oder finale Rollback-Artefakt-
  Finalisierung fehlschlaegt
- Metadatenblock-Tests fuer gueltige Down-SQL-Artefakte, fehlende Bloecke,
  doppelte Bloecke, unbekannte Formatversionen, fehlende Pflichtfelder,
  ungueltiges JSON, Fingerprint-Algorithmus-Mismatch, Body-Hash-Mismatch,
  Dialekt-Mismatch, Recovery-Felder (`recovery`, `postUpVerified`,
  `allowedPostUpFingerprints`) und Secret-Scrubbing
- MySQL-Dependency-Tests fuer fehlende oder unvollstaendige View-Dependency-
  Privilegien; betroffene View-Replacements und spaltenveraendernde Operationen
  muessen blockieren
- Docker-Smokes:
  - PostgreSQL Up
  - PostgreSQL Up + Down
  - MySQL Up
  - MySQL Up + Down fuer die erste reversible Operationsmatrix
  - SQLite Up
  - SQLite Up + Down fuer direkt reversible Operationen ohne Rebuild
  - SQLite Up + Down fuer mindestens einen echten Table-Rebuild
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
- Jede Operation hat Phase, ID, qualifizierte `DiffObjectRef`,
  richtungsabhaengige Up-/Down-Risiken, Reversibilitaet und den fuer
  Rendering/Rollback notwendigen Payload.
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
- PostgreSQL rendert `ReplaceView` nur dann als `CREATE OR REPLACE VIEW`, wenn
  die View-Aenderung kompatibel ist. Muss eine View wegen abhaengiger Tabellen-/
  Spaltenaenderungen oder inkompatibler sichtbarer Spaltenform entfernt werden,
  entstehen explizite `DROP VIEW`-/`CREATE VIEW`-Schritte in dependency-sicherer
  Reihenfolge.
- PostgreSQL rendert einfache Enum-Custom-Types nur, wenn sie verlustfrei im
  Schema vorliegen und ihre Abhaengigkeiten zu Tabellen/Spalten eindeutig
  planbar sind; nicht triviale `ALTER TYPE`-Faelle werden diagnostiziert statt
  blind gerendert.
- MySQL setzt fuer Live-DB-Operanden keine spaltenpraezise
  `VIEW_COLUMN_USAGE`-Quelle voraus; `DropColumn` und `AlterColumn*` unter
  Views werden ohne explizite column-level Dependencies blockiert.
- MySQL behandelt fehlende oder nicht belegbare Privilegien fuer
  `VIEW_TABLE_USAGE`/`VIEW_ROUTINE_USAGE` als unvollstaendige Dependency-
  Projektion und blockiert betroffene View-Replacements oder
  spaltenveraendernde Operationen mit Diagnose.
- `CHECK`- und `EXCLUDE`-Constraint-Aenderungen werden nur als renderbare
  Operationen akzeptiert, wenn der Compare-Kern sie verlustfrei in `SchemaDiff`
  abbildet; andernfalls gelten sie als diagnostizierter Gap und duerfen nicht
  still als SQL gerendert werden.
- SQLite-Rebuilds werden durch einen expliziten `DialectMigrationPlan` geplant:
  Spaltenmapping, temporaere Namen, Index-/Constraint-/Trigger-/View-
  Wiederaufbau, Preflight, Transaktionsgrenzen und Fehler-Rollback sind
  deterministisch beschrieben und getestet.
- SQLite-Down-Rebuilds werden als eigene inverse Rebuild-Plaene erzeugt; ein
  blosses Vertauschen von `oldTable` und `newTable` reicht nicht als
  Down-Vertrag.
- Destruktive Up-Operationen werden ohne Freigabe nicht als ausfuehrbares
  Up-SQL gerendert oder ausgefuehrt; `--plan-only` darf weiterhin einen
  Risiko-Report erzeugen.
- Destruktive Down-Operationen aus einem reversiblen Up-Plan blockieren die
  Down-SQL-Erzeugung nicht, werden aber im Metadatenblock markiert und bei
  `schema rollback --execute` nur mit `--allow-destructive` ausgefuehrt.
- `--generate-rollback` erzeugt keine falschen Down-Schritte fuer
  `NOT_REVERSIBLE` oder `MANUAL_REQUIRED`.
- Operation-IDs sind deterministisch aus fachlicher Semantik abgeleitet und in
  Up-, Down- und Report-Artefakten referenzierbar.
- Datei-zu-Datei-Planung ohne Live-Datenbank erzeugt Plan, Up-SQL, optional
  Down-SQL und optional einen Report, wenn `--dialect` gesetzt ist.
- Datei-zu-Datei mit `--execute` endet mit Exit `2`.
- `schema migrate --execute --dry-run` endet mit Exit `2`.
- `schema rollback --execute --dry-run` endet mit Exit `2`.
- Ein renderbarer Dry-Run ohne `--output` schreibt Up-SQL nach `stdout`; mit
  `--output` schreibt er Up-SQL nur in diese Datei.
- `--report` erzeugt nur dann ein Report-Artefakt, wenn es explizit gesetzt
  ist; es gibt keinen impliziten Report-Sidecar.
- `schema migrate --execute` wendet Up-DDL gegen die Ziel-Datenbank an.
- `schema migrate --execute` verlangt ein explizites `--report`; ohne Report-
  Pfad endet der Lauf mit Exit `2`.
- Schlaegt `schema migrate --execute` nach Beginn der DDL-Ausfuehrung fehl,
  endet der Lauf mit Exit `5`, berichtet den Ausfuehrungszustand strukturiert
  und unterscheidet beweisbar zurueckgerollt von moeglicherweise partiell
  angewendet.
- Ein durch fehlendes `--allow-destructive` blockierter Dry-Run ueberschreibt
  kein `--output`-Artefakt mit teilweise gerendertem SQL.
- Up-SQL-, Down-SQL- und Report-Dateien werden erst nach vollstaendigem
  Rendering und erfolgreicher Blocker-Pruefung atomar finalisiert; bestehende
  Artefakte bleiben bei Fehlern unveraendert.
- `schema migrate --generate-rollback --rollback-output ...` erzeugt ein zum
  Up-Plan passendes Down-SQL-Artefakt mit maschinenlesbarem
  `d-migrate`-Metadatenblock.
- Der Down-SQL-Metadatenblock hat stabile Begrenzungskommentare, enthaelt ein
  kanonisches JSON-Objekt mit Pflichtfeldern und wird von `schema rollback`
  strikt geparst.
- Der Down-SQL-Metadatenblock enthaelt `bodyHashAlgorithm` und `bodyHash`; der
  Rollback-Runner berechnet den Hash des SQL-Bodys in Preview-, Validierungs-
  und Execute-Pfaden neu und lehnt veraenderte Artefakte ohne DB-Zugriff ab.
- Der Metadatenblock nutzt dieselbe kanonische Fingerprint-Projektion wie
  Nach-Compare und Driftpruefung und enthaelt die verwendete
  Fingerprint-Algorithmus-ID.
- Der Metadatenblock enthaelt `recovery` und `postUpVerified`; Recovery-
  Artefakte enthalten zusaetzlich nicht leere `allowedPostUpFingerprints`.
- `schema migrate --generate-rollback` ohne `--rollback-output` endet mit
  Exit `2`.
- `schema migrate --execute --generate-rollback --rollback-output ...` schreibt
  das finale Down-SQL-Artefakt erst nach erfolgreichem Up und Nach-Compare; der
  Metadatenblock enthaelt den beobachteten Post-Up-Fingerprint.
- Schlaegt `schema migrate --execute --generate-rollback` nach erfolgreichem Up,
  aber vor finalisiertem Rollback-Artefakt fehl, wird der Side Effect
  strukturiert ausgewiesen (`upExecuted = true`, `rollbackFinalized = false`).
  Ein bestehendes `--rollback-output` bleibt unveraendert; soweit moeglich wird
  ein markiertes Recovery-Rollback-Artefakt geschrieben.
- `schema rollback --source rollback.sql --target ... --execute` wendet
  nicht destruktives Down-SQL gegen die Ziel-Datenbank an.
- `schema rollback --source rollback.sql --target ... --execute` prueft vor der
  Ausfuehrung, dass der aktuelle Zielzustand zum im Metadatenblock erwarteten
  Post-Up-/Soll-Fingerprint passt, und bricht bei Drift ab.
- `schema rollback --source rollback.sql --target ... --execute` prueft vor der
  Ausfuehrung, dass der Ziel-Dialekt zum im Metadatenblock gespeicherten
  Dialekt passt, und bricht bei Abweichung mit `TARGET_DIALECT_MISMATCH` ab.
- `schema rollback --source rollback.sql --target ... --execute` mit
  `--allow-destructive` wendet destruktives Down-SQL nur dann an, wenn der
  Metadatenblock diese Freigabe verlangt und der Nutzer sie explizit setzt.
- Nach `migrate --execute` vergleicht ein Smoke den Zielzustand gegen das
  Soll-Schema.
- Nach `schema rollback --execute` vergleicht ein Smoke den Zielzustand gegen
  das Ausgangsschema.
- PostgreSQL, MySQL und SQLite haben jeweils mindestens einen echten
  Up-Smoke.
- Mindestens PostgreSQL und SQLite haben je einen Up+Down-Smoke. Der
  SQLite-Smoke enthaelt mindestens einen echten Table-Rebuild.
- `schema compare`-Output bleibt rueckwaertskompatibel und serialisiert nicht
  ploetzlich das interne `DiffResult`.
- 0.7.0-Tool-Exports bleiben full-state und unveraendert.

---

## 11. Entscheidungen fuer den ersten Slice

Der erste `DiffResult`-Slice soll den fuer `migrate up/down` benoetigten
Vertrag vollstaendig festlegen, ohne zusaetzliche Produktvarianten als
Nutzervertrag freizugeben.

Zur Vermeidung von Missverstaendnissen ist die Milestone-Grenze:

| Milestone | Enthalten | Nicht enthalten |
|---|---|---|
| 0.7.0 full-state | `schema generate`, Tool-Exports, full-state Rollback-Artefakte | diff-basierte `schema migrate`-Ausfuehrung |
| 0.9.6 erster `DiffResult`-Slice | Datei-zu-DB `schema migrate`, Datei-zu-Datei-Planung ohne Live-Datenbank, Up-DDL-Ausfuehrung fuer DB-Targets, Down-SQL-Erzeugung, `schema rollback` aus Down-SQL, Risiko-/Rollback-Blocker | gespeicherter `DiffResult` als Rollback-Input, Teil-Rollbacks, Rename-Mappings |
| Nach 0.9.6 separat zu entscheiden | noch kein verbindlicher Umfang | versionierte Plan-Artefakte, `schema rollback` aus Plan, optionale Partial-/Manual-Workflows, automatische Datenrekonstruktion nach destruktiven Operationen |

Damit ist `migrate up/down` verbindlicher Bestandteil von 0.9.6: Up wird aus
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
  - `--rollback-output` ist bei `--generate-rollback` der einzige Down-SQL-Pfad.
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
  der Compare-Kern sie nicht verlustfrei diffen kann.
- PostgreSQL-Views nutzen `CREATE OR REPLACE VIEW` nur fuer kompatible
  Replacements; dependency-bedingte oder signaturinkompatible Replacements
  werden als explizites Drop/Recreate geplant oder blockiert.
- PostgreSQL rendert im ersten Slice einfache Enum-Custom-Types, soweit sie
  verlustfrei diffbar und dependency-sicher planbar sind; nicht triviale
  `ALTER TYPE`-Faelle bleiben blockierende Diagnosen.
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
  Pflichtfeldern, Fingerprint-Algorithmus-ID, SQL-Body-Hash,
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
  `--rollback-output` unveraendert. Der Runner schreibt soweit moeglich ein
  markiertes Recovery-Rollback-Artefakt und meldet den Side Effect strukturiert.
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
