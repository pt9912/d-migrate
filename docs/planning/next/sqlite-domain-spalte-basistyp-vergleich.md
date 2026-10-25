# SQLite: eine Domain-Spalte über boolean, decimal oder uuid gilt bei jedem Lauf als geändert (Folge von #54)

> **Status:** Draft mit Scope (2026-10-25). Befund aus der Umsetzung von
> [`sqlite-neubau-cast-gleiche-speicherklasse.md`](../in-progress/sqlite-neubau-cast-gleiche-speicherklasse.md) (#54): Domain-Spalten über `boolean`, `decimal` oder `uuid`
> planen bei **jedem** `schema migrate` ein `AlterColumnType` und blockieren damit (über die Cast-Matrix) den
> Neubau, auch ohne jede Änderung. Gemessen ist nur `varchar` (nicht betroffen) gegen `boolean`/`decimal`/`uuid`
> (betroffen); nach dem Code ist **jeder** Basistyp betroffen, dessen Reverse-Form nicht `Text()` ist (P0 misst die Matrix).
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit.
> GitHub-Issue: [#56](https://github.com/pt9912/d-migrate/issues/56).

## Ziel

Eine Domain-Spalte, die Datei und SQLite-Datenbank gleich ausdrücken, erzeugt keine Operation — gleich über welchen Basistyp.

## Problem (gemessen in #54, P0; Ursache aus dem Code)

Die Datei trägt `Enum(refType = <domain>)`; die Datenbank enthält die Domain nicht als Typ-Objekt, der Reader liest die
Spalte als `Integer` (`boolean`), `Float` (`decimal`) oder `Text` (`uuid`).

**Ursache:** der **Fingerabdruck ist schon domain-bewusst**, der **strukturelle Vergleich nicht**.
[`SqliteNeutralTypeCanonicalizer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteNeutralTypeCanonicalizer.kt)
hat eine Überladung `canonicalize(type, customTypes)`, die `Enum(refType)` über `SqliteRefTypeColumns.baseType` auf den
Basistyp auflöst; der Abdruck-Pfad nutzt sie (`registrySchemaAwareCanonicalizer`, `TypeCanonicalizerWiring`). Der Vergleich
(`TableComparator.compareColumn`) bekommt dagegen die kontextfreie Projektion (`typeCanonicalizerFor`, KDoc: „Nur der
Fingerprint-Pfad nutzt das"); `Enum(refType)` wird kontextfrei zu `Text()` und steht gegen `Integer`/`Float`.

**Warum `varchar` unbetroffen ist:** nur zufällig — kontextfrei ergibt `Enum` `Text()`, und die Datenbank liest `Text()`. Dieselbe
Rechnung trifft jede Domain über `integer`, `smallint`, `biginteger`, `float`, `date`, `time`, `datetime`, `json`, `binary` u. a.,
soweit der Reader nicht `Text()` liest. „Domain über `integer` ist nicht betroffen" ist **nicht gemessen** (die #54-Tabelle hat
nur `varchar` und `boolean`/`decimal`/`uuid`).

### Zu prüfen (P0)

Abgeschlossene Matrix: eine Zeile je Basistyp aus
[`DomainTypes.resolveBaseType`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/metadata/DomainTypes.kt) (Zeilenquelle: das `when` in der privaten `resolveBaseTypeName`, alle Basistypen inkl. `char`, `xml`, `timestamptz`, `smallint`; für SQLite maßgeblich `SqliteRefTypeColumns.baseType`; `decimal` ohne `precision` ergibt E053 — eigene Zeile, kein Vergleichsfall)
mit Spalten *Reverse-Form · Soll · Operationen im Plan-Only · Exit mit `--execute`*. P0 endet, wenn alle Zeilen gefüllt sind.
Zusätzlich: Gegenproben **Enum-`ref_type`** (kontextfrei `Text()`, soll unbetroffen bleiben), **Domain mit Wertelisten-CHECK**
(`RefTypeVocabulary.resolve` löst sie zu `Enum(values, refType)` auf, der Wertevorrat-Vergleich greift vor `canon`; der Plan
kannte nur Enums), **Domain mit Länge** (`varchar(32)`: Reader liest `Text()`, Domain trägt `Text(32)` — eigener Befund aus der
#54-Plandatei, hier Abgrenzung), `file:`-Ziel (Datei gegen Datei).

## Richtung (nach P0 zu entscheiden)

* **A — Vergleich löst die Domain auf.** Muster existiert: `TargetProjection.resolvesRefTypeVocabulary` und
  `TableComparator.resolvedAgainst` lösen nur dort auf, wo die Gegenseite die Spalte **nicht** auch als `ref_type` führt
  (sonst entstünde ein zweites `ColumnDiff` und Operations-IDs änderten sich). **Präzedenzregel:** Datei gegen Datei bleibt
  unaufgelöst; ein Basistypwechsel erzeugt weiter genau ein `AlterCustomType` ohne zusätzliches `AlterColumnType`
  (Abstimmung mit `SqliteRebuildPlanner.domainColumnsWithStorageChange`).
  Umsetzungsort (P1, Kriterium: keine zweite Verdrahtung in `SchemaMigrateRunner`/`SchemaRollbackRunner`/`PartitionOverlayHint`,
  kontextfreier Vertrag des `TargetProjection.type` bleibt):
  (a) `TableComparator` löst vor `canon` gegen den `CustomTypeContext` auf, den es ohnehin hat, mit Dialekt-Schalter nach dem
  Muster `resolvesRefTypeVocabulary` (SQLite-spezifisch; PostgreSQL kennt den Typ) — **bevorzugt**;
  (b) schema-bewusster Canonicalizer in `TargetProjection` — bricht den kontextfreien Vertrag.
* **B — Reader:** nicht tragfähig (die Datenbank trägt die Domain nicht).
* **Fingerabdruck:** der Abdruck faltet die Domain schon; erwartet **kein** Tag-Bump. P0-Kriterium: Hash vorher/nachher für
  denselben Eingang gleich. Ein ADR nur, wenn der Vergleich Domains neu auflöst (Präzedenz ADR 0060 Wertevorrat; Grenze ADR 0026
  (streng/strukturell; die Kontextfreiheit steht im KDoc von `TypeCanonicalizerWiring`); dann neuer ADR (nächste freie Nummer; ADR 0061 als jüngster Domain-Basistyp-ADR mit prüfen; accepted-ADRs bleiben unberührt) und Klärung, ob
  er 0060/0026 ergänzt.

## Folgen für den Neubau-CAST-Plan (#54)

Entfällt das `AlterColumnType`, kopiert `computeColumnMapping` die Spalte (Speicherklasse gleich) statt sie durch die Cast-Matrix
zu schicken; `castBlocked` bleibt leer. Pro Basistyp (`decimal`: `Float` gegen `Decimal`, `uuid`) hängt das an `toSql` — messen,
nicht annehmen. In
[`sqlite-neubau-cast-gleiche-speicherklasse.md`](../in-progress/sqlite-neubau-cast-gleiche-speicherklasse.md) kippen danach die
Tabellenzeile zu „boolean/decimal/uuid", die Abgrenzung („bleibt außerhalb dieses Plans") und der Test „Domain über boolean als
bekannte Grenze (Exit 8)" — letzterer wird hier zur Akzeptanz.

Nicht-Ziel: der Neubau-CAST selbst (erledigt in #54 Ursache 2).

## Phasen

P0 Messung (SQLite-CLI-Test, Matrix oben), P1 Entscheidung (Ort, Präzedenzregel, ADR-Bedarf; ob die aufgelöste Tabelle `RefTypeVocabulary.resolve` nur den Basistyp oder auch den Vorrat liefert; ob die vorhandene Überladung `canonicalize(type, customTypes)` Option (a) verkleinert), P2 Umsetzung mit Tests (Plan-Only 0
Operationen, `--execute`, zweiter Lauf, Rückweg, echte Änderung des Basistyps bleibt genau ein `AlterCustomType`), P3 Doku
(Handbuch nur Ist-Zustand; Grenze streichen, `CHANGELOG.md`).

**P2 Matrix:** den Cross-Dialect-Matrix-Test (`test/cross-dialect-matrix`) um eine Domain-Spalte je Basistyp der P0-Matrix erweitern (zweiter Lauf `operationsTotal` 0); Fälle zuerst als Fixture, rot ohne die Änderung.

## Akzeptanz

* Jede gemessene Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt; absichtlich falsche
  Zusicherung einmal rot gesehen.
* Zweiter Lauf: `operationsTotal` = 0; Rückweg: Exit 0; Datei gegen Datei mit Basistypwechsel: genau ein `AlterCustomType`,
  kein `AlterColumnType`; Fingerabdruck vorher/nachher unverändert; der #54-Test „Domain ueber boolean: der Neubau blockiert wegen des Dauer-AlterColumnType (bekannte Grenze)" (`adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaMigrateRebuildFoldedGapsSqliteTest.kt`) wird in P3 umbenannt und erwartet Exit 0.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=` (geteilte Signatur im
  Hexagon, `CLAUDE.md`), `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`,
  `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
