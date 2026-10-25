# Domain: `DEFAULT`, `NOT NULL` und Kollation im Modell (Folge von #52)

> **Status:** In Progress (2026-10-25); P0 gemessen, P1 entschieden (**A-PG**, Nutzerentscheidung), P2/P3 in Arbeit. Reine Modell- und Spec-Frage:
> `CustomTypeDefinition` kennt weder `DEFAULT` noch `NOT NULL` noch eine Kollation einer Domain; eine Änderung daran ist im Diff
> unsichtbar. GitHub-Issue: [#59](https://github.com/pt9912/d-migrate/issues/59).

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

## P1 Entscheidung: A-PG (Nutzerentscheidung, 2026-10-25)

Aufwandsschätzung vor der Wahl (Anzahl Quelldateien in `src/main`, aus `grep` nach `CustomTypeDefinition`/`customTypes`; Tests und Doku
kommen je Option hinzu):

| Option | Inhalt | Dateien/Dialekte | Fingerabdruck/ADR |
| ------ | ------ | ---------------- | ----------------- |
| B | Nur Spec und Handbuch nennen die Grenze (inkl. Kollation und `required`-Effekt) | 0 Code; 3 Doku-Dateien | kein Bump, kein ADR |
| **B+** | B, dazu `SchemaReadNote`, wenn die Domain `DEFAULT`/`NOT NULL`/Kollation trägt | ca. 2 Code-Dateien (`PostgresTypeMetadataQueries` für `typnotnull`/`typcollation`, `PostgresSchemaStructureReaders`) + 2 Tests (Reader-Unit, Integration) + 3 Doku-Dateien; 1 Dialekt | kein Bump, kein ADR |
| Read-only | Reader liest alle drei, Vergleich meldet nur (Notiz), blockiert nicht | wie B+ plus Meldung im Vergleichs-/Report-Pfad: ca. 5–7 Code-Dateien; Modell bräuchte Felder oder einen Seitenkanal | Bump nur, wenn die Felder im Modell landen |
| A-PG | Felder am Modell; Auswertung nur PostgreSQL | Modell + Parser + `schema.json` + Vergleich + Abdruck (core/formats, ca. 8–10 Dateien) + PostgreSQL (Reader, Renderer, ca. 4 Dateien); andere Dialekte bleiben unberührt, müssen aber mit den Feldern leben (Abdruck!) | neuer Tag `v22` (nächster freier ist koordiniert vorab vergeben, siehe unten) + ADR |
| A | A-PG, dazu Renderer/Reverse aller Dialekte mit Domains | Modell/Core ca. 15 Dateien; PostgreSQL 5, MSSQL 5, MySQL 3, Oracle 4, SQLite 13, formats 4, cli 4 — Obergrenze ca. 50 Dateien in 5 Dialekt-Modulen | wie A-PG |
| A+ | A, dazu `ALTER DOMAIN … SET DEFAULT/SET NOT NULL` (hebt die Blockade, `DROP DEFAULT`/`DROP NOT NULL`/Kollation `ALTER` gehört dazu) | A plus neuer Renderer-Pfad in `PostgresDiffDdlGenerator` (ca. 2–3 Dateien) | wie A-PG |

Die Empfehlung der Messung war B+ (kein Anwenderbedarf, kleiner Aufwand). Der Nutzer hat **A-PG** gewählt; der
`required`-Defekt (P0, Zeile 2) ist ein Grund dafür: nur wenn die Domain `NOT NULL` selbst trägt, kann die Spalte es lassen.

**Entscheidung A-PG:**

1. **Modell:** `CustomTypeDefinition` bekommt `default: DefaultValue?`, `notNull: Boolean` (Datei: `not_null`) und
   `collation: String?`. Sie gelten nur für `kind: domain`. Der Datei-Parser liest sie, der Writer schreibt sie nur, wenn
   sie gesetzt sind.
2. **Nur PostgreSQL wertet aus:** Reader (`typnotnull`, `typcollation` abweichend vom Basistyp, `domain_default` über
   `PostgresTypeMapping.parseDefault`, die Normalisierung der Spalten-Defaults), `CREATE DOMAIN`-Renderer und der Vergleich.
   MSSQL, MySQL, SQLite und Oracle rendern Basistyp + CHECK inline und ignorieren die Felder (Regressionstest je Dialekt). Ihr
   Abdruck sieht benannte Typen ohnehin nicht (`supportsCustomTypes = false`, `projectsCustomTypes`), sie brauchen also keine Faltung.
3. **Vergleich:** Default über `ColumnDefaultProjection.same` (ADR 0063), `notNull` als Wahrheitswert, Kollation ohne
   Anführungszeichen und mit Groß-/Kleinschreibung (Kollationsnamen unterscheiden sie). `CustomTypeDiff` trägt drei neue Felder.
4. **`required`-Defekt:** der Reader bildet `required` einer Spalte aus `pg_attribute.attnotnull`, nicht aus
   `information_schema.columns.is_nullable` (das die Domain-`NOT NULL` einrechnet). Die Domain-Eigenschaft steht an der Domain.
5. **Abdruck:** `schema-fingerprint-v24`, ADR 0066. Die Segmente `default=`, `not_null=`, `collation=` stehen nur, wenn
   gesetzt; Operations-IDs (`CanonicalPayload`) unbekannter Typen bleiben stabil. Der Reader-Ausgang einer Domain
   ändert sich (sie trägt jetzt die drei Felder), und `required` von Domain-Spalten sinkt: ein unverändertes Schema mit
   Domain-`NOT NULL` hasht anders.
6. **Keine Notiz `R408`.** Der Domain-`DEFAULT` geht durch dieselbe Normalisierung wie der Spalten-`DEFAULT` (Literal, bekannte
   Funktion, `nextval('s')` als `SequenceNextVal`, sonst `FunctionCall` im Katalogtext); es geht nichts still verloren, also
   gibt es nichts zu melden. Der vorab vergebene Code `R408` bleibt unbenutzt.
7. **`ALTER DOMAIN` (A+) wird nicht mitgeliefert — bewusst.** Gründe: (a) `CreateCustomType`/`DropCustomType` einer Domain sind
   auf PostgreSQL ebenfalls blockiert (`renderCreateCustomType` kennt nur Enums); ein `ALTER DOMAIN` allein wäre eine Insel im
   Typ-Pfad, ohne dass eine neue Domain oder das Abräumen einer alten migrierbar wäre. (b) `AlterCustomType` hat
   `MANUAL_REQUIRED` und kein Down; `SET NOT NULL` kann an vorhandenen Daten scheitern, `SET DEFAULT` ändert nichts an
   bestehenden Zeilen — beides braucht ein eigenes Risiko-/Rollback-Modell. (c) Der Zugewinn von A-PG ist, dass die Änderung
   **sichtbar** wird: statt still (Default, Kollation) oder als falsche Spaltenoperation (`NOT NULL`) endet `schema migrate`
   mit Exit 8 und `DIALECT_UNSUPPORTED_OPERATION`. Die Änderung der Kollation bleibt auch bei einem späteren A+ blockierend
   (`ALTER DOMAIN` kennt kein `COLLATE`). Ein `ALTER DOMAIN`-Renderer gehört in einen eigenen Plan zusammen mit dem
   Domain-Create/Drop.

Aufwand A-PG (ausgeführt): Modell, Vergleich, Abdruck/Payload, Parser/Writer, `schema.json`, PostgreSQL-Reader und -Renderer,
CLI-/MCP-Darstellung der Änderung; Spec (`schema-reference.md`, `schema.json`, `ddl-generation-rules.md`) zuerst, dann Code, dann Handbuch.

## P2 Umsetzung (A-PG)

* **Spec zuerst:** `spec/schema-reference.md` (Abschnitt Domain), `spec/schema.json` (`default`, `not_null`, `collation` an
  `customType`), `spec/ddl-generation-rules.md` §6.3.
* **Modell/Core:** `CustomTypeDefinition` (+3 Felder), `CustomTypeDiff` (+3), `DomainTraits` (Kollationsname), `SchemaComparator`
  (`ColumnDefaultProjection.same`), `TargetProjection.comparesDomainTraits` (vom Migrate-Pfad aus `supportsCustomTypes`;
  ohne ihn plante jeder Nicht-PostgreSQL-Dialekt bei einer Änderung an den Feldern einen sinnlosen `AlterCustomType` —
  auf SQLite einen Tabellen-Neubau), `MigrationFingerprint` (`v24`) und `CanonicalPayload`.
* **Formate:** Parser und Writer (`default` über die Spalten-Default-Formen).
* **PostgreSQL:** `PostgresTypeMetadataQueries` (`typnotnull`, `typcollation`), `PostgresTableMetadataQueries`
  (`attnotnull` als `column_not_null`), `PostgresSchemaStructureReaders` (`readDomainDefault`, `required`),
  `PostgresTypeSequenceDdlSupport` (`CREATE DOMAIN … COLLATE … DEFAULT … NOT NULL CHECK …`).
* **Darstellung:** CLI-`schema compare` und MCP-Funde nennen `default`, `not_null`, `collation`.
* **Nicht berührt:** MySQL-, SQLite-, SQL-Server- und Oracle-Renderer/-Reader; Regressionstest belegt gleiche Ausgabe mit und
  ohne die Felder.

**Matrix-Test-Punkt:** die Matrix (`test/cross-dialect-matrix`) führt `SchemaMigrateRunner` Datei gegen Datei, **ohne
Reader**; sie sieht `typnotnull`/`typcollation`/`domain_default` und das `required` der Spalte also nicht. Dafür gelten
`PostgresSchemaReaderDomainTraitsTest` (Reader-Unit) und `PostgresDomainTraitsIntegrationTest` (echter Katalog, inkl.
Round-Trip `generate` → Datenbank → `read`). Was die Matrix sieht, steht in `DomainTraitsMatrixTest` (Fixture
`H.domain-traits`, außerhalb des Workstream-Katalogs des Sweeps): PostgreSQL Exit 8 bei einer Änderung, Exit 0 bei gleicher
Datei; MySQL, SQLite, SQL Server, Oracle Exit 0; `schema generate` je Dialekt gleich mit und ohne die Felder.

Zusätzlich je Dimension ein Test, der ohne die Änderung rot wird (jeweils mit einer absichtlich falschen Zusicherung als
laufend belegt): `SchemaComparatorDomainTraitsTest`, `MigrationFingerprintDomainTraitsTest`, `SchemaNodeDomainTraitsTest`,
`PostgresDdlGeneratorDomainTraitsTest`, `PostgresSchemaReaderDomainTraitsTest`.

## P3 Doku

`docs/user/anwenderhandbuch.md` (Domain im Vergleich), `CHANGELOG.md`, ADR 0066.

## Akzeptanz

* P1 ist entschieden und im Plan begründet; der P0-Messbefund steht im Plan (Tabelle: Fall · Reader-Ausgabe · `schema generate` · `schema migrate`). Bei B+: Test, dass die `SchemaReadNote` erscheint. Bei A+: Renderer-Test für `ALTER DOMAIN`. Bei Read-only/A-PG: Test, dass andere Dialekte unberührt bleiben. Bei B: Handbuch und Spec benennen die Grenze. Bei A: jede Dimension (Parser, Vergleich,
  Abdruck, Renderer je Dialekt, Reverse) hat einen Test, der ohne die Änderung rot wird; `make docker-test`, `docker-check`
  (ganzes Repo), `make integration` der Dialekt-Module mit Domains, `solid-suppression-gate`, `docs-check`, `doc-immutable`.
