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
- Tool-Export-Artefakte aus 0.7.0 ersetzen
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
- Falls `DiffResult` spaeter als oeffentliches Artefakt serialisiert wird,
  kann diese Einbettung durch eine versionierte Projektion oder einen
  Fingerprint ersetzt werden. Das ist keine Entscheidung fuer den ersten
  internen Core-Vertrag.

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
current schema  -> materialisiertes Ist-Schema
desired schema  -> materialisiertes Soll-Schema
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
- `DIALECT_UNSUPPORTED_OPERATION`

Damit kann ein Runner unterscheiden, ob der Up-Plan wegen fehlendem
`--allow-destructive` blockiert ist, ob nur `--generate-rollback` wegen
`NOT_REVERSIBLE` scheitert, oder ob der Ziel-Dialekt eine Operation nicht
rendern kann.

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

Die CLI-Namen folgen dem bestehenden Stub in `spec/cli-spec.md`:
`--source` bezeichnet das Soll-Schema, `--target` die Ist-Datenbank. Intern
soll der Runner diese Werte sofort auf die eindeutigen Begriffe `desired` und
`current` abbilden. `SchemaComparator.compare(current, desired)` ist die
verbindliche Richtung fuer den Operationsplan.

Exit-Codes sollten sich an bestehenden Mustern orientieren:

| Exit | Bedeutung |
|---|---|
| `0` | Erfolg |
| `2` | ungueltige CLI-Argumente |
| `3` | Schema-Validierungsfehler |
| `4` | Verbindungsfehler |
| `7` | I/O-, Planungs- oder Renderfehler |
| `8` | Migration durch Risiko-, Rollback- oder Dialektblocker nicht renderbar |

Exit `0` gilt auch fuer erfolgreiche No-op-Laeufe, wenn kein Diff vorhanden
ist, und fuer erfolgreiche `--plan-only`-Laeufe. Das unterscheidet
`schema migrate` bewusst von `schema compare`, wo Exit `1` "Unterschiede
gefunden" bedeutet. Ein Plan mit Risiken bleibt erfolgreich, solange er nicht
als ausfuehrbare Migration gerendert werden soll oder durch fehlende Freigaben,
nicht moeglichen Rollback oder nicht renderbare Dialektoperationen blockiert
wird.

Exit `8` muss im strukturierten Fehler zwischen mindestens drei Faellen
unterscheiden:

- destruktive Up-Operation ohne `--allow-destructive`
- `--generate-rollback` angefordert, aber mindestens eine Operation ist
  `NOT_REVERSIBLE`
- Ziel-Dialekt kann eine geplante Operation nicht rendern

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
- Sequence-Migrationen, inklusive Sequence-Emulationen
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

## 11. Entscheidungen fuer den ersten Slice

Der erste `DiffResult`-Slice soll bewusst eng bleiben. Er muss den fachlichen
Kernvertrag stabilisieren, ohne gleichzeitig alle spaeteren Migrationsvarianten
als Nutzervertrag freizugeben.

Verbindlich fuer den ersten Slice:

- `schema migrate` unterstuetzt zunaechst Datei-zu-DB:
  - `--source` ist das Soll-Schema als Datei.
  - `--target` ist die Ist-Datenbank.
  - Datei-zu-Datei als reiner SQL-Plan bleibt spaeterer Scope.
- `DiffResult` wird nicht als oeffentliches Input-Artefakt serialisiert.
  Stattdessen gibt es einen stabilen Report-Vertrag.
- `--generate-rollback` ist streng:
  - enthaelt der Plan mindestens eine `NOT_REVERSIBLE`-Operation, bricht
    Rollback-Erzeugung mit Exit `8` und `blockedReason = ROLLBACK_NOT_POSSIBLE`
    ab.
  - Teil-Rollbacks mit Warn-/Manual-Blocks sind spaeterer Scope.
- Destruktive Up-DDL braucht explizit `--allow-destructive`.
  Ohne diesen Schalter endet Rendering mit Exit `8` und
  `blockedReason = DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION`.
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
