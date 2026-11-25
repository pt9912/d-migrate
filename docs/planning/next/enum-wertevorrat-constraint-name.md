# Inline-Enum: Name für den Wertevorrat-CHECK (`values_constraint`)

- **Status**: Draft mit Scope (2026-11-25). **Wartet auf die Eigner-Entscheidung** im
  [ADR 0069](../../adr/0069-name-des-wertevorrat-checks-einer-inline-enum.md) (`proposed`): A (Feld an der Spalte),
  B (dokumentieren) oder C (Konvention auch für PostgreSQL). Dieser Plan beschreibt A. Fällt die Wahl auf B,
  schrumpft er auf P5 (Doku).
- **Ziel**: Wer den Namen des CHECKs braucht, der den Wertevorrat einer Spalte durchsetzt, schreibt
  `type: enum, values: [...], values_constraint: <name>` statt eines rohen `CHECK … IN (…)`. Der Name bleibt auf
  PostgreSQL, SQLite, SQL Server und Oracle stabil. Datei gegen Datei kennt der Up den Namen des alten CHECKs.
- **Herkunft**: Nebenbefund aus Issue #76 und #81; Anlass: Das Modell kann den Namen heute nicht ausdrücken.
- **Vorbedingungen**: ADR 0069 `accepted`. Der Fingerabdruck steigt auf `schema-fingerprint-v26`.

## Ausgangslage (gemessen 2026-11-25)

| Dialekt | `type: enum, values: [...]` rendert | Name des CHECK |
| --- | --- | --- |
| PostgreSQL | `TEXT` + `CHECK` | keiner, Server: `<tabelle>_<spalte>_check` |
| SQLite, SQL Server, Oracle | Typ + `CONSTRAINT "ck_<tabelle>_<spalte>" CHECK` | Konvention |
| MySQL | `ENUM('…')` | kein CHECK |

Vorbilder im Modell: `ColumnDefinition.uniqueConstraintName` (YAML `unique_constraint`) und
`ReferenceDefinition.constraintName`. Die Konvention steht in `EnumValueCheck.name`,
`MssqlConstraintNames.check` und `OracleColumnConstraintHelper.enumCheckName`.

## Scope-Skizze

- **P0 — Messen.** Was liest der Reverse je Dialekt zurück, wenn der CHECK einen frei gewählten Namen trägt
  (PostgreSQL, SQLite, SQL Server, Oracle)? Bleibt der Name in der Ist-Seite stehen, und erkennt
  `EnumCheckFolding` den CHECK weiter (die Faltung ist formbasiert, nicht namensbasiert)? Gilt das auch für den
  Namen über 63 Byte (PostgreSQL kürzt) und für einen Namen, den ein anderer Constraint belegt?
- **P1 — Modell und Datei.** `ColumnDefinition.valuesConstraintName: String?`; `SchemaNodeParser` und
  `SchemaNodeBuilder` lesen und schreiben `values_constraint` (nur wenn gesetzt); `spec/schema.json` und
  `spec/schema-reference.md`. Validierung: an einer Spalte ohne Inline-Enum (anderer Typ, `ref_type`) ein Fehler mit
  neuem Code im Code-Ledger.
- **P2 — Erzeugen.** PostgreSQL, SQLite, SQL Server und Oracle benutzen den Namen in `CREATE TABLE` und in
  `ADD COLUMN`/`ALTER`; ohne Feld bleibt das Ergebnis byte-gleich (Golden-Master unverändert). MySQL ignoriert das
  Feld mit einer Warnung (neuer W-Code).
