# SQLite: ein Neubau behält das Merkmal der Datenbank, Abweichung der Datei wird gewarnt (Produktentscheidung zu Issue #67)

> **Status:** Entschieden (Eigner, 2026-10-26): **Option C plus Warnung `W164`**. Draft mit Scope für die Umsetzung; noch kein Implementierungs-Commit.
> **Vorbedingung:** keine. **Reihenfolge:** **Schritt 0** Spec-Prosa (vor dem Code), (1) dieser Slice (Struktur, Regel, Warnung, Tests, Ledger), (2) Rollback-Slice
> [`sqlite-rollback-without-rowid-vorher.md`](sqlite-rollback-without-rowid-vorher.md) (Test und Absicherung), (3) Handbuch (nach dem Code) und `CHANGELOG.md`.
> Dieser Slice ist der **einzige**, der den Hinweg-Satz in Spec §3.5 und Handbuch §3.17 ändert; der Rollback-Slice ergänzt dort nur den Rückweg.
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit; beim Move die relativen `../in-progress/`-Links auf `./` umstellen, die Rückverweise im
> Schwesterslice und den Nachtrag im #60-Slice (`sqlite-without-rowid-im-neubau.md`, `../next/` → `./`) nachziehen **und die Status-Spalte der README-Zeilen** (`next/` und `in-progress/`) mitziehen;
> nur Planning-Dateien, kein ADR-`consulted:`. GitHub-Issue: [#67](https://github.com/pt9912/d-migrate/issues/67).
> **Vorgabe für Spec und Handbuch:** nur der gültige Zustand, zeitlos; keine Chronik oder Forensik (keine Versionen, keine Herleitung, keine Erkennung alter Artefakte).
> Version, Betroffene und Herleitung stehen nur im `CHANGELOG.md` und hier in `docs/planning/`.

## Ziel

Ein Tabellenneubau behält `WITHOUT ROWID` genau dann, wenn die **Datenbank** die Tabelle so führt. Die Datei ändert das Merkmal beim Neubau nicht. Führt die Datei
`metadata: { without_rowid: true }` gegen eine Rowid-Tabelle (Fall A), läuft die Migration weiter und warnt mit `W164`.

## Problem (Herleitung, nur Planning)

Handbuch §3.17 sagt zugleich: ein Neubau erhält das Merkmal, „wenn die Datenbank **oder die Datei** die Tabelle so führt" (Fall A: Umwandlung hin) **und** „`schema migrate` plant das Merkmal
selbst nicht: es ändert eine bestehende Tabelle weder hinzu noch weg". Beides zugleich stimmt für Fall A nicht. Die Regel stammt aus
[`sqlite-without-rowid-im-neubau.md`](../in-progress/sqlite-without-rowid-im-neubau.md) P1 (Datei oder Datenbank; als Folge in Kauf genommen); `spec/ddl-generation-rules.md` §3.5 trägt denselben Satz.
Fall A hatte keinen Test — deshalb rutschte #67 durch.

## Entscheidung (Eigner, 2026-10-26): Option C plus Warnung

* Ein Neubau nimmt das Merkmal **nur aus der Datenbank** (Zustand vor dem Lauf), in Up wie Down. Weicht die Datei ab (Datei `true`, Datenbank Rowid = Fall A), läuft die Migration **weiter**
  (kein Block) und warnt in `--plan-only` und `--execute`. **Nur Up warnt, Down nie.**
* Fall C2 (Datei ohne Angabe, Datenbank `WITHOUT ROWID`) warnt nicht. Eine Angabe `false` ist von „fehlt" nicht unterscheidbar (Modelltyp `Boolean`, Default `false`:
  `TableMetadata.withoutRowid`, Parser `boolOrDefault`) und warnt ebenfalls nicht.
* Begründung: passt zum Handbuchsatz „weder hinzu noch weg"; `WITHOUT ROWID` ändert die Tabellensemantik und soll keine Nebenwirkung von `unique: true` sein; Fall A verschwindet im Hinweg.
* **Folge:** die Datei wirkt beim Neubau nicht aufs Merkmal. Ein gezieltes Ändern wäre ein eigener Slice, nur bei Bedarf.
* **Kosten:** kein bestehender CLI-Test bricht. `CliSchemaMigrateWithoutRowidSqliteTest` geht durchweg von `databaseWithoutRowid(dir)` aus (Fall C1). Angepasst wird der Unit-Test
  `SqliteRebuildPlanWithoutRowidTest` „Merkmal der Datei zaehlt, wenn die Datenbank es nicht fuehrt" (pinnt das ODER). `CreateTable` und `schema generate` bleiben unberührt (dort gilt die Datei).
