---
status: accepted
date: 2026-10-25
decision-makers: pt9912
consulted: docs/adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md, docs/adr/0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md, docs/planning/in-progress/postgres-domain-laenge-array.md
informed: hexagon/core (CustomTypeBaseType, SchemaComparator, CanonicalPayload, MigrationFingerprint), adapters/driven/driver-postgresql (PostgresTypeMetadataQueries, PostgresDomainBaseType, PostgresSchemaStructureReaders), spec/ddl-generation-rules.md, spec/schema-reference.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# Der Basistyp einer Domain trägt Länge und Array aus dem Katalog — Neutralform mit Klammer und `[]`, `schema-fingerprint-v23`

> **Status: accepted (2026-10-25).** Der PostgreSQL-Reader liest den Basistyp einer Domain aus
> `pg_catalog` (`format_type`) und gibt ihn mit Länge und Array zurück (`varchar(50)`, `char(5)`,
> `integer[]`). Die Neutralform des Basistyps trägt die Länge bei `varchar`, `char`, `timestamp`,
> `timestamptz`, `time`, `timetz`, `bit`, `varbit` und ein `[]` bei einem Array. Das ergänzt
> [ADR 0061](0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md)
> (Entscheidung 3 und 4); der Algorithmus-Tag steigt auf `v23`.

## Kontext und Problemstellung

ADR 0061 ließ zwei Lücken offen: den Basistyp `varchar(n)`/`char(n)`, dessen Länge der Reader nicht
las, und die Array-Domain. Gemessen (PostgreSQL 18, 26 Domains):

- Der Reader las `information_schema.domains.numeric_*`, nicht `character_maximum_length`.
  `varchar(50)` und `char(5)` wurden beide `text`; `varchar(50)` → `varchar(254)` war gegen eine
  Datenbank unsichtbar, `char` und `varchar` nicht unterscheidbar.
- Der Basistyp einer Array-Domain war der interne Typname `_int4`; die Datei schreibt `integer[]`.
  Die Domain galt als geändert, `schema migrate` endete mit Exit 8.
- `timestamp(3)`, `time(3)` und `bit(n)` verloren ihren Modifikator.
- `information_schema.domains` ist rechteabhängig: ohne `USAGE` an der Domain liefert die Sicht keine
  Zeile (gemessen: `NULL` für Länge und Genauigkeit). `format_type` aus `pg_catalog` nennt dieselben
  Werte ohne diese Abhängigkeit.

## Betrachtete Optionen

1. **Quelle `information_schema` beibehalten, `character_maximum_length` ergänzen** — verworfen:
   weiter rechteabhängig, und der Array-Basistyp bliebe `_int4`.
2. **`PostgresTypeMapping.mapParamType` um Länge und Array erweitern** — verworfen: die Routinen-Reader
   benutzen es für Parameter- und Rückgabetypen; deren Abdruck würde sich verschieben.
3. **`format_type(typbasetype, typtypmod)` und eine eigene Abbildung für Domains** — gewählt.
4. **Länge nur vergleichen, wenn beide Seiten sie nennen (ADR 0061 Entscheidung 4 unverändert)** —
   verworfen: der Reader nennt sie jetzt; `varchar(50)` gegen `varchar` ohne Länge (unbegrenzt) wäre
   sonst unbemerkt gleich.

## Entscheidung

1. **Quelle `format_type`.** `listDomainTypes` liest `format_type(typbasetype, typtypmod)` aus
   `pg_catalog` und verbindet `information_schema` nicht mehr. Liegt der Basistyp außerhalb von
   `pg_catalog` (eine andere Domain, ein Erweiterungstyp), bleibt der unqualifizierte Typname: `format_type`
   qualifiziert ihn je nach `search_path`.
2. **Eigene Abbildung** (`PostgresDomainBaseType`), `mapParamType` bleibt unberührt. Länge und
   Genauigkeit bleiben in der Klammer, ein Array trägt `[]`:
   `character varying(50)` → `varchar(50)`, `character(5)` → `char(5)`, `integer[]` → `integer[]`,
   `timestamp(3) with time zone` → `timestamptz(3)`, `bit varying(7)` → `varbit(7)`. `numeric(p,s)` ohne
   Array bleibt `decimal` mit `precision`/`scale` in den Feldern (ADR 0061 Entscheidung 3, nicht beides);
   mit Array wird es `decimal(p,s)[]`, die Felder bleiben leer.
3. **Neutralform** (`CustomTypeBaseType.canonical`): Typen mit Länge tragen sie in der Klammer.
   Ohne Klammer gilt der Standard des Typs: `varchar` ist unbegrenzt und damit `text`, `char` ist
   `char(1)`, `timestamp`/`timestamptz`/`time`/`timetz` sind `(6)`, `bit` ist `bit(1)`, `varbit` ohne Länge
   bleibt `varbit`. `char` und `varchar`
   sind verschiedene Typen. Ein Suffix `[]` (auch `[][]`, `[3]`) wird zu einem `[]` am Typnamen; der
   Katalog unterscheidet Dimensionen nicht.
