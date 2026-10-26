# SQLite: Tabellen-Neubau mit `FULLTEXT`-Index derselben Tabelle in einem `--execute`-Lauf

> **Status:** Code, Tests und Doku (P1 bis P4) umgesetzt auf Branch `fix-neubau-fulltext-execute-p1-p4`; offen bis zur Abnahme: Eigner-Entscheidung zur Abweichung bei E3 (Exit 5 statt Exit 0 in den degradierten Fällen 8, 9), Integrationslauf, Review, Merge, Closure (Abschnitt Umsetzung). **Entschieden (Eigner, 2026-10-26):** E1 (Richtung (A)), E2 (Spatial ausgenommen, Nicht-Scope), E3, E4.
> **Anlass:** Fall „A-FT" aus der P0-Messtabelle von
> [`sqlite-without-rowid-umwandlung-produktfrage.md`](./sqlite-without-rowid-umwandlung-produktfrage.md) (Issue #67): Neubau (z. B. wegen `unique: true`) plus neuer `FULLTEXT`-Index in einem Lauf endet mit
> `--execute` mit Exit 8 (`TRANSACTION_SCOPE_UNSUPPORTED`), schon **vor** der #67-Änderung. #67 testet A-FT nur mit `--plan-only` und `--output`.
> **Unabhängig von #67:** beide Slices lassen sich in jeder Reihenfolge liefern; keiner setzt den anderen voraus. (Berührungspunkt, kein Hindernis: beide fassen `SqliteRebuildPlan`/`SqliteRebuildRenderer` an; wer später landet, rebased.)
> **Vorbedingung:** keine Eigner-Entscheidung, kein ADR, keine Spec vorab (siehe „Rückwirkung"). **Verwandt:** [`sqlite-neubau-allein-execute-bestandsdatei.md`](./sqlite-neubau-allein-execute-bestandsdatei.md) (#60, hat den Plain-/BTREE-Teil gelöst und `FULLTEXT` ausdrücklich als Folgeplan abgegrenzt),
> [`sqlite-neubau-abhaengige-sichten.md`](./sqlite-neubau-abhaengige-sichten.md) (#50, Neubau neben **anderer** Tabelle/Sicht), [`sqlite-fulltext-rebuild-recreate.md`](../done/sqlite-fulltext-rebuild-recreate.md) (Neubau erhält FTS5).
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit; beim Move die `../in-progress/`-Links auf `./`, die Status-Spalte der README-Zeile nachziehen; nur Planning-Dateien, kein ADR-`consulted:`.
> **Vorgabe für Spec und Handbuch:** nur der gültige Zustand, keine Chronik oder Forensik; Version und Herleitung nur im `CHANGELOG.md`.

## Ziel

`schema migrate --execute` gegen SQLite führt einen Lauf aus, der eine Tabelle neu baut **und** an derselben Tabelle einen `FULLTEXT`-Index anlegt oder entfernt, und endet danach ohne Fehler und ohne Drift. Der Rückweg (`schema rollback`) stellt den Stand vor dem Lauf her, einschließlich der FTS5-Objekte.

## Problem (Herleitung, nur Planning)

### Ursache (am Code belegt; P0 misst nach)

Dieselbe Klasse wie #50 und #60: **Neubau-Strom (`STREAM_OWNED`) neben einer `RUNNER_OWNED`-Anweisung im selben Plan.**

* **Fundstelle des Fehlers.** [`SchemaMigrateRenderPipeline.applyTransactionScopeGuard`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateRenderPipeline.kt) ruft nur bei `--execute`
  [`MigrationStreamClassifier.unsupportedTransactionScopeReason`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/migration/MigrationStreamClassifier.kt): `STREAM_OWNED` neben einem anderen Scope → „mixed transaction scopes are not executable as one migration stream"
  → Blocker `TRANSACTION_SCOPE_UNSUPPORTED`, Exit 8, Datenbank unverändert. `--plan-only` rendert dieselben Anweisungen ohne den Guard (Exit 0) — deshalb ist A-FT dort testbar.
* **Wer die Scopes setzt.** [`SqliteDiffRenderContext.emitRebuildStatement`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffRenderContext.kt) gibt jede Neubau-Anweisung als `STREAM_OWNED` aus (eigene `BEGIN IMMEDIATE`/`COMMIT`-Klammer samt
  `PRAGMA foreign_keys`-Rahmen); einfache Operationen (`ctx.emit`) sind `RUNNER_OWNED`.
* **Warum der `FULLTEXT`-Index eine einfache Operation bleibt.** [`SqliteRebuildPlanner.isAbsorbedIndexOp`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt) (Lieferung von #60) nimmt `AddIndex`/`DropIndex` einer Neubau-Tabelle in den Neubau-Eimer auf,
  **nimmt aber `IndexType.FULLTEXT` und Geometrie-Indizes aus** (Begründung dort: `DROP TABLE` räumt eine FTS5-Virtual-Table nicht weg, `SqliteDiffSimpleOps` hat für beide eigene Pfade). Der `FULLTEXT`-`AddIndex` landet in `simpleOps` und wird als `RUNNER_OWNED` gerendert (`SqliteFullTextExpansion.emitCreate`).
* **Zweiter Fehler dahinter (verdeckt, wie bei #60).** Der Neubau legt die Indizes der Zielsicht selbst an: `indexesToRecreate = target.indices` enthält den neuen `FULLTEXT`-Index schon, [`SqliteRebuildRenderer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt)
  legt ihn über `emitFullTextIndexRecreate` an (FTS5-Tabelle, `'rebuild'`, drei Trigger) und räumt alte FTS5-Objekte der Quellsicht in der Drop-Phase selbst ab (`DROP … IF EXISTS`). Die `simpleOp` wiederholt das: Messung aus #60 (Fälle 11a/11b, ohne Guard per JDBC):
  neu → `table "ft_b" already exists`; weg → das DOWN legt FTS5 zweimal an. Wer nur den Guard lockert, tauscht Exit 8 gegen einen Laufzeitfehler mitten im Lauf (die Option „Guard lockern" ist im #60-Slice als (d) verworfen).
* **Nebenwirkung bei degradiertem FTS5.** Kann FTS5 nicht gebaut werden (`SqliteFullTextExpansion.unsupportedReason`: `WITHOUT ROWID`, reservierter/kollidierender Spaltenname), steht `W132` zweimal im Report (Neubau und `AddIndex`; so gemessen in der #67-Tabelle).
* **Kein Fall „`PRAGMA foreign_keys` in der Transaktion".** Der Neubau setzt das Pragma außerhalb seiner Klammer (`SQLITE_REBUILD_OUTSIDE_TX_HINTS`); die Ablehnung kommt allein aus dem Scope-Mix, nicht aus `CREATE VIRTUAL TABLE` selbst. `CREATE VIRTUAL TABLE` ist in SQLite innerhalb einer Transaktion zulässig; ob es im Neubau-Strom (nach `RENAME`) Besonderheiten gibt, ist durch den Geschwister-Pfad (`emitFullTextIndexRecreate`, geliefert mit `sqlite-fulltext-rebuild-recreate`) bereits abgedeckt (Neubau **mit unverändertem** `FULLTEXT` läuft); P0 bestätigt es mit einer Messung.

### Bestand der Doku und Tests

* Bekannt und dokumentiert: Handbuch §3.17 („Index neben einem Neubau": `FULLTEXT` neben Neubau blockiert `--execute` mit Exit 8). Kein eigenes Issue, kein eigener Slice, kein Befund unter `docs/planning/open/` (geprüft mit `rg -i fulltext docs/planning` und `gh issue list`; offene Issues: #67, #68, beide nicht dieser Fall).
  Der #60-Slice nennt die Lücke als „Befund für einen Folgeplan" — dieser Slice ist dieser Folgeplan.
* Pin-Test auf dem Ist-Zustand: `CliSchemaMigrateRebuildIndexOpsSqliteTest` „FULLTEXT-Index neben einem Neubau behaelt seinen Pfad: --execute bleibt Exit 8" (grün **mit** dem Fehler; kein Rot-Beleg, wird umgeschrieben); `SqliteRebuildPlannerTest` pinnt die `FULLTEXT`-Ausnahme.

## Schnitt: nur `FULLTEXT`, nicht „Neubau + alles"

Die Klasse „Neubau neben `RUNNER_OWNED`-Anweisung" hat drei Auslöser; nur einer ist dieser Slice:

| Auslöser | Stand | Zuständig |
|---|---|---|
| Plain-/BTREE-Index derselben Tabelle (neu, weg, unverändert) | gelöst (absorbiert) | #60 |
| **`FULLTEXT`-Index derselben Tabelle (neu, weg, geändert)** | blockiert, Exit 8 | **dieser Slice** |
| Geometrie-/Spatial-Index derselben Tabelle | blockiert; zu messen, ob überhaupt erreichbar (11c in #60: `E052` greift schon vorher, wenn das Spatial-Profil `none` ist) | **P0 entscheidet** (Entscheidung E2) |
| Änderung an einer **anderen** Tabelle, Sicht, Trigger neben dem Neubau | blockiert | #50 / eigener Befund; **nicht** hier |

Der Schnitt ist kein Raten: P0 misst die Matrix unten, auch die Fälle, die **nicht** betroffen sein sollen (Gegenprobe), und der Schnitt wird gegen die Messung bestätigt.

## Lösungsoptionen (entschieden: (A))

* **(A) `FULLTEXT`-Index-Operationen der Neubau-Tabelle in den Neubau-Eimer absorbieren** (**gewählt, Eigner 2026-10-26**; spiegelt #60). `isAbsorbedIndexOp` nimmt `FULLTEXT` auf; der Neubau legt FTS5 samt `'rebuild'` und Triggern aus `indexesToRecreate` an und räumt die Objekte der Quellsicht ab (beides existiert und ist getestet).
  Kein `RUNNER_OWNED` neben dem Strom → Guard und Doppelung sind zugleich erledigt; `W132` entsteht einmal. Kosten und Prüfpunkte: `operationIds` des Neubau-Statements tragen die Index-IDs (Report, Drift-Prüfung nach dem Lauf); DOWN: das Rückplan-Neubau baut aus der Gegenrichtung, die FTS5-Objekte der Datei-Seite müssen **weg** (Drop-Phase der Rückrichtung), die der Datenbank-Seite zurück;
  Ausnahmefälle mit eigener Meldung bleiben (`unsupportedReason` → `W132`-Skip-Marker); die Vorprüfungen der `simpleOp` (`checkAbsorbedIndexOps`: Rohtext, INCLUDE/Bitmap) laufen für absorbierte erzeugende Operationen weiter.
* **(B) Execute in zwei Segmente teilen** (Neubau in eigener Transaktion, danach die Runner-Anweisungen). Der Executor teilt schon heute `RUNNER_TRANSACTION` und `NO_TRANSACTION` in Segmente. Löst die Doppelung **nicht** (FTS5 zweimal) und gibt die Alles-oder-nichts-Aussage des Laufs auf (Rollback-Treue `FULL_ROLLBACK_CONFIRMED`, Fingerabdruck-Vorbedingung der Rückführung, Teilzustand bei Fehler im zweiten Segment). Ändert Vertrag in `spec/cli-spec.md` und für **alle** Dialekte/Fälle. **Verworfen (Eigner):** ändert den Transaktionsvertrag für alle Fälle und Dialekte, löst die Doppelung nicht.
* **(C) Fehler beibehalten, besser dokumentieren.** Handbuch nennt Exit 8 samt Ausweg: zwei Läufe (erst Neubau, dann `FULLTEXT`-Index). Kein Code. **Verworfen (Eigner)** als Lösung; nur Rückfall, falls P0 zeigt, dass Absorbieren den Rückweg nicht treu herstellt (dann zurück an den Eigner, nicht stillschweigend).
* **(D) Verworfen: Guard für `RUNNER_OWNED` derselben Tabelle lockern.** Ersetzt Exit 8 durch „already exists" mitten im Lauf (Messung #60, Fälle 11a/11b).

**Kriterien:** (1) wirkt für „Neubau + `FULLTEXT` neu / weg / geändert derselben Tabelle"; (2) Rückweg treu (`catalog()`-Snapshot inkl. FTS5-Objekte gleicht dem Stand vor dem Lauf); (3) kein neuer Vertrag, kein Fingerabdruck-Tag; (4) Aufwand.
**Entscheidung: (A).** Gleiches Muster wie #60, mechanisch im Planner/Renderer, kein ADR und kein neuer Tag erwartet (der Abdruck entsteht aus dem Schema, nicht aus dem Plan; P1 belegt es erneut gegen `MigrationFingerprint`).

## Entscheidungen (Eigner, 2026-10-26) und offene Frage

* **E1 Richtung: entschieden, (A) absorbieren.** (B), (C), (D) verworfen (siehe oben). Kein ADR, keine Spec-Änderung als Vorbedingung.
* **E2 Spatial: entschieden (Eigner, 2026-10-26): ausgenommen, Nicht-Scope** (`isAbsorbedIndexOp` behält die Geometrie-Ausnahme; kein Handbuchsatz). Ursprüngliche Frage: Geometrie-Index mit im Scope? Hängt an P0: ist der Fall ohne `E052` erreichbar (mit gesetztem Spatial-Profil), und was zeigt er? Erreichbar → Empfehlung: im selben Muster mitnehmen **oder** ausdrücklich ausgenommen und im Handbuch benannt. Nicht erreichbar → Nicht-Scope, kein Satz im Handbuch.
* **E3 Degradierter Fall (FTS5 nicht baubar, z. B. `WITHOUT ROWID`): entschieden, ja.** Der Neubau läuft mit `W132` (einmal) und ohne FTS5, **kein** Blocker (wie der Neubau mit unverändertem `FULLTEXT`).
* **E4 Slice-Zuschnitt: entschieden, eigener Slice**, keine Zusammenlegung mit #60/#67 (#60 hat den Plain-Teil geliefert, #67 behandelt ein anderes Merkmal).

## Arbeitspakete

* **P0 Messung** (SQLite-CLI-Test gegen echte Datei; je Fall `--plan-only`, `--execute`, zweiter Lauf, `schema rollback`; Katalog-Snapshot inkl. `sqlite_master` der FTS5-Objekte vor/nach; zusätzlich das Skript **ohne Guard** per JDBC, Mechanismus wie in #60). Erwartung in Klammern; Abweichung ist der Befund.
  **DoD:** Tabelle ausgefüllt, Schnitt bestätigt oder korrigiert, E2 entscheidbar.

  | # | Fall | Erwartung heute |
  |---|---|---|
  | 1 | Neubau (`unique: true` gegen `unique`-Spalte neu) + neuer `FULLTEXT`-Index, Rowid-Tabelle (A-FT) | Exit 8; ohne Guard `table "ft_b" already exists` |
  | 2 | Neubau (`AlterColumnNullability`) + neuer `FULLTEXT`-Index | wie 1 |
  | 3 | Neubau + `FULLTEXT`-Index weg | Exit 8; DOWN legt FTS5 zweimal an |
  | 4 | Neubau + `FULLTEXT`-Index geändert (Spalten anders, gleicher Name: `DropIndex` + `AddIndex`) | Exit 8 |
  | 5 | Neubau, `FULLTEXT`-Index **unverändert** auf derselben Tabelle | **Gegenprobe:** Exit 0, FTS5 samt Triggern danach vorhanden, zweiter Lauf 0 Operationen |
  | 6 | `FULLTEXT` neu **ohne** Neubau (nur `AddIndex`) | **Gegenprobe:** Exit 0 (ein Umfang) |
  | 7 | Neubau an `t`, `FULLTEXT` neu an **anderer** Tabelle `x` | Exit 8 (`RUNNER_OWNED` neben Strom, fremde Tabelle); bleibt nach dem Fix (gehört zu #50) |
  | 8 | Neubau + `FULLTEXT` neu, Tabelle `WITHOUT ROWID` (FTS5 nicht baubar) | Exit 8; Plan: `W132` zweimal |
  | 9 | Neubau + `FULLTEXT` neu, reservierter Spaltenname | wie 8 |
  | 10 | Neubau + `FULLTEXT` neu, Tabelle hat unveränderten Plain-Index und einen Fremdschlüssel | Exit 8; Neubau trägt Index und FK |
  | 11 | Spatial-Index neben Neubau, Spatial-Profil gesetzt (ohne `E052`) | zu messen (E2) |
  | 12 | Rückweg des Falls 1 (`down.sql` aus `--plan-only --output`, ohne Guard) | zu messen: FTS5-Doppelung im DOWN? Rollback-Treue ist vor dem Fix nicht messbar (Lauf scheitert) |

  Zusätzlich: der Bezug zu #67 (Rowid-Datenbank, `without_rowid: true` in der Datei, Neubau + `FULLTEXT`): Hinweg bleibt Rowid, FTS5 wird aufgebaut — als Kontrolle, keine Vorbedingung.
* **P1 Bestätigung und E2:** E2 mit dem Eigner nach P0 entscheiden. **Tag/ADR-Bedarf:** Abdruck (`MigrationFingerprint`, aktueller Tag zum Aktivierungszeitpunkt ablesen, nicht aus diesem Plan übernehmen) hängt am Schema nach Reader und Projektion, nicht am Plan → erwartet **kein** Bump, **kein** ADR; P1 belegt es.
* **P2 Umsetzung** — **DoD** je Punkt mit Test, der ohne die Änderung fehlschlägt:
  * Planner: `isAbsorbedIndexOp` nimmt `FULLTEXT` auf (Ausnahme Geometrie, bis E2 entschieden ist); `SqliteRebuildPlannerTest` „FULLTEXT bleibt einfache Operation" wird **umgedreht** (Pin, grün mit dem Fehler).
  * Renderer: UP und DOWN je genau ein FTS5-Aufbau je Ziel-`FULLTEXT`-Index, kein `DROP`/`CREATE` ohne Gegenstück; `W132` einmal; Vorprüfungen (`checkAbsorbedIndexOps`) laufen für den absorbierten `FULLTEXT`-`AddIndex`.
  * Klassifizierer-Test: Neubau + `FULLTEXT` derselben Tabelle ist kein gemischter Strom; Neubau + fremde Anweisung (Fall 7) bleibt abgelehnt.
  * CLI-Tests (Fälle 1–4, 8, 10): `--execute` Exit 0, FTS5-Objekte vorhanden bzw. entfernt, zweiter Lauf `operationsTotal` 0, **Rückweg** mit `catalog()`-Vergleich gegen den Stand vor dem Lauf (inkl. FTS5-Tabelle und Trigger); Gegenproben 5, 6, 7 unverändert; Pin-Test „bleibt Exit 8" umschreiben.
  * **Rot-ohne-Änderung:** Renderer-Test auf der Anweisungsfolge und CLI-Test „Skript ohne Guard per JDBC" (Fälle 1, 3, 12) schlagen auf dem Stand vor der Änderung fehl („already exists"); einmal mit absichtlich falscher Zusicherung rot gesehen (CLAUDE.md „Grün heißt nicht geprüft").
* **P3 Doku (getrennt, nach dem Code):**
  * `docs/user/anwenderhandbuch.md` §3.17, Absatz „Index neben einem Neubau": `FULLTEXT` aus der Ausnahme nehmen; es bleibt „eine Änderung an einer **anderen** Tabelle" (und Geometrie, falls E2 es ausnimmt). Zeitlos formuliert: nur der gültige Zustand, kein „früher", keine Issue-Nummer.
  * `CHANGELOG.md`: Eintrag mit Version und Herleitung (hier und nur hier).
  * `docs/planning/in-progress/sqlite-neubau-allein-execute-bestandsdatei.md`: Verweis im Abschnitt zu 11a/11b nachtragen („erledigt in …").
* **P4 Abschluss:** Gates (unten), Move nach `done/` mit Closure-Notiz; Bezug zu #67 (A-FT mit `--execute` jetzt testbar): der #67-Slice darf seinen Hinweis „nur `--plan-only`/`--output`" erst **nach** diesem Slice streichen; er wartet nicht darauf.

### P0 Messung (Ergebnis)

Gemessen am Runtime-Image (`make docker-build`, `d-migrate:dev`) gegen echte SQLite-Dateien; Datenbank mit `sqlite3` aus SQL (3.54, FTS5 vorhanden), Soll-Schema als YAML. Je Fall: `schema migrate --plan-only --output` (Plan), `--generate-rollback --output --rollback-output` ohne Ausführung (Skripte), `--execute` (Exit, Diagnostics, `sqlite_master` vor/nach), bei Exit 0 zweiter Lauf und `schema rollback --execute --allow-destructive` mit Katalogvergleich, **Skript ohne Guard** (`up.sql` direkt mit `sqlite3 -bail`, danach `down.sql`). Das Messprogramm war ein Wegwerf-Shellskript; `--plan-only` verbietet `--rollback-output` („--plan-only forbids --rollback-output"), deshalb zwei Läufe. Der Mechanismus „Skript direkt ausführen" ist derselbe wie in #60 (`CliSchemaMigrateRebuildIndexOpsSqliteTest`, Skript per JDBC; dort trigger-bewusster Teiler). Bei „Skript ohne Guard" ist der Katalog nach UP und DOWN in allen Fällen gleich dem Stand vor dem Lauf, wo der Fehler nach dem COMMIT des Neubaus auftritt; die Fehler sind die Befunde.

| # | Fall | Gemessen | Erwartung |
|---|---|---|---|
| 1 | Neubau (`unique: true` gegen `CREATE UNIQUE INDEX`) + `FULLTEXT` neu, Rowid-Tabelle | Plan Exit 0, 3 Operationen; `--execute` **Exit 8** `TRANSACTION_SCOPE_UNSUPPORTED`, DB unverändert. Ohne Guard: UP bricht am zweiten `CREATE VIRTUAL TABLE "ft_b"` mit `table "ft_b" already exists` (danach `trigger "ft_b_ai" already exists`); FTS5 samt Triggern steht schon aus dem Neubau | bestätigt |
| 2 | Neubau (`AlterColumnNullability`) + `FULLTEXT` neu | wie 1 (Exit 8; ohne Guard `ft_b already exists`) | bestätigt |
| 3 | Neubau + `FULLTEXT` weg | Exit 8; UP ohne Guard läuft (Neubau räumt FTS5 per `DROP … IF EXISTS`); **DOWN** legt FTS5 zweimal an: `table "ft_b" already exists` | bestätigt |
| 4 | Neubau + `FULLTEXT` geändert (gleicher Name, andere Spalte) | Exit 8; Plan: 3 Operationen. Ohne Guard laufen UP und DOWN **fehlerfrei**: der Neubau legt FTS5 an, die `simpleOp` räumt per `DROP … IF EXISTS` ab und legt erneut an (zweimal `CREATE VIRTUAL TABLE` im UP, redundant, kein Fehler) | **Abweichung** (Erwartung nur „Exit 8"): kein Laufzeitfehler, nur Doppelung |
| 5 | Gegenprobe: Neubau, `FULLTEXT` **unverändert** | Exit 0, FTS5 samt drei Triggern danach vorhanden, zweiter Lauf 0 Operationen, **Rollback Exit 0, Katalog == Stand vor dem Lauf**; je ein `CREATE VIRTUAL TABLE` in UP und DOWN | bestätigt |
| 6 | Gegenprobe: `FULLTEXT` neu ohne Neubau | Exit 0, zweiter Lauf 0, Rollback Exit 0, Katalog == vorher | bestätigt |
| 7 | Neubau an `t`, `FULLTEXT` neu an **anderer** Tabelle `x` | Exit 8 `TRANSACTION_SCOPE_UNSUPPORTED`; ohne Guard laufen UP und DOWN fehlerfrei (kein Doppel-Anlegen) | bestätigt (gehört zu #50) |
| 8 | Neubau + `FULLTEXT` neu, Tabelle `WITHOUT ROWID` (Datei `without_rowid: true`) | Exit 8; `W132` **zweimal** im Report (Plan und Execute); kein FTS5 im Skript; ohne Guard UP/DOWN fehlerfrei | bestätigt |
| 9 | Neubau + `FULLTEXT` neu, Spalte `rank` | wie 8 (Exit 8, `W132` zweimal) | bestätigt |
| 10 | Neubau + `FULLTEXT` neu, unveränderter Plain-Index `ix_u` und Fremdschlüssel | Exit 8; ohne Guard `ft_b already exists`; `ix_u` und FK im Neubau; DOWN fehlerfrei | bestätigt |
| 11 | Spatial-Index neben Neubau, `--spatial-profile spatialite` (ohne `E052`) | Plan **und** `--execute` Exit 8, aber **nicht** `TRANSACTION_SCOPE_UNSUPPORTED`: `SPATIAL_METADATA_UNSUPPORTED` (BLOCKER, je Operation des Neubau-Eimers) und `EXTENSION_DEPENDENCY_UNKNOWN` (BLOCKER am `AddIndex`); es entstehen keine Skripte. Ohne Profil (11b): `E052` wie in #60 | **Abweichung/Befund:** siehe E2 unten |
| 12 | Rückweg des Falls 1 (`down.sql`, ohne Guard) | läuft nach dem UP fehlerfrei; der Katalog gleicht danach dem Stand vor dem Lauf (der Stand vor dem Lauf hat kein FTS5, die Drop-Phase des Neubaus räumt `ft_b` ab); **keine** FTS5-Doppelung im DOWN von Fall 1. Die Doppelung steht im DOWN von Fall 3 (FTS5 in der Datenbank) | **Abweichung:** Erwartung „FTS5-Doppelung im DOWN" gilt nur für Fall 3, nicht für 1 |
| K | Kontrolle #67: Rowid-Datenbank, Datei `without_rowid: true`, Neubau + `FULLTEXT` neu | Exit 8; `W164` im Report; ohne Guard wie Fall 1 (`ft_b already exists`); der Neubau bleibt Rowid, FTS5 wird aufgebaut | bestätigt |

**E2 (Spatial), Befund.** Fall 11 ist als „Neubau + Geometrie-Index derselben Tabelle in einem `--execute`-Lauf" **nicht erreichbar**, auch mit gesetztem Spatial-Profil. Ursache am Code: `SqliteRebuildRenderer.emitGeometryBlockerIfAny` blockiert **jeden** Neubau einer Tabelle, deren Soll- oder Ist-Seite eine `Geometry`-Spalte trägt (`SPATIAL_METADATA_UNSUPPORTED`, `MANUAL_ACTION_REQUIRED`), unabhängig vom Profil; ein Geometrie-Index (`isSpatialGeometryIndex`: Typ nicht `FULLTEXT` und Spalte `Geometry`) setzt aber eine solche Spalte voraus. Der Strom wird gar nicht erst ausgegeben, der Guard `TRANSACTION_SCOPE_UNSUPPORTED` erreicht ihn nicht. **Empfehlung E2: ausnehmen, Nicht-Scope** (`isAbsorbedIndexOp` behält die Geometrie-Ausnahme); kein Satz im Handbuch §3.17 nötig, weil die Blockade eine andere, schon eigenständige Ursache und Meldung hat. Eine Aufnahme wäre sinnlos, solange der Neubau Geometrie-Registrierung nicht mitnimmt (eigener Posten, im Renderer-Kommentar benannt).

**Schnitt bestätigt:** nur `FULLTEXT` derselben Tabelle ist dieser Slice; Plain-/BTREE (#60) gelöst, fremde Tabelle (Fall 7) bleibt #50, Geometrie unerreichbar (E2). **Rückweg im Ist-Stand:** wo messbar (Fälle 5, 6 und Kontrolle: Neubau mit unverändertem `FULLTEXT`, nur `AddIndex`), stellt `schema rollback --execute` den Katalog (inkl. FTS5-Tabelle, Schatten-Tabellen und drei Trigger) exakt wieder her, keine Abweichung. Für die blockierten Fälle ist der Rollback-Rundlauf vor dem Fix nicht messbar; ersatzweise UP+DOWN ohne Guard: Katalog gleich dem Stand vor dem Lauf, aber DOWN von Fall 3 bricht mit `already exists`, und Fall 4 zeigt die redundante Doppelung (P2 muss auch dort je Ziel-Index genau einen Aufbau erzeugen). Die Slice-Annahme „`W132` zweimal" (Fälle 8, 9) ist gemessen.

## Umsetzung (P1 bis P4)

**P1, Abdruck und ADR.** `MigrationFingerprint.ALGORITHM` steht bei `schema-fingerprint-v24` und bleibt. `MigrationFingerprint.compute` (`hexagon/core`) nimmt nur ein `SchemaDefinition` samt Kanonisierern; Plan, Diff und Renderer gehen nicht ein. Die Änderung berührt allein die Anweisungsfolge des SQLite-Neubaus (`SqliteRebuildPlanner`), nicht das Schema nach Reader und Projektion. Kein Bump, kein ADR. `spec/ddl-generation-rules.md` und `spec/cli-spec.md` enthalten keine Aussage zu `FULLTEXT` neben einem Neubau; `spec/` blieb unverändert.

**P2, Code.** Allein `SqliteRebuildPlanner.isAbsorbedIndexOp`: die `FULLTEXT`-Ausnahme entfällt, die Geometrie-Ausnahme bleibt. Renderer (`emitDependentDropsBeforeBaseDrop`, `emitFullTextIndexRecreate`) und die Vorprüfungen (`checkAbsorbedIndexOps`) trugen den `FULLTEXT`-Fall schon (Lieferung von `sqlite-fulltext-rebuild-recreate`), brauchten keine Änderung. Der Guard (`MigrationStreamClassifier.unsupportedTransactionScopeReason`, `applyTransactionScopeGuard`) ist unverändert und greift nicht mehr, weil kein zweiter Scope entsteht; Fall 7 (fremde Tabelle) bleibt abgelehnt.

**P3, Tests.** `SqliteRebuildIndexOpsTest` (Anweisungsfolge, Hin- und Rückweg, Fälle 1 bis 4, 8, 9, 10, Gegenprobe Fall 7, Strom ohne gemischten Scope), `SqliteRebuildPlannerTest` (Pin umgedreht, neuer Test für `FULLTEXT` einer anderen Tabelle), `CliSchemaMigrateRebuildIndexOpsSqliteTest` (CLI je Fall mit `--execute`, zweiter Lauf, `schema rollback` mit Katalogvergleich; Skript ohne Guard per JDBC; Gegenproben 5, 6, 7). Die Pin-Tests „FULLTEXT bleibt einfache Operation" und „bleibt Exit 8" sind ersetzt (waren grün mit dem Fehler, kein Rot-Beleg). **Rot ohne Änderung** (nur die Planner-Datei auf den Stand vor der Änderung zurückgestellt): 8 Tests in `driver-sqlite` und 11 im CLI schlagen fehl, darunter alle Skript-Tests (`CREATE VIRTUAL TABLE` je Richtung zweimal statt einmal) und alle neuen Hin-/Rückweg-Fälle; die Gegenproben 5, 6, 7 bleiben grün.

**Messbefund zu Fall 4 (geändert).** Vor der Änderung fehlerfrei, aber doppelt: der Neubau baut FTS5, die `simpleOp` räumt per `DROP ... IF EXISTS` ab und legt erneut an. Jetzt je Richtung ein Aufbau und ein Abräumen; der Skript-Test belegt es.

**Abweichung von der Slice-Annahme E3 (Eigner-Entscheidung nötig).** In den degradierten Fällen 8 und 9 läuft der Neubau mit `W132` genau einmal, ohne FTS5 und ohne `TRANSACTION_SCOPE_UNSUPPORTED`, aber `--execute` endet mit **Exit 5** (`POST_EXECUTE_DRIFT`, BLOCKER) statt Exit 0, und der zweite Lauf zeigt eine Operation (den `AddIndex`). Die Ursache liegt nicht in dieser Änderung: dieselbe Datei gegen dieselbe Tabelle **ohne** Neubau ergibt ebenfalls Exit 5 und eine Operation im zweiten Lauf (gemessen), denn die Datei führt einen `FULLTEXT`-Index, den die Datenbank-Tabelle nicht tragen kann. Die Tests pinnen das Ist-Verhalten (Exit 5, `W132` einmal, kein FTS5, der Rückweg stellt den Katalog her; der zweite Lauf wird für 8 und 9 nicht auf 0 geprüft). Wer Exit 0 will, ändert die Drift-Prüfung für degradierte Indizes: eigener Posten, nicht Teil dieses Slice. Akzeptanzkriterium 2 gilt deshalb für die Fälle 1 bis 4 und 10 uneingeschränkt, für 8 und 9 mit dieser Einschränkung.

**P3 Doku.** Handbuch §3.17 („Index neben einem Neubau"): `FULLTEXT` derselben Tabelle aus der Ausnahme genommen; `CHANGELOG.md` unter `[Unreleased]`; Verweis im #60-Slice zu 11a/11b.

**Bis zur Abnahme fehlt (Closure-Sektion nach ADR 0004, noch nicht geschrieben):** Eigner-Entscheidung zu Exit 5 in den Fällen 8 und 9; Integrationslauf `:test:integration-sqlite:test` und vollständiger CI-Stand; Review; Merge; dann Move nach `done/` mit `## Closure`, `../done/`-Links und Status in der README-Zeile; der #67-Slice darf erst danach seinen Hinweis „nur `--plan-only`/`--output`" streichen.

## Rückwirkung auf Spec und Handbuch (getrennt)

* **`spec/` (Zielbild, darf vorgreifen):** keine Render-Regel ändert sich mit (A) — dieselben Anweisungen, nur nicht doppelt (wie #60). P0/P1 prüfen `spec/ddl-generation-rules.md` (SQLite, Neubau/FTS5) auf eine Aussage, die „`FULLTEXT` neben Neubau" blockiert beschreibt; falls vorhanden, wird sie **vor** dem Code auf den Zielzustand gestellt. Heute gefunden: keine.
  Nur gültiger Zustand, ohne Chronik.
* **`docs/user/` (Ist-Zustand):** erst **nach** dem Code (P3), nie vorher: bis dahin gilt die bestehende Aussage („blockiert mit Exit 8") und ist richtig.

## Akzeptanzkriterien

1. P0-Tabelle ausgefüllt; Schnitt (nur `FULLTEXT`, E2 für Spatial) gegen die Messung bestätigt.
2. Fälle 1–4, 8, 10 enden mit `--execute` Exit 0; zweiter Lauf `operationsTotal` 0; FTS5-Objekte entsprechen der Datei (neu vorhanden, weg entfernt, geändert neu aufgebaut); degradierte Fälle (8, 9) mit `W132` **einmal**.
3. **Rückweg:** `schema rollback --execute` bringt den `catalog()`-Snapshot (inkl. FTS5-Tabelle und Trigger) auf den Stand vor dem Lauf.
4. Gegenproben unverändert: Fall 5 (Exit 0), Fall 6 (Exit 0), Fall 7 bleibt Exit 8 `TRANSACTION_SCOPE_UNSUPPORTED`.
5. **Rot ohne Änderung** (Renderer-Anweisungsfolge, Skript ohne Guard) belegt; Pin-Tests umgeschrieben und als solche gekennzeichnet (kein Rot-Beleg); neue Tests laufen wirklich.
6. Handbuch §3.17 und `CHANGELOG.md` nach dem Code; `make docs-check` grün; keine Chronik in `spec/` und `docs/user/`.

## Verifikation (Gates nach `CLAUDE.md`)

* `make docker-check MODULES=":adapters:driven:driver-sqlite"` und `MODULES=":hexagon:ports-read :hexagon:application"` bzw. `":adapters:driving:cli"` je nach Berührung; **einmal ohne `MODULES`** (`MODULES=` lässt `test/integration-*` aus, auch die Kompilierung; relevant, falls eine geteilte Signatur im Hexagon wechselt).
* `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"` (ohne `-PintegrationTests` läuft nichts; `make integration` setzt es).
* `make solid-suppression-gate` vor jedem Commit (Größenbefunde durch Aufteilung, nie `@Suppress`; `SqliteDiffSimpleOps`/`SqliteRebuildRenderer` sind groß).
* `make docs-check` bei jeder Änderung an `docs/`, `spec/`, ADRs; vor dem Push `make doc-immutable RANGE=origin/main..HEAD` (beim Move `consulted:`-Pfade in ADRs nicht nachziehen).

## Nicht-Scope

Neubau neben Änderung an **anderer** Tabelle, Sicht oder Trigger (#50); `Index`-Plain-Fälle (#60, geliefert); Zwei-Segment-Execute für alle Dialekte (verworfen, E1); `WITHOUT ROWID`-Semantik (#67); FTS5-Konfiguration/Tokenizer; andere Dialekte (`FULLTEXT` ist dort kein Virtual-Table-Thema).
