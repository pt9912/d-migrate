# Implementierungsplan: 0.9.7 — Materialized-View-Migrationsvertrag (D.3b-Vollscheibe)

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: D.3b-Carve-out (Plan-2 §8 D.3 / D.3b)
> **Status**: open (geplant, noch nicht gestartet)
> **Vorbedingung**: D.3a ✅ (Materialized-View-Guard), D.1 ✅ (PG-View-Signaturen),
>                 D.2 ✅ (MySQL-View-Dependency-Projektion), Workstream G ✅
>                 (struktureller Statement-Vertrag)
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md`
>             §8 D.3 / D.3a / D.3b

---

## 1. Ziel

D.3a (Status: implementiert 2026-05-12) hat Materialized-View-Diff-
Operationen vor SQL-Render mit `MATERIALIZED_VIEW_DIFF_UNSUPPORTED`
blockiert. Der **konservative D.3b-Report-Vertrag** (ebenfalls
2026-05-12) hat sie als Top-Level-`materializedViews`-Block mit
`status=BLOCKED_UNTIL_REFRESH_STALENESS_CONTRACT`,
`stalenessAfterUp=UNKNOWN_BLOCKED`,
`locking=UNKNOWN_REQUIRES_MANUAL_CONTRACT` und
`refreshSteps=[BLOCKED_REFRESH_CONTRACT_REQUIRED]` maschinenlesbar
sichtbar gemacht, ohne automatisches Rendering oder
CONCURRENTLY-Raten.

Die **D.3b-Vollscheibe** löst diesen Blocker-Vertrag auf:

1. PostgreSQL Materialized Views bekommen eigene Op-Klassen
   (`CreateMaterializedView`, `ReplaceMaterializedView`,
   `DropMaterializedView`) und werden im Diff-Pfad gerendert
   (`CREATE / DROP MATERIALIZED VIEW`, Replace = `DROP + CREATE`).
2. Der Report ersetzt die generischen `BLOCKED_*`-Statuswerte durch
   konkrete Werte für `status`, `stalenessAfterUp`, `refreshSteps`,
   `locking` und `rollback`.
3. MySQL und SQLite bleiben strukturell blockierend (keine native
   MV-Unterstützung) — aber mit präzisem
   `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`-Diagnostic statt des
   D.3a-Sammelblockers.
4. Der Dependency-Graph integriert MVs als Knoten, sodass das
   Droppen einer Tabelle mit abhängiger MV nicht still in den
   Render-Fehler läuft, sondern vor Render blockiert (oder als
   Cascade-Drop dokumentiert wird).

`REFRESH MATERIALIZED VIEW CONCURRENTLY` und die zugehörige
Unique-Index-Voraussetzung sind **bewusst aus Scope** und bleiben in
D.3b als harte OOS-Blocker explizit dokumentiert
(`BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED`) — **ausschließlich dann**, wenn der
planungsseitige Refresh-Vertrag `CONCURRENTLY` explizit fordert.
Ein solcher Vertrag wird **nicht** aus `ViewDefinition.refresh` abgeleitet.
Sie benötigen eine Atomic-Swap-Strategie via `tempName`, die strukturell mit dem
SQLite-Rebuild-Vertrag verwandt ist und nicht in den stabilen Kern-Schnitt
von D.3b passt.

## 2. Scope

### In Scope

- **Eigene Op-Klassen** in `hexagon:core`
  (`DiffOperation.{CreateMaterializedView, ReplaceMaterializedView,
  DropMaterializedView}`). Diese ergänzen — nicht ersetzen — die
  bestehenden `CreateView`/`ReplaceView`/`DropView`-Klassen, die
  weiterhin für nicht-materialisierte Views verwendet werden.
- **`DiffPlanner`-Routing**: View-Operationen mit
  `materialized` in genau einem Diff-Zustand explizit gesetzt auf `true`
  werden zu den neuen MV-Op-Klassen umgeleitet, bevor sie den Renderer
  erreichen.
  Bei echter `materialized`-Werte-Umwandlung (`false`↔`true`) wird
  immer `BLOCKED_CONVERSION_UNSUPPORTED` vor der Erstellung konkreter
  MV-Ops gesetzt.
  Ist auf genau einer Seite `materialized=true` und auf der anderen Seite
  `null` oder unklar (`null`-Asymmetrie), blockt der Planer mit
  `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`.
  `materialized = null` auf beiden Seiten wird als `false` (nicht
  materialisiert) interpretiert und fällt in den regulären View-Pfad.
- Der bisherige D.3a-Guard im Renderer bleibt als Defense-in-Depth.
- **PostgreSQL-Renderer**: vollständige Pipeline
  - `CreateMaterializedView` → `CREATE MATERIALIZED VIEW <name> AS <query>;`
  - `DropMaterializedView` → `DROP MATERIALIZED VIEW <name>;`
  - `ReplaceMaterializedView` → `DROP MATERIALIZED VIEW <name>;
    CREATE MATERIALIZED VIEW <name> AS <afterQuery>;`
  - Down jeweils gespiegelt; `Replace`-Down nur bei bekanntem
    `before.query`, sonst Block.
- **MySQL- und SQLite-Renderer**: separater Block-Diagnostic
  `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT` mit konkreter
  Begründung („Dialekt hat keine native Materialized-View-
  Unterstützung; Emulation ist nicht in Scope von D.3b").
- **Report-Vertrag**: `materializedViews[]` führt jetzt konkrete
  Werte (siehe §6.4) statt der generischen `BLOCKED_*`-Platzhalter.
- **Dependency-Graph-Integration**: `RoutineDependencyAnalyzer` (oder
  analoge Quelle für MV-Edges) extrahiert MV → Table/View/Routine
  aus `ViewDefinition.dependencies`. `DependencyGuardEvaluator`
  nutzt diese Edges für MV-Op-Bewertung.
- **Tests**: pro Sub-Slice Positiv-Pfad + Block-Pfad pro Dialekt,
  Up + Down getrennt, Dependency-Block.

### Aus Scope

- **`REFRESH MATERIALIZED VIEW CONCURRENTLY`** + `uniqueIndexes`-
  Modellfeld + Atomic-Swap-Strategie — harte Blockierung in D.3b.
  Der Blocker wird aktiv, wenn ein expliziter Refresh-Vertrags-Input
  `CONCURRENTLY` verlangt.
  Kein separater Slice in diesem Plan; es gibt nur einen
  deterministischen Block-Diagnostic-Pfad mit
  `status=BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED`.
  Begründung:
  CONCURRENTLY ist relevant für In-Place-Refresh ohne `DROP`, was
  einen Multi-Step-Atomic-Swap mit `tempName`-Auflösung verlangt
  (analog SQLite-Rebuild). Das ist eigene Komplexität und passt
  nicht in einen D.3b-Slice, der „MVs werden überhaupt erst
  diff-basiert renderbar" liefern soll.
- **MySQL- und SQLite-Emulation** via Helper-Table — Plan-2 §8 D.3
  nennt das explizit als „nicht akzeptabel". MySQL/SQLite bleiben
  blockierend mit `status=BLOCKED_DIALECT_UNSUPPORTED`. Die
  Reverse-Lese-Logik signalisiert ggf. mit einem Hinweis „Source-Schema
  enthält Materialized View, Ziel-Dialekt unterstützt sie nicht".
- **Live-DB-Reverse-Read** der MV (Live-Vorbody, Live-Index-
  Existenz, refreshed-at-Timestamp) — harter OOS-Blocker in D.3b,
  da hierfür ein `MaterializedViewMetadataQueries`-Adapter pro Dialekt
  erforderlich wäre (`status=BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED`).
  Datei-zu-Datei-Pfad nutzt für Down nur den Schema-File als
  Wahrheitsquelle.
- **View-zu-MV-Konversion** (View löschen + MV mit demselben Namen
  erstellen, oder umgekehrt). D.3b blockiert das als Sicherheitsnetz;
  kein zusätzlicher Slice in diesem Release
  (`status=BLOCKED_CONVERSION_UNSUPPORTED`).
- **`schema refresh materialized-view`**-Subcommand — in D.3b hart
  blockiert (`status=BLOCKED_SCHEMA_REFRESH_UNSUPPORTED`). Periodisches
  Re-Refresh ist kein Schema-Diff-Op.
- **`ViewDefinition.refresh`** — Feld bleibt als Semantik-Input
  absichtlich ungenutzt und damit hart blockiert
  (`status=BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED`), solange keine
  Refresh-Vertragsspezifikation vorliegt.

### D.3b-Refresh-Vertragsinput (Schnittstellenkontext)

Für alle OOS-Entscheidungen rund um `REFRESH MATERIALIZED VIEW` ist der
Auslöser ein dedizierter, expliziter Eingangs-Hinweis aus dem
Planungs-/Ausführungs-Kontext (z. B. CLI-Contract, Pipeline-Metadaten oder
anderer Orchestrator-Input), **nicht** aus den Diff-Metadaten.

- Wenn dieser Kontext `CONCURRENTLY` setzt, wird
  `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED` verwendet.
- Wenn kein Kontext vorhanden ist oder die Refresh-Strategie nicht
  `CONCURRENTLY` ist, darf D.3b keinen Refresh-Contract selbst erraten.

Wird `ViewDefinition.refresh` gesetzt, bleibt der Pfad deterministisch
`BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED`.

## 3. Acceptance Criteria

- [ ] `DiffOperation` hat `CreateMaterializedView`,
      `ReplaceMaterializedView`, `DropMaterializedView` als eigene
      Op-Klassen; der `objectType` liefert `MATERIALIZED_VIEW` (statt
      `VIEW`) zur Renderer-Dispatch-Entscheidung.
- [ ] `DiffPlanner` emittiert die neuen Op-Klassen statt
      `CreateView`/`ReplaceView`/`DropView`, wenn `materialized=true` in einem
      Diff-Zustand eindeutig vorliegt; bei inkonsistentem oder fehlendem
      Materialization-Metadaten-Set nutzt er den passenden OOS-Blocker.
- [ ] `DiffPlanner` validiert bei `CreateMaterializedView`/`ReplaceMaterializedView`,
      dass das benötigte `query` (`after.query` bzw. `before.query`) vorhanden ist;
      bei fehlender Query wird `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`
      verwendet statt eines Laufzeitfehlers, außer bei echter
      `materialized`-Werte-Umwandlung (die immer `BLOCKED_CONVERSION_UNSUPPORTED`
      verwenden).
- [ ] `DiffPlanner` blockiert echte `View`↔`Materialized View`-Konversionen
      explizit mit `BLOCKED_CONVERSION_UNSUPPORTED`
      (`primaryBlockedReason=MATERIALIZED_VIEW_CONVERSION_UNSUPPORTED`) und
      liefert dafür keine MV-Op-Klasse.
- [ ] PostgreSQL-Renderer emittiert für die drei neuen Op-Klassen
      `CREATE MATERIALIZED VIEW`, `DROP MATERIALIZED VIEW`, und
      `DROP + CREATE MATERIALIZED VIEW` für Replace.
- [ ] PostgreSQL-Renderer-Down: `CreateMaterializedView`-Down →
      `DROP MATERIALIZED VIEW`; `DropMaterializedView`-Down nur bei
      bekanntem `view.query`, sonst Block
      `BLOCKED_DOWN_QUERY_UNKNOWN`;
      `ReplaceMaterializedView`-Down nur bei bekanntem
      `before.query`, sonst Block
      `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN`.
- [ ] MySQL- und SQLite-Renderer blockieren die drei MV-Op-Klassen
      mit `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT` und einer
      Begründung, die den Dialekt nennt.
- [ ] `SchemaMigrateReportBuilder.buildMaterializedViewContracts`
      schreibt konkrete Werte für `status`, `stalenessAfterUp`,
      `refreshSteps`, `locking` und `rollback` (siehe §6.4 für die
      Enumeration); kein Eintrag verbleibt mit
      `BLOCKED_UNTIL_REFRESH_STALENESS_CONTRACT` oder
      `UNKNOWN_REQUIRES_MANUAL_CONTRACT`, wenn die zugrunde liegende
      Op gerendert werden konnte.
- [ ] `SchemaMigrateReportBuilder` ordnet harte D.3b-OOS-Entscheidungen
      deterministisch zu:
      - inklusive `primaryBlockedReason`-Zuordnung der OOS-Codes.
      `BLOCKED_DIALECT_UNSUPPORTED`,
      `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED`,
      `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED`,
      `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED`,
      `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED` und
      `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED` sowie
      `BLOCKED_CONVERSION_UNSUPPORTED` sowie
      `BLOCKED_DOWN_QUERY_UNKNOWN`,
      `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN` und
      `BLOCKED_DEPENDENCY_UNRESOLVED`.
- [ ] `DependencyGuardEvaluator` integriert MV-Knoten in den
      Edge-Graph. Drop oder Replace einer Tabelle/View/Routine mit
      abhängiger MV ohne expliziten MV-Planungsschritt im selben Plan
      blockiert mit `BLOCKED_DEPENDENCY_UNRESOLVED`.
- [ ] `spec/cli-spec.md` beschreibt das MV-Rendering-Verhalten pro
      Dialekt + die neuen `materializedViews[]`-Statuswerte.
- [ ] CHANGELOG-Eintrag (Feature-Notiz für MV-Diff-Migration,
      Breaking-Change-Notiz für die neuen `DiffOperation`-Klassen).

## 4. Definition of Done

- AC §3 erfüllt.
- `make docker-test` + `make docker-coverage-gate` grün (≥90% pro
  Modul, inkl. der drei neuen Sub-Slices).
- Plan-Datei nach `docs/planning/done/` verschoben.
- Plan-2 §8 D.3b Status-Header-Block aktualisiert
  („Implementiert: PG-Renderer + Report-Vertrag + Dependency-Graph;
  CONCURRENTLY/uniqueIndexes/Refresh-Blocker hart dokumentiert").
- API-Migrationsnotiz im CHANGELOG (siehe §7).

## 5. Sub-Slice-Schnitt

Drei Sub-Slices, in dieser Reihenfolge zu implementieren. Jeder
Sub-Slice ist eigenständig review-fähig und schließt mit grünem
`docker-test` / `docker-coverage-gate` ab.

### Sub-Slice A — Op-Klassen + PG Create/Drop (Minimal-Renderpfad)

Schwerpunkt: neue Op-Typen + kleinster diff-basierter Renderpfad
(Create/Drop).

- `hexagon:core`:
  - Neue Klassen `DiffOperation.CreateMaterializedView`,
    `DiffOperation.DropMaterializedView` analog zu
    `CreateView`/`DropView` (gleiche Felder, eigener Klassenname).
  - `DiffPlanner` emittiert die neuen Klassen bei
    exakt einem Diff-Zustand mit `materialized=true` in genau einer der
    beiden Seiten (Create/Drop auf MV).
    Der bestehende View-Pfad bleibt für `materialized=false` unverändert.
  - Neue Operations-Type-Konstante in
    `RenameDependencyProjector.objectKey` für die Plan-Sortierung
    (`MATERIALIZED_VIEW_CREATE`, `MATERIALIZED_VIEW_DROP`).
- `adapters/driven/driver-postgresql`:
  - Neue Datei `PostgresDiffMaterializedViewOps.kt` mit
    `renderCreateMaterializedView(...)` /
    `renderDropMaterializedView(...)`. Body-Logik analog zur
    bestehenden `PostgresRoutineDdlHelper.kt:34`-Variante aus dem
    `schema generate`-Pfad — Diff-Pfad bekommt jetzt seine eigene
    Implementierung.
  - Render-Output (Sub-Slice A): `CREATE MATERIALIZED VIEW <name> AS
  <query>;` und `DROP MATERIALIZED VIEW <name>;`. **Kein**
    expliziter `REFRESH MATERIALIZED VIEW`-Schritt — der initiale
    Refresh ist implizit Teil von `CREATE … AS query`. Re-Refresh
    ist hart OOS in D.3b.
  - Down: Create-Down → emittiere `DROP MATERIALIZED VIEW`;
    Drop-Down → emittiere `CREATE MATERIALIZED VIEW`, wenn
    `view.query` bekannt; sonst Block
    `BLOCKED_DOWN_QUERY_UNKNOWN` mit
    `primaryBlockedReason = MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN`.
- `adapters/driven/driver-mysql` + `driver-sqlite`:
  - Renderer-Branch für die neuen Op-Klassen: `block` mit
    `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`. Der bisherige
    D.3a-`MATERIALIZED_VIEW_DIFF_UNSUPPORTED`-Pfad bleibt aktiv
    (greift, wenn der Planner aus irgendwelchen Gründen einen
    `CreateView` mit `materialized=true` durchlässt — Defense-in-
    Depth).
- `hexagon:application`:
  - `SchemaMigrateReportBuilder.materializedViewDefinition` /
    `materializedViewAction` / `materializedViewRollbackContract`
    erkennen die neuen Op-Klassen.
  - `buildMaterializedViewContracts` schreibt für **renderbare** MV-
    Ops (PG, nicht blockiert):
    - `status=READY`
    - `stalenessAfterUp=FRESH_AFTER_INITIAL_REFRESH` für Create
    - `stalenessAfterUp=NOT_APPLICABLE_DROP` für Drop
    - `refreshSteps=["INITIAL_REFRESH_VIA_CREATE"]` für Create
    - `refreshSteps=[]` für Drop
    - `locking=ACCESS_EXCLUSIVE` für beide; `CONCURRENTLY` ist hart
      blockiert in D.3b
    - `rollback=DROP_CREATED_MATERIALIZED_VIEW_REFRESH_NOT_REQUIRED`
      für Create
    - `rollback=SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED`
      für Drop (wenn `view.query` bekannt) oder
      `MANUAL_RECONSTRUCTION_REQUIRED` (sonst)
  - Für blockierte MV-Ops:
    - MySQL/SQLite: `status=BLOCKED_DIALECT_UNSUPPORTED`
      (Blocker-Typ auf Dialekt-Ebene).
    - PG mit fehlendem `query` im MV-Diff: `status=BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`.
    - `Drop` ohne bekannten Vorbody: `status=BLOCKED_DOWN_QUERY_UNKNOWN`,
    `stalenessAfterUp=UNKNOWN_BLOCKED`,
    `refreshSteps=["BLOCKED_*"]`-Variante mit konkretem Code.
- Tests:
  - PG View→MV bzw. MV→View-Konversion blockiert deterministisch mit
    `BLOCKED_CONVERSION_UNSUPPORTED` und
    `primaryBlockedReason=MATERIALIZED_VIEW_CONVERSION_UNSUPPORTED`.
    Es wird keine `CreateMaterializedView`/`ReplaceMaterializedView`-Op erzeugt.
  - PG `CreateMaterializedView` Up: `CREATE MATERIALIZED VIEW …`
    rendert, Report enthält READY + FRESH_AFTER_INITIAL_REFRESH.
  - PG `CreateMaterializedView` Up ohne `after.query`:
    blockiert mit `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`
    und `primaryBlockedReason=MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`.
  - PG `DropMaterializedView` Up: `DROP MATERIALIZED VIEW …`
    rendert.
  - PG `DropMaterializedView` Down mit `view.query`: rendert
    `CREATE MATERIALIZED VIEW …` zurück.
  - PG `DropMaterializedView` Down ohne `view.query`: blockiert mit
    `BLOCKED_DOWN_QUERY_UNKNOWN`.
    `primaryBlockedReason` muss `MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN`
    lauten.
  - MySQL `CreateMaterializedView`: blockiert mit
    `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`, Report-Status
    `BLOCKED_DIALECT_UNSUPPORTED`.
    `primaryBlockedReason` muss `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`
    lauten.
  - MySQL `CreateMaterializedView` mit explizitem `REFRESH MATERIALIZED VIEW CONCURRENTLY`
    im Refresh-Contract: ebenfalls `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`
    (Dialekt-Blocker hat Vorrang).
  - SQLite analog.
    `primaryBlockedReason` muss `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`
    lauten.
  - MySQL `DropMaterializedView` (Up/Down): blockiert mit
    `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`, Report-Status
    `BLOCKED_DIALECT_UNSUPPORTED`.
    `primaryBlockedReason` muss `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`
    lauten.
  - SQLite `DropMaterializedView` (Up/Down): analog.
  - PG `schema refresh materialized-view` (als OOS-Intent im Plan-Kontext):
    `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED` mit
    `primaryBlockedReason=MATERIALIZED_VIEW_SCHEMA_REFRESH_UNSUPPORTED`.
  - PG ohne verfügbare `MaterializedViewMetadataQueries`-Integration im
    Reverse-Read-Pfad (z. B. Live-Metadaten-Querystrom): `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED`
    mit `primaryBlockedReason=MATERIALIZED_VIEW_METADATA_UNSUPPORTED`.

**Nicht** in Sub-Slice A: `ReplaceMaterializedView`-Pfad
(Sub-Slice B), Dependency-Graph (Sub-Slice C). `CONCURRENTLY` ist
in D.3b hart blockiert.

### Sub-Slice B — PG `ReplaceMaterializedView`

Schwerpunkt: Replace-Pfad mit DROP+CREATE-Strategie und sauberem
Down-Vertrag.

- `hexagon:core`:
  - Neue Klasse `DiffOperation.ReplaceMaterializedView(name, before,
    after, …)`.
  - `DiffPlanner` emittiert Replace bei body- oder columns-Diff auf
    einer MV (statt der vorherigen `ReplaceView`-Variante).
- `adapters/driven/driver-postgresql`:
  - `renderReplaceMaterializedView(...)` emittiert zwei Statements:
    `DROP MATERIALIZED VIEW <name>; CREATE MATERIALIZED VIEW <name>
    AS <after.query>;`. Statement-Hints:
    `transactionBehavior=FULLY_TRANSACTIONAL` (PG-DDL ist
    transaktional), `locking=ACCESS_EXCLUSIVE`. Plan-Tests pinnen,
    dass beide Statements zur selben `operationId` gehören.
- Down: nur bei bekanntem `before.query`, sonst Block
    `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN` mit
    `primaryBlockedReason = MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN`.
- `adapters/driven/driver-mysql` + `driver-sqlite`:
  - Renderer-Branch für `ReplaceMaterializedView` blockiert analog
    Sub-Slice A.
- `hexagon:application`:
  - Report-Status für Replace: `status=READY`,
    `stalenessAfterUp=FRESH_AFTER_REPLACE_REFRESH`,
    `refreshSteps=["DROP_CREATE_INITIAL_REFRESH"]`,
    `locking=ACCESS_EXCLUSIVE`,
    `rollback=SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED`
    (wenn `before.query` bekannt) oder
    `MANUAL_RECONSTRUCTION_REQUIRED` (sonst).
- Tests:
  - PG `ReplaceMaterializedView` Up: emittiert DROP + CREATE
    in dieser Reihenfolge, beide mit derselben operationId.
  - PG `ReplaceMaterializedView` Up ohne `before.query` oder `after.query`:
    blockiert mit `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`
    und `primaryBlockedReason=MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`.
  - PG `ReplaceMaterializedView` Down mit `before.query`: emittiert
    den Spiegelvorgang.
  - PG `ReplaceMaterializedView` Down ohne `before.query`:
    blockiert mit `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN`.
    `primaryBlockedReason` muss `MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN`
    lauten.
  - MySQL/SQLite Replace: Block-Pfad.
    `primaryBlockedReason` muss `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`
    lauten.

### Sub-Slice C — Dependency-Graph + Sortierung

Schwerpunkt: MVs als Knoten im Dependency-Graph; Block bei
nicht-aufgelösten Drop-/Replace-Kaskaden; Sortierung von
Tabellen-/View-/Routinen-Drops/Replace und MV-Drops/Replaces.

- `hexagon:core`:
  - `RoutineDependencyAnalyzer` (oder ein neuer
    `MaterializedViewDependencyAnalyzer`) extrahiert Edges MV →
    Table/View/Routine aus `ViewDefinition.dependencies` (Feld
    existiert bereits).
  - `DependencyGuardEvaluator` integriert MV-Knoten. Drop einer
    Tabelle/View/Routine, auf die eine MV in `dependencies.tables`/
    `dependencies.views`/`dependencies.routines` zeigt, ohne dass
    die MV im selben Plan einen Drop oder Replace hat, blockiert
    mit `BLOCKED_DEPENDENCY_UNRESOLVED`.
  - `DiffPlanner` sortiert MV-Drops/Replaces vor Table-/View-/
    Routinen-Drops/Replaces, sodass abhängige MV-Knoten zuerst in der
    Reihenfolge ablaufen (Standard-PG-Verhalten ohne `CASCADE`).
  - `hexagon:application`:
  - Report-Status `status=BLOCKED_DEPENDENCY_UNRESOLVED` für den
    Block-Pfad, mit `dependencyBlockers`-Feld, das die abhängige
    MV und die droppende Tabelle nennt.
  - `materializedViews[]`-Eintrag enthält im Block-Pfad eine
    `dependencyBlockers`-Subfield mit dem Pfad der droppenden
    Operation.
- Tests:
  - PG Drop einer Tabelle mit abhängiger MV ohne MV-Drop: Block.
    `primaryBlockedReason` muss `MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED`
    lauten.
  - PG Drop einer Tabelle mit abhängiger MV inklusive MV-Drop:
    rendert beide Drops in der richtigen Reihenfolge (MV vor
    Tabelle).
  - PG Replace einer Tabelle mit abhängiger MV inkl. MV-Replace:
    rendert beide Replaces in der richtigen Reihenfolge (MV vor
    Tabelle).
  - PG Replace einer Tabelle mit abhängiger MV ohne MV-Replace:
    Block (`BLOCKED_DEPENDENCY_UNRESOLVED`) für D.3b.
    `primaryBlockedReason` muss `MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED`
    lauten.
  - Cross-MV-Abhängigkeit: MV-A referenziert MV-B; Drop MV-B
    ohne MV-A-Drop: Block.
    `primaryBlockedReason` muss `MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED`
    lauten.
  - Diese Dependency-Blocker gelten nur auf renderbaren PG-Pfaden; für
    MySQL/SQLite blockt zuerst `BLOCKED_DIALECT_UNSUPPORTED` (kein
    MV-Support).
  - OOS-Kanten-Tests:
    - `BLOCKED_DEPENDENCY_UNRESOLVED` enthält `dependencyBlockers` mit
      genauem `operationPath` der blockierenden Tabelle/View/Routine.
    - `primaryBlockedReason` ist `MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED`.

### Cross-Slice OOS-Contract-Coverage

- Nicht-slice-spezifische OOS-Assertions für den D.3b-Vertragskern:
  - Präzedenzregel für OOS-Codes:
    1. `BLOCKED_DIALECT_UNSUPPORTED` (Primary-Reason:
    `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`) hat höchste Priorität,
    weil die Operation auf dem Ziel-Dialekt selbst nicht ausführbar ist.
    2. `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED` (Primary-Reason:
    `MATERIALIZED_VIEW_CONCURRENT_REFRESH_UNSUPPORTED`) hat Vorrang vor allen
    Schema-/Refresh-spezifischen OOS-Codes, aber nach Dialekt-Blockade.
    3. `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED` (Primary-Reason:
    `MATERIALIZED_VIEW_SCHEMA_REFRESH_UNSUPPORTED`) hat Vorrang vor
    `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED`,
    `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED`,
    `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`,
    `BLOCKED_DOWN_QUERY_UNKNOWN` und `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN`.
    4. `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` (Primary-Reason:
    `VIEW_DEFINITION_REFRESH_SEMANTICS_UNSPECIFIED`) hat Vorrang vor
    `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED` und
    `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`.
    5. `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED` (Primary-Reason:
    `MATERIALIZED_VIEW_METADATA_UNSUPPORTED`) hat Vorrang vor
    `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`.
    6. `BLOCKED_CONVERSION_UNSUPPORTED` (Primary-Reason:
    `MATERIALIZED_VIEW_CONVERSION_UNSUPPORTED`) hat Vorrang vor
    `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`.
    7. `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED` ist der
    fallback bei unklarer MV-Metadatenlage (ohne Konversion, ohne andere
    harte OOS-Cause).
    8. `BLOCKED_DEPENDENCY_UNRESOLVED` ist der fallback für renderbare
    PG-Pfade, wenn keine höher priorisierte harte Inkompatibilität vorliegt.
  - `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED` wird deterministisch mit
    `primaryBlockedReason=MATERIALIZED_VIEW_CONCURRENT_REFRESH_UNSUPPORTED`
    gesetzt, wenn ein expliziter Refresh-Vertragsinput außerhalb von
    `ViewDefinition.refresh` auf `CONCURRENTLY` verweist.
  - `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED` für `schema refresh materialized-view`
    inklusive `primaryBlockedReason=MATERIALIZED_VIEW_SCHEMA_REFRESH_UNSUPPORTED`.
  - `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` bei gesetzter
    `ViewDefinition.refresh` ohne D.3b-Semantik mit
    `primaryBlockedReason=VIEW_DEFINITION_REFRESH_SEMANTICS_UNSPECIFIED`.
  - `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED` für fehlende
    Reverse-Read-Metadaten (Live-DB-/Runtime-Metadaten) bei fehlender
    `MaterializedViewMetadataQueries`-Integration.
  - `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED` bei unklarer oder
    asymmetrischer `materialized`-Deklaration im Diff.

## 6. Konkrete Spezifikation

### 6.1 Neue Op-Klassen

```kotlin
// hexagon:core/diff/migration/DiffOperation.kt
sealed interface DiffOperation {
    // existing operations …

