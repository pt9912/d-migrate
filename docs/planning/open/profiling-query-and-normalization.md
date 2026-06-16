# Vorschlag: Profiling `--query` und `--analyze-normalization`

> **Status:** Vorschlag (Draft, 2026-06-15)
> **Trigger:** `spec/profiling.md` §7.1 beschreibt die CLI-Flags `--query` und
> `--analyze-normalization` als Teil des Zielbilds; Milestone 0.7.5 hat bewusst
> nur den deterministischen Kern (DB-/Tabellen-Profiling) geliefert. Siehe
> [`../done-archive/implementation-plan-0.7.5.md`](../done-archive/implementation-plan-0.7.5.md)
> §3.2 / §4.1 — beide Flags sind dort explizit „bewusst nicht Teil von 0.7.5".
> **Aktivierungsbedingung:** Sobald der Funktionsumfang für einen Milestone
> priorisiert wird, wandert dieser Vorschlag nach `../next/` — **dort** mit
> Phasenschnitt und Akzeptanzkriterien (ADR 0004 reserviert ausgearbeitete
> Phasen/Akzeptanz für `next/`; dieses `open/`-Dokument bleibt auf
> Vorschlags-Altitude und legt nur Ziel, Scope und die offenen
> Designentscheidungen fest).

## 1. Ziel

Die in `spec/profiling.md` vorgesehenen, in 0.7.5 zurückgestellten Fähigkeiten
nachrüsten, additiv auf dem bestehenden deterministischen Kern:

- **`--query`** — ein beliebiges SELECT profilieren statt benannter Tabellen.
- **`--analyze-normalization`** — Struktur- und Normalisierungsanalyse
  (namens- und datenbasiert), **ohne** LLM-Schicht (Spec §10 bleibt separat).

## 2. Hintergrund

`spec/profiling.md` ist das **Soll-Bild**, nicht der aktuelle Stand. Real trägt
`TableProfile` heute nur `name/schema/rowCount/columns/warnings`; die Felder
`structuralFindings`/`normalizationProposal` (Spec §4.1/§4.5), der Query-Pfad
und die Modellklassen `StructuralFinding`, `NormalizationProposal`,
`ProposedEntity`, `ProposedLookup`, `UnpivotCandidate` fehlen.

## 3. Scope

### 3.1 In Scope

- CLI-Flags `--query <sql>` und `--analyze-normalization` für `data profile`.
- Namensbasierte Strukturanalyse (`StructuralFinding`: `REPEATED_COLUMN_GROUP`,
  `PARALLEL_COLUMN_GROUP`) — deterministisch, kein DB-Zugriff (Spec §4.5).
- Datenbasierte Normalisierungsanalyse (`NormalizationProposal`): FD-Discovery,
  Low-Cardinality-Lookups, Repeated-Column-Unpivot, optional Kookkurrenz —
  **deterministisch per Code** (Spec §4.5).
- Erweiterung von `TableProfile` (+ Modellklassen, Ports, Services, CLI-Wiring).

### 3.2 Nicht in Scope

- **LLM-/semantische Analyse** (Spec §10): Entitäts-/Tabellennamen, fachliche
  Beziehungstypen, Bedeutungsableitung. `suggestedName`/`suggestedChildTable`
  bleiben `null`, wo Code nicht benennen kann.
- Automatische Schema-Mutation aus Profiling-Ergebnissen.
- Profiling-Report-Export für externe Quality-Tools → eigener Plan
  [`../next/profiling-data-quality-export.md`](../next/profiling-data-quality-export.md).

## 4. `--query` — Designvorgaben

**Zweck:** Statistiken/Warnungen über das Ergebnis eines SELECT (gefilterte
Teilmengen, Joins, abgeleitete Spalten) statt über physische Tabellen.

**CLI-Vertrag:**

- Exklusiv zu `--tables` und `--analyze-normalization`.
- **`--query` + `--schema` → Exit 2** (Nutzungsfehler). Konsistent mit dem
  bestehenden Verhalten (`--schema` auf MySQL/SQLite → Exit 2). *Folge:* der
  Spec-§7.1-Wortlaut „`--schema` wirkt im Query-Modus nicht" ist beim Bau auf
  „Exit 2" zu schärfen.
- Query-Ausführungsfehler → Exit 5.

