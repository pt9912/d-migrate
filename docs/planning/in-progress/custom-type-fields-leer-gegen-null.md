# Custom Types: Datei-Form gegen Reader-Form im Vergleich (Issue #51)

> **Status:** In Umsetzung (2026-10-25); Draft mit Scope nach drei Reviews. Aus Issue #51:
> `schema migrate` gegen PostgreSQL plant `AlterCustomType` für einen unveränderten Typ
> und blockiert. **Vorbedingung:** keine. Aktiviert mit dem Implementierungs-Commit.

## Ziel

Ein benannter Typ (`custom_types`), der in Datei und Datenbank gleich ist, erzeugt keine
`AlterCustomType`-Operation — gleich, wie die Datei ihn schreibt. `schema migrate` gegen
PostgreSQL führt dann eine rein additive Änderung (eine Spalte mehr) aus.

## Problem

Nachgestellt mit einem Integrationstest gegen PostgreSQL (Testcontainers): Typ
`ee AS ENUM ('a','b')`, Tabelle `t(id, e ee NOT NULL DEFAULT 'a')`; Soll = dasselbe plus
Spalte `extra`. Zwei Messungen, eine Variable:

| Soll-Typ `ee` | Ergebnis |
| --- | --- |
| `fields = null` (handgebautes Schema) | Exit 0, nur `AddColumn` |
| `fields = emptyMap()` (so liefert es der Datei-Parser) | Exit 8, `AlterCustomType` (`MANUAL_REQUIRED`, nicht gerendert), `customTypesChanged = [fields: null → {}]` |

Ein Test mit handgebautem Soll (`fields = null`) bleibt auch ohne Fix grün — der Test muss das
Soll über den Datei-Parser erzeugen oder `fields = emptyMap()` setzen.

### Ursache

* Der Datei-Parser setzt `fields = parseColumns(childNode["fields"])`
  ([`SchemaNodeStructureParsers.kt`](../../../adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeStructureParsers.kt),
  `parseCustomTypes`); `parseNamedObjectMap` liefert für einen fehlenden Knoten eine **leere** Map.
