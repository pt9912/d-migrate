---
status: accepted
date: 2026-10-25
decision-makers: pt9912
consulted: docs/adr/0026-fingerprint-kanonisierung-post-compare.md, docs/adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md, docs/adr/0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md, docs/planning/in-progress/sqlite-eindeutiger-index-als-constraint.md
informed: adapters/driven/driver-sqlite (SqliteSchemaReader, SqliteMetadataQueries), hexagon/core (MigrationFingerprint), spec/ddl-generation-rules.md, spec/schema-reference.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# Ein eindeutiger Index bleibt auf SQLite ein Index — der Reader unterscheidet `CREATE UNIQUE INDEX` von `UNIQUE`-Constraint, `schema-fingerprint-v20`

> **Status: accepted (2026-10-25).** Der SQLite-Reader liest nur einen
> `UNIQUE`-Constraint (`PRAGMA index_list`, Ursprung `u`) als `unique` an der Spalte
> bzw. als Constraint; ein `CREATE UNIQUE INDEX` (Ursprung `c`) bleibt ein Index mit
> `unique: true`. Das ändert den Reader-Ausgang und damit den Abdruck jeder SQLite-
> Datenbank mit eindeutigem Index; der Algorithmus-Tag steigt auf `v20`.

## Kontext und Problemstellung

Eine Datei mit `indices: [{ unique: true }]` und eine SQLite-Datenbank, die aus ihr entstanden
ist, planten `DropConstraint` + `AddIndex` (Issue #53). Kam eine weitere additive Änderung dazu,
endete `schema migrate --execute` mit Exit 8 (`TRANSACTION_SCOPE_UNSUPPORTED`): auf SQLite ist
`DropConstraint` ein Tabellenneubau, und Neubau und Nicht-Neubau liegen dann in einem Strom. Ein
**neu angelegter** eindeutiger Index endete mit Exit 5 (`POST_EXECUTE_DRIFT`), obwohl die Änderung
ein einzelnes `CREATE UNIQUE INDEX` war.

Gemessen (Ursache): `SqliteSchemaReader` hob **jeden** eindeutigen Index ohne Prädikat auf
`column.unique` (eine Spalte) bzw. auf einen Constraint unter dem Indexnamen (mehrere Spalten) und
warf ihn aus `indices`. Die Datei beschreibt denselben Index als Index. Zwei weitere Folgen derselben
Faltung: ein eindeutiger Index auf einer Primärschlüsselspalte und ein eindeutiger Ausdrucks-Index
gingen im Reverse ganz verloren (`unique` an einer Primärschlüsselspalte wird nicht geführt, ein
Ausdruck ist keine Spalte).

## Betrachtete Optionen

- **A — Reader: Herkunft beachten.** Nur Ursprung `u` wird Constraint/`column.unique`.
- **B/D — Vergleich/Abdruck falten.** Die Datei (oder beide Seiten) werden für SQLite so
  normalisiert, dass ein eindeutiger Index wie `column.unique` zählt.
- **C — A plus B/D als Netz für Bestandsdateien.**

## Entscheidung

**A.** Die Herkunft ist im Katalog erkennbar (`origin`), der Fehler sitzt im Reader: er faltet zwei
verschiedene Objekte in eins. B/D verschöben die Faltung in den Vergleich und machten sie
beidseitig: ein **neuer** eindeutiger Index in der Datei gälte dann als `AddConstraint`, was SQLite
nur als Tabellenneubau kann — der Exit-8-Weg, der vermieden werden soll, und die reine Addition
`CREATE UNIQUE INDEX` ginge verloren. C scheidet aus demselben Grund aus: wo A und B trotzdem eine
Operation ergeben, bleibt nur der Neubau. Index und Constraint lassen sich in SQLite nicht ineinander
umbauen (kein `ALTER TABLE ADD/DROP CONSTRAINT`), sie sind verschiedene Objekte.

1. **Reader.** `listIndices` liefert jeden `CREATE [UNIQUE] INDEX` unverändert (Spalten, Richtung,
   Prädikat, Ausdrücke); `column.unique` und der mehrspaltige Constraint kommen allein aus den
   Autoindizes mit Ursprung `u` (`listUniqueConstraintIndexes`). Die gemeinsamen
   `SchemaReaderUtils` sind unverändert; MySQL, SQL Server und Oracle lesen weiter wie bisher.
2. **Vergleich und Abdruck** ändern sich nicht; sie sehen jetzt auf beiden Seiten einen Index.
3. **Ein Wechsel zwischen Index und Constraint ist eine Änderung.** Ein `UNIQUE`-Constraint in der
   Datei gegen einen eindeutigen Index in der Datenbank (und umgekehrt) plant einen Tabellenneubau
   — das ist die treue Abbildung zweier verschiedener SQLite-Objekte.
4. **Fingerabdruck-Bump.** `ALGORITHM` steigt von `schema-fingerprint-v19` auf `v20`: dieselbe
   SQLite-Datenbank mit eindeutigem Index trägt `indices` statt `unique`/Constraint. Schemas ohne
   eindeutigen Index und die anderen Dialekte hashen bis auf den Tag wie zuvor. Ein Artefakt vor
   `v20` liefert die bestehende Meldung `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`.

## Konsequenzen

- `schema migrate --execute` führt eine rein additive Änderung neben einem unveränderten
  eindeutigen Index aus; ein neu angelegter eindeutiger Index ist ein einzelnes
  `CREATE UNIQUE INDEX` und endet ohne Drift.
- Sichtbar ändert sich `schema reverse` einer SQLite-Datenbank: ein eindeutiger Index steht unter
  `indices`, nicht als `unique: true` an der Spalte; Primärschlüsselspalte und Ausdruck gehen nicht
  mehr verloren.
- **Bestandsdateien.** Eine Datei aus einem früheren `schema reverse` einer Datenbank mit
  `CREATE UNIQUE INDEX` trägt `unique: true` an der Spalte (bzw. einen Constraint). Derselbe Stand
  liest jetzt als Index; der Vergleich plant `AddConstraint` (Neubau) + `DropIndex`. Das ist ein
  neues Exit-8-Risiko genau für diese Anwender: mit einer weiteren Änderung daneben endet `--execute` mit Exit 8
  (`TRANSACTION_SCOPE_UNSUPPORTED`, die Datenbank bleibt unverändert). Dokumentierte Grenze (Handbuch, CHANGELOG). Ein
  Netz (Vergleich, der `column.unique` gegen einen eindeutigen Index gleichsetzt) bräuchte die
  beidseitige Faltung, die oben verworfen ist; es ist kein Teil dieser Entscheidung.
- Weiter offen: der Text eines Ausdrucks-Index (`PRAGMA`/`sqlite_master` führt `(lower(a))`, die
  Datei `lower(a)`) gilt im Vergleich als verschieden; das betrifft den Ausdruck, nicht die
  Eindeutigkeit, und ist hier nicht entschieden.

## Weitere Informationen

Ergänzt [ADR 0057](0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md) (ein Objekt, das
beide Seiten gleich meinen, ist kein Unterschied) um den Reader-Fall und führt den
Fingerabdruck-Bump von [ADR 0061](0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md)
fort ([ADR 0026](0026-fingerprint-kanonisierung-post-compare.md): Abdruck/Post-Compare). Plan:
[`sqlite-eindeutiger-index-als-constraint.md`](../planning/in-progress/sqlite-eindeutiger-index-als-constraint.md).
