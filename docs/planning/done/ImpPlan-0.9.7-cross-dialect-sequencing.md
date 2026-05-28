# Implementierungsplan: 0.9.7 — Cross-Dialect Sequencing

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.3 architektonischer Schirm
> **Status**: ✅ Done (2026-05-27). Architektur-Plan harmonisiert die
>           drei bereits abgeschlossenen parallelen Slices retroaktiv.
>           Sub-Slices A ✅ (`c912386f`) + B.0 ✅ (`04a74225`) + B.1 ✅
>           (`cc8a2643`) + C ✅ (`387304b6`) + D ✅ (`e27c3164`) +
>           E (closing iter, dieser Commit).
> **Vorbedingung**: PG-Sequence-Diff-Renderer ✅; MySQL-Sequence-Diff
>                  *(done, `docs/planning/done/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`)*;
>                  preserveCurrentValue-Slice
>                  *(done, `docs/planning/done/ImpPlan-0.9.7-sequence-preserve-current-value.md`)*;
>                  SQLite-Sequence-Plan
>                  (`docs/planning/in-progress/sqlite-sequence-emulation-plan.md`, weiter offen).
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md` §E.3;
>             `docs/planning/done/mysql-sequence-emulation-plan.md`;
>             `docs/planning/in-progress/sqlite-sequence-emulation-plan.md`;
>             ADR-0003 (`docs/adr/0003-cross-dialect-sequencing.md`)
>             dokumentiert die fünf Decisions D1-D5 in binder Form.

---

## 1. Auslöser

Sequence-Migrationen leben in 0.9.7 als vier voneinander unabhängige
Slices:

- **PG** (E.3 Erstscheibe ✅): native `CREATE/ALTER/DROP/RENAME
  SEQUENCE`-DDL.
- **MySQL** (parallel-Plan): Emulation via `dmg_sequences`-Helper-
  Table und Sequence-Trigger.
- **SQLite** (parallel-Plan): rebuild-basierte Emulation; Details
  in `docs/planning/in-progress/sqlite-sequence-emulation-plan.md`.
- **preserveCurrentValue** (parallel-Plan): cross-dialect
  Live-DB-Probe + Setval-Pattern.

Was **fehlt** ist ein gemeinsamer architektonischer Vertrag, der
die folgenden Fragen entscheidet:

1. **Cross-Dialect-Transfer**: was passiert, wenn ein
   PG-Schema mit `CREATE SEQUENCE` nach MySQL transferiert wird?
  Heute: MySQL-Renderer blockiert mit einem Dialekt-Blocker;
   geplant ist die Feineinstellung auf sequence-spezifische Blocker-Codes
   nach der Einführung dieser Schicht.
   Nach den parallelen Plänen: MySQL emittiert die helper_table-
   Emulation — aber wie soll die Source-PG-Sequence-Definition
   überhaupt nach MySQL gemappt werden? Welche Attribute gehen
   verloren?

2. **Sequence-Default-Reprojection** (F.4 Sub-Slice D): wirkt heute
   nur für `RenameSequence`-Op. Soll sie auch für
   Cross-Dialect-Transfer wirken, wenn die Sequenz-Identität via
   Schema-Reader rekonstruiert wird?

3. **Sequence-Identität über Dialekte hinweg**: PG-`SequenceName`
   ist global im Schema; MySQL-Emulation nutzt `dmg_sequences.name`
   (auch global) plus einen Trigger pro Tabellen-Spalte; SQLite hat keine
   native Sequence, emuliert aber ebenfalls über `dmg_sequences.name`.
   Wer ist der Single Source of Truth?

4. **Capability-Matrix**: welche
   `SequenceDefinition`-Attribute überleben den Cross-Dialect-
   Transfer verlustfrei?
   - `start`, `increment`, `cycle`: PG + MySQL-Emulation plus
     SQLite-Helper-Table-Vertrag (`next_value`, `increment_by`,
     `cycle_enabled`); bei SQLite ist `start` nur als Seed-Zustand
     sinnvoll belegbar, nicht aber für Reverse nach Laufzeitnutzung.
   - `minValue` / `maxValue`: PG + MySQL-Emulation + SQLite-Helper-Table.
   - `cache`: PG `CACHE` ist ein Performance-Hint, MySQL hat
     kein direktes Analog (heute deklarativ in
     `dmg_sequences.cache_size` gefuehrt, aber semantisch ignoriert).
     SQLite speichert als `dmg_sequences.cache_size` nur als Metadatum
     (`W114`).
   - aktueller Wert: siehe preserveCurrentValue-Plan.

5. **Cross-Dialect-Rename**: ein `RenameSequence` auf PG ist nativ;
   auf MySQL ist es ein `UPDATE dmg_sequences`; auf SQLite
   blockiert (`docs/planning/done/ImpPlan-0.9.7-F.4-routine-trigger-view-renames.md`
   Sub-Slice A.2 Teil 1). Cross-Dialect: nicht definiert.

---

## 2. Warum jetzt?

Die parallelen MySQL- und preserveCurrentValue-Slices brauchen
eine konsistente Decision-Basis, sonst entstehen
Sub-Verträge, die später kollidieren. Der SQLite-Sequence-Plan
existiert seit längerem in `docs/planning/open/` ohne klare
Cross-Dialect-Anbindung. Ohne diesen Schirm-Plan landen drei
voneinander unabhängige Sequence-Implementierungen mit
inkompatiblen Annahmen.

Dieser Plan-Doc ist **kein Code-Plan** — er entscheidet die
gemeinsamen Architektur-Fragen und delegiert die Umsetzung
zurück an die drei dialect-spezifischen Plans. Das Ergebnis
sind dokumentierte Decision-Records (ADR-Style), die jeder
parallele Slice referenzieren kann.

---

## 3. Scope

### 3.1 In-Scope (Entscheidungen, die hier getroffen werden)

- **D1**: Sequence-Identität über Dialekte hinweg — der
  Sequenz-Name aus dem neutralen Modell ist der Single Source of
  Truth. Konkret ist `NamedSequence.name` (bzw. das gleichnamige Feld in
  `SequenceDiff`) die Identitäts-Schluesselstelle. Dialekt-spezifische
  Emulation (MySQL: `dmg_sequences.name`; SQLite:
  `dmg_sequences.name` im Helper-Table-Pfad) MUSS auf diesen Namen
  mappen, ohne ihn zu transformieren.
- **D2**: Cross-Dialect-Transfer-Vertrag —
  Source-Dialekt-Sequenzen werden über den neutralen Modellpfad
  gespiegelt. Wenn ein Attribut im Ziel-Dialekt nicht
  unterstützt wird (z.B. PG-Sequence nach SQLite, solange der
  SQLite-Sequence-Renderer fehlt), emittiert
  der Renderer standardmaessig
  `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT`.
  `cache` ist im `helper_table`-Pfad kein Blocker: MySQL speichert
  den Wert heute als Metadatum und emittiert defaultmaessig `W114`;
  der offene SQLite-Plan definiert denselben Vertrag fuer die spaetere
  SQLite-`helper_table`-Implementierung.
- **D3**: Capability-Matrix als versionierte
  Spec — `spec/neutral-model-spec.md` §9 fuehrt die
  Cross-Dialect-Capability-Tabelle (welches Attribut überlebt
  welchen Transfer); `spec/cli-spec.md` §4 listet die neuen
  Blocker-Codes pro Carve-out.
- **D4**: Sequence-Default-Reprojection-Vertrag für
  Cross-Dialect-Transfer — F.4 Sub-Slice D
  (`SequenceDefaultReprojector`) wirkt bereits dialekt-neutral
  für `RenameSequence`. Fuer Cross-Dialect-Transfer wirkt sie
  IMPLIZIT, weil der Neutralmodell-Vergleich keine Rename-
  Information trägt (Source und Target haben denselben
  Sequenz-Namen). Falls der Operator explizit eine Sequenz
  umbenennen will (Cross-Dialect-Migration plus Rename), nutzt
  er das bestehende F.4-Overlay-Schema mit
  `objectType = "sequence"`.
- **D5**: Capability-Source-Resolution-Pattern — analog zu
  E.1 Routine-Capability (`RoutineCapabilityDefaults` + `EffectiveRoutineCapability`).
  Sequence-Capabilities besitzen konservative Defaults pro Dialekt und
  sind via Overlay/CLI überschreibbar (inkl. version-sensitiver Regeln).

### 3.2 Out-of-Scope (delegiert an die parallelen Plans)

- Konkretes MySQL-Render-DDL → `docs/planning/done/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`.
- Konkretes SQLite-Render-DDL → `docs/planning/in-progress/sqlite-sequence-emulation-plan.md`.
- `preserveCurrentValue`-Probe-Implementation →
  `docs/planning/done/ImpPlan-0.9.7-sequence-preserve-current-value.md`.
- MariaDB-native `CREATE SEQUENCE` (10.3+) — separate
  Capability-Gate-Tranche.

---

## 4. Capability-Matrix (Decision-Record)

| `SequenceDefinition`-Attribut | PG | MySQL (Emul.) | SQLite heute / geplantes `helper_table` | Cross-Dialect-Verhalten |
|---|---|---|---|---|
| `name` | nativ | `dmg_sequences.name` | heute nicht gerendert (`E056` / Diff-Blocker); geplant: `dmg_sequences.name` | Source = neutral; Mapping verlustfrei, sobald der Target-Renderer Sequences aktiviert |
| `start` | `START WITH` | `dmg_sequences.next_value` | heute nicht gerendert; geplant: Seed via `next_value` (kein natives Start-Attribut) | Verlustfrei für frische Migrationen; SQLite-`helper_table` modelliert nur den Seed-Zustand, nicht zwingend den späteren aktuellen Wert |
| `increment` | `INCREMENT BY` | `dmg_sequences.increment_by` | heute nicht gerendert; geplant: `dmg_sequences.increment_by` | Verlustfrei zwischen PG/MySQL; SQLite erst nach `helper_table`-Implementierung |
| `minValue` | `MINVALUE` | `dmg_sequences.min_value` | heute nicht gerendert; geplant: `dmg_sequences.min_value` | SQLite erst nach `helper_table`-Implementierung verlustfrei |
| `maxValue` | `MAXVALUE` | `dmg_sequences.max_value` | heute nicht gerendert; geplant: `dmg_sequences.max_value` | SQLite erst nach `helper_table`-Implementierung verlustfrei |
| `cycle` | `CYCLE` / `NO CYCLE` | `dmg_sequences.cycle` | heute nicht gerendert; geplant: `dmg_sequences.cycle_enabled` | SQLite erst nach `helper_table`-Implementierung verlustfrei |
| `cache` | `CACHE n` | `dmg_sequences.cache_size` (Metadaten; keine Preallocation) | heute nicht gerendert; geplant: `dmg_sequences.cache_size` (Metadaten; keine Preallocation) | Kein Blocker im `helper_table`-Pfad; Renderer emittieren defaultmaessig `W114` ohne Overlay, weil der Wert gespeichert, aber nicht als Runtime-Cache emuliert wird |
| `preserveCurrentValue` | `setval(…, true)` | `UPDATE dmg_sequences SET next_value = …` | `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` | Execute-only; siehe preserveCurrentValue-Plan |
| `OWNED BY <table>.<column>` (nur PG) | nativ, aber nicht als `SequenceDefinition`-Attribut modelliert | nicht abbildbar | nicht abbildbar | Heute out of scope: PostgreSQL-Reader filtert `deptype IN ('a','i')` bewusst aus dem Standalone-Sequence-Modell, und der PG-Generator rendert spalteneigene Identity-/Serial-Sequenzen nicht als Standalone-Sequences. Kein Renderer-Blocker, bis das Neutralmodell ein Ownership-Feld traegt. |

**Blocker-Codes** (neu in
`PlannerBlockerClassifier`):

- `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT` →
  `MANUAL_ACTION_REQUIRED` (Operator entscheidet,
  ob Attribut verloren geht oder Migration blockt).
- `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT` →
  `MANUAL_ACTION_REQUIRED` (reserviert fuer eine spaetere
  Neutralmodell-Erweiterung; Sub-Slice A erzeugt diesen Code nicht,
  weil `OWNED BY` heute nicht in `SequenceDefinition` landet).
- `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` →
  `DIALECT_UNSUPPORTED_OPERATION` (bestehender
  Preserve-Current-Value-Vertrag: SQLite hat heute keinen
  Sequence-Renderer / keine Sequence-Emulation).

---

## 5. Architektur

### 5.1 Neutralmodell-Pfad

`NamedSequence` und `SequenceDefinition` bilden das neutrale Sequenzmodell
(`hexagon:core/model/SequenceDefinition.kt`, Name über `NamedSequence.name`).
Reader pro Dialekt liefern `NamedSequence`-Eintraege; Renderer pro
Dialekt konsumieren deren `SequenceDefinition`. Cross-Dialect-Transfer ist
die Kombination:
`source-reader → NamedSequence + SequenceDefinition → target-renderer`.
Wenn ein Attribut im Target nicht abbildbar ist, blockt der Target-Renderer.

### 5.2 Capability-Resolver

```kotlin
// hexagon:ports-read
data class SequenceCapability(
    val supportsNamedSequences: Boolean,
    val supportsStart: Boolean,
    val supportsMinMaxValue: Boolean,
    val supportsCycle: Boolean,
    val supportsCache: Boolean,
    val emitsCachePreallocationWarning: Boolean,
    val supportsCurrentValuePreserve: Boolean,
    val supportsOwnedBy: Boolean,
)

