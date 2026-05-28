---
status: accepted
date: 2026-05-27
decision-makers: pt9912
consulted: 0.9.7-Cross-Dialect-Sequencing-Umbrella-Plan-Review
informed: Sub-Slice-A/B/C-Reviewer; künftige Implementier des SQLite-Sequence-Emulation-Plans
---

# Cross-Dialect-Sequencing — Capability-Vertrag

## Kontext und Problemstellung

0.9.7 liefert Sequence-Migrationen als vier unabhängige Slices:
PostgreSQL native DDL (E.3-Erstslice, done), MySQL
`dmg_sequences`-Helper-Table-Emulation (Parallel-Plan, done),
SQLite Rebuild-basierte Emulation (noch
`docs/planning/in-progress/sqlite-sequence-emulation-plan.md`) und
das cross-dialect `preserveCurrentValue`-Follow-up (Parallel-Plan,
done).

Als das Umbrella-Plan-Doc
(`docs/planning/done/ImpPlan-0.9.7-cross-dialect-sequencing.md`,
Closing in Sub-Slice E) Scope erreichte, waren drei der vier
Slices bereits gemerged. Das Plan-Doc wandelte sich damit von
"Upstream-Architektur für parallele Slices" zu "Retrofit-
Harmonisierung bereits gelieferter Slices". Drei Drifts sind
zwischen Plan-Doc-Intent und Code-Realität aufgetaucht:

1. **preserve-not-supported-Routing**: Das Plan-Doc wollte
   `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` auf
   `MANUAL_ACTION_REQUIRED` mappen; der `PlannerBlockerClassifier`
   mappte es auf `DIALECT_UNSUPPORTED_OPERATION` mit dem
   expliziten Grund "SQLite has no sequence emulation yet."
2. **MySQL `cache`-Mapping**: Das Plan-Doc wollte einen
   Default-Blocker plus Overlay-gegateten `W114`-Warning; der
   Renderer emittierte `W114` direkt ohne Overlay.
3. **PG `OWNED BY`**: Das Plan-Doc wollte einen renderer-seitigen
   Blocker (`SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT`); der
   PG-Reverse-Reader filterte owned Sequences über
   `pg_depend.deptype IN ('a','i')`, sodass der Renderer sie nie
   sah.

Ohne einen einzigen Decision-Record würde der nächste
Sequence-Slice (SQLite-Helper-Table, MariaDB-native, neutrales
Ownership-Feld) dieselben Fragen erneut verhandeln und
auseinanderdriften.

## Entscheidungstreiber

- Den as-shipped-Vertrag dort respektieren, wo die Code-Begründung
  tragfähig ist; bereits gemergedes Verhalten nicht zurückrollen,
  nur um einem Entwurfs-Plan-Doc zu entsprechen.
- Reality-first-SQLite-Defaults, damit die Capability-Schicht
  abbildet, was der Renderer heute tatsächlich tut — nicht, was
  er einmal können wird, sobald der offene SQLite-Plan landet.
- Forward-Compatibility für den OWNED-BY-Fall und den
  SQLite-Per-Attribute-Fall — Codes reservieren, damit ein
  späterer Slice sie emittieren kann, ohne den Classifier
  anzufassen.
- Minimale API-Oberfläche: keine Operator-konfigurierbare
  Capability-Quelle für Sequences in 0.9.7. Die
  Capability-Schicht ist Defaults-only.
- Die bestehenden `RoutineCapability` / `TriggerCapability`-
  Muster spiegeln, damit Reviewer keine dritte Form lernen
  müssen.

## Betrachtete Optionen

Für jede der drei Drifts hat das Umbrella-Plan-Review (Code
gewinnt, Plan-Doc gewinnt, Sunset-Kompromiss) abgewogen. Der
gewählte Pfad nimmt den as-shipped-Vertrag für die beiden Drifts,
wo die Code-Begründung tragfähiger war als der Entwurf, und passt
das Plan-Doc für die dritte an — wo Reader-seitiges Filtern
Ownership komplett aus dem Neutralmodell heraushielt.

## Entscheidung

Fünf Entscheidungen, alle im Umbrella-Plan-Doc §3.1 gepinnt und
in den Sub-Slices A (`SequenceCapability`-Defaults), B (MySQL-
Diff-Pfad `W114` via Capability + Classifier-Konstanten) und C
(`spec/neutral-model-spec.md §9.2`, `spec/cli-spec.md §4.7`)
materialisiert.

### D1 — Sequence-Identität dialektübergreifend

`NamedSequence.name` (das Neutralmodell-Feld, äquivalent zu
`SequenceDiff.name`) ist die einzige Source-of-Truth.
Dialekt-Emulationen (`MySQL.dmg_sequences.name`, die geplante
SQLite-Helper-Table `dmg_sequences.name`) MÜSSEN ohne
Transformation auf diesen Namen mappen.

### D2 — Cross-dialect-Transfervertrag

Renderer konsultieren `SequenceCapability` und emittieren
`SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT`, wenn ein gesetztes
Attribut beim Ziel auf ein unsupported-Flag trifft. Der OP-Level-
Fall (`supportsNamedSequences = false`, heute SQLite) bleibt bei
`DIALECT_UNSUPPORTED_OPERATION`, weil keine Operator-Aktion ein
fehlendes Dialekt-Konzept aktiviert; der Attribut-Level-Code ist
für Partial-Support-Fälle reserviert (z. B. ein zukünftiger
SQLite-Renderer, der Named Sequences unterstützt, aber `cycle`
nicht). MySQL `cache` ist kein Blocker: der Renderer emittiert
`W114` per Default — sowohl im Full-Schema- als auch im
Diff-Pfad —, weil der `helper_table` den Wert ohnehin als
Metadata persistiert. Kein Operator-Overlay erforderlich.

