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
`--plan-only`: Exit 0, `AddConstraint` + `DropIndex` (zehn Anweisungen). `--execute --report r.json`: Exit 8, Datenbank unverändert.
Gegenprobe 1.9.1: Exit 0 (der Reader faltete den Index vor ADR 0062 zu `unique`, kein Neubau). Eine Datei mit
`indices: [{ name: ix_u, columns: [u], unique: true }]` geht (0, 0).

### Ursache (am Code belegt; P0 misst es nach)

* [`SqliteRebuildPlanner`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt):
  `AddConstraint`/`DropConstraint` lösen den Tabellenneubau aus (`isRebuildTrigger`) und werden vom Neubau-Eimer aufgenommen
  (`isAbsorbedByRebuild`). `AddIndex` und `DropIndex` stehen in **keiner** der beiden Listen; `classify` legt sie in `simpleOps`.
  Der Neubau legt die Indizes der Zielsicht selbst neu an (`SqliteRebuildPlan.indexesToRecreate`, aus `newTable.indices`); die
  Datenbank-Seite `ix_u` verschwindet mit `DROP TABLE`. Das `DropIndex` ist also nach dem Neubau überflüssig — es wird trotzdem
  als eigene Anweisung gerendert.
* [`SqliteDiffRenderContext`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffRenderContext.kt)
  gibt den Neubau-Strom als `TransactionScope.STREAM_OWNED` aus (eigenes `BEGIN IMMEDIATE`/`COMMIT`); die einfache Anweisung
  `DROP INDEX` bleibt `RUNNER_OWNED`.
