---
status: accepted
date: 2026-10-25
decision-makers: pt9912
consulted: docs/adr/0026-fingerprint-kanonisierung-post-compare.md, docs/adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md, docs/adr/0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md, docs/adr/0062-sqlite-eindeutiger-index-bleibt-index.md, docs/planning/in-progress/sqlite-ausdrucks-index-textform.md
informed: adapters/driven/driver-sqlite (SqliteIndexKeyScanner), hexagon/core (MigrationFingerprint), spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# Der SQLite-Reader entfernt die umschließende Klammer eines Ausdrucks-Index-Schlüssels — `schema-fingerprint-v22`

> **Status: accepted (2026-10-25).** Der SQLite-Reader gibt einen Ausdrucks-Schlüssel ohne die eine
> Klammer zurück, die der Generator um jeden Ausdruck setzt (im Katalog `ON t ((lower(a)))`; der Schlüssel innerhalb der Listenklammer `(lower(a))` → `lower(a)`). Der
> Reader-Ausgang jedes SQLite-Ausdrucks-Index ändert sich, der Algorithmus-Tag steigt auf `v22`.
> **Korrigiert** die Aussage in [ADR 0062](0062-sqlite-eindeutiger-index-bleibt-index.md), der Reader
> liefere `lower(a)`: er lieferte `(lower(a))`.

## Kontext und Problemstellung

