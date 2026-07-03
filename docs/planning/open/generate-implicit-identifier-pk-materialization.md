# Generate materialisiert den impliziten `identifier`-PK nicht (MySQL/SQLite-Kanten)

> Status: **Draft (Trigger Watch)**
> Trigger: AP0-Probe-Matrix des Typ-Kanonisierungs-Slices
> ([`../in-progress/postcompare-type-canonicalization-slice.md`](../in-progress/postcompare-type-canonicalization-slice.md),
> Status-Update 2026-07-03) — zwei Runtime-Execution-Fehler auf spec-validen Schemata.
> Aktivierungsbedingung: Scope-Schnitt (Ziel + Arbeitspakete + Akzeptanz).
> **Vormerkung (2026-07-03): Kandidat für den direkten Folge-Slice nach der
> Post-Compare-Kanonisierung** — die Lösung nutzt dieselbe Ableitungsregel wie
> `effectivePrimaryKey` (Fingerprint v3), und der dort gelieferte Typ-Smoke
> (`sample-db-types-smoke`) enthält die `identifier`-/`identifier_pk`-Proben je
> Dialekt bereits als Sensorik.

## Befund (live belegt 2026-07-03, Runtime-Image)

`spec/neutral-model-spec.md` Abschnitt 13.1 definiert `identifier` als PK-tragend
(explizit **oder** über den Typ; fehlender expliziter PK ist nur Warnung E008). Der
Fingerprint kanonisiert das seit v3 (`effectivePrimaryKey`). Der **Generate-Pfad**
materialisiert die implizite PK aber nicht — mit zwei dialektabhängigen Fehlerkanten
(beides `migrate --execute` Exit 5 via **`executionError`**, keine Post-Compare-Drift):

1. **MySQL, `identifier` ohne `primary_key`:** gerendert wird
   `` `id` INT NOT NULL AUTO_INCREMENT `` **ohne** KEY-Klausel → MySQL lehnt ab:
   „Incorrect table definition; there can be only one auto column and it must be
   defined as a key". Ein spec-valides identifier-only-Schema ist auf MySQL nicht
   anlegbar.
2. **SQLite, `identifier` MIT explizitem `primary_key: [id]`:** gerendert wird
   `"id" INTEGER PRIMARY KEY AUTOINCREMENT` **plus** Tabellen-Level
   `PRIMARY KEY ("id")` → SQLITE_ERROR (doppelter PK). Die implizite Variante (ohne
   `primary_key`) ist auf SQLite grün; die explizite ist es nicht — genau invers zu
   MySQL.

PostgreSQL rendert `SERIAL` ohne PK (DDL valide, aber die implizite PK fehlt im Ziel;
der zugehörige Post-Compare-/Reverse-Aspekt ist im Ticket
[`sqlite-reverse-identifier-64bit-narrowing.md`](sqlite-reverse-identifier-64bit-narrowing.md)
festgehalten).

## Lösungsrichtung (Skizze)

Der Generate leitet den effektiven PK nach derselben Regel ab wie der Fingerprint
(genau **eine** `identifier`-Spalte und leeres `primary_key` → diese Spalte ist PK;
ambige Fälle unverändert), und die Dialekt-Renderer dedupen die PK-Materialisierung
(SQLite: kein Tabellen-Level-`PRIMARY KEY`, wenn die Spalte bereits inline
`INTEGER PRIMARY KEY AUTOINCREMENT` trägt; MySQL: KEY-Klausel für die
AUTO_INCREMENT-Spalte).

## Akzeptanz-Skizze

- identifier-only-Schema (ohne `primary_key`): `migrate --execute` Exit 0 auf
  **MySQL** (heute Runtime-Fehler) — PK im Ziel materialisiert.
- `identifier` + explizites `primary_key: [id]`: Exit 0 auf **SQLite** (heute
  SQLITE_ERROR).
- Bestehende Grün-Fälle bleiben grün (SQLite implizit; MySQL mit explizitem PK,
  AP0-Probe `identifier_pk` Exit 0).
