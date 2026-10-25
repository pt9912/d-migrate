---
status: accepted
date: 2026-10-25
decision-makers: pt9912
consulted: docs/adr/0026-fingerprint-kanonisierung-post-compare.md, docs/adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md, docs/adr/0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md, docs/adr/0062-sqlite-eindeutiger-index-bleibt-index.md, docs/planning/in-progress/sqlite-default-vergleich.md
informed: hexagon/core (ColumnDefaultProjection, TableComparator, MigrationFingerprint), spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# Ein gleicher Spalten-`default` ist keine Änderung — Zahlen numerisch, `boolean` auf `integer` gefaltet, `schema-fingerprint-v21`

> **Status: accepted (2026-10-25).** Der Vergleich der Spalten-`default`s gleicht Zahlenliterale
> numerisch an (alle Dialekte) und faltet an einer `boolean`-Spalte `false`/`true` auf `0`/`1`,
> wo das Ziel `boolean` auf `integer` abbildet (SQLite). Der Fingerabdruck folgt derselben
> Projektion; der Algorithmus-Tag steigt auf `v21`. Der Reader-Ausgang ändert sich nicht.

## Kontext und Problemstellung

`schema migrate` plante für eine unveränderte Spalte mit `default: 0`/`5`/`1` bei jedem Lauf ein
`AlterColumnDefault` (Issue #54, Ursache 1). Gemessen auf **PostgreSQL, MySQL und SQLite**
(Integrationsmodule und SQLite-CLI-Test, jeweils dasselbe Schema): der Datei-Parser liefert
`DefaultValue.NumberLiteral(Int)` für eine Ganzzahl, jeder Reader liest den Katalogtext mit
`toLongOrNull()`/`toDoubleOrNull()` und liefert `Long` bzw. `Double`; MySQL liefert für
`DECIMAL(10,2) DEFAULT 0` sogar `0.00`. `NumberLiteral` ist eine data class — `0` (Int) ist nicht
`0L` und nicht `0.0` —, und `TableComparator` verglich per `==`. Nur ein Wert, der auf beiden
Seiten denselben Zahlentyp hat (`1.5`, `5000000000`), glich sich.

Auf SQLite kommt eine zweite Ursache dazu: `boolean` liegt als `INTEGER` ab, der Reader liest
`INTEGER` mit Default `0`/`1` und kennt den Typ „boolean“ nicht. Datei `default: false` gegen
Datenbank `0` galt als geändert; der Neubau, den das auslöste, endete bei `boolean` und `decimal`
mit Exit 8 (`SQLITE_CAST_NOT_WHITELISTED`, eigener Plan).

## Betrachtete Optionen

- **A — Reader vereinheitlicht den Zahlentyp** auf das, was der Parser liefert. Ändert, was
  `schema reverse` schreibt (`0` → `0.0`), und trifft nur den Reader, nicht den Datei-Parser; der
  `0`/`1`-Fall bliebe offen, weil der Reader den Spaltentyp „boolean“ nicht hat.
- **B — Der Vergleich gleicht an.** Zahlenliterale gelten bei numerischer Gleichheit als gleich;
  die Wahrheitswert-Faltung läuft über den Spaltentyp der Datei-Seite und die Typ-Projektion des
  Ziels.

## Entscheidung

**B.** P0 zeigt dieselbe Abweichung auf allen drei gemessenen Dialekten und `Int`↔`Double`
(`float`/`decimal`); die Auswahlregel des Plans verlangt dann den Vergleich, nicht den Reader. Der
Reader-Ausgang bleibt unverändert, ein `schema reverse` schreibt dieselbe Datei wie zuvor.

1. **`ColumnDefaultProjection`** (hexagon/core) trägt beide Regeln: `canonicalNumber` (über
   `BigDecimal`, ohne Exponent und nachlaufende Nullen: `2.0` → `2`, `1e3` → `1000`) und
   `project(type, default, canonicalizeType)`: ein `BooleanLiteral` an einer `boolean`-Spalte wird
   `0`/`1`, **wenn** die Typ-Projektion des Ziels `boolean` auf `integer` abbildet. Es gibt keinen
   neuen Parameter an `TargetProjection`; die Faltung folgt aus der bestehenden Typ-Projektion und
   gilt damit nur auf SQLite (die anderen Dialekte behalten `boolean`).
2. **`TableComparator`** vergleicht den Default beider Seiten über diese Projektion. Die Zahlen-
   Regel gilt auch im strikten Modus (`schema compare`): `0` und `0L` sind dort keine Aussage über
   die Datenbank. Die Wahrheitswert-Faltung gilt nur mit Zielprojektion.
3. **Operation und Operations-ID bleiben unverändert.** Die geplante Operation trägt die
   unveränderte Definition; `CanonicalPayload` schreibt weiter `num:${value}`. Eine Projektion, die
   in die Operation flösse, änderte die ID und entwertete Overlays (wie bei `TargetProjection`).
4. **Fingerabdruck-Bump.** `ALGORITHM` steigt von `schema-fingerprint-v20` auf `v21`: der Abdruck
   projiziert den Default unter derselben Funktion wie der Vergleich, sonst meldete der
   Post-Compare Drift ohne Operation. Schemas mit Zahlen-Default mit nachlaufender Null oder
   Exponent und SQLite-Schemas mit `boolean`-Default hashen anders; alle anderen bis auf den Tag
   wie zuvor. Ein Artefakt vor `v21` liefert die bestehende Meldung
   `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`.

## Konsequenzen

- Eine unveränderte Spalte mit Zahlen- oder (SQLite) `boolean`-Default erzeugt keine Operation
  mehr; `--execute` mit einer additiven Änderung daneben endet mit Exit 0, der zweite Lauf ist leer.
  Ein anderer Wert, ein hinzugefügter und ein entfernter Default bleiben `AlterColumnDefault`.
- Der Exit 8 bei einer **echten** `boolean`-/`decimal`-Änderung (`SQLITE_CAST_NOT_WHITELISTED`) ist
  hier nicht behandelt; er gehört zum Neubau-CAST (eigener Plan).
- **Grenze:** eine Ganzzahl über der Genauigkeit von `double` (`99999999999999999999`) liest SQLite
  als `Double` (`1.0E20`); sie ist nicht numerisch gleich der Datei-Zahl und bleibt ein Unterschied.
  Ein PostgreSQL-`bigint` mit negativem oder großem Default liest der PostgreSQL-Reader als
  `StringLiteral` (`'-1'::bigint`); auch das ist hier nicht entschieden.

## Weitere Informationen

Setzt [ADR 0057](0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md) (was beide Seiten
gleich meinen, ist kein Unterschied) für den Spalten-Default fort und führt den Fingerabdruck-Bump
von [ADR 0062](0062-sqlite-eindeutiger-index-bleibt-index.md) und
[ADR 0061](0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md) fort
([ADR 0026](0026-fingerprint-kanonisierung-post-compare.md): Abdruck/Post-Compare). Plan:
[`sqlite-default-vergleich.md`](../planning/in-progress/sqlite-default-vergleich.md).
