# SQLite: ein Neubau behält das Merkmal der Datenbank, Abweichung der Datei wird gewarnt (Produktentscheidung zu Issue #67)

> **Status:** Entschieden (Eigner, 2026-10-26): **Option C plus Warnung**. Draft mit Scope für die Umsetzung; noch kein Implementierungs-Commit.
> **Vorbedingung:** keine mehr. **Reihenfolge der Lieferung:** (1) dieser Slice (Code, Test, Entscheidung), (2) Rollback-Slice
> [`sqlite-rollback-without-rowid-vorher.md`](sqlite-rollback-without-rowid-vorher.md) P0/P1, (3) Doku (Spec mit dem Code, Handbuch danach, `CHANGELOG.md`-Befund).
> Dieser Slice ist der **einzige**, der den Hinweg-Satz in Spec §3.5 und Handbuch §3.17 ändert; der Rollback-Slice ändert dort nur den Rückweg.
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit; beim Move die relativen `../in-progress/`-Links auf `./` umstellen und die
> Rückverweise im Schwesterslice sowie den Nachtrag im #60-Slice (`sqlite-without-rowid-im-neubau.md`, `../next/` → `./`) nachziehen; nur Planning-Dateien, kein ADR-`consulted:`.
> GitHub-Issue: [#67](https://github.com/pt9912/d-migrate/issues/67) (Vorschlag 1 Alternative, Vorschlag 2).

## Ziel

Ein Tabellenneubau behält `WITHOUT ROWID` genau dann, wenn die **Datenbank** die Tabelle so führt. Die Datei ändert das Merkmal beim Neubau nicht mehr. Führt die Datei
`metadata: { without_rowid: true }` gegen eine Rowid-Tabelle (Fall A), läuft die Migration weiter und warnt.

## Problem

Handbuch §3.17 (`docs/user/anwenderhandbuch.md`, Absatz „`WITHOUT ROWID`") sagt zugleich: ein Neubau erhält das Merkmal, „wenn die Datenbank **oder die Datei** die Tabelle so führt"
(Fall A: Umwandlung hin) **und** „`schema migrate` plant das Merkmal selbst nicht: es ändert eine bestehende Tabelle weder hinzu noch weg". Beides zugleich stimmt für Fall A nicht.
Die Regel stammt aus [`sqlite-without-rowid-im-neubau.md`](../in-progress/sqlite-without-rowid-im-neubau.md) P1 (Datei oder Datenbank; dort als Folge in Kauf genommen).
`spec/ddl-generation-rules.md` §3.5 trägt denselben Satz. Fall A hatte bisher keinen Test — deshalb rutschte #67 durch.

## Entscheidung (Eigner, 2026-10-26): Option C plus Warnung

* Ein Neubau (Up) nimmt das Merkmal **nur aus der Datenbank** (Zustand vor dem Lauf). Weicht die Datei ab (Datei `true`, Datenbank Rowid = Fall A), läuft die Migration
  **weiter** (kein Block) und meldet eine **Warnung** im Plan (`--plan-only`) **und** im `--execute`-Lauf: `schema migrate` ändert das Merkmal nicht.
* Fall C2 (Datei ohne Angabe, Datenbank `WITHOUT ROWID`) bleibt unberührt: keine Warnung. Ein explizites `without_rowid: false` ist von „fehlt" nicht unterscheidbar
  (wie in #60) und warnt ebenfalls nicht.
* Begründung: passt zum Handbuchsatz „weder hinzu noch weg"; `WITHOUT ROWID` ändert die Tabellensemantik und soll keine Nebenwirkung von `unique: true` sein; Fall A verschwindet im Hinweg.
* **Folge:** die Datei wirkt beim Neubau nicht mehr aufs Merkmal. Ein Weg, das Merkmal gezielt zu ändern, wäre ein eigener Slice, nur bei Bedarf.
* **Kosten:** kein bestehender CLI-Test bricht. Alle Tests in `CliSchemaMigrateWithoutRowidSqliteTest` gehen von `databaseWithoutRowid(dir)` aus (Fall C1) und bleiben grün. Angepasst wird
  nur der Unit-Test in `SqliteRebuildPlanWithoutRowidTest` „Merkmal der Datei zaehlt, wenn die Datenbank es nicht fuehrt", der das ODER pinnt. `CreateTable` (neue Tabelle) und
  `schema generate` bleiben unberührt (dort gilt die Datei).

### Verworfen

* **A — Umwandlung gewollt** (Doku einschränken): verworfen, weil `unique: true` dann stillschweigend die Tabellensemantik ändert und der Handbuchsatz „weder hinzu noch weg" gebrochen bliebe.
* **B — Block bei Abweichung:** verworfen zugunsten der Warnung: der Neubau selbst ist korrekt, ein Block verhinderte eine sonst richtige Migration (Exit 8) ohne Gewinn.

## Arbeitspakete

* **P0 Messung des Ist** (Fälle A, B, C1, C2, E unten, `--plan-only` und `--execute`, vor der Änderung): bestätigt, dass A heute `WITHOUT ROWID` ergibt. DoD: Messtabelle im Slice.
* **P1 Code.** `SqliteRebuildPlan.withoutRowid` nimmt nur noch die **Datenbank-Seite**. Weil der Plan keine Richtung kennt, bekommt er die Datenbank-Seite explizit (Up: `oldTable`,
  Down: `newTable`; gemeinsame Struktur mit dem Rollback-Slice P1, kein zweiter Umbau von `planRebuild`; kein `@Suppress`, `LongParameterList` zählt zu `solid_rules`).
  `SqliteDiffRenderContext.tableWithoutRowid` (FTS5-Prüfung) folgt derselben Regel. Die Warnung entsteht zur Plan-Zeit (Datei-Seite `true`, Datenbank-Seite Rowid, Neubau der Tabelle),
  mit eigenem Diagnosecode im Katalog des Repos (Code und Ablage in P1 festlegen) und erscheint in `--plan-only` und `--execute`.
* **P2 Doku (nach dem Code bzw. mit ihm, siehe Reihenfolge):** Spec `spec/ddl-generation-rules.md` §3.5 darf **mit dem Code** (oder davor) kommen: Neubau erhält das Merkmal der
  Datenbank, Abweichung der Datei warnt. Handbuch §3.17 (`docs/user/anwenderhandbuch.md`) **erst nach dem Code** (Ist-Zustand): Satz „oder die Datei" streichen, Warnung nennen.
  `spec/neutral-model-spec.md` (`without_rowid`-Zeile) prüfen. `CHANGELOG.md` mit der Lieferung.
* **ADR:** nicht angenommen nötig (Planer-/Renderer-Verhalten, kein Fingerabdruck- oder Modellvertrag); im Zweifel klärt der Eigner. Ein Fingerabdruck-Bump ist zurückgestellt (Rollback-Slice).

## Akzeptanzkriterien

Hinweg je Fall (Änderung `unique: true`, Neubau), jeweils `--plan-only` **und** `--execute` (Exit 0, Diagnose, `sqlite_master.sql`):

| Fall | Datenbank | Datei `metadata` | Hinweg | Warnung |
|---|---|---|---|---|
| A | Rowid | `true` | **Rowid** (Nullbefund gegenüber heute) | ja |
| B | Rowid | keine | Rowid | nein |
| C1 | `WITHOUT ROWID` | `true` | `WITHOUT ROWID` | nein |
| C2 | `WITHOUT ROWID` | keine | `WITHOUT ROWID` | nein |
| E | Rowid | `true`, **kein Neubau** (z. B. nur neue nullable Spalte) | Rowid | nein (kein Neubau; Merkmal wird nicht geplant) |

1. Tests für alle fünf Fälle, `--plan-only` und `--execute`; Fall A **rot ohne die Änderung** belegt (heute `WITHOUT ROWID`), Tests laufen wirklich (CLAUDE.md „Grün heißt nicht geprüft").
2. Unit: `SqliteRebuildPlanWithoutRowidTest` an die Datenbank-Seiten-Regel angepasst (nicht nur ergänzt).
3. Warnung erscheint im Plan und im Lauf; Exit bleibt 0.
4. Spec §3.5 und Handbuch §3.17 widersprechen sich und dem Verhalten für Fall A nicht mehr.

## Verifikation

`make docs-check`; `make docker-check MODULES=":adapters:driven:driver-sqlite"` und `":adapters:driving:cli"`, einmal ohne `MODULES`, `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`,
`make solid-suppression-gate`, vor dem Push `make doc-immutable RANGE=origin/main..HEAD`.

## Nicht-Scope

Der Rollback-Fix und die Doku zu den Kopfwerten (Schwesterslice); `WITHOUT ROWID` per `schema migrate` gezielt hinzufügen/entfernen (eigener Slice, nur bei Bedarf).
