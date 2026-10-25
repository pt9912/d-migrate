# SQLite: ein eindeutiger Index gilt gegen die Datenbank als geändert (Issue #53)

> **Status:** In Arbeit (2026-10-25; Draft mit Scope nach Review-Runde 3, Entscheidung A, ADR 0062). Aus Issue #53: `schema migrate`
> gegen SQLite plant für einen unveränderten eindeutigen Index `DropConstraint` + `AddIndex`;
> `--execute` blockiert mit `TRANSACTION_SCOPE_UNSUPPORTED` (Exit 8). Gleiche Fehlerklasse wie
> #51/#52 (ein Objekt, das beide Seiten gleich meinen, gilt als geändert), anderer Ort.
> Schwesterpläne: `sqlite-default-vergleich.md` und `sqlite-neubau-cast-gleiche-speicherklasse.md` (beide #54;
> teilen sich Fingerabdruck-Tag und ADR-Nummer). **Vorbedingung:** keine. Aktiviert mit dem ersten Implementierungs-Commit.

## Ziel

Ein eindeutiger Index (`indices: [{ unique: true }]`), den Datei und SQLite-Datenbank gleich
ausdrücken, erzeugt keine Operation. `schema migrate --execute` führt dann eine rein additive Änderung
aus, statt mit Exit 8 zu blockieren.

## Problem

Nachgestellt mit dem SQLite-CLI-Test (`schema generate` → Datenbank → `schema migrate --plan-only`
mit **demselben** Schema):

| Index in Datei | Operationen gegen die erzeugte Datenbank |
| --- | --- |
| eindeutig, **eine** Spalte, ohne Prädikat | `DropConstraint ["t","_unique_j"]`, `AddIndex ["t","ix_t"]` |
| eindeutig, **zwei** Spalten, ohne Prädikat | `DropConstraint ["t","ix_t"]`, `AddIndex ["t","ix_t"]` |
| eindeutig mit Prädikat (`where`) | keine |
| nicht eindeutig | keine |
| Spalte mit `unique: true` (kein Index) | keine |

Der Reporter nennt als Kontrolle PostgreSQL 18.6: derselbe Fall geht dort (Plan, Ausführung, Rückführung
Exit 0); nicht von uns nachgemessen.