### D3 — Capability-Matrix als versionierte Spezifikation

Die cross-dialect Capability-Matrix lebt in
`spec/neutral-model-spec.md §9.2`, und der Katalog der
string-codierten Sequence-Blocker-Codes (mit
`MigrationBlockedReason`-Routing) lebt in
`spec/cli-spec.md §4.7`. Beide sind normativ; die in-Code
`SequenceCapabilityDefaults` müssen mit ihnen konsistent
bleiben.

### D4 — Sequence-Default-Reprojection (cross-dialect)

Der F.4-Sub-Slice-D `SequenceDefaultReprojector` arbeitet bereits
dialekt-neutral für `RenameSequence`. Für cross-dialect-Transfer
gilt er implizit: der Neutralmodell-Diff trägt keine
Rename-Information (Quelle und Ziel teilen den Sequence-Namen),
also sieht der Reprojector schlicht den kanonischen Namen auf
beiden Seiten. Operatoren, die ein explizites
cross-dialect-Rename wollen, nutzen das bestehende F.4-Overlay-
Schema mit `objectType = "sequence"`. Kein neuer Code nötig.

### D5 — Capability-Source-Resolution-Pattern

`SequenceCapability` spiegelt `RoutineCapability` /
`TriggerCapability`: eine Data-Class in `hexagon:ports-read` mit
per-Dialekt-Defaults via `SequenceCapabilityDefaults.forDialect()`.
0.9.7 liefert Defaults-only; ein
`EffectiveSequenceCapability`-Sealed-Envelope analog zu
`EffectiveRoutineCapability` landet erst, wenn ein späterer
Tranche CLI- / YAML-Overrides für Sequences einführt.

### Konsequenzen

- Gut, weil die vier bereits gelieferten Slices jetzt einen
  bindenden Vertrag haben statt dreier unabhängiger Annahmen.
- Gut, weil die SQLite-Reality-first-Defaults dafür sorgen, dass
  die Capability-Schicht heute wahr ist; der offene SQLite-Plan
  flippt die relevanten Flags als Teil seiner eigenen Änderungen,
  statt unbegründete `true`-Werte zu erben.
- Gut, weil die beiden Forward-Compat-Classifier-Codes
  (`SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT`,
  `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT`) später
  emittiert werden können, ohne den Classifier oder die
  Routing-Tabelle anzufassen.
- Schlecht, weil der OP- vs. Attribut-Level-Split für SQLite
  bedeutet, dass dasselbe beobachtbare Verhalten (SQLite lehnt
  Sequence-Ops ab) je nach Slice durch zwei verschiedene
  Gründe signalisiert wird. Operatoren, die rohe Exit-Codes
  lesen, sehen heute `DIALECT_UNSUPPORTED_OPERATION`, könnten
  aber `MANUAL_ACTION_REQUIRED` sehen, sobald partielle
  SQLite-Emulation landet. Der cli-spec-§4.7-Katalog kalliert das
  explizit aus.
- Neutral, weil MariaDB-natives `CREATE SEQUENCE` (10.3+), falls
  als eigenes Capability-Gate landend, einen Vendor-Version-
  Branch in `SequenceCapabilityDefaults.forDialect(MYSQL)`
  brauchen wird — analog zu
  `RoutineCapabilityDefaults.forMysqlServerVersion` — ohne diese
  ADR zu ändern.

### Bestätigung

- `hexagon:ports-read:SequenceCapabilityTest` pinnt die
  per-Dialekt-Defaults (Sub-Slice A).
- `MysqlDiffSequenceOpsCacheWarningTest` (Sub-Slice B.1) pinnt
  die `W114`-Emission in CreateSequence-UP, AlterSequence
  (beide Richtungen wenn `cache` abweicht) und
  DropSequence-DOWN; pinnt Stille bei CreateSequence-DOWN und
  DropSequence-UP.
- `PlannerBlockerClassifierTest` (Sub-Slice B.0) pinnt
  `MANUAL_ACTION_REQUIRED`-Routing für die beiden
  Forward-Compat-Codes.
- `spec/neutral-model-spec.md §9.2` und `spec/cli-spec.md §4.7`
  tragen den normativen Vertrag.

## Weitere Informationen

- Umbrella-Plan-Doc:
  `docs/planning/done/ImpPlan-0.9.7-cross-dialect-sequencing.md`
  (Closing in Sub-Slice E).
- Parallele Slices, die diese ADR retroaktiv harmonisiert:
  `docs/planning/done/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`,
  `docs/planning/done/ImpPlan-0.9.7-sequence-preserve-current-value.md`,
  `docs/planning/done/mysql-sequence-emulation-plan.md`.
- Offenes Follow-up, an das diese ADR explizit deferred:
  `docs/planning/in-progress/sqlite-sequence-emulation-plan.md`.
- Verwandte Capability-Muster:
  `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/RoutineCapability.kt`,
  `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/TriggerCapability.kt`.
- Verwandte ADRs: ADR-0001 / ADR-0002 wählten WARNING für
  Routine-seitige Risiken; diese ADR wählt dieselbe abgedämpfte
  Haltung für das MySQL-`cache`-Lossy-Mapping (Default `W114`,
  kein Overlay-Gate).