* [`MigrationStreamClassifier.unsupportedTransactionScopeReason`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/migration/MigrationStreamClassifier.kt)
  lehnt jeden Strom ab, in dem `STREAM_OWNED` neben einem anderen Scope steht („mixed transaction scopes are not executable as one
  migration stream"). Aufgerufen wird er von
  [`SchemaMigrateRenderPipeline.applyTransactionScopeGuard`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateRenderPipeline.kt)
  nur bei `--execute`; deshalb besteht `--plan-only`.
* **Warum die Bestandsdatei das auslöst:** seit ADR 0062 liest der Reader einen `CREATE UNIQUE INDEX` als Index; die Datei
  meint dasselbe Objekt als `column.unique` (Constraint). ADR 0062 Entscheidung 3: ein Wechsel Index ↔ Constraint ist ein
  Tabellenneubau. Der Neubau ist also gewollt; blockiert wird nur seine Ausführung wegen der **Index-Nebenoperationen**, die er
  selbst abdeckt.

Abgrenzung zum gelösten Fall: ein **neuer** eindeutiger Index auf einer Tabelle, die im selben Lauf neu gebaut wird, ist in #53
dadurch lösbar, dass der Neubau den Index über `indexesToRecreate` selbst anlegt (zu bestätigen in P0 — die Reihenfolge der
Prüfung steht im Plan #53; hier nicht angenommen). Der Fall dieses Plans ist die Umkehrung: der Index der **Datenbank** soll
verschwinden und `AddConstraint` ist der Auslöser.

Die Handbuchzeile in §3.17 („Mit einer weiteren Änderung daneben endet `--execute` mit Exit `8`") ist falsch: Exit 8 gilt auch
allein. **Sofortmaßnahme (getrennt von diesem Plan, direkt auf `main`):** den Satz korrigieren; dieser Plan ändert das Handbuch
erst in P3 für den Zustand nach der Entscheidung.

## Richtung (nach P0/P1 zu entscheiden)

* **(a) Neubau samt Index-Operationen als einen Strom ausführbar machen.** Der Klassifizierer (oder der Planer) erkennt
  „Neubau + `AddIndex`/`DropIndex` auf derselben Tabelle" als Einheit: der Planer nimmt diese Index-Operationen in den
  Neubau-Eimer auf (`isAbsorbedByRebuild`; `DropIndex` entfällt, `AddIndex` wird über `indexesToRecreate` angelegt) oder rendert sie
  `STREAM_OWNED` innerhalb der Klammer. Folgen: `operationIds` des Neubau-Statements tragen dann auch die Index-IDs
  (Post-Execute-Drift-Prüfung, Rollback-Umkehr, Report). Der Guard bleibt für echte Mischungen (Neubau + `AddColumn` auf einer
  **anderen** Tabelle, #50) unverändert. Löst das Problem für **jeden** Auslöser, nicht nur diesen. Kosten: Planer-/Renderer-Änderung,
  der Rückweg muss den Index wiederherstellen.
* **(b) Reader/Vergleich: `unique: true` an der Spalte gegen einen eindeutigen Index nicht als Änderung.** ADR 0062 hat die
  beidseitige Faltung (B/D) **verworfen**: ein neuer eindeutiger Index der Datei gälte als `AddConstraint`, und die reine Addition
  `CREATE UNIQUE INDEX` ginge verloren; ein Netz für Bestandsdateien ist ausdrücklich „kein Teil dieser Entscheidung". Eine
  **einseitige, paarweise** Variante (nur wo Datei-`unique` und Datenbank-Index dieselben Spalten meinen, kein Wechsel) wäre enger,
  widerspräche aber Entscheidung 3 („ein Wechsel ist eine Änderung") und bräuchte einen Folge-ADR, der ADR 0062 ergänzt (Status
  von 0062 bleibt `accepted`; `doc-immutable`). Außerdem ändert sie Semantik: die Datenbank behielte den Index, die Datei fordert
  einen Constraint — `schema compare` meldete „gleich", obwohl `schema generate` aus der Datei einen anderen Objekttyp erzeugt.
* **(c) Nur Handbuch.** §3.17 nennt den Fall ehrlich: Exit 8 auch allein; Auswege (Datei mit `schema reverse` neu ziehen oder die
  Spalte als `indices: [{ unique: true }]` schreiben). Kein Code, kein Tag, kein ADR. Der Anwender bleibt auf den manuellen Weg
  angewiesen; der Plan benennt den Neubau, der Lauf verweigert ihn.

**Kriterien:** (1) Wirkt der Fix für jeden Auslöser „Neubau + Index-Operation auf derselben Tabelle", nicht nur diesen Fall?
(2) Bleibt ADR 0062 unangetastet? (3) Bleibt der Rückweg (`schema rollback`) treu? (4) Aufwand und Tag-Folge.

**Vorläufige Empfehlung:** (a), weil (b) ADR 0062 widerspricht und eine falsche „gleich"-Aussage erzeugt, und (c) ein
blockierendes Verhalten festschreibt, das der Plan selbst als Neubau benennt. (c) ist der Rückfall, falls P0 zeigt, dass der Rückweg
(Rollback der Index-Wiederherstellung) nicht tragfähig ist. Die Empfehlung ist eine Hypothese; P0 und P1 entscheiden.

## Phasen

* **P0 Messung** (SQLite-CLI-Test bzw. Integrationstest; je Fall `--plan-only`, `--execute`, zweiter Lauf, Rollback):
  1. **allein:** der Fall des Issues (PK + `u` mit Index);
  2. **mit weiterer Änderung:** zusätzlich `AddColumn` an `t` und an einer anderen Tabelle (trennt „Neubau + Index" von „Neubau +
     fremde Tabelle", #50);
  3. **Ausdrucks-Index** (`lower(a)`, #55) auf derselben Tabelle: bleibt er erhalten, wird er neu angelegt?;
  4. **`WITHOUT ROWID`-Tabelle;**
  5. **FK-Spalte** (`u` ist Ziel oder Quelle einer Fremdschlüsselbeziehung): `PRAGMA foreign_keys`-Verhalten im Neubau;
  6. **Rollback:** Rückweg des Neubaus (Index wiederherstellen?), `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` nicht verwechseln.
  Je Fall: Exit-Code, Anweisungsliste, Scope der Anweisungen, Zustand der Datenbank danach, Operationszahl im zweiten Lauf.
  Außerdem prüfen, ob **die Matrix** (`test/cross-dialect-matrix`) den Fall überhaupt sieht: die Fixtures decken eine Quelle ohne
  `CREATE UNIQUE INDEX`-Bestand ab, der Gegenstand ist ein Datei-/Datenbank-Paar mit Herkunftsunterschied — wahrscheinlich nicht.
* **P1 Entscheidung:** Richtung (a)/(b)/(c) gegen die Kriterien. **ADR-/Tag-Bedarf:** (a) ändert Planer/Renderer, nicht den Reader-
  Ausgang und die Projektion; vermutlich **kein** Tag-Bump, sofern der Hash nur vom Reader-Ausgang und der Projektion abhängt (in P1
  gegen `MigrationFingerprint` bestätigen). (b) bräuchte Folge-ADR und Tag. Nummern: **zum Aktivierungszeitpunkt der nächste freie**
  ADR und Tag — nicht vorab festlegen; die heute absehbaren Nummern sind für #55/#58/#59 vergeben.
* **P2 Umsetzung mit Tests** (für (a); bei (c) entfällt P2 bis auf die Regression):
  * Planer-Unit-Test: `classify` legt `AddIndex`/`DropIndex` derselben Tabelle in den Neubau-Eimer, die einer anderen Tabelle nicht.
  * Klassifizierer-Test: Neubau + Index auf derselben Tabelle ist kein gemischter Strom; Neubau + fremde Anweisung bleibt abgelehnt
    (`MigrationStreamClassifierTest` bleibt grün).
  * CLI-Tests (SQLite): `--execute` **allein** → Exit 0, Datenbank enthält `UNIQUE`-Constraint statt Index; **zweiter Lauf**
    `operationsTotal` 0; **Rückweg** (`schema rollback`) stellt den Index wieder her.
  * **Matrix-Test:** den Cross-Dialect-Matrix-Test um ein Datei-/Datenbank-Paar „`unique: true` gegen `CREATE UNIQUE INDEX`" mit
    `--execute`-Ergebnis und zweitem Lauf `operationsTotal` 0 erweitern; zuerst als Fixture, rot ohne die Änderung, grün mit ihr. Zeigt
    P0, dass die Matrix den Fall nicht ausdrücken kann, ist das die Lücke, die P2 zuerst schließt.
* **P3 Doku:** Handbuch §3.17 (Absatz „Abweichende Schreibweise", nur Ist-Zustand nach der Entscheidung), `CHANGELOG.md`; `spec/`
  nur, falls eine Render-Regel betroffen ist (`spec/ddl-generation-rules.md`, SQLite-Neubau) — sonst nicht anfassen. ADR 0062
  bleibt unverändert; ein Folge-ADR verweist rückwärts.

## Nicht-Scope

Ausdrucks-Index-Textform (#55); Neubau mit abhängigen Sichten (#50); Default-Vergleich (#54); Reader-Herkunftsregel aus ADR 0062.

## Akzeptanz

* Der Fall des Issues (P0.1) endet mit der gewählten Richtung messbar: (a) `--execute` Exit 0, Datenbank trägt den Constraint, zweiter
  Lauf `operationsTotal` 0, Rollback stellt den Index her; (c) Exit 8 ist im Handbuch benannt, mit Auswegen.
* Jede gemessene Fehlerzeile aus P0 hat einen Test, der ohne die Änderung fehlschlägt; absichtlich falsche Zusicherung einmal rot gesehen.
* Das Handbuch sagt in keinem Zustand „nur mit einer weiteren Änderung" für diesen Fall.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module (einmal ohne `MODULES=`, falls eine geteilte Signatur wechselt),
  `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.