Für einen Ausdrucks-Index (auch einen eindeutigen), den Datei und SQLite-Datenbank gleich ausdrücken,
plante `schema migrate` bei jedem Lauf `DROP INDEX` + `CREATE INDEX` (ausführbar, Exit 0; Issue #55,
Folge von #53). ADR 0062 hielt dies als offen fest und nahm an, der Reader liefere `lower(a)`.

Gemessen (SQLite, `SqliteSchemaReader` und die CLI gegen eine vom Generator angelegte Datenbank): der
Generator (`IndexColumn.renderKey`) klammert **jeden** Ausdruck, weil PostgreSQL und MySQL es
verlangen; für die Datei `expression: lower(a)` legt SQLite `CREATE INDEX … ((lower(a)))` an. Der
Katalog (`sqlite_master.sql`) führt den Text unverändert, der Reader gab den Schlüssel als
`(lower(a))` zurück, die Datei trägt `lower(a)`. Der Vergleich sieht zwei Ausdrücke. Ein Rundlauf
`schema reverse` → `generate` → `reverse` setzte je Zyklus eine Klammerschicht dazu.

## Betrachtete Optionen

- **A — Reader-Normalform:** der Reader entfernt genau eine Klammer, die den ganzen Schlüssel
  umschließt.
- **B — Vergleich:** Index-Ausdrücke zielbewusst falten. Größer, widerspricht der Rohtext-Faltung
  (Schlüsselausdrücke bleiben auch in `schema compare` wortgleich, ADR 0056) und greift für ein
  SQLite-Problem dialektweit ein.
- **C — Generator:** SQLite klammert nur, wo nötig. Ändert die erzeugte DDL und lässt Bestandsdatenbanken
  mit `((…))` weiter abweichen; kein Ersatz für A.

## Entscheidung

**A.** Die Klammer ist die exakte Umkehrung von `renderKey` und deshalb gefahrlos zu entfernen; die
Normalform liegt im Reader, der Vergleich bleibt wortgleich (Präzedenz ADR 0061).

1. **Ort:** `SqliteIndexKeyScanner.keysOf`, nach dem Abtragen von Kommentaren und `COLLATE`/`ASC`/`DESC`, mit dem
   Quote- und Kommentar-Skip des Scanners. Beispiele beziehen sich auf den Schlüssel innerhalb der
   Listenklammer von `CREATE INDEX … ON t (…)`: `(lower(a))` → `lower(a)`. Entfernt wird **eine** Klammer, und nur, wenn die zur
   öffnenden gehörende schließende Klammer das letzte Zeichen des Schlüssels ist. `(a) + (b)`,
   `(a + b) * 2` und `('(' || a)` bleiben unberührt (eine Klammer in einer Zeichenkette zählt nicht);
   `((x))` verliert nur die äußere Schicht.
2. **Folgen für Handangelegtes:** `ON t(lower(a))` und `ON t((lower(a)))` lesen beide `lower(a)`;
   Bestandsdatenbanken mit `((…))` werden mitrepariert. Ein handgeschriebenes `ON t((a))` meldet
   der Katalog als Spalte `a` (`PRAGMA index_xinfo` liefert einen Namen); es ist einer Spalte gleich, so gewollt und
   unverändert. Eine Datei mit `expression: a` bleibt gegen die Spalte ein Unterschied.
3. **`COLLATE`** bleibt außerhalb des Modells: `a COLLATE NOCASE` ist kein Ausdruck, der Reader lässt ein
   **nachgestelltes** `COLLATE` weg (unverändert). Steht es innerhalb der Klammer (`((a COLLATE NOCASE))`),
   bleibt es im Text; es entfällt nur die Klammer.
4. **Kommentare** (`-- …` und `/* … */`) im Schlüsseltext sind semantisch leer und können beim Wiederrendern die
   schließende Klammer verschlucken. Der Reader entfernt sie vor dem Abtragen der Klammer (Quote-/Kommentar-Skip des
   Scanners: Kommentarzeichen in Zeichenketten und gequoteten Bezeichnern bleiben); an ihrer Stelle bleibt höchstens
   ein Leerzeichen (`a /* c */ + b` → `a + b`). Damit liest `ON t((lower(a) -- c\n))` als `lower(a)`, und der Rundlauf
   bleibt stabil. **Restgrenze:** steht der Kommentar im `expression`-Text der **Datei** (`lower(a) /* c */`),
   unterscheidet sich die Datei vom kommentarfreien Reader-Ergebnis und gilt als Änderung — wie bei `COLLATE`.
5. **Fingerabdruck-Bump.** `ALGORITHM` steigt von `schema-fingerprint-v21` auf `v22`: `expr:` in
   `CanonicalPayload.index` und `IndexColumn.toString()` tragen den Reader-Ausgang. Nur Schemas mit
   SQLite-Ausdrucks-Index hashen anders (und die Operations-IDs ihrer Index-Operationen); alle anderen
   bis auf den Tag wie zuvor. Ein Artefakt vor `v22` liefert die bestehende Meldung
   `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`.

## Konsequenzen

- Ein Ausdrucks-Index, den Datei und Datenbank gleich ausdrücken, erzeugt keine Operation; `--execute`
  mit einer additiven Änderung daneben endet mit Exit 0, der zweite Lauf ist leer, der Rundlauf
  `reverse → generate → reverse` ist stabil. Eine echte Änderung des Ausdrucks (`lower(a)` →
  `upper(a)`) bleibt `DropIndex` + `AddIndex`.
- Sichtbar ändert sich `schema reverse` einer SQLite-Datenbank: ein Ausdrucks-Schlüssel steht ohne die
  äußere Klammer in der Datei.
- Eine Datei aus einem früheren `schema reverse` trägt `(lower(a))`; sie gilt gegen die Datenbank als
  geändert, bis `schema reverse` neu gezogen oder die Klammer entfernt ist.

## Weitere Informationen

Setzt [ADR 0061](0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md) (Reader-
Normalform statt Falten im Vergleich) für den Index-Ausdruck fort und führt den Fingerabdruck-Bump von
[ADR 0062](0062-sqlite-eindeutiger-index-bleibt-index.md) fort
([ADR 0026](0026-fingerprint-kanonisierung-post-compare.md): Abdruck/Post-Compare). Plan:
[`sqlite-ausdrucks-index-textform.md`](../planning/in-progress/sqlite-ausdrucks-index-textform.md).