**Architektur — anschlussfähig machen (Review-Fund):** Der heutige
`ProfilingDataPort` nimmt **Tabellen-/Spaltennamen** entgegen, und die Adapter
quoten die Tabelle über `ProfilingSqlNames.tablePath()` als **Identifier**
(z. B. `PostgresProfilingDataAdapter`). Ein Subquery-String kann dort **nicht**
eingesetzt werden („`FROM (<query>) AS sub`" lässt sich nicht in die
Identifier-Quoting-Stelle splicen). Stattdessen:

- Ein **typisierter Relation-Source** einführen, z. B. sealed
  `ProfilingRelation { TableRelation(name, schema) | QueryRelation(sql) }`.
  Der SQL-Builder rendert `TableRelation` weiterhin identifier-gequotet und
  `QueryRelation` als abgeleitete Tabelle (`(<sql>) AS sub`). Die Port-Methoden
  nehmen die Relation, nicht einen in das Identifier-Quoting gesplicten String.
- Alternativ ein connection-/session-basierter Query-Aggregat-Pfad (Query einmal
  in eine read-only-Hilfsrelation/Temp-View materialisieren, dann die
  vorhandenen Aggregate darauf).
- **Sicherheit:** nur SELECT zulassen (Statement-Klassifizierung), read-only-/
  Rollback-Transaktion, keine DDL/DML; Trusted-Input dokumentieren (wie
  `--filter`/CHECK).
- **Connection-Disziplin:** Aggregate auf der **bereits geborgten** Connection
  ausführen, kein verschachteltes `pool.borrow()` (vgl. der 2026-06-15 gefixte
  `topValues`-Bug — kritisch bei SQLite-Pool-Size 1).

## 5. `--analyze-normalization` — Designvorgaben

- **Namensbasierte Strukturanalyse** (`structuralFindings`): Regex auf
  Spaltennamen (kein DB-Zugriff), pro Tabelle — `REPEATED_COLUMN_GROUP`
  (`wert_1/2/3`, `addr1/2`), `PARALLEL_COLUMN_GROUP` (`phone_home/work/mobile`).
- **Datenbasierte Analyse** (`normalizationProposal`, nur bei Flag, da
  rechenintensiv): FD-Discovery (`ProposedEntity`, TANE/HyFD-Familie, mit
  Größen-Cap/Sampling), Low-Cardinality-Lookups (`ProposedLookup`), Unpivot
  (`UnpivotCandidate`), optional Kookkurrenz. Confidence-/Quelle-Hinweise;
  Benennung bleibt der LLM-/Mensch-Stufe (§3.2) vorbehalten.

## 6. Offene Designentscheidungen (vor `next/` festzulegen)

- **Gating von `structuralFindings` (Report-Vertrag):** Vorschlag — die
  namensbasierten Findings laufen **nur** bei gesetztem
  `--analyze-normalization` (sauberer, opt-in Report-Vertrag: keine neuen
  Felder ohne Flag). Alternative (da billig & DB-frei): immer mitlaufen lassen.
  Hier bewusst als **gated** vorgeschlagen; final beim `next/`-Schnitt fixieren.
- **Modellfelder-Default:** `structuralFindings` default leer, `normalizationProposal`
  default `null` — additive, brechen den bestehenden Determinismus-Vertrag nicht.

## 7. Risiken

- **FD-Discovery-Skalierung:** teuer auf breiten/großen Tabellen → Spalten-/
  Zeilen-Cap, Sampling, Timeouts; strikt opt-in.
- **Query-Sicherheit:** nur SELECT, read-only; keine Nebenwirkungen.
- **Scope-Kriechen Richtung LLM:** Benennung/Semantik strikt bei §10 lassen.
- **Connection-Pool:** keine verschachtelten Borrows (SQLite Pool-Size 1).

## 8. Referenzen

- [`../../../spec/profiling.md`](../../../spec/profiling.md) §4.5 (Strukturanalyse),
  §7.1 (CLI), §10 (LLM — out of scope)
- [`../done-archive/implementation-plan-0.7.5.md`](../done-archive/implementation-plan-0.7.5.md)
  §3.2 / §4.1 (Deferral)
- [`../next/profiling-data-quality-export.md`](../next/profiling-data-quality-export.md)
  (Report-Export, getrennter Plan)
- [`../../adr/0004-documentation-and-planning-structure.md`](../../adr/0004-documentation-and-planning-structure.md)
  (Lebenszyklus `open/` → `next/` → `in-progress/` → `done/`)