* Der PostgreSQL-Reader lässt `fields` bei Enum und Domain **unbelegt** (`null`)
  ([`PostgresSchemaStructureReaders.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt)).
* [`SchemaComparator.compareCustomType`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt)
  vergleicht `left.fields == right.fields` ohne Angleichung; `values` vergleicht es mit
  `orEmpty()`. `null != {}`.
* Der Fingerabdruck ist nicht betroffen
  ([`CanonicalPayload`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/CanonicalPayload.kt)
  schreibt `fields?.size ?: 0`).

Nur der PostgreSQL-Reader erzeugt überhaupt `CustomTypeDefinition` (MySQL, MSSQL und Oracle
lesen keine Typ-Objekte; ihre Typen kommen nur aus der Datei, dort ist es leer gegen leer).
SQLite ebenso — deshalb fiel es dort nicht auf. Der Fehler ist also **PostgreSQL-spezifisch**.

### Weitere Quellen desselben Symptoms (aus dem Review, vor P0 zu messen)

Ein Typ gilt als geändert, sobald **irgendein** Feld abweicht — nicht nur `fields`:

1. **Composite, `ordinal`:** der Reader setzt `ordinal = Index + 1`
   ([`ColumnDefinition.ordinal`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ColumnDefinition.kt)
   ist „bewusst nicht Teil von schema compare“), eine handgeschriebene Datei trägt es nicht.
   Die Map-Gleichheit der `ColumnDefinition` (data class) schließt es ein.
2. **Composite, Feldattribute:** der Reader füllt nur `type` und `ordinal`; `required`,
   `default`, `description` an einem Datei-Feld bleiben eine „Änderung“.
3. **`description`:** der Reader liest sie für Typen nicht, die Datei darf sie tragen;
   der Fingerabdruck lässt sie weg, der Vergleich nicht (Widerspruch).
4. **Domain:** `baseType` (`mapParamType`), `check` (`normalizeDomainCheck`), `precision`/`scale`
   kommen vom Reader in Katalogform, die Datei in Autorenform; `compareCustomType` kennt weder
   Projektion noch Folding.
5. **Enum, `values`:** Reihenfolge (die Liste ist ordnungsabhängig) — Reader-Reihenfolge
   (`enumsortorder`) gegen Datei.

Außer `fields` fand das Review in `compareTable`/`compareColumn` keine zweite
`null`-gegen-leer-Stelle (Listen und Maps dort haben Defaults `emptyList()`/`emptyMap()`,
`TableMetadata()` ist gleichgesetzt, `ConstraintDefinition.columns` löst kein Falsch-Positiv aus).

### Antwort auf Frage (b) aus dem Issue

`AlterCustomType` ist für PostgreSQL **nicht in der ersten Matrix**
([`PostgresDiffDdlGenerator`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffDdlGenerator.kt):
`OpCategory.UNSUPPORTED`, Diagnose `POSTGRES_RENDER_SKIP`); eine **echte** Änderung eines
benannten Typs (neue Werte, andere Basis) blockiert dort mit Exit 8. Es gibt dazu weder
einen ADR noch eine Spec-Regel (grep in `spec/` und `docs/adr/` ohne Treffer für PostgreSQL),
und das Handbuch nennt die Grenze bisher nicht.

## Richtung

**A — Vergleich angleichen (gewählt).** `compareCustomType` behandelt die Darstellungs-
unterschiede, die Reader und Parser legitim erzeugen, als gleich: `fields` mit `orEmpty()`,
Felder ohne `ordinal`. Über `description`, Domain-Felder und Enum-Reihenfolge entscheidet
P0 nach Messung (jeweils: angleichen, wenn die Datei-Form legitim ist und die Datenbank sie
nicht zurückliefert; sonst als Befund festhalten). Eine Stelle, alle Dialekte, kein
Reader- oder Parser-Vertrag betroffen. Ein Eintrag bei ADR 0055 (zielbewusster Vergleich) ist
nur nötig, wenn P0 ein allgemeines Muster „fehlend gleich leer“ für den ganzen Vergleich findet.

**B — Reader und Parser auf eine Form bringen.** Streut über mehrere Module, und jede neue
Quelle kann die Asymmetrie neu einführen. Verworfen.

Nicht-Ziel: `AlterCustomType` für PostgreSQL rendern (`ALTER TYPE … ADD VALUE`), eigener Umfang.

## Phasen

* **P0 Messung** (Integrationstest gegen PostgreSQL, Soll über den Datei-Parser): je Fall
  eine Probe mit „Datei-Form gegen Reader-Form“ — Enum (2 und 3 Werte), Composite (ohne
  `ordinal`; mit `required`/`default` am Feld), Domain (`base_type`, `check`, `precision`),
  Typ mit `description`. Ergebnis je Fall: gleich / `AlterCustomType`. Abbruchkriterium: nur
  die Felder von `CustomTypeDefinition` (acht) und die Felder der Composite-Spalten; was
  darüber hinausgeht (Spalte, Tabelle), ist Befund für ein Folge-Issue.
* **P1 Umsetzung:** `compareCustomType` nach P0; Unit-Tests im Core: `fields` `null` gegen leer
  gleich, leer gegen gefüllt verschieden (beide Richtungen), Composite mit/ohne `ordinal`
  gleich, echte Feldänderung (anderer Typ) verschieden, je gemessener weiterer Fall. Die
  Konsumenten von `CustomTypeDiff.fields` (`OperationMapperSchemaObjects`, die
  `*DiffCustomTypeOps` der Treiber) lesen ihr `fields` nur bei `COMPOSITE`; in P1 gegenlesen.
* **P2 Integration** (Muster `PostgresEnumMigrateConvergenceIntegrationTest`, aber mit
  Datei-Parser-Soll): `ref_type`-Enum + additive Spalte → Exit 0, `AddColumn` allein; zweiter
  Lauf ohne Operationen (`operationsTotal: 0`, kein `POST_EXECUTE_DRIFT`); Rückweg
  (`--generate-rollback`, `schema rollback`) Exit 0; eine **echte** Änderung der Werte blockiert
  weiter (Exit 8, `DIALECT_UNSUPPORTED_OPERATION`, `AlterCustomType`, und `fields` steht nicht
  im Diff); Domain und Composite je einmal; `file:`-Ziel bleibt unverändert (dort sieht die
  Operation weiter, ADR 0060).
* **P3 Doku:** Anwenderhandbuch §3.21 „Eigene Datentypen definieren“ (Anknüpfung bei
  `CreateCustomType`/`DropCustomType`): was heute gilt — auf PostgreSQL ändert `schema migrate`
  einen benannten Typ nicht (`AlterCustomType` blockiert mit Exit 8); die Aussage „unveränderte
  Typen sind kein Hindernis“ erst mit dem Fix-Commit. `CHANGELOG.md` (Fixed). Spec: es gibt keine
  Regel zu `AlterCustomType` auf PostgreSQL; ob eine Zeile in `spec/ddl-generation-rules.md`
  gehört (Zielbild: PostgreSQL rendert es noch nicht), entscheidet P3.

## Nicht-Scope

* `AlterCustomType` auf PostgreSQL rendern.
* `null`/leer-Asymmetrien außerhalb von `CustomTypeDefinition` (Folge-Issue, wenn P0 welche findet).

## Akzeptanz

* Der Integrationstest schlägt ohne die Änderung an der erwarteten Stelle fehl
  (`AlterCustomType`, Exit 8, `fields: null → {}`) und wird mit ihr grün; dasselbe je
  Composite-/Description-Fall, den P0 als Fehler misst.
* Die neuen Tests werden einmal mit absichtlich falscher Zusicherung rot gesehen.
* Kein ADR betroffen (`make doc-immutable RANGE=origin/main..HEAD` trotzdem vor dem Push).
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal
  ohne `MODULES=` (die `test/integration-*`-Module bauen sonst nicht mit, Detekt eingeschlossen),
  `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`,
  `make solid-suppression-gate`, `make docs-check`.

## Nachtrag P0/P1/P2 (2026-10-25)

**P0 (Messung, `PostgresCustomTypeFileFormMigrateIntegrationTest`):** Das Soll ist die gelesene
Datenbank mit den Typen in Datei-Form (`fileForm`: `fields` leer, kein `ordinal`, optional
`description`) — nicht der Datei-Parser selbst (das Integrationsmodul kennt ihn nicht); die Form
stimmt mit `parseCustomTypes` überein. Vor dem Fix `AlterCustomType` (Exit 8) bei Enum mit zwei
und mit drei Werten, Composite ohne `ordinal` und Typ mit `description` (Domain: wegen `fields`).
**Gemessen in Autorenform (Domain, `base_type: integer`, `check: "VALUE > 0"`): nicht gleich.** Der
Reader liefert `precision=32`, `scale=0` und `check=(VALUE > 0)` (`normalizeDomainCheck` entfernt
`CHECK` und nur die äußeren Klammern des Katalogtexts `CHECK ((VALUE > 0))`). Das ist eine
**zweite Ursache** (Domain-Normalisierung), nicht Teil von #51: Folge-Issue. Nicht gemessen:
Enum-Reihenfolge in Autorenform (der Reader liefert `enumsortorder`, die Datei die Schreibreihenfolge).

**P1:** `SchemaComparator.compareCustomType`: `sameFields` — fehlend gleich leer, Vergleich des
Feldtyps (der Reader füllt an Composite-Feldern nur `type` und `ordinal`, `required`/`unique`/
`default` kann die Datenbank nie zurückmelden), die Position zählt nur, wenn beide Seiten eine
tragen (vertauschte Felder sind eine Änderung). Die `description` eines Typs zählt nur im strikten
Vergleich (`schema compare`): gegen **jedes** Ziel (auch ein `file:`-Ziel) wird sie nicht
verglichen, kein Reader liefert sie, keine Operation rendert sie. Die gemeldete Änderung trägt die
unveränderten Felder. Core-Tests rot ohne den Fix: „fehlend gleich leer“, „Position“, „Beschreibung“
(die übrigen sind Regressionsabsicherung).

**P2:** Integrationstest grün: additive Spalte Exit 0 ohne `AlterCustomType`, zweiter Lauf
`operationsTotal=1→0`, Rückweg über `schema rollback` (Spalte weg, Typ vorhanden), Composite mit
`required`/`unique` am Feld, echte Änderung von Enum, Domain und
Composite blockiert weiter (Exit 8, `DIALECT_UNSUPPORTED_OPERATION`). **Offen (Folge-Issue):** Domain in Autorenform
(`precision`/`scale`, Klammern im `CHECK`); `ALTER DOMAIN`/`ADD VALUE`-Rendern (Nicht-Scope).
