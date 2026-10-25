# SQLite: ein Ausdrucks-Index gilt gegen die Datenbank als geändert (Folge von #53)

> **Status:** Draft mit Scope (2026-10-25), Ursache gemessen. Befund aus der Umsetzung von
> `sqlite-eindeutiger-index-als-constraint.md` (#53, ADR 0062): für einen Ausdrucks-Index plant `schema migrate` bei
> jedem Lauf `DROP INDEX` + `CREATE INDEX` (ausführbar, Exit 0). **Vorbedingung:** keine. **Aktivierung** (Move nach
> `../in-progress/`) beim ersten Implementierungs-Commit; dann GitHub-Issue anlegen und hier verlinken.

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
`CanonicalPayload.index`, `IndexColumn.toString()` in `MigrationFingerprint`) → **Tag-Bump erwartet** (Präzedenz ADR 0062, aktuell
`schema-fingerprint-v21`, nächster freier Tag zum Aktivierungszeitpunkt; **neuer** ADR, nächste freie Nummer, ADR 0062/0063 sind
eingefroren; er korrigiert ausdrücklich die Aussage in ADR 0062, der Reader liefere `lower(a)`). Folgen benennen: Rollback-Artefakte älterer Läufe → `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`; Operations-IDs von
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
* **P3 Doku:** Handbuch (Grenze „Ausdrucks-Index" streichen: `docs/user/anwenderhandbuch.md` um Zeile 2164; nur Ist-Zustand),
  neuer `CHANGELOG.md`-Eintrag unter der laufenden Entwicklung (kein Rückgriff auf 1.10.0; im Handbuch keine Chronik); Handbuch-Hinweis zu `COLLATE`; `spec/ddl-generation-rules.md` §5.1a um die Reader-Regel „entfernt eine umschließende
  Klammer" ergänzen (Zielbild, wie bei ADR 0061; ohne ADR-Verweis); `spec/schema-reference.md`: bewusst nicht (dort steht nichts zu Reader-Texten von Index-Ausdrücken); P1 bestätigt.

## Akzeptanz

* Jede Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt (`DropIndex` + `AddIndex`);
  jede Kontrollzeile einen Test, der grün bleibt; absichtlich falsche Zusicherung einmal rot gesehen. Zweiter Lauf:
  `operationsTotal` = 0, Rückweg Exit 0.
* Eine echte Änderung des Ausdrucks (`lower(a)` → `upper(a)`) bleibt eine Operation; ein Ausdruck mit inneren Klammern wird
  nicht verstümmelt.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=`,
  `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.
