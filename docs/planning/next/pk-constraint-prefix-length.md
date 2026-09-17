# PRIMARY-KEY-/Constraint-Präfixlängen (1.0.x)

> **Status:** Draft mit Scope (2026-06-26). Promotet aus `../open/` nach getroffener
> Richtungsentscheidung (planen, nicht in 0.9.9 implementieren).
> **Vorbedingung / Gate:** [`ADR 0012`](../../adr/0012-index-prefix-length-scope.md)
> (D-4) bleibt in Kraft, bis dieser Slice aktiviert wird — Präfixlängen sind in 0.9.9
> ausschließlich auf `IndexColumn`. Aktivierung (Move nach `../in-progress/`) erst beim
> ersten Implementierungs-Commit, frühestens 1.0.x.
> **Nachtrag (2026-09-17):** `ERROR 1170` aus der Compare-Matrix, andere Ursache
> (längenloser Text statt Präfix-PK), s. unten.

## Ziel

`PRIMARY KEY (col(n))` und Constraint-modelliertes `UNIQUE KEY (col(n))` (MySQL)
round-trip-fähig machen: Per-Spalten-Präfixlänge in den heute nackten Spaltenlisten

- `TableDefinition.primaryKey: List<String>`
- `ConstraintDefinition.columns: List<String>`

tragen, **analog zur gelieferten `IndexColumn.prefixLength`-Mechanik**
([`../done/index-prefix-length-model.md`](../done/index-prefix-length-model.md)).
Schließt die in [ADR 0012](../../adr/0012-index-prefix-length-scope.md) bewusst
offen gelassene Round-Trip-Lücke (Präfix-PK auf `TEXT`/`BLOB` → sonst `ERROR 1170`
beim Regenerieren).

## Abgrenzung (bereits abgedeckt — NICHT Scope)

- Eigenständige Präfix-Indizes auf `TEXT`/`BLOB` (`CREATE INDEX … (col(n))`) — I-08-MySQL.
- `UNIQUE` als Index (`IndexDefinition(unique = true)`) — erbt `prefixLength` über `IndexColumn`.

Offen bleibt nur das **literale `PRIMARY KEY`** und die **PG-artige Constraint-Modellierung**
von UNIQUE (`ConstraintDefinition`).

## Modellrichtung (aus ADR 0012)

Strukturierte Spalten-Einträge statt nackter Strings. Vorgeschlagene Form, gemeinsam mit
`IndexColumn` wiederverwendbar — ein `KeyColumn(name, prefixLength: Int? = null)`-Typ
(oder Wiederverwendung von `IndexColumn` ohne `direction`), sodass die Generatoren eine
einzige `col(n)`-Rendermechanik teilen. **Entscheidung Teil des Slices** (Reuse vs. eigener
Typ), nicht hier vorwegnehmen.

## Scope-Skizze (Phasen)

- **P1 — Modell + Serialisierung.** `primaryKey`/`ConstraintDefinition.columns` auf
  strukturierte Einträge heben (Default-Länge `null` = abwärtskompatibel). `spec/schema.json`:
  `primary_key`/Constraint-`columns` akzeptieren `string | {name, prefix_length}` (wie
  Index-Columns). Parser/Serializer. **DoD:** Round-Trip serialize→parse erhält Länge; nackte
  Strings bleiben gültig.
- **P2 — Vergleich + Fingerprint.** `TableComparator` (PK-Diff, Constraint-Diff) und
  `CanonicalPayload`/`MigrationFingerprint` müssen die Länge **semantisch** mitführen
  (Längenänderung = Diff; Op-ID-stabil). **DoD:** Regressionstests Längen-Diff vs.
  Längen-Identität.
- **P3 — Generatoren (3 Dialekte).** MySQL rendert `col(n)` in `PRIMARY KEY`/`UNIQUE`;
  PG/SQLite ignorieren die Länge dokumentiert (keine Präfix-Keys → Note statt Stillverlust).
  MySQL-Reverse (`MysqlMetadataQueries`/`MysqlSchemaReader`) liest `SUB_PART` für PK/Constraint.
  Interaktion mit `MysqlPrimaryKeyOrdering` (AUTO_INCREMENT-first) prüfen. **DoD:** MySQL
  reverse→generate Round-Trip einer Präfix-PK auf `TEXT`; kein `ERROR 1170`.
