# SQLite: ein eindeutiger Index gilt gegen die Datenbank als geändert (Issue #53)

> **Status:** Draft mit Scope (2026-10-26). Aus Issue #53: `schema migrate` gegen SQLite plant für
> einen unveränderten eindeutigen Index `DropConstraint` + `AddIndex`; `--execute` blockiert mit
> `TRANSACTION_SCOPE_UNSUPPORTED` (Exit 8). Gleiche Fehlerklasse wie #51/#52 (ein Objekt, das
> beide Seiten gleich meinen, gilt als geändert), anderer Ort.
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim ersten
> Implementierungs-Commit.

## Ziel

Ein eindeutiger Index (`indices: [{ unique: true }]`), den Datei und SQLite-Datenbank gleich
ausdrücken, erzeugt keine Operation. `schema migrate --execute` führt dann eine rein additive Änderung
aus, statt mit Exit 8 zu blockieren.

## Problem

Nachgestellt mit dem SQLite-CLI-Test (`schema generate` → Datenbank → `schema migrate --plan-only`
mit **demselben** Schema); Fälle nach dem Muster der Messungen in
`custom-type-fields-leer-gegen-null.md`:

| Index in Datei | Operationen gegen die erzeugte Datenbank |
| --- | --- |
| eindeutig, **eine** Spalte, ohne Prädikat (`unique: true`) | `DropConstraint ["t","_unique_j"]`, `AddIndex ["t","ix_t"]` |
| eindeutig, **zwei** Spalten, ohne Prädikat | `DropConstraint ["t","ix_t"]`, `AddIndex ["t","ix_t"]` |
| eindeutig mit Prädikat (`where`) | keine |
| nicht eindeutig | keine |
| Spalte mit `unique: true` (kein Index) | keine |

Auf PostgreSQL geht derselbe Fall. Mit `--execute` und einer weiteren additiven Änderung (`AddColumn`)
liegen `DropConstraint`/`AddIndex` mit dem `AddColumn` in einem Strom, was
`MigrationStreamClassifier.unsupportedTransactionScopeReason` ablehnt
(`TRANSACTION_SCOPE_UNSUPPORTED`; Mischumfang wie in #50).

### Spuren im Code (Ursache in P0 zu bestätigen)

* Der Reader
  ([`SqliteSchemaReader`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt))
  liest UNIQUE-Constraint-Autoindizes („AP4“, Zeilen um 248–256, 308, 415) und filtert eindeutige
  Mehrspalten-Indizes aus den Indizes heraus („multi-col unique already in constraints“, Zeile um 368).
  Ein `CREATE UNIQUE INDEX` (Ursprung `c` in `PRAGMA index_list`) sieht aus wie ein
  Tabellen-`UNIQUE`; das Reverse macht daraus einen Constraint beziehungsweise `column.unique`, die
  Datei beschreibt einen Index.
* Der Vergleich
  ([`TableComparator`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableComparator.kt),
  `NormalizedConstraints.singleColumnUnique`, `syntheticUniqueConstraint` → `_unique_<spalte>`) kennt
  Spalten-`unique` und Constraint, setzt aber den **Index** mit `unique: true` nicht mit ihnen gleich.
  Beim Zweispalten-Fall trägt der `DropConstraint` den Namen des Index (`ix_t`) — ein Hinweis, dass der
  Reader den Index unter dem Indexnamen als Constraint zurückgibt.

## Richtung (nach P0 zu entscheiden)

* **A — Reader:** nur ein Autoindex mit Ursprung `u` (aus einem `UNIQUE`-Constraint) wird Constraint
  oder `column.unique`; ein Index mit Ursprung `c` bleibt ein Index. Wirkt in `schema reverse` und
  `schema compare` (Reverse-Vertrag), löst die Ursache. Ein Reverse einer Datenbank mit `CREATE UNIQUE
  INDEX` schreibt dann einen Index statt eines Constraints.
* **B — Vergleich:** ein eindeutiger Index ohne Prädikat und ein einspaltiger/mehrspaltiger
  `UNIQUE`-Constraint über dieselben Spalten gelten als gleich (zielbewusst, SQLite). Reverse bleibt;
  der Vergleich faltet. Auf anderen Dialekten sind Index und Constraint verschiedene Objekte (PostgreSQL:
  `DROP CONSTRAINT` gegen `DROP INDEX`) — das Gate wäre dialektbezogen, mit Vertragsfolge für
  Operationen (`AddIndex` ersetzt keinen Constraint).
* **C — Beides:** A für die Herkunft, B als Netz für Datenbanken, die tatsächlich einen Constraint
  tragen, während die Datei einen Index schreibt (oder umgekehrt) — dann ist die Aussage „gleiche
  Eindeutigkeit“ legitim, aber der Umbau ist nur auf SQLite möglich (kein `ALTER TABLE ADD/DROP
  CONSTRAINT`).

Nicht-Ziel: `UNIQUE`-Constraints, die wirklich als Constraint in der Datenbank stehen und in der Datei
als Constraint stehen (laufen heute).

## Phasen

* **P0 Messung** (SQLite-CLI-Test + Reader-Unit-Test; Muster `CliSchemaMigrateViewRebuildSqliteTest`):
  feste Fallliste mit (Datei, was der Reader liefert, Operationen): eindeutiger Index 1 Spalte / 2 / 3
  Spalten; mit `where`; mit `COLLATE`/absteigend; Index über Ausdruck; `unique: true` an der Spalte;
  Tabellen-`UNIQUE (a, b)`; **Datei mit Constraint, Datenbank mit Index** und umgekehrt; benannter
  Constraint; Fremdschlüsselspalte mit Index. Dazu `PRAGMA index_list` (Ursprung `c`/`u`/`pk`). Die
  Reader-Ausgabe je Fall festhalten. P0 endet, wenn die Tabelle voll ist.
* **P1 Entscheidung:** A, B oder C nach der Messung; Auswirkung auf `schema reverse`-Goldens und
  `schema compare`; ob ein Fingerabdruck-Bump (`v20`) nötig ist (der Reader-Ausgang ändert sich);
  ADR-Bedarf (Reader-Vertrag, Anknüpfung ADR 0061/0060).
* **P2 Umsetzung** nach P1: Unit-Tests (Reader, Comparator), SQLite-CLI-Tests (Plan-Only `operationsTotal`
  0 für den unveränderten Fall, `--execute` mit additiver Änderung Exit 0, zweiter Lauf ohne Operationen,
  Rückweg `schema rollback`), echte Änderung (anderer Index, andere Spalten, Unique entfernt) bleibt
  sichtbar.
* **P3 Doku:** Anwenderhandbuch (SQLite-Indizes, nur Ist-Zustand), `CHANGELOG.md`;
  `spec/ddl-generation-rules.md` (Index/UNIQUE auf SQLite) und `spec/schema-reference.md` prüfen.

## Nicht-Scope

* `UNIQUE`-Constraints auf anderen Dialekten.
* Der Neubau selbst (Reihenfolge, Temp-Namen) — nur dass er nicht ausgelöst wird.

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der ohne die Änderung an der erwarteten Stelle
  fehlschlägt (`DropConstraint`/`AddIndex`, bei `--execute` zusätzlich Exit 8
  `TRANSACTION_SCOPE_UNSUPPORTED`); die neuen Tests werden einmal mit absichtlich falscher Zusicherung rot
  gesehen.
* Eine echte Änderung eines eindeutigen Index bleibt eine Operation.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`,
  `make solid-suppression-gate`, `make docs-check`; bei einem ADR `make doc-immutable
  RANGE=origin/main..HEAD`.