    data class CreateMaterializedView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val view: ViewDefinition,
        // analog zu CreateView: phase, reversibility, …
    ) : DiffOperation

    data class ReplaceMaterializedView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val before: ViewDefinition,
        val after: ViewDefinition,
        // analog zu ReplaceView
    ) : DiffOperation

    data class DropMaterializedView(
        override val id: String,
        override val objectRef: DiffObjectRef,
        val view: ViewDefinition,
        // analog zu DropView
    ) : DiffOperation
}
```

Die `objectType`-Eigenschaft für die drei Klassen liefert einen
neuen Wert `MATERIALIZED_VIEW` (statt `VIEW`). Damit kann der
Renderer pro Op-Klasse dispatchen, ohne das `materialized`-Flag in
`ViewDefinition` zu inspizieren.

### 6.2 DiffPlanner-Routing

```kotlin
// hexagon:core/diff/migration/DiffPlanner.kt (sinngemäß)
private fun planView(name: String, diff: ViewDiff) {
    val beforeMaterialized = diff.before?.materialized
    val afterMaterialized = diff.after?.materialized
    val beforeQuery = diff.before?.query
    val afterQuery = diff.after?.query
    val beforeExists = diff.before != null
    val afterExists = diff.after != null
    val hasBothObjects = beforeExists && afterExists
    // `materialized = null` bedeutet im D.3b-Pfad „nicht explizit materialized“
    // und wird wie ein normaler View-Flow behandelt.
    val hasMaterializedHint = (beforeMaterialized == true) || (afterMaterialized == true)
    when {
        hasBothObjects && beforeMaterialized != null && afterMaterialized != null && beforeMaterialized != afterMaterialized ->
            planMaterializationConversionUnsupported(name, diff)
        hasMaterializedHint && afterExists && !beforeExists && (afterQuery == null) ->
            planMaterializedViewDiffMetadataMissingUnsupported(name, diff)
        hasMaterializedHint && beforeExists && afterExists && (beforeQuery == null || afterQuery == null) ->
            planMaterializedViewDiffMetadataMissingUnsupported(name, diff)
        hasMaterializedHint && beforeExists && afterExists && (beforeMaterialized == null || afterMaterialized == null) ->
            planMaterializedViewDiffMetadataMissingUnsupported(name, diff)
        hasMaterializedHint && beforeExists && beforeMaterialized == null ->
            planMaterializedViewDiffMetadataMissingUnsupported(name, diff)
        hasMaterializedHint && afterExists && afterMaterialized == null ->
            planMaterializedViewDiffMetadataMissingUnsupported(name, diff)
        hasMaterializedHint -> planMaterializedView(name, diff)
        else -> planRegularView(name, diff)
    }
}
```

Ein View, dessen `materialized`-Flag zwischen `before` und `after`
kippt, ist eine **View-zu-MV-Konversion** und bleibt für D.3b
explizit blockierend (`BLOCKED_CONVERSION_UNSUPPORTED`,
siehe §2 „Aus Scope"). Es gibt keinen separaten Slice für diese
Migrationsklasse im Rahmen von D.3b.

`planMaterializedViewDiffMetadataMissingUnsupported` blockiert Fälle, in denen die
`materialized`-Semantik bei MV-relevanten Objekten nicht eindeutig aus dem
Diff ableitbar ist (z. B. `materialized=true` auf einer Seite und
`null` auf der anderen, oder nur eine Seite mit `materialized = null`
bekannt) und damit auch ein fehlender `query`-Body für einen Create/Replace
des MV-Diffs. Der Report liefert dafür deterministisch
`status=BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED` mit
`primaryBlockedReason = MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`.

### 6.3 PostgreSQL-Renderer-Output

**Create:**
```sql
CREATE MATERIALIZED VIEW "daily_sales" AS
SELECT date, SUM(amount) AS total FROM sales GROUP BY date;
```

**Drop:**
```sql
DROP MATERIALIZED VIEW "daily_sales";
```

**Replace:**
```sql
DROP MATERIALIZED VIEW "daily_sales";
CREATE MATERIALIZED VIEW "daily_sales" AS
SELECT date, SUM(amount) AS total, COUNT(*) AS rows
FROM sales GROUP BY date;
```

Beide Statements einer Replace-Operation tragen dieselbe
`operationId`, sodass `executionStatementGroups` sie als atomare
Einheit ausweisen kann (Workstream-G-Vertrag).

### 6.4 Report-Vertrag: `materializedViews[]`-Statuswerte

| Feld | Werte | Semantik |
|---|---|---|
| `status` | `READY` | MV ist diff-basiert renderbar (PG). |
| | `BLOCKED_DIALECT_UNSUPPORTED` | MySQL/SQLite — kein nativer MV-Renderer. |
| | `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED` | `REFRESH MATERIALIZED VIEW CONCURRENTLY` ist explizit per Refresh-Contract angefordert (nicht aus `ViewDefinition.refresh`). |
| | `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED` | `materialized`-Semantik im Diff unklar oder asymmetrisch. |
| | `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED` | Reverse-Read-Metadaten der MV nicht verfügbar. |
| | `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` | `ViewDefinition.refresh` ist bewusst nicht spezifiziert. |
| | `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED` | `schema refresh materialized-view` nicht Teil des Diff-Scope. |
| | `BLOCKED_DOWN_QUERY_UNKNOWN` | Drop-Down ohne bekannten Vorbody (Datei-zu-Datei). |
| | `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN` | Replace-Down ohne bekannten Vorbody. |
| | `BLOCKED_DEPENDENCY_UNRESOLVED` | Dependency-Drop oder -Replace ohne passende MV-Operation im selben Plan. |
| | `BLOCKED_CONVERSION_UNSUPPORTED` | View-zu-MV-Konversion (oder umgekehrt). |
| `stalenessAfterUp` | `FRESH_AFTER_INITIAL_REFRESH` | Create lieferte gefüllte MV. |
| | `FRESH_AFTER_REPLACE_REFRESH` | Replace lieferte gefüllte MV. |
| | `NOT_APPLICABLE_DROP` | MV existiert nach Drop nicht mehr. |
| | `UNKNOWN_BLOCKED` | Op blockiert; Aussage nicht möglich. |
| `refreshSteps` | `["INITIAL_REFRESH_VIA_CREATE"]` | Create-Pfad. |
| | `["DROP_CREATE_INITIAL_REFRESH"]` | Replace-Pfad. |
| | `[]` | Drop-Pfad. |
| | `["BLOCKED_*"]` (mit konkretem Block-Code) | blockiert. |
| `locking` | `ACCESS_EXCLUSIVE` | PG-MV-DDL ohne CONCURRENTLY. |
| | `UNKNOWN_BLOCKED` | Op blockiert. |
| `rollback` | `DROP_CREATED_MATERIALIZED_VIEW_REFRESH_NOT_REQUIRED` | Create-Down trivial. |
| | `SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED` | Replace/Drop-Down mit Vorbody. |
| | `MANUAL_RECONSTRUCTION_REQUIRED` | Replace/Drop-Down ohne Vorbody. |
| | `ROLLBACK_NOT_POSSIBLE` | Down-Blocker — Rollback wäre nur mit manueller Rekonstruktion möglich. |

Der bisherige konservative Wert
`BLOCKED_UNTIL_REFRESH_STALENESS_CONTRACT` existiert nach D.3b
nicht mehr. Der `UNKNOWN_REQUIRES_MANUAL_CONTRACT`-Locking-Wert
wird nicht für produktive Diagnosepfade in D.3b verwendet; für die
CONCURRENTLY-/Metadata-/Schema-Refresh- bzw. Refresh-Semantik-OOS-Pfade
werden die konkreten Block-Diagnostics verwendet.

Zu den neuen OOS-Status gehören deterministische
`primaryBlockedReason`-Werte:
- `BLOCKED_DIALECT_UNSUPPORTED`  -> `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`
- `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED`
  -> `MATERIALIZED_VIEW_CONCURRENT_REFRESH_UNSUPPORTED`
- `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`
  -> `MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`
- `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED`
  -> `MATERIALIZED_VIEW_METADATA_UNSUPPORTED`
- `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED`
  -> `VIEW_DEFINITION_REFRESH_SEMANTICS_UNSPECIFIED`
- `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED`
  -> `MATERIALIZED_VIEW_SCHEMA_REFRESH_UNSUPPORTED`
- `BLOCKED_CONVERSION_UNSUPPORTED`
  -> `MATERIALIZED_VIEW_CONVERSION_UNSUPPORTED`
- `BLOCKED_DOWN_QUERY_UNKNOWN`
  -> `MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN`
- `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN`
  -> `MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN`
- `BLOCKED_DEPENDENCY_UNRESOLVED`
  -> `MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED`

### 6.4.1 OOS-Sourcing (wer setzt welche OOS-Entscheidung)

- `DiffPlanner`:
  - `BLOCKED_CONVERSION_UNSUPPORTED`.
  - `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`,
    `BLOCKED_DOWN_QUERY_UNKNOWN`,
    `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN` (wenn der Down-Body nicht
    deterministisch rekonstruiert werden kann).
  - `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` (bei gesetztem
    `ViewDefinition.refresh`, da D.3b keine semantische Auswertung vornimmt).
  - `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED` bei explizitem Refresh-Contract
    mit `CONCURRENTLY` außerhalb des Diff-Scopes.
- `DependencyGuardEvaluator`:
  - `BLOCKED_DEPENDENCY_UNRESOLVED`.
- Renderer (`driver-postgresql`, `driver-mysql`, `driver-sqlite`):
  - `BLOCKED_DIALECT_UNSUPPORTED` inklusive D.3a-Defense (`MATERIALIZED_VIEW_DIFF_UNSUPPORTED`).
- `SchemaMigrateReportBuilder`:
  - Normalisiert abschließend die finalen `status`/`primaryBlockedReason`/
    `stalenessAfterUp`/`refreshSteps`/`locking`/`rollback` für alle OOS-Fälle.
  - Schreibt zusätzlich die Context-basierten OOS-Fälle in die Vertragsantwort:
    `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED`,
    `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED`,
    `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED`,
    `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED`
    inklusive der in §6.4.1 definierten `primaryBlockedReason`.

### 6.5 Modul-Grenzen

| Layer | Typ / Datei |
|---|---|
| `hexagon:core` | `DiffOperation.{CreateMaterializedView, ReplaceMaterializedView, DropMaterializedView}` (neu) |
| `hexagon:core` | `DiffPlanner` (Routing) |
| `hexagon:core` | `RoutineDependencyAnalyzer` (Erweiterung um MV-Edges) — oder neuer `MaterializedViewDependencyAnalyzer` |
| `hexagon:application` | `SchemaMigrateReportBuilder` (Statuswerte) |
| `adapters/driven/driver-postgresql` | `PostgresDiffMaterializedViewOps` (neu) |
| `adapters/driven/driver-mysql` | Renderer-Branch in `MysqlDiffOtherOps` (oder analog) |
| `adapters/driven/driver-sqlite` | Renderer-Branch in `SqliteDiffSimpleOps` (oder analog) |

Keine Adapter-Layer-Inversion: alle neuen Op-Klassen leben in
`hexagon:core`; alle Renderer in `adapters/driven/driver-*`.

### 6.6 ViewDefinition.refresh in D.3b

`ViewDefinition.refresh` (`String?`) bleibt in D.3b bewusst unbenutzt:

- D.3b-Renderer dürfen `refresh` weder lesen noch daraus SQL oder
  Report-Vertrag ableiten.
- D.3b-Diff kann `refresh` als alleinige Änderung nicht als
  Down-/Up-Delta rendern.
- `ViewDefinition.refresh`-Inhalt wird als semantischer Input **nicht**
  ausgewertet; ist er gesetzt, landet die Operation auf
  `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` mit
  `VIEW_DEFINITION_REFRESH_SEMANTICS_UNSPECIFIED`.
- Die Erkennung von
  `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED` ist auf einen separaten,
  expliziten Refresh-Vertragsinput (nicht auf `ViewDefinition.refresh`)
  beschränkt.

## 7. Breaking-Change-Migration

`DiffOperation` bekommt drei neue Sealed-Subklassen
(`CreateMaterializedView`, `ReplaceMaterializedView`,
`DropMaterializedView`). Existierender Code, der `when`-
exhaustiveness gegen `DiffOperation` prüft, muss die drei neuen
Klassen ergänzen — sonst Compiler-Fehler in Kotlin.

Betroffen sind insbesondere:

- Alle Renderer-Dispatcher in den drei Dialekten.
- `SchemaMigrateReportBuilder`-Helper
  (`materializedViewDefinition`, `materializedViewAction`,
  `materializedViewRollbackContract`).
- `DependencyGuardEvaluator` (für MV-Edge-Behandlung).
- `RenameDependencyProjector` (für Plan-Sortierung).
- Test-Fixtures, die direkt `DiffOperation`-Instanzen
  konstruieren.

Migrationsnotiz im CHANGELOG:

> `DiffOperation` hat drei neue Sealed-Subklassen
> (`CreateMaterializedView`, `ReplaceMaterializedView`,
> `DropMaterializedView`). Embedders / Extension-Code, der gegen
> `DiffOperation` `when`-matcht, muss die neuen Klassen ergänzen.
> Default-Behaviour für nicht-MV-Renderer ist ein Block mit
> `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`.

## 8. Risiken / Stolperfallen

- **View-zu-MV-Konversion**: ein Schema-Wechsel von
  `materialized=false` zu `materialized=true` (oder umgekehrt) ist
  in PG kein In-Place-Befehl. Der Planer muss das als
  `BLOCKED_CONVERSION_UNSUPPORTED` erkennen und nicht still als
  ReplaceView ↔ ReplaceMaterializedView durchgehen lassen.
- **Replace einer Tabelle, von der eine MV abhängt**: PG-Semantik
  ist „Replace blockt, weil die MV den `SELECT`-Plan an die alte
  Tabellenform gebunden hat". Sub-Slice C blockt für D.3b, weil ein
  automatischer MV-Replace ohne expliziten Schema-Eintrag eine versteckte
  SQL-Änderung wäre.
- **DROP + CREATE in der Replace-Strategie ist nicht atomar in
  Bezug auf Lesezugriffe**: zwischen DROP und CREATE sieht jeder
  Reader keine MV. PG-DDL ist transaktional, also schützt
  `BEGIN; DROP; CREATE; COMMIT;` Reader vor dem
  Zwischenzustand — aber nur, wenn alle Statements im selben
  Statement-Group laufen. Workstream-G-`transactionScope=RUNNER_OWNED_TX`
  ist Vorbedingung. Test pinnt das.
- **`DROP MATERIALIZED VIEW IF EXISTS` vs. ohne `IF EXISTS`**:
  D.3b-Vollscheibe nutzt **ohne** `IF EXISTS` für die Up-Variante
  (Plan beschreibt den Ist-Zustand exakt; eine fehlende MV beim
  Drop ist ein Konflikt, kein Idempotenz-Use-Case). Der Down-Pfad
  einer Create-Op nutzt `DROP MATERIALIZED VIEW` ohne `IF EXISTS`
  konsistent — falls die Up nie lief, schlägt Down ohnehin nicht
  fehl, weil dann kein Down-Statement abgefeuert wird (Rollback-
  Logik filtert).
- **Reverse-Read im Datei-zu-DB-Pfad**: dieser Slice nimmt
  `view.query` aus dem Schema-File als Wahrheitsquelle. Wenn der
  Live-DB-Stand davon abweicht (z. B. weil jemand die MV manuell
  geändert hat), bemerkt der Diff das nicht. Ein eigener
  `MaterializedViewMetadataQueries`-Adapter ist OOS für D.3b.
- **Kover-Coverage**: die neuen Op-Klassen erzeugen viele neue
  `when`-Branches im Application-/Renderer-Code. Coverage-Pin
  pro Branch.
- **`ViewDefinition.refresh`**: das Feld existiert bereits
  (`String?`), wird aber bisher nicht gerendert. D.3b lässt das
  Feld bewusst unverwendet — die `refresh`-Bedeutung („Auto-
  Refresh-Periode" o.ä.) ist nicht spezifiziert und bleibt blockiert.

## 9. Harte OOS-/Blocker-Entscheidung für D.3b (6 Carve-outs)

1. **`REFRESH MATERIALIZED VIEW CONCURRENTLY`** + Unique-Index-
   Voraussetzungen + Atomic-Swap-Strategie (`BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED`):
   hart blockiert, wenn der explizite Refresh-Vertragsinput diese Strategie verlangt.
   CONCURRENTLY ist relevant für In-Place-Refresh ohne `DROP` und
   erfordert zusätzliche Runtime-Koordination, die D.3b nicht vorsieht.
2. **MySQL-/SQLite-Emulation** (`BLOCKED_DIALECT_UNSUPPORTED`):
   hart blockiert; keine MV-Emulation im Scope.
3. **Live-DB-Reverse-Read** der MV (Live-Vorbody, Live-Index-Existenz,
   refreshed-at-Timestamp) (`BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED`):
   hart blockiert, da zusätzlicher
   `MaterializedViewMetadataQueries`-Adapter im Plan nicht vorgesehen.
4. **View-zu-MV- und MV-zu-View-Konversion**
   (`BLOCKED_CONVERSION_UNSUPPORTED`):
   hart blockiert, um stille Schema-Änderungen zu vermeiden.
5. **`schema refresh materialized-view`** (`BLOCKED_SCHEMA_REFRESH_UNSUPPORTED`):
   hart blockiert; nicht Teil des Schema-Diff-Scope.
6. **`ViewDefinition.refresh`-Semantik** (`BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED`):
   hart blockiert/unausgewertet in D.3b; keine implizite
   Refresh-Kontraktbildung.
