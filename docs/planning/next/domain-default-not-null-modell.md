# Domain: `DEFAULT`, `NOT NULL` und Kollation im Modell (Folge von #52)

> **Status:** Draft mit Scope (2026-10-26). Abgetrennt aus dem früheren Sammelplan. Reine Modell- und Spec-Frage:
> `CustomTypeDefinition` kennt weder `DEFAULT` noch `NOT NULL` noch eine Kollation einer Domain; eine Änderung daran ist im Diff
> unsichtbar. **Vorbedingung:** Entscheidung, ob das Modell erweitert wird (P1). **Aktivierung** (Move nach `../in-progress/`)
> beim ersten Implementierungs-Commit; dann GitHub-Issue anlegen und hier verlinken.

## Ziel

Entscheiden, ob und wie eine Domain `DEFAULT`, `NOT NULL` und Kollation im neutralen Modell trägt — oder dass sie bewusst nicht
getragen werden — und den Vertrag dafür festzuschreiben.

## Problem

* `CustomTypeDefinition` ([Modell](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/CustomTypeDefinition.kt)) hat
  `kind`, `values`, `fields`, `baseType`, `precision`, `scale`, `check`, `description`. Der PostgreSQL-Reader selektiert
  `domain_default` ([`PostgresTypeMetadataQueries`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMetadataQueries.kt)),
  verwendet es aber nirgends. `NOT NULL` (`pg_type.typnotnull`) und die Kollation (`typcollation` abweichend vom Basistyp)
  liest er nicht.
* Folge: `ALTER DOMAIN … SET DEFAULT/SET NOT NULL` ist für `schema migrate` unsichtbar; `schema generate` einer gelesenen Domain
  verliert beides.

## Richtung (nach P1)

* **A — Modell erweitern:** `default`/`not_null`/`collation` an `CustomTypeDefinition`. Vertragsänderung quer durch
  **`spec/schema-reference.md` (zuerst)**, `spec/schema.json`, Datei-Parser/-Writer, Vergleich, Fingerabdruck
  (`CanonicalPayload`, `MigrationFingerprint`), alle Dialekte mit Domains (PostgreSQL nativ; MSSQL/MySQL/SQLite rendern Basistyp
  + CHECK inline — dort ist `NOT NULL`/`DEFAULT` an der Spalte eine andere Sache), Renderer, Reverse. Neuer Fingerabdruck-Tag und ADR.
* **B — Bewusste Grenze:** das Modell bleibt; Handbuch und Spec nennen, dass `DEFAULT`/`NOT NULL`/Kollation einer Domain nicht
  verglichen werden. Kein Code. Reicht, wenn kein Anwender es braucht.

## Phasen

P0 Messung (PostgreSQL: Domain mit `DEFAULT`, `NOT NULL`, Kollation; was liest der Reader, was rendert `schema generate`, Verhalten
von `schema migrate` bei einer Änderung daran; Anwenderbedarf: gibt es ein Issue?), P1 Entscheidung A oder B (Aufwand in
Dateien/Dialekten schätzen; ADR), P2 Umsetzung nach P1, P3 Doku (Spec zuerst).

## Akzeptanz

* P1 ist entschieden und im Plan begründet. Bei B: Handbuch und Spec benennen die Grenze. Bei A: jede Dimension (Parser, Vergleich,
  Abdruck, Renderer je Dialekt, Reverse) hat einen Test, der ohne die Änderung rot wird; `make docker-test`, `docker-check`
  (ganzes Repo), `make integration` der Dialekt-Module mit Domains, `solid-suppression-gate`, `docs-check`, `doc-immutable`.
