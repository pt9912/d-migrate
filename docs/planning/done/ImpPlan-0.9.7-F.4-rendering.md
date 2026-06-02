# Implementierungsplan: 0.9.7 — F.4 Rename-Rendering

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.4 (zweiter Slice — Rendering)
> **Status**: ✅ abgeschlossen (2026-05-14)
> **Vorbedingung**: F.0 Overlay-Grundvertrag ✅, F.4 Overlay-Vertragsslice ✅
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md` §10 F.4

---

## 1. Ziel

Den Rename-Overlay-Vertrag aus dem ersten F.4-Slice in renderbare
Migrationen ueberfuehren. Der erste Slice hat die Overlay-Eingabe,
Fingerprint-Bindung, Eindeutigkeits- und Kettenpruefung implementiert.
Dieser Slice fuegt die zweite Schicht hinzu: konkrete `RenameTable`-
und `RenameColumn`-Operationen, die der Planner aus passenden
Drop+Add-Paaren plus aktivem Overlay zusammenfaltet, plus Renderer
fuer PostgreSQL, MySQL und SQLite.

## 2. Scope

In Scope:

- Zwei neue `DiffOperation`-Subtypes: `RenameTable`, `RenameColumn`.
- `OperationMapper`-Erweiterung: konsumiert `RenameMappingOverlayEntry`-
  Liste, paart Drop+Add zu Rename, validiert strukturelle Gleichheit.
- Drei Renderer: PostgreSQL, MySQL, SQLite.
- Up- und Down-Pfad (Down ist inverser Rename — automatisch reversibel).
- Tests pro Dialekt: positiver Tabellen-Rename, positiver Spalten-Rename,
  Up+Down Round-Trip, Blocker bei strukturell inkompatiblem Rename.

Aus Scope:

- Dependency-Re-Projection fuer Indizes/FKs/Defaults auf alte Namen
  (im ersten Rendering-Slice gilt: nur Rename ohne weitere Aenderungen
  ist renderbar; Mischfaelle bleiben Blocker oder fuehren zur
  Drop+Add-Fallback-Pfad).
- View-/Trigger-/Routine-Renames (Vorbedingung Workstream G).
- CLI-Flag fuer Inline-Overlay; nur die bestehende
  `--migration-overlay`-Pipeline.
- Live-Pruefung des Rename-Targets (Datei-zu-DB ist nicht in diesem
  Slice).

## 3. Vertrag

### 3.1 DiffOperation-Subtypes

```kotlin
data class RenameTable(
    override val id: String,
    override val objectRef: DiffObjectRef,    // path = [newName]
    val fromName: String,
    val toName: String,
    val overlaySource: String,                // Datei-/Quellenangabe
    val overlayHash: String?,                 // Fingerprint-Bindung
    override val phase: DiffPhase = DiffPhase.TABLES,
    override val dependencies: Set<String> = emptySet(),
    override val reversibility: Reversibility = Reversibility.AUTOMATIC,
    override val risks: OperationRisks = OperationRisks(
        up = OperationRisk.SAFE,
        down = OperationRisk.SAFE,
    ),
) : DiffOperation
```

```kotlin
data class RenameColumn(
    override val id: String,
    override val objectRef: DiffObjectRef,    // path = [tableName, newColumnName]
    val fromName: String,
    val toName: String,
    val overlaySource: String,
    val overlayHash: String?,
    override val phase: DiffPhase = DiffPhase.COLUMNS,
    override val dependencies: Set<String> = emptySet(),
    override val reversibility: Reversibility = Reversibility.AUTOMATIC,
    override val risks: OperationRisks = OperationRisks(
        up = OperationRisk.SAFE,
        down = OperationRisk.SAFE,
    ),
) : DiffOperation
```

### 3.2 OperationMapper-Konsumption

Bei aktivem `RenameMappingOverlayEntry` (objectType = `table` oder
`column`) prueft der Mapper:

1. Es gibt genau ein `tablesAdded` mit `name == toName` und genau ein
   `tablesRemoved` mit `name == fromName` (analog fuer Spalten innerhalb
   eines `tablesChanged`-Eintrags).
2. Das `before`- und `after`-Objekt sind strukturell gleich
   (Spalten/Constraints/Indizes/PK fuer Tabellen; Type/Default/Required
   fuer Spalten). Die Vergleichsbasis nutzt `CanonicalPayload`.
3. Erfuellt: Drop+Add werden nicht gemappt; stattdessen
   `RenameTable`/`RenameColumn` mit dem Overlay-Hash.
4. Nicht erfuellt: Drop+Add bleiben; eine Warn-Diagnostic
   `RENAME_OVERLAY_STRUCTURAL_MISMATCH` listet die Differenz, der
   Renderer rendert weiterhin den Drop+Add-Fallback.

`structuralMismatch` ist eine Diagnostic, kein Blocker — der
Drop+Add-Pfad ist nicht plausibel weniger sicher als der
nicht-renderbare Rename. Der Operator entscheidet ueber Anpassung des
Schemas oder des Overlays.

### 3.3 Renderer

Alle drei Dialekte rendern Tabellen- und Spalten-Rename ueber
natives `ALTER TABLE ... RENAME`:

- PostgreSQL: `ALTER TABLE "old" RENAME TO "new"` /
  `ALTER TABLE "tbl" RENAME COLUMN "old" TO "new"`.
- MySQL 8.0+: identische Syntax (RENAME COLUMN seit 8.0.3).
- SQLite 3.25+: identische Syntax (RENAME COLUMN seit 3.25,
  RENAME TO seit 3.0).

Die Down-Renderung tauscht `fromName` und `toName` und nutzt sonst
identische Syntax. Da `Reversibility.AUTOMATIC` gilt, blockiert kein
Renderer den Down-Pfad.

Identifier-Quoting laeuft pro Dialekt ueber den jeweiligen
`Identifiers`-Helfer. Reservierte Worte und Sonderzeichen werden so
behandelt wie bei `CreateTable`/`AddColumn`.

### 3.4 Reversibility und Risiko

`AUTOMATIC` mit `OperationRisk.SAFE` auf beiden Seiten. Rename
verschiebt keine Daten und greift nicht in Constraints ein. Das
schliesst die F.4-Akzeptanz "automatisch nur der inverse Rename, wenn
die Up-Operation allein ein Rename ist" (Plan-2 §10) ein — sobald
Mischfaelle auftauchen, fallen wir auf Drop+Add zurueck und der
Reversibility-Vertrag dieser Operationen greift.

## 4. Akzeptanzkriterien

- [x] `DiffOperation.RenameTable`/`RenameColumn` existieren und sind
      Teil der `sealed interface DiffOperation`.
- [x] `OperationMapper` erzeugt aus passenden Drop+Add-Paaren plus
      gueltigem Rename-Overlay genau ein `RenameTable`- bzw.
      `RenameColumn`-Op statt der Drop+Add-Folge.
- [x] Strukturelle Inkompatibilitaet emittiert
      `RENAME_OVERLAY_STRUCTURAL_MISMATCH` als Warning und faellt auf
      Drop+Add zurueck.
- [x] PostgreSQL/MySQL/SQLite-Renderer rendern Up und Down als
      `ALTER TABLE ... RENAME ...` — Down ist der inverse Rename.
- [x] Pro Dialekt existieren Tests fuer Tabellen-Rename und
      Spalten-Rename, jeweils Up+Down Round-Trip.
- [x] Pro Dialekt existiert ein Blocker-Test fuer strukturelle
      Inkompatibilitaet (Drop+Add bleibt, Warn-Diagnostic ist
      gepinnt). (`Postgres/Mysql/SqliteDiffRenameTest` —
      "structural mismatch falls back to drop+create with warning,
      no RENAME rendered".)
- [x] Der Mapper-Test deckt die Overlay-Konsumption ab inkl.
      Operation-Collapsing und Hash-Propagation in die Operation.
- [x] `roadmap.md` und `diffresult-migration-plan-2.md §10 F.4`
      bekommen einen Status-Update auf 2026-05-14.

Nachgezogen nach Review (2026-05-14):

- [x] `RenameOverlayIndex` lehnt Cross-Table-Spaltenmappings
      (`users.old -> orders.new`) und Mixed-Qualification
      (`users.old -> new`) hart ab, statt den Qualifier zu verwerfen.
      Diagnostics `RENAME_OVERLAY_CROSS_TABLE_REJECTED` und
      `RENAME_OVERLAY_MIXED_COLUMN_QUALIFICATION` sind im
      `RenameOverlayMapperTest` gepinnt.

## 5. Definition of Done

- [x] Alle Akzeptanzkriterien aus §4 erfuellt.
- [x] `make docker-test` gruen, Output in `/tmp/build.log`.
- [x] Coverage je betroffenem Modul ≥ 90% (CI-Flake-Toleranz beachten).
      Letzter Lauf (2026-05-14, nach Analyzer-Fix für RenameTable
      als FK-Quelle): core 95.3%, postgres 93.4%, mysql 92.8%,
      sqlite 93.7%, application 91.1%. Gate-Verifikation via
      `make docker-coverage-gate` Exit 0.
- [x] Plan-Datei nach `docs/planning/done/` verschoben.

## 6. Carve-outs (Folgeslice)

Pro Carve-out existiert ein eigener Plan unter
`docs/planning/open/`:

- Dependency-Re-Projection (FK / View / Index / Default / Trigger
  auf neuen Namen): `ImpPlan-0.9.7-F.4-dependency-projection.md`.
  Heute blockiert der Mischfall ueber
  `RENAME_OVERLAY_STRUCTURAL_MISMATCH` und faellt auf Drop+Add
  zurueck — konservativ aber korrekt.
- View-/Trigger-/Routine-Renames (Vorbedingung Workstream G):
  `ImpPlan-0.9.7-F.4-routine-trigger-view-renames.md`.
- CLI-Inline-Overlay (statt nur Datei-Pfade):
  `ImpPlan-0.9.7-F.4-cli-inline-overlay.md`.
- `RENAME_MAPPING_INVALID` als eigener
  `MigrationBlockedReason`-Enum-Wert:
  `ImpPlan-0.9.7-F.4-rename-mapping-invalid-enum.md`.
