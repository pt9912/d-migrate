# PostgreSQL: Domain in Autorenform gegen Katalogform (Issue #52)

> **Status:** Draft mit Scope (2026-10-25). Aus Issue #52, abgegrenzt von #51
> (`custom-type-fields-leer-gegen-null.md`): dort ging es um `fields`, `ordinal`
> und `description`; die Domain-Abweichung blieb offen.
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim
> ersten Implementierungs-Commit.

## Ziel

Eine Domain, die Datei und PostgreSQL-Datenbank gleich ausdrücken, erzeugt keine
`AlterCustomType`-Operation — auch wenn die Datei sie anders schreibt als der Katalog.
`schema migrate` führt dann eine rein additive Änderung aus; eine **echte** Änderung der
Domain blockiert weiter (Exit 8, `AlterCustomType` ist für PostgreSQL nicht gerendert).

## Problem

Gemessen mit einem Integrationstest gegen PostgreSQL (`CREATE DOMAIN pos AS integer CHECK
(VALUE > 0)`; Soll in Autorenform `base_type: integer`, `check: "VALUE > 0"`, sonst unverändert):

| Feld | Reader (Katalog) | Datei (Autor) |
| --- | --- | --- |
| `baseType` | `integer` | `integer` |
| `precision` / `scale` | `32` / `0` | `null` / `null` |
| `check` | `(VALUE > 0)` | `VALUE > 0` |

[`SchemaComparator.compareCustomType`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt)
vergleicht `precision`, `scale` und `check` strikt (`valueChangeOrNull`), ohne Projektion oder Folding.

### Ursachen

1. **`precision`/`scale` bei ganzzahligem Basistyp.** Die Abfrage
   ([`PostgresTypeMetadataQueries.listDomainTypes`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMetadataQueries.kt))
   liest `information_schema.domains.numeric_precision/_scale`; PostgreSQL füllt sie auch für
   `integer` (32/0), `bigint` (64/0), `smallint` (16/0). Eine Datei schreibt sie nur für
   `decimal(p, s)`.
2. **`check` in Katalogform.** `pg_get_constraintdef` liefert die vom Server umgeschriebene Form
   (`CHECK ((VALUE > 0))`); [`normalizeDomainCheck`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt)
   entfernt `CHECK` und **eine** Klammerebene. Das trifft den einfachen Fall nicht
   (`(VALUE > 0)` bleibt), und der Server schreibt weiter um: `BETWEEN` wird zu
   `(VALUE >= 1) AND (VALUE <= 10)`, `IN (…)` zu `= ANY (ARRAY[…::text])`, Literale bekommen Casts.
   Eine Klammer- oder Leerraum-Angleichung allein reicht deshalb nicht.

Ob weitere Felder betroffen sind (`baseType` bei `varchar(n)`/`text`/`bpchar`, Domain-Default,
`NOT NULL` an der Domain), ist zu messen (P0).

## Richtung (offen, nach P0 zu entscheiden)

**A — Reader: `precision`/`scale` nur für numerische Basistypen** (`numeric`/`decimal`). Kleine,
lokale Änderung (Ursache 1); ändert, was ein `schema reverse` einer ganzzahligen Domain schreibt
(die Datei verliert `precision: 32`), also einen Reverse-Vertrag — im Handbuch/CHANGELOG nennen,
Goldens und Reverse-Tests prüfen.

**B — Vergleich: `precision`/`scale` nur bei numerischem Basistyp zählen.** Dieselbe Wirkung ohne
Reader-Änderung, dafür im dialektübergreifenden Comparator.

**C — Domain-`check` falten.** Für Ursache 2 gibt es Infrastruktur:
[`RawTextFolding`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/RawTextFolding.kt)
(Herkunft des Textes über `RawTextAuthorship`, Serverform über `RawTextServerForm`, Ausdrucksvergleich
über `ConstraintDiffContract.canonicallyEqual`) wird für Constraints, Index-Prädikate, Generierung und
Sicht-Rümpfe benutzt, **nicht** für den `check` eines Custom Types. ADR 0056 hält fest, dass der
Migrate-Pfad die Dialekt-Schreibweise nicht allgemein faltet; ob die Herkunft-/Serverform-Quellen
(autor-geschrieben gegen Katalog, Sandbox) hier ausreichen oder ein eigener Vertrag nötig ist, ist
die Kernfrage von P1 (Folge-ADR wahrscheinlich).

**D — `check` nur gegen eine Serverform vergleichen** (Sandbox: PostgreSQL legt die Autorenform in einer
Wegwerf-Domain an und liest sie zurück; beide Seiten sind dann Katalogform). Präzise, braucht aber eine
Datenbank und gilt nur für `--execute`/Live-Ziele, nicht für `file:`.

Nicht-Ziel: `AlterCustomType` für PostgreSQL rendern; Composite und Enum (erledigt mit #51).

## Phasen

* **P0 Messung** (Integrationstest gegen PostgreSQL, Muster
  `PostgresCustomTypeFileFormMigrateIntegrationTest`): je Fall Autorenform gegen Katalogform —
  `integer`/`bigint`/`smallint` mit einfachem `CHECK`, `decimal(10,2)`, `text`/`varchar(n)`, `CHECK`
  mit `BETWEEN`, `IN (…)`, Literalen mit Cast, mehreren Bedingungen (`AND`), Domain mit `DEFAULT`
  und `NOT NULL`. Ergebnis je Fall: gleich/`AlterCustomType`, und was der Reader liefert.
* **P1 Entscheidung:** Richtung je Ursache (Ursache 1: A oder B; Ursache 2: C oder D oder
  „dokumentierte Grenze“), ADR falls ein Faltungsvertrag für Custom-Type-Ausdrücke entsteht
  (Anknüpfung ADR 0055 und 0056).
* **P2 Umsetzung** nach P1; Unit-Tests im Core bzw. Reader, Integrationstest grün, zweiter Lauf
  `operationsTotal=0`, Rückweg `schema rollback`, echte Änderung der Domain blockiert weiter.
* **P3 Doku:** Anwenderhandbuch §3.21 (die Grenze aus #51 streichen oder nachführen), `CHANGELOG.md`,
  `spec/ddl-generation-rules.md` prüfen (Domain-CHECK, Zeilen um 1253).

## Nicht-Scope

* `AlterCustomType` auf PostgreSQL rendern (`ALTER DOMAIN`).
* Andere Dialekte: nur PostgreSQL liest Typ-Objekte.
* Rohe Ausdrücke außerhalb von `CustomTypeDefinition.check`.

## Akzeptanz

* Der Integrationstest schlägt ohne die Änderung an der erwarteten Stelle fehl (`AlterCustomType`,
  Exit 8, Diff auf `precision`/`scale` bzw. `check`) und wird mit ihr grün — je gemessener Fall.
* Die neuen Tests werden einmal mit absichtlich falscher Zusicherung rot gesehen.
* Eine echte Änderung (anderer `CHECK`, andere Genauigkeit eines `decimal`) blockiert weiter.
* Ein `schema reverse`-Golden/Test deckt die geänderte Ausgabe, falls Richtung A gewählt wird.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`,
  `make solid-suppression-gate`, `make docs-check`; bei einem ADR `make doc-immutable
  RANGE=origin/main..HEAD`.
