# Implementierungsplan: 0.9.7 — F.4 Rename-Dependency-Projection

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.4 (dritter Slice — Dependency-Projection für Renames)
> **Status**: open (geplant, noch nicht gestartet)
> **Vorbedingung**: F.4 Overlay-Vertragsslice ✅, F.4 Rendering-Slice ✅,
>                  zentraler Pre-Plan-Overlay-Gate aus dem
>                  `RENAME_MAPPING_INVALID`-Slice oder in diesem Slice
>                  mitgeliefert
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md` §10 F.4
>             `docs/planning/done/ImpPlan-0.9.7-F.4-rendering.md`

---

## 1. Ziel

Der Rendering-Slice (F.4 zweite Scheibe) faltet `(DropTable,
CreateTable)`- und per-Tabelle `(DropColumn, AddColumn)`-Paare nur,
wenn Quelle und Ziel **strukturell identisch** sind. Sobald eine
Tabelle nebenbei Indizes, Constraints oder Spalten aendert oder eine
Spalte nebenbei Typ/Default/Required wechselt, faellt der Mapper auf
den bestehenden Drop+Add-Pfad zurueck und legt einen
`RENAME_OVERLAY_STRUCTURAL_MISMATCH`-Warning ab.

Dieser Folgeslice schaltet den Rename auch fuer Mischfaelle frei,
sofern alle abhaengigen Objekte zuverlaessig auf den neuen Namen
projiziert werden koennen. Die Faltung bleibt verboten, wenn auch nur
eine Dependency nicht eindeutig oder dialektsicher nachgezogen werden
kann; in dem Fall greift der bestehende Drop+Add-Fallback weiter.

## 2. Scope

In Scope:

- `RenameDependencyProjector` als neue Komponente in `hexagon:core`;
  er wird in die Mapper-/Planner-Grenze eingezogen und erzeugt vor
  `DependencyAnalyzer` die endgueltige Operationenliste.
- Explizite Mischfall-Delta-Synthese fuer umbenannte Objekte: Weil der
  Comparator Tabellen-/Top-Level-Objekte nach Map-Key vergleicht, liefert
  ein Rename mit Zusatzänderung heute nur `removed + added`, aber keinen
  `TableDiff`/`ViewDiff`/`SequenceDiff`. Der Mapper muss daher fuer jedes
  akzeptierte Rename-Paar Quelle und Ziel auf einen gemeinsamen Zielnamen
  rebasen, die normalen Detail-Diffs synthetisieren und daraus nach dem
  Rename die fachlichen Folge-Operationen (`AddColumn`,
  `AlterColumn*`, `AddIndex`, `DropConstraint`, ...) erzeugen.
- Pro Dialekt eine `RenameDependencyPolicy`, die klassifiziert, welche
  Dependencies die Engine automatisch nachzieht
  (`AUTOMATIC_BY_ENGINE`), welche das d-migrate-Plan-Modell explizit
  nachzieht (`EXPLICIT_REPROJECTION`) und welche das Rename hart
  blockieren (`NO_PROJECTION_AVAILABLE`).
- Die Policy-Typisierung bleibt core-lokal: `hexagon:core` darf nicht
  von `hexagon:ports-common` / `DatabaseDialect` abhaengen. Der
  Application-/CLI-Layer mappt `DatabaseDialect` auf einen neuen
  core-lokalen `RenameProjectionDialect`-Wert oder auf einen stabilen
  String-Identifier, den `DiffPlanner.plan(...)` konsumiert.
- Runtime-/Engine-Capabilities sind expliziter Policy-Input und muessen
  vor `DiffPlanner.plan(...)` verfuegbar sein. SQLite-
  Version/`legacy_alter_table`, MySQL-Serverfamilie/-Version und
  aehnliche Engine-Preconditions duerfen nicht aus dem Dialekt allein
  geraten werden. Wenn der Application-/CLI-Layer diese Informationen
  nicht liefern kann (typisch Datei-zu-Datei oder DB-Target ohne
  verlustfrei bekannte Capability), klassifiziert die Policy die
  betroffenen Dependency-Klassen konservativ als
  `NO_PROJECTION_AVAILABLE`. Ein spaeter Execute-Preflight darf nur die
  schon geplante Garantie bestaetigen oder den Lauf blockieren; er darf
  nicht nachtraeglich einen Drop+Add-Fallback in einen Rename umplanen.
- Explizite Re-Projection-Operationen fuer die `EXPLICIT_REPROJECTION`-
  Faelle (z.B. `Index` mit Spaltennamen oder `View` mit Body-Referenz)
  als zusaetzliche Folge-Operationen im Plan, deterministisch nach dem
  Rename sortiert.
- Reportausgabe: `renameProjection`-Block pro Rename-Candidate, nicht nur
  pro final emittierter `RenameTable`/`RenameColumn`. Der Block traegt die
  drei Listen, die ID der finalen Rename-Operation falls eine Faltung
  stattfindet, die IDs der erzeugten Folge-Operationen und bei Fallback die
  IDs der Drop+Add-Operationen. Die Daten werden nicht aus freiem Text
  rekonstruiert, sondern als eigenes Planmodell im `DiffResult` getragen.
  Sobald dieses Planmodell in ein oeffentliches `migration-plan.v1`-Artefakt
  serialisiert wird, ist es versionierte Semantik und braucht denselben
  JSON-/Validator-/Golden-/Compat-Gate wie andere F.2-Artefaktfelder.
  Ohne diesen Gate bleibt `renameProjections` auf den Migrate-Report und den
  internen `DiffResult` beschraenkt. Da der Migrate-Report selbst
  oeffentlicher CLI-Vertrag ist, wird `spec/cli-spec.md` §6.1 im selben
  Slice um den optionalen `renameProjections`-Abschnitt erweitert.
- Entry-Provenance wird ueberall explizit transportiert: Candidate,
  `RenameTable`/`RenameColumn`, `RenameProjectionReport` und spaetere
  Plan-/Report-Felder tragen `overlayEntryId` neben `overlaySource` und
  `overlayHash`. Ein Overlay-Dokument kann mehrere Rename-Mappings
  enthalten; `overlaySource + overlayHash` reicht daher nicht, um den
  autorisierenden Eintrag maschinenlesbar zu bestimmen.
- Rollback-Vertrag fuer Mischfaelle: Automatisches Down ist nur zulaessig,
  wenn der inverse Rename plus die inversen synthetischen Delta- und
  Re-Projection-Operationen vollstaendig aus dem Planmodell rekonstruierbar
  sind. Andernfalls blockiert `--generate-rollback` mit
  `ROLLBACK_NOT_POSSIBLE`; ein reines "Rename up, inverse Rename down"
  waere fuer Mischfaelle fachlich unvollstaendig.
- Tests pro Dialekt mit mindestens einem
  `AUTOMATIC_BY_ENGINE`-, einem `EXPLICIT_REPROJECTION`- und einem
  `NO_PROJECTION_AVAILABLE`-Pfad.

Aus Scope:

- View-Body-Re-Schreiben mit SQL-Parsing: Solange `ViewDefinition.query`
  ein opaker String ist, beschraenkt sich die Projection auf den
  Replace-Pfad (Drop alten View, Create neuen View aus dem Soll-Body).
  Echtes SQL-Body-Rewriting bleibt View-Migration-Folgeslice (E.2/G).
- Routine-/Trigger-Body-Re-Schreiben: gleicher Grund, bleibt
  Workstream E.
- Live-Pruefung gegen die Datenbank, ob Dependencies wirklich
  existieren: Datei-zu-DB ist Folgeslice; dieser Slice arbeitet rein
  auf dem Schema-Modell.
- Sequence-Ownership-Reprojection (`ALTER SEQUENCE ... OWNED BY ...`):
  Das neutrale Modell hat aktuell kein `SequenceDefinition.ownedBy`;
  owned/implizite Sequenzen haengen an `ColumnGeneration.sequenceName`
  und duerfen nicht als standalone `sequences:`-Objekt modelliert sein.
  Ownership-Reprojection bleibt daher aus diesem Slice heraus, bis ein
  eigener Sequence-Ownership-Vertrag `ownedBy` oder ein aequivalentes
  Modellfeld eingefuehrt hat.
- `RENAME_MAPPING_INVALID` als eigener `MigrationBlockedReason`-Enum-
  Wert (Plan-2 §10 Carve-out): bleibt offen, bis die existierenden
  Blocker-Reasons nicht mehr reichen.

## 3. Architektur

### 3.1 Pipeline-Einbettung

```
SchemaDiff
   ↓
