# SQLite: der Rollback-Neubau stellt `WITHOUT ROWID` aus der Datenbank vor dem Lauf her — Absicherung (Issue #67)

> **Status:** Draft mit Scope (2026-10-26). Entscheidungen des Eigners (2026-10-26) unten. Folge von
> [`sqlite-without-rowid-im-neubau.md`](../in-progress/sqlite-without-rowid-im-neubau.md) (#60). **Vorbedingung:** Lieferung 1, der Produktslice
> [`sqlite-without-rowid-umwandlung-produktfrage.md`](sqlite-without-rowid-umwandlung-produktfrage.md) (liefert die gemeinsame Struktur und behebt Down-Fall A). Dieser Slice **konsumiert** sie.
> **Reihenfolge:** Schritt 0 Spec (im Produktslice), (1) Produktslice, (2) dieser Slice, (3) Handbuch und `CHANGELOG.md`.
> **Option Zusammenlegen:** ist der Rest nach Lieferung 1 zu dünn (nur Tests und ein Doku-Absatz), darf dieser Slice im Produktslice aufgehen; nicht ohne Not.
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit; beim Move die `../in-progress/`-Links auf `./`, die Rückverweise im Schwesterslice, den Nachtrag im #60-Slice
> (`../next/` → `./`) und die Status-Spalte der README-Zeilen nachziehen; nur Planning-Dateien, kein ADR-`consulted:`. GitHub-Issue: [#67](https://github.com/pt9912/d-migrate/issues/67).
> **Vorgabe für Spec und Handbuch:** nur der gültige Zustand, keine Chronik oder Forensik; Version und Herleitung nur im `CHANGELOG.md`.

## Ziel

`schema rollback` stellt nach einem Neubau den Zustand **vor dem Lauf** her, auch im Merkmal `WITHOUT ROWID`. Nach Lieferung 1 ist das Verhalten behoben; dieser Slice sichert es mit Tests ab und
legt die Aussage der Kopfwerte fest.

## Problem (Herleitung, nur Planning)

Fall A (Rowid-Datenbank, Datei `true`, `unique: true` → Neubau): Up ergab `WITHOUT ROWID`, der `down.sql` legte `t__dmg_rebuild_…` wieder `WITHOUT ROWID` an; Kopf `rollbackComplete: true`, `postUpVerified: true`.

### Ursache A: das Down-Skript (am Code belegt)

* `SqliteRebuildPlan.withoutRowid` war `oldTable… || newTable…` — richtungsblind. In Down ist `source = desired` (Datei, `true`), `target = current` (Datenbank vor dem Lauf, Rowid); das ODER schrieb `WITHOUT ROWID`.
* `SqliteDiffDdlGenerator` kannte die Richtung, reichte sie an `planRebuild` nicht weiter; `SqliteDiffRenderContext.tableWithoutRowid` war ebenfalls richtungsblind.

### Ursache B: warum die Kopfwerte grün bleiben

Die Kopfwerte sind **keine Nachprüfung**: `rollbackComplete` ist `!input.partialRollback` (`RollbackArtefactBuilder`), beim Erzeugen gesetzt, und sagt nur, ob Operationen ausgelassen wurden. `postUpVerified` setzt
`migrate --execute` aus dem gelesenen Post-Up-Stand (`SchemaMigrateRollbackArtefactBuilder`). Der Fingerabdruck (`schema-fingerprint-v24`) projiziert `TableMetadata` nicht. `schema rollback` prüft nach der Ausführung nichts, nur
die Vorbedingung (`SchemaRollbackRunner.verifyTargetMatchesArtefact`, sonst `TARGET_STATE_MISMATCH`).

## Entscheidungen (Eigner, 2026-10-26)

1. **Down nimmt das Merkmal aus dem Zielzustand der Richtung** (Datenbank vor dem Lauf). Mit Option C gilt dasselbe für Up. Fall A verschwindet im Hinweg; die Down-Regel bleibt nötig für Artefakte
   früherer Versionen und als Absicherung.
2. **Nachprüfung → Option (c): nur dokumentieren.** (a) Fingerabdruck v25 zurückgestellt (ADR, Exit 8 `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` für alte Artefakte, Driftrisiko C2; Neubewertung nur, wenn P0 Drift in anderen
   Fällen zeigt). (b') Generator-Meldung entfällt (Down kann das Merkmal immer herstellen). (b'') echte Post-Rollback-Prüfung im Runner: eigener Slice, nur bei Bedarf.
3. **Alte Artefakte: keine Laufzeitprüfung** (das Vorher-Merkmal steckt weder im Artefakt noch im Fingerabdruck; ein Check wäre geraten und warnte korrekte Artefakte). Der Befund steht nur im `CHANGELOG.md`
   (Eintrag im Produktslice, P5); **nicht** in `docs/user/` und nicht im Troubleshooting-Leitfaden.

## Arbeitspakete

* **P0 Messung** (je frische Datenbank, `unique: true` auf `u`, `migrate --execute --generate-rollback`, `rollback --execute --allow-destructive`, Folgeplan; Fälle der Tabelle unten, Hin- und Rückweg): `sqlite_master.sql`,
  `down.sql`-Text, Kopfwerte, Folgeplan; Messung zu Ursache B (Vorbedingung vs. Nachprüfung); ob in **anderen** Fällen Drift auftritt (Voraussetzung, (a) neu zu bewerten). **Prüfpunkt Leser:** alle Leser von `withoutRowid`
  im Treiber durchgehen und je Down bewerten: `SqliteRebuildPlan.withoutRowid`, `SqliteDiffRenderContext.tableWithoutRowid`, `SqliteTableDdlSupport` (`CREATE TABLE`-Klausel, Index/FTS5), `SqliteSequenceDdlSupport.resolveSequenceDefault`
  (E057) und `shouldSuppressNotNull`, `SqliteSchemaReader` (Quelle). **Erwartung (am Code geprüft, P0 bestätigt):** `SqliteDiffRenderContext.currentSchema` ist in Up und Down die Datenbank-Seite (aus `diff.currentSchema`);
  `SqliteSequenceDdlSupport.currentSchema` ist ein **gleichnamiges, anderes** Feld (`beginRun` aus `SqliteDdlGenerator.generate`, Schema des `schema generate`-Laufs) und vom Down-Neubau nicht berührt; Abweichung wäre ein Befund.
* **P1 Absicherung des Down-Wirings** (Struktur und Regel liefert der Produktslice): Unit-Test mit **handgebautem `DiffResult`** (Rowid-Vorher, `true`-Quelle) für `generateRollback`; `SqliteDdlGeneratorRollbackTest` pinnt den Down-Text für A und C2.
* **P2 Kopfwerte = Dokumentation (Option c).** Kein Code. Handbuch §3.17 **nach dem Code**, als zeitlose Definition: „`rollbackComplete: true` heißt: keine Operation wurde ausgelassen. `postUpVerified: true` heißt: der Post-Up-Stand wurde gelesen.
  Beides sagt nichts über Strukturmerkmale wie `WITHOUT ROWID`." Kein „früher", kein Verweis auf #67.
* **P3 Doku:** Spec §3.5 (Rückweg: Datenbank vor dem Lauf) wird im Schritt 0 des Produktslice mitgeschrieben, kein eigener Eingriff hier. Den **Hinweg**-Satz ändert nur der Produktslice. `CHANGELOG.md`: kein zweiter Eintrag,
  der Rückweg steht im Eintrag des Produktslice (oder gegenseitiger Verweis). Troubleshooting-Leitfaden: **nichts**.

## Akzeptanzkriterien

Soll je Fall, Hin- **und** Rückweg (`unique: true`, Neubau; Hinweg nach Option C):

| Fall | Datenbank | Datei `metadata` | Hinweg | Rückweg |
|---|---|---|---|---|
| A | Rowid | `true` | Rowid (+ `W164`) | Rowid, Index `ux` zurück |
| A-FT | Rowid | `true`, `FULLTEXT`-Index im Lauf | Rowid, FTS5-Aufbau | Rowid, FTS5-Objekte entfernt/zurück wie vor dem Lauf |
| B | Rowid | keine | Rowid | Rowid, Index zurück |
| C1 | `WITHOUT ROWID` | `true` | `WITHOUT ROWID` | `WITHOUT ROWID`, Index zurück |
| C2 | `WITHOUT ROWID` | keine | `WITHOUT ROWID` | `WITHOUT ROWID` (aus dem Datenbank-Stand), Index zurück |
| E | Rowid | `true`, **kein Neubau** | Rowid | Rowid |

1. **CLI-Rundlauf je Fall** (A, B, C1, C2, E): `migrate --execute --generate-rollback`, `rollback --execute --allow-destructive`, Folgeplan, mit `sqlite_master.sql` vor/nach jedem Schritt; A-FT mit Rückweg (eigener Test).
2. **Folgeplan:** nach Rollback in Fall A steht die Datenbank wie vor dem Lauf. Der Folgeplan ist derselbe wie vorher: `AddConstraint` + `DropIndex` **und** `W164` (Datei `true`, Datenbank Rowid), **keine** zusätzliche Operation
   für das Merkmal (`TableComparator.metadataChangeOrNull` meldet, der Planer ignoriert). B, C1, C2: derselbe Plan wie vor dem Lauf, ohne `W164`.
3. **Rot-Nachweis** über den Unit-Test mit handgebautem `DiffResult` (Down-Wiring auf Stand `main` zurückgenommen → Test schlägt fehl), nicht über den CLI-Rundlauf (dessen Hinweg ist nach Lieferung 1 ohnehin Rowid); Tests laufen wirklich.
4. B, C1, C2, E unverändert (Regressionsschutz; C2 bräche jede „immer aus der Datei"-Lösung).
5. Unit: `SqliteRebuildPlanWithoutRowidTest` „Merkmal der Datenbank-Seite zaehlt in der Gegenrichtung (Down: newTable)" pinnt das ODER und wird an die neue Struktur **angepasst**; neu: Down mit Rowid-Ziel + `true`-Quelle → `false`.
6. P2: Handbuchtext wie oben; `make docs-check` grün.

## Verifikation

`make docker-check MODULES=":adapters:driven:driver-sqlite"` und `":adapters:driving:cli"`; einmal ohne `MODULES` (`MODULES=` lässt `test/integration-*` aus), `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`;
`make solid-suppression-gate`; `make docs-check`; vor dem Push `make doc-immutable RANGE=origin/main..HEAD` (beim Move `consulted:`-Pfade in ADRs nicht nachziehen).

## Nicht-Scope

Fingerabdruck v25 und eine Post-Rollback-Prüfung im Runner (zurückgestellt bzw. eigener Slice nur bei Bedarf); `WITHOUT ROWID` gezielt per `schema migrate` ändern; andere Dialekte.
