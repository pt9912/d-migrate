# SQLite: der Rollback-Neubau stellt `WITHOUT ROWID` aus der Datenbank vor dem Lauf her (Issue #67)

> **Status:** Draft mit Scope (2026-10-26, Review-Runde 1 eingearbeitet), Ursache am Code belegt, Messung P0 steht aus. Folge von
> [`sqlite-without-rowid-im-neubau.md`](../in-progress/sqlite-without-rowid-im-neubau.md) (#60).
> **Vorbedingung:** keine für P0/P1; P2 braucht die Eigner-Entscheidung 2 (und bei Option a einen ADR).
> **Reihenfolge der Slices:** [`sqlite-without-rowid-umwandlung-produktfrage.md`](sqlite-without-rowid-umwandlung-produktfrage.md)
> entscheidet **zuerst** (Eigner), ihre Datenstruktur-Anforderung steht in P1 unten; dieser Slice (P0/P1) kann parallel laufen.
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

## Entscheidungen / Annahmen (vom Eigner nicht endgültig bestätigt)

1. **Annahme (Issue-Vorschlag 1):** Der Down-Neubau nimmt das Merkmal aus dem **Zielzustand der Richtung**, also der Datenbank vor dem Lauf,
   nicht aus der Datei. Up behält „Datei oder Datenbank" (#60), bis der Produktslice anders entscheidet.
2. **Issue-Vorschlag 3 („ein Rollback, der das Vorher nicht herstellt, meldet nicht Erfolg") ist eine eigene Frage.** P1 erledigt ihn **teilweise**:
   nach P1 stellt das Down-Skript das Vorher her, die Abweichung entsteht gar nicht mehr. Offen bleibt, ob darüber hinaus eine Meldung nötig ist.
   **Entscheidung offen (P2), Optionen:**
   * **(a) `withoutRowid` in den Fingerabdruck** (`ALGORITHM` v25, nur-wenn-gesetzt wie `DomainTraits`). Wirkt auf `postUpVerified` **und** auf die
     Vorbedingung von `schema rollback` (`TARGET_STATE_MISMATCH`). Alte Artefakte scheitern benannt mit `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`
     (Exit 8). **Risiko C2:** Soll-Abdruck (Datei ohne Merkmal) gegen gelesenen Stand (`WITHOUT ROWID`) wäre Drift, obwohl #60 festlegt, dass eine
     Bestandsdatei das Merkmal nicht ändert — es braucht eine Soll-Projektion „Datei ohne Angabe = Datenbankwert". **ADR wahrscheinlich nötig**
     (Abdruck-Vertrag, nicht nur Test-Nachzug); akzeptierte ADRs bleiben unter `doc-immutable` unangetastet.
   * **(b') Generator-Meldung:** `SqliteDiffDdlGenerator` setzt `partialRollback` (also `rollbackComplete: false`) bzw. `skippedOperationIds`/Warnung,
     wenn der Down-Neubau das Merkmal nicht herstellen kann. Nur relevant, solange P1 nicht greift (Down-Artefakte nach P1 stellen es her).
   * **(b'') echte Post-Rollback-Prüfung im Runner:** `SchemaRollbackRunner` liest nach der Ausführung den Stand und vergleicht ihn mit dem Vorher.
     Eigener Umfang (Artefakt muss den Vorher-Abdruck/-Zustand tragen), eigener Slice wenn gewählt.
   * **(c)** nur dokumentieren, dass die Kopfwerte das Merkmal nicht einschließen.
   Empfehlung: erst P1 liefern, dann P0-Messung zu (a) gegen C2/Bestandsdateien, dann entscheiden; ohne Eigner-Entscheid bleibt (c).

## Arbeitspakete

* **P0 Messung** (CLI-Test neben [`CliSchemaMigrateWithoutRowidSqliteTest`](../../../adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaMigrateWithoutRowidSqliteTest.kt);
  je frische Datenbank, Änderung `unique: true` auf `u`, `migrate --execute --generate-rollback`, `rollback --execute --allow-destructive`, Folgeplan):
  Fälle der Tabelle unten, je Hinweg und Rückweg: `sqlite_master.sql`, `down.sql`-Text, Kopfwerte, Folgeplan. Dazu die Messung zu Ursache B
  (Vorbedingung vs. Nachprüfung) und ob (a) C2 als Drift meldet. **Prüfpunkt Leser:** alle Leser von `withoutRowid` im Treiber durchgehen und je Down bewerten (was ist dort unter `currentSchema` zu verstehen?). **Erwartung (am Code geprüft, P0 bestätigt oder benennt die Abweichung):** `SqliteDiffRenderContext.currentSchema` wird aus `diff.currentSchema` gesetzt, ist also in Up **und** Down die Datenbank-Seite (Zustand vor dem Lauf); `desiredSchema` die Datei-Seite. Der Down-Zweig in `SqliteDiffDdlGenerator` tauscht nur Quelle/Ziel des Neubaus (`source = desired`, `target = current`), nicht diese Felder. `SqliteSequenceDdlSupport.currentSchema` (E057, `shouldSuppressNotNull`) ist dagegen ein **gleichnamiges, anderes** Feld: `beginRun(schema, …)` aus `SqliteDdlGenerator.generate`, also das Schema des `schema generate`-/`CreateTable`-Laufs, nicht `diff.currentSchema`. Erwartung: diese beiden Leser sind vom Down-Neubau nicht berührt und brauchen keine Änderung; Abweichung (z. B. Aufruf aus dem Diff-Pfad) wäre ein Befund: `SqliteRebuildPlan.withoutRowid`, `SqliteDiffRenderContext.tableWithoutRowid`, `SqliteTableDdlSupport` (`CREATE TABLE`-Klausel und Index-Erzeugung/FTS5), `SqliteSequenceDdlSupport.resolveSequenceDefault` (E057) und `shouldSuppressNotNull` (beide lesen `currentSchema.metadata.withoutRowid`), `SqliteSchemaReader` (Quelle). DoD: Messtabelle im Slice, Ist = Issue-Tabelle.
* **P1 Richtungsbewusste Regel.** `planRebuild`/`SqliteRebuildPlan` erhalten **eine** Struktur mit Datenbank-Seite, Datei-Seite und Richtung
  (kleines Wertobjekt, auch gegen `LongParameterList`: kein `@Suppress`). Sie muss auch **Produktoption C** (Datenbank gewinnt immer, Up wie Down)
  tragen, damit `planRebuild` nicht zweimal umgebaut wird: die Regel ist eine Funktion dieser Struktur (Up/A: Datei oder Datenbank, Down: Datenbank, Up/C: Datenbank).
  `SqliteDiffDdlGenerator` reicht sie durch; `SqliteDiffRenderContext.tableWithoutRowid` folgt. E057/`shouldSuppressNotNull` in `SqliteSequenceDdlSupport` bleiben unberührt (anderes `currentSchema`, siehe P0-Prüfpunkt). DoD: Fall A endet im Down-Skript ohne `WITHOUT ROWID`.
  **Bei Produktoption C** wird Fall A im Hinweg ein Nullbefund; der Rollback-Fix bleibt nötig bei Option A/B und für Down-Artefakte früherer Versionen
  (nur als Text im Artefakt; `schema rollback` führt sie unverändert aus — kein Änderungsbedarf, aber im Handbuch zu nennen).
* **P2 Nachprüfung** nach Entscheidung 2 (a, b', b'' oder c). DoD je Option siehe Akzeptanz 6.
* **P3 Doku, getrennt:** **Spec** (`spec/ddl-generation-rules.md` §3.5: Down = Datenbank vor dem Lauf) darf **mit P1 gleichzeitig** kommen
  (Zielbild). **Handbuch** (`docs/user/anwenderhandbuch.md` §3.17, Rückweg) **erst nach dem Code**, `CHANGELOG.md` mit der Lieferung.
  Den Hinweg-Satz („weder hinzu noch weg") ändert **nur** der Produktslice; §3.5/§3.17 werden so nie in zwei Slices zugleich um dieselbe Zeile gebaut.

## Akzeptanzkriterien

Soll je Fall, Hin- **und** Rückweg (Änderung `unique: true`, Neubau; Up-Spalte gilt, solange der Produktslice Option A/B nicht ändert):

| Fall | Datenbank | Datei `metadata` | Hinweg (nach Up) | Rückweg (nach Rollback) |
|---|---|---|---|---|
| A | Rowid | `true` | `WITHOUT ROWID` | **Rowid**, Index `ux` zurück |
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
3. Fall A: Down-Skript ohne `WITHOUT ROWID`; Datenbank nach Rollback Rowid.
4. B, C1, C2, E unverändert (Regressionsschutz; C2 ist der Fall, der eine „immer aus der Datei"-Lösung bräche).
5. Unit: `SqliteRebuildPlanWithoutRowidTest`: der Test „Merkmal der Datenbank-Seite zaehlt in der Gegenrichtung (Down: newTable)" pinnt das ODER-Verhalten und wird an die neue Struktur **angepasst**, nicht nur ergänzt; neu: Down mit Rowid-Ziel + `true`-Quelle → `false`; `SqliteDdlGeneratorRollbackTest` pinnt den Down-Text für A und C2.
6. P2 je Option: (a) `schema rollback` gegen ein Ziel mit abweichendem Merkmal → `TARGET_STATE_MISMATCH`, alte Artefakte → `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`, C2 ohne Drift;
   (b') Generator-Test: nicht herstellbares Merkmal → `rollbackComplete: false`; (b'') Runner-Test mit absichtlich abweichendem Ergebnis; (c) Handbuchsatz.
7. Rot ohne die Änderung belegt (Haupt-Dateien auf `main`: A-Tests schlagen fehl, B/C1/C2/E absichtlich grün) und dass die Tests wirklich laufen (CLAUDE.md „Grün heißt nicht geprüft").

## Verifikation

* `make docker-check MODULES=":adapters:driven:driver-sqlite"` und `":adapters:driving:cli"` (bei P2 a/b'/b'' auch `:hexagon:core`/`:hexagon:application`).
* Einmal **ohne** `MODULES`: geteilte Signatur, und `MODULES=` lässt `test/integration-*` aus; `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`.
* `make solid-suppression-gate` vor jedem Commit; `make docs-check`; vor dem Push `make doc-immutable RANGE=origin/main..HEAD` (Move `next/` → `in-progress/`:
  `consulted:`-Pfade in akzeptierten ADRs nicht nachziehen; bei Option a ADR **neu** anlegen, nicht ändern).
* Bei Option a: Tests, die `schema-fingerprint-v24` pinnen, nachziehen (Suche nach dem Stempel).

## Nicht-Scope

Das Merkmal per `schema migrate` hinzufügen oder entfernen; die Entscheidung, ob die Umwandlung beim Neubau bleibt (Produktslice); andere Dialekte.
