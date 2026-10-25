# SQLite: gleicher Default gilt als geändert, und der Neubau blockiert an `text(n)` (Issue #54)

> **Status:** Draft mit Scope (2026-10-26). Aus Issue #54: `schema migrate` gegen SQLite plant für
> eine unveränderte `integer`-/`boolean`-Spalte mit `default` ein `AlterColumnDefault`; der daraus folgende
> Tabellenneubau blockiert, sobald die Tabelle eine `text`-Spalte mit `max_length` (oder `boolean`/`decimal`)
> trägt (`SQLITE_CAST_NOT_WHITELISTED`, Exit 8). Zwei Ursachen, ein Symptom.
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim ersten
> Implementierungs-Commit.

## Ziel

1. Ein `default`, den Datei und SQLite-Datenbank gleich ausdrücken (Zahl, Wahrheitswert), erzeugt kein
   `AlterColumnDefault`.
2. Ein Tabellenneubau, der aus einem **anderen** Grund nötig ist, blockiert nicht an einer Spalte, deren Typ
   sich nicht ändert (`text` mit `max_length`).

## Problem

Nachgestellt mit dem SQLite-CLI-Test (`schema generate` → Datenbank → `schema migrate --plan-only`, dasselbe
Schema):

| Spalte in Datei | Operationen | Plan |
| --- | --- | --- |
| `integer, required, default: 0` / `default: 5` / nullable mit `default: 0` | `AlterColumnDefault` | 0 |
| `boolean, default: false` / `default: true` | `AlterColumnDefault` | **8** (`SQLITE_CAST_NOT_WHITELISTED`) |
| `decimal(10,2), default: 0` | `AlterColumnDefault` | **8** (`SQLITE_CAST_NOT_WHITELISTED`) |
| `text, default: "x"` | keine | 0 |
| `float, default: 1.5` | keine | 0 |
| `integer, default: 0` **und** `text, max_length: 32` | `AlterColumnDefault` | **8** |

Der Neubau auf einer `text`-Spalte ohne `default` allein geht (Reporter). Auf PostgreSQL gibt es keine
`AlterColumnDefault`.

### Spuren im Code (Ursachen in P0 zu bestätigen)

* **Ursache 1 — Defaultvergleich.** Der SQLite-Reader parst `dflt_value` in
  [`SqliteTypeMapping.parseDefault`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt)
  (`toLongOrNull()` → `NumberLiteral(Long)`, `toDoubleOrNull()` → `NumberLiteral(Double)`). Die Datei trägt
  einen Zahlenwert (vermutlich `Int`/`Long`/`Double` je nach Parser) beziehungsweise `BooleanLiteral`. Die
  data-class-Gleichheit von `DefaultValue.NumberLiteral` unterscheidet die Zahlentypen (`0L` ≠ `0` ≠ `0.0`);
  der Float-Fall geht, weil beide `Double` sind. SQLite speichert `boolean` als `0`/`1`: der Reader liefert
  `NumberLiteral(0)`, die Datei `BooleanLiteral(false)`. `TableComparator` vergleicht die Defaults ohne
  Projektion.
* **Ursache 2 — Neubau und CAST.** Der Neubau berechnet die Spaltenabbildung
  ([`SqliteRebuildPlanner.computeColumnMapping`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt),
  `SqliteCastMatrix`) zwischen dem **gelesenen** Ist-Typ und dem Soll-Typ. SQLite kennt keine Länge: das
  Ist ist `Text(null)` (die Datenbank trägt `TEXT`), das Soll `Text(maxLength = 32)`. Die Abbildung hält das
  für eine Typänderung, die nicht auf der Whitelist steht, obwohl nichts umgewandelt wird. Dasselbe Muster
  für `boolean` (Ist `Integer`, Soll `Boolean`) und `decimal` (Ist `Float`/`REAL`, Soll `Decimal`).

## Richtung (nach P0 zu entscheiden)

* **Ursache 1:** (A) der Reader liefert den Default in der Form der Datei (Zahlentyp vereinheitlichen,
  `0`/`1` an einer `boolean`-Spalte als Wahrheitswert) — wirkt in `schema reverse`; (B) der Vergleich
  gleicht Zahlentypen und `0`/`1` ↔ Wahrheitswert zielbewusst an (Projektion in `TargetProjection`, nur
  SQLite). B ändert keinen Reverse-Vertrag, A ist ehrlicher.
* **Ursache 2:** die Abbildung vergleicht Ist und Soll über dieselbe Typprojektion wie der Vergleich
  (`text(32)` und `text` falten auf denselben SQLite-Typ). Ein Eintrag in der Whitelist (`text(n)` →
  `text`) wäre die kleinste Änderung, ein Netz, das die Faltung nicht sieht.
* Ob der Neubau für eine **unveränderte** Spalte überhaupt eine Umwandlung (`CAST`) braucht — ohne
  Typänderung nicht; in P0 prüfen, ob die Abbildung eine Identität erkennt.

Nicht-Ziel: `AlterColumnDefault` ohne Neubau (SQLite kann `ALTER COLUMN` nicht); echte Typwechsel.

## Phasen

* **P0 Messung** (SQLite-CLI-Test + Unit): feste Tabelle Spaltentyp × Default × Spaltenbegleiter
  (`integer`/`smallint`/`biginteger`/`boolean`/`decimal(p,s)`/`float`/`text`/`text(n)`/`date`/`timestamp`
  × Default `0`, `5`, `-1`, `true`/`false`, `1.5`, `"x"`, Funktion `current_timestamp`, kein Default × alleine,
  plus `text(n)`, plus `boolean`), mit der Reader-Ausgabe (`PRAGMA table_info`, `DefaultValue`) und den
  Operationen. Ursache 2 getrennt: ein Neubau aus anderem Grund (`AddConstraint`/`AlterColumnNullability`)
  an einer Tabelle mit `text(n)`, `boolean`, `decimal`, `varchar`-Spalten ohne Default.
* **P1 Entscheidung:** A oder B je Ursache 1; Ursache 2: Projektion oder Whitelist; Fingerabdruck
  (Reader-Ausgabe ändert sich → `v20`?); ADR-Bedarf (Reverse-Vertrag).
* **P2 Umsetzung** nach P1: Unit-Tests, SQLite-CLI-Tests (Plan-Only 0 Operationen, `--execute` mit
  additiver Änderung Exit 0, zweiter Lauf ohne Operationen, Rückweg), echte Änderung des Defaults
  (`0` → `5`) bleibt `AlterColumnDefault`, ein echter Typwechsel bleibt auf der Whitelist/dem Blocker.
* **P3 Doku:** Handbuch (nur Ist-Zustand), `CHANGELOG.md`; `spec/ddl-generation-rules.md` (Defaults auf
  SQLite) prüfen.

## Nicht-Scope

* Andere Dialekte (PostgreSQL meldet keine `AlterColumnDefault`).
* Der `SQLITE_CAST_NOT_WHITELISTED`-Blocker für **echte** Typwechsel.

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der ohne die Änderung an der erwarteten Stelle
  fehlschlägt (`AlterColumnDefault`, `SQLITE_CAST_NOT_WHITELISTED`, Exit 8); die neuen Tests werden einmal
  mit absichtlich falscher Zusicherung rot gesehen.
* Eine echte Defaultänderung bleibt eine Operation.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`,
  `make solid-suppression-gate`, `make docs-check`; bei einem ADR `make doc-immutable
  RANGE=origin/main..HEAD`.
