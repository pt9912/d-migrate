---
status: accepted
date: 2026-10-25
decision-makers: pt9912
consulted: docs/adr/0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md, docs/adr/0063-default-vergleich-zahlen-numerisch-boolean-auf-integer.md, docs/planning/in-progress/domain-default-not-null-modell.md
informed: hexagon/core (CustomTypeDefinition, SchemaComparator, MigrationFingerprint, CanonicalPayload), adapters/driven/driver-postgresql, adapters/driven/formats, spec/schema-reference.md, spec/schema.json, spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# `DEFAULT`, `NOT NULL` und Kollation einer Domain im Modell — nur PostgreSQL wertet aus, `schema-fingerprint-v24`

> **Status: accepted (2026-10-25).** `CustomTypeDefinition` trägt `default`, `notNull` (Datei: `not_null`)
> und `collation`. Nur PostgreSQL liest, rendert und vergleicht sie; die anderen Dialekte ignorieren
> sie. `required` einer Domain-Spalte kommt aus `pg_attribute.attnotnull`. Der Algorithmus-Tag steigt
> auf `v24`.

## Kontext und Problemstellung

Eine PostgreSQL-Domain kann `DEFAULT`, `NOT NULL` und eine abweichende Kollation tragen. Das Modell kannte
sie nicht (Issue #59, Folge von #52). Gemessen (Plan, P0): `schema generate` einer gelesenen Domain ließ alle
drei still weg; `schema migrate` sah eine Änderung an `DEFAULT` und Kollation nicht. Das `NOT NULL` einer
Domain erschien als **falsche Spaltenoperation**: der Reader bildete `required` der Spalte aus
`information_schema.columns.is_nullable`, das die Domain-`NOT NULL` einrechnet, und `ALTER DOMAIN … DROP NOT
NULL` wurde so zu `ALTER TABLE … ALTER COLUMN … SET NOT NULL`.

## Betrachtete Optionen

- **B/B+ — Grenze nennen**, optional mit Reader-Notiz: kein Vertragswechsel, behebt den `required`-Defekt nicht.
- **A-PG — Felder am Modell, nur PostgreSQL wertet aus.**
- **A — dazu Renderer/Reverse aller Dialekte** mit Domains: MSSQL, MySQL, SQLite und Oracle haben keine
  Domain; sie rendern Basistyp + `CHECK` an der Spalte. Dort steuert die Spalte `NOT NULL`/`DEFAULT` selbst.
- **A+ — dazu `ALTER DOMAIN`-Renderer.**

## Entscheidung

**A-PG** (Entscheidung des Nutzers; die Messung hatte B+ empfohlen).

1. **Modell und Datei.** `default: DefaultValue?`, `notNull: Boolean`, `collation: String?` an
   `CustomTypeDefinition`; YAML-Schlüssel `default`, `not_null`, `collation` an `kind: domain`. Der Default hat die
   Formen des Spalten-`default`. Writer schreiben die Felder nur, wenn sie gesetzt sind.
2. **Vergleich.** Default über `ColumnDefaultProjection.same` ([ADR 0063](0063-default-vergleich-zahlen-numerisch-boolean-auf-integer.md)),
   `notNull` als Wahrheitswert, Kollation ohne Anführungszeichen und mit Groß-/Kleinschreibung. Drei neue Felder in
   `CustomTypeDiff`.
3. **PostgreSQL.** Reader: `typnotnull`, `typcollation` nur bei Abweichung vom Basistyp (außerhalb von
   `pg_catalog` als `schema.name`), `domain_default` durch dieselbe Normalisierung wie ein Spalten-Default (inkl.
   `nextval` als `SequenceNextVal`). Renderer: `CREATE DOMAIN … AS t COLLATE … DEFAULT … NOT NULL CHECK (…)`.
4. **`required` aus `attnotnull`.** Die Spalte trägt nur ihr eigenes `NOT NULL`; die Domain-Eigenschaft steht an der
   Domain. Das behebt die falsche Spaltenoperation.
5. **Andere Dialekte ignorieren die Felder.** MSSQL, MySQL, SQLite und Oracle rendern unverändert (Regressionstest je
   Dialekt). Ihr Abdruck hasht benannte Typen nicht (`supportsCustomTypes = false`), braucht also keine Faltung.
6. **Fingerabdruck.** `ALGORITHM` steigt auf `schema-fingerprint-v24`. `default=`, `not_null=`, `collation=` stehen im
   Abdruck und in der Operations-Nutzlast (`CanonicalPayload`) nur, wenn gesetzt; ein Typ ohne sie hasht wie zuvor,
   seine Operations-ID bleibt. Der Wechsel trifft ein Schema mit Domain-`NOT NULL` (die Domain trägt es jetzt, die
   Spalte nicht mehr) und jedes mit einer Domain mit `DEFAULT`/Kollation. Ein Artefakt vor `v24` liefert die
   bestehende Meldung `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`.
7. **Kein `ALTER DOMAIN`-Renderer (A+ nicht mitgeliefert).** `CreateCustomType`/`DropCustomType` einer Domain sind auf
   PostgreSQL ebenfalls blockiert; `AlterCustomType` hat `MANUAL_REQUIRED` und kein Down; `SET NOT NULL` kann an
   vorhandenen Daten scheitern. Eine Änderung an einem der drei Felder wird dadurch **sichtbar** und endet in Exit 8
   (`DIALECT_UNSUPPORTED_OPERATION`), statt still oder als falsche Spaltenoperation. Ein `ALTER DOMAIN`-Renderer gehört
   zusammen mit Domain-Create/Drop in einen eigenen Plan; die Änderung der Kollation bleibt auch dann blockierend
   (`ALTER DOMAIN` kennt kein `COLLATE`).

## Konsequenzen

- `schema generate` einer gelesenen Domain behält `DEFAULT`, `NOT NULL` und Kollation; `schema reverse` schreibt
  `default`, `not_null`, `collation`.
- Eine Spalte vom Typ einer Domain mit `NOT NULL` kommt ohne `required: true` zurück. Eine Datei, die `required: true`
  an dieser Spalte schreibt, erzeugt ein explizites `NOT NULL` an der Spalte und gilt dann als gleich.
- Eine Datei, die `default`/`not_null`/`collation` an einer Domain ändert, blockiert gegen PostgreSQL (Exit 8), bis die
  Datenbank von Hand angeglichen ist.
- Grenze: eine Domain-Kollation `default` (dieselbe wie die des Basistyps) ist vom Fehlen nicht zu unterscheiden.

## Weitere Informationen

Führt [ADR 0061](0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md) und
[ADR 0063](0063-default-vergleich-zahlen-numerisch-boolean-auf-integer.md) fort
([ADR 0026](0026-fingerprint-kanonisierung-post-compare.md): Abdruck/Post-Compare). Plan:
[`domain-default-not-null-modell.md`](../planning/in-progress/domain-default-not-null-modell.md).
