# SQLite: der Rollback-Neubau stellt `WITHOUT ROWID` aus der Datenbank vor dem Lauf her — Rundläufe und Kopfwert-Doku (Issue #67)

> **Status:** Draft mit Scope (2026-10-26), **auf den Stand nach Lieferung 1 gezogen**. Entscheidungen des Eigners (2026-10-26) unten. Folge von
> [`sqlite-without-rowid-im-neubau.md`](../in-progress/sqlite-without-rowid-im-neubau.md) (#60).
> **Vorbedingung:** Lieferung 1, der Produktslice [`sqlite-without-rowid-umwandlung-produktfrage.md`](../in-progress/sqlite-without-rowid-umwandlung-produktfrage.md),
> ist auf `main` (Commits `e0b3a8e5a` Spec, `6f93b467f` Code, `01f24c921` Handbuch/CHANGELOG). Er hat Regel, Struktur, Down-Wiring und die Down-Fall-A-Tests geliefert; dieser Slice **konsumiert** sie und liefert nur noch den Rest.
> **Fix-Branch:** `fix-review-slice1-issue-67` (Code-Review zu Lieferung 1: `generateDown`-Tests für C1/C2 = LOW-2, Doppelung `SqliteRebuildSides`/`SqliteRebuildPlan` = LOW-1) ist **noch nicht auf `main`** (Stand: Branch gleich `main`). **Vor Start prüfen, ob gemergt**, und P1 danach nachziehen.
> **Reihenfolge:** Schritt 0 Spec, (1) Produktslice samt Handbuch und `CHANGELOG.md`: **erledigt**; (2) dieser Slice (Rundläufe, P2-Doku).
> **Schnitt (Eigner-Frage E-R1):** der Rest ist klein (eine CLI-Rundlauf-Testklasse, Messung, ein Handbuchabsatz). Der Eigner-Beschluss „eigener Slice" bleibt gültig; die Option Zusammenlegen mit dem Produktslice-Abschluss steht unten als Frage.
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit; beim Move die `../in-progress/`-Links auf `./`, die Rückverweise im Schwesterslice, den Nachtrag im #60-Slice
> (`../next/` → `./`) und die Status-Spalte der README-Zeilen nachziehen; nur Planning-Dateien, kein ADR-`consulted:`. GitHub-Issue: [#67](https://github.com/pt9912/d-migrate/issues/67).
> **Vorgabe für Spec und Handbuch:** nur der gültige Zustand, keine Chronik oder Forensik; Version und Herleitung nur im `CHANGELOG.md`.

## Ziel

`schema rollback` stellt nach einem Neubau den Zustand **vor dem Lauf** her, auch im Merkmal `WITHOUT ROWID`. Lieferung 1 hat das Verhalten behoben und für Down-Fall A in Unit- und CLI-Test gepinnt. Dieser Slice
sichert es **über den ganzen Rundlauf** (Up, Rollback, Folgeplan) je Fall ab und legt die Aussage der Kopfwerte im Handbuch fest.

## Erledigt in Lieferung 1 (nicht mehr Teil dieses Slice)

* Richtungsbewusste Regel `withoutRowid`: nur die **Datenbank-Seite**, in Up (Quelle) und Down (Ziel).
* Wertobjekt `SqliteRebuildSides` (ersetzt `source`/`target`/`sourceSchema`/`targetSchema` in `planRebuild`); Down-Wiring in `SqliteDiffDdlGenerator`.
* `SqliteRebuildPlanWithoutRowidTest` an die neue Regel angepasst (inkl. Down mit Rowid-Ziel + `true`-Quelle → `false`).
* `SqliteDiffRenderContext.tableWithoutRowid` folgt `currentSchema` (Datenbank-Seite).
* Down-Fall-A-Tests (Unit und CLI), `W164` (nur Up), Spec §3.5/§4.5, Ledger, Handbuch §3.17 (Hinweg und Rückweg), `CHANGELOG.md`-Eintrag unter `[Unreleased]`.
* **CHANGELOG-Befund zu Artefakten aus 1.11.0/1.11.1: steht dort** (Betroffene, Erkennung über den `__dmg_rebuild`-Block im `down.sql`, Abhilfe). Das frühere Arbeitspaket entfällt; hier ist nichts mehr zu schreiben.

**Kommt mit Fix-Branch `fix-review-slice1-issue-67` (vor Start prüfen, ob gemergt):** `generateDown`-Tests für C1 und C2 (LOW-2); Auflösung der Doppelung Sides/Plan (LOW-1). Sind sie gemergt, ist die
frühere P1 (Unit-Absicherung des Down-Wirings) vollständig erledigt; sind sie es nicht, bleibt der C1/C2-Down-Text-Test hier als Rest (kleiner Test in `SqliteDdlGeneratorRollbackTest`).

## Hintergrund (Herleitung, nur Planning)

Ursache A (richtungsblindes ODER in `SqliteRebuildPlan.withoutRowid`, Down legte `WITHOUT ROWID` an) ist mit Lieferung 1 behoben. **Offen bleibt die Aussage zu Ursache B** — warum die Kopfwerte grün blieben:
`rollbackComplete` ist `!input.partialRollback` (`RollbackArtefactBuilder`), beim Erzeugen gesetzt, und sagt nur, ob Operationen ausgelassen wurden. `postUpVerified` setzt
`migrate --execute` aus dem gelesenen Post-Up-Stand (`SchemaMigrateRollbackArtefactBuilder`). Der Fingerabdruck (`schema-fingerprint-v24`) projiziert `TableMetadata` nicht. `schema rollback` prüft nach der Ausführung nichts, nur
die Vorbedingung (`SchemaRollbackRunner.verifyTargetMatchesArtefact`, sonst `TARGET_STATE_MISMATCH`).

## Entscheidungen (Eigner, 2026-10-26)

1. **Down nimmt das Merkmal aus dem Zielzustand der Richtung** (Datenbank vor dem Lauf): **geliefert** (Lieferung 1).
2. **Nachprüfung → Option (c): nur dokumentieren.** Umsetzung ist P2 dieses Slice. (a) Fingerabdruck v25 zurückgestellt (ADR, Exit 8 `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` für alte Artefakte, Driftrisiko C2; Neubewertung nur, wenn P0 Drift in anderen
   Fällen zeigt). (b') Generator-Meldung entfällt. (b'') echte Post-Rollback-Prüfung im Runner: eigener Slice, nur bei Bedarf.
3. **Alte Artefakte: keine Laufzeitprüfung**; der Befund steht nur im `CHANGELOG.md` (**geliefert**), nicht in `docs/user/` und nicht im Troubleshooting-Leitfaden.

## Grenze: FULLTEXT (unabhängig von #67)

Fall A mit `FULLTEXT`-Index und `--execute` endet **unabhängig von #67** mit Exit 8 (`TRANSACTION_SCOPE_UNSUPPORTED`, Neubau und `AddIndex` in einem Lauf). Dafür gibt es den eigenen Slice
[`sqlite-neubau-mit-fulltext-index-execute.md`](sqlite-neubau-mit-fulltext-index-execute.md). **Hier gibt es keinen Rollback-Rundlauf für A-FT mit `--execute`**: ohne angewendeten Lauf gibt es nichts zurückzurollen.
Der Hinweg von A-FT ist in Lieferung 1 mit `--plan-only`/`--output` abgedeckt; der Rückweg von A-FT gehört in den FULLTEXT-Slice.

## Arbeitspakete

* **P0 Messung** (je frische Datenbank, `unique: true` auf `u`, `migrate --execute --generate-rollback`, `rollback --execute --allow-destructive`, Folgeplan; Fälle A, B, C1, C2, E, Hin- und Rückweg): `sqlite_master.sql`,
  `down.sql`-Text, Kopfwerte, Folgeplan; Messung zu Ursache B (Vorbedingung vs. Nachprüfung); ob in **anderen** Fällen Drift auftritt (Voraussetzung, (a) neu zu bewerten). DoD: Messtabelle im Slice.
  **Prüfpunkt Leser:** `SqliteRebuildPlan.withoutRowid` und `SqliteDiffRenderContext.tableWithoutRowid` sind in Lieferung 1 erledigt; die übrigen Leser gegenprüfen und je Down bewerten: `SqliteTableDdlSupport` (`CREATE TABLE`-Klausel, Index/FTS5),
  `SqliteSequenceDdlSupport.resolveSequenceDefault` (E057) und `shouldSuppressNotNull`, `SqliteSchemaReader` (Quelle). **Erwartung (am Code geprüft, P0 bestätigt):** `SqliteSequenceDdlSupport.currentSchema` ist ein **gleichnamiges, anderes** Feld
  (`beginRun` aus `SqliteDdlGenerator.generate`) und vom Down-Neubau nicht berührt; Abweichung wäre ein Befund.
* **P1 Rundlauf-Tests** (CLI, je Fall A, B, C1, C2, E): `migrate --execute --generate-rollback`, `rollback --execute --allow-destructive`, Folgeplan, mit `sqlite_master.sql` vor/nach jedem Schritt; Soll aus P0 gemessen, gegen die Tabelle unten geprüft.
  Ist der Fix-Branch nicht gemergt: zusätzlich `generateDown`-Test für C1/C2. DoD: Tests laufen wirklich (einmal absichtlich rot).
* **P2 Kopfwerte = Dokumentation (Option c).** Kein Code; das Handbuch darf jetzt, weil der Code steht. Heute nennt `docs/user/` `rollbackComplete` und `postUpVerified` nirgends. Ort: §3.17 (bei der
  `WITHOUT ROWID`-Zeile) oder der Rollback-Abschnitt bei `TARGET_STATE_MISMATCH` (Zeile ~1629); beim Schreiben den passenderen wählen. Vorher beide Definitionen gegen `RollbackArtefactBuilder` und `SchemaMigrateRollbackArtefactBuilder` prüfen.
  Zeitlose Definition: „`rollbackComplete: true` heißt: keine Operation wurde ausgelassen. `postUpVerified: true` heißt: der Post-Up-Stand wurde gelesen. Beides sagt nichts über Strukturmerkmale wie `WITHOUT ROWID`." Kein „früher", kein Verweis auf #67, keine Chronik.
* **Entfallen:** Spec §3.5 und die Hinweg-/Rückweg-Sätze (Lieferung 1), `CHANGELOG.md` (Befund und Fix stehen), Troubleshooting-Leitfaden (nichts).

## Akzeptanzkriterien

Soll je Fall, Hin- **und** Rückweg (`unique: true`, Neubau):

| Fall | Datenbank | Datei `metadata` | Hinweg | Rückweg |
|---|---|---|---|---|
| A | Rowid | `true` | Rowid (+ `W164`) | Rowid, Index `ux` zurück |
| B | Rowid | keine | Rowid | Rowid, Index zurück |
| C1 | `WITHOUT ROWID` | `true` | `WITHOUT ROWID` | `WITHOUT ROWID`, Index zurück |
| C2 | `WITHOUT ROWID` | keine | `WITHOUT ROWID` | `WITHOUT ROWID` (aus dem Datenbank-Stand), Index zurück |
| E | Rowid | `true`, **kein Neubau** | Rowid | Rowid |

A-FT hat hier **keine Zeile** (siehe „Grenze: FULLTEXT").

1. **CLI-Rundlauf je Fall** (A, B, C1, C2, E) wie P1, mit gemessenem Soll aus P0.
2. **Folgeplan:** nach Rollback in Fall A steht die Datenbank wie vor dem Lauf. Der Folgeplan ist derselbe wie vorher: `AddConstraint` + `DropIndex` **und** `W164` (Datei `true`, Datenbank Rowid), **keine** zusätzliche Operation
   für das Merkmal (`TableComparator.metadataChangeOrNull` meldet, der Planer ignoriert). B, C1, C2: derselbe Plan wie vor dem Lauf, ohne `W164`.
3. B, C1, C2, E unverändert (Regressionsschutz; C2 bräche jede „immer aus der Datei"-Lösung).
4. P2: Handbuchtext wie oben; `make docs-check` grün.
5. Der Rot-Nachweis des Down-Wirings (Unit-Test mit handgebautem `DiffResult`) liegt in Lieferung 1 bzw. im Fix-Branch; hier nur prüfen, dass er vorhanden ist.

## Verifikation

`make docker-check MODULES=":adapters:driving:cli"` (und `":adapters:driven:driver-sqlite"`, falls der Rest-Test aus P1 dort landet); einmal ohne `MODULES` (`MODULES=` lässt `test/integration-*` aus), `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`;
`make solid-suppression-gate`; `make docs-check`; vor dem Push `make doc-immutable RANGE=origin/main..HEAD` (beim Move `consulted:`-Pfade in ADRs nicht nachziehen).

## Offene Eigner-Frage

* **E-R1 Zusammenlegen?** Nach Lieferung 1 trägt dieser Slice nur noch Rundlauf-Tests, eine Messtabelle und einen Handbuchabsatz. Planner-Einschätzung: dünn, aber vertretbar als eigener Slice, weil die Messung (Ursache B, Drift in
  anderen Fällen) das Urteil über den zurückgestellten Fingerabdruck v25 trägt und den Abschluss von Lieferung 1 nicht aufhalten soll. Die Alternative (Aufgehen im Produktslice-Abschluss) spart einen README-Eintrag und einen Move. Bis zur Antwort gilt der Eigner-Beschluss: eigener Slice.

## Nicht-Scope

Fingerabdruck v25 und eine Post-Rollback-Prüfung im Runner (zurückgestellt bzw. eigener Slice nur bei Bedarf); `WITHOUT ROWID` gezielt per `schema migrate` ändern; andere Dialekte; Rollback von A-FT mit `--execute` (FULLTEXT-Slice).
