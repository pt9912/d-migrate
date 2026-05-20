# Implementierungsplan: 0.9.7 — Cross-Dialect Sequencing

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.3 architektonischer Schirm
> **Status**: `open` (seit 2026-05-19) — Architektur-Plan, kein Code-Plan.
> **Vorbedingung**: PG-Sequence-Diff-Renderer ✅; MySQL-Sequence-Diff-Plan
>                  *(parallel in-progress,
>                  `docs/planning/in-progress/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`)*;
>                  SQLite-Sequence-Plan
>                  (`docs/planning/open/sqlite-sequence-emulation-plan.md`);
>                  preserveCurrentValue-Plan
>                  *(parallel in-progress,
>                  `docs/planning/in-progress/ImpPlan-0.9.7-sequence-preserve-current-value.md`)*.
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md` §E.3;
>             `docs/planning/done/mysql-sequence-emulation-plan.md`;
>             `docs/planning/open/sqlite-sequence-emulation-plan.md`.

---

## 1. Auslöser

Sequence-Migrationen leben in 0.9.7 als vier voneinander unabhängige
Slices:

- **PG** (E.3 Erstscheibe ✅): native `CREATE/ALTER/DROP/RENAME
  SEQUENCE`-DDL.
- **MySQL** (parallel-Plan): Emulation via `dmg_sequences`-Helper-
  Table und Sequence-Trigger.
- **SQLite** (parallel-Plan): rebuild-basierte Emulation; Details
  in `docs/planning/open/sqlite-sequence-emulation-plan.md`.
- **preserveCurrentValue** (parallel-Plan): cross-dialect
  Live-DB-Probe + Setval-Pattern.

Was **fehlt** ist ein gemeinsamer architektonischer Vertrag, der
die folgenden Fragen entscheidet:

1. **Cross-Dialect-Transfer**: was passiert, wenn ein
   PG-Schema mit `CREATE SEQUENCE` nach MySQL transferiert wird?
  Heute: MySQL-Renderer blockiert mit `DIALECT_UNSUPPORTED_OPERATION`;
   geplant ist die Feineinstellung auf sequence-spezifische Blocker-Codes
   nach der Einführung dieser Schicht.
   Nach den parallelen Plans: MySQL emittiert die helper_table-
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
  unterstützt wird (z.B. PG-`CACHE` nach SQLite), emittiert
  der Renderer standardmaessig
  `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT`.
  Für explizit als kontrollierten Attribut-Verlust dokumentierte
  Felder (z.B. `cache`) kann ein vorhandener Overlay-/Override-
  Mechanismus die Migration auf `W114` als WARNING begrenzen;
  fehlt ein solcher Treffer, bleibt der Standardfall Blocker.
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
  Sequence-Capabilities haben Defaults pro Dialekt + Version
  und sind via Overlay/CLI überschreibbar.

### 3.2 Out-of-Scope (delegiert an die parallelen Plans)

- Konkretes MySQL-Render-DDL → `docs/planning/in-progress/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`.
- Konkretes SQLite-Render-DDL → `docs/planning/open/sqlite-sequence-emulation-plan.md`.
- `preserveCurrentValue`-Probe-Implementation →
  `docs/planning/in-progress/ImpPlan-0.9.7-sequence-preserve-current-value.md`.
- MariaDB-native `CREATE SEQUENCE` (10.3+) — separate
  Capability-Gate-Tranche.

---

## 4. Capability-Matrix (Decision-Record)

| `SequenceDefinition`-Attribut | PG | MySQL (Emul.) | SQLite (`helper_table`) | Cross-Dialect-Verhalten |
|---|---|---|---|---|
| `name` | nativ | `dmg_sequences.name` | `dmg_sequences.name` | Source = neutral; Mapping verlustfrei |
| `start` | `START WITH` | `dmg_sequences.next_value` | Seed via `next_value` (kein natives Start-Attribut) | Verlustfrei für frische Migrationen; SQLite modelliert nur den Seed-Zustand, nicht zwingend den späteren aktuellen Wert |
| `increment` | `INCREMENT BY` | `dmg_sequences.increment_by` | `dmg_sequences.increment_by` | Verlustfrei zwischen PG/MySQL; SQLite analog |
| `minValue` | `MINVALUE` | `dmg_sequences.min_value` | `dmg_sequences.min_value` | SQLite: verlustfrei in `helper_table` |
| `maxValue` | `MAXVALUE` | `dmg_sequences.max_value` | `dmg_sequences.max_value` | SQLite: verlustfrei in `helper_table` |
| `cycle` | `CYCLE` / `NO CYCLE` | `dmg_sequences.cycle` | `dmg_sequences.cycle_enabled` | SQLite: verlustfrei in `helper_table` |
| `cache` | `CACHE n` | `dmg_sequences.cache_size` (deklarativ, semantisch nicht äquivalent) | `dmg_sequences.cache_size` | standardmaessig lossy/Blocker; mit Overlay/Override als kontrollierte `W114`-Warning |
| `preserveCurrentValue` | `setval(…, true)` | `UPDATE dmg_sequences SET next_value = …` | `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` | Execute-only; siehe preserveCurrentValue-Plan |
| `OWNED BY <table>.<column>` (nur PG) | nativ | nicht abbildbar | nicht abbildbar | PG → MySQL/SQLite: `MANUAL_ACTION_REQUIRED`; ownership-Inferenz vom Reader entscheidet, ob die Sequenz mit ihrer „eigentuemer-Spalte" verbunden ist |

**Blocker-Codes** (neu in
`PlannerBlockerClassifier`):

- `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT` →
  `MANUAL_ACTION_REQUIRED` (Operator entscheidet,
  ob Attribut verloren geht oder Migration blockt).
- `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT` →
  `MANUAL_ACTION_REQUIRED`.
- `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` →
  `MANUAL_ACTION_REQUIRED` (bestehender Preserve-Current-Value-Vertrag; Delegation an Slice E).

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
    val supportsStart: Boolean,
    val supportsMinMaxValue: Boolean,
    val supportsCycle: Boolean,
    val supportsCache: Boolean,
    val supportsCurrentValuePreserve: Boolean,
    val supportsOwnedBy: Boolean,
)

object SequenceCapabilityDefaults {
    fun forDialect(dialect: DatabaseDialect): SequenceCapability = when (dialect) {
        DatabaseDialect.POSTGRESQL -> SequenceCapability(
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = true,
        )
        DatabaseDialect.MYSQL -> SequenceCapability(
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = false, // W114 als lossy-Mapping über Overlay/Warning
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = false,
        )
        DatabaseDialect.SQLITE -> SequenceCapability(
            supportsStart = true, // initial über seed in helper-table
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = false, // metadata-only; kein runtime-caching (`W114`), W114-Warnung
            supportsCurrentValuePreserve = false, // bis SQLite-Plan landet
            supportsOwnedBy = false,
        )
    }
}
```

