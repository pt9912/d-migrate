# Domain: `DEFAULT`, `NOT NULL` und Kollation im Modell (Folge von #52)

> **Status:** Draft mit Scope (2026-10-25); P0 gemessen, P1-Empfehlung B+ geschrieben, Wahl offen. Abgetrennt aus dem früheren Sammelplan. Reine Modell- und Spec-Frage:
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

## P0 Messbefund (2026-10-25, PostgreSQL-Image aus `TestImages.POSTGRESQL`)

Gemessen mit einer Wegwerf-Integrationsprobe (Testcontainers, `PostgresSchemaReader`, `PostgresDdlGenerator`,
`SchemaMigrateRunner` mit `PostgresDiffDdlGenerator`, `execute = false`; die Probe ist nicht eingecheckt). Domains:
`d_def` (`integer DEFAULT 5 CHECK (VALUE > 0)`), `d_nn` (`integer NOT NULL`), `d_col` (`text COLLATE "C"`),
`d_txt` (`text DEFAULT 'x'`), `d_all` (`text COLLATE "C" NOT NULL DEFAULT 'x' CHECK (length(VALUE) > 0)`), `d_plain`
(`integer`), je eine Tabellenspalte pro Domain.

| Fall | Reader-Ausgabe | `schema generate` | `schema migrate` |
| ---- | -------------- | ----------------- | ---------------- |
| `DEFAULT` an der Domain (`d_def`, `d_txt`) | `CustomTypeDefinition` ohne Default; `SchemaReadNote` keine; `domain_default` wird selektiert, aber nicht verwendet. Katalog liefert `5` bzw. `'x'::text` | `CREATE DOMAIN … AS INTEGER CHECK (VALUE > 0)` — `DEFAULT` fehlt, still | `ALTER DOMAIN … SET/DROP DEFAULT` in der Datenbank: `customTypes` vorher/nachher gleich, keine Operation (unsichtbar) |
| `NOT NULL` an der Domain (`d_nn`, `d_all`) | Domain ohne `NOT NULL`; **aber** die Domain-Spalte kommt mit `required = true` (Spalten `b`, `f`) | Domain ohne `NOT NULL`; die Tabellenspalte bekommt stattdessen `NOT NULL` (Spaltenebene) | Änderung an der Domain wirkt **mittelbar** über `required` der Spalte: `ALTER DOMAIN d_nn DROP NOT NULL` → `ALTER TABLE "t" ALTER COLUMN "b" SET NOT NULL;`, `ALTER DOMAIN d_plain SET NOT NULL` → `ALTER TABLE "t" ALTER COLUMN "g" DROP NOT NULL;`. Beides ist die falsche Abhilfe (setzt eine Tabellen-Constraint, bzw. lässt die Domain-Constraint stehen), Exit 0, keine Blockade |
| Kollation an der Domain (`d_col`, `d_all`) | Nicht gelesen; Katalog: `typcollation` weicht vom Basistyp ab (`pg_type`, `coll = true`) | `COLLATE` fehlt, still | Unsichtbar (nicht im Modell) |
| Domain ohne alles (`d_plain`) | vollständig | vollständig | Zweiter Lauf ohne Operationen (`no_op`) |
| `ALTER DOMAIN` als Änderung durch Datei (Domain-Feld `default`/`not_null`/`collation`) | Das Modell kennt die Felder nicht; der Datei-Parser ignoriert unbekannte Schlüssel in `custom_types` ohne Meldung (`parseCustomTypes`); `spec/schema.json` verbietet sie nicht per `additionalProperties: false` | — | Eine Änderung an den Feldern in der Datei ist wirkungslos; auch mit Modell-Erweiterung bliebe `AlterCustomType` auf PostgreSQL blockierend (Exit 8), solange der `ALTER DOMAIN`-Renderer fehlt |

Weitere Befunde:

* `domain_default` kommt als Katalogtext (`5`, `'x'::text`), muss also normalisiert werden, bevor es mit einer Autorenform
  vergleichbar ist (`ColumnDefaultProjection`, ADR 0063).
* Das Handbuch (`docs/user/anwenderhandbuch.md`, Abschnitt zur Domain im Vergleich) nennt bereits: „Auch `DEFAULT` und `NOT NULL`
  einer Domain vergleicht es nicht." Die Kollation und der Effekt auf `required` der Spalte nennt es nicht.
