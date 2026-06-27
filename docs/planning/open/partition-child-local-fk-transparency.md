# Vorschlag: Kind-lokale FK-Transparenz auf Partitionen (E065)

> **Status:** Draft (Vorschlag, 2026-06-27)
> **Trigger:** Carve-Out aus der graduierten Cross-Dialect-Partitionierung
> ([`../done/cross-dialect-partitioning.md`](../done/cross-dialect-partitioning.md),
> Sub-Slice „Kind-lokale FK-Transparenz E065"). Getrackt in
> [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 9, Zeile 1 —
> dort als **Provisional** mit Trigger „Cross-Dialect-Fidelity-Bedarf".
> **Aktivierungsbedingung:** Sobald cross-dialektische Partitions-Fidelity (PG-Reverse,
> der kind-lokale FKs sichtbar macht) für einen Milestone priorisiert wird, wandert
> dieser Vorschlag nach [`../next/`](../next/) — **dort** mit Phasenschnitt und
> Akzeptanzkriterien ([ADR 0004](../../adr/0004-documentation-and-planning-structure.md)
> reserviert ausgearbeitete Phasen/Akzeptanz für `next/`). Dieses `open/`-Dokument bleibt
> auf Vorschlags-Altitude: Ziel, Scope und offene Designentscheidungen.

## 1. Ziel

PostgreSQL erlaubt Fremdschlüssel **direkt auf Kind-Partitionen** (nicht nur am Parent) —
in Pagila deklariert `payment` FKs auf den monatlichen Kind-Tabellen. Heute erfasst der
Reverse-Pfad solche kind-lokalen FKs **nicht**: sie fallen still weg. Ziel ist die
**Transparenz** — kind-lokale FKs sollen beim Reverse erfasst werden, damit sie

- bei PG→PG-Round-Trip **erhalten** bleiben (auf den Kindern regeneriert), und
- bei PG→MySQL **sichtbar verworfen** werden (`action_required`-Note **E065** statt
  stillem Verlust — MySQL/InnoDB verbietet FKs auf partitionierten Tabellen ohnehin).

Das MySQL-**Ergebnis** ist bereits heute korrekt; was fehlt, ist die nachvollziehbare
Meldung für genau den kind-lokalen Fall.

## 2. Hintergrund (Ist-Stand im Code)

- **Modell trägt kein FK-Feld auf Partitionen.** `PartitionDefinition` in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/PartitionConfig.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/PartitionConfig.kt)
  trägt heute nur `indices: List<IndexDefinition>` (kind-lokale Indizes, AP6.3) — **kein**
  Constraint-/FK-Feld. Der Klassen-Kommentar hält explizit fest:
  „Parent-Indizes/-Constraints und FKs bleiben am Parent und propagieren von dort" — das
  deckt den kind-lokal **deklarierten** FK nicht ab.
- **FKs sind als `ConstraintDefinition` modelliert** (Typ `ConstraintType.FOREIGN_KEY`) in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ConstraintDefinition.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ConstraintDefinition.kt);
  an der Tabelle hängen sie über `TableDefinition.constraints` in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/TableDefinition.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/TableDefinition.kt).
- **Reverse-Vorbild = Index-Heben.** Der PG-Reverse-Pfad in
  [`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt)
  liest pro Kind die **kind-lokalen Indizes** und filtert die geerbten via `pg_inherits`
  (Queries in
  [`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresPartitionMetadataQueries.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresPartitionMetadataQueries.kt)).
  Eine analoge FK-Erfassung gibt es nicht — sie ist die Vorlage für diesen Slice.
- **E065 existiert bereits** für FKs auf partitionierten Tabellen — emittiert an drei
  Stellen (Inline-Ref, expliziter Constraint, zirkuläre Refs) in
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt).
  Heute greift E065 nur für **Tabellen-Ebene-FKs**; kind-lokale FKs erreichen den Generator
  gar nicht, weil das Modell sie nicht trägt.

## 3. Scope

### 3.1 In Scope

- **Modell:** Ein FK-tragendes Feld auf `PartitionDefinition` — naheliegend
  `foreignKeys: List<ConstraintDefinition>` (FK-Teilmenge wiederverwenden, kein neuer Typ).
- **PG-Reverse:** kind-lokale FKs pro Kind erfassen, geerbte/propagierte herausfiltern
  (`pg_inherits`-Muster wie beim Index-Heben), an `PartitionDefinition.foreignKeys` hängen.
- **PG-Generate:** kind-lokale FKs beim PG→PG-Round-Trip auf den Kindern regenerieren.
- **MySQL-Generate:** kind-lokale FKs als **E065** melden (bestehende Note-Logik auf den
  kind-lokalen Fall ausdehnen) statt still zu verwerfen.
- **Serialisierung + Spec:** YAML-Codec für das neue Feld, `spec`/`ledger`-Sync der
  E065-Reichweite.
- **ADR-Ergänzung:** [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md)
  (FK-Feld als Teil der strukturierten Partitions-Repräsentation) und
  [ADR 0020](../../adr/0020-cross-dialect-partitioning-mysql.md) (kind-lokaler FK als
  zusätzlicher E065-Auslöser).

### 3.2 Nicht in Scope

- **FKs auf MySQL-partitionierten Tabellen durchsetzen** — strukturell unmöglich
  (InnoDB verbietet das); dies ist ein reines **Transparenz**-Feature, kein Funktions-Gewinn.
- **MySQL-Reverse kind-lokaler FKs** — MySQL kann sie nicht haben, also entfällt das.
- Änderung am bereits korrekten MySQL-**Ergebnis** (FKs werden weiter verworfen, nur
  jetzt gemeldet).

## 4. Offene Designentscheidungen

1. **„Kind-lokal" vs. „propagiert" sauber unterscheiden.** Ein am Parent definierter FK
   propagiert in PG auf die Kinder; nur der **am Kind selbst deklarierte** FK ist kind-lokal.
   Beim Index-Heben löst `pg_inherits` das. Für FKs ist zu klären, ob dieselbe Abgrenzung
   trägt (FK-Vererbung verhält sich in PG nicht identisch zur Index-Vererbung) — das ist die
   zentrale Korrektheitsfrage des Slice.
2. **Feld-Form auf `PartitionDefinition`.** `List<ConstraintDefinition>` (FK-Teilmenge,
   einfach) gegen einen schmaleren FK-eigenen Typ. Tendenz: `ConstraintDefinition`
   wiederverwenden, konsistent mit `TableDefinition.constraints`.
3. **E065-Granularität.** Eine Note pro kind-lokalem FK (präzise, ggf. viele bei
   monatlichen Pagila-Kindern) gegen eine aggregierte Note pro Tabelle.
4. **PG→PG-Generate-Pfad.** Verifizieren, dass der PG-Generator FKs **auf den Kindern**
   (nicht am Parent) emittieren kann — sonst geht die Fidelity beim Regenerieren verloren.

## 5. Bezug

- Quell-Slice (graduiert): [`../done/cross-dialect-partitioning.md`](../done/cross-dialect-partitioning.md).
- Carve-Out-Tracker: [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 9.
- ADRs: [0019](../../adr/0019-partition-hierarchy-structured-representation.md),
  [0020](../../adr/0020-cross-dialect-partitioning-mysql.md).
- Schwester-Slice (gleicher Carve-Out-Abschnitt):
  [`partition-list-default-transfer-preflight.md`](partition-list-default-transfer-preflight.md).
