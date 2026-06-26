# Spalten-Ordinalreihenfolge erhalten (Reverse → Serialize → Generate)

> **Status:** done / graduiert (2026-06-26). Vollbau grün (alle Unit-Tests + detekt +
> koverVerify ≥ 90 % pro Modul). Gate-ADR: [ADR 0021](../../adr/0021-column-ordinal-fidelity.md).
> Entscheidung: **voll umsetzen, Hybrid-Repräsentation** (geordnete Serialisierung +
> explizites `ordinal`).

## Entscheidung

- **Scope:** Spalten-Ordinalreihenfolge wird end-to-end erhalten (kein bloßes
  „als Normalisierung dokumentieren"). Passt zur Fidelity-/First-class-Modellierungslinie
  des Projekts.
- **Repräsentation: Hybrid.** Spalten werden (a) in **physischer Reihenfolge serialisiert**
  *und* (b) tragen ein **explizites `ordinal`-Feld**. (a) liefert lesbares YAML in
  natürlicher Reihenfolge, (b) sichert die Reihenfolge robust gegen Re-Parse, Overlay-Merge
  und manuelles Umsortieren ab.

## Befund (korrigiert die Vorabklärung)

Die alte open/-Notiz argumentierte, die reine geordnete Serialisierung „überlebe keinen
Re-Parse". Das stimmt für **Spalten nicht**: `parseNamedObjectMap`
(`SchemaNodeParserSupport.kt`) baut einen `LinkedHashMap` und iteriert `fieldNames()` →
die YAML-Reihenfolge **bleibt beim Parsen erhalten**. Die Reihenfolge wird nur an zwei
Stellen geplättet:

1. **Serialisierung:** `SchemaNodeStructureBuilders.buildColumns` iteriert
   `columns.entries.sortedBy { it.key }` (alphabetisch).
2. **Generate (Diff-Pfad):** `PostgresDiffTableOps`/`MysqlDiffTableOps` rendern das
   CREATE-TABLE über `op.table.columns.entries.sortedBy { it.key }`.

Die Reverse-Reader (PG/MySQL/SQLite) liefern bereits **physisch geordnete** `LinkedHashMap`s
(PG/MySQL `ORDER BY ordinal_position`, SQLite `cid`-Reihenfolge aus `PRAGMA table_info`).
Die Ordinaldaten liegen also am Reader-Rand bereits vor.

## Risiko-Entwarnung (verifiziert vor Bau)

- **`schema compare`**: `TableComparator.compareColumn` vergleicht **feldweise**
  (type/required/default/unique/references/generation), nicht `left == right`. Ein neues
  `ordinal`-Feld erzeugt deshalb **keine** falschen Diffs. Bewusst: Reihenfolge ist kein
  Migrationsgrund (DBs altern Spaltenreihenfolge i.d.R. nicht), also bleibt `ordinal`
  compare-invariant.
- **Post-Compare-Drift-Fingerprint (v3)**: `CanonicalPayload.column()` listet die Felder
  **explizit** auf (kein `toString()`), sortiert Spalten nach Name. `ordinal` fließt nicht
  ein → Operation-IDs / Migration-Artefakte bleiben stabil. Bewusst nicht aufgenommen
  (order-invariant, konsistent mit compare).

## Touchpoints

| Bereich | Datei | Änderung |
|---------|-------|----------|
| Modell | `hexagon/core/.../model/ColumnDefinition.kt` | `ordinal: Int? = null` |
| Modell | `hexagon/core/.../model/` (neu/Helper) | `Map<String,ColumnDefinition>.inOrdinalOrder()` (stabil, nullsLast) |
| Reverse | `PostgresSchemaStructureReaders.kt` | 1-basierter Laufindex (Tabellenspalten + Composite-Felder) |
| Reverse | `MysqlSchemaReader.kt` | 1-basierter Laufindex |
| Reverse | `SqliteSchemaReader.kt` | 1-basierter Laufindex |
| Serialize | `SchemaNodeStructureBuilders.kt` | `buildColumns` → `inOrdinalOrder()`; `buildColumn` schreibt `ordinal` |
| Parse | `SchemaNodeStructureParsers.kt` | `parseColumn` liest `ordinal` |
| Generate | `PostgresDdlGenerator.kt`, `MysqlDdlGenerator.kt` | Spalten-/Inline-FK-Loops → `inOrdinalOrder()` |
| Generate | `PostgresDiffTableOps.kt`, `MysqlDiffTableOps.kt` | CREATE TABLE: `sortedBy{it.key}` → `inOrdinalOrder()` |
| Generate | SQLite-Pfade (`SqliteDiffSimpleOps`, `SqliteTableDdlSupport`, `SqliteRebuildRenderer`/`-Planner`) | auf `inOrdinalOrder()` |
| Generate | `PostgresTypeSequenceDdlSupport` (Composite `CREATE TYPE … AS (…)`) | auf `inOrdinalOrder()` — **Review-Nachtrag**, schließt ADR-0021-„Single Source of Truth" |
| Schema | `spec/schema.json` | `ordinal` (optional integer) auf column-def (deckt Composite-`fields` via `$ref` ab) |
| Spec | `spec/neutral-model-spec.md`, `spec/schema-reference.md` | `ordinal`-Feld dokumentieren |
| ADR | `docs/adr/0021-column-ordinal-fidelity.md` | Entscheidung + Hybrid-Begründung (de) |

## Ordnungs-Contract (`inOrdinalOrder`)

```
columns.entries.sortedWith(compareBy(nullsLast<Int>()) { it.value.ordinal })
```

`sortedWith` ist **stabil** → Spalten ohne `ordinal` (hand-authored, Overlay-Zusatz) behalten
ihre Einfügereihenfolge und landen hinter den ordinal-getragenen Spalten. Single Source of
Truth für Serializer **und** alle Generate-Pfade.

## Definition of Done

- [x] Reverse setzt `ordinal`; Serialize schreibt es in physischer Reihenfolge; Parse liest es.
- [x] `migrate`/`generate` rendern CREATE TABLE in Ordinalreihenfolge (PG/MySQL/SQLite,
      inkl. SQLite-Rebuild).
- [x] `compare` + Fingerprint bleiben ordinal-invariant (`SchemaComparatorOrdinalTest`).
- [x] schema.json erlaubt `ordinal` auf der column-def (deckt Composite-`fields` via `$ref` ab).
- [x] Round-Trip-Test serialize → parse → generate (`SchemaColumnOrdinalRoundtripTest`,
      `PostgresDdlGeneratorOrdinalTest`). Der **live** reverse → generate Round-Trip ist
      **ausgeführt grün**: `make integration` (PG/MySQL/SQLite SchemaReader/Round-Trip-Tests
      grün; die 2 MySQL-Fehler sind nur der umgebungssensitive `E07MysqlTimeoutBench`,
      change-fremd) und `make sample-db-smoke` (echte 22-Tabellen-Pagila: reverse→generate→
      transfer→`compare == baseline`, keine unerwarteten Diffs).
- [x] Betroffene Goldens aktualisiert (verifiziert gegen Test-`actual`): `add-table`
      {pg,my,sqlite} + `alter-column-type-safe/sqlite` (CREATE+INSERT). Rebuild-Tempname-Hash
      stabil (über sortierte Op-IDs → order-invariant). `generate`-DDL-Goldens unverändert
      (Generate iterierte schon in Dokumentreihenfolge).
- [x] Docker-Build (stage `build`) grün in 2m 05s, Kover ≥ 90 % pro Modul.
- [x] ADR 0021 accepted; open/-Ticket → done/ graduiert.

## Nicht-Scope

- Reihenfolge als **Diff-Signal** (ALTER zum Umsortieren) — bewusst nicht; `compare`
  bleibt order-invariant.
- View-Spaltenreihenfolge (`ViewDefinition.columns` ist bereits eine geordnete Liste).