OperationMapper.prepare(..., migrationOverlays)
   ↓  (normale Ops + RenamePlanningItem mit stabilen Candidate-IDs
       + synthetischen Intra-Object-Delta-Ops + Drop/Add-Fallback)
RenameDependencyProjector.project(candidates, current, desired, dialect)
   ↓  (ersetzt Candidates durch Rename+Folge-Ops oder durch Fallback-Ops)
OperationMapper.finalizeIds(...)
   ↓
splitReplaceViewsForColumnConflicts(...)
   ↓  (liefert ggf. oldOpId -> replacementOpIds und remappt Reports)
   ↓
DependencyAnalyzer.attach
   ↓
TopologicalSorter.sort
   ↓
DiffPlanner final safety diagnostics
   - detectViewColumnDepsBlockers(...)
   - detectIncompleteViewProjections(...)
   ↓
DiffResult
```

Der Projector muss VOR `DependencyAnalyzer` laufen, damit sowohl die
synthetischen Delta-Operationen innerhalb des umbenannten Objekts als
auch die Dependency-Folge-Operationen Teil der Topo-Sortierung werden.
Er darf nicht nach einem bereits finalisierten `List<DiffOperation>` nur
noch "aufsatteln", weil ein blockierter Rename dann keine saubere
Drop+Add-Alternative mehr hat. Der Mapper liefert deshalb ein
Zwischenmodell mit Candidate, synthetischen Delta-Operationen und
Fallback-Operationen; erst der Projector entscheidet, welche Variante in
den Plan gelangt.

Bestehende Planner-Safety-Pässe bleiben Teil der finalen Pipeline und
laufen auf der vom Projector erzeugten Operationenliste, nicht auf der
vorherigen Mapper-Rohform. Insbesondere muessen
`splitReplaceViewsForColumnConflicts(...)`,
`detectViewColumnDepsBlockers(...)` und
`detectIncompleteViewProjections(...)` auch synthetische
Post-Rename-Column-Deltas sehen. Der View-Split laeuft wie heute vor der
Dependency-Analyse; die finalen View-Blocker-Diagnostics laufen auf der
sortierten finalen Operationenliste. Ein Spalten-Rename plus
`AlterColumnType` darf daher weiterhin den G.3-View-Split bzw. die
F.6.b/G.2-View-Blocker ausloesen; der Projector ist keine Abkuerzung um
diese Schutzmechanismen.

Wichtig fuer die ID-Stabilitaet: Der Candidate traegt bereits die
finale Rename-ID, die auch im Erfolgsfall in `DiffOperation.Rename*`
landet. Folge-Operationen duerfen `dependencies = setOf(candidate.id)`
setzen. Falls die Implementierung weiterhin `OperationMapper.disambiguateIds`
nach dem Projector ausfuehrt, muss dieser Schritt eine
`oldId -> newId`-Map liefern und alle `dependencies` der gesamten
Operationenliste remappen. Ohne eine dieser beiden Garantien koennen
Folge-Operationen auf veraltete Rename-IDs zeigen.

Dasselbe gilt fuer Planner-Rewrites nach dem Projector. Insbesondere
`splitReplaceViewsForColumnConflicts(...)` darf keine Report-Referenzen
auf Operationen hinterlassen, die es aus der finalen Operationenliste
entfernt. Wenn eine explizite View-Reprojection zunaechst als
`ReplaceView` erzeugt wird und der Split daraus `DropView`/`CreateView`
macht, muss der Split ein strukturiertes `oldOperationId ->
replacementOperationIds`-Mapping liefern und `RenameProjectionReport.explicit`
sowie alle Dependency-Referenzen vor `DiffResult` atomar remappen. Alternativ
emittiert der Projector fuer solche Faelle direkt finale Drop/Create-
Reprojection-Ops. Ein `renameProjection`-Report darf niemals auf eine
Operation-ID zeigen, die in `DiffResult.operations` nicht existiert.

### 3.2 RenameDependencyPolicy

```kotlin
internal interface RenameDependencyPolicy {
    // Core-local discriminator, not dev.dmigrate.driver.DatabaseDialect:
    // hexagon:core must remain dependency-free.
    val dialect: RenameProjectionDialect

