# SQLite: ein Neubau behält das Merkmal der Datenbank, Abweichung der Datei wird gewarnt (Produktentscheidung zu Issue #67)

> **Status:** Entschieden (Eigner, 2026-10-26): **Option C plus Warnung `W164`**. In Umsetzung (2026-10-26).
> **Vorbedingung:** keine. **Reihenfolge:** **Schritt 0** Spec-Prosa (vor dem Code), (1) Struktur, Regel, Warnung, Tests, Ledger (P1–P4), Handbuch und `CHANGELOG.md` (P5) — **auf `main` geliefert**
> (Commits `e0b3a8e5a`, `6f93b467f`, `01f24c921`); (2) **Rollback-Rest** (P6–P8: Messung, Rundläufe, Kopfwert-Doku). Der Rest war als eigener Slice geplant und ist **auf Eigner-Beschluss (E-R1, 2026-10-26)
> in diesen Slice aufgegangen**; der frühere Slice `sqlite-rollback-without-rowid-vorher.md` ist gelöscht.
> **Fix-Branch:** `fix-review-slice1-issue-67` (Code-Review zu den Commits oben: `generateDown`-Tests für C1/C2 = LOW-2, Doppelung `SqliteRebuildSides`/`SqliteRebuildPlan` = LOW-1) war beim Stand dieser Notiz
> **noch nicht auf `main`**. **Vor Start von P6/P7 prüfen, ob gemergt**; P7 hängt davon ab.
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit; beim Move die relativen `../in-progress/`-Links auf `./` umstellen, die Rückverweise im
> Verweise und den Nachtrag im #60-Slice (`sqlite-without-rowid-im-neubau.md`, `../next/` → `./`) nachziehen **und die Status-Spalte der README-Zeilen** (`next/` und `in-progress/`) mitziehen;
> nur Planning-Dateien, kein ADR-`consulted:`. GitHub-Issue: [#67](https://github.com/pt9912/d-migrate/issues/67).
> **Vorgabe für Spec und Handbuch:** nur der gültige Zustand, zeitlos; keine Chronik oder Forensik (keine Versionen, keine Herleitung, keine Erkennung alter Artefakte).
> Version, Betroffene und Herleitung stehen nur im `CHANGELOG.md` und hier in `docs/planning/`.

## Ziel

Ein Tabellenneubau behält `WITHOUT ROWID` genau dann, wenn die **Datenbank** die Tabelle so führt. Die Datei ändert das Merkmal beim Neubau nicht. Führt die Datei
`metadata: { without_rowid: true }` gegen eine Rowid-Tabelle (Fall A), läuft die Migration weiter und warnt mit `W164`.

## Problem (Herleitung, nur Planning)

Handbuch §3.17 sagt zugleich: ein Neubau erhält das Merkmal, „wenn die Datenbank **oder die Datei** die Tabelle so führt" (Fall A: Umwandlung hin) **und** „`schema migrate` plant das Merkmal
selbst nicht: es ändert eine bestehende Tabelle weder hinzu noch weg". Beides zugleich stimmt für Fall A nicht. Die Regel stammt aus
[`sqlite-without-rowid-im-neubau.md`](sqlite-without-rowid-im-neubau.md) P1 (Datei oder Datenbank; als Folge in Kauf genommen); `spec/ddl-generation-rules.md` §3.5 trägt denselben Satz.
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

## Hintergrund zum Rückweg (Herleitung, nur Planning)

* **Ursache A (Down, behoben mit P1):** `SqliteRebuildPlan.withoutRowid` war `oldTable… || newTable…`, richtungsblind. In Down ist `source = desired` (Datei, `true`), `target = current` (Datenbank vor dem Lauf, Rowid); das ODER schrieb
  `WITHOUT ROWID`. `SqliteDiffDdlGenerator` kannte die Richtung, reichte sie nicht an `planRebuild` weiter; `SqliteDiffRenderContext.tableWithoutRowid` war ebenfalls richtungsblind.
* **Ursache B (offen, nur dokumentiert, P8):** die Kopfwerte sind **keine Nachprüfung**. `rollbackComplete` ist `!input.partialRollback` (`RollbackArtefactBuilder`), beim Erzeugen gesetzt, und sagt nur, ob Operationen
  ausgelassen wurden. `postUpVerified` setzt `migrate --execute` aus dem gelesenen Post-Up-Stand (`SchemaMigrateRollbackArtefactBuilder`). Der Fingerabdruck (`schema-fingerprint-v24`) projiziert `TableMetadata` nicht.
  `schema rollback` prüft nach der Ausführung nichts, nur die Vorbedingung (`SchemaRollbackRunner.verifyTargetMatchesArtefact`, sonst `TARGET_STATE_MISMATCH`). Deshalb blieben die Kopfwerte grün.
* **Warum das Merkmal aus der Datenbank kommt (Down):** Down stellt den Zustand **vor dem Lauf** her; der steht in der Datenbank, nicht in der Datei. Mit Option C gilt dasselbe für Up; Fall A verschwindet im Hinweg.
  Die Down-Regel bleibt nötig für Artefakte früherer Versionen und als Absicherung.

### Entscheidungen zum Rückweg (Eigner, 2026-10-26)

1. **Nachprüfung: Option (c), nur dokumentieren** (P8). (a) **Fingerabdruck v25 zurückgestellt:** er bräuchte einen ADR, ließe alte Artefakte mit Exit 8 `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` scheitern und
   birgt Driftrisiko bei C2. (b') **Generator-Meldung entfällt:** Down kann das Merkmal immer herstellen. (b'') **echte Post-Rollback-Prüfung im Runner:** eigener Slice, nur bei Bedarf.
2. **Alte Artefakte: keine Laufzeitprüfung.** Das Vorher-Merkmal steckt weder im Artefakt noch im Fingerabdruck; ein Check wäre geraten und warnte korrekte Artefakte. Der Befund steht nur im `CHANGELOG.md`
   (geliefert, P5), **nicht** in `docs/user/` und nicht im Troubleshooting-Leitfaden.
3. **Neue Regel:** zeigt die P6-Messung **Drift des Merkmals in anderen Fällen** (nach Rollback weicht `WITHOUT ROWID` von vor dem Lauf ab), wird daraus ein **neuer eigener Slice** für die Nachprüfung
   (Fingerabdruck v25 / ADR bzw. Post-Rollback-Prüfung). Der Rest in diesem Slice bleibt klein und wartet nicht darauf.

## Grenze: FULLTEXT (unabhängig von #67)

Fall A mit `FULLTEXT`-Index und `--execute` endet unabhängig von #67 mit Exit 8 (`TRANSACTION_SCOPE_UNSUPPORTED`). Eigener Slice:
[`sqlite-neubau-mit-fulltext-index-execute.md`](../next/sqlite-neubau-mit-fulltext-index-execute.md). **Hier kein Rollback-Rundlauf für A-FT mit `--execute`** (ohne angewendeten Lauf nichts zurückzurollen); der Rückweg
von A-FT gehört in den FULLTEXT-Slice.

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
* **P6 Messung Rundlauf** (Rest, nach P1–P5; Fix-Branch vorher prüfen): je frische Datenbank, `unique: true` auf `u`, `migrate --execute --generate-rollback`, `rollback --execute --allow-destructive`, Folgeplan; Fälle A, B, C1, C2, E,
  Hin- und Rückweg. Erfasst: `sqlite_master.sql`, `down.sql`-Text, Kopfwerte, Folgeplan; Messung zu Ursache B (Vorbedingung vs. Nachprüfung); **ob in anderen Fällen Drift auftritt** (löst ggf. Regel 3 aus).
  **Prüfpunkt Leser:** `SqliteRebuildPlan.withoutRowid` und `SqliteDiffRenderContext.tableWithoutRowid` sind mit P1/P3 erledigt; die übrigen gegenprüfen und je Down bewerten: `SqliteTableDdlSupport` (`CREATE TABLE`-Klausel,
  Index/FTS5), `SqliteSequenceDdlSupport.resolveSequenceDefault` (E057) und `shouldSuppressNotNull`, `SqliteSchemaReader` (Quelle). **Erwartung:** `SqliteSequenceDdlSupport.currentSchema` ist ein gleichnamiges, anderes Feld
  (`beginRun` aus `SqliteDdlGenerator.generate`) und vom Down-Neubau nicht berührt; Abweichung wäre ein Befund. DoD: Messtabelle im Slice.
* **P7 Rundlauf-Tests** (CLI, je Fall A, B, C1, C2, E): `migrate --execute --generate-rollback`, `rollback --execute --allow-destructive`, Folgeplan, mit `sqlite_master.sql` vor/nach jedem Schritt; Soll aus P6, gegen die
  Rückweg-Tabelle unten. Ist der Fix-Branch nicht gemergt: zusätzlich `generateDown`-Test für C1/C2 (kleiner Test in `SqliteDdlGeneratorRollbackTest`). DoD: Tests laufen wirklich (einmal absichtlich rot).
* **P8 Kopfwerte = Dokumentation (Option c).** Kein Code. Heute nennt `docs/user/` `rollbackComplete` und `postUpVerified` nirgends. Ort: Handbuch §3.17 (bei der `WITHOUT ROWID`-Zeile) oder der Rollback-Abschnitt bei
  `TARGET_STATE_MISMATCH` (Zeile ~1629); den passenderen wählen. **Erst beide Definitionen gegen `RollbackArtefactBuilder` und `SchemaMigrateRollbackArtefactBuilder` prüfen.** Zeitlos: „`rollbackComplete: true` heißt:
  keine Operation wurde ausgelassen. `postUpVerified: true` heißt: der Post-Up-Stand wurde gelesen. Beides sagt nichts über Strukturmerkmale wie `WITHOUT ROWID`." Kein „früher", kein Verweis auf #67, keine Chronik.
* **ADR:** nicht angenommen nötig (Planer-/Renderer-Verhalten, kein Fingerabdruck-/Modellvertrag); im Zweifel klärt der Eigner.

## P0 Messung (Ist vor der Änderung, 2026-10-26)

Aufbau: Tabelle `t(id, a)` mit Zeile, Datei mit `a` nullable (Neubau über `AlterColumnNullability`); Fall E: neue nullable Spalte `n` (kein Neubau). Je Fall `--plan-only` und `--execute`, dazu `--output` mit `--generate-rollback`.

| Fall | Hinweg (`up.sql`/Tabelle nach `--execute`) | `down.sql` | Warnung |
|---|---|---|---|
| A | `WITHOUT ROWID` (Befund) | `WITHOUT ROWID` (Befund) | keine |
| A-FT (neuer `FULLTEXT`-Index) | `WITHOUT ROWID`, FTS5 degradiert, `W132` (zweimal: Neubau und `AddIndex`) | `WITHOUT ROWID` | `W132` |
| B | Rowid | Rowid | keine |
| C1, C2 | `WITHOUT ROWID` | `WITHOUT ROWID` | keine |
| E / F | 1 Operation (`AddColumn`), Tabelle bleibt Rowid; die `metadata`-Differenz erzeugt keine Operation | n/a | keine |

Befunde:

* `--plan-only` rendert die Up-DDL (`statementsTotal` 9, `statements: null` im Report): eine Warnung aus dem Renderer erreicht auch den Plan.
* **Fundstelle der Warnung:** nur der Report (`--report`, `--report-format json|yaml`), Feld `diagnostics` (Einträge mit `code`, `severity`, `message`, `operationId`). `schema migrate` schreibt weder auf stdout noch auf stderr Diagnostics; `--output-format` gibt es bei `schema migrate` nicht (Usage-Fehler). Das `up.sql` trägt keine Diagnostics.
* **Fall A-FT mit `--execute`** endet schon vor der Änderung mit Exit 8 (`TRANSACTION_SCOPE_UNSUPPORTED`: Neubau und `AddIndex` in einem Lauf) — unabhängig von #67. Der Fall wird deshalb mit `--plan-only` und `--output` getestet.

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
   Ein **geblockter** Neubau (z. B. `NOT_NULL_BACKFILL_REQUIRED`) trägt keine `W164`: `render()` kehrt vor der Warnung zurück; der Neubau baut die Tabelle nicht um (Unit-Test).
5. Spec §3.5 und Handbuch §3.17 widersprechen sich und dem Verhalten nicht; beide ohne Chronik/Forensik.

Rückweg je Fall (P6/P7; `unique: true`, Neubau; Hinweg wie oben), A-FT ausdrücklich ohne Zeile (siehe „Grenze: FULLTEXT"):

| Fall | Datenbank | Datei `metadata` | Rückweg |
|---|---|---|---|
| A | Rowid | `true` | Rowid, Index `ux` zurück |
| B | Rowid | keine | Rowid, Index zurück |
| C1 | `WITHOUT ROWID` | `true` | `WITHOUT ROWID`, Index zurück |
| C2 | `WITHOUT ROWID` | keine | `WITHOUT ROWID` (aus dem Datenbank-Stand), Index zurück |
| E | Rowid | `true`, kein Neubau | Rowid |

6. **CLI-Rundlauf je Fall** (A, B, C1, C2, E) mit gemessenem Soll aus P6; B, C1, C2, E unverändert (Regressionsschutz; C2 bräche jede „immer aus der Datei"-Lösung).
7. **Folgeplan Fall A:** nach Rollback steht die Datenbank wie vor dem Lauf; der Folgeplan ist derselbe wie vorher: `AddConstraint` + `DropIndex` **und** `W164`, **keine** zusätzliche Operation für das Merkmal
   (`TableComparator.metadataChangeOrNull` meldet, der Planer ignoriert). B, C1, C2: derselbe Plan wie vor dem Lauf, ohne `W164`.
8. P8: Handbuchtext wie oben, `make docs-check` grün. Der Rot-Nachweis des Down-Wirings (Unit-Test mit handgebautem `DiffResult`) liegt bei P1 bzw. im Fix-Branch; hier nur prüfen, dass er vorhanden ist.
9. Zeigt P6 Drift in anderen Fällen: neuer Slice angelegt (`next/`), Rest dieses Slice schließt trotzdem.

## Verifikation

`make docs-check`; `make docker-check MODULES=":adapters:driven:driver-sqlite"`, `":adapters:driving:cli"` und das Modul des `CodeLedgerValidationTest`, einmal ohne `MODULES` (geteilte Signatur; `MODULES=` lässt `test/integration-*` aus),
`make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`, vor dem Push `make doc-immutable RANGE=origin/main..HEAD`.

## Nicht-Scope

Fingerabdruck v25 und eine Post-Rollback-Prüfung im Runner (zurückgestellt; bei Drift neuer Slice, siehe Regel 3); Rollback von A-FT mit `--execute` (FULLTEXT-Slice); `WITHOUT ROWID` per `schema migrate` gezielt hinzufügen/entfernen (eigener Slice, nur bei Bedarf).