* **Warnungscode `W164`** (festgelegt): nutzersichtbar, also Ledger-Pflicht; nächster freier Code nach `W163` im Bereich W100–W199 (`spec/cli-spec.md` §4.5; „neue Codes am Ende des Bereichs").
  Muster `ctx.warning(op, message, code)` (`SqliteDiffRenderContext`, `DiffDiagnostic` mit `severity WARNING` und `operationId`), aufgerufen wie `warnRebuiltColumnDegradations` in `SqliteRebuildRenderer` (nur UP).

### Verworfen

* **A — Umwandlung gewollt** (Doku einschränken): `unique: true` änderte stillschweigend die Tabellensemantik; „weder hinzu noch weg" bliebe gebrochen.
* **B — Block bei Abweichung:** der Neubau selbst ist korrekt; ein Block (Exit 8) verhinderte eine richtige Migration ohne Gewinn.

## Arbeitspakete

* **Schritt 0 Spec (vor dem Code, nur Zielbild, keine Links auf Pläne/ADRs — `.d-check.yml` matrix):**
  * `spec/ddl-generation-rules.md` §3.5, zeitlos und ohne Begründungssätze (die heutige Begründung zur Bestandsdatei entfällt), z. B.: „Ein Tabellen-Neubau behält das Merkmal der Datenbank, in
    `schema migrate` wie im erzeugten Rollback. Eine abweichende Datei ändert es nicht und löst `W164` aus; eine fehlende oder `false`-Angabe der Datei warnt nicht."
  * `spec/cli-spec.md` §4.5: Zeile `W164` (englisch wie die Nachbarzeilen). `spec/neutral-model-spec.md` (`without_rowid`-Zeile) prüfen.
  * Der Ledger-Eintrag und die Zeile in `spec/ledger.md` kommen erst **mit dem Code und dem Test** (sonst fällt das Gate), siehe P3.
* **P0 Messung des Ist** (vor der Änderung, `--plan-only` und `--execute`): Fälle A, B, C1, C2, E, F (unten); dass `--plan-only` die Up-DDL tatsächlich rendert (sonst käme die Warnung dort nicht an);
  **wie `schema migrate` Diagnostics ausgibt** (Plan-Text, `--output-format json`, Report-Feld `diagnostics`) — daraus die Fundstelle der Warnung in Akzeptanz 3. DoD: Messtabelle im Slice.
* **P1 Gemeinsame Struktur und Regel** (dieser Slice liefert, der Rollback-Slice konsumiert). Neues kleines Wertobjekt für `SqliteRebuildPlanner.planRebuild` mit Quelle und Ziel (je Tabelle + Schema)
  und Richtung; die Datenbank-Seite ergibt sich daraus (Up: Quelle, Down: Ziel). Es **ersetzt** die Parameter `source`/`target`/`sourceSchema`/`targetSchema` (nicht ergänzen: `LongParameterList` zählt zu
  `solid_rules`, kein `@Suppress`). Beide Aufrufe in `SqliteDiffDdlGenerator` (Up und Down) werden umgestellt. `SqliteRebuildPlan.withoutRowid` nimmt nur die Datenbank-Seite.
  **Down-Fall A ist damit mit dieser Lieferung behoben.**
* **P2 Warnung `W164`:** Plan-Zeit-Prüfung (Datei-Seite `true`, Datenbank-Seite Rowid, Neubau der Tabelle), nur UP, an der ersten Operation des Neubau-Eimers. Test in `SqliteRebuildRendererTest`/CLI.
* **P3 FTS5 ohne Neubau (M4):** `SqliteDiffRenderContext.tableWithoutRowid` (heute ODER über Datei und Datenbank) steuert auch `SqliteFullTextExpansion` ohne Neubau. Es folgt der Datenbank-Seite; KDoc anpassen.
  Folge: Fall A mit `FULLTEXT`-Index wechselt von `W132`-Degradation zu FTS5-Rendering (Rowid-Tabelle ist FTS5-fähig). Test dazu.
* **P4 Ledger (mit dem Test):** Eintrag `W164` in `ledger/warn-code-ledger-1.1.0.yaml` (Felder wie `W163`: `level`, `status`, `entry_type`, `test_path`, `evidence_paths` mit Produktions- und Testdatei),
  Zeile in `spec/ledger.md`; `CodeLedgerValidationTest` läuft mit.
* **P5 Handbuch und CHANGELOG (nach dem Code):** Handbuch §3.17, zeitlos, ohne „bisher/neu/seit/ab jetzt": Neubau behält das Merkmal der Datenbank; abweichende Datei → `W164`. **Ein** `CHANGELOG.md`-Eintrag mit
  Befund **und** Fix: Version (1.11.0/1.11.1), Betroffene (Neubau bei Datei `true` + Rowid-Datenbank; Rollback-Artefakte daraus), Erkennung (`down.sql` mit `__dmg_rebuild`-Block `WITHOUT ROWID`, obwohl die Tabelle vorher
  Rowid war), Abhilfe (nicht angewendeten Lauf neu erzeugen; bei angewendetem manueller Eingriff). **In `docs/user/` steht dazu nichts** (kein Troubleshooting-Eintrag).
* **ADR:** nicht angenommen nötig (Planer-/Renderer-Verhalten, kein Fingerabdruck-/Modellvertrag); im Zweifel klärt der Eigner.

## Akzeptanzkriterien

Hinweg je Fall (`unique: true`, Neubau), `--plan-only` **und** `--execute`:

| Fall | Datenbank | Datei `metadata` | Hinweg | `W164` |
|---|---|---|---|---|
| A | Rowid | `true` | Rowid | ja |
| A-FT | Rowid | `true`, neuer `FULLTEXT`-Index | Rowid, FTS5-Aufbau (kein `W132`) | ja, falls Neubau |
| B | Rowid | keine | Rowid | nein |
| C1 | `WITHOUT ROWID` | `true` | `WITHOUT ROWID` | nein |
| C2 | `WITHOUT ROWID` | keine | `WITHOUT ROWID` | nein |
| E | Rowid | `true`, **kein Neubau** (z. B. nur neue nullable Spalte) | Rowid | nein |
| F | Rowid | `true`, kein Neubau, nur `--plan-only` | 0 Operationen für das Merkmal | nein |

1. Tests für alle Zeilen; Fall A **rot ohne die Änderung** belegt (heute `WITHOUT ROWID`); Tests laufen wirklich (CLAUDE.md „Grün heißt nicht geprüft").
2. Unit: `SqliteRebuildPlanWithoutRowidTest` an die Datenbank-Seiten-Regel angepasst (nicht nur ergänzt).
3. `W164` erscheint als `DiffDiagnostic` (Severity `WARNING`, `operationId` des Neubau-Eimers) in `--plan-only`, `--execute` und im Report; Fundstelle je Ausgabe (Text, `--output-format json`, Report) aus P0 benannt;
   Exit 0; **Down erzeugt keine Warnung**. Der Folgeplan nach einem Rollback in Fall A trägt `W164` wieder (Datei `true`, Datenbank Rowid) — erwartet.
4. Ledger: `W164` mit `test_path` und Evidence, `CodeLedgerValidationTest` grün; `spec/ledger.md` und `spec/cli-spec.md` §4.5 tragen die Zeile.
5. Spec §3.5 und Handbuch §3.17 widersprechen sich und dem Verhalten nicht; beide ohne Chronik/Forensik.

## Verifikation

`make docs-check`; `make docker-check MODULES=":adapters:driven:driver-sqlite"`, `":adapters:driving:cli"` und das Modul des `CodeLedgerValidationTest`, einmal ohne `MODULES` (geteilte Signatur; `MODULES=` lässt `test/integration-*` aus),
`make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`, vor dem Push `make doc-immutable RANGE=origin/main..HEAD`.

## Nicht-Scope

Rollback-Absicherung und Doku der Kopfwerte (Schwesterslice); `WITHOUT ROWID` per `schema migrate` gezielt hinzufügen/entfernen (eigener Slice, nur bei Bedarf).
