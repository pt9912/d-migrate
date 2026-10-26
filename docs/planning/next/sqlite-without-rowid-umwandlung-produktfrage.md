# SQLite: ist die Umwandlung Rowid → `WITHOUT ROWID` beim Neubau gewollt? (Produktfrage zu Issue #67)

> **Status:** Entwurf mit Scope (2026-10-26, Review-Runde 1 eingearbeitet). **Vorbedingung:** **Eigner-Entscheidung**, danach Doku bzw. Code; keine stille Festlegung.
> **Reihenfolge:** dieser Slice entscheidet **zuerst**; er ist der einzige, der den Hinweg-Satz in Handbuch §3.17 und Spec §3.5 ändert. Der technische Schwesterslice
> [`sqlite-rollback-without-rowid-vorher.md`](sqlite-rollback-without-rowid-vorher.md) ändert dort nur den Rückweg, damit die beiden nicht um dieselbe Zeile kollidieren.
> Beim Move nach `../in-progress/` die relativen `../in-progress/`-Links auf `./` umstellen. Dabei auch die Rückverweise im Schwesterslice und den Nachtrag im #60-Slice (`sqlite-without-rowid-im-neubau.md`, `../next/` → `./`) nachziehen; nur Planning-Dateien, kein ADR-`consulted:`. GitHub-Issue: [#67](https://github.com/pt9912/d-migrate/issues/67)
> (Vorschlag 1 Alternative und Vorschlag 2).

## Ziel

Eine ausdrückliche Antwort, ob `schema migrate --execute` eine bestehende Rowid-Tabelle bei einem Neubau aus anderem Anlass zur `WITHOUT ROWID`-Tabelle machen darf,
wenn die Datei `metadata: { without_rowid: true }` trägt — und Handbuch/Spec, die Hin- und Rückweg widerspruchsfrei beschreiben.

## Problem

Handbuch §3.17 (`docs/user/anwenderhandbuch.md`, Absatz „`WITHOUT ROWID`") sagt zugleich: ein Neubau erhält das Merkmal, „wenn die Datenbank **oder die Datei** die Tabelle so führt"
(Fall A: Umwandlung hin) **und** „`schema migrate` plant das Merkmal selbst nicht: es ändert eine bestehende Tabelle weder hinzu noch weg". Beides zugleich stimmt für Fall A nicht.
Die Regel stammt aus [`sqlite-without-rowid-im-neubau.md`](../in-progress/sqlite-without-rowid-im-neubau.md) P1 (Datei oder Datenbank, weil eine Bestandsdatei „fehlt" und „false"
nicht unterscheidet; dort als Folge in Kauf genommen). `spec/ddl-generation-rules.md` §3.5 trägt denselben Satz.

## Optionen (Eigner wählt; Plan-Empfehlung: B als Minimum; Kosten: A nur Doku, B Code + Diagnosecode, C Code + ein Unit-Test angepasst, kein CLI-Test bricht)

* **A — gewollt:** Umwandlung beim Neubau bleibt. §3.17 schränkt „weder hinzu noch weg" ein („außer als Nebenwirkung eines Neubaus, wenn die Datei `without_rowid: true` trägt")
  und nennt den Rückweg (nach dem Schwesterslice: stellt Rowid wieder her; sowie, falls dort Option c gewählt wird, dass die Kopfwerte das Merkmal nicht einschließen).
  Spec §3.5 und `spec/neutral-model-spec.md` (`without_rowid`-Zeile) mitziehen.
* **B — Block oder Warnung bei Abweichung:** hat die Datei `without_rowid: true`, die Datenbank-Tabelle ist eine Rowid-Tabelle und ein Neubau steht an, blockiert der Lauf
  oder warnt mit Code. Eine Datei **ohne** Angabe (Bestandsdatei, C2) ist keine Abweichung. Code in `SqliteRebuildPlanner` (Plan-Zeit), Diagnosecode im Katalog,
  Handbuch/Troubleshooting.
* **C — Datenbank gewinnt immer:** Up nimmt das Merkmal nur aus der Datenbank; eine Datei mit `true` gegen eine Rowid-Tabelle wandelt beim Neubau nie um.
  **Option C bricht keinen bestehenden CLI-Test:** alle Tests in `CliSchemaMigrateWithoutRowidSqliteTest` mit „Datei mit Merkmal" gehen von `databaseWithoutRowid(dir)` aus
  (Fall C1, Datenbank `WITHOUT ROWID`) und blieben grün. **Fall A (Datenbank Rowid, Datei `true`, Neubau) hatte bisher keinen Test** — deshalb rutschte #67 durch; er kommt
  neu hinzu. Anzupassen wäre nur der Unit-Test `SqliteRebuildPlanWithoutRowidTest` „Merkmal der Datei zaehlt, wenn die Datenbank es nicht fuehrt", der das ODER pinnt.
  Neue Tabellen (`CreateTable`) bleiben unberührt. Fall A wird im Hinweg ein Nullbefund; der Rollback-Fix des Schwesterslice bleibt für Down-Artefakte früherer Versionen.

## Arbeitspakete

* **P0 Entscheidung** des Eigners (A/B/C) mit Datum im Kopf; ob ein ADR nötig ist (B/C ändern das Verhalten von `schema migrate`; ein Fingerabdruck-Bump des Schwesterslice braucht wahrscheinlich einen **eigenen** ADR).
* **P1 Doku:** Handbuch §3.17 und Spec §3.5 widerspruchsfrei, `CHANGELOG.md`. Bei A sofort; bei B/C `docs/user/` erst **nach** dem Code (Ist-Zustand), die Spec darf vorher.
* **P2 Code (nur B/C):** Datenstruktur aus P1 des Schwesterslice nutzen (Datenbank-Seite, Datei-Seite, Richtung) statt `planRebuild` erneut umzubauen.

## Akzeptanzkriterien

1. Entscheid steht im Slice; §3.17 und Spec §3.5 widersprechen sich und dem Verhalten für Fall A nicht mehr.
2. Tests für **alle vier Fälle** A, B, C1, C2 (Datenbank Rowid/`WITHOUT ROWID` × Datei mit/ohne Merkmal, Änderung `unique: true`) **je gewählter Option**, jeweils mit
   `--plan-only` **und** `--execute` (Exit, Diagnose, `sqlite_master.sql`).
3. Eigener Fall **E** (= E im Schwesterslice): **Datei `true`, Datenbank Rowid, kein Neubau** (z. B. nur neue nullable Spalte): `--plan-only` 0 Operationen für das Merkmal, `--execute` lässt die Tabelle Rowid —
   bei jeder Option (nur B warnt hier nicht, da kein Neubau).
4. C2 unverändert: keine Warnung, kein Block, Tabelle bleibt `WITHOUT ROWID`.
5. Bei B/C: Test für Fall A rot ohne die Änderung belegt.

## Verifikation

`make docs-check`; bei Code `make docker-check MODULES=":adapters:driven:driver-sqlite"` und `":adapters:driving:cli"`, einmal ohne `MODULES`, `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`,
`make solid-suppression-gate`, vor dem Push `make doc-immutable RANGE=origin/main..HEAD`.

## Nicht-Scope

Der technische Rollback-Fix und die Nachprüfung (Schwesterslice); `WITHOUT ROWID` per `schema migrate` gezielt hinzufügen/entfernen (eigener Slice, Neubau mit Strukturänderung).
