# Cross-Dialect-Partitionierung (AP6 — MySQL-Reverse + verlustbehaftetes RANGE-Mapping)

> **Status:** Vorschlag (Scope ausgearbeitet, noch nicht aktiv). Folge-Slice der
> graduierten [Partitions-Hierarchie-Rekonstruktion](../done/partition-hierarchy-reconstruction.md)
> (PG-first), gemäß [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md),
> Punkt 3+4 (Cross-Dialect-Form-Divergenz + MySQL-Reverse als eigener Slice).
> **Trigger:** Die PG-first-Scheibe hat das strukturierte `PartitionDefinition`-Modell,
> den PG-Reverse-Capture (Kinder + Grenzen + kind-lokale Indizes) und den
> partitions-bewussten Comparator/Fingerprint geliefert. Der MySQL-Pfad konsumiert das
> Modell heute **nur teilweise** und es gibt **keinen** MySQL-Reverse — ein voller
> Cross-Dialect-Round-Trip fehlt.
> **Aktivierungsbedingung:** wenn Cross-Dialect-Partitionierung (PG↔MySQL) priorisiert
> wird; aktuell bricht `make sample-db-cross-smoke-pg2my` an genau diesen Lücken.

## Ziel

Ein partitioniertes Schema verlustarm zwischen PostgreSQL und MySQL round-trippen:
MySQL-Partitionen **reverse-erfassen** (heute partitions-blind) und MySQL-**Generate**
das strukturierte Modell vollständig konsumieren lassen — inkl. der bewussten,
ADR-pflichtigen Verlustpunkte beim RANGE-Mapping.

## Arbeitspakete

- **AP6.1 — MySQL-Reverse-Capture der Partitionen (dieselbe AP1-Klasse Arbeit für MySQL).**
  Kein MySQL-Reader befüllt heute `PartitionConfig.partitions` (nur `MysqlDdlGenerator`
  konsumiert es). MySQL exponiert Partitionen über `information_schema.PARTITIONS`
  (PARTITION_NAME, PARTITION_METHOD, PARTITION_EXPRESSION, PARTITION_DESCRIPTION,
  SUBPARTITION_*). Strategie/Schlüssel + Kind-Grenzen ins strukturierte Modell heben —
  analog zum PG-`PostgresPartitionBoundParser`, aber für MySQLs `VALUES LESS THAN`/
  `VALUES IN`-Form. Bound-Parser/Normalisierer ist auch hier der Hotspot.

- **AP6.2 — Verlustbehaftetes RANGE-Mapping (semantischer Carve-Out, ADR-pflichtig).**
  PG-RANGE hat `from`+`to` (Lücken erlaubt); MySQL-RANGE kennt nur `VALUES LESS THAN`
  (Obergrenze, Kontiguität) — der `from`-Anteil wird verworfen (heute schon, per W112).
  **Zusätzlich:** MySQL-RANGE auf einer Datums-/Zeitspalte braucht einen **Integer-Ausdruck**
  (`UNIX_TIMESTAMP(col)` / `TO_DAYS(col)` / `YEAR(col)`) oder `RANGE COLUMNS(col)` — eine rohe
  timestamptz-Grenze wie Pagilas `'2022-02-01 00:00:00+00'` lehnt MySQL ab. Dieser Mapping-
  Entscheid (welche Wrapper-Funktion bzw. `RANGE COLUMNS`) ist die eigentliche Designarbeit
  und braucht eine **eigene ADR** (analog fulltext/geometry-Carve-Out-Muster).

- **AP6.3 — MySQL-Generate emittiert `partition.indices` (Review-Befund AP2a).**
  `MysqlIndexPartitionDdlHelper` iteriert heute nur `table.indices` und **verwirft die
  kind-lokalen Partition-Indizes still** — PG→MySQL verlöre sie. MySQL teilt Indizes über
  alle Partitionen (kein per-Partition-Index wie PG), also ist die Abbildung selbst ein
  Carve-Out: kind-lokale Indizes müssen entweder auf den Parent gehoben oder per Note
  gemeldet werden. (SQLite verwirft via `E055` die ganze Partitionierung → dort moot.)

- **AP6.4 — Voller Cross-Dialect-Round-Trip + Harness.** `sample-db-cross-smoke-pg2my`
  grün bekommen (PG→MySQL) und ein MySQL→PG-Pendant; Comparator/Fingerprint sind seit der
  PG-Scheibe bereits partitions-bewusst.

## Akzeptanzkriterien (Skizze — schärfen beim Move nach `in-progress/`)

- MySQL-Reverse befüllt `partitions` für RANGE/LIST/HASH (Unit-Test je Strategie + Live).
- PG→MySQL: eine RANGE-partitionierte Tabelle (Pagila `payment`) erzeugt **gültiges**
  MySQL-DDL (Integer-Wrapper bzw. `RANGE COLUMNS`), wendet sauber an, Zeilen-Parität +
  Nicht-Duplikation; der Verlust (`from` verworfen, Datums-Wrapper) ist per Note (W112 o. ä.)
  gemeldet und in einer ADR begründet.
- Kind-lokale Indizes (AP6.3) gehen nicht **still** verloren — entweder abgebildet oder
  per Note gemeldet.
- `make sample-db-cross-smoke-pg2my` grün; Baselines neu gepinnt + erklärt.

## Abgrenzung / Nicht-Ziel

- **Sub-Partitionierung** bleibt OUT (wie in der PG-Scheibe, ADR 0019).
- **Performance** (paralleler per-Partition-Transfer) bleibt Performance-Phase.
- SQLite: keine Partitionierung → `E055` unverändert.

## Bezug

- Gate-ADR der Modellform: [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md).
- Vorläufer-Scheibe (PG-first, geliefert): [`../done/partition-hierarchy-reconstruction.md`](../done/partition-hierarchy-reconstruction.md).
- Anforderung **LN-008** „Partitionierung für große Tabellen"
  ([`../../../spec/lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md)) —
  Cross-Dialect-Teil.
