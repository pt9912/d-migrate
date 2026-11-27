# Inline-Enum: Name für den Wertevorrat-CHECK (`values_constraint`)

- **Status**: Abgeschlossen (2026-11-27, vom Eigner abgenommen). Ausgeliefert mit 1.14.0, siehe „Closure".
- **Ziel**: Wer den Namen des CHECKs braucht, der den Wertevorrat einer Spalte durchsetzt, schreibt
  `type: enum, values: [...], values_constraint: <name>` statt eines rohen `CHECK … IN (…)`. Der Name bleibt auf
  PostgreSQL, SQLite, SQL Server und Oracle stabil. Datei gegen Datei kennt der Up den Namen des alten CHECKs.
- **Herkunft**: Nebenbefund aus Issue #76 und #81; Anlass: Das Modell kann den Namen heute nicht ausdrücken.
- **Vorbedingungen**: ADR 0069 `accepted`. Der Fingerabdruck bleibt unverändert (der Name steht nicht darin).

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
- **P1 — Modell und Datei.** `NeutralType.Enum.valuesConstraintName: String?` (am Typ, nicht an der Spalte: so läuft
  eine Namensänderung über die vorhandene Typänderung); `SchemaNodeParser` und `SchemaNodeBuilder` lesen und schreiben
  `values_constraint` (nur wenn gesetzt); `spec/schema.json` und `spec/schema-reference.md`. Validierung `E126` im
  Code-Ledger: an einer Spalte ohne Inline-Enum (anderer Typ, `ref_type`, leerer Name).
- **P2 — Erzeugen.** PostgreSQL, SQLite, SQL Server und Oracle benutzen den Namen in `CREATE TABLE` und in
  `ADD COLUMN`/`ALTER`; ohne Feld bleibt das Ergebnis byte-gleich (Golden-Master unverändert). MySQL ignoriert das
  Feld mit `W165`.
