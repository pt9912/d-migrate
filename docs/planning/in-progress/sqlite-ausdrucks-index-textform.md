# SQLite: ein Ausdrucks-Index gilt gegen die Datenbank als geändert (Folge von #53)

> **Status:** In Umsetzung (2026-10-25), Ursache gemessen, Entscheidung A (ADR 0064). Befund aus der Umsetzung von
> `sqlite-eindeutiger-index-als-constraint.md` (#53, ADR 0062): für einen Ausdrucks-Index plant `schema migrate` bei
> jedem Lauf `DROP INDEX` + `CREATE INDEX` (ausführbar, Exit 0). **Vorbedingung:** keine. Aktiviert mit dem ersten
> Implementierungs-Commit. GitHub-Issue: [#55](https://github.com/pt9912/d-migrate/issues/55).

## Ziel

Ein Ausdrucks-Index (auch ein eindeutiger), den Datei und SQLite-Datenbank gleich ausdrücken, erzeugt keine Operation.

## Problem

Gemessen (SQLite, `SqliteSchemaReader` auf einer In-Memory-Datenbank):

| `CREATE INDEX …` | Reader (`IndexColumn.expression`) |
| --- | --- |
| `ON t ((lower(a)))` | `(lower(a))` |
| `ON t (lower(a))` | `lower(a)` |
| `UNIQUE … (lower(a), b)` | `lower(a)` + Spalte `b` |
| `ON t (a, (b + 1) DESC)` | `a` + `(b + 1)`, `DESC` |
| `ON t (a COLLATE NOCASE)` | `a` (`COLLATE` entfällt) |

### Ursache

[`IndexColumn.renderKey`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/IndexKeyRendering.kt) schreibt
**jeden** Ausdruck in Klammern (`"($it)"`), weil PostgreSQL und MySQL sie verlangen. Der SQLite-Generator legt für die Datei
`expression: lower(a)` also `CREATE INDEX … ((lower(a)))` an; der Reader gibt den Schlüssel so zurück, wie er im Katalog
steht, `(lower(a))` ([`SqliteIndexKeyScanner`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteIndexKeyScanner.kt));
die Datei trägt `lower(a)`. Der Vergleich sieht zwei Ausdrücke. Dieselbe Klasse wie beim Domain-`CHECK` auf PostgreSQL
(ADR 0061): die Darstellung unterscheidet sich, der Index nicht.

(Der Fall, dass sich ein Schlüssel nicht auflösen lässt, ist mit `R206` gemeldet und nicht Teil dieses Plans.)

Ein Rundlauf `schema reverse` → `generate` → `reverse` setzt heute je Zyklus eine Klammerschicht dazu; A macht ihn stabil. Der
Katalogtext ist mit `sqlite3` geprüft (`((a)+(b))`, `(lower(a))`, `('(' || a)` stehen unverändert in `sqlite_master.sql`).

## Richtung (A erwartet, P1 bestätigt)

* **A — Reader-Normalform:** der Reader entfernt **eine** überflüssige äußere Klammer um einen Index-Ausdruck, wenn sie den
  ganzen Ausdruck umschließt (`(lower(a))` → `lower(a)`; `(a) + (b)` bleibt), und kann das dank der Generator-Regel
  (`renderKey` klammert immer) gefahrlos tun. Normalform des **Readers**, kein Falten im Vergleich (ADR 0056 unberührt,
  Präzedenz ADR 0061). Wirkt in `schema reverse`/`schema compare` (Reverse-Vertrag).
  **Ort:** nicht `SqliteSchemaReader.indexDefinitions` (reicht nur durch), sondern
  `SqliteIndexKeyScanner.keysOf` nach `stripKeyModifiers` (Reihenfolge: erst Modifier `COLLATE`/`DESC`, dann Klammer), mit dem
  Quote-/Kommentar-Skip `skipNonCode` des Scanners — sonst entfernt ein Tiefenzähler bei `('(' || a)` falsch. A ist die exakte
  Umkehrung von `renderKey`: genau **eine** umschließende Klammer; Datei `(lower(a))` → Schlüsseltext im Katalog (den `keysOf` sieht, innerhalb der Listenklammer) `((lower(a)))` → Reader `(lower(a))`.
  Von Hand angelegte `ON t(lower(a))` und `ON t((lower(a)))` werden beide `lower(a)`; Bestandsdatenbanken mit `((…))` werden mitrepariert.
* **B — Vergleich:** Index-Ausdrücke zielbewusst falten (`TargetProjection.index`, Abdruck-Vertrag) — größer; widerspricht dem
  Kommentar in `RawTextFolding` (Schlüsselausdrücke bleiben auch in `schema compare` wortgleich) und ADR 0056; dialektweiter Eingriff
  für ein SQLite-Problem. Die bestehende Rohtext-Faltung (`RawTextFolding.index`, `RawTextProvenance.INDEX_KEY_EXPRESSION`) bleibt von A unberührt.
* **C — Generator:** SQLite schreibt keine zusätzlichen Klammern, wo sie nicht nötig sind (ein bloßer Funktionsaufruf):
  ändert die erzeugte DDL und lässt Bestandsdatenbanken (mit `((…))`) weiter abweichen — kein Ersatz für A.

**P0-Fallmenge (festgelegt), je Fall Reader-Ausgabe und Operationen gegen die Generator-Datenbank:**
Fehlerzeilen: `((lower(a)))`, `UNIQUE (lower(a), b)`, `(a, (b + 1) DESC)`. Kontrollzeilen (dürfen nicht kippen): `lower(a)` ohne
Klammer, handgeschriebenes `ON t((a))` (wird zu `a`, einer Spalte gleich — bewusst festhalten), `(a) + (b)`, `(a + b) * 2`, `('(' || a)`, Ausdruck + `COLLATE`/`DESC`, Prädikat (`WHERE`), mehrere Ausdrücke, Reihenfolge der
Schlüssel. P0 endet, wenn jede Zeile Reader-Ausgabe und Operationen trägt.

**Erwartung Fingerabdruck:** A ändert den Reader-Ausgang jedes SQLite-Ausdrucks-Index, der Abdruck trägt ihn (`expr:` in
`CanonicalPayload.index`, `IndexColumn.toString()` in `MigrationFingerprint`) → **Tag-Bump erwartet** (Präzedenz ADR 0062; entschieden in P1:
`schema-fingerprint-v22`, **neuer** ADR 0064; ADR 0062/0063 sind eingefroren; er korrigiert ausdrücklich die Aussage in ADR 0062, der Reader liefere `lower(a)`). Folgen benennen: Rollback-Artefakte älterer Läufe → `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`; Operations-IDs von
Index-Ops mit Ausdruck ändern sich. Nur Schemas mit SQLite-Ausdrucks-Index sind betroffen.

Nicht-Ziel: `COLLATE` im Modell (die Spalte `a COLLATE NOCASE` ist kein Ausdruck; der Reader lässt `COLLATE` weg — Hinweis im
Handbuch, kein eigener Folge-Plan); Ausdrucks-Indizes auf anderen Dialekten.

## Phasen

* **P0 Messung** (Reader-Unit-Test + SQLite-CLI-Test; Fallliste oben): je Fall Reader-Ausgabe, Operationen gegen die vom
  Generator erzeugte Datenbank, `--execute`, zweiter Lauf. P0 endet, wenn die Tabelle voll ist.
* **P1 Entscheidung:** A (erwartet) oder B; Tag und ADR wie oben (bei A erwartet, nicht bedingt). Der Tag wird mit den
  PostgreSQL-Domain-Plänen koordiniert (siehe dort), je Plan der nächste freie.
* **P2 Umsetzung:** Unit-Tests (Reader), SQLite-CLI-Tests (Plan-Only `operationsTotal` 0, `--execute` Exit 0, zweiter Lauf 0,
  Rückweg, echte Ausdrucksänderung bleibt eine Operation). Bestehende Tests erweitern:
  `SqliteIndexKeyScannerTest` (Randfälle), `SqliteExpressionIndexReverseIntegrationTest` (deckt bisher nur `ON t(LOWER(nm))`, nicht die
  Generator-Form `((…))`). Die vorhandenen Fixtures mit `expression:` (`F.5/positive/desired.yaml`, `e-commerce.yaml`, `full-featured.yaml`) sind CHECK-Ausdrücke,
  keine Index-Schlüssel und hier belanglos; braucht es eine Matrix-/Format-Fixture, wird eine neue mit Index-`expression` angelegt.
  SQLite-CLI-Tests: Datei und Modul in P0 benennen (`test/integration-sqlite`).
* **P2 Matrix:** den Cross-Dialect-Matrix-Test (`test/cross-dialect-matrix`, Fixtures je Fall mit Soll-Ergebnis) um die Fälle der P0-Fallmenge erweitern (Fehler- und Kontrollzeilen, Rundlauf `reverse → generate → reverse` stabil). Neue Fixture mit Index-`expression` anlegen — die vorhandenen `expression:`-Fixtures sind CHECK-Ausdrücke erweitern; Fälle zuerst als Fixture, rot ohne die Änderung, grün mit ihr.
* **P3 Doku:** Handbuch (Grenze „Ausdrucks-Index" streichen: `docs/user/anwenderhandbuch.md` um Zeile 2164; nur Ist-Zustand),
  neuer `CHANGELOG.md`-Eintrag unter der laufenden Entwicklung (kein Rückgriff auf 1.10.0; im Handbuch keine Chronik); Handbuch-Hinweis zu `COLLATE`; `spec/ddl-generation-rules.md` §5.1a um die Reader-Regel „entfernt eine umschließende
  Klammer" ergänzen (Zielbild, wie bei ADR 0061; ohne ADR-Verweis); `spec/schema-reference.md`: bewusst nicht (dort steht nichts zu Reader-Texten von Index-Ausdrücken); P1 bestätigt.

## P0 — Messung (2026-10-25)

Gemessen mit dem CLI-Test
[`CliSchemaMigrateExpressionIndexSqliteTest`](../../../adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaMigrateExpressionIndexSqliteTest.kt)
(`adapters/driving/cli`; die Datenbank entsteht aus `schema generate`, dann `schema migrate` mit derselben Datei) vor der
Änderung: **jede** vom Generator angelegte Datenbank mit Ausdrucks-Index plant 2 Operationen (`DropIndex` + `AddIndex`)
statt 0 — nicht nur die Fehlerzeilen, auch die Kontrollfälle, weil der Generator jeden Ausdruck klammert. Die Annahme des
Plans stimmt also, aber breiter als angenommen. Von Hand angelegt (Katalogtext wie geschrieben, Datei trägt den Ausdruck
ohne Klammer), kippt nur die Form mit zusätzlicher Klammer.

| Fall | Reader vor der Änderung | Operationen vor | Reader danach | Operationen danach |
| --- | --- | --- | --- | --- |
| Datei `lower(a)` (Generator: `((lower(a)))`) | `(lower(a))` | 2 | `lower(a)` | 0 |
| `UNIQUE`, `lower(a)` + Spalte `b` | `(lower(a))` + `b` | 2 | `lower(a)` + `b` | 0 |
| Spalte `a` + `b + 1 DESC` | `a` + `(b + 1)`, `DESC` | 2 | `a` + `b + 1`, `DESC` | 0 |
| `(a) \|\| (b)` (Klammer gehört zum Ausdruck) | `((a) \|\| (b))` | 2 | `(a) \|\| (b)` | 0 |
| `(a \|\| b) \|\| 'x'` | `((a \|\| b) \|\| 'x')` | 2 | `(a \|\| b) \|\| 'x'` | 0 |
| `'(' \|\| a` (Klammer in der Zeichenkette) | `('(' \|\| a)` | 2 | `'(' \|\| a` | 0 |
| zwei Ausdrücke; Ausdruck, Spalte, Ausdruck (Reihenfolge) | je Schlüssel eine Klammer | 2 | ohne | 0 |
| Prädikat `WHERE` | `(lower(a))` | 2 | `lower(a)` | 0 |
| Datei `expression: a` (Generator: `((a))`) | Spalte `a` (der Katalog meldet den Namen) | 2 | Spalte `a` | 2 (bleibt: Ausdruck gegen Spalte) |
| von Hand `ON t(lower(a))` | `lower(a)` | 0 | `lower(a)` | 0 |
| von Hand `ON t((lower(a)))` | `(lower(a))` | 2 | `lower(a)` | 0 |
| von Hand `ON t((a) \|\| (b))`, `((a + 1) * 2)`, `('(' \|\| a)` | wie geschrieben | 0 | unverändert | 0 |
| von Hand `ON t(lower(a) COLLATE NOCASE)` | `lower(a)` | 0 | `lower(a)` | 0 |
| von Hand `ON t((a))`, Datei `name: a` | Spalte `a` | 0 | Spalte `a` (einer Spalte gleich, bewusst) | 0 |

Der Rundlauf `schema reverse` → `generate` → `reverse` war vorher nicht stabil (je Zyklus eine Klammerschicht mehr) und ist
es danach. `lower(a)` → `upper(a)` bleibt 2 Operationen (`DropIndex` + `AddIndex`), `(a) || (b)` → `(a) || (a)` ebenso.

**Rot ohne die Änderung (gemessen, Review-Runde 1):** mit abgeschalteter Klammer-Entfernung in `keysOf` sind rot:
`SqliteIndexKeyScannerTest` 8 Tests, `CliSchemaMigrateExpressionIndexSqliteTest` 23 Tests,
`CliSchemaMigrateUniqueIndexGapsSqliteTest` 1 Test, `ExpressionIndexRoundTripTest` 1 Test; mit der Änderung alle grün.
Kommentare im Schlüsseltext (`((lower(a) -- c\n))`) entfernt der Reader vor dem Abtragen der Klammer; gemessen vor
dieser Regel: Klammer blieb stehen, 2 Operationen, Rundlauf wuchs je Zyklus. Restgrenze: ein Kommentar im
`expression`-Text der Datei gilt als Änderung (2 Operationen), und ein `--`-Kommentar dort erzeugt ungültiges DDL
(eigener Befund: der Generator klammert wortgleich, der Kommentar verschluckt die schließende Klammer). `COLLATE`
innerhalb der Klammer bleibt im Text.

## P1 — Entscheidung (2026-10-25)

**A**, wie erwartet: die Messung zeigt eine einzige Ursache (die Klammer des Generators) und keinen Fall, in dem das
Entfernen einer umschließenden Klammer einen Ausdruck verändert (Klammern im Ausdruck und in Zeichenketten bleiben). B
bräuchte eine dialektweite Faltung für ein SQLite-Problem, C ließe Bestandsdatenbanken mit `((…))` abweichen.
Fingerabdruck **`schema-fingerprint-v22`**, Entscheidung **ADR 0064** (korrigiert die Aussage in ADR 0062, der Reader liefere
`lower(a)`). Die Matrix erhält den Arbeitsstrang `expression-index` (Fixture mit allen Fällen, im Sweep file-mode in jedem
Dialekt gerendert; der Rundlauf gegen SQLite steht in `ExpressionIndexRoundTripTest`, rot ohne die Änderung, weil der
Sweep selbst nur Dateien vergleicht und den Reader nicht sieht).

## Akzeptanz

* Jede Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt (`DropIndex` + `AddIndex`);
  jede Kontrollzeile einen Test, der grün bleibt; absichtlich falsche Zusicherung einmal rot gesehen. Zweiter Lauf:
  `operationsTotal` = 0, Rückweg Exit 0.
* Eine echte Änderung des Ausdrucks (`lower(a)` → `upper(a)`) bleibt eine Operation; ein Ausdruck mit inneren Klammern wird
  nicht verstümmelt.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=`,
  `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.