4. **`sameParameters` gilt für alle Dialekte und wird nicht nach Reader gefasst.** Für Typen mit
   Länge steht die Klammer in der Neutralform; dort ist `varchar(50)` gegen `varchar` eine
   Änderung. Für jeden anderen Typ (`integer(11)`) bleibt es wie in ADR 0061: verglichen wird nur,
   wenn beide Seiten die Klammer nennen. Welche Dialekte betroffen sind, ist gemessen (siehe
   Konsequenzen); `SchemaComparator.sameBaseType`, `CanonicalPayload.customType` und
   `MigrationFingerprint.appendCustomTypes` nutzen dieselbe Funktion.
5. **Fingerabdruck-Bump.** `ALGORITHM` steigt von `schema-fingerprint-v21` auf `v23` (`v22` ist für den
   Slice zu #55 vorgesehen, ADR 0064 ebenso; die Auslieferung führt die Tags der Slices in dieser Reihenfolge zusammen). `base=` einer Domain trägt jetzt Länge und `[]`. Schemas ohne Domain und
   Domains über `integer`, `text`, `decimal` hashen bis auf den Tag wie zuvor.
6. **Bestandsdateien.** Eine `schema reverse`-Datei aus einer früheren Version führt `text` für eine
   `varchar(50)`-Domain. Das ist nicht derselbe Typ; `schema migrate` plant `AlterCustomType` und
   blockiert mit Exit 8. Es gibt keine stille Übergangsregel, denn sie machte `text` gegen
   `varchar(50)` zu einem unerkannten Unterschied. Abhilfe: `schema reverse` neu ziehen.

## Konsequenzen

- `schema migrate` gegen PostgreSQL führt eine additive Änderung auch dann aus, wenn die Datei eine
  `varchar(n)`-, `char(n)`-, `timestamp(n)`- oder Array-Domain in Autorenform schreibt; der Folgelauf
  ist ohne Drift. Eine echte Änderung der Länge, des Array-Typs oder von `char` ↔ `varchar` blockiert
  weiter (Exit 8, `AlterCustomType` ist auf PostgreSQL nicht gerendert).
- Sichtbar ändert sich `schema reverse`: `varchar(50)`, `char(5)`, `timestamp(3)` und `integer[]`
  stehen statt `text`, `text`, `timestamp` und `_int4` in `base_type`. Die erzeugte PostgreSQL-DDL
  trifft damit den Basistyp der Datenbank.
- Andere Dialekte: kein Reader außer dem PostgreSQL-Reader liefert Domains. `schema generate` löst den
  Basistyp wie bisher über `DomainTypes` auf; jede Länge, die der Reader schreibt, ergibt einen
  neutralen Typ, ein Array-Basistyp bleibt auf SQLite und MSSQL E053 (vorher `_int4`, ebenfalls ohne
  Gegenstück); Oracle schreibt jede Domain als CLOB mit E053. **MySQL löst den Basistyp nicht auf**,
  sondern gibt `base_type` roh in die Spalte aus: `varchar(50)`, `char(5)`, `timestamp(3)` sind dort gültig
  (vorher `text`), ein Array, `timestamptz(3)`, `timetz` und `varbit(7)` ergeben ungültiges DDL ohne
  Notiz — wie zuvor mit `_int4`/`timestamptz`. Das ist eine dokumentierte Grenze, kein Teil dieser
  Entscheidung. Verändert hat sich sonst nur `schema compare` zwischen zwei Dateien: `varchar` gegen
  `varchar(50)` ist dort jetzt eine Änderung.
- Vorgesehen ist, dass `v23` auch die Reader-Änderungen am `CHECK` einer Domain trägt, die der Slice zu #57
  liefert (Aggregation mehrerer `CHECK`-Constraints, Prädikat eines `NOT VALID`-Constraints): der
  Reader-Ausgang einer Domain ändert sich dort ebenfalls, und die Auslieferung fasst beides unter `v23` und
  diesem ADR zusammen, statt einen weiteren Tag zu vergeben.
- Ein Rollback- oder Plan-Artefakt vor `v23` ist neu zu erzeugen.
- Weiter nicht erkannt: mehrdimensionale Arrays (`integer[][]` gegen `integer[]`), `DEFAULT`/`NOT NULL`
  einer Domain, mehrere `CHECK`-Constraints.

## Weitere Informationen

Ergänzt [ADR 0061](0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md)
Entscheidung 3 und 4. Plan:
[`postgres-domain-laenge-array.md`](../planning/in-progress/postgres-domain-laenge-array.md).
