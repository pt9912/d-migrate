# Domain: `DEFAULT`, `NOT NULL` und Kollation im Modell (Folge von #52)

> **Status:** Draft mit Scope (2026-10-25). Abgetrennt aus dem früheren Sammelplan. Reine Modell- und Spec-Frage:
> `CustomTypeDefinition` kennt weder `DEFAULT` noch `NOT NULL` noch eine Kollation einer Domain; eine Änderung daran ist im Diff
> unsichtbar. Der Plan ist eine **Entscheidungs-/Messvorlage** (P0 misst, P1 entscheidet), noch keine Umsetzung. **Vorbedingung:** Entscheidung, ob das Modell erweitert wird (P1). **Aktivierung** (Move nach `../in-progress/`)
> beim ersten Implementierungs-Commit. GitHub-Issue: [#59](https://github.com/pt9912/d-migrate/issues/59).

## Ziel

Entscheiden, ob und wie eine Domain `DEFAULT`, `NOT NULL` und Kollation im neutralen Modell trägt — oder dass sie bewusst nicht
getragen werden — und den Vertrag dafür festzuschreiben.

## Problem

* `CustomTypeDefinition` ([Modell](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/CustomTypeDefinition.kt)) hat
  `kind`, `values`, `fields`, `baseType`, `precision`, `scale`, `check`, `description`. Der PostgreSQL-Reader selektiert
  `domain_default` ([`PostgresTypeMetadataQueries`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMetadataQueries.kt)),
  verwendet es aber nirgends. `NOT NULL` (`pg_type.typnotnull`) und die Kollation (`typcollation` abweichend vom Basistyp)
  liest er nicht. `domain_default` kommt als Katalogtext (Literal, ggf. mit Cast `'x'::text`) und bräuchte eine Normalisierung (P1-Frage: `ColumnDefaultProjection`, ADR 0063, wiederverwenden statt eines Domain-eigenen Wegs; Rahmen ADR 0061), bevor es mit einer Autorenform vergleichbar ist.
* **`AlterCustomType` ist auf PostgreSQL UNSUPPORTED** (blockiert Exit 8, `DIALECT_UNSUPPORTED_OPERATION`); es gibt keinen
  `ALTER DOMAIN`-Renderer. Auch bei A bliebe eine Änderung blockierend, solange der Renderer fehlt.
* Folge: `ALTER DOMAIN … SET DEFAULT/SET NOT NULL` ist für `schema migrate` unsichtbar; `schema generate` einer gelesenen Domain
  verliert beides.

## Richtung (nach P1)

* **A — Modell erweitern:** `default`/`not_null`/`collation` an `CustomTypeDefinition`. Vertragsänderung quer durch
  **`spec/schema-reference.md` (zuerst)**, `spec/schema.json`, Datei-Parser/-Writer, Vergleich, Fingerabdruck
  (`CanonicalPayload`, `MigrationFingerprint`), alle Dialekte mit Domains (PostgreSQL nativ; MSSQL/MySQL/SQLite rendern Basistyp
  + CHECK inline — dort ist `NOT NULL`/`DEFAULT` an der Spalte eine andere Sache), Renderer, Reverse. Neuer Fingerabdruck-Tag (aktuell `v21`, nächster freier `v22`) und ADR (nächste freie, heute 0064) —
  koordiniert mit `postgres-domain-mehrfach-check.md` und `postgres-domain-laenge-array.md` (ein gemeinsamer Bump möglich).
  Vollständige Dateiliste (zusätzlich `PostgresTypeMetadataQueries`: `NOT NULL` braucht `pg_type.typnotnull`, die Kollation `typcollation`; Reader-Fixtures `PostgresSchemaReaderTest` mit `domain_default` ziehen mit): `spec/schema-reference.md` (zuerst) → `spec/schema.json` → Modell/Parser/Writer → Vergleich → Abdruck →
  Renderer/Reverse **inkl. Oracle** und aller Dialekte mit Domains → Code → Handbuch (Reihenfolge Spec → Code → Handbuch).
* **A+ — Modell erweitern und `ALTER DOMAIN … SET DEFAULT/SET NOT NULL` rendern** (hebt die Blockade).
* **A-PG — nur PostgreSQL:** Felder am Modell, Auswertung nur im PostgreSQL-Reader/-Vergleich; die anderen Dialekte ignorieren sie.
* **Read-only:** der Reader liest `DEFAULT`/`NOT NULL`/Kollation, der Vergleich meldet sie nur (Notiz/Warnung), blockiert nicht.
* **B — Bewusste Grenze:** das Modell bleibt; Handbuch und Spec nennen, dass `DEFAULT`/`NOT NULL`/Kollation einer Domain nicht
  verglichen werden. Kein Code. Reicht, wenn kein Anwender es braucht.
* **B+ — Grenze mit Hinweis:** wie B, aber der Reader meldet eine `SchemaReadNote`, wenn die Domain `DEFAULT`/`NOT NULL`/Kollation
  trägt, damit die Grenze nicht still bleibt.

## Phasen

P0 Messung (die Entscheidung hängt an ihr; PostgreSQL: Domain mit `DEFAULT`, `NOT NULL`, Kollation; was liest der Reader, was rendert `schema generate`, Verhalten
von `schema migrate` bei einer Änderung daran; Anwenderbedarf: gibt es ein Issue? Kein Bedarf → B oder B+ ohne weitere Messung), P1 Wahl unter A/A+/A-PG/Read-only/B/B+ (Aufwand in
Dateien/Dialekten schätzen; ADR), P2 Umsetzung nach P1, P3 Doku (Spec zuerst).

Bei A/A+/A-PG gehört in P2 auch eine Erweiterung des Cross-Dialect-Matrix-Tests (`test/cross-dialect-matrix`) um Domains mit `DEFAULT`/`NOT NULL`/Kollation (Fixture zuerst, rot ohne die Änderung).

## Akzeptanz

* P1 ist entschieden und im Plan begründet; der P0-Messbefund steht im Plan (Tabelle: Fall · Reader-Ausgabe · `schema generate` · `schema migrate`). Bei B+: Test, dass die `SchemaReadNote` erscheint. Bei A+: Renderer-Test für `ALTER DOMAIN`. Bei Read-only/A-PG: Test, dass andere Dialekte unberührt bleiben. Bei B: Handbuch und Spec benennen die Grenze. Bei A: jede Dimension (Parser, Vergleich,
  Abdruck, Renderer je Dialekt, Reverse) hat einen Test, der ohne die Änderung rot wird; `make docker-test`, `docker-check`
  (ganzes Repo), `make integration` der Dialekt-Module mit Domains, `solid-suppression-gate`, `docs-check`, `doc-immutable`.
