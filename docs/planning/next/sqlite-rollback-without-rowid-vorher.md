# SQLite: der Rollback-Neubau stellt `WITHOUT ROWID` aus der Datenbank vor dem Lauf her (Issue #67)

> **Status:** Draft mit Scope (2026-10-26), Ursache am Code belegt, Messung P0 steht aus. Entscheidungen des Eigners (2026-10-26) unten eingetragen. Folge von
> [`sqlite-without-rowid-im-neubau.md`](../in-progress/sqlite-without-rowid-im-neubau.md) (#60). **Vorbedingung:** keine offene.
> **Reihenfolge:** (1) Produktslice [`sqlite-without-rowid-umwandlung-produktfrage.md`](sqlite-without-rowid-umwandlung-produktfrage.md) (Entscheidung C, Code, Test),
> (2) dieser Slice P0/P1, (3) Doku (Spec mit dem Code, Handbuch danach, `CHANGELOG.md`-Befund).
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit; beim Move die relativen
> `../in-progress/`-Links in diesem und im Schwesterslice auf `./` umstellen, ebenso die Rückverweise im jeweils anderen Slice und den Nachtrag im
> #60-Slice (`sqlite-without-rowid-im-neubau.md`, `../next/` → `./`); nur Planning-Dateien, kein ADR-`consulted:`.
> GitHub-Issue: [#67](https://github.com/pt9912/d-migrate/issues/67).

## Ziel

`schema rollback` stellt nach einem Neubau den Zustand **vor dem Lauf** her, auch im Merkmal `WITHOUT ROWID`: aus einer
Rowid-Tabelle, die der Hinweg wegen `metadata: { without_rowid: true }` in der Datei zur `WITHOUT ROWID`-Tabelle machte, wird
wieder eine Rowid-Tabelle.

## Problem (Issue #67, 1.11.1)

Fall A (Rowid-Datenbank, Datei mit `without_rowid: true`, Änderung `unique: true` → Neubau): Up gibt `WITHOUT ROWID`, der
erzeugte `down.sql` legt `t__dmg_rebuild_…` wieder `WITHOUT ROWID` an; Kopf `rollbackComplete: true`, `postUpVerified: true`.

### Ursache A: das Down-Skript (am Code belegt; P0 misst nach)

* `SqliteRebuildPlan.withoutRowid` (`adapters/driven/driver-sqlite/…/SqliteRebuildPlan.kt`) ist `oldTable… == true || newTable… == true`
  — **richtungsblind**. Richtig für Up (Bestandsdatei kennt das Merkmal nicht), falsch für Down: dort ist `source = desired` (Datei, `true`),
  `target = current` (Datenbank vor dem Lauf, Rowid); das ODER schreibt wieder `WITHOUT ROWID`.
* `SqliteDiffDdlGenerator` (Down-Zweig in der Neubau-Behandlung) kennt die Richtung, reicht sie an `SqliteRebuildPlanner.planRebuild` nicht weiter.
* `SqliteDiffRenderContext.tableWithoutRowid` (FTS5-Prüfung) ist ebenfalls richtungsblind; in Down muss es den Zielzustand befragen.

### Ursache B: warum die Kopfwerte grün bleiben (Korrektur nach Review)

Die Kopfwerte sind **keine Nachprüfung** des Rollbacks:

* `rollbackComplete` ist `!input.partialRollback` (`RollbackArtefactBuilder`), beim **Erzeugen** gesetzt; es sagt nur, ob Operationen ausgelassen
  wurden. Es hat mit dem Merkmal nichts zu tun und bleibt deshalb grün — nicht wegen des Fingerabdrucks.
* `postUpVerified` setzt `migrate --execute` aus dem gelesenen Post-Up-Stand (`SchemaMigrateRollbackArtefactBuilder`). Der Fingerabdruck
  (`MigrationFingerprint`, `ALGORITHM = schema-fingerprint-v24`) projiziert `TableMetadata` nicht; `TableComparator.metadataChangeOrNull` meldet
  die Differenz, kein Planer macht eine Operation daraus.
* `schema rollback` prüft **nach** der Ausführung nichts; es prüft nur die Vorbedingung (`SchemaRollbackRunner.verifyTargetMatchesArtefact`,
  Zielstand gegen `postUpFingerprint`, sonst `TARGET_STATE_MISMATCH`).

## Entscheidungen (Eigner, 2026-10-26)

1. **Down nimmt das Merkmal aus dem Zielzustand der Richtung** (Datenbank vor dem Lauf), nicht aus der Datei (Issue-Vorschlag 1). Mit der Produktentscheidung **Option C** gilt
   dasselbe für Up: die Datenbank-Seite entscheidet in beiden Richtungen; die Datei wirkt beim Neubau nicht aufs Merkmal. Dadurch verschwindet Fall A im Hinweg; der Rollback-Fix
   bleibt nötig für Down-Artefakte früherer Versionen und als Absicherung der Down-Regel.
2. **Nachprüfung (Issue-Vorschlag 3) → Option (c): nur dokumentieren.** `rollbackComplete` heißt „keine Operation ausgelassen", `postUpVerified` „der Post-Up-Stand wurde gelesen" —
   nicht „der Zustand vor dem Lauf ist exakt hergestellt". Zurückgestellt bzw. verworfen:
   * **(a) Fingerabdruck v25** zurückgestellt (Aufwand: eigener ADR, Exit 8 für alte Artefakte `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`, Driftrisiko C2). Neubewertung nur, wenn P0 Drift in
     anderen Fällen zeigt.
   * **(b') Generator-Meldung** entfällt: der Down-Neubau kann das Merkmal mit P1 immer herstellen.
   * **(b'') echte Post-Rollback-Prüfung im Runner** bleibt ein eigener Slice, nur bei Bedarf (das Artefakt müsste den Vorher-Zustand tragen).
3. **Alte Artefakte: bekannter Befund, keine Laufzeitprüfung.** Der Runner prüft nichts zur Laufzeit: das Vorher-Merkmal steckt weder im Artefakt noch im Fingerabdruck, ein Check wäre geraten
   und warnte korrekte Artefakte. Dokumentation siehe P3.

## Arbeitspakete

* **P0 Messung** (CLI-Test neben [`CliSchemaMigrateWithoutRowidSqliteTest`](../../../adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaMigrateWithoutRowidSqliteTest.kt);
  je frische Datenbank, Änderung `unique: true` auf `u`, `migrate --execute --generate-rollback`, `rollback --execute --allow-destructive`, Folgeplan):
  Fälle der Tabelle unten, je Hinweg und Rückweg: `sqlite_master.sql`, `down.sql`-Text, Kopfwerte, Folgeplan. Dazu die Messung zu Ursache B
  (Vorbedingung vs. Nachprüfung) und ob (a) C2 als Drift meldet. **Prüfpunkt Leser:** alle Leser von `withoutRowid` im Treiber durchgehen und je Down bewerten (was ist dort unter `currentSchema` zu verstehen?). **Erwartung (am Code geprüft, P0 bestätigt oder benennt die Abweichung):** `SqliteDiffRenderContext.currentSchema` wird aus `diff.currentSchema` gesetzt, ist also in Up **und** Down die Datenbank-Seite (Zustand vor dem Lauf); `desiredSchema` die Datei-Seite. Der Down-Zweig in `SqliteDiffDdlGenerator` tauscht nur Quelle/Ziel des Neubaus (`source = desired`, `target = current`), nicht diese Felder. `SqliteSequenceDdlSupport.currentSchema` (E057, `shouldSuppressNotNull`) ist dagegen ein **gleichnamiges, anderes** Feld: `beginRun(schema, …)` aus `SqliteDdlGenerator.generate`, also das Schema des `schema generate`-/`CreateTable`-Laufs, nicht `diff.currentSchema`. Erwartung: diese beiden Leser sind vom Down-Neubau nicht berührt und brauchen keine Änderung; Abweichung (z. B. Aufruf aus dem Diff-Pfad) wäre ein Befund: `SqliteRebuildPlan.withoutRowid`, `SqliteDiffRenderContext.tableWithoutRowid`, `SqliteTableDdlSupport` (`CREATE TABLE`-Klausel und Index-Erzeugung/FTS5), `SqliteSequenceDdlSupport.resolveSequenceDefault` (E057) und `shouldSuppressNotNull` (beide lesen `currentSchema.metadata.withoutRowid`), `SqliteSchemaReader` (Quelle). DoD: Messtabelle im Slice, Ist = Issue-Tabelle.
* **P1 Richtungsbewusste Regel.** `planRebuild`/`SqliteRebuildPlan` erhalten **eine** Struktur mit Datenbank-Seite, Datei-Seite und Richtung
  (kleines Wertobjekt, auch gegen `LongParameterList`: kein `@Suppress`). Sie trägt die entschiedene **Produktoption C** (Datenbank gewinnt, Up wie Down),
  damit `planRebuild` nicht zweimal umgebaut wird: die Regel ist eine Funktion dieser Struktur (entschieden: Up und Down nehmen die Datenbank-Seite).
  `SqliteDiffDdlGenerator` reicht sie durch; `SqliteDiffRenderContext.tableWithoutRowid` folgt. E057/`shouldSuppressNotNull` in `SqliteSequenceDdlSupport` bleiben unberührt (anderes `currentSchema`, siehe P0-Prüfpunkt). DoD: Fall A endet im Down-Skript ohne `WITHOUT ROWID`.
  Mit der entschiedenen **Produktoption C** wird Fall A im Hinweg ein Nullbefund; der Rollback-Fix bleibt nötig für Down-Artefakte früherer Versionen und als Absicherung. P1 teilt die
  Struktur mit dem Produktslice P1 (dort zuerst geliefert); hier bleiben Down-Wiring, `tableWithoutRowid` in Down und die Tests.
* **P2 Nachprüfung = Dokumentation (Option c).** Kein Code. Die Aussage der Kopfwerte (siehe Entscheidung 2) steht im Handbuch (nach dem Code) und wird beim Artefakt-Format-Eintrag der Spec geprüft.
  P0 misst, ob in **anderen** Fällen Drift zwischen Soll- und gelesenem Stand auftritt (Voraussetzung, (a) neu zu bewerten).
* **P3 Doku, getrennt:**
  * **Spec** (`spec/ddl-generation-rules.md` §3.5: Down = Datenbank vor dem Lauf) darf **mit dem Code** kommen (Zielbild); den Hinweg-Satz ändert nur der Produktslice.
  * **Handbuch** (`docs/user/anwenderhandbuch.md` §3.17, Rückweg und Aussage der Kopfwerte) **erst nach dem Code**.
  * **`CHANGELOG.md` zuerst: bekannter Befund alter Artefakte.** Betroffen sind Artefakte aus 1.11.0/1.11.1, die bei Datei `true` + Rowid-Datenbank mit Neubau entstanden. **Erkennung:**
    `down.sql` enthält einen `__dmg_rebuild`-Block mit `WITHOUT ROWID`, obwohl die Tabelle vorher Rowid war. **Abhilfe:** nicht angewendeten Lauf nach dem Update neu erzeugen; bei schon
    angewendetem Lauf manueller Eingriff.
  * **`docs/user/troubleshooting-leitfaden.md`** mit demselben Befund, **erst wenn der Fix läuft** (nur Ist-Zustand).

## Akzeptanzkriterien

Soll je Fall, Hin- **und** Rückweg (Änderung `unique: true`, Neubau; Hinweg nach der Entscheidung Option C):

| Fall | Datenbank | Datei `metadata` | Hinweg (nach Up) | Rückweg (nach Rollback) |
|---|---|---|---|---|
| A | Rowid | `true` | **Rowid** (+ Warnung, Produktslice; heute `WITHOUT ROWID`) | Rowid, Index `ux` zurück |
| B | Rowid | keine | Rowid | Rowid, Index zurück |
| C1 | `WITHOUT ROWID` | `true` | `WITHOUT ROWID` | `WITHOUT ROWID`, Index zurück |
| C2 | `WITHOUT ROWID` | keine | `WITHOUT ROWID` | `WITHOUT ROWID` (Rollback-Neubau nimmt es aus dem Datenbank-Stand), Index zurück |
| E | Rowid | `true`, **kein Neubau** (z. B. nur neue nullable Spalte) | Rowid (Merkmal wird nicht geplant) | Rowid |

Hinweis: C1 ist die im Review genannte Konstellation „Datei `true`, Datenbank `WITHOUT ROWID`, Rollback" und steht als eigene Zeile.

1. **CLI-Rundlauf je Fall** (A, B, C1, C2, E): `migrate --execute --generate-rollback`, `rollback --execute --allow-destructive`, Folgeplan gegen die Datei,
   mit `sqlite_master.sql` vor/nach jedem Schritt. Kein Fall nur in P0.
2. **Erwartung Folgeplan (vor Ausarbeitung festgehalten, P0 bestätigt):** nach Rollback in Fall A steht die Datenbank wie vor dem Lauf (Rowid, Index `ux`).
   Der Folgeplan ist deshalb derselbe wie vorher: `AddConstraint` + `DropIndex` (2 Operationen, siehe Issue), **keine** zusätzliche Operation für das Merkmal:
   `TableComparator.metadataChangeOrNull` meldet die Differenz, der Planer ignoriert sie (vgl. #60-Slice Fall D). Fälle B, C1, C2 gleichfalls derselbe Plan wie vor dem Lauf.
3. Fall A: Down-Skript ohne `WITHOUT ROWID`; Datenbank nach Rollback Rowid. Zusätzlich ein Down-Test, der ein von Hand gebautes Rowid-Vorher mit `true`-Quelle (Datei-Seite) prüft, unabhängig vom Hinweg (deckt Down-Artefakte, die ohne Hinweg-Neubau entstehen).
4. B, C1, C2, E unverändert (Regressionsschutz; C2 ist der Fall, der eine „immer aus der Datei"-Lösung bräche).
5. Unit: `SqliteRebuildPlanWithoutRowidTest`: der Test „Merkmal der Datenbank-Seite zaehlt in der Gegenrichtung (Down: newTable)" pinnt das ODER-Verhalten und wird an die neue Struktur **angepasst**, nicht nur ergänzt; neu: Down mit Rowid-Ziel + `true`-Quelle → `false`; `SqliteDdlGeneratorRollbackTest` pinnt den Down-Text für A und C2.
6. P2 (Option c): kein Test; DoD ist der Doku-Text (Kopfwerte) und der CHANGELOG-Befund; `make docs-check` grün.
7. Rot ohne die Änderung belegt (Haupt-Dateien auf `main`: A-Tests schlagen fehl, B/C1/C2/E absichtlich grün) und dass die Tests wirklich laufen (CLAUDE.md „Grün heißt nicht geprüft").

## Verifikation

* `make docker-check MODULES=":adapters:driven:driver-sqlite"` und `":adapters:driving:cli"`.
* Einmal **ohne** `MODULES`: geteilte Signatur, und `MODULES=` lässt `test/integration-*` aus; `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`.
* `make solid-suppression-gate` vor jedem Commit; `make docs-check`; vor dem Push `make doc-immutable RANGE=origin/main..HEAD` (Move `next/` → `in-progress/`:
  `consulted:`-Pfade in akzeptierten ADRs nicht nachziehen).

## Nicht-Scope

Das Merkmal per `schema migrate` hinzufügen oder entfernen; Fingerabdruck v25 und eine Post-Rollback-Prüfung im Runner (zurückgestellt bzw. eigener Slice nur bei Bedarf); andere Dialekte.
