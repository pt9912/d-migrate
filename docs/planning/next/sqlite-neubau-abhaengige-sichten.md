# SQLite: Tabellen-Neubau mit abhängigen Sichten (Issue #50)

> **Status:** Draft mit Scope (2026-10-24), nach einem Review. Aus dem Befund bei
> #48 (Stand `7a492031a`): ein Neubau, an dem eine Sicht hängt, scheitert in
> `schema migrate --execute`.
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim
> ersten Implementierungs-Commit.

## Ziel

`schema migrate --execute` gegen eine SQLite-Datenbank baut eine Tabelle auch dann
neu auf, wenn Sichten von ihr abhängen — gleich, ob das Schema die Abhängigkeit
der Sicht deklariert oder nicht — und der Lauf endet danach ohne Fehler und ohne
Drift.

## Problem

Zwei Messungen mit einem CLI-Test gegen eine echte SQLite-Datei (Tabelle `t`,
Sicht `v_t` = `SELECT id, n FROM t`; Änderung: zusätzlicher CHECK an `t`, der einen
Neubau erzwingt). Der erste Fall besteht schon auf dem Stand `1b8a2cce4`, vor den
Änderungen zu #47–#49.

| Sicht im Schema | `schema migrate --execute` |
| --- | --- |
| **ohne** `dependencies` | Exit `8`, Blocker `TRANSACTION_SCOPE_UNSUPPORTED` (Operationen `AddConstraint` und `ReplaceView`); die Datenbank bleibt unverändert |
| **mit** `dependencies: { tables: [t] }` | Exit `5`, `status: failed`: **Ausführungsfehler** `error in view v_t: no such table: main.t` beim Neubau, Rollback bestätigt (`FULL_ROLLBACK_CONFIRMED`), die Datenbank bleibt unverändert |

Beide Male ändert sich nichts an der Datenbank; der Anwender kommt aber nicht ans Ziel.

### Gemeinsame Wurzel

