# SQLite: `--execute` blockiert für `unique: true` gegen einen eindeutigen Index, auch allein (Folge von #53, Issue #60)

> **Status:** Draft mit Scope (2026-10-25), Ursache am Code belegt, Messung offen. Folge der Entscheidung A aus ADR 0062
> (`sqlite-eindeutiger-index-als-constraint.md`, #53): eine Bestandsdatei mit `unique: true` an einer Spalte gegen eine Datenbank
> mit `CREATE UNIQUE INDEX` plant `AddConstraint` + `DropIndex`; `--plan-only` endet mit Exit 0, `--execute` mit Exit 8
> (`TRANSACTION_SCOPE_UNSUPPORTED`) — **auch ohne weitere Änderung**. **Vorbedingung:** keine; die Wahl zwischen den Richtungen
> trifft P1. **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit.
> GitHub-Issue: [#60](https://github.com/pt9912/d-migrate/issues/60).

## Ziel

`schema migrate --execute` gegen SQLite endet für diesen Fall nicht mit Exit 8: entweder führt der Lauf den Neubau samt
Index-Operationen aus, oder der Fall ist kein Unterschied, oder das Handbuch sagt ehrlich, dass der Fall blockiert.

## Problem

Reproduktion (Issue #60, 1.10.0): Datenbank `CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY, u INTEGER); CREATE UNIQUE INDEX ix_u ON t (u);`,
Datei `t: columns: { id: {type: integer, required: true}, u: {type: integer, unique: true} }, primary_key: [id]`.
`--plan-only`: Exit 0, `AddConstraint` + `DropIndex` („zehn Anweisungen" laut Issue, nicht nachgemessen). `--execute --report r.json`: Exit 8, Datenbank unverändert.
Gegenprobe 1.9.1: Exit 0 (der Reader faltete den Index vor ADR 0062 zu `unique`, kein Neubau). Eine Datei mit
`indices: [{ name: ix_u, columns: [u], unique: true }]` geht (0, 0).

### Ursache (am Code belegt; P0 misst es nach)

Zwei Fehler übereinander; der Guard zeigt nur den ersten und **verdeckt den zweiten**.

**Fehler 1 — Mischumfang (Exit 8).**

* [`SqliteRebuildPlanner`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt):
  `AddConstraint`/`DropConstraint` lösen den Neubau aus (`isRebuildTrigger`) und werden vom Neubau-Eimer aufgenommen
  (`isAbsorbedByRebuild`). `AddIndex`/`DropIndex` sind ausdrücklich **nicht** absorbiert (Kommentar Zeilen 654–659: „Index ops are
  NOT absorbed — they run after the rebuild"); `classify` legt sie in `simpleOps`. Festgeschrieben in
  [`SqliteRebuildPlannerTest`](../../../adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlannerTest.kt)
  („Index ops on a rebuild table stay as simple ops", Zeile 65–85).
* [`SqliteDiffRenderContext`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffRenderContext.kt)
  gibt den Neubau-Strom als `TransactionScope.STREAM_OWNED` aus; die einfachen Index-Anweisungen bleiben `RUNNER_OWNED`.
* [`MigrationStreamClassifier.unsupportedTransactionScopeReason`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/migration/MigrationStreamClassifier.kt)
  lehnt `STREAM_OWNED` neben einem anderen Scope ab; aufgerufen von
  [`SchemaMigrateRenderPipeline.applyTransactionScopeGuard`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateRenderPipeline.kt)
  nur bei `--execute` — deshalb besteht `--plan-only` mit Exit 0.

**Fehler 2 — die Anweisung selbst scheitert (verdeckt).** Die Neubau-Eimer werden zuerst gerendert, die `simpleOps` danach
(`SqliteDiffDdlGenerator`, Zeilen 96–101). `ix_u` verschwindet aber mit dem `DROP TABLE` des Neubaus; das danach gerenderte
`DROP INDEX` hat **kein `IF EXISTS`** (`SqliteDiffSimpleOps` Zeilen 249/315 über `dropIndexSql`, `SqliteDiffSqlBuilders` Zeile 207–208).
Der Strom würde also auch ohne Guard mit „no such index" scheitern. Wer nur den Guard lockert (Option d unten), tauscht Exit 8
gegen einen Laufzeitfehler mitten im Lauf. P0 führt die Anweisungsfolge ohne Guard aus und beobachtet den Fehler.

**Dasselbe Muster beim neuen Index (kein „gelöster Fall").** Ein **neuer** Index auf einer im selben Lauf neu gebauten Tabelle ist
heute **nicht** gelöst: `indexesToRecreate = target.indices` (`SqliteRebuildPlanner` Zeile 254) enthält den neuen Index bereits,
das `AddIndex` aus `simpleOps` legt ihn danach ein zweites Mal an („already exists"), und der Guard blockiert schon vorher mit
Exit 8. Für diesen Fall gibt es **keinen** bestehenden Pin-Test (P0.4 legt ihn an). Beide Richtungen — Index weg, Index neu — haben dieselbe
Wurzel: Index-Operationen auf einer Neubau-Tabelle sind weder absorbiert noch dedupliziert, obwohl der Neubau die Indizes der
Zielsicht selbst neu anlegt.

**Warum die Bestandsdatei das auslöst:** seit ADR 0062 liest der Reader einen `CREATE UNIQUE INDEX` als Index; die Datei meint
dasselbe Objekt als `column.unique`. ADR 0062 Entscheidung 3: ein Wechsel Index ↔ Constraint ist ein Tabellenneubau — gewollt.

**Handbuch und Pin-Tests auf `main`.** Die falsche Zeile in §3.17 („Mit einer weiteren Änderung daneben endet `--execute` mit
Exit `8`", Exit 8 gilt auch allein) und ein Test „Bestandsdatei … ohne weitere Aenderung endet mit Exit 8" werden getrennt auf
`main` committet (Sofortmaßnahme, nicht Teil dieses Plans). Pin-Tests
(Bestand): `CliSchemaMigrateUniqueIndexGapsSqliteTest` um Zeile 212 („unique an der Spalte gegen eindeutigen Index: --execute mit
weiterer Aenderung endet mit Exit 8", `colUnique=true, withD=true`) pinnt P0-Fall 2 (`AddConstraint` + `DropIndex` + `AddColumn`), nicht
„neuer Index neben Neubau". Der Pin-Test „ohne weitere Aenderung" (P0-Fall 1) kommt auf `main` (Commit folgt). Beide sind **grün mit dem Fehler**: sie
pinnen den Ist-Zustand. Mit dem Fix müssen sie **umgeschrieben** werden (Exit 0); sie sind kein „rot ohne Änderung"-Beleg.
`--plan-only` meldete laut Issue „zehn Anweisungen" — Angabe des Reporters, nicht nachgemessen (P0).

## Richtung (nach P0/P1 zu entscheiden)

* **(a1) Index-Operationen in den Neubau-Eimer absorbieren und deduplizieren.** `isAbsorbedByRebuild` nimmt `AddIndex` und
  `DropIndex` der **Neubau-Tabelle** auf; der Neubau legt die Indizes der Zielsicht über `indexesToRecreate` an, ein absorbiertes
  `AddIndex` erzeugt keine zweite Anweisung, ein absorbiertes `DropIndex` keine (der Index geht mit `DROP TABLE`). Es gibt dann
  keine `RUNNER_OWNED`-Anweisung neben dem Strom: Guard und Fehler 2 sind zugleich erledigt. Auswirkungen: die `operationIds` des
  Neubau-Statements tragen die Index-IDs (Report, Post-Execute-Drift-Prüfung); der **Rollback** muss aus dem Neubau-Eimer des
  Rückplans den ursprünglichen Index wiederherstellen (P0-Frage). Kippt `SqliteRebuildPlannerTest` Zeile 65 (umdrehen: Index-Ops
  sind im Eimer). Nur unter dieser Bedingung gilt: löst jeden Auslöser „Neubau + Index-Operation derselben Tabelle", auch den neuen Index.
  Der Guard bleibt für echte Mischungen (Neubau + `AddColumn` einer **anderen** Tabelle, #50).
* **Einschränkung von (a1): nur Plain-/BTREE-Indizes.** `SqliteDiffSimpleOps` (Zeilen 242–248) routet `DropIndex` für `FULLTEXT` über
  `SqliteFullTextExpansion.emitDrop` (FTS5-Virtual-Table plus drei Trigger) und bei Geometrie über
  `SqliteSpatialDiffOps.disableSpatialIndex`. `DROP TABLE` räumt eine FTS5-Table nicht weg; ein stilles Absorbieren hinterließe Waisen.
  Wiederanlegen läuft über `emitFullTextIndexRecreate`; für Spatial ist es unklar (P0). Diese Typen behalten ihren Pfad (ihr Zusammenspiel mit dem Neubau
  ist `sqlite-fulltext-rebuild-*` und bleibt dort); der Guard bleibt für sie. Die Aussage „absorbiertes `DropIndex` = keine Anweisung" gilt
  nur für Plain-/BTREE-Indizes.
* **(a2) `STREAM_OWNED`-Klammer um Neubau und Index-Operationen.** Die Index-Anweisungen werden innerhalb des Stroms als
  `STREAM_OWNED` gerendert. Löst Fehler 1, nicht Fehler 2 (`DROP INDEX` ohne `IF EXISTS` scheitert weiter, `AddIndex` doppelt) —
  nur zusammen mit `IF EXISTS`/Deduplizierung tragfähig, und dann ist (a1) einfacher. Nur als Rückfall, falls (a1) die
  `operationIds`-Zuordnung nicht trägt.
* **(b) Reader/Vergleich: `unique: true` an der Spalte gegen einen eindeutigen Index nicht als Änderung.** ADR 0062 hat die
  beidseitige Faltung (B/D) **verworfen**: ein neuer eindeutiger Index der Datei gälte als `AddConstraint`, die reine Addition
  `CREATE UNIQUE INDEX` ginge verloren; ein Netz für Bestandsdateien ist ausdrücklich „kein Teil dieser Entscheidung". Eine
  **einseitige, paarweise** Variante (nur wo Datei-`unique` und Datenbank-Index dieselben Spalten meinen) widerspräche Entscheidung 3,
  bräuchte einen Folge-ADR (ADR 0062 bleibt `accepted`; `doc-immutable`) und meldete `schema compare` „gleich", obwohl
  `schema generate` aus der Datei einen anderen Objekttyp erzeugt. **Tag:** die Faltung ändert, was Projektion/Vergleich aus Datei
  und Datenbank machen; der Abdruck (`MigrationFingerprint`, `indices = table.indices.map(canonicalizeIndex)`, Zeile 350/355) hängt am
  Schema nach Reader und Projektion — wird die Faltung im Reader/Projektion umgesetzt, ändert sich der Hash und der Tag steigt; wird
  sie nur im Vergleich umgesetzt, nicht. P1 entscheidet den Ort und belegt es gegen `MigrationFingerprint`.
* **(c) Nur Handbuch.** §3.17 nennt den Fall ehrlich: Exit 8 auch allein; Auswege (Datei mit `schema reverse` neu ziehen oder die
  Spalte als `indices: [{ unique: true }]` schreiben). Kein Code, kein Tag, kein ADR; der Anwender bleibt auf den manuellen Weg angewiesen.
* **(d) Verworfen: der Guard lässt `RUNNER_OWNED` derselben Tabelle neben `STREAM_OWNED` zu.** Löst Fehler 2 nicht: das `DROP INDEX`
  läuft nach dem Neubau und scheitert mit „no such index"; das `AddIndex` scheitert mit „already exists". Der Guard maskiert
  heute nur, was der Planer falsch erzeugt.

**Kriterien:** (1) wirkt der Fix für jeden Auslöser „Neubau + Index-Operation derselben Tabelle" (Index weg **und** neu)?
(2) ADR 0062 unangetastet? (3) **Rückweg treu**, Maßstab: `schema rollback` bringt die Datenbank in einen Stand, dessen
`catalog()`-Snapshot (wie in `CliSchemaMigrateUniqueIndexGapsSqliteTest` und den Nachbartests) dem Stand vor dem Lauf gleicht,
einschließlich Indexname und Eindeutigkeit. (4) Aufwand und Tag-Folge.

**Vorläufige Empfehlung:** (a1). (b) widerspricht ADR 0062, (c) schreibt ein Verhalten fest, das der Plan selbst als Neubau benennt,
(d) und (a2) lösen Fehler 2 nicht. (c) ist der Rückfall, falls P0 zeigt, dass der Rückweg nicht trägt. Hypothese, keine Entscheidung.

## Phasen

* **P0 Messung** (SQLite-CLI-Test; je Fall `--plan-only`, `--execute`, zweiter Lauf, Rollback; Katalog-Snapshot vorher/nachher).
  Zusätzlich je Fall die Anweisungsfolge **ohne Guard** ausführen (Fehler 2 beobachten). Mechanismus: `--plan-only --output` schreibt
  das Skript, ein Testhelfer führt es direkt per JDBC gegen die Datenbank aus; alternativ ein Renderer-Unit-Test auf der Anweisungsfolge.
  Die Akzeptanz „schlägt ohne Änderung fehl" setzt einen dieser beiden Wege voraus. Erwartung vor der Messung in Klammern;
  weicht das Ergebnis ab, ist das der Befund.

  | # | Fall | Erwartung heute |
  | - | ---- | --------------- |
  | 1 | allein: Datei `unique: true`, Datenbank `CREATE UNIQUE INDEX` | Exit 8; ohne Guard `DROP INDEX` scheitert („no such index") |
  | 2 | wie 1 plus `AddColumn` an `t` | Exit 8 (wie 1) |
  | 3 | wie 1 plus `AddColumn` an einer **anderen** Tabelle | Exit 8 durch den Neubau-Strom + Fremdanweisung (#50), unabhängig vom Index; bleibt nach dem Fix; gehört in die Klassifizierer-Tests, nicht in die Index-Matrix |
  | 4 | `AddIndex` (neuer, nicht eindeutiger Index der Datei) neben einem Neubau derselben Tabelle, Neubau-Auslöser fest: `AlterColumnNullability` einer Spalte | Exit 8; ohne Guard „already exists" (Ziel-Index steckt schon in `indexesToRecreate`) |
  | 5 | `DropIndex` eines **anderen, nicht eindeutigen** Index der Neubau-Tabelle | Exit 8; ohne Guard „no such index" |
  | 6 | Erhalt eines unveränderten Ziel-Index im Neubau, Auslöser fest: `AlterColumnNullability` (sonst kein Neubau) | `CREATE INDEX` aus `indexesToRecreate`, Index nach dem Lauf vorhanden, keine Operation im zweiten Lauf |
  | 7 | `WITHOUT ROWID`-Tabelle | wie 1; Neubau erhält `WITHOUT ROWID` (zu messen) |
  | 8 | `u` ist Quelle/Ziel eines Fremdschlüssels | wie 1; `PRAGMA foreign_keys`-Verhalten im Neubau zu messen |
  | 9 | Ausdrucks-Index (`lower(a)`, #55) auf der Neubau-Tabelle | Index bleibt in `indexesToRecreate`; wegen #55 plant der Vergleich zusätzlich Drop+Create — Erwartung: Exit 8; **streichen**, falls #55 vor P0 fehlt oder der Fall nichts Eigenes zeigt |
  | 10 | Rollback: das DOWN-Skript des Falls 1 (aus `--plan-only --output`, Rückplan) | **Ist-Zustand:** das DOWN scheitert („already exists": `AddIndex ix_u` als `simpleOp` nach dem Neubau, `indexesToRecreate` enthält `ix_u` schon). Ob das DOWN nach dem Fix den Index wiederherstellt, ist vor dem Fix **nicht messbar** und wird in P2 geprüft |
  | 11 | FULLTEXT- und Spatial-Index auf der Neubau-Tabelle (`DropIndex`/`AddIndex`) | bleiben auf ihrem Pfad (`emitDrop`/`disableSpatialIndex`); zu messen: Waisen nach dem Neubau, Verhalten des Guards |

  Außerdem: sieht die Matrix (`test/cross-dialect-matrix`) das Paar „Datei-`unique` / Datenbank-Index"? Vermutung nein.
* **P1 Entscheidung:** Richtung gegen die Kriterien. **ADR-/Tag-Bedarf:** aktueller Tag `schema-fingerprint-v21`
  (`MigrationFingerprint` Zeile 223; ADR 0062 nennt `v20`, danach stieg er mit dem Default-Vergleich). (a1) ändert nur Planer und
  Renderer; der Abdruck wird aus dem Schema nach Reader und Projektion gebildet (Zeilen 350/355; Zeile 275 ist nur `algorithm=`), nicht aus dem Plan —
  **vermutlich kein Tag-Bump**, in P1 gegen `MigrationFingerprint` und `MigrationFingerprintTest` zu bestätigen. (b) siehe dort. ADR
  nur bei (b) oder wenn (a1) eine Regel festlegt, die ADR 0062 berührt. Nummern: **zum Aktivierungszeitpunkt der nächste freie**;
  nicht vorab, die heute absehbaren sind für #55/#58/#59 vergeben.
* **P2 Umsetzung mit Tests** (für (a1); bei (c) nur Regression):
  * Planer-Unit-Test: `classify` absorbiert `AddIndex`/`DropIndex` der Neubau-Tabelle, nicht die einer anderen;
    `SqliteRebuildPlannerTest` Zeile 65 **umdrehen**.
  * Renderer-Test: genau ein `CREATE INDEX` je Ziel-Index, kein `DROP INDEX` eines mit der Tabelle verschwundenen **Plain-/BTREE**-Index; FULLTEXT/Spatial unverändert (P0.11).
  * Klassifizierer-Test: Neubau + Index derselben Tabelle ist kein gemischter Strom; Neubau + fremde Anweisung bleibt abgelehnt.
  * CLI-Tests: `--execute` **allein** Exit 0, Datenbank trägt den Constraint statt des Index; **zweiter Lauf** `operationsTotal` 0;
    **Rückweg** mit `catalog()`-Vergleich gegen den Stand vor dem Lauf; Fälle 3–6 aus P0.
  * **Pin-Tests umschreiben** (Exit 8 → Exit 0): P0-Fall 1 („ohne weitere Aenderung", von `main`) und P0-Fall 2 (Test um Zeile 212); für P0.4 ist ein neuer Test zu schreiben, kein Pin vorhanden.
  * **Rollback** (nach dem Fix): DOWN-Skript läuft, `catalog()`-Snapshot gleicht dem Stand vor dem Lauf.
  * **Matrix-Test** (nur (a1)/(b); bei (c) entfällt er): den Cross-Dialect-Matrix-Test um das Datei-/Datenbank-Paar erweitern (`--execute`, zweiter Lauf `operationsTotal`
    0); zuerst als Fixture, rot ohne die Änderung, grün mit ihr. Kann die Matrix das Paar nicht ausdrücken, ist das die Lücke, die P2 zuerst schließt.
* **P3 Doku:** Handbuch §3.17 (Absatz „Abweichende Schreibweise", nur Ist-Zustand nach der Entscheidung), `CHANGELOG.md`; `spec/`
  nur, falls eine Render-Regel betroffen ist (`spec/ddl-generation-rules.md`, SQLite-Neubau) — sonst nicht anfassen. ADR 0062
  bleibt unverändert; ein Folge-ADR verweist rückwärts.

## Nicht-Scope

Ausdrucks-Index-Textform (#55); Neubau mit abhängigen Sichten (#50); Default-Vergleich (#54); Reader-Herkunftsregel aus ADR 0062.

## Akzeptanz

**Gemeinsam:** das Handbuch sagt in keinem Zustand „nur mit einer weiteren Änderung" für diesen Fall; `make docs-check`,
`make doc-immutable RANGE=origin/main..HEAD`.

**(a1):**
* P0.1: `--execute` Exit 0, Datenbank trägt den Constraint statt des Index, zweiter Lauf `operationsTotal` 0.
* P0.2, P0.4, P0.5, P0.6: Exit 0 bzw. unverändert korrekt; Fall 3 bleibt Exit 8 (Klassifizierer-Test); FULLTEXT/Spatial (P0.11) unverändert.
* **Rollback-Treue (nach P2 messbar):** DOWN-Skript läuft, `catalog()`-Snapshot gleicht dem Stand vor dem Lauf, mit Indexname und Eindeutigkeit.
* **Fehlerzeilen, je mit Test:** (1) Exit 8 allein, (2) `DROP INDEX` nach dem Neubau scheitert, (3) `AddIndex` neben Neubau scheitert
  doppelt, (4) das DOWN scheitert heute. Neue Tests zu (2)/(3)/(4) laufen über den in P0 genannten Mechanismus (Skript per JDBC bzw.
  Renderer-Test) und schlagen ohne die Änderung fehl; absichtlich falsche Zusicherung einmal rot gesehen.
* **Ausnahme:** die Pin-Tests (Fall 1 von `main`, Fall 2 Zeile 212, `SqliteRebuildPlannerTest` Zeile 65) sind grün mit dem Fehler und
  werden umgeschrieben; sie zählen nicht als „rot ohne Änderung".
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module (einmal ohne `MODULES=`, falls eine geteilte Signatur
  wechselt), `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`.

**(b):** messbar: Datei-`unique` gegen Datenbank-Index (dieselben Spalten) liefert in `schema migrate --plan-only` **0 Operationen** und
`schema compare` „gleich"; ein Wechsel mit **anderen** Spalten, ein neuer eindeutiger Index der Datei und ein `UNIQUE`-Constraint gegen
Index (Entscheidung 3 aus ADR 0062) bleiben Änderungen, je ein Test; Folge-ADR vorhanden, Tag-Entscheidung (Ort der Faltung) belegt.

**(c):** §3.17 benennt „Exit 8 auch allein" mit den beiden Auswegen; kein Code; `make docs-check`. Kein Matrix- und kein Rollback-Test.