* Anwenderbedarf: außer diesem Folge-Issue (#59, aus der Prüfung von #52) gibt es kein Issue und keine Anwenderanfrage, die
  `DEFAULT`/`NOT NULL`/Kollation einer Domain verlangt. Der Plan sieht für „kein Bedarf" B oder B+ ohne weitere Messung vor.
* Neu gegenüber dem Planentwurf: die Domain-`NOT NULL`-Änderung ist **nicht** vollständig unsichtbar, sondern erscheint als
  irreführende Spaltenoperation (Zeile 2 der Tabelle). Das ist ein eigener Defekt am Reader (die Domain-Eigenschaft wird in
  `required` der Spalte eingerechnet) und wird durch B/B+ nicht behoben, wohl aber durch A/A-PG (Domain trägt `NOT NULL`, die Spalte
  nicht) — siehe Empfehlung.

## P1 Empfehlung (Wahl offen, wartet auf Bestätigung)

Aufwandsschätzung (Anzahl Quelldateien in `src/main`, aus `grep` nach `CustomTypeDefinition`/`customTypes`; Tests und Doku
kommen je Option hinzu):

| Option | Inhalt | Dateien/Dialekte | Fingerabdruck/ADR |
| ------ | ------ | ---------------- | ----------------- |
| B | Nur Spec und Handbuch nennen die Grenze (inkl. Kollation und `required`-Effekt) | 0 Code; 3 Doku-Dateien | kein Bump, kein ADR |
| **B+** | B, dazu `SchemaReadNote`, wenn die Domain `DEFAULT`/`NOT NULL`/Kollation trägt | ca. 2 Code-Dateien (`PostgresTypeMetadataQueries` für `typnotnull`/`typcollation`, `PostgresSchemaStructureReaders`) + 2 Tests (Reader-Unit, Integration) + 3 Doku-Dateien; 1 Dialekt | kein Bump, kein ADR |
| Read-only | Reader liest alle drei, Vergleich meldet nur (Notiz), blockiert nicht | wie B+ plus Meldung im Vergleichs-/Report-Pfad: ca. 5–7 Code-Dateien; Modell bräuchte Felder oder einen Seitenkanal | Bump nur, wenn die Felder im Modell landen |
| A-PG | Felder am Modell; Auswertung nur PostgreSQL | Modell + Parser + `schema.json` + Vergleich + Abdruck (core/formats, ca. 8–10 Dateien) + PostgreSQL (Reader, Renderer, ca. 4 Dateien); andere Dialekte bleiben unberührt, müssen aber mit den Feldern leben (Abdruck!) | neuer Tag `v22` (nächster freier ist koordiniert vorab vergeben, siehe unten) + ADR |
| A | A-PG, dazu Renderer/Reverse aller Dialekte mit Domains | Modell/Core ca. 15 Dateien; PostgreSQL 5, MSSQL 5, MySQL 3, Oracle 4, SQLite 13, formats 4, cli 4 — Obergrenze ca. 50 Dateien in 5 Dialekt-Modulen | wie A-PG |
| A+ | A, dazu `ALTER DOMAIN … SET DEFAULT/SET NOT NULL` (hebt die Blockade, `DROP DEFAULT`/`DROP NOT NULL`/Kollation `ALTER` gehört dazu) | A plus neuer Renderer-Pfad in `PostgresDiffDdlGenerator` (ca. 2–3 Dateien) | wie A-PG |

**Empfehlung: B+**, mit zwei benannten Folgeentscheidungen.

Begründung:

1. Es gibt keinen Anwenderbedarf (P0); A/A+/A-PG verlangen einen Vertragswechsel (Spec, Schema, Parser, Writer, Abdruck, neuer
   Tag, ADR, Cross-Dialect-Matrix) für ein Merkmal, das niemand angefragt hat. Der Plan selbst nennt B/B+ als Weg bei „kein Bedarf".
2. Die Lücke ist heute still: `schema generate` einer gelesenen Domain lässt `DEFAULT`/`NOT NULL`/Kollation weg, ohne dass es
   jemand erfährt. B ließe das beim Handbuchhinweis; B+ macht die Grenze am Gegenstand sichtbar, sobald sie zutrifft. Der Aufwand
   ist klein (ein Dialekt, eine Abfrage, kein Fingerabdruck).
3. B+ ist umkehrbar: wer später A-PG oder A will, liest `typnotnull`/`typcollation`/`domain_default` ohnehin; die Abfragen aus B+
   wandern mit.
4. Ein Bump von `v21` auf `v22` für diesen Plan würde den koordinierten Bump von #55 (`v22`, ADR 0064) und #58 (`v23`, ADR 0065)
   verschieben; B+ lässt diese Reihenfolge unberührt.

Folgeentscheidungen, die B+ **nicht** löst (zur Bestätigung):

* **`required` der Domain-Spalte (Befund 2):** der Reader rechnet die Domain-`NOT NULL` in `required` der Spalte ein, daraus
  entstehen die falschen `ALTER TABLE … SET/DROP NOT NULL`. Entweder eigener Defekt-Issue (Reader soll `required` nur aus
  `attnotnull` der Spalte bilden; ändert den Vergleich bestehender Domain-Spalten, braucht Matrix-/Integrationstest), oder
  über A/A-PG mit erledigt. Dieser Punkt allein spricht dafür, bei Anwenderbedarf direkt A-PG statt B+ zu nehmen.
* **Bei Wahl A-PG statt B+:** zusätzlich Abstimmung mit `postgres-domain-mehrfach-check.md` und `postgres-domain-laenge-array.md`
  (gemeinsamer Fingerabdruck-Tag möglich) und ein `ALTER DOMAIN`-Renderer (A+), sonst bleibt jede Änderung blockierend.

P2/P3 sind **nicht** begonnen; der Plan bleibt in `next/`.

## Akzeptanz

* P1 ist entschieden und im Plan begründet; der P0-Messbefund steht im Plan (Tabelle: Fall · Reader-Ausgabe · `schema generate` · `schema migrate`). Bei B+: Test, dass die `SchemaReadNote` erscheint. Bei A+: Renderer-Test für `ALTER DOMAIN`. Bei Read-only/A-PG: Test, dass andere Dialekte unberührt bleiben. Bei B: Handbuch und Spec benennen die Grenze. Bei A: jede Dimension (Parser, Vergleich,
  Abdruck, Renderer je Dialekt, Reverse) hat einen Test, der ohne die Änderung rot wird; `make docker-test`, `docker-check`
  (ganzes Repo), `make integration` der Dialekt-Module mit Domains, `solid-suppression-gate`, `docs-check`, `doc-immutable`.