- **P4 — Goldens + E2E.** Migrate-/DDL-Goldens; ein `examples/sample-db`-Smoke-Case mit
  Präfix-PK. **DoD:** Vollbau grün, `make integration` + sample-db-Smoke grün.

## Blast Radius (zur Aufwandsschätzung)

`List<String>` → strukturierte Einträge ist eine **modellweite Contract-Änderung**: ~29
Nicht-Test-Main-Dateien berühren `.primaryKey`; `ConstraintDefinition.columns` zusätzlich in
MySQL-Generator/Reverse/Diff/DataWriter. Jede `joinToString`/`==`/`contains`-Nutzung muss
auf den neuen Typ. Deshalb eigener Slice (ADR 0012 „verdient einen eigenen Slice statt eines
Anhängsels").

## Akzeptanzkriterien

- Präfix-PK auf `TEXT`/`BLOB` (MySQL) überlebt reverse→serialize→generate verlustfrei;
  kein `ERROR 1170` beim Apply.
- Constraint-modelliertes `UNIQUE (col(n))` round-trippt verlustfrei.
- `schema compare`: Längenänderung in PK/Constraint = echter Diff; gleiche Länge = kein Diff.
- Migration-Fingerprint/Operation-IDs bleiben für Längen-Identität stabil.
- Nackte (längenlose) PK/Constraint-Schemata bleiben vollständig abwärtskompatibel.
- ADR 0012 wird beim Slice-Start abgelöst/ergänzt (neuer ADR „Präfixlängen auch auf
  PK/Constraints", der D-4 für 1.0.x aufhebt).

## Nachtrag 2026-09-17 — `ERROR 1170` in der Compare-Matrix, aus einer anderen Ursache

Die 5x5-Compare-Matrix des Compare-Slices
([`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md),
„Offen") scheitert in der Zelle **SQLite → MySQL** mit `ERROR 1170` (gepinnt als
`APPLY-FAIL` in
[`examples/mcp-e2e/expected/compare-matrix.env`](../../../examples/mcp-e2e/expected/compare-matrix.env)).
Derselbe Fehler, aber **nicht** der Fall dieses Plans:

1. Die Fixture trägt `email: { type: text, max_length: 254, unique: true }`.
2. Der SQLite-Generator rendert jeden `text` als `TEXT` und lässt die Länge
   fallen
   ([`SqliteTypeMapper`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt));
   der Reverse liest deshalb `text` **ohne** `max_length`.
3. Der MySQL-Generator rendert daraus `TEXT` mit `UNIQUE` — ohne Präfixlänge
   lehnt MySQL den Schlüssel ab.

Hier fehlt also nicht die Präfixlänge im Modell (die hätte die Quelle gar nicht),
sondern die Länge der Spalte. Die Mechanik dieses Plans (`KeyColumn` mit
`prefixLength`) schließt die Zelle nur, wenn ein Generator die Länge selbst
**ableitet** — das wäre eine eigene Entscheidung. Näher liegen zwei andere Wege,
die bei der Aktivierung mitzuwiegen sind:

- **MySQL meldet statt zu scheitern:** ein `UNIQUE`/`PRIMARY KEY` auf einer
  längenlosen Text-Spalte wird mit Code und Hinweis ausgelassen
  (`skipped_objects`, Exit 8) statt als DDL erzeugt, die der Server ablehnt.
- **SQLite behält die Länge:** der Generator schreibt `VARCHAR(n)` (in SQLite
  gültig, Typaffinität `TEXT`), der Reverse liest sie zurück. Das ist eine
  Fidelity-Frage wie im Reader-Slice
  ([`reader-treue-spatial-array-json.md`](reader-treue-spatial-array-json.md),
  Posten D3 — dort für PostgreSQL).

Hinter `ERROR 1170` wartet in derselben Zelle vermutlich ein zweiter Fehler:
die doppelten Fremdschlüsselnamen des SQLite-Reverse (Reader-Slice, Posten D5).

## Aktivierungs-Trigger

Ein konkreter Pilot-/Anwenderfall mit Präfixlänge in `PRIMARY KEY`/Constraint (insb.
Präfix-PK auf `TEXT`/`BLOB`). Bis dahin bleibt der Eintrag hier (geplant, nicht aktiv).