object SequenceCapabilityDefaults {
    fun forDialect(dialect: DatabaseDialect): SequenceCapability = when (dialect) {
        DatabaseDialect.POSTGRESQL -> SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            emitsCachePreallocationWarning = false,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = true,
        )
        DatabaseDialect.MYSQL -> SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true, // metadata roundtrip; W114 notes no preallocation
            emitsCachePreallocationWarning = true,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = false,
        )
        DatabaseDialect.SQLITE -> SequenceCapability(
            supportsNamedSequences = false, // Reality-first: renderer emits E056 / Diff blocks today
            supportsStart = false,
            supportsMinMaxValue = false,
            supportsCycle = false,
            supportsCache = false,
            emitsCachePreallocationWarning = false,
            supportsCurrentValuePreserve = false,
            supportsOwnedBy = false,
        )
    }
}
```

Renderer prüfen pro Op die `SequenceCapability`; nicht
unterstützte Attribute → standardmaessig Blocker.
`cache` ist im `helper_table`-Pfad kein Attribut-Blocker: MySQL
speichert den Wert heute als Metadatum und emittiert `W114` ohne
Overlay. Der offene SQLite-Plan definiert denselben Vertrag fuer eine
spaetere `helper_table`-Implementierung; bis dahin bleiben die
SQLite-Defaults `false`.

### 5.3 Cross-Dialect-Validation in `DiffPlanner`

Heute kennt der Planner den Ziel-Dialekt bereits über
`RenameProjectionCapabilities.dialect`; was fehlt, ist eine
dedizierte Sequence-Capability-Schicht. Dieser Plan
erweitert die Capabilities-Struktur (oder fuegt eine parallele
`SequenceCapabilities` hinzu), sodass die Mapper- und
Renderer-Validierung Sequence-Attribute-Mismatches
vor Rendern diagnostizieren können.

Alternative: Validation lebt nur im Renderer (Default heute für
andere dialect-mismatches). Die finalen Entscheidungen trifft
Sub-Slice B.

---

## 6. Sub-Slice-Schnittstellen

Da dies ein **Architektur-Plan** ist, sind die Sub-Slices kleiner
und delegierbar:

| Sub-Slice | Inhalt |
|---|---|
| A | `SequenceCapability` + `SequenceCapabilityDefaults` in `hexagon:ports-read`; Tests pinnen Defaults pro Dialekt |
| B | Renderer-Side-Validation: pro Dialekt-Renderer pruefen Capability, emit Blocker bei Mismatch |
| C | `spec/neutral-model-spec.md` §9 erweitern um die Capability-Matrix; `spec/cli-spec.md` §4 erweitern um die neuen Blocker-Codes |
| D | ADR (`docs/adr/0003-cross-dialect-sequencing.md`, neu anzulegen) dokumentiert die fuenf Decisions (D1–D5) |
| E | Closing: Plan-Doc nach `docs/planning/done/ImpPlan-0.9.7-cross-dialect-sequencing.md`; Cross-Links in die drei dialekt-spezifischen Plans setzen |

---

## 7. Akzeptanzkriterien

- [ ] `SequenceCapability` + `SequenceCapabilityDefaults` sind im
      Code; Defaults pro Dialekt gepinnt.
- [ ] Renderer pro Dialekt prueft Capability vor Render und
      emittiert bei Mismatch Blocker; `cache` im `helper_table`-Pfad
      bleibt eine defaultmaessige Renderer-Warnung (`W114`).
- [ ] PG-`OWNED BY` bleibt in Sub-Slice A ausserhalb des
  Standalone-Sequence-Modells: Reader-Filterung fuer `deptype IN
  ('a','i')` bleibt bestehen; kein negativer Renderer-Test, bis das
  Neutralmodell ein Ownership-Feld besitzt.
- [ ] PG → MySQL mit `CACHE` nutzt den defaultmaessigen `W114`-
  Warning-Pfad; kein Overlay ist erforderlich, weil `helper_table`
  den Wert als Metadatum kontrolliert erhaelt.
- [ ] Decision-Record (ADR) ist gepinnt.
- [ ] `spec/neutral-model-spec.md` und `spec/cli-spec.md` sind auf
      Stand.

---

## 8. Definition of Done (§13-Template)

- [ ] **Modus**: alle Prüfungen laufen render-time.
- [ ] **Renderbare Ops**: keine neuen — nur Validierung
      bestehender Sequence-Ops.
- [ ] **Neue Diagnostics**:
      `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT`,
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`,
      `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT`. Mappen via
      `PlannerBlockerClassifier` (`SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`
      → `DIALECT_UNSUPPORTED_OPERATION`; operator-fixbare Attribut- und
      spaetere Ownership-Faelle → `MANUAL_ACTION_REQUIRED`; `W114` ist
      eine Renderer-Warnung, kein Overlay-Carve-out).