    fun classifyTableRename(
        rename: RenameTableCandidate,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection

    fun classifyColumnRename(
        rename: RenameColumnCandidate,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection
}

internal enum class RenameProjectionDialect {
    POSTGRESQL,
    MYSQL,
    SQLITE,
}

internal data class RenameProjection(
    val automatic: List<DependencyRef>,           // engine handles it
    val explicit: List<DiffOperation>,            // we render extra ops
    val blockers: List<RenameProjectionBlocker>,  // mismatch beyond repair
)

internal data class RenameProjectionReport(
    val candidateId: String,
    val overlayEntryId: String,
    val renameOperationId: String?,      // null when the candidate fell back to Drop+Add
    val fallbackOperationIds: List<String>,
    val automatic: List<DependencyRef>,
    val explicit: List<ExplicitProjectionRef>,
    val blockers: List<RenameProjectionBlocker>,
)

internal data class ExplicitProjectionRef(
    val kind: String,
    val path: List<String>,
    val operationId: String,
)

internal data class RenameProjectionCapabilities(
    val source: RenameCapabilitySource,    // FILE_ONLY, LIVE_TARGET, TEST_PINNED
    val sqliteVersion: String? = null,
    val sqliteLegacyAlterTable: Boolean? = null,
    val mysqlServerFamily: String? = null, // mysql, mariadb, unknown
    val mysqlVersion: String? = null,
)

internal enum class RenameCapabilitySource {
    FILE_ONLY,
    LIVE_TARGET,
    TEST_PINNED,
}

internal data class DependencyRef(
    val kind: String,        // "FK", "VIEW", "TRIGGER", "INDEX", "DEFAULT", ...
    val path: List<String>,  // ["orders", "fk_orders_user"] etc.
    val rationale: String,   // why this is engine-automatic
)

internal data class RenameProjectionBlocker(
    val code: String,        // e.g. RENAME_DEPENDENCY_UNPROJECTABLE
    val operationId: String, // the rename operation that triggered
    val message: String,
)
```

`RenameCapabilitySource` ist der gemeinsame core-lokale Capability-
Carrier fuer alle F.4-Rename-Entscheidungen. Der View-/Trigger-/Routine-
Rename-Slice nutzt denselben Typ oder fuehrt ihn zuerst ein; zwei
gleichnamige, semantisch fast gleiche Capability-Enums duerfen nicht in
`hexagon:core` entstehen.

Die String-Felder in `RenameProjectionCapabilities` sind nur der
Transportvertrag an der Application-/Core-Grenze. Die Policy darf keine
lexikographischen Stringvergleiche ausfuehren; sie parst Versionen in einen
kleinen internen Typ (z.B. `major/minor/patch + suffix`) und klassifiziert
unparsebare Werte konservativ als unbekannte Capability. Tests muessen
insbesondere `3.9` vs. `3.26`, `3.26.0`, `8.0.30`, MariaDB-Suffixe und
leere/krumme Serverstrings pinnen, damit runtime-abhaengige Auto-Projection
nicht durch Stringsortierung falsch freigeschaltet wird.

### 3.3 Dialekt-Matrix (erste Annahmen, im Slice gegen Doku validieren)

| Dependency-Klasse        | PostgreSQL                 | MySQL 8.0+                  | SQLite 3.26+        |
| ------------------------ | -------------------------- | --------------------------- | ------------------- |
| FK auf Tabellen-Rename   | AUTOMATIC_BY_ENGINE        | AUTOMATIC_BY_ENGINE, wenn kein Constraint-Namenskonflikt erkennbar ist | AUTOMATIC_BY_ENGINE nur bei gepinnter Version + `legacy_alter_table=OFF` |
| FK auf Spalten-Rename    | AUTOMATIC_BY_ENGINE        | AUTOMATIC_BY_ENGINE, wenn Engine-Preconditions erfuellt sind | AUTOMATIC_BY_ENGINE nur bei gepinnter Version + `legacy_alter_table=OFF` |
| View-Body-Referenzen     | AUTOMATIC_BY_ENGINE nur mit verifizierter Modell-Provenance; sonst NO_PROJECTION_AVAILABLE oder EXPLICIT_REPROJECTION | EXPLICIT_REPROJECTION (View muss DROP/CREATE) | AUTOMATIC_BY_ENGINE nur bei gepinnter Version + `legacy_alter_table=OFF` |
| Trigger-Body-Referenzen  | NO_PROJECTION_AVAILABLE, solange Trigger-Bodies opake Strings sind | EXPLICIT_REPROJECTION nur als Drop/Create aus Soll-Body, kein Body-Rewrite | AUTOMATIC_BY_ENGINE nur bei gepinnter Version + `legacy_alter_table=OFF` |
| Index-Definition         | AUTOMATIC_BY_ENGINE (Spalten- und Tabellen-IDs sind oid-basiert) | AUTOMATIC_BY_ENGINE | AUTOMATIC_BY_ENGINE |
| Default-Expression mit Spaltenname | NO_PROJECTION_AVAILABLE (Function-Body opaque) | NO_PROJECTION_AVAILABLE | NO_PROJECTION_AVAILABLE |
| Sequence-Ownership `OWNED BY` | OUT_OF_SCOPE_UNTIL_OWNED_BY_MODEL | n/a | n/a |
| Routine-Body-Referenzen  | NO_PROJECTION_AVAILABLE (Plan-vorbedingung Workstream G) | NO_PROJECTION_AVAILABLE | NO_PROJECTION_AVAILABLE |

Die Matrix ist eine Annahme — Slice-Start braucht eine Spec-/Doku-
Bestaetigung pro Zelle, weil Dialekt-Verhalten zwischen Major-
Versionen wechseln kann (insbesondere SQLite `legacy_alter_table`,
MySQL `view-track-by-name`).

Qualifizierungen, die in der Implementierung als Policy-Input landen
muessen:

- MySQL-FK-Projektion ist nur `AUTOMATIC_BY_ENGINE`, wenn die Engine den
  Rename ohne Constraint-Namenskonflikt akzeptiert. MySQL aktualisiert
  FK-Namen in bestimmten Faellen automatisch, dokumentiert aber auch
  Konflikte, in denen `RENAME TABLE` fehlschlaegt. Datei-zu-Datei kann
  diesen Live-Konflikt nur konservativ abschaetzen; unsichere Faelle
  klassifizieren als `NO_PROJECTION_AVAILABLE` und fallen auf Drop+Add
  zurueck. Ein Execute-Preflight darf nur eine vor `plan()` mit
  `LIVE_TARGET` belegte Capability bestaetigen oder bei Drift blockieren;
  er darf keinen unsicheren Datei-/Schema-only-Fall nachtraeglich als
  Rename freischalten.
- SQLite-Tabellen-/Spalten-Rename ist nur fuer die gepinnte
  Mindestversion und `legacy_alter_table = OFF` automatisch. Ist der
  Runtime-Modus unbekannt oder legacy aktiv, blockiert die Policy
  Trigger-/View-/FK-Projektion statt sie als automatisch anzunehmen.
- PostgreSQL-View-Abhaengigkeiten duerfen nur dann als automatisch gelten,
  wenn die Abhaengigkeit im Modell ausreichend nachweisbar ist und eine
  vertrauenswuerdige Provenance hat (z.B. ein Reader, der katalogseitige
  Dependencies verlustfrei in `ViewDefinition.dependencies` transportiert).
  Dieser Slice fuehrt keine zusaetzliche Live-`pg_depend`-Pruefung im Planner
  aus. Datei-/Schema-only Inputs ohne verifizierte Dependency-Provenance und
  Views mit opakem Query-Body gelten daher nicht automatisch sicher; die Policy
  klassifiziert sie konservativ als `NO_PROJECTION_AVAILABLE` oder nutzt nur
  einen expliziten Drop/Create-Reprojection-Pfad aus dem Soll-Body. Trigger-,
  Routine- und Function-Bodies bleiben dagegen `NO_PROJECTION_AVAILABLE`,
  solange sie im neutralen Modell opake Strings sind: PostgreSQL kann zwar
  Objekt-Identitaeten katalogseitig nachziehen, aber ein textueller Body mit
  hart codierten Namen ist ohne Parser-/Body-Vertrag nicht beweisbar sicher.
  Opaque Function-/Default-Ausdruecke bleiben ebenfalls blockierend.

### 3.3a Capability-Beschaffung und Plan-Zeitpunkt

`DiffPlanner.plan(...)` erhaelt zusaetzlich zum core-lokalen Dialekt
eine `RenameProjectionCapabilities`-Instanz. Der Application-Layer fuellt
sie wie folgt:

- Datei-zu-Datei: `source = RenameCapabilitySource.FILE_ONLY`;
  Versionen/PRAGMAs bleiben `null`. Policies muessen daraus konservative
  Entscheidungen ableiten.
- DB-Target ohne Execute: nur Informationen verwenden, die der bestehende
  Loader/Reader bereits verlustfrei liefert. Keine zusaetzliche Live-
  Mutation oder Sniffing-Queries in diesem Slice.
- Execute-Pfad: Wenn eine runtime-abhaengige `AUTOMATIC_BY_ENGINE`-
  Entscheidung genutzt werden soll, muss der Runner die noetigen
  read-only Capability-Probes **vor** dem ersten `plan()` ausfuehren und
  `source = RenameCapabilitySource.LIVE_TARGET` an den Planner geben. Nach
  dem Planen duerfen Preflights nur noch die bereits im Plan verwendeten Capabilities
  validieren und bei Drift/Unsicherheit blockieren. Kein Re-Planning
  nach Overlay-Preflight, SQLite-Probe oder Cast-Preflight in diesem
  Slice.
- Tests: `RenameCapabilitySource.TEST_PINNED` erlaubt gezielte Matrix-
  Pfade ohne einen echten Server, muss aber als Test-only Input sichtbar
  bleiben.

Ohne diese Capabilities darf keine Policy-Zelle aus §3.3 als
`AUTOMATIC_BY_ENGINE` gelten, wenn sie von Version, PRAGMA oder Server-
Variante abhaengt.

Konkrete Runner-Aenderung: `SchemaMigrateRunner.execute` berechnet nach
Operand-Load, Normalisierung, Validierung, Dialekt-Aufloesung und
Comparator-Lauf zuerst die erwarteten Fingerprints und fuehrt den zentralen
planunabhaengigen Overlay-Gate aus. Dieser Gate prueft File-/Inline-Overlays
inklusive dokumentlokaler und Cross-Document-Rename-Mapping-Blocker **vor**
dem ersten `DiffPlanner.plan(...)`; bei Blockern entsteht ein Pre-Plan-
Blocker-Result ohne Operationenliste. Erst danach berechnet der Runner eine
`RenameProjectionCapabilities`-Instanz und uebergibt sie beim ersten und
einzigen `DiffPlanner.plan(...)`-Aufruf. Read-only Capability-Probes, die fuer
runtime-abhaengige Rename-Projection-Entscheidungen benoetigt werden
(z.B. SQLite-Version/`legacy_alter_table`), laufen ebenfalls vor diesem
Plan-Aufruf. Nachgelagerte Pre-Render-Stages wie SQLite-Cast-Preflights
duerfen nur noch den geplanten Vertrag validieren oder blockieren; sie duerfen
den Plan nicht fachlich umklassifizieren.

### 3.4 Mischfall-Delta-Synthese

Die zentrale Neuerung gegenueber dem Rendering-Slice ist nicht nur
Dependency-Klassifikation, sondern auch das Erzeugen der normalen
Schema-Deltas fuer ein Objekt, das im `SchemaDiff` wegen geaendertem
Namen nicht als `changed` auftaucht.

Beispiel Tabellen-Rename mit Zusatzspalte:

```
current.tables["users_old"] = (id, email)
desired.tables["users"]     = (id, email, created_at)
SchemaDiff                  = tablesRemoved[users_old] + tablesAdded[users]
```

Ohne zusaetzliche Synthese gaebe es nach der Rename-Faltung nur
`RenameTable(users_old -> users)` und die Spalte `created_at` ginge im
Plan verloren. Der Mapper muss deshalb:

1. Das Overlay-Paar `users_old -> users` aufloesen.
2. Quelle und Ziel unter einem gemeinsamen logischen Namen vergleichen
   (fuer Tabellen ueber denselben Detailvergleich wie `TableComparator`;
   fuer Spalten ueber einen re-basierten `ColumnDefinition`-Vergleich).
3. Aus dem Detail-Diff normale Operationen mit Ziel-ObjektRefs erzeugen.
4. Jede dieser Operationen mit `dependencies += rename.id` versehen, weil
   sie erst nach dem nativen Rename ausgefuehrt werden darf.
5. Dieselben Operationen in den Drop+Add-Fallback NICHT doppelt emittieren;
   im Fallback bleibt die heutige `Drop* + Create*`-Recreation der
   vollstaendige Zielzustand.

Fuer Spalten-Renames gilt analog: Ein Paar
`DropColumn(old_name) + AddColumn(new_name)` mit Typ-/Default-Drift wird
zu `RenameColumn(old_name -> new_name)` plus den synthetischen
`AlterColumnType`/`AlterColumnDefault`/`AlterColumnNullability`-Ops fuer
`new_name`. Ohne diesen Schritt wuerde die Zusatzänderung entweder
verloren gehen oder faelschlich weiter als Drop+Add laufen.

### 3.5 Mapper-Integration

`OperationMapper` darf nicht mehr alleine entscheiden, ob ein Rename
gerendert wird. Stattdessen liefert der Mapper fuer jedes passende
Overlay-Paar ein `RenamePlanningItem`: Candidate plus die Drop+Add-
Operationen, die heute beim Strukturmismatch entstehen wuerden, plus
die synthetischen Delta-Operationen fuer den Rename-Erfolgsfall. Der
Projector ersetzt jedes Item deterministisch durch eine von drei
Varianten:

- Rename plus synthetische Intra-Object-Delta-Ops, aber ohne explizite
  Dependency-Reprojection (`AUTOMATIC_BY_ENGINE` only, inklusive
  bestaetigter Capabilities falls die Dialektzelle runtime-abhaengig ist).
- Rename plus synthetische Delta-Ops plus explizite Dependency-Folge-
  Operationen (`EXPLICIT_REPROJECTION`).
- Drop+Add-Fallback plus `DiffDiagnostic`, wenn mindestens ein
  Rename-Candidate-Blocker existiert. "Blocker" meint hier: der
  Projector blockiert die Rename-Faltung, nicht zwingend die ganze
  Migration. Solange die `fallbackOperations` vollstaendig und
  renderbar sind, bleibt die Diagnose eine `WARNING` und der bestehende
  Drop+Add-Pfad uebernimmt. Ein echter `BLOCKER` entsteht nur, wenn auch
  der Fallback nicht verlustfrei geplant oder gerendert werden kann.

```kotlin
internal data class RenameTableCandidate(
    val id: String,
    val fromName: String,
    val toName: String,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    val structurallyEqual: Boolean,
)

internal data class RenamePlanningItem(
    val candidate: RenameTableCandidate,
    val postRenameDeltaOperations: List<DiffOperation>,
    val fallbackOperations: List<DiffOperation>,
)
```

Wenn der Projector `RenameProjection(blockers = nonEmpty)` zurueckgibt,
nimmt der Projector die `fallbackOperations` in die Operationenliste
auf (gleiches Verhalten wie heute beim Strukturmismatch). Die Projector-
Blocker werden als `DiffDiagnostic` weitergereicht:

- `WARNING`, wenn Drop+Add den Zielzustand vollstaendig erreicht;
- `BLOCKER`, wenn kein verlustfreier Fallback existiert oder der
  Fallback selbst nicht renderbar ist.

Dadurch ist `NO_PROJECTION_AVAILABLE` kein automatischer Exit-8-Fall.
Der Normalfall lautet: Rename nicht falten, Warnung ausgeben, Drop+Add
planen. Nur echte Fallback-Luecken blockieren die Migration.

Wenn der Projector `RenameProjection(explicit = nonEmpty)` zurueckgibt,
emittiert der Projector den Rename plus `postRenameDeltaOperations` plus
die Dependency-Folge-Operationen mit deterministischer Dependency-Bindung
(`candidate.id`/`rename.id` als `dependencies`-Eintrag in jeder Folge-
Operation). Der anschliessende ID-Finalisierungsschritt muss entweder
keine IDs mehr veraendern oder alle Dependency-Referenzen atomar
mitziehen.

### 3.6 Plan- und Report-Vertrag

`RenameDependencyProjector.project(...)` liefert neben der finalen
Operationenliste eine strukturierte Liste von `RenameProjectionReport`-
Eintraegen. `DiffPlanner.plan(...)` speichert diese Liste in einem neuen
optionalen Feld `DiffResult.renameProjections: List<RenameProjectionReport> =
emptyList()`. `SchemaMigrateReportBuilder` liest ausschliesslich dieses
Feld fuer die Reportausgabe; es darf keine `renameProjection`-Daten aus
Diagnostics, Operation-IDs oder Renderer-Nebenwirkungen ableiten.

Der Report ist an den Candidate gebunden. Bei erfolgreicher Faltung zeigt
`renameOperationId` auf die finale `Rename*`-Operation und
`fallbackOperationIds` ist leer. Bei `NO_PROJECTION_AVAILABLE` oder einem
anderen Projector-Blocker, der verlustfrei auf Drop+Add zurueckfaellt, bleibt
`renameOperationId = null`; `fallbackOperationIds` enthaelt dann die
tatsaechlich geplanten Drop+Add-Operationen. Damit bleibt auch ein
abgelehnter Rename maschinenlesbar sichtbar, ohne auf eine nicht existierende
finale Rename-Operation zu verweisen.

`overlayEntryId` ist Pflicht im Report und im Operation-Payload. Reports
rekonstruieren Entry-Provenance nicht aus Operation-ID-Konventionen,
Mapping-Reihenfolge oder `overlayHash`, weil mehrere Eintraege denselben
Overlay-Hash teilen.

Damit bleibt der Datenfluss eindeutig:

```
RenameDependencyProjector
   -> ProjectorResult(operations, diagnostics, renameProjections)
   -> DiffResult.renameProjections
   -> SchemaMigrateReport.renameProjections
```

`renameProjections` ist der gemeinsame F.4-Carrier fuer Rename-Projection
und Rename-Provenance. Falls der View-/Trigger-/Routine-Rename-Slice spaeter
Drop+Create-Fallbacks mit `RenameProvenance` modelliert, muss er denselben
Carrier erweitern oder auf ihn referenzieren; er darf keinen zweiten
Report-/Artefaktabschnitt einfuehren, der dieselbe Operator-Entscheidung
parallel beschreibt.

Der Migrate-Report erhaelt einen optionalen
`renameProjections`-Abschnitt:

```json
{
  "renameProjections": [
    {
      "candidateId": "rename-table-users",
      "overlayEntryId": "rename-table-users",
      "renameOperationId": "rename-table-users",
      "fallbackOperationIds": [],
      "automatic": [
        {"kind": "FK", "path": ["orders", "fk_orders_user"], "rationale": "..."}
      ],
      "explicit": [
        {"kind": "VIEW", "path": ["v_user_email"], "operationId": "create-view-v_user_email-postrename"}
      ],
      "blockers": []
    }
  ]
}
```

Damit kann der Operator vor dem Execute pruefen, welche Dependencies
die Engine uebernimmt und welche d-migrate explizit re-rendert.

Artefakt-Gate: Dieser Slice darf `renameProjections` nicht stillschweigend in
`migration-plan.v1` schreiben, wenn alte Consumer das Feld ignorieren koennen
und dadurch einen Rename-Mischfall ohne Projection-Vertrag ausfuehren
wuerden. Falls das Feld im selben Slice in Plan-Artefakte aufgenommen wird,
muessen `migration-plan.v1`-Codec, Validator, Golden-Files und Compat-Tests
entweder ein bekanntes versioniertes Feld pinnen oder ein
`requiredFeatures`/`semanticExtensions`-Gate setzen. Falls das nicht geleistet
wird, bleibt die Artefaktserialisierung unveraendert und der Report ist der
einzige oeffentliche Carrier fuer `renameProjections`.

## 4. Akzeptanzkriterien

- [ ] `RenameDependencyProjector` ist in `hexagon:core` implementiert
      und ist die einzige Stelle, die `RenameTable`/`RenameColumn`-
      Operationen freigibt.
- [ ] Pro Dialekt existiert eine `RenameDependencyPolicy`-Implementierung
      (`PostgresRenameDependencyPolicy`, `MysqlRenameDependencyPolicy`,
      `SqliteRenameDependencyPolicy`) inkl. Doku-Verweis zur jeweiligen
      Engine-Garantie.
- [ ] `DiffPlanner.plan(...)` bzw. die Mapper-/Projector-Grenze konsumiert
      einen core-lokalen `RenameProjectionCapabilities`-Input; Datei-zu-
      Datei nutzt konservative Defaults und behauptet keine runtime-
      abhaengige Auto-Projection.
- [ ] Runtime-abhaengige Capabilities fuer Execute werden, falls genutzt,
      vor dem einzigen `DiffPlanner.plan(...)`-Aufruf erhoben. Nachgelagerte
      Preflights validieren oder blockieren nur; sie planen keinen Rename
      nachtraeglich um.
- [ ] Der zentrale planunabhaengige Overlay-Gate laeuft vor dem einzigen
      `DiffPlanner.plan(...)`-Aufruf. Rename-Mapping-Blocker aus File-/Inline-
      Overlays erzeugen ein Pre-Plan-Blocker-Result ohne Operationenliste;
      der Projector sieht nur Overlays, die diesen Gate bestanden haben.
- [ ] PostgreSQL-View-Dependencies werden nur dann als
      `AUTOMATIC_BY_ENGINE` klassifiziert, wenn `ViewDefinition.dependencies`
      aus einer vertrauenswuerdigen Modell-Provenance stammt. Datei-/Schema-only
      Views ohne solche Provenance oder mit nur opakem Query-Body fallen auf
      `NO_PROJECTION_AVAILABLE` bzw. explizite Drop/Create-Reprojection zurueck.
- [ ] Rename-Mischfaelle verlieren keine fachlichen Deltas: Tests decken
      mindestens Tabellen-Rename + Zusatzspalte, Tabellen-Rename +
      Index-/Constraint-Änderung und Spalten-Rename + Typ-/Default-
      Änderung ab. Der Plan enthaelt jeweils `Rename*` plus die korrekten
      Zielnamen-Delta-Operationen mit Dependency auf die finale Rename-ID.
- [ ] `EXPLICIT_REPROJECTION`-Faelle erzeugen deterministisch geordnete
      Folge-Operationen mit `dependencies = setOf(rename.id)`; die
      bestehende Topo-Sort haengt sie sauber nach dem Rename ein.
- [ ] Bestehende Planner-Safety-Pässe laufen nach der Projection auf der
      finalen Operationenliste: Tests decken mindestens einen
      Spalten-Rename + synthetisches `AlterColumnType` ab, der wegen
      View-Column-Dependencies denselben G.3/F.6.b/G.2-Schutz ausloest
      wie ein normal gemappter Column-Alter.
- [ ] `--generate-rollback` ist fuer Rename-Mischfaelle gepinnt:
      Rename plus synthetische Delta-Operationen erzeugt einen vollstaendigen
      inversen Down-Plan, Rename plus `EXPLICIT_REPROJECTION` erzeugt alle
      noetigen inversen Folge-Operationen, und nicht rekonstruierbare
      alte Bodies/Dependencies blockieren mit `ROLLBACK_NOT_POSSIBLE`.
- [ ] ID-Disambiguierung und Dependency-Referenzen sind gemeinsam
      stabil: Tests decken mindestens einen ID-Kollisionsfall ab und
      pruefen, dass Folge-Operationen auf die finale Rename-ID zeigen.
- [ ] Planner-Rewrites nach dem Projector remappen Report- und
      Dependency-Referenzen atomar: Ein Test deckt eine explizite
      View-Reprojection ab, die durch `splitReplaceViewsForColumnConflicts`
      in `DropView`/`CreateView` aufgespalten wird, und prueft, dass
      `renameProjections.explicit[].operationId` nur finale
      `DiffResult.operations`-IDs referenziert.
- [ ] `overlayEntryId` wird fuer erfolgreiche Faltungen und Drop+Add-
      Fallbacks in Candidate, `RenameTable`/`RenameColumn` und
      `renameProjections` transportiert. Tests decken ein Overlay mit
      mehreren Rename-Mappings ab und pruefen, dass der Report den
      konkreten Entry nicht aus `overlaySource + overlayHash` ableitet.
- [ ] `NO_PROJECTION_AVAILABLE`-Faelle blockieren die Rename-Faltung und
      diagnostizieren `RENAME_DEPENDENCY_UNPROJECTABLE` mit konkretem
      `path`-Verweis. Wenn die `fallbackOperations` vollstaendig sind,
      ist diese Diagnose eine `WARNING`, der Drop+Add-Fallback uebernimmt
      und der bestehende Strukturmismatch-Pfad bleibt aktiv. Nur wenn
      auch der Fallback nicht verlustfrei planbar/renderbar ist, wird aus
      demselben Code ein `BLOCKER`.
- [ ] Pro Dialekt ein Positivpfad fuer Rename + automatische
      Engine-Projection (z.B. Postgres Tabellen-Rename mit FK), ein
      Positivpfad mit EXPLICIT-Folge-Op (z.B. MySQL Tabellen-Rename
      mit View-Body), ein Rename-Faltungs-Blocker mit Drop+Add-Fallback
      (z.B. Default-Expression mit Spaltenname) und ein echter
      Migration-Blocker, falls der Fallback nicht vollstaendig
      rekonstruierbar ist.
- [ ] SQLite-/MySQL-Automatiktests pinnen die noetigen Capabilities
      explizit; unbekannte Version/PRAGMA/Serverfamilie wird in einem
      separaten Test konservativ blockiert.
- [ ] Version-/Serverfamily-Gates nutzen einen getesteten Parser statt
      lexikographischer Stringvergleiche. Tests decken mindestens
      `3.9 < 3.26`, `3.26.0`, `8.0.30`, MariaDB-Suffixe und unparsebare
      Werte ab; unparsebare Werte gelten als unbekannt und blockieren
      runtime-abhaengige Auto-Projection.
- [ ] `renameProjections`-Reportbeispiel pinnt JSON-Struktur und
      Feldreihenfolge.
- [ ] `spec/cli-spec.md` §6.1 dokumentiert den optionalen
      `renameProjections`-Abschnitt des Migrate-Reports inklusive
      Fallback-Fall (`renameOperationId = null`) und Entry-Provenance.
- [ ] `renameProjections` deckt sowohl erfolgreiche Faltungen als auch
      Drop+Add-Fallbacks ab: Fallback-Eintraege tragen `candidateId`,
      `renameOperationId = null`, konkrete `fallbackOperationIds` und die
      Projector-Blocker, ohne eine nicht existierende Rename-Operation zu
      referenzieren.
- [ ] `DiffResult` traegt `renameProjections` als strukturiertes
      Planfeld; `SchemaMigrateReportBuilder` liest diesen Carrier und
      rekonstruiert die Reportdaten nicht aus Diagnostics oder
      Operation-ID-Konventionen.
- [ ] Wenn `renameProjections` in `migration-plan.v1` serialisiert wird,
      behandelt der Slice das Feld als versionierte Semantik: JSON-Codec,
      Validator, Golden-Files und Compat-Tests pinnen entweder ein bekanntes
      Feld oder ein `requiredFeatures`/`semanticExtensions`-Gate. Ohne diesen
      Artefakt-Gate bleibt `migration-plan.v1` unveraendert und nur der
      Migrate-Report enthaelt den neuen Abschnitt.
- [ ] Default-Expression-Tests decken das aktuelle Modell-Limit ab:
      `DefaultValue.FunctionCall` in der betroffenen Rename-Umgebung
      blockiert konservativ, solange kein explizites Dependency-Feld
      fuer Default-Argumente/Raw-Expressions existiert.
- [ ] `roadmap.md` und `diffresult-migration-plan-2.md §10 F.4`
      bekommen einen Status-Update mit Datum des Slice-Abschlusses.

## 5. Definition of Done

- [ ] Alle Akzeptanzkriterien aus §4 erfuellt.
- [ ] `make docker-test` gruen, Output in `/tmp/build.log`.
- [ ] Coverage je betroffenem Modul ≥ 90% (CI-Flake-Toleranz beachten).
- [ ] Dialekt-Matrix-Annahmen (§3.3) sind gegen aktuelle Dialekt-Doku
      validiert und Quellen sind im Code als `// see <link>` zitiert.
- [ ] Plan-Datei nach `docs/planning/done/` verschoben.

## 6. Risiken und Carve-outs

### 6.1 Dialekt-Verhalten zwischen Versionen

SQLite `legacy_alter_table` aendert ab 3.26 ob Trigger-/View-Bodies
automatisch reprojiziert werden. MySQL behandelt View-Track-by-Name
je nach Server-Variant unterschiedlich. PostgreSQL `pg_depend` haengt
von Catalog-Permissions ab.

**Mitigation**: Policy-Implementierungen pinnen die Mindestversion
explizit. Faelle ausserhalb der pinned Versionsmatrix klassifizieren
als `NO_PROJECTION_AVAILABLE`, statt eine schwaechere Garantie
anzunehmen.

### 6.2 View-/Routine-/Trigger-Body-Rewriting bleibt offen

Wenn ein View-Body `FROM users_old` enthaelt und die Engine das nicht
automatisch nachzieht (MySQL bestimmte Versionen), kann d-migrate den
Body nicht selbstaendig umschreiben — das wuerde einen SQL-Parser
brauchen. Der Slice klassifiziert solche Faelle als
`EXPLICIT_REPROJECTION` mit der Konsequenz, dass die View ueber
`DropView` + `CreateView` aus dem Soll neu gerendert wird (Soll-Body
zeigt schon auf `users`). Das funktioniert nur, wenn die Soll-Seite
einen Body fuer die View liefert; sonst `NO_PROJECTION_AVAILABLE`.

### 6.3 Default-Expressions mit Spaltenreferenzen

Postgres-Defaults wie `DEFAULT some_fn(other_column)` sind
opake Expressions. Das aktuelle neutrale Modell kann Argumente solcher
Funktions-Defaults nicht ausdruecken: `DefaultValue.FunctionCall` traegt
nur den Funktionsnamen, keine Argumentliste oder Raw-SQL-Expression. Wenn
eine Spalte umbenannt wird und eine betroffene Tabelle opake FunctionCall-
Defaults besitzt, kann der Planner daher nicht erkennen, ob die Default-
Expression die alte Spalte referenziert.

Erste Iteration: konservativ blockieren mit
`RENAME_DEPENDENCY_UNPROJECTABLE`, sobald ein `DefaultValue.FunctionCall`
in der betroffenen Rename-Umgebung vorkommt und die Policy keine
vollstaendige Default-Dependency-Projektion nachweisen kann. Praezisere
Unterscheidung zwischen unkritischem `now()` und
`some_fn(other_column)` braucht einen eigenen Modellvertrag fuer
Default-Argumente/Raw-Expressions oder ein SQL-Parser-basiertes
Dependency-Feld.

### 6.4 Sequence-Ownership im Spalten-Rename

`OWNED BY users.id` bleibt nach `RenameColumn id → user_id` ein
relevanter Folgefall, aber das aktuelle neutrale Modell kann ihn nicht
verlustfrei ausdruecken: `SequenceDefinition` besitzt kein `ownedBy`,
und owned/implizite Sequenzen werden ueber `ColumnGeneration.sequenceName`
modelliert statt als standalone `sequences:`-Objekt. Dieser Slice darf
daher keine synthetische `ALTER SEQUENCE ... OWNED BY ...`-Operation
erfinden. Voraussetzung fuer diesen Pfad ist ein eigener
Sequence-Ownership-Slice, der `ownedBy` oder ein gleichwertiges Modellfeld
einfuehrt und Comparator, Fingerprint, Renderer und Rollback-Vertrag
pinnt. Bis dahin klassifiziert die Policy erkannte Ownership-Faelle als
`NO_PROJECTION_AVAILABLE`.

### 6.5 RENAME_MAPPING_INVALID-Enum-Wert

Plan-2 §10 sieht diesen Reason-Wert vor. Heute mappen wir auf
`MANUAL_ACTION_REQUIRED`/`ROLLBACK_NOT_POSSIBLE`. Dieser Slice fuegt
den Enum-Wert NICHT hinzu — das wird ein eigener kleiner Vertragsslice,
sobald Renderer den feineren Reason konsumieren koennen.

## 7. Test-Strategie

- **Mapper-Test**: `RenameDependencyProjectorTest` mit synthetischen
  Schemas pro Dialekt; deckt alle drei Klassifikationsbuckets und die
  deterministische Sortierung der Folge-Operationen.
- **Renderer-Tests**: pro Dialekt je ein End-to-End-Test, der einen
  Rename mit EXPLICIT-Folge-Operationen rendert und prueft, dass
  Rename und Folge-Statements in der richtigen Reihenfolge im
  Statement-Stream landen.
- **Report-Test**: pinnt das JSON-Schema des `renameProjections`-
  Abschnitts; Golden-File falls vorhanden.
- **Regressionstest**: die existierenden F.4-Rendering-Tests bleiben
  unveraendert gruen — `structurellyEqual=true`-Pfad bleibt
  abgrenzbar von Mischfall-Pfaden.

## 8. Out-of-Scope-Verweis

Der bestehende Strukturmismatch-Fallback aus dem Rendering-Slice
bleibt aktiv. Dieser Slice oeffnet zusaetzlich Mischfall-Pfade —
er ersetzt den Drop+Add-Fallback nicht, sondern macht ihn fuer einen
groesseren Teil der Faelle vermeidbar.