Der Neubau ([`SqliteRebuildRenderer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt))
löscht abhängige Sichten **vor** `DROP TABLE` und legt sie **nach** dem Umbenennen
wieder an — aus den Listen `dependentViewsToDrop` und `dependentViewsToRecreate`. Welche
Sichten dazugehören, entscheidet allein `view.dependencies.tables`
([`SqliteRebuildPlanner.collectDependentViews`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt),
`absorbingRebuildTableFor`, `viewOrTriggerTableRefs`). Der
[`SqliteSchemaReader`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt)
setzt `dependencies` an keiner Sicht (er baut `ViewDefinition(query, sourceDialect)`);
weder Normalisierer noch Vergleich noch Planner leiten sie für SQLite ab.

**Fall 1 (ohne `dependencies`).** Die Sicht gilt als unabhängig. Ihr `ReplaceView`
fällt nicht in den Neubau-Eimer und bleibt ein einfacher Vorgang im Runner
(`RUNNER_OWNED`). Der Neubau selbst verwaltet seine Transaktion (`STREAM_OWNED`);
der Executor lehnt `STREAM_OWNED` neben einem anderen Umfang ab
([`MigrationStreamClassifier.unsupportedTransactionScopeReason`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/migration/MigrationStreamClassifier.kt)).

**Fall 2 (mit `dependencies`).** Die **Asymmetrie** der Quellen: der Neubau plant mit
`sourceSchema = diff.currentSchema`, dem aus der Datenbank gelesenen Schema — dort
tragen die Sichten nie `dependencies`, also ist `dependentViewsToDrop` **leer**.
`dependentViewsToRecreate` kommt aus dem Soll-Schema, das die Deklaration trägt, und
ist **gefüllt**. Gemessen: im Statement-Strom fehlt das `DROP VIEW` vor `DROP TABLE`,
das `CREATE VIEW` steht danach; `ALTER TABLE … RENAME` scheitert an der verwaisten
Sicht. (Selbst wenn dieser Schritt durchginge, scheitert das `CREATE VIEW` am
Namen, den es noch gibt.)

Ein Fingerabdruck-Drift scheidet als Ursache aus: `MigrationFingerprint.appendViews`
schreibt `materialized`, `refresh`, `query` und `source_dialect`, **nicht**
`dependencies`; `SchemaComparator.compareView` vergleicht sie ebenfalls nicht.

### Warum überhaupt ein `ReplaceView` für eine unveränderte Sicht?

`ReplaceView` entsteht nur aus `diff.viewsChanged`. Fehlende `dependencies` scheiden
als Grund aus (sie werden nicht verglichen). Es bleiben `query`, `columns`,
`materialized` und `refresh`. Wahrscheinlichster Kandidat ist ein Textunterschied
zwischen Soll und Ist: der Reader schneidet den Sichttext per `extractViewQuery` bei
`" AS "` ab (`SqliteTypeMapping`), das ist anfällig für `AS` in Spaltenaliassen,
Zeilenumbrüche und Semikolons. Aus dem Code allein nicht entscheidbar; P0.1 misst
es.

## Richtung (vorläufig, nach P0 zu bestätigen)

Zu leisten ist eine **Tabellenbezug-Ableitung aus dem Sichttext**, die an drei Stellen
wirkt: bei der Drop-Liste (aus dem Ist-Schema), bei der Create-Liste (aus dem
Soll-Schema) und in `viewOrTriggerTableRefs` (damit `CreateView`/`DropView`/`ReplaceView`
in den Eimer fallen). Sie muss **transitiv** sein (eine Sicht über eine Sicht über
`t`), denn `collectDependentViews` kennt heute `dependencies.views` nicht.

**A′ — Ableitung im SQLite-Planner** (vorläufige Neigung). Der Planner bestimmt die
Bezüge aus `ViewDefinition.query`; Reader, Schema-Modell und Vertrag bleiben
unverändert, ein ADR ist voraussichtlich nicht nötig. Eine Überschätzung ist harmlos
(eine Sicht wird unnötig gelöscht und neu angelegt), eine Unterschätzung ist der
Fehler. Für den Text-Scan gibt es schon einen Bezeichner-Scanner
(`SqliteIdentifierTokenScanner`, heute für Sequenz-Marker in
[`SqliteSequenceMarkerParser`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSequenceMarkerParser.kt))
und den Quoting-bewussten [`SqliteDdlScanning`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlScanning.kt);
ob einer für Sichttexte taugt, ist zu prüfen.

**A — Ableitung im Reader.** Der Reader setzt `dependencies`. Ändert den
Reader-Vertrag (ADR), und die Soll-Seite bräuchte dieselbe Ableitung. Mehr Aufwand als
A′ für dasselbe Ergebnis.

**B — Alle Sichten um jeden Neubau.** Der Neubau löscht und legt jede Sicht an,
gleich ob sie die Tabelle erwähnt. Einfach, robust, breiter als nötig.

**C — Der Executor trennt `STREAM_OWNED` von anderen Umfängen.** Der Executor trennt
heute schon Umfänge (`segmentForExecute` spaltet nach `NO_TRANSACTION` gegen den Rest);
`STREAM_OWNED` ist ausgeschlossen, weil der Strom eigene `BEGIN`/`COMMIT` trägt und ein
Fremdsegment in oder außerhalb einer Transaktion läge, die der Runner nicht kontrolliert
(`ExecutableSegments`, `MigrationStreamClassifier`). C löst nur die Sperre in Fall 1,
nicht Fall 2, und ist die größte Änderung.

**D — Abhängige Sichten aus dem Live-Katalog.** Die Neubau-Planung zieht die Sichten
aus `sqlite_master` (`SqliteCatalogSnapshot.fromLiveCatalog`, im Up-Pfad schon
vorhanden). Präzise für die Drop-Seite, ohne Text-Parsing — greift aber nur bei
`--execute`, nicht bei Plan-Only/`--output`, und nicht für die Down-Richtung.

Zu trennen ist: die **Listen** des Planners (`dependentViewsTo*`, auch für Sichten ohne
Operation) und die **Eimer-Zuordnung** von Operationen (`absorbingRebuildTableFor`).

## Phasen

### P0 — Messung

1. Warum entsteht ein `ReplaceView` für eine unveränderte Sicht? Beide `query`-Texte
   (Soll, Ist) im Test ausgeben und den Fold-Pfad (`RawTextFolding.viewQuery`,
   `QuerySpelling.equal`) prüfen.
2. Den Report-Befund aus Fall 2 bestätigen (`executionError`, fehlendes `DROP VIEW`)
   und festhalten, ob zusätzlich das `CREATE VIEW` ohne `IF NOT EXISTS` scheitern würde.
3. Plan-Only (`--output`, ohne `--execute`): taucht der Blocker aus Fall 1 schon im Plan
   auf? Es gibt dort keinen Live-Katalog.
4. Die Fälle: eine Sicht, mehrere Sichten, **eine Sicht über zwei neu gebaute Tabellen**
   (H.3a-Einschränkung: nur der alphabetisch erste Eimer löscht die Sicht, aber mit der
   Ableitung würde sie in beiden Eimern angelegt), **eine Sicht über eine Sicht**,
   `SELECT *` (der Spaltensatz ändert sich beim Neubau).
5. **Down-Richtung / `schema rollback`:** der Down-Neubau vertauscht die Schemas; die
   Ableitung muss dort ebenfalls stimmen.

### P1 — Entscheidung

Richtung (vorläufig A′, mit B als Rückfall für Sichten, deren Text sich nicht sicher
lesen lässt); ob ein ADR nötig ist (bei A′ voraussichtlich nicht).

### P2 — Umsetzung

Je nach P1. Mindestens: der Neubau nimmt abhängige Sichten unabhängig von der
Deklaration mit — in der Drop- wie in der Create-Liste und in der Eimer-Zuordnung —,
auch transitiv; der Lauf endet ohne Fehler.

### P3 — Doku

Anwenderhandbuch (Hinweis, dass `dependencies` an Sichten bisher nötig waren — und ab
wann nicht mehr); `CHANGELOG.md`; prüfen, ob `spec/ddl-generation-rules.md` regelt,
welche Sichten ein Neubau mitnimmt, und das gegebenenfalls nachziehen.

## Nicht-Scope

- Materialisierte Sichten (bleiben blockiert, `emitMaterializedViewBlocker`).
- Trigger, deren Rumpf die Tabelle nur erwähnt: heute zählt nur `trigger.table`, nicht
  der Rumpf. Ein Gegenstück zur Sicht-Ableitung, aber nicht Teil von #50.
- Der Neubau selbst (Reihenfolge, Temp-Namen, Preflight) — unverändert.
- Andere Dialekte.

## Akzeptanz

- E2E gegen eine echte SQLite-Datei: Neubau einer Tabelle mit abhängiger Sicht, **mit
  und ohne** deklarierte `dependencies`; Exit 0, Daten und Sicht erhalten, ein zweiter
  Lauf ist ohne Operationen.
- Dasselbe für die Down-Richtung (`schema rollback` stellt Tabelle und Sicht her) und
  für den Plan-Only-Pfad (`--output`: kein `TRANSACTION_SCOPE_UNSUPPORTED`).
- Sicht über eine Sicht (transitiv) und Sicht über zwei neu gebaute Tabellen: getestet;
  bleibt eine Grenze, steht sie im Handbuch.
- Der Test schlägt auf dem Stand `1b8a2cce4` fehl, und zwar an der erwarteten Stelle:
  Blocker `TRANSACTION_SCOPE_UNSUPPORTED` (Fall 1) bzw. `executionError` „no such
  table" (Fall 2) — nicht nur am Exit-Code. Die neuen Tests werden einmal mit absichtlich
  falscher Zusicherung rot gesehen (`CLAUDE.md`, „Grün heißt nicht geprüft").
- Kein Quoting-/Groß-Kleinschreibungs-Fehler beim Ableiten der Tabellenbezüge
  (`"T"`, `` `t` ``, `[t]`, Schema-Präfix `main.t`); Namen in Literalen, Kommentaren und
  als Funktionsname lösen keinen Bezug aus.
- `make docker-test`, `make docker-check` (betroffene Module, einmal ohne `MODULES=`),
  `make integration` (setzt `-PintegrationTests` selbst), `make solid-suppression-gate`,
  `make docs-check` grün.
