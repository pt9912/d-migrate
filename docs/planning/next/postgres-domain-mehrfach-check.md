# PostgreSQL: der Reader verliert bei einer Domain alle CHECKs bis auf den letzten (Folge von #52)

> **Status:** Draft mit Scope (2026-10-26). Abgetrennt aus dem früheren Sammelplan zu Domain-Länge/-Array (dort in drei Pläne
> geteilt). Kleiner Reader-Fehler ohne Vertragsänderung. **Vorbedingung:** keine. **Aktivierung** (Move nach
> `../in-progress/`) beim ersten Implementierungs-Commit; dann GitHub-Issue anlegen und hier verlinken.
> Bezug: ADR 0061 („Weiter nicht erkannt": mehrere CHECKs).

## Ziel

Der PostgreSQL-Reader gibt **alle** `CHECK`-Constraints einer Domain zurück. Eine Datei und eine Datenbank, die dieselben
Prüfungen führen, erzeugen keine `AlterCustomType`-Operation.

## Problem (gemessen in #52, P0)

`CREATE DOMAIN d AS integer CONSTRAINT c1 CHECK (VALUE > 0) CONSTRAINT c2 CHECK (VALUE < 10)` → Reader liefert nur
`VALUE < 10`. Ursache:
[`PostgresTypeMetadataQueries.listDomainTypes`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMetadataQueries.kt)
verbindet `pg_constraint` ohne Aggregation (`LEFT JOIN … contype = 'c'`), zwei CHECKs ergeben zwei Zeilen;
[`readPostgresCustomTypes`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt)
schreibt `result[name] = …`, die letzte Zeile gewinnt (Reihenfolge nicht festgelegt). Ein CHECK geht still verloren; eine Datei
mit beiden gilt als geändert, eine mit dem ersten wird übersehen.

Weitere Reader-Verluste an Domain-`CHECK`s, in P0 zu messen: `NOT VALID` (`pg_get_constraintdef` hängt ` NOT VALID` an;
`normalizeDomainCheck` behält es, die äußere Klammer wird dann nicht mehr entfernt → ungültiges DDL `CHECK (… NOT VALID)`),
**Domain über Domain** mit eigenem CHECK (der innere geht verloren, `bt.typname` nennt nur den Namen).

## Richtung (nach P0 zu entscheiden)

* Der Reader aggregiert die Prädikate (`string_agg` nach `conname`, `COLLATE "C"` für stabile Reihenfolge) und führt sie mit
  `AND`, **jedes in Klammern**, ein einzelnes CHECK bleibt unverändert (`n = 1` nicht klammern). Semantik: Domain-CHECKs
  verwerfen nur bei `false`, `a AND b` ist äquivalent; Constraint-Namen gehen verloren (das Modell kennt eine `check`-Zeichenkette).
* **Vergleich mit der Autorenform:** der zusammengesetzte Ausdruck `(VALUE > 0) AND (VALUE < 10)` gegen `VALUE > 0 AND
  VALUE < 10` ist die Grenze „`AND` ohne Serverklammern" aus ADR 0061 Entscheidung 2 — dieser Plan beseitigt den Reader-Verlust,
  nicht diese Grenze. Das Ziel der Akzeptanz ist entsprechend die Klammerform.
* **Round-Trip:** `schema generate` schreibt **einen** `CHECK`; zwei benannte Constraints werden beim Zurückschreiben zu einem
  (zu messen: Drift gegen eine Datenbank, die zwei benannte trägt).
* `NOT VALID` → Notiz im Reader (Constraint nicht in `check` übernehmen oder kennzeichnen); Domain über Domain → die inneren
  CHECKs auflösen oder als Grenze benennen (P1).
* Filter `contype = 'c'` erhalten (NOT NULL, `contype = 'n'`, nicht als CHECK einmischen).

Fingerabdruck: nur der Reader-Ausgang ändert sich (bei Domains mit mehreren CHECKs); die Projektion der Datei nicht. Ob das einen
Tag-Bump trägt, ist in P1 zu entscheiden (Präzedenz: ADR 0061 hob für die Reader-Normalform an; hier ändert sich der Hash nur
für den Verlustfall). Nächste freie ADR-Nummer, falls nötig: nach 0063.

## Phasen

* **P0 Messung** (Integrationstest gegen PostgreSQL, Muster `PostgresCustomTypeFileFormMigrateIntegrationTest`): zwei CHECKs
  (benannt/unbenannt, unsortierte Namen, `c10` vor `c2`), drei CHECKs, `NOT VALID`, Domain über Domain mit eigenem CHECK,
  Domain mit `NOT NULL` + CHECK (PG-Versionen des Testimages angeben). Je Fall Reader-Ausgabe und Diff gegen die Autorenform.
* **P1 Entscheidung:** Aggregationsform, `NOT VALID`, Domain über Domain, Tag/ADR.
* **P2 Umsetzung:** Reader-Unit-Test (Zeilen-Stub mit mehreren CHECKs), Integrationstest (zweiter Lauf `operationsTotal` 0,
  Rückweg, echte Änderung eines der CHECKs bleibt `AlterCustomType`).
* **P3 Doku:** Handbuch §3.21 (die Grenze „mehrere `CHECK`-Constraints" streichen/nachführen, nur Ist-Zustand), `CHANGELOG.md`;
  `spec/ddl-generation-rules.md` §6.3 (Reader-Regel).

## Nicht-Scope

`AlterCustomType` auf PostgreSQL rendern; Länge/Array (`postgres-domain-laenge-array.md`); `DEFAULT`/`NOT NULL`/Kollation
(`domain-default-not-null-modell.md`).

## Akzeptanz

* Jede gemessene Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt; absichtlich falsche
  Zusicherung einmal rot gesehen.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=`,
  `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"` (das ganze Modul, Detekt eingeschlossen),
  `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