- [ ] **Up / Down**: identisch (Validation greift in beide).
- [ ] **Report-Felder**: keine neuen.
- [ ] **Dialekte**: PG, MySQL, SQLite — alle drei mit Capability-
      Defaults.
- [ ] **F.0-Erfuellung**: irrelevant; ggf. Operator-Overlay-Override
      über bestehenden F.0-Vertrag, falls eine spaetere
      Operator-Override-Tranche das verlangt.
- [ ] **Positiv- + Blocker-Tests**: pro Dialekt mindestens je
      einer.
- [ ] **Rollback**: irrelevant (Validierung greift vor Render,
      keine Rollback-Wirkung).
- [ ] **Datei-zu-Datei**: identisch.
- [ ] **Bestehende Vertraege unveraendert**: keine Aenderung an
      `SequenceDefinition`-Feldern; nur neue Capability-Schicht.

---

## 9. Out-of-Scope / Folge-Themen

- MariaDB-native Sequences (`CREATE SEQUENCE`) →
  Capability-Gate für Dialekt-Family-Override.
- Operator-Override/Carve-out für Cross-Dialect-Attribute-Loss
  (via Sequenz-Overlay/CLI-Pfad) → spaetere Tranche.
- Sequence-Default-Reprojection beim Cross-Dialect-Transfer
  (anders als beim Rename) — ist durch das Neutralmodell-
  Pattern abgedeckt, bedarf keiner separaten Verdrahtung.