- **P3 — Up und Down.** Steht der Name in der Ist-Seite, löst der Up den alten CHECK unter diesem Namen. Dann
  entfällt für eine benannte Inline-Enum `POSTGRES_ENUM_CHECK_NAME_UNKNOWN` (#81) auch Datei gegen Datei. Der Down
  legt den neuen CHECK unter dem Namen an, den der Up vergeben hat, und löst ihn deshalb ohne Raten. Der unbenannte
  Fall bleibt blockiert; die Diagnose nennt als Ausweg `values_constraint` zu setzen.
- **P4 — Vergleich und Fingerabdruck.** Setzt die Soll-Seite einen Namen und die Ist-Seite führt den CHECK unter
  anderem Namen, plant `schema migrate` `DROP` und `ADD`. Ohne gesetzten Namen bleibt der Name ohne Wirkung wie
  heute. `schema compare` (strikt) meldet den Namensunterschied als Fund, denn die Typen sind verschieden. **Der Name
  steht nicht im Fingerabdruck**: Er ist eine Funktion eines Schemas, die gelesene Datenbankseite trägt den vom Server
  vergebenen Namen, und im Hash unterschieden sich beide nach jedem `--execute`. Die Zielprojektion bekommt dafür die
  Fähigkeit `comparesValuesConstraintName` (`DialectCapabilities.namesInlineEnumCheck`: PostgreSQL, SQLite,
  SQL Server, Oracle; nicht MySQL).
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

**Fingerabdruck:** Ein Test belegt, dass zwei Enums, die sich nur im Namen unterscheiden, gleich hashen. Der Tag bleibt
`schema-fingerprint-v25`; gespeicherte Artefakte bleiben gültig.

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

- **C**, also die Konvention `ck_<tabelle>_<spalte>` auch auf PostgreSQL: **abgelehnt** (Eigner, 2026-11-25, auf
  Empfehlung; ADR 0069 nennt C als getrennte Entscheidung). Gründe:
  1. C tauscht eine sichere Blockade gegen einen möglichen stillen Fehler. Heute blockiert der Up für eine unbenannte
     Inline-Enum Datei gegen Datei, weil er den Namen des alten CHECKs nicht kennt. Mit C nähme er
     `ck_<tabelle>_<spalte>` an. Jede Datenbank, die ein früheres d-migrate angelegt hat, trägt aber
     `<tabelle>_<spalte>_check` vom Server: Das Skript löste einen CHECK, den es nicht gibt, und legte den neuen
     daneben, derselbe Defekt wie #81, nur unsichtbar. Sicher wäre C nur, wenn jede Datenbank vom neuen Stand stammt,
     und das kann d-migrate nicht prüfen.
  2. C ändert das DDL jedes PostgreSQL-Schemas mit Inline-Enum (Golden-Master, archivierte Skripte, Flyway- und
     Liquibase-Exporte; ein neu exportierter, schon angewendeter Flyway-Eintrag riskiert einen Prüfsummenfehler,
     nicht gemessen).
  3. Die Namensgrenze bleibt: PostgreSQL kürzt Bezeichner auf 63 Byte (`PostgresEnumCheckRollback.MAX_IDENTIFIER_BYTES`),
     ein langer `ck_<tabelle>_<spalte>` würde still gekürzt und stimmte nicht mehr mit dem berechneten Namen überein.
  4. C liefert nicht, was der Konsument braucht, nämlich einen selbst gewählten Namen. Den liefert `values_constraint`,
     ausdrücklich und prüfbar.

  Stattdessen bleibt die Blockade für die unbenannte Enum; ihre Meldung nennt den Ausweg (`values_constraint` setzen
  oder gegen die Datenbank migrieren). Ein Opt-in (`ddl.postgresql.…`) wird nicht gebaut, solange es niemand fordert.
- Der **Reverse** macht aus einem benannten CHECK keine `enum`-Spalte. Er liefert weiter Text plus CHECK.
- Das **Umbenennen** eines Constraints als eigene Operation. Es bleibt Abbau und Aufbau.

## Stand (2026-11-25)

- **Abweichungen vom ADR-Entwurf** (im ADR festgehalten): Das Feld steht am Typ (`NeutralType.Enum`), nicht an der
  Spalte; der Name steht nicht im Fingerabdruck, daher kein `v26`.
- **P1:** `NeutralType.Enum.valuesConstraintName`, Parser/Builder, `E126` (`SchemaColumnValidationRules.validateEnumType`,
  mit eigener Funktion wegen Detekt), `spec/schema.json`. Dabei fiel auf, dass `schema.json` auch `unique_constraint`
  nicht kannte, obwohl der Reverse es schreibt und das Spaltenobjekt keine weiteren Felder erlaubt; ergänzt, mit
  Rot-Beleg (ohne beide Felder scheitert der Vertragstest).
- **P2:** PostgreSQL (zwei Render-Pfade: `PostgresColumnConstraintHelper` und `PostgresDiffSqlBuilders.columnLine`; der
  zweite fiel erst im Live-Test auf), SQLite, SQL Server, Oracle; MySQL `W165` (`MysqlEnumCheckNameIgnored`).
- **P3:** PostgreSQL-Up löst den alten CHECK unter dem `values_constraint` der Ist-Seite, `PostgresEnumCheckUpName`
  blockiert nicht mehr, der Down (`PostgresEnumCheckRollback`) kennt beide Namen. SQL Server löst unter dem Namen der
  tragenden Seite (`MssqlConstraintNames.valueCheck`: gesetzter Name, sonst Name der gelesenen CHECK-Zeile, sonst
  Konvention). Oracle ebenso, dazu die Konvention für eine Inline-Enum ohne Namen Datei gegen Datei (vorher legte der
  Up den neuen CHECK neben den alten).
- **P4:** `EnumCheckNames` (Namen der Ist-Seite, `renamed`), `TargetProjection.comparesValuesConstraintName`,
  `DialectCapabilities.namesInlineEnumCheck`. Ohne die Fähigkeit (MySQL) bleibt der Name ohne Wirkung.
- **Tests:** `SchemaComparatorValuesConstraintNameTest` (8 Fälle, darunter Fingerabdruck), `SchemaValidatorTest` (E126),
  `YamlSchemaCodecValuesConstraintTest` (YAML/JSON-Rundlauf), je Dialekt ein Test (PostgreSQL erweitert, SQLite,
  SQL Server, Oracle, MySQL), `CliSchemaMigrateEnumCheckNameTest` (die Repro aus #81, benannt und unbenannt, E126),
  `PostgresEnumCheckNameIntegrationTest` (live: 5 Fälle). Rot-Belege: ohne die Vergleichsregel scheitern 3 Specs, ohne
  den Namen im Up 2 PostgreSQL-Specs, ohne die Felder in `schema.json` der Vertragstest.
- **Offen:** Integrationsläufe der vier anderen Dialekte, Gesamtlauf, CI.

## Closure (2026-11-27)

- **Geliefert:** `values_constraint` nach [ADR 0069](../../adr/0069-name-des-wertevorrat-checks-einer-inline-enum.md),
  P0–P5, mit 1.14.0. Abnahme durch den Eigner am 2026-11-27.
- **Zum letzten „Offen" im Stand:** Auf dem Tag `v1.14.0` liefen „Integration Tests" (alle Integrationsmodule), „Build &
  Test", Per-Module Coverage und Native Image grün.
- **Nicht festgehalten:** ein Rot-Beleg je Dialekt. Notiert ist er für die Vergleichsregel, PostgreSQL und
  `spec/schema.json`, nicht für SQLite, SQL Server, Oracle und MySQL.
- **Folgearbeit:** keine aus diesem Plan. Die unter „Nicht im Umfang" genannten Punkte sind entschieden oder bewusst
  nicht vorgesehen.