Renderer prüfen pro Op die `SequenceCapability`; nicht
unterstützte Attribute → Blocker.

### 5.3 Cross-Dialect-Validation in `DiffPlanner`

Heute kennt der Planner den Ziel-Dialekt bereits über
`RenameProjectionCapabilities.dialect`; was fehlt, ist eine
dedizierte Sequence-Capability-Schicht. Dieser Plan
erweitert die Capabilities-Struktur (oder fuegt eine parallele
`SequenceCapabilities` hinzu), sodass der Mapper /
Renderer-Validierungsstufe Sequence-Attribute-Mismatches
diagnostizieren kann VOR Render.

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
      emittiert bei Mismatch entweder Blocker oder via
      Overlay/Override kontrollierte Warnung (`W114`).
- [ ] PG → MySQL mit `OWNED BY` blockt mit dem neuen Code
  (negativer Test).
- [ ] PG → MySQL mit `CACHE` nutzt den Overlay/Override-Pfad zu
  `W114` als WARNING (kein harter Blocker im Standardpfad).
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
      `PlannerBlockerClassifier`.
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
- **SQLite-Plan ist offen**: solange `docs/planning/open/sqlite-sequence-emulation-plan.md`
  nicht implementiert ist, blockt jeder SQLite-Pfad mit
  `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT`. Das ist kein Blocker
  für DIESEN Plan — die Capability-Defaults sind konservativ.
- **PG `OWNED BY` semantisch nicht abbildbar**: PG-Sequenzen
  koennen einer Spalte gehoeren; MySQL/SQLite kennen das nicht.
  Wenn Reverse-Read den `OWNED BY` trägt und der Transfer-
  Renderer das nicht abbilden kann, blockt der Slice mit
  `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT`.
