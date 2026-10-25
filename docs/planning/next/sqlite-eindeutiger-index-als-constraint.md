# SQLite: ein eindeutiger Index gilt gegen die Datenbank als geändert (Issue #53)

> **Status:** Draft mit Scope (2026-10-25), nach Review-Runde 2. Aus Issue #53: `schema migrate`
> gegen SQLite plant für einen unveränderten eindeutigen Index `DropConstraint` + `AddIndex`;
> `--execute` blockiert mit `TRANSACTION_SCOPE_UNSUPPORTED` (Exit 8). Gleiche Fehlerklasse wie
> #51/#52 (ein Objekt, das beide Seiten gleich meinen, gilt als geändert), anderer Ort.
> Schwesterpläne: `sqlite-default-vergleich.md` und `sqlite-neubau-cast-gleiche-speicherklasse.md` (beide #54;
> teilen sich Fingerabdruck-Tag und ADR-Nummer). **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim
> ersten Implementierungs-Commit.

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
* **P1 Entscheidung:** A, B oder C nach der Messung; die Liste der umzuschreibenden Reader-Tests/Goldens;
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
  §5.1 (Zeilen um 1090: „eindeutiger Ausdrucks-Index bleibt Index mit `unique`“) für SQLite um den Wortlaut der
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
* Ein Ausdrucks-Index bleibt ein Index (Ist-Zustand der Spec, §5.1); „Unique an einer Indexspalte
  entfernen“ ist in der Datenbank (`sqlite_master`) geprüft, nicht nur als Operation.
* Die bewusst umgeschriebenen Reader-Tests/Goldens sind in P1 aufgelistet und im Commit begründet.
* Bei geänderten gemeinsamen Hilfsfunktionen: Regressionstest für mindestens einen weiteren Dialekt.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"` (bei gemeinsamen
  Hilfsfunktionen alle betroffenen Integrationsmodule), `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.
