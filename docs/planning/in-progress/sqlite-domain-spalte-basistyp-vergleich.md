# SQLite: eine Domain-Spalte über boolean, decimal oder uuid gilt bei jedem Lauf als geändert (Folge von #54)

> **Status:** Umgesetzt, im Review (2026-10-25); P0 bis P3 und die Gates stehen in den Nachträgen unten. Befund aus der Umsetzung von
> [`sqlite-neubau-cast-gleiche-speicherklasse.md`](sqlite-neubau-cast-gleiche-speicherklasse.md) (#54): Domain-Spalten über `boolean`, `decimal` oder `uuid`
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
[`sqlite-neubau-cast-gleiche-speicherklasse.md`](sqlite-neubau-cast-gleiche-speicherklasse.md) kippen danach die
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

## Nachtrag P0: Messung (2026-10-25)

Messung per SQLite-CLI-Test (`schema generate` → Datenbank → `schema migrate` gegen `db:sqlite://`), eine Domain `d` über den
Basistyp, Spalte `n: enum, ref_type: d`, Tabelle `t(id, flag, n)`. *Plan-Only* = Lauf ohne Änderung an der Datei; *Neubau* =
dieselbe Datei mit `flag` auf `required: true` (Neubau aus anderem Grund), `--execute`; *Zweiter Lauf* = derselbe Befehl erneut.
„Reverse-Form" ist der deklarierte Typ aus `schema generate` (`SqliteTypeMapper.toSql`), gelesen durch `SqliteTypeMapping.mapColumn`.
`CreateCustomType` steht bei **jedem** Lauf im Plan (die Datenbank führt kein Typ-Objekt, `SQLITE_CUSTOM_TYPE_AT_COLUMN`,
rendert nichts): `operationsTotal` ist deshalb mit Domain nie 0, sondern 1 — die Messgröße ist die Abwesenheit von
`AlterColumnType`.

| Basistyp der Domain | Deklariert | Reverse-Form | Plan-Only Operationen | Plan-Only Exit | Neubau `--execute` Exit | Zweiter Lauf |
| --- | --- | --- | --- | --- | --- | --- |
| `integer` | `INTEGER` | `Integer` | `CreateCustomType`, **`AlterColumnType`** | 0 | 0 | wieder `AlterColumnType` |
| `biginteger` | `INTEGER` | `Integer` | wie `integer` | 0 | 0 | wie oben |
| `smallint` | `INTEGER` | `Integer` | wie `integer` | 0 | 0 | wie oben |
| `boolean` | `INTEGER` | `Integer` | `CreateCustomType`, **`AlterColumnType`** | **8** (`SQLITE_CAST_NOT_WHITELISTED`) | **8** | 8 |
| `real` (`float`, einfache Genauigkeit) | `REAL` | `Float()` | `CreateCustomType`, **`AlterColumnType`** | **8** | **8** | 8 |
| `float` | `REAL` | `Float()` | `CreateCustomType`, **`AlterColumnType`** | 0 | 0 | wieder `AlterColumnType` |
| `decimal` mit `precision`/`scale` | `REAL` | `Float()` | `CreateCustomType`, **`AlterColumnType`** | **8** | **8** | 8 |
| `decimal` ohne `precision` | `TEXT` (Basistyp nicht auflösbar, die Spalte bleibt Enum) | `Text()` | `CreateCustomType` | 0 | 0 | 1 Operation |
| `text`, `varchar(32)`, `char(5)` | `TEXT` | `Text()` | `CreateCustomType` | 0 | 0 | 1 Operation |
| `uuid`, `json`, `xml` | `TEXT` | `Text()` | `CreateCustomType` | 0 | 0 | 1 Operation |
| `date`, `time`, `datetime`, `timestamptz` | `TEXT` | `Text()` | `CreateCustomType` | 0 | 0 | 1 Operation |
| `binary` | `BLOB` | `Binary` | `CreateCustomType`, **`AlterColumnType`** | 0 | 0 | wieder `AlterColumnType` |
| Gegenprobe: Enum mit `ref_type` | `TEXT` + CHECK | `Text()` | `CreateCustomType` | 0 | 0 | 1 Operation |
| Gegenprobe: Domain mit Wertelisten-CHECK (`text`) | `TEXT` + CHECK | `Text()` | `CreateCustomType` | 0 | 0 | 1 Operation |
| Gegenprobe: Domain über `integer` mit CHECK | `INTEGER` + CHECK | `Integer` | `CreateCustomType`, **`AlterColumnType`** | 0 | 0 | wieder `AlterColumnType` |

Befunde, die den Plan korrigieren:

* **Betroffen ist jeder Basistyp mit nicht-`Text`-Reverse-Form** (`integer`, `biginteger`, `smallint`, `boolean`, `real`, `float`,
  `decimal` mit Präzision, `binary`), wie aus dem Code vermutet — nicht nur `boolean`/`decimal`/`uuid`.
* **`uuid` ist nicht betroffen** (Reverse-Form `Text()`, wie `varchar`); die Angabe aus #54 war falsch. Dasselbe gilt für `json`,
  `xml`, `date`, `time`, `datetime`, `timestamptz`, `char`.
* **Die Domain über `integer`, `biginteger`, `smallint`, `float`, `binary` ist ebenfalls betroffen, blockiert aber nicht**
  (Exit 0): Die Cast-Matrix lässt `Integer`→`Integer` durch. Der Schaden ist dort ein **Neubau der Tabelle bei jedem Lauf**
  (`AlterColumnType` → Rebuild), ohne dass sich etwas geändert hat. Blockiert wird nur, wo die Matrix die Umwandlung nicht
  kennt: `boolean`, `real`, `decimal` mit Präzision.
* **`decimal` ohne `precision`** liefert hier weder E053 noch einen Fehler: der Generator rendert `TEXT`, die Spalte bleibt
  `Enum` (`SqliteRefTypeColumns.baseType` ergibt `null`). Das ist vorhanden, nicht Gegenstand dieses Slices.
* **Fingerabdruck:** `MigrationFingerprint` löst die Domain über `registrySchemaAwareCanonicalizer` schon auf den Basistyp auf;
  der Slice ändert nur den Vergleich (`TableComparator`), nicht den Abdruck-Pfad — der Hash für denselben Eingang bleibt
  gleich, kein Tag-Bump.

## Nachtrag P1: Entscheidung (2026-10-25)

**Richtung A, Umsetzungsort (a) in leicht abgewandelter Form.** Der Vergleich (`TableComparator`) löst die Domain-Spalte vor
`canon` über ihren Basistyp auf, nach dem Muster `resolvesRefTypeVocabulary`. Abweichung vom Plan: `TableComparator` kann den
Basistyp nicht selbst bestimmen (`DomainTypes`/`SqliteRefTypeColumns` liegen in den Treibern, nicht im Hexagon `core`), und
`resolvedAgainst`/`RefTypeVocabulary.resolve` liefern nur den Wertevorrat, nicht den Basistyp. Darum trägt `TargetProjection` ein
neues Feld `typeWithCustomTypes: ((NeutralType, Map<String, CustomTypeDefinition>) -> NeutralType)?`, und der Runner setzt es
aus der **vorhandenen** Überladung `NeutralTypeCanonicalizer.canonicalize(type, customTypes)` (`SqliteNeutralTypeCanonicalizer`
löst `Enum(refType)` über `SqliteRefTypeColumns.baseType` auf) — nur für einen Dialekt mit `resolvesRefTypeVocabulary`
(heute SQLite). Das Feld ist ein Schalter des **ziel-bewussten** Vergleichs; `TargetProjection.type` bleibt kontextfrei.

* **Keine zweite Verdrahtung:** `TargetProjection` wird an genau einer Stelle gebaut (`SchemaMigrateRunner.computePlanAndOverlay`);
  `SchemaRollbackRunner` und `PartitionOverlayHint` bauen keinen Vergleich, sondern Abdrücke, und bleiben unverändert.
* **Präzedenzregel** (`DomainColumnTypes`): aufgelöst wird nur eine Seite, deren Spalte `Enum(refType)` auf eine DOMAIN ist
  **und** deren Gegenseite die Spalte nicht auch als `ref_type` führt. Datei gegen Datei bleibt unaufgelöst; ein Basistypwechsel
  ist genau ein `AlterCustomType`, ohne `AlterColumnType` (gemessen, Test).
* **Wertevorrat:** `RefTypeVocabulary.resolve` bleibt unverändert (liefert den Vorrat einer Wertelisten-Domain); der
  Wertevorrats-Vergleich steht weiter vor `canon`. Die aufgelöste Domain-Spalte läuft danach über die Überladung — für
  Wertelisten-Domains ergibt das `Text()`, wie gemessen.
* **Kein ADR:** die Auflösung ändert weder die Grenze von ADR 0026 (der Fingerabdruck-Pfad kannte die Auflösung schon, der
  Vergleich übernimmt dieselbe Projektion für denselben Dialekt) noch den Wertevorrat (ADR 0060). Sie ist die Angleichung des
  Vergleichs an den Abdruck, kein neuer Vertrag. (ADR 0064/0066 bleiben unberührt.)
* **Kein Tag-Bump:** `MigrationFingerprint`, `CanonicalPayload` und `DiffPlanner` sind unberührt (`git diff` zeigt keine Zeile
  dort); die Abdruck-Projektion ist dieselbe wie zuvor. Der Abdruck ändert sich durch diese Änderung nicht.
* **Der Neubau-CAST (#54):** entfällt das `AlterColumnType`, kopiert `computeColumnMapping` die Spalte; gemessen für alle
  Zeilen oben (Neubau Exit 0, Daten erhalten, `castBlocked` leer).

**Nebenbefund, ausdrücklich gewollt:** eine Domain über `integer` gegen eine **Textspalte** der Datenbank blieb bisher
unbemerkt (`Enum(refType)` kontextfrei `Text()` = `Text()`); sie gilt jetzt als Typänderung (Test).

## Nachtrag P2/P3: Umsetzung (2026-10-25)

* Code: `TargetProjection.typeWithCustomTypes`, `DomainColumnTypes` (neu, `hexagon/core`), `TableComparator` (Domain-Typen je
  Seite neben dem Wertevorrat), `registryDomainAwareCanonicalizer` (`TypeCanonicalizerWiring`), Setzen im Runner.
* Tests: `DomainColumnTypesTest` (Unit); `CliSchemaMigrateDomainColumnSqliteTest` (eine Zeile je P0-Basistyp und Gegenprobe:
  Plan-Only nur `CreateCustomType`, Neubau `--execute` Exit 0 mit erhaltener Zeile, zweiter Lauf, Rückweg; Datei gegen Datei
  ohne Änderung und mit Basistypwechsel = genau ein `AlterCustomType`; echter Unterschied bleibt `AlterColumnType`);
  `CliSchemaMigrateRebuildFoldedGapsSqliteTest` (der #54-Test „Domain über boolean“ erwartet jetzt Exit 0). Ohne die Zeile im
  Runner sind elf der Tests rot (gemessen: `integer`, `biginteger`, `smallint`, `boolean`, `real`, `float`, `decimal(10,2)`,
  `binary`, Domain über `integer` mit CHECK, der „echte Unterschied“-Test und der #54-Test).
* Matrix: neuer Workstream `H.1` (`test/cross-dialect-matrix`, `fixtures/H.1/positive`): `current` trägt die Reverse-Form
  (`integer`, `float`, `text`, `binary`), `desired` die Domains über alle Basistypen der P0-Matrix (ohne `decimal` ohne
  `precision`, das keinen Vergleich auslöst) plus Enum und Wertelisten-Domain, dazu `flag` auf `required: true` als
  Neubaugrund. SQLite-Zelle Exit 0; ohne die Änderung Exit 8 (gemessen, `SQLITE_CAST_NOT_WHITELISTED`). PostgreSQL/MySQL sind
  carve-out (`DIALECT_UNSUPPORTED_OPERATION`, Befund gilt nur für SQLite). Der Sweep hatte weder einen ziel-bewussten
  Vergleich noch geladene Treiber (Typ-Projektion = Identität): beides ist jetzt verdrahtet wie in der CLI; die übrigen Zellen
  blieben grün.
* **Abweichung von der Akzeptanz:** „zweiter Lauf `operationsTotal` = 0“ gilt gegen eine Datenbank nicht. Die Datenbank führt
  kein Typ-Objekt, `CreateCustomType` (rendert nichts, `SQLITE_CUSTOM_TYPE_AT_COLUMN`) steht bei **jedem** Lauf im Plan, auch
  für `varchar`, das nie betroffen war. Gemessen wird deshalb: im Plan nur `CreateCustomType` (kein `AlterColumnType`, kein
  Neubau). Die Matrix prüft nur Exit-Codes.
* **Review-Nachtrag (Runde 1/2):** `SqliteDomainFingerprintWiringTest` (`hexagon/application`) bildet den Abdruck über die echte Verdrahtung (`registrySchemaAwareCanonicalizer` und die `capability*`-Projektionen wie in `SchemaMigrateRunner.endpointFingerprints`) und sagt Gleichheit aus: Domain über `boolean`/`decimal`/`integer` = Spalte mit direktem Basistyp; ein Basistypwechsel ändert den Abdruck. Feste Hashes pinnt er bewusst nicht (sie enthalten die Algorithmus-Version). Zuvor war ein fester Hash einmal mit dem Produktionscode von `main` und einmal mit der Änderung gemessen und gleich. Matrix `H.1` über alle Dialekte: siehe „Gates“.
* Doku: `docs/user/anwenderhandbuch.md` (SQLite-Hinweis zu Enum/Domain), `CHANGELOG.md` (Unreleased).