---

## 10. Risiken

- **Capability-Matrix-Drift**: wenn die parallelen Slices
  (MySQL-Diff, SQLite-Sequence, preserveCurrentValue) eigene
  Capability-Annahmen einbauen, koennen sie divergieren.
  Mitigation: Capability-Resolver ist die einzige Quelle, alle
  Slices muessen ihn konsumieren.
- **SQLite-Plan ist offen**: solange `docs/planning/in-progress/sqlite-sequence-emulation-plan.md`
  nicht implementiert ist, melden die SQLite-Capability-Defaults
  `supportsNamedSequences = false`. Das ist Reality-First:
  `SqliteCapabilityDdlSupport.generateSequences` erzeugt heute `E056`,
  und `SqliteDiffDdlGenerator` blockt Sequence-Ops mit
  `DIALECT_UNSUPPORTED_OPERATION`. Der offene SQLite-Plan bleibt die
  Vorlage fuer eine spaetere effektive `helper_table`-Capability, nicht
  fuer heutige Defaults.
- **PG `OWNED BY` semantisch nicht abbildbar**: PG-Sequenzen
  koennen einer Spalte gehoeren; MySQL/SQLite kennen das nicht.
  Der heutige Reader filtert solche Sequenzen bewusst aus
  `schema.sequences`, und das Neutralmodell besitzt kein
  Ownership-Feld. Sub-Slice A entfernt diese Filterung nicht; ein
  `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT`-Blocker wird erst
  relevant, wenn Ownership neutral modelliert wird.