- **P3 — Up und Down.** Steht der Name in der Ist-Seite, löst der Up den alten CHECK unter diesem Namen. Dann
  entfällt für eine benannte Inline-Enum `POSTGRES_ENUM_CHECK_NAME_UNKNOWN` (#81) auch Datei gegen Datei. Der Down
  legt den neuen CHECK unter dem Namen an, den der Up vergeben hat, und löst ihn deshalb ohne Raten. Der unbenannte
  Fall bleibt blockiert; die Diagnose nennt als Ausweg `values_constraint` zu setzen.
- **P4 — Vergleich und Fingerabdruck.** Setzt die Soll-Seite einen Namen und die Ist-Seite führt den CHECK unter
  anderem Namen, plant `schema migrate` `DROP` und `ADD`. Ohne gesetzten Namen bleibt der Name ohne Wirkung wie
  heute. `schema compare` meldet den Namensunterschied als Fund. Der Name steht in der kanonischen Nutzlast nur,
  wenn gesetzt; `MigrationFingerprint.ALGORITHM` = `schema-fingerprint-v26` mit KDoc-Eintrag.
- **P5 — Doku.** `spec/ddl-generation-rules.md` (Namen je Dialekt, Verhalten von Up und Down),
  `spec/schema-reference.md`, das Anwenderhandbuch („Werteliste mit festem Namen" statt des rohen CHECK am Beispiel)
  und der CHANGELOG samt Hinweis auf den neuen Algorithmus-Tag.

## Akzeptanz (DoD)

**Fachlich, gemessen gegen PostgreSQL 18 (live) und die drei weiteren Dialekte:**
* Eine Datei mit `type: enum, values: [...], values_constraint: ck_a_state` erzeugt auf PostgreSQL, SQLite,
  SQL Server und Oracle den CHECK **unter diesem Namen**; auf MySQL entsteht das `ENUM` und eine Warnung.
* `schema migrate --target db:` gegen eine so angelegte Datenbank plant 0 Anweisungen, auch beim zweiten Lauf.
* Eine erweiterte und eine verkürzte Werteliste ersetzen den CHECK unter seinem Namen, auch **Datei gegen Datei**
  ohne `POSTGRES_ENUM_CHECK_NAME_UNKNOWN`. Danach nimmt die Datenbank den neuen Wert an.
* `--generate-rollback` rendert den Down ohne Raten und stellt den alten CHECK unter dem alten Namen her.
* Ein abweichender Name in der Datenbank (etwa `<tabelle>_<spalte>_check` aus einer früheren Version) plant
  `DROP`/`ADD` auf den gesetzten Namen; ohne gesetzten Namen plant er nichts.
* Dasselbe Schema **ohne** `values_constraint` erzeugt dasselbe DDL wie vorher (DDL-Goldens unverändert).
* `values_constraint` an einer Spalte ohne Inline-Enum (`text`, `ref_type`) ist ein Validierungsfehler mit Pfad.

**Rot-Beleg:** Je Dialekt scheitert der neue Test ohne die Änderung; den Fehlschlag einmal sehen und die Rücknahme
an der Ausgabe prüfen.

**Fingerabdruck:** Ein Schema ohne das Feld hasht bis auf den Algorithmus-Tag wie zuvor (Test gegen den bisherigen
Hash-Aufbau); mit Feld hasht es anders. `v26` ist in den Test-Pins nachgezogen, der CHANGELOG nennt die Folge für
gespeicherte Artefakte.

**Gates:**
* `make docker-check MODULES=":hexagon:core :hexagon:application :adapters:driven:formats :adapters:driven:driver-postgresql :adapters:driven:driver-sqlite :adapters:driven:driver-mssql :adapters:driven:driver-oracle :adapters:driving:cli"`.
  Ändert sich eine geteilte Signatur (`ColumnDefinition`), zusätzlich einmal `make docker-test` ohne `MODULES=`.
* `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"` und die Module der drei anderen Dialekte,
  soweit der Plan ihr Verhalten ändert.
* `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
* Auf dem Push sind „Build & Test“, „Integration Tests“ und „MCP-E2E Compare-Matrix“ grün; die Matrix-Erwartungen
  sind aus den Artefakten nachgemessen, nicht geraten.

**Doku:** wie in P5; ADR 0069 ist `accepted`.

## Nicht im Umfang

- **C**, also die Konvention `ck_<tabelle>_<spalte>` auch auf PostgreSQL. Es ändert das DDL jedes PostgreSQL-Schemas
  mit Inline-Enum und ist eine eigene Entscheidung (ADR 0069, Option C).
- Der **Reverse** macht aus einem benannten CHECK keine `enum`-Spalte. Er liefert weiter Text plus CHECK.
- Das **Umbenennen** eines Constraints als eigene Operation. Es bleibt Abbau und Aufbau.