**Warum Exit 8.** Allein sind `DropConstraint` + `AddIndex` ausführbar (der Plan, `--plan-only`, endet mit
Exit 0). Auf SQLite ist `DropConstraint` ein Tabellenneubau (`STREAM_OWNED`); kommt eine weitere
Nicht-Neubau-Operation dazu (`AddColumn`), liegen zwei Umfänge in einem Strom, was
`MigrationStreamClassifier.unsupportedTransactionScopeReason` ablehnt (`TRANSACTION_SCOPE_UNSUPPORTED`;
derselbe Mischumfang wie in #50).

### Ursache (am Code bestätigt, in P0 mit der Messung zu belegen)

* **Eine Spalte:** [`SqliteSchemaReader`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt)
  faltet jeden eindeutigen Index ohne Prädikat über
  [`SchemaReaderUtils.singleColumnUniqueFromIndices`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/metadata/SchemaReaderUtils.kt)
  auf `column.unique` (Zeilen 255–256) und wirft ihn aus `indices` (Filter 366–370). Der Vergleich
  sieht links `column.unique`, rechts nicht → `DropConstraint` mit dem synthetischen Namen `_unique_j`
  (`TableComparator.syntheticUniqueConstraint`); der Index der Datei fehlt links → `AddIndex`.
* **Mehrere Spalten:** `buildMultiColumnUniqueFromIndices` (Zeile 307) macht den Index unter dem
  **Indexnamen** zum Constraint → `DropConstraint ["t","ix_t"]`.
* Die Autoindizes aus einem `UNIQUE`-Constraint (`PRAGMA index_list`-Ursprung `u`) liefert
  `SqliteMetadataQueries.listUniqueConstraintIndexes`; `listIndices` lässt Ursprung `c` (ein
  `CREATE UNIQUE INDEX`) durch. Die Reader-Faltung unterscheidet die beiden Herkünfte nicht.
* Die anderen Reader (MySQL, MSSQL, Oracle) rufen dieselben `SchemaReaderUtils`-Funktionen
  (`MysqlSchemaReader`, `MssqlSchemaReader`, `OracleSchemaReader`); eine Änderung dort wirkt auf sie.

## Richtung (nach P0 zu entscheiden)

* **A — Reader: Herkunft beachten.** Nur ein Autoindex mit Ursprung `u` wird Constraint/`column.unique`;
  ein Index mit Ursprung `c` bleibt ein Index. Löst die Ursache, wirkt in `schema reverse` und
  `schema compare` (Reverse-Vertrag). **Kosten:** kippt festgeschriebene Reader-Tests
  (`SqliteSchemaReaderTest` „single-column UNIQUE is lifted to ColumnDefinition.unique“ nimmt ausdrücklich
  `CREATE UNIQUE INDEX idx_email` als Fall, weitere Tests und Goldens mit eindeutigem Index — Liste in P1)
  und ändert die Auslegung in den Kommentaren von `listIndices` und `SqliteSchemaReader`
  („UNIQUE-Autoindex ist Constraint“). **Bestandsdateien:** eine Datei aus einem früheren
  `schema reverse` einer Datenbank mit `CREATE UNIQUE INDEX` trägt `unique: true` an der Spalte oder einen
  Constraint; nach A liest derselbe Stand als Index, der Vergleich plant `AddConstraint` (Neubau) +
  `DropIndex` — ein neues Exit-8-Risiko genau für Bestandsanwender. Das ist die eigentliche
  Abwägung gegen C.
* **B — Vergleich über die zielbewusste Projektion.** Ein eindeutiger Index ohne Prädikat und ohne Ausdruck
  und ein `UNIQUE`-Constraint/`column.unique` über dieselben Spalten gelten als gleich. Der Vergleich kennt
  schon einen Haken: `TargetProjection.index` projiziert Indizes zielbewusst (`TableIndexComparator.projectIndex`),
  und der Abdruck „nutzt dieselbe Projektion“. B ist deshalb wahrscheinlich ein Eintrag in der Projektion des
  SQLite-Ziels (ADR 0057: „ein Name, den ein Server nicht führt, ist kein Unterschied“), kein Umbau von drei
  Stellen — in P1 zu prüfen. **Kosten:** der Index-Vertrag steckt in drei Projektionen (Vergleich,
  `MigrationFingerprint.foldConstraints` faltet einen einspaltigen Constraint auf `column.unique`, einen
  eindeutigen Index nicht, `CanonicalPayload`); wirkt die Faltung nur im Vergleich, driftet der Post-Compare
  nach `--execute` und der `schema rollback`-Check. B ändert den Abdruck-Vertrag (Tag-Bump). Gate nur SQLite
  (die anderen Reader laufen über dieselben Hilfsfunktionen).
* **D — Datei-Seite falten.** Die Datei wird für SQLite normalisiert: ein eindeutiger Index ohne Prädikat und
  Ausdruck zählt wie `column.unique`/Constraint. Die Normalform liegt im Vergleich und im Abdruck, der Reader
  und die Bestandsdateien bleiben unberührt; die Normalform der Datei im Vergleich ändert sich. Praktisch B mit
  anderer Rechtsrichtung — Entscheidung in P1.
* **E — Reader liefert Index und Markierung.** Der Reader behält den Index **und** vermerkt die Eindeutigkeit
  (`column.unique` ohne Faltung): verworfen als Zwischenform — Bestandsdateien mit `unique: true` planten
  weiter `AddConstraint` + `DropIndex`, A bliebe ohne Netz.
* **C — beides:** A für die Herkunft, B (oder D) als Netz für Bestandsdateien. **Widerspruch, den P1 auflösen
  muss:** SQLite kann Index und Constraint nicht ineinander umbauen (kein `ALTER TABLE ADD/DROP CONSTRAINT`);
  wo A und B trotzdem eine Operation ergeben, bleibt nur der Tabellenneubau — genau der Exit-8-Weg, der
  vermieden werden soll. C lohnt nur, wenn B die Operation dort ganz entfallen lässt.

**Echte Änderungen müssen ausführbar bleiben.** Wird ein Index als `column.unique` gelesen und die Datei
streicht die Eindeutigkeit, entsteht `DropConstraint` auf etwas, das in SQLite ein Index ist; der Neubau
löscht den Index nur zufällig mit. Das Ergebnis in der Datenbank ist zu prüfen, nicht nur die Operation
(P0/P2: „Unique an einer Indexspalte entfernen“, `sqlite_master` danach).

Nicht-Ziel: `UNIQUE`-Constraints, die in der Datenbank als Constraint stehen und in der Datei als
Constraint (laufen heute).

## Phasen

* **P0 Messung** (SQLite-CLI-Test + Reader-Unit-Test; Muster `CliSchemaMigrateViewRebuildSqliteTest`):
  feste Fallliste, je Zeile (Datei, Reader-Ausgabe, Operationen, `--execute`-Exit mit weiterer additiver
  Änderung):
  eindeutiger Index über 1/2/3 Spalten; mit `where`; absteigend/`COLLATE`; Ausdrucks-Index; `unique: true` an
  der Spalte; Tabellen-`UNIQUE (a, b)`; **Datei Constraint ↔ Datenbank Index** und umgekehrt; benannter
  Constraint; Index auf einer PK-Spalte; Spalten-`unique` **plus** Index auf derselben Spalte; Index auf einer
  Fremdschlüsselspalte; `WITHOUT ROWID`; Spaltennamen in anderer Groß-/Kleinschreibung; Indexname mit
  `sqlite_autoindex_`-Präfix; Round-Trip `schema reverse` → `schema generate` → neue Datenbank → Vergleich;
  Index auf einer Tabelle, die im selben Lauf neu gebaut wird (kein doppelter Eindeutigkeitsschutz). Dazu
  `PRAGMA index_list` (Ursprung `c`/`u`/`pk`). P0 endet, wenn jede Zeile eine feste Zusicherung hat; neue
  Fälle gehen in einen Folgeplan.
* **P1 Entscheidung:** A, B, C oder D nach der Messung; die Liste der umzuschreibenden Reader-Tests/Goldens;
  die Aussage zu Bestandsdateien; **Fingerabdruck-Tag:** der Reader-Ausgang ändert sich bei A und C (Bump
  praktisch sicher), B ändert die Projektion (Bump); die Nummer vergibt der zuerst gemergte der Schwesterpläne
  #53/#54 (`v20`), der andere nimmt `v21`; jeder führt seinen Absatz am `ALGORITHM`. **Ein ADR ist Pflicht**
  (Reader-Normalform und Abdruck ändern sich, wie bei #52/ADR 0061); die Nummer vergibt der zuerst gemergte der
  Pläne #53/#54 (`0062`), der nächste nimmt `0063` — oder ein gemeinsamer ADR, wenn beide denselben Abdruck-Tag
  anfassen; Bezüge:
  [ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md) (ein Name, den ein
  Server nicht führt, ist kein Unterschied) und
  [ADR 0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md) (Abdruck/Post-Compare); akzeptierte
  ADRs bleiben im Kern unverändert (`doc-immutable`).
* **P2 Umsetzung** nach P1. Die Spec-Regel ändert sich **mit** dem Code, nicht danach: `spec/ddl-generation-rules.md`
  §5.1a „Ausdrucks-Schlüssel“ (Zeilen 1085–1095: „eindeutiger Ausdrucks-Index bleibt Index mit `unique`“) für SQLite um den Wortlaut der
  neuen Regel ergänzen (z. B. „nur Ursprung `u` wird Constraint“), `spec/schema-reference.md` (Zeilen um 69 und
  198: `unique` an der Spalte gegen `unique` am Index). Tests: Reader, Comparator, Fingerabdruck; SQLite-CLI
  (`operationsTotal` 0 für jede unveränderte Zeile, `--execute` mit additiver Änderung Exit 0, zweiter Lauf 0,
  Rückweg `schema rollback` ohne Drift); echte Änderung (anderer Index, andere Spalten, Unique entfernt/neu)
  bleibt eine Operation.
* **Teilung:** wählt P1 A oder C, darf das „Bestandsdateien-Netz“ (B/D) als Folgeplan abgespalten werden;
  A allein wäre dann ohne Netz nur mit ausdrücklicher Handbuch-Aussage zu Bestandsdateien zu liefern.
* **P3 Doku:** Anwenderhandbuch (SQLite-Indizes, nur Ist-Zustand), `CHANGELOG.md`; Bestandsdateien-Hinweis,
  falls A gewählt wird.

## Nicht-Scope

* `UNIQUE`-Constraints auf anderen Dialekten — dort **nicht gemessen** (MySQL kennt keinen Unterschied zwischen
  Index und Constraint, MSSQL trägt `clustered`/`includeColumns`); Verhalten dort darf sich nicht ändern — Regressionstest, wenn
  gemeinsame `SchemaReaderUtils` angefasst werden).
* Der Neubau selbst (Reihenfolge, Temp-Namen) — nur dass er nicht ausgelöst wird.

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der ohne die Änderung an der erwarteten Stelle
  fehlschlägt (`DropConstraint`/`AddIndex`; mit weiterer additiver Änderung bei `--execute` zusätzlich Exit 8
  `TRANSACTION_SCOPE_UNSUPPORTED`); die neuen Tests werden einmal mit absichtlich falscher Zusicherung rot
  gesehen.
* Jede unveränderte Zeile: `operationsTotal` 0, `--execute` Exit 0, zweiter Lauf 0, `schema rollback` ohne
  Drift. Eine echte Änderung eines eindeutigen Index bleibt eine Operation.
* Ein Ausdrucks-Index bleibt ein Index (Ist-Zustand der Spec, §5.1a); „Unique an einer Indexspalte
  entfernen“ ist in der Datenbank (`sqlite_master`) geprüft, nicht nur als Operation.
* Die bewusst umgeschriebenen Reader-Tests/Goldens sind in P1 aufgelistet und im Commit begründet.
* Bei geänderten gemeinsamen Hilfsfunktionen: Regressionstest für mindestens einen weiteren Dialekt.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"` (bei gemeinsamen
  Hilfsfunktionen alle betroffenen Integrationsmodule), `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.

## Nachtrag P0/P1/P2 (2026-10-25)

**P0 (Messung, ungefixt; SQLite-CLI-Test mit `schema generate` → Datenbank → `schema reverse` /
`schema migrate --plan-only` / `--execute` mit einer zusätzlichen Spalte `d`).** Tabelle `t(id, a, b, c)`,
`ix_t` der eindeutige Index; „Ops“ = `operationsTotal` gegen die Datenbank, „+d“ = `--execute` der Datei
mit der zusätzlichen Spalte:

| Fall | Reader vorher | Ops vorher | +d vorher | nachher (Ops / +d) |
| --- | --- | --- | --- | --- |
| eindeutig, 1 Spalte | `a.unique`, Index fehlt | 2 (Neubau + `CREATE UNIQUE INDEX`) | Exit 8 `TRANSACTION_SCOPE_UNSUPPORTED` | 0 / Exit 0 |
| eindeutig, 2 und 3 Spalten | Constraint `ix_t` | 2 | Exit 8 | 0 / Exit 0 |
| eindeutig mit `where` | Index mit `where` | 0 | Exit 0 | 0 / Exit 0 |
| eindeutig, absteigend | `a.unique`, Richtung verloren | 2 | Exit 8 | 0 / Exit 0 |
| nicht eindeutig; Spalte `unique: true`; Tabellen-`UNIQUE (a, b)` benannt; Tabellen-`UNIQUE (a)` | wie die Datei | 0 | Exit 0 | 0 / Exit 0 |
| eindeutig auf Primärschlüsselspalte | Index **fehlt** | 1 (`AddIndex`) | Exit 5 | 0 / Exit 0 |
| `unique: true` **plus** Index auf derselben Spalte | `a.unique`, Index fehlt | 1 (`AddIndex`) | Exit 5 | 0 / Exit 0 |
| eindeutig auf Fremdschlüsselspalte; `WITHOUT ROWID` | `a.unique` | 2 | Exit 8 | 0 / Exit 0 |
| eindeutiger Ausdrucks-Index (`lower(a)`) | Index **fehlt** | 1 (`AddIndex`) | Exit 5 | 2 (Text `(lower(a))` gegen `lower(a)`), Exit 0 — offen, siehe unten |
| `COLLATE NOCASE` in der Datenbank | `a.unique` (Collation geht verloren) | 2 | Exit 8 | Index; nicht nachgemessen |
| **neuer** eindeutiger Index (Datenbank ohne) | — | 1 (`CREATE UNIQUE INDEX`) | Exit 5 `POST_EXECUTE_DRIFT` | 1 / Exit 0, danach 0 |
| Unique entfernt / Spalten geändert | `a.unique` | 2 (Neubau) | — | 2 (`DROP INDEX` + `CREATE INDEX`); `sqlite_master` ohne `UNIQUE` bzw. Index auf `b` |
| eindeutigen Index streichen | `a.unique` | 1 | Exit 0 | 1 (`DROP INDEX`) |
| eindeutiger Index auf Tabelle, die im selben Lauf neu gebaut wird (`c` optional) | `a.unique` | 3 | Exit 8 | Neubau + ein `CREATE UNIQUE INDEX`, Exit 0 |
| Datei Constraint `(a)` / `(a, b)` ↔ Datenbank Index | gleich gefaltet | 0 | Exit 0 | 2 — **bewusste Änderung** (zwei Objekte) |
| Datei Index ↔ Datenbank Constraint | verschieden | 2 | — | 2 |
| Bestandsdatei `unique: true` an der Spalte ↔ Datenbank Index | gleich gefaltet | 0 | Exit 0 | 2 — **Grenze** (Bestandsdateien) |
| Round-Trip `reverse` → `generate` → neue Datenbank → Vergleich mit der Ausgangsdatei | — | 2 | — | 0 |
| `sqlite_autoindex_`-Präfix am Indexnamen | von SQLite abgelehnt (`object name reserved for internal use`) | — | — | nicht erzeugbar, kein Fall |
| Spaltennamen anderer Schreibweise (`A`, `B`) | Datei scheitert an der Validierung (Exit 3) | — | — | kein Reader-Thema |

`PRAGMA index_list`: `CREATE UNIQUE INDEX` → `origin = c`, Inline-/Tabellen-`UNIQUE` → `u`, Primärschlüssel
bei `WITHOUT ROWID` → `pk` (alle drei `unique = 1`). Zwei weitere Folgen derselben Faltung, die der Plan nicht
kannte: ein eindeutiger Index auf einer Primärschlüsselspalte und ein eindeutiger Ausdrucks-Index gingen im
Reverse ganz verloren (der alte Filter in `indexDefinitions`), und ein **neu angelegter** eindeutiger Index endete
mit `POST_EXECUTE_DRIFT`.

**P1 (Entscheidung): A.** Die Herkunft steht im Katalog (`origin`), der Fehler sitzt im Reader. B/D falteten
beidseitig im Vergleich: ein neuer eindeutiger Index in der Datei wäre ein `AddConstraint` und damit ein
Tabellenneubau statt eines `CREATE UNIQUE INDEX` — der Exit-8-Weg bliebe, die reine Addition ginge verloren. C
scheidet aus demselben Grund aus (der Widerspruch im Abschnitt „Richtung“: wo A und B eine Operation ergeben,
bleibt der Neubau); E ist verworfen. Die Faltung im Vergleich ändert sich nicht, die gemeinsamen
`SchemaReaderUtils` bleiben unverändert (MySQL, SQL Server, Oracle unberührt, deshalb kein Regressionstest für
einen weiteren Dialekt nötig). **Bestandsdateien:** eine Datei mit `unique: true` an einer Spalte, deren
Datenbank einen `CREATE UNIQUE INDEX` führt, plant jetzt einen Neubau (Constraint + `DropIndex`); das Netz
(Vergleich) wird nicht gebaut und ist in Handbuch und CHANGELOG als Grenze geführt. Fingerabdruck
**`schema-fingerprint-v20`**, Entscheidung **ADR 0062**.

Umgeschriebene Tests: `SqliteSchemaReaderTest` „single-column UNIQUE is lifted…“ (der Fall `CREATE UNIQUE INDEX
idx_email` erwartet jetzt einen Index; der Lift gilt dem Inline-`UNIQUE`); neu `SqliteSchemaReaderUniqueIndexTest`
(Index bleibt Index: eine und drei Spalten, Richtung, Primärschlüsselspalte); die `v19`-Literale in
`MigrationFingerprintTest`, `RefTypeVocabularyTest`, `IndexPredicateCanonicalisationTest` und
`MigrationPlanArtifactBuilderTest`. Goldens: keine umgeschrieben (kein Golden trägt einen eindeutigen SQLite-Index
im Reader-Pfad).

**P2:** `CliSchemaMigrateUniqueIndexSqliteTest` (25 Fälle: unveränderte Zeilen mit `--execute` + zweitem Lauf,
neuer Index, Unique entfernt/nachgetragen, andere Spalten, gestrichen, Neubau im selben Lauf, Index ↔ Constraint,
Bestandsdatei, Round-Trip, `schema rollback`). **Rot ohne den Fix** (Reader-Änderung zurückgenommen): 18 der 25
CLI-Fälle (u. a. Plan mit 2 statt 0 Operationen, `+d` mit Exit 8, neuer Index mit Exit 5, Round-Trip, Rollback)
und 4 der neuen Reader-Tests; mit dem Fix grün. Spec: `spec/ddl-generation-rules.md` §5.3 (Zurücklesen auf SQLite),
`spec/schema-reference.md` (`unique` an Spalte gegen Index).

**Offen / abgetrennt:** (1) Der Text eines Ausdrucks-Index (`(lower(a))` aus dem Katalog gegen `lower(a)` aus der
Datei) gilt als Unterschied (`DROP INDEX` + `CREATE INDEX`, ausführbar); das betrifft den Ausdruck, nicht die
Eindeutigkeit, und ist hier nicht behandelt. (2) Das Netz für Bestandsdateien ist nicht gebaut. (3) Die
`COLLATE`-Angabe eines Index kennt das Modell nicht. (4) *Behoben:* der Testdaten-Seeder (`TableRowSeeder`) hält eine Spalte jetzt auch eindeutig, wenn sie nur über
einen einspaltigen `UNIQUE`-Constraint oder einen einspaltigen eindeutigen Index (ohne Prädikat/Ausdruck) eindeutig ist;
mehrspaltige Schlüssel bleiben Nicht-Scope. (5) *Behoben:* ein nicht auflösbarer Ausdrucks-Index fällt nicht mehr still
weg, der Reader meldet ihn als Notiz `R206` (`WARNING`).
(6) `--execute` für die Bestandsdateien-Zeile endet mit Exit 8 (Review gemessen); Test dafür steht in der Nachbesserung.

**Test-Nachtrag (Lücken aus dem Review, nur Tests).**
* Ausdrucks-Index: Reader-Test (`unique = true`, kein `column.unique`) und CLI-Test, der die Grenze festhält (2 Operationen bei jedem Lauf, `--execute` Exit 0); der Reader liefert hier `lower(a)` ohne Klammern, der Text-Unterschied liegt also nicht im Reader (Folge-Slice `sqlite-ausdrucks-index-textform.md` zu prüfen).
* `--execute` mit zusätzlicher Spalte für Fremdschlüsselspalte, `WITHOUT ROWID`, Spalte `unique` plus Index und Spalte `unique` ohne Index: Exit 0, zweiter Lauf 0 Operationen.
* Bestandsdatei mit `--execute` und weiterer Änderung: Exit 8, `sqlite_master` vorher und nachher gleich.
* `schema rollback` auch für den Ein-Spalten-Index und den Index auf der Primärschlüsselspalte.
* Reader: Index mit `where`, zwei eindeutige Indizes, Gegenprobe Tabellen-`UNIQUE (a, b)` bleibt Constraint, `WITHOUT ROWID`.
* Duplikat-Insert nach `--execute` wird abgelehnt (bestehender und neu angelegter Ein-Spalten-Index).
