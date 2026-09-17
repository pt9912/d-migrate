# Reader-Treue 1: die Compare-Matrix wird Abnahme (P6, P0, S4, P12, P11)

> **Status:** **In Arbeit seit 2026-09-17** (Schnitt 2026-09-17 aus dem
> ungeschnittenen Reader-Slice; Befunde aus Plan-Review und
> Architektur-Prüfung eingearbeitet, Anker gegen `90c6c234f` nachgemessen).
> Der Bauabschnitt unten hält Commits, Messungen, Sabotagen und Neu-Pins fest.
> Teil des Umbrellas [`reader-treue.md`](reader-treue.md). Dort stehen der
> gemeinsame Nenner, die Belegart, die Regeln der Abnahme (Neu-Pins,
> betroffene Zellen, Nulllinie, Sabotage), die Doku-Pflichten und die Codes.
> **Vorbedingung / Gate:** keins. Die Eigner-Frage **E1** (Bezeichner in
> `"…"` gegen MySQL, s. Umbrella) ist am 2026-09-17 als **(b)** entschieden:
> der MySQL-Generator setzt `"…"`-Bezeichner in rohen Ausdrücken (CHECK,
> berechnete Spalte, Index-Prädikat) in Backticks um, wie es 8.3 für
> Sichten-Rümpfe schon vorsieht. Das ist ein eigener Bauteil vor den Neu-Pins
> von P6 und P12 (Spec 8.3 zieht mit); die Neu-Pins sind damit frei.
> **Aktivierung:** mit dem ersten Implementierungs-Commit nach `in-progress/`
> gewandert, zusammen mit dem Umbrella.
> **Abhängigkeit:** kein Vorgänger. Plan 2 und Plan 3 setzen die Matrix aus P0
> voraus, Plan 4 die Normalisierung aus P12.

## Befund

### C1 — MySQLs Charset-Introducer macht das Schema ungültig (nachgemessen)

Aus der Compare-Messung (dort Posten 4). Eine Reverse-Datei mit dem
MySQL-CHECK ist ungültig:

```
$ d-migrate schema validate --source cs_my.yaml
  ✗ Error [E012]: Check expression 'ck_mail' references unknown column '_utf8mb4'
```

Gegen MySQL 9.7.2 nachgemessen: `CHECK_CLAUSE` liefert neben dem Introducer
auch das Backslash-Escape und Backtick-Quoting —
`` (`email` like _latin1\'%@%\') ``. Der Introducer ist der Zeichensatz der
Sitzung, die den CHECK anlegte. Der Server-Text geht aus
`MysqlMetadataQueries` (`cc.check_clause`) unverändert ins Modell.

**Der Präzedenzfall steht im Repo.** Der SQL-Server-Reader normalisiert
denselben Fall im Reader: `MssqlTypeMapping.normalizeCheckExpression`
(`MssqlTypeMapping.kt:326`) streicht `N'…'` und macht aus `[col]` den nackten
Namen bzw. `"Col"`; aufgerufen in `MssqlMetadataQueries.kt:344`, gepinnt in
`MssqlTypeMappingTest`. `spec/type-mapping.md` beschreibt das in 6.2
(„CHECK-Ausdrücke kommen in neutraler Syntax"). Die Alternative „die Analyse
kennt den Introducer" hat das Repo für dieselbe Klasse schon verworfen.

**Wirkung in der Matrix:** die drei Zellen der MySQL-Zeile stehen auf
`INVALID`/`E012-introducer` (`known_introducer_invalid` in
`lib/compare-guards.sh`). Die `E012`-Grenze steht im Anwenderhandbuch
(`docs/user/anwenderhandbuch.md:2198`).

### M10 — derselbe Server-Text im Berechnungsausdruck (im Code geprüft)

`mysqlComputedGeneration` (`MysqlSchemaReader.kt:283`) übernimmt
`GENERATION_EXPRESSION` roh; die KDoc hält die Serverform fest (gemessen auf
9.7.2: `` (`q` * `price`) ``). `E136` erkennt Spaltenbezüge mit derselben
Analyse wie `E012` (`CheckExpressionColumns.referencedIn`,
`SchemaColumnValidationRules.kt:253`). Ein Introducer im Berechnungsausdruck
macht das Schema also mit `E136` ungültig — und den erkennt
`known_introducer_invalid` nicht: die Zelle würde `GEN-FAIL`, nicht pinnbar.
Die Fixture trägt keinen Berechnungsausdruck mit Zeichenkette; der Fall ist zu
messen.

### N2 — Backtick-Quoting im MySQL-Server-Text (beim Schnitt gefunden, im Code geprüft)

Beide Server-Texte oben tragen Backticks. `RawSqlExpressionPortability.assess`
meldet Backtick-Quoting für jedes Ziel außer MySQL
([`RawSqlExpressionPortability.kt`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/RawSqlExpressionPortability.kt),
Zeile 60), und jeder Generator prüft CHECK und Berechnungsausdruck damit.
Entfernte P6 nur Introducer und Backslash-Escape, wechselte die MySQL-Zeile von
`INVALID` zu „misst, aber jeder CHECK und jede Berechnung fällt mit `E053`
weg". Der SQL-Server-Präzedenzfall entfernt deshalb auch sein Quoting.

### M9 — `"Name"` gegen MySQL (im Code geprüft)

`neutralIdentifier` (`MssqlTypeMapping.kt:396`) lässt einen kleingeschriebenen
Namen nackt und macht jeden anderen zu `"Name"`. MySQL liest `"…"` ohne
`ANSI_QUOTES` als Zeichenkette (`RawSqlSkeleton.kt:16`;
`spec/cli-spec.md:843`), und `RawSqlExpressionPortability` hat dafür keinen
Marker. Für einen SQL-Server-**CHECK** gilt das schon heute: er läuft durch
dieselbe Normalisierung, ein PascalCase-CHECK SQL Server → MySQL vergleicht
also bereits eine Zeichenkette. Nach P12 scheitert auch eine
PascalCase-Berechnung SQL Server → MySQL nicht mehr am Server, sondern rechnet
still mit Zeichenketten. Nach P6 gilt dasselbe für MySQL → MySQL über eine neutrale Datei, wenn ein
nicht-kleingeschriebener Backtick-Name zu `"Name"` wird. Das ist Eigner-Frage
E1 im Umbrella.

### D6 — T-SQL-Quoting im Berechnungsausdruck (nachgemessen)

Der SQL-Server-Reverse liest den Berechnungsausdruck mit Klammer-Quoting
(`[quantity]*[unit_price]`); PostgreSQL (`syntax error at or near "["`) und
MySQL (`ERROR 1064`) lehnen die DDL ab. Die Matrix pinnt beide Zellen als
`APPLY-FAIL`. Der Computed-Zweig übernimmt `computedDefinition` roh
(`MssqlSchemaReader.kt:168`), der CHECK-Pfad normalisiert
(`MssqlMetadataQueries.kt:344`). `MssqlHashPartitionRecognition.recognize`
liest denselben Text und erwartet die Serverform. `spec/ddl-generation-rules.md`
8.3 („Roher Ausdruckstext: CHECK, Index-Prädikat, Index-Ausdruck") zählt den
Berechnungsausdruck nicht auf, obwohl der Code ihn prüft
(`RawSqlExpressionPortability.computedRefusal`).

### F5 — Herkunftsplanung nach einer Reader-Normalisierung (Architektur-Prüfung)

`schema migrate` plant rohe Texte aus der Herkunft: „Hat der **Autor** den
Text seit dem letzten Anwenden geändert?"
([ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md),
übernommene Entscheidung 2). Ein Migrations-Artefakt, dessen Herkunftstext der
alte Reader schrieb (`[q]*[p]`), gegen einen neuen Reverse (`q*p`) sieht nach
einer Autorenänderung aus. Für den Berechnungsausdruck heißt das
`AlterColumnGeneration`, und SQL Server lehnt die in place ab
(`spec/ddl-generation-rules.md`, 3.2a). Für den MySQL-CHECK heißt es Drop und
Add. Das ist eine Messung, kein ADR.

### D5 — SQLite nennt die Fremdschlüssel jeder Tabelle `fk_0` (Msg 2714 nachgemessen, Rest im Code geprüft)

`SqliteMetadataQueries.listForeignKeys` vergibt `fk_<id>` aus der
PRAGMA-Nummer (`SqliteMetadataQueries.kt:138`), also mehrfach im Schema. SQL
Server verlangt eindeutige Constraint-Namen (Matrix: SQLite → SQL Server,
`Msg 2714`). Derselbe Reader vergibt für unbenannte mehrspaltige
UNIQUE-Klauseln `uq_0` je Tabelle (`syntheticUniqueName`,
`SqliteSchemaReader.kt:341`); PostgreSQL und SQL Server lehnen auch das ab.
SQLite **bewahrt** Namen im `CREATE TABLE`-Text; der Präzedenzfall ist
`SqliteUniqueConstraintScanner`, der die Namen mehrspaltiger UNIQUE-Klauseln
dort liest.

### M8, I3, I5 — der Scanner, der Generator und die PRAGMA (im Code geprüft)

- **Kommentare:** `SqliteDdlScanning` (`CONSTRAINT_NAME_BEFORE`,
  `SqliteDdlScanning.kt:13`; `skipQuoted`, `:51`) kennt weder `--` noch
  `/* */`. SQLite speichert Kommentare innerhalb des `CREATE TABLE`-Textes; ein
  Apostroph darin verschiebt das Überspringen von Literalen.
  `hasAutoincrement` sucht mit `contains` im ganzen Text
  (`SqliteTypeMapping.kt:242`) und trifft damit auch einen Kommentar oder einen
  Spaltennamen.
- **Benannte Spalten-Fremdschlüssel** (`CONSTRAINT n REFERENCES …`) kennt
  kein Scanner.
- **Der Generator schreibt keinen Namen für einen Spalten-Fremdschlüssel** —
  weder im Generate-Pfad (`inlineForeignKey`,
  `SqliteColumnConstraintHelper.kt:178`) noch im Migrate-Pfad (`columnLine`,
  `SqliteDiffSqlBuilders.kt:76`). Eine von d-migrate angelegte
  SQLite-Datenbank trägt solche Namen also nie; der Reader muss sie bilden.
- **I5 — Namensvorbild:** `fk_${tableName}_$columnName` für aufgeschobene
  Fremdschlüssel (`DdlGenerationSupport.kt:79`).
- **I3:** `listForeignKeys` castet `it["to"] as String`
  (`SqliteMetadataQueries.kt:141`). Ein Fremdschlüssel ohne Spaltenliste
  (`REFERENCES t`, also auf den Primärschlüssel) liefert dort `NULL`; der
  Reverse scheitert an einer gültigen Datenbank.
- **Fähigkeit:** `namesSingleColumnConstraints = false`
  (`SqliteCapabilities.kt:31`) projiziert in `schema migrate` die Namen
  einspaltiger Constraints weg (`TableComparator.renamedConstraint` über
  `TargetProjection.constraintName`). Mehrspaltige Fremdschlüssel sind
  Tabellen-Constraints und werden mit Namen verglichen.

### S4 — SQLite-Migrate verwirft Fremdschlüssel-Aktionen (Review M15, im Code geprüft)

`SqliteDiffSqlBuilders.constraintLine` rendert einen Fremdschlüssel der
Tabellenebene **ohne** `ON DELETE`/`ON UPDATE` (`SqliteDiffSqlBuilders.kt:115`).
Benutzt wird die Zeile für `CreateTable` (`SqliteDiffSimpleOps.kt:68`) und für
jeden Rebuild (`SqliteRebuildRenderer.kt:612`). Der Generate-Pfad schreibt die
Aktionen (`SqliteColumnConstraintHelper.kt:238`), der Spalten-Fremdschlüssel in
`columnLine` auch. Folge: `schema migrate` legt eine solche Tabelle ohne
Aktionen an, und ein Rebuild (etwa für `AlterColumnNullability`) nimmt einer
bestehenden Tabelle ihre Aktionen. Für P11 stört das die Konvergenzmessung:
der Post-Compare sieht dann fehlende Aktionen statt der Namen.

### Die Matrix heute (für P0)

- Sie wendet `fixtures/seeds/<dialekt>.sql` schon an, wenn es die Datei gibt
  (`smoke-compare-matrix.sh:400`); es gibt keine.
- Sie pinnt Zahl und Codes der Vergleichsfunde je Zelle, aber weder die Notes
  eines Reverse noch die des Generate-Schritts.
- **H1:** Exit 3 des Generate-Schritts erkennt sie nur als Introducer-Fall
  (`smoke-compare-matrix.sh:420`); jeder andere Exit 3 ist `GEN-FAIL` und nicht
  pinnbar. Ein `geography`-Seed liest heute als `Enum(refType = "geography")`
  (`PostgresTypeMapping.kt:174`), die Validierung meldet `E007`
  (`SchemaColumnValidationRules.kt:152`), `schema generate` endet mit Exit 3
  (`SchemaGenerateRunner.kt:192`): alle PostgreSQL-Zellen würden `GEN-FAIL`.
  Der `geography`-Seed kommt deshalb erst mit P4 (Plan 3).
- **L1:** der Sidecar-Report heißt `generated.report.yaml`, nicht
  `generated.sql.report.yaml` — die Endung wird ersetzt
  (`SchemaGenerateHelpers.sidecarPath`, aufgerufen in
  `SchemaGenerateOutputSupport.kt:106`).
- **M12:** das Image `postgis/postgis` installiert PostGIS per Init-Skript in
  der Datenbank aus `POSTGRES_DB`, also in `public`. `dialect_clean` räumt
  PostgreSQL mit `DROP SCHEMA public CASCADE` (`lib/dialects.sh:84`) und nähme
  die Extension mit. Präzedenz für ein eigenes Init-Verzeichnis:
  `examples/sample-db/docker-compose.yml:74`.
- **H2:** ein Seed `integer GENERATED ALWAYS AS IDENTITY` als alleiniger
  Primärschlüssel liest als `identifier` **ohne** Modus
  (`PostgresTypeMapping.kt:54`); `W163` (Plan 2) wäre damit nie auslösbar. Der
  P10-Seed ist deshalb `bigint GENERATED ALWAYS AS IDENTITY` (der Zweig
  `PostgresTypeMapping.kt:46` behält den Modus); der `integer`-Fall ist Posten
  S1 in Plan 2 und bekommt einen eigenen Seed.
- **L5:** zwei unbenannte mehrspaltige UNIQUE-Klauseln in zwei
  SQLite-Tabellen ergeben zweimal `uq_0`. SQLite → PostgreSQL wechselt mit dem
  Seed absehbar auf `APPLY-FAIL`, bis P11 kommt.

## Ziel

1. Ein MySQL-Reverse ist gültig, und CHECK und Berechnungsausdruck kommen in
   neutraler Syntax: Introducer, Backslash-Escape und Backtick-Quoting
   entfallen. Die MySQL-Zeile der Matrix misst.
2. Die Matrix ist die Abnahme der Reader-Pakete: native Seeds, ein
   Silent-Loss-Check auf Quell- **und** Zielseite, die Codes je Reverse und je
   Generate.
3. `schema migrate` gegen SQLite behält die Aktionen eines Fremdschlüssels.
4. Ein SQL-Server-Reverse liefert Berechnungsausdrücke ohne T-SQL-Quoting;
   SQL Server → PostgreSQL und → MySQL messen.
5. Der SQLite-Reverse liest Fremdschlüsselnamen aus der Quelle; ein gebildeter
   Name ist im Schema eindeutig und von Lauf zu Lauf gleich.
6. Kein Paket macht aus einem lauten Fehlschlag einen stillen (E1).

Begründet gegen [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004)
(Reverse-Engineering) und die Abnahme in
[`spec/lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md),
Abschnitt 8.4 (Constraint-Typen, Multi-Column Foreign Keys).

## Abgrenzung

- **Kein SQL-Server-Seed für D1.** Eine berechnete Spalte mit `CAST` legt SQL
  Server als `CONVERT(…)` ab; der Seed schlösse die Zellen, die P12 öffnet.
  D1 ist in der Matrix ohnehin unsichtbar (beide Seiten sind Reverses); Plan 4
  misst im Roundtrip-Harness.
- **Kein `geography`-Seed** (H1) und **kein `search_path`-Bein** — beide
  kommen mit ihrem Paket (P4 in Plan 3, P3 in Plan 2).
- **Oracle bleibt aus der Matrix** (Umbrella, „Die Matrix").
- **P11 ändert weder den Generator noch eine `names*`-Fähigkeit.** Braucht
  der Fix eines von beiden, hält das Paket an: das berührt die Familie aus
  [ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md),
  Abschnitt 2, und ist eine Eigner-Frage.
- **Die Portabilitätsprüfung mit Herkunft** (D6, zweite Hälfte) ist nicht
  hier:
  [`../open/ausdrucks-portabilitaet-mit-herkunft.md`](../open/ausdrucks-portabilitaet-mit-herkunft.md).

## Arbeitspakete

**Reihenfolge:** P6 → P0 → S4 → P12 und P11 (untereinander frei). P6 kommt
vor P0, damit der Neu-Pin der MySQL-Zeile allein im Diff steht. S4 kommt vor
P11, weil P11 gegen SQLite migriert.

### P6 — MySQL: Server-Text-Anhänge gehören nicht ins Modell (C1, M10, N2, F5)

**Modul:** `:adapters:driven:driver-mysql` — `MysqlMetadataQueries` (CHECK)
und `mysqlComputedGeneration` in `MysqlSchemaReader` (Berechnungsausdruck).
Eine gemeinsame Normalisierung mit SQL Server in `driver-common` nur, wenn die
Regeln sich wirklich decken; sonst eine eigene im MySQL-Treiber.

**Was P6 baut.** Für CHECK und Berechnungsausdruck, an der Stelle, an der der
Text ins Modell geht:
1. der Introducer `_<zeichensatz>'…'` wird `'…'`;
2. das Backslash-Escape `\'` in einem Literal wird `''`;
3. ein Backtick-Bezeichner wird nackt, wenn er kleingeschrieben und ohne
   Quoting gültig ist, sonst `"…"` — dieselbe Regel wie beim SQL-Server-Reader.
   Der dritte Schritt hängt an E1.

**Zuerst messen** (MySQL 9.7.2):
1. `CHECK_CLAUSE` und `GENERATION_EXPRESSION` für einen Ausdruck mit
   Zeichenkette, mit Backslash-Escape und mit einer PascalCase-Spalte (M10,
   N2).
2. **F5:** `schema migrate` mit einem Artefakt, dessen Herkunftstext den
   Introducer trägt, gegen eine Datenbank, die der neue Reader liest — wird
   ein Drop und Add des CHECK bzw. eine `AlterColumnGeneration` geplant?
3. **E1:** MySQL → MySQL über eine neutrale Datei mit einer
   PascalCase-Spalte in CHECK und Berechnung.

**DoD:**
1. PostgreSQL ↔ MySQL meldet `ck_customer_email_shape` nicht mehr; ein
   MySQL-Reverse mit einem solchen CHECK ist gültig (kein `E012`), einer mit
   einer Zeichenkette im Berechnungsausdruck ebenso (kein `E136`).
2. Ein normalisierter MySQL-CHECK und -Berechnungsausdruck ist für
   PostgreSQL, SQLite und SQL Server portabel (Unit-Test je Ziel über
   `RawSqlExpressionPortability.assess`); kein `E053` allein wegen Backticks.
3. PostgreSQL ↔ SQL Server war sauber und bleibt es.
4. Die drei Messungen stehen in diesem Plan. Ergibt Messung 2 eine geplante
   Operation, nennt der CHANGELOG sie unter „Changed", mit dem Ausweg.
5. **Matrix:** die drei Zellen der MySQL-Zeile verlassen
   `INVALID`/`E012-introducer` und messen. Betroffen sind außerdem alle Zellen
   mit Ziel MySQL (der Ziel-Reverse läuft durch denselben Reader): PostgreSQL
   → MySQL misst weiter und wird geprüft, SQLite → MySQL und SQL Server → MySQL
   bleiben aus ihren eigenen Gründen `APPLY-FAIL`. Zeigt die geöffnete Zeile
   neue Befunde (MySQL als Quelle ist nie gemessen), werden sie vor dem Pin
   benannt. `known_introducer_invalid` und der Zustand `INVALID` bleiben im
   Harness, um einen Rückfall zu erkennen; die Kommentare in der
   Erwartungsdatei und im README ziehen nach. Der Neu-Pin wartet auf E1.
6. P6 darf einen MySQL-Seed mit einem Berechnungsausdruck mit Zeichenkette
   anlegen (M10); er ist P6s eigener Fall und steht im selben Neu-Pin.
7. Der Roundtrip-Harness weist MySQL nicht mehr als ungültig aus (README).
8. **Doku:** `spec/type-mapping.md`, Abschnitt 4 (MySQL), bekommt die Regel
   nach dem Muster von 6.2 — für CHECK und Berechnungsausdruck; Introducer,
   Backslash-Escape und Backtick-Quoting entfallen, sonst nichts. Das
   Anwenderhandbuch zieht die `E012`-Grenze nach
   (`docs/user/anwenderhandbuch.md:2198`). CHANGELOG „Fixed". Kein neuer Code,
   also kein Ledger.
9. **Sabotage:** Introducer nicht entfernt → Unit- und Integrationsfall rot,
   die Matrix fällt auf `INVALID` zurück; Backticks nicht entfernt → der
   Portabilitäts-Unit-Test rot.

**Abnahme:** `:test:integration-mysql` (Nulllinie gemessen), Matrix.

### P0 — die Matrix wird Abnahme: native Seeds und Silent-Loss-Check

**Auftrag:** aus dem Compare-Slice („Native Typ-Seeds und Silent-Loss-Check
der Compare-Matrix"). **Ort:** `examples/mcp-e2e` —
[`smoke-compare-matrix.sh`](../../../examples/mcp-e2e/scripts/smoke-compare-matrix.sh),
eine neue Bibliothek neben
[`lib/compare-guards.sh`](../../../examples/mcp-e2e/scripts/lib/compare-guards.sh),
die Seeds unter `fixtures/seeds/`,
[`expected/compare-matrix.env`](../../../examples/mcp-e2e/expected/compare-matrix.env),
[`lib/dialects.sh`](../../../examples/mcp-e2e/scripts/lib/dialects.sh), der
README-Abschnitt „Compare-Matrix 5x5" und
[`docker-compose.yml`](../../../examples/mcp-e2e/docker-compose.yml). Kein
Produktionscode.

**Teil 1 — PostGIS im PostgreSQL-Dienst (M12).** Der Dienst wechselt auf das
Image, das `examples/sample-db/docker-compose.yml` per Digest pinnt
(`postgis/postgis:18-3.6`). Das Init-Verzeichnis wird durch ein eigenes
ersetzt, damit das Image PostGIS nicht in `public` installiert. Das eigene
Init legt die Extension ins Schema `postgis` und setzt den `search_path` auf
Datenbankebene (`public, postgis`) — die Konfiguration, die die PostGIS-Doku
empfiehlt. `DROP SCHEMA public CASCADE` in `dialect_clean` lässt das Schema
`postgis` stehen; das ist zu prüfen, nicht anzunehmen. So bleibt der A3-Fall
(rund 1000 Funktionen in `public`) aus der Matrix: der MCP-Reverse liest
Routinen immer und überschwemmte sonst jede PostgreSQL-Zelle. Dieser Teil ist
ein eigener Commit **ohne** Neu-Pin: die Matrix bleibt unverändert, und
`make mcp-e2e-roundtrip` und `make mcp-e2e-smoke` bleiben grün (gemessen).

**Teil 2 — Seeds und ihre Anmerkungen (M1, M8).** Je Dialekt eine Datei mit
Tabellen unter dem Präfix `sl_`. Jede Seed-Spalte trägt eine Anmerkung, die
**außerhalb** der `CREATE`-Anweisung steht (SQLite speichert Kommentare
innerhalb im Tabellentext, und die Scanner kennen keine Kommentare, s. M8).
Die Anmerkung nennt Tabelle und Spalte ausdrücklich, das Paket, das den Fall
trägt, die erwartete neutrale Form im Reverse der Quelle und je Ziel die
erwartete neutrale Form im Reverse des Ziels. Wo eine Form einen Verlust
darstellt, nennt die Anmerkung den erwarteten Code oder ausdrücklich „keinen".
Die Anmerkungen beschreiben den **Zielzustand** nach den vier Plänen; was davon
heute fehlt, steht in der Liste bekannter Befunde (Teil 3). Die Anmerkung für
S1 folgt der Empfehlung zu E2; entscheidet der Eigner anders, zieht Plan 2 sie
nach. **Kein Seed ohne Posten.**

| Quelle | Seed | trägt |
| --- | --- | --- |
| PostgreSQL | `integer[]`, `text[]`, `bigint[]`, `date[]` | P5 und S3 (Plan 2), N1 (Plan 2, P9) |
| PostgreSQL | `json`, `jsonb` | P8 (Plan 2) |
| PostgreSQL | `numeric` ohne Präzision | P9 (Plan 2) |
| PostgreSQL | `bigint GENERATED ALWAYS AS IDENTITY` als alleiniger Primärschlüssel seiner Tabelle (H2) | P10 (Plan 2) |
| PostgreSQL | `integer GENERATED ALWAYS AS IDENTITY` als alleiniger Primärschlüssel einer eigenen Tabelle | S1 (Plan 2) |
| PostgreSQL | `varchar` ohne Länge, `interval` | D3 und B4 (Gegenprobe: Text-Familie bzw. `R301`) |
| SQLite | unbenannter und benannter Spalten-Fremdschlüssel, unbenannter Tabellen-Fremdschlüssel mit `ON DELETE CASCADE`, unbenannte mehrspaltige UNIQUE-Klausel in **zwei** Tabellen | P11, S4 |
| SQLite | `NUMERIC` ohne Präzision | P9 (Plan 2) |
| MySQL | — (die Fixture trägt C1; P6 darf einen eigenen Seed anlegen) | P6 |
| SQL Server | — (Abgrenzung; P12 legt seinen PascalCase-Seed erst nach E1 an) | P12 |

**Die `ALWAYS`-Spalten sind alleiniger Primärschlüssel ihrer Tabelle**, denn
nur dann rendert SQLite sie als `AUTOINCREMENT`; sonst greift `W135`.
**Seeds, die den Reverse vor ihrem Paket scheitern lassen, kommen mit dem
Paket** — etwa ein Fremdschlüssel ohne Spaltenliste (I3) oder ein Kommentar
mit Apostroph im Tabellentext (M8): beide bringt P11. Ein gescheiterter
Reverse ist nicht pinnbar und würde die ganze Quelle stilllegen.

**Teil 3 — Silent-Loss-Check.** Vier Klassen:

1. **Quelle verfehlt die Erwartung** — der Reverse der Quelle liefert für eine
   Seed-Spalte eine andere neutrale Form als ihre Anmerkung (Typ,
   Element-Typ, Identity-Modus). `varchar` gehört zur Text-Familie (D3).
2. **`ref_type` ohne Eintrag in `custom_types`** — auf jedem Reverse. Ein
   solcher Verweis ist nie richtig (A5).
3. **Eine Spalte fehlt** — im Reverse der Quelle gegen die Anmerkungen; im
   Reverse des Ziels gegen den Reverse der Quelle, außer für Objekte, die der
   Generate-Schritt in `skipped_objects` nennt.
4. **Ziel ohne Code (M1)** — der Reverse des Ziels liefert die erwartete
   Form, die Anmerkung nennt dafür einen Code, und der Generate-Report der
   Zelle trägt ihn nicht für dieses Objekt. Eine erwartete Degradierung, deren
   Anmerkung „keinen Code" sagt, ist selbst ein Fehlschlag.

Ein Fall ist **laut**, wenn der zugehörige Report eine Note mit genau diesem
Objekt trägt. Ein stiller Fall ist ein Fehlschlag, **außer** er steht in der
festen Liste bekannter Befunde im Harness-Code, mit dem Paket, das ihn
auflöst (Muster: `known_introducer_invalid`). Die Liste ist Code, keine
Erwartung: `--update-expectations` erweitert sie nicht, und ein Eintrag, der im
Lauf **nicht** auftritt, ist selbst ein Fehlschlag („bekannter Befund
verschwunden — Liste nachziehen").

Mit den Seeds aus Teil 2 stehen beim Einbau mindestens diese Einträge in der
Liste: P5 (`W162` fehlt auf MySQL und SQLite für die Arrays), N1 (`date[]`
liest `element_type: text` ohne `R301`), P8 (`R402` fehlt), P9 (`R404` und
`R221` fehlen), P10 (`W163` fehlt auf MySQL und SQLite), S1 (die
`integer`-Identity liest als `identifier` ohne Modus). Sie gehören alle Plan 2.

**Teil 4 — Codes je Reverse und je Generate.** Zwei neue Schlüsselfamilien in
`expected/compare-matrix.env`, versionsgebunden und pinnbar wie
`CELL_`/`CODES_`:

- `REPORT_CODES_<DIALEKT>` — die Codes des Reverse-Reports der Quelle, mit
  Anzahl;
- `GEN_CODES_<QUELLE>_<ZIEL>` — die Codes aus dem Sidecar-Report des
  Generate-Schritts (`generated.report.yaml`, L1), mit Anzahl; auch für eine
  Zelle, die danach `APPLY-FAIL` wird.

Ein Paket, das eine Note einführt, wird damit zu einem bewussten Neu-Pin, und
seine Sabotage zu einer Abweichung, die den Lauf rot macht. Weitere Beine (das
`search_path`-Bein aus Plan 2) hängen ihren Schlüssel an dieselbe Familie.

**Teil 5 — Schutz und Selbstprüfung.** Silent-Loss-Fehlschläge, ein nicht
auftretender bekannter Befund, ein unlesbarer Report und eine Anmerkung, die
eine nicht vorhandene Spalte nennt, laufen über `note_failure` — nie pinnbar;
`--update-expectations` schreibt dann nichts. Ein Seed ohne Anmerkungen, ein
Report ohne die erwarteten Schlüssel oder ein jq-Fehler scheitern laut, statt
„nichts gefunden" zu melden.

**DoD:**
1. Teil 1 steht als eigener Commit; Matrix, `make mcp-e2e-roundtrip` und
   `make mcp-e2e-smoke` sind unverändert grün (gemessen).
2. Seeds für PostgreSQL und SQLite im Umfang der Tabelle (ohne die Fälle, die
   mit P11 kommen); jede Seed-Spalte ist angemerkt, jede Anmerkung steht
   außerhalb der `CREATE`-Anweisung.
3. Der Check läuft mit vier Klassen, der Liste bekannter Befunde und der
   Selbstprüfung.
4. `REPORT_CODES_*` und `GEN_CODES_*` sind gepinnt; zwei aufeinanderfolgende
   Läufe sind identisch.
5. **Neu-Pin der Seeds** als eigener Commit. Betroffen sind alle Zellen mit
   Quelle PostgreSQL oder SQLite. **Vor dem Pin benannt (L5):** SQLite →
   PostgreSQL wechselt auf `APPLY-FAIL` (`uq_0` doppelt); P11 öffnet die Zelle
   wieder. Jede andere Zelle, die kippt, wird vor dem Pin geklärt (Seed
   anpassen oder Posten benennen).
6. **Sabotage am Harness**, je mit unveränderter Erwartungsdatei: (a) eine
   Note, die der Reverse nicht mehr trägt → Klasse rot; (b) eine Anmerkung für
   eine fehlende Spalte → Selbstprüfung rot; (c) ein bekannter Befund, der
   nicht auftritt → rot; (d) `--update-expectations` mit einem stillen Verlust
   → Datei unverändert, rot; (e) ein kaputtes jq-Programm → rot; (f) eine
   Ziel-Degradierung, deren Code im Generate-Report fehlt → Klasse 4 rot.
7. README-Abschnitt und Skriptkopf beschreiben Seeds, Anmerkungen, Klassen,
   Liste und Schlüsselfamilien; `bash -n` und shellcheck (Container) für alle
   geänderten Skripte.

### S4 — SQLite-Migrate behält die Aktionen eines Fremdschlüssels (Review M15)

**Modul:** `:adapters:driven:driver-sqlite` — `SqliteDiffSqlBuilders.constraintLine`.

**Zuerst messen:** `schema migrate --execute` gegen SQLite (a) mit einer neuen
Tabelle, die einen Fremdschlüssel der Tabellenebene mit `ON DELETE CASCADE`
trägt, und (b) mit einer Nullbarkeits-Änderung an einer bestehenden Tabelle
mit einem solchen Fremdschlüssel (Rebuild). Festzuhalten: was
`PRAGMA foreign_key_list` danach in `on_delete` zeigt, und ob der
Post-Compare den Verlust meldet (Exit-Code, Fund). Meldet er ihn, ist der
Posten laut, aber weiter ein Defekt.

**Was S4 baut:** `constraintLine` schreibt die Aktionen wie der
Generate-Pfad, über dieselbe `referentialActionSql`.

**DoD:**
1. Beide Messungen stehen im Plan.
2. `CreateTable` und Rebuild behalten die Aktionen; der Post-Compare ist in
   beiden Fällen sauber.
3. Ein Unit-Test je Pfad (`SqliteDiffSimpleOps`, `SqliteRebuildRenderer`);
   ein Fall in `:test:integration-sqlite`.
4. `make sample-db-types-smoke` bleibt grün.
5. **Doku:** prüfen, ob `spec/ddl-generation-rules.md` (3.5, 3.7) etwas zu
   Fremdschlüsseln im Migrate-Pfad sagt; wenn nicht, bleibt die Spec, wie sie
   ist (die Aktionen sind Teil des Modells, ihr Erhalt ist keine neue Regel).
   CHANGELOG „Fixed". Kein Code, kein Ledger.
6. **Sabotage:** Aktion weggelassen → beide Unit-Tests rot.

**Matrix:** keine Änderung erwartet — die Matrix generiert, sie migriert
nicht.

### P12 — SQL Server: der Berechnungsausdruck kommt ohne T-SQL-Quoting (D6, M9, F5)

**Modul:** `:adapters:driven:driver-mssql` — der Computed-Zweig in
`MssqlSchemaReader` führt `computedDefinition` durch dieselbe Normalisierung
wie ein CHECK. Ob `normalizeCheckExpression` dafür umbenannt wird,
entscheidet das Paket. **Nicht mitnormalisieren:**
`MssqlHashPartitionRecognition` liest denselben Text und erkennt die
Serverform; die Normalisierung gehört an die Stelle, an der der Ausdruck ins
Modell geht, nicht an die Abfrage.

**Zuerst messen** (SQL Server 2025):
1. Ergibt ein Vergleich eines **älteren** SQL-Server-Reverse (mit Klammern)
   gegen einen neuen einen Fund? Für den Berechnungsausdruck gilt die eigene
   Regel mit `W137`; ADR 0056 nimmt ihn ausdrücklich aus.
2. Plant der Post-Compare von `schema migrate` gegen SQL Server neu? Beide
   Seiten laufen durch denselben Reader.
3. **F5:** `schema migrate` mit einem Artefakt, dessen Herkunftstext die
   Klammern trägt, gegen eine Datenbank, die der neue Reader liest. **Plant
   das eine `AlterColumnGeneration`, hält das Paket an:** SQL Server lehnt die
   Operation ab, und ein Anwender mit einem älteren Artefakt stünde nach dem
   Update vor einer blockierten Migration. Der Ausweg ist dann eine
   Eigner-Frage, keine Entscheidung im Bau.
4. **M9:** eine PascalCase-Berechnung SQL Server → MySQL; der Ausgang folgt
   E1.

**DoD:**
1. Ein SQL-Server-Reverse liefert `quantity*unit_price` statt
   `[quantity]*[unit_price]`; die Hash-Partitionserkennung bleibt grün.
2. Die vier Messungen stehen im Plan.
3. **Matrix:** SQL Server → PostgreSQL und → MySQL verlassen `APPLY-FAIL` und
   messen. Betroffen sind alle Zellen mit Quelle **oder** Ziel SQL Server:
   SQL Server → SQLite, PostgreSQL → SQL Server und MySQL → SQL Server werden
   geprüft; SQLite → SQL Server bleibt bis P11 `APPLY-FAIL`. P12 legt nach E1
   einen SQL-Server-Seed mit einer PascalCase-Berechnung an und pinnt die Zelle
   SQL Server → MySQL mit dem entschiedenen Ausgang. Der Neu-Pin wartet auf
   E1.
4. **Doku:** `spec/type-mapping.md` 6.2 (der Absatz über CHECK-Ausdrücke gilt
   für Berechnungsausdrücke mit) und 6.3 („Der Ausdruck ist die Serverform" —
   bis auf diese Normalisierung); `spec/ddl-generation-rules.md` 8.3 nennt den
   Berechnungsausdruck unter den Feldern mit rohem Ausdruckstext, und die
   Überschrift „Roher Ausdruckstext: CHECK, Index-Prädikat, Index-Ausdruck"
   zieht mit. Anwenderhandbuch 3.23 („Eine Spalte aus anderen Spalten
   berechnen lassen", `docs/user/anwenderhandbuch.md:2494`): der
   SQL-Server-Reverse liefert den Ausdruck in neutraler Schreibweise.
   CHANGELOG „Changed" (ein Reverse derselben Datenbank liefert anderen Text).
5. **Sabotage:** Normalisierung weg → Unit-Test rot, beide Zellen zurück auf
   `APPLY-FAIL`.

**Abnahme:** `:test:integration-mssql` (Nulllinie vorher messen), Matrix.

### P11 — SQLite: Constraint-Namen aus der Quelle, schemaweit eindeutig (D5, M8, I3, I5)

**Modul:** `:adapters:driven:driver-sqlite` — `SqliteDdlScanning`,
`SqliteMetadataQueries.listForeignKeys`, `SqliteSchemaReader`
(`multiColumnUniqueConstraints`, `syntheticUniqueName`), ein Scanner für
Fremdschlüssel-Klauseln nach dem Muster von `SqliteUniqueConstraintScanner`,
`SqliteTypeMapping.hasAutoincrement`.

**Was P11 baut.**
1. **Kommentare im Lexer.** `SqliteDdlScanning` überspringt `--` und
   `/* */` für alle Scanner; `hasAutoincrement` prüft das Schlüsselwort im
   Token-Strom, nicht per `contains`.
2. **Der Name aus der Quelle.** Eine Klausel `CONSTRAINT <name> FOREIGN KEY …`
   und eine Spaltenklausel `CONSTRAINT <name> REFERENCES …` liefern ihren
   Namen; zugeordnet über Spaltenliste und Zieltabelle, verbrauchend wie der
   UNIQUE-Scanner.
3. **Ein gebildeter Name ist schemaweit eindeutig und stabil** — für
   unbenannte Fremdschlüssel und unbenannte UNIQUE-Klauseln. Die Form folgt
   dem Vorbild `fk_<tabelle>_<spalte>` (I5) bzw. `uq_<tabelle>_<spalten>`, ist
   auf die kleinste Bezeichnergrenze der Ziele gekürzt und bekommt bei einer
   Kollision — mit einem echten oder einem schon gebildeten Namen — einen
   Zähler. Die Vergabe ist deterministisch (feste Reihenfolge über Tabelle und
   Klausel), zwei Reverses derselben Datenbank liefern dieselben Namen.
4. **I3:** ein Fremdschlüssel ohne Spaltenliste verweist auf den
   Primärschlüssel der Zieltabelle; der Reader liest ihn von dort, statt am
   `NULL` zu scheitern.

**Zuerst messen:**
1. Der Reverse einer von d-migrate erzeugten SQLite-Datenbank: Tabellen-
   Fremdschlüssel tragen ihren Namen im Text, Spalten-Fremdschlüssel nicht
   (M8).
2. Zielbewusster Vergleich und Post-Compare von `schema migrate` gegen SQLite
   mit geänderten gebildeten Namen: einspaltige werden wegprojiziert,
   mehrspaltige mit Namen verglichen — plant ein Zweitlauf neu? Konvergenz:
   `make sample-db-types-smoke`, Probe „Plan-Konvergenz". Erst nach S4.
3. Ein Vergleich gegen eine ältere Reverse-Datei (`fk_0`) meldet die neuen
   Namen; das ist die sichtbare Änderung.

**DoD:**
1. Benannte Fremdschlüssel (Tabellen- und Spaltenklausel) kommen mit ihrem
   Namen zurück; kein gebildeter Name kommt im Schema zweimal vor; zwei
   Reverses derselben Datenbank sind gleich.
2. Unit-Tests für: benannte Spaltenklausel, Kommentar mit Apostroph im
   Tabellentext, `AUTOINCREMENT` nur in einem Kommentar, Kollision mit einem
   echten Namen, Kürzung, Fremdschlüssel ohne Spaltenliste.
3. Die drei Messungen stehen im Plan; die Konvergenz ist gemessen.
4. **Seeds:** P11 ergänzt die SQLite-Seeds um den Fremdschlüssel ohne
   Spaltenliste und die Tabelle mit Kommentar im Text (Teil 2 von P0).
5. **Matrix:** betroffen sind alle Zellen mit Quelle **oder** Ziel SQLite.
   SQLite → SQL Server verlässt `APPLY-FAIL`/`Msg 2714`, SQLite → PostgreSQL
   verlässt das `APPLY-FAIL` aus dem Seed (L5); SQLite → MySQL bleibt
   `ERROR 1170` (andere Ursache:
   [`pk-constraint-prefix-length.md`](../next/pk-constraint-prefix-length.md)).
   PostgreSQL → SQLite, MySQL → SQLite und SQL Server → SQLite werden
   geprüft, weil der Ziel-Reverse andere Namen liefert.
6. **Doku:** `spec/type-mapping.md`, Abschnitt 5 (SQLite): woher der Name
   kommt und wie ein fehlender gebildet wird. Anwenderhandbuch 3.3 („Eine
   bestehende Datenbank übernehmen"): Namen von SQLite-Fremdschlüsseln.
   CHANGELOG „Changed" (ein Reverse derselben Datenbank trägt andere Namen,
   `fk_0` → `fk_cm_order_customer`).
7. **Sabotage:** wieder `fk_<id>` → Unit-Test rot, SQLite → SQL Server zurück
   auf `Msg 2714`; Kommentar-Behandlung weg → der Apostroph-Test rot.

**Stopp-Regel:** braucht der Fix eine geänderte oder neue `names*`-Fähigkeit
oder einen Generator, der Namen von Spalten-Fremdschlüsseln schreibt, hält das
Paket an (Abgrenzung). Eine Fähigkeitsänderung wäre außerdem eine geteilte
Signatur in `:hexagon:ports-common` (einmal ohne `MODULES` bauen).

**Abnahme:** `:test:integration-sqlite` (Nulllinie vorher messen), Matrix,
`make sample-db-types-smoke`.

## Bau

### Nulllinie der Integrationsmodule (2026-09-17, vor dem ersten Paket)

`make integration INTEGRATION_TASKS=":test:integration-mysql:test
:test:integration-sqlite:test :test:integration-mssql:test --continue"`:
`BUILD SUCCESSFUL`, **55 Tasks, alle `executed`** — die drei `:test`-Tasks
stehen ohne `SKIPPED` und ohne `UP-TO-DATE` im Lauf. Damit sind die beiden im
Umbrella offenen Zeilen gemessen: `:test:integration-sqlite` und
`:test:integration-mssql` laufen.

**Eine Selbstüberspringung gibt es doch**, und sie gehört in die Tabelle des
Umbrellas: `MssqlFullTextEnvironmentIntegrationTest` überspringt sich mit
`xtest`, wenn das abgeleitete Volltext-Image fehlt (`MSSQL_FTS_IMAGE`, sonst
`d-migrate-mssql-fts:local`; gebaut von `make mssql-fts-image`). Auf dem
Messhost liegt das Image, die Spec lief also mit. Kein Paket dieses Plans
hängt an ihr; wer die Nulllinie auf einem anderen Host misst, prüft das Image
mit. In `:test:integration-mysql` und `:test:integration-sqlite` gibt es keine
Selbstüberspringung (weder `assumeTrue`/`Assumptions`, `@Disabled` noch
`xtest`).

Die Testzahl je Modul steht **nicht** im Lauf: das Integrations-Image trägt
das Repo als Kopie, die Reports bleiben im Container, und Gradle zählt in der
Konsolenausgabe nichts. Gemessen ist deshalb der ausgeführte Task, nicht die
Zahl.


### E1-Bauteil — der MySQL-Generator schreibt `"…"` in Backticks um (2026-09-17)

**Gebaut** (`:adapters:driven:driver-mysql`, `MysqlRawExpressionText`): an jeder
Stelle, an der der MySQL-Generator rohen Ausdruckstext schreibt — CHECK
(Generate und Migrate), Berechnungsausdruck (Generate und Migrate),
Ausdrucks-Schlüssel eines Index (beide Renderer über `IndexColumn.mysqlKey`)
und der CHECK eines Domain-Typs. Umgeschrieben wird `"Name"` → `` `Name` ``
(`""` als Escape) und der Backslash in einem String-Literal verdoppelt; alles
andere bleibt wortgleich, und ein Text, den der Scanner nicht sicher abgrenzen
kann (offene Quotierung, offener Blockkommentar), bleibt unverändert.

**Zwei Punkte über den Wortlaut von E1 hinaus**, beide gemeldet:
1. **Der Backslash.** Ohne ihn mitzunehmen wäre P6 an dieser Stelle still
   falsch: der Reader packt `'a\\b'` (MySQL-Schreibweise, Wert `a\b`) zu
   `'a\b'` aus, und MySQL läse daraus beim Rendern `a<BS>`. Die Verdopplung
   ist dieselbe Regel, nach der `SqlIdentifiers.quoteStringLiteral` jeden
   anderen MySQL-Literalwert schreibt (Default-`sql_mode` ohne
   `NO_BACKSLASH_ESCAPES`); der Präzedenzfall steht in
   [`../done/mysql-string-literal-backslash-escaping.md`](../done/mysql-string-literal-backslash-escaping.md).
2. **Die CHECK-Preflight-Sonde** prüft jetzt dieselbe Schreibweise, die der
   Generator anlegt (`CheckPreflightPlanner.plan(expressionText = …)`,
   Voreinstellung unverändert). Ohne das zählte `SELECT count(*) … WHERE NOT
   ('Qty' > 0)` gegen MySQL jede Zeile als Verstoß und blockte die Migration —
   laut, aber falsch. Die gemeldete `expression` bleibt der neutrale Text.

**Spec:** [`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md),
8.3 („Roher Ausdruckstext"): der Berechnungsausdruck steht jetzt in der
Überschrift und in der Feldliste (das erledigt zugleich die Spec-Hälfte von
P12s DoD 4), und die Umschreibe-Regel steht dort; Abschnitt 2.3 bekommt den
Absatz „String-Literale" (die Backslash-Verdopplung war nirgends in der Spec).
CHANGELOG „Changed".

**Sabotage S-E1** (`make docker-test MODULES=":adapters:driven:driver-mysql"`,
beide Eingriffe zugleich): `"…"` unverändert durchgereicht **und** die
Backslash-Verdopplung entfernt → **20 von 877 Tests rot**, in
`MysqlRawExpressionTextTest` (Umschreibung, Generate, Migrate, Preflight).
Integrationsfall mit derselben Sabotage
(`:test:integration-mysql --tests '*NeutralExpressionSpelling*'`): rot, mit dem
DDL im Klartext (`GENERATED ALWAYS AS ("Qty" * "UnitPrice")`). Rücknahme per
Prüfsumme belegt (`md5sum` gleich), danach grün.

### P6 — MySQL: Server-Text-Anhänge gehören nicht ins Modell (2026-09-17)

**Zuerst gemessen** (MySQL 9.7.2 im `mcp-e2e`-Stack, `--raw`, also ohne
Client-Escaping):

1. **Die Serverform, zwei Ebenen.** `CHECK_CLAUSE` und
   `GENERATION_EXPRESSION` tragen Introducer, Backslash-Escapes und Backticks
   — und das Ganze ein **zweites Mal** escapet, als wäre der Text selbst ein
   Literal: jedes Anführungszeichen steht dort hinter einem Backslash und jeder
   Backslash des Werts verdoppelt. Ein CHECK auf ein Literal aus `a`,
   Backslash, `b` kommt als `_latin1` + escapetes Literal mit **vier**
   Backslashes zurück; `'it''s'` als Literal mit dem Muster Backslash-Quote
   um `it`, drei Backslashes, `s`. Ein `"` wird dabei **nicht** escapet, ein
   Tab steht roh, die Escapes `0` und `n` bleiben Escapes. Betroffen sind auch
   Bezeichner: ein Spaltenname mit Apostroph kommt escapet. Der Introducer ist der
   Zeichensatz der **Sitzung** (`_latin1` über den mysql-Client, `_utf8mb4`
   über JDBC), `N'…'` kommt als `_utf8mb3`, `X'41'` als `0x41`.
2. **Der Ausdrucks-Schlüssel eines funktionalen Index trägt dieselbe Form**
   (`information_schema.statistics.expression`: `` lower(`Note`) ``,
   `` concat(`nm`,_utf8mb4\'x\') ``). Das ist über den Wortlaut von P6 hinaus
   (der Plan nennt CHECK und Berechnungsausdruck), aber **derselbe Befund**:
   ohne die Regel bleibt ein funktionaler MySQL-Index auf jedem anderen Ziel
   unportabel (`E053`), und ein Introducer stünde im Modell. Mitgenommen und
   hier gemeldet.
3. **F5 — die Herkunftsplanung.** Die Frage ließ sich nicht wie geplant
   beantworten: das Dokument aus `--provenance-output` weist
   `--migration-overlay` **derselben Version** ab (`dialect` leer,
   `schemaFingerprint` veraltet — auch gegen dieselbe Quelldatei). Das ist ein
   vorbestehender Defekt, gemessen mit dem Image vom Stand `a77fc9bb7` auf
   beiden Seiten, und steht jetzt in
   [`../open/provenance-overlay-nicht-rueckfuehrbar.md`](../open/provenance-overlay-nicht-rueckfuehrbar.md).
   Gemessen wurde deshalb der Fall **ohne** Herkunft, und zwar in drei
   Richtungen (MySQL 9.7.2, `schema migrate --plan-only`):

   | Soll | Reader | Plan |
   | --- | --- | --- |
   | alter Reverse | alt | `no_op`, 0 Operationen |
   | neuer Reverse | neu | `no_op`, 0 Operationen |
   | **alter** Reverse | **neu** | 2 Operationen: `DropConstraint` + `AddConstraint` für den CHECK; **keine** `AlterColumnGeneration` — der Berechnungsausdruck bleibt unentscheidbar (`W137`), Exit 0 |

   Der Umstieg kostet also je CHECK ein Drop und Add, wenn jemand eine alte
   Reverse-Datei als Soll behält; ein frischer Reverse plant nichts. Der
   CHANGELOG nennt es unter „Fixed".

**Gebaut** (`MysqlServerExpressionText`, eigener Normalisierer im
MySQL-Treiber): die zweite Escape-Ebene wird abgezogen, wenn der Text sie
durchgehend trägt; dann fallen Introducer, Backslash-Escapes (`\'` → `''`,
`\\` → `\`, Steuerzeichen als Zeichen, `\%`/`\_` behalten ihren Backslash)
und Backtick-Quoting weg. Die Bezeichner-Regel (kleingeschrieben nackt, sonst
`"Name"`) ist die des SQL-Server-Readers und liegt jetzt als
`NeutralExpressionIdentifier` in `driver-common`; `MssqlTypeMapping` geht
darüber. Eine gemeinsame **Normalisierung** mit SQL Server gibt es nicht — die
Regeln decken sich nur im Bezeichner.

**Doku:** `spec/type-mapping.md` bekommt Abschnitt 4.5 nach dem Muster von 6.2;
Anwenderhandbuch 3.19 (die `E012`-Stelle) sagt, was aus einem Reverse kommt und
dass der Generator es zurückschreibt; CHANGELOG „Fixed". Kein neuer Code, kein
Ledger. **Nebenbefund korrigiert:** `spec/cli-spec.md` nannte den
Berechnungsausdruck weder bei `--provenance-output` noch in der Feldtabelle des
`raw-text-provenance`-Overlays, obwohl der Code ihn schreibt.

**Sabotage S-P6** (beide Eingriffe zugleich: Introducer nicht erkannt,
Bezeichner nicht umgesetzt): **22 von 899 Tests rot** —
`MysqlServerExpressionTextTest` (alle gemessenen Formen, Portabilität),
`MysqlMetadataQueriesTest`, `MysqlSchemaReaderTest`. Integrationsfall mit
derselben Sabotage: rot mit der Serverform im Klartext
(`` (`email` like _utf8mb4'%@%') ``). Rücknahme per Prüfsumme belegt.

**Gates E1 und P6:** `make docker-check MODULES=":adapters:driven:driver-mysql
:adapters:driven:driver-mssql :adapters:driven:driver-common :hexagon:core"`
grün (core 1484, driver-common 544, driver-mysql 899, driver-mssql 485 Tests,
0 Fehler; Zählung aus dem Image); `make integration
INTEGRATION_TASKS=":test:integration-mysql:test"` grün (8 min 30 s);
`make docs-check` (353 Dateien, 0 Befunde); `make solid-suppression-gate` vor
jedem Commit.


### Neu-Pin P6 — die MySQL-Zeile misst (2026-09-17)

Ein eigener Commit, nur die Wirkung von P6. **Sechs Schlüssel**, alle in der
MySQL-Zeile; keine andere Zelle hat sich bewegt (der Lauf meldete genau diese
sechs Abweichungen und keine nicht pinnbare):

| Schlüssel | vorher | nachher |
| --- | --- | --- |
| `CELL_MYSQL_POSTGRESQL` / `CODES_…` | `INVALID` / `E012-introducer` | `4` / `TABLE_CONSTRAINT_CHANGED:2 W137:2` |
| `CELL_MYSQL_MSSQL` / `CODES_…` | `INVALID` / `E012-introducer` | `8` / `TABLE_COLUMN_GENERATION_CHANGED:1 TABLE_COLUMN_REQUIRED_TIGHTENED:1 TABLE_COLUMN_TYPE_CHANGED:2 TABLE_CONSTRAINT_CHANGED:2 W137:2` |
| `CELL_MYSQL_SQLITE` / `CODES_…` | `INVALID` / `E012-introducer` | `11` / `TABLE_COLUMN_GENERATION_CHANGED:1 TABLE_COLUMN_TYPE_CHANGED:10` |

**Jeder neue Fund ist erklärt** (die MySQL-Zeile war nie gemessen):

- zwei CHECK-Funde auf PostgreSQL und SQL Server: die Werteliste (`in (…)`
  gegen `= ANY (ARRAY[…])` bzw. gegen die `OR`-Kette) ist bewusst ein Fund
  ([ADR 0055](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)),
  der zweite hängt allein an der Schlüsselwort-Schreibweise (MySQL gibt
  `is null` klein zurück) — die offene Frage aus
  [ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md);
- `W137` zweimal: der Berechnungsausdruck der Fixture und der des Seeds sind
  ohne Herkunft nicht entscheidbar;
- auf SQL Server zusätzlich der Identity-Modus (`W140` im Generate-Report) und
  **Typ und Nullbarkeit** beider berechneter Spalten: SQL Server leitet beides
  aus dem Ausdruck ab (`decimal(23,2)`, `text(5)`, `NOT NULL`) — derselbe
  Posten D1, den Plan 4 misst;
- auf SQLite die Typaffinität (zehn Spalten) und die Identity als
  `identifier(auto)` — dieselbe Klasse wie in den drei anderen SQLite-Spalten
  der Matrix.

**Kein Objekt fällt weg:** die Generate-Reports der drei Zellen nennen
`skipped_objects: 0`; der CHECK mit Zeichenkette und die berechnete Spalte des
Seeds werden auf allen drei Zielen gerendert. Der Reverse-Report der Quelle
trägt `R205` (Präferenz) und `R330`.

**Der Seed** (`fixtures/seeds/mysql.sql`, DoD 6) trägt den Fall M10 — eine
berechnete Spalte, deren Ausdruck eine Zeichenkette enthält — und eine nicht
kleingeschriebene Spalte (`Menge`) in zwei CHECKs. Der Ausdruck ist bewusst ein
`CASE`: ein `CONCAT` wäre auf PostgreSQL nicht immutabel und machte die Zelle
`APPLY-FAIL`. Das Anmerkungsformat, das P0 auswertet, steht im Kopf der Datei.

**Roundtrip** (`make mcp-e2e-roundtrip`, DoD 7): MySQL ist nicht mehr
„ungültig", sondern misst **5** Funde — drei CHECKs in MySQLs Kleinschreibung,
das Enum inline statt als Typ und `legacy_serial_syntax` (der Roundtrip
erklärt keine Präferenz). README nachgezogen, dort und in der Erwartungsdatei
steht jetzt, dass `INVALID` ein Wächter gegen einen Rückfall ist.


### P0 Teil 1 — PostGIS im eigenen Schema (M12, 2026-09-17)

Eigener Commit **ohne** Neu-Pin. Der `postgres`-Dienst fährt auf
`postgis/postgis:18-3.6` (derselbe Digest wie in `examples/sample-db`), und
`initdb-postgres/` ersetzt das Init-Verzeichnis des Images: die Extension geht
ins Schema `postgis`, der `search_path` der Datenbank lautet `public, postgis`.

**Gemessen, nicht angenommen** (frisches Volume):

- `pg_extension` führt `postgis` im Schema `postgis`; `public` trägt **0**
  Routinen (der A3-Fall bleibt damit aus der Matrix);
- nach `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` — dem, was
  `dialect_clean` tut — steht die Extension unverändert da, und `geometry`
  löst weiter auf.

Weil das Init-Verzeichnis nur beim **ersten** Volume-Init läuft, prüft der
Matrix-Lauf die Lage der Extension jetzt selbst (`mcp_e2e_assert_postgis` in
`lib/dialects.sh`) und scheitert laut mit dem Hinweis auf `make
mcp-e2e-purge`. Ohne die Prüfung liefe ein Host mit altem Volume still ohne
PostGIS.

**DoD 1 erfüllt:** `make mcp-e2e-compare-matrix` (unverändert grün, die
Erwartungsdatei bewegt sich nicht), `make mcp-e2e-roundtrip` und
`make mcp-e2e-smoke` grün.


### P0 Teile 2 bis 5 — Seeds, Anmerkungen und der Silent-Loss-Check (2026-09-17)

**Die Anmerkungen** stehen im Kopf jeder Seed-Datei und in der README. Format:

```
-- seed: <tabelle>.<spalte> | paket: <Paket> | quelle: <form> [| code: <Code|keinen>]
--   [generation: <gen>]
--   [ausdruck: <text>]
--   ziel <dialekt>: <form> | code: <Code|keinen> [| generation: <gen>] [| ausdruck: <text>]
```

Die Form ist eine kurze Schreibweise des neutralen Typs (`text(40)`,
`array(text)`, `identifier(auto)`, `decimal(12,2)`, `float`), daneben die
Erzeugung (`identity(always)`, `computed(stored)`) und der rohe
Berechnungsausdruck. Sie beschreiben den **Zielzustand** nach den vier Plänen.

**Das Modell des Checks — eine Abweichung vom Plan, bewusst.** Der Check
braucht die neutralen Formen; die stehen im Schema-Dokument, das MCP-Artefakt
ist YAML, und der Harness hat keinen YAML-Leser (`jq` liest kein YAML, `yq`
ist keine Voraussetzung). Der Lauf liest deshalb je Dialekt ein **zweites**
Reverse über die CLI (`schema reverse --format json`) — dieselbe Datenbank,
derselbe Reader, dieselbe Konfigurationsdatei wie der MCP-Server (mit der
Präferenz `identity`). Die **Zellen** der Matrix kommen unverändert aus dem
MCP-Reverse; nur die Formen des Checks aus dem JSON. Die Reports bleiben YAML
und werden mit einem selbstprüfenden awk gelesen: die Zahlen aus `summary`
müssen zu den gelesenen Listen passen, sonst scheitert der Lauf laut.

**Vier Klassen**, wie geplant: `quelle` (Form, Erzeugung, Ausdruck und der
Code des Reverse-Reports), `reftype` (ein `ref_type` ohne Eintrag in
`custom_types`, auf **jedem** Reverse), `verloren` (eine angemerkte Spalte, die
der Reverse nicht hat; eine Spalte der Quelle, die im Ziel fehlt und nicht in
`skipped_objects` steht) und `ziel` (die Zielform samt M1: eine Degradierung
ohne Code im Generate-Report, und eine Degradierung, deren Anmerkung „keinen"
sagt). Dazu eine fünfte Selbstprüfung, die der Plan nicht nennt: eine
**Seed-Spalte ohne Anmerkung** ist ein Fehlschlag — sonst wäre ein Seed, den
niemand angemerkt hat, im Check unsichtbar.

**Die Liste bekannter Befunde** steht in
[`examples/mcp-e2e/scripts/lib/silent-loss.sh`](../../../examples/mcp-e2e/scripts/lib/silent-loss.sh), nicht in
der Erwartungsdatei: 24 Einträge, jeder wortgleich der Verstoß und dahinter das
Paket. Sie sind das Arbeitsvorrat-Bild der Pläne 2 und 4:

| Befund | Anzahl | Paket |
| --- | --- | --- |
| `R301` (unbekanntes Array-Element), `R402` (`json`), `R404` (`numeric` ohne Präzision), `R221` (SQLite `NUMERIC`) fehlen im Reverse-Report | 4 | N1, P8, P9 (Plan 2) |
| die `integer`-Identity liest als `identifier` ohne Modus | 2 | S1 (Plan 2) |
| Array-Verlust ohne `W162` auf MySQL und SQLite | 8 | P5 (Plan 2) |
| `ALWAYS` ohne Entsprechung, ohne `W163` auf MySQL und SQLite | 4 | P10, S1 (Plan 2) |
| SQL Server: `integer`+`ALWAYS` → `identifier(auto)` ohne Code | 1 | S1, Zielseite (Plan 2) |
| SQLite verschweigt Typmarke und Länge (`json` → `text`, `text(40)` → `text`) | 4 | neuer Befund, [`../open/sqlite-generate-verschweigt-typmarke-und-laenge.md`](../open/sqlite-generate-verschweigt-typmarke-und-laenge.md) |
| SQL Server leitet Typ **und** Nullbarkeit einer berechneten Spalte ab (`text(10)` → `text(5)`) | 1 | D1 (Plan 4) |

**Zwei neue Befunde**, die der Check gefunden hat und die kein Paket trägt:

1. **SQLite verschweigt, was es verwirft** — die deklarierte Länge und die
   Typmarke `json` fallen ohne Code weg, während der Präzisionsverlust bei
   `decimal` `W200` bekommt. Eigener `open/`-Eintrag (oben).
2. **Der Reverse-Report von PostgreSQL trägt `R400`** („extension postgis is
   installed") — die Folge von Teil 1, gepinnt in `REPORT_CODES_POSTGRESQL`.

**Was der Check **nicht** prüft:** Constraints, Indizes und Sichten. Die
Anmerkung hängt an einer Spalte; die Namen der SQLite-Constraints (P11) misst
die Zelle SQLite → SQL Server, nicht der Check.

**Sabotage-Protokoll P0** (je ein voller Matrix-Lauf, die Erwartungsdatei
blieb in jedem unverändert — Prüfsumme vorher und nachher gleich):

| Sabotage | Ergebnis |
| --- | --- |
| (a) eine Anmerkung nennt einen Code, den der Reverse-Report nicht trägt (`R999`) | rot: „quelle postgresql: sl_pg_text.free_text: der Reverse-Report nennt R999 nicht" |
| (b) eine Anmerkung für eine Spalte, die es nicht gibt | rot: „verloren postgresql: sl_pg_text.gibtsnicht: die Anmerkung nennt eine Spalte, die der Reverse nicht hat" |
| (f) eine Ziel-Degradierung mit einem Code, den der Generate-Report nicht trägt (`W999`) | rot: „ziel postgresql->sqlite: sl_pg_number.bounded: Degradierung ohne W999 im Generate-Report" |
| Selbstprüfung: eine Seed-Spalte ohne Anmerkung | rot: „anmerkung postgresql: sl_pg_text.id: Seed-Spalte ohne Anmerkung" |
| (d) dieselben drei mit `--update-expectations` | „Erwartungen NICHT geschrieben: 4 nicht pinnbare Abweichung(en)", Exit 2, Datei-Prüfsumme unverändert |
| (c) ein bekannter Befund, der nicht auftritt | rot: „bekannter Befund verschwunden — Liste nachziehen: …" |
| (e) ein kaputtes jq-Programm | rot: viermal „die Spalten des Reverse sind nicht lesbar: jq: error …" und zwanzigmal „bekannter Befund verschwunden" — nie „nichts gefunden" |
| Report mit falscher Zahl in `summary` (direkt geprüft) | `report_json` scheitert laut: „summary nennt 2 notes, gelesen wurden 1" |

**Drei Fehler, die erst die Sabotage zeigte** (alle behoben): die Prüfung auf
nicht angemerkte Seed-Spalten lief still leer (`$annotated | index(.)` — das
`.` ist in jq hinter dem Pipe der **Array**, nicht der Schlüssel); dieselbe
Falle in der Prüfung auf verlorene Spalten (dort hätte sie einen jq-Fehler
geworfen, der in der Pipeline verlorenging); und ein jq-Fehler des Erzeugers
ging in `erzeuger | bewerter` unter — die Auswertung läuft jetzt über eine
Variable und meldet den Fehler.


### Neu-Pin P0 — die Seeds und die zwei neuen Schlüsselfamilien (2026-09-17)

Ein eigener Commit, nur die Wirkung von P0. **24 Schlüssel**: 16 neue
(4 × `REPORT_CODES_*`, 12 × `GEN_CODES_*`) und 8 geänderte in den Zellen mit
Quelle PostgreSQL oder SQLite — genau die, die der Umbrella als betroffen
nennt. Vor dem Pin geprüft, jede Änderung erklärt:

| Zelle | vorher | nachher | Grund |
| --- | --- | --- | --- |
| PostgreSQL → MySQL | 6 | 11 | vier Array-Spalten (`array(…)` → `json`) und der Modus der `ALWAYS`-Identity |
| PostgreSQL → SQL Server | 5 | 11 | vier Arrays und zwei `json`-Spalten (→ `text`, je mit `W137`) |
| PostgreSQL → SQLite | 13 | 22 | dieselben acht Spalten, dazu `decimal(12,2)` → `float` (`W200`) und die Identity |
| SQLite → PostgreSQL | 3 | `APPLY-FAIL` (`relation "uq_0" already exists`) | **L5, vom Plan vorhergesagt**: zwei unbenannte mehrspaltige UNIQUE-Klauseln in zwei Tabellen ergeben zweimal `uq_0`. P11 öffnet die Zelle wieder |

Die neuen Codes je Reverse und je Generate sind erklärt: `R202:5` (SQLite rät
zur 64-Bit-Breite, jetzt an fünf Tabellen), `R301:1 R400:1` (PostgreSQL:
`interval` und die installierte PostGIS-Extension), `R205:1 R330:1` (MySQL),
`E053:4` (PostgreSQL-Casts in CHECK und Berechnung), `E057` (Partial-Index auf
MySQL; UNIQUE auf ungebundenem `text` in SQL Server), `W125:2` (MySQL verlangt
eine Präfixlänge für UNIQUE auf `TEXT`), `W137:6`, `W140`, `W200`.

Zwei aufeinanderfolgende Läufe auf dem Endstand sind identisch (Zellen, Codes
und die Liste der bekannten Befunde).


### S4 — SQLite-Migrate behält die Aktionen eines Fremdschlüssels (2026-09-17)

**Zuerst gemessen** (SQLite 3.45, `schema migrate --execute`, Post-Compare
eingeschlossen):

| Fall | `PRAGMA foreign_key_list` danach | Ausgang |
| --- | --- | --- |
| (a) neue Tabelle mit `ON DELETE CASCADE ON UPDATE RESTRICT` | `NO ACTION` / `NO ACTION` | Exit **5**, „Post-execute compare detected drift" |
| (b) Nullbarkeits-Änderung an einer bestehenden Tabelle mit denselben Aktionen (Rebuild) | vorher `RESTRICT`/`CASCADE`, danach `NO ACTION`/`NO ACTION` | Exit **5**, dieselbe Meldung |

Der Posten ist also **laut** — aber ein Defekt: der Post-Compare meldet die
Drift erst, nachdem das Löschverhalten der Datenbank schon ein anderes ist.
Der Generate-Pfad war nie betroffen.

**Gebaut:** `SqliteDiffSqlBuilders.constraintLine` schreibt `ON DELETE` und
`ON UPDATE` über dieselbe `referentialActionSql` wie der Generate-Pfad. Beide
Emitter, die eine `CREATE TABLE` schreiben, gehen darüber
(`SqliteDiffSimpleOps`, `SqliteRebuildRenderer`).

**Doku:** `spec/ddl-generation-rules.md` sagt zu Fremdschlüsseln im
Migrate-Pfad nichts, was nachzuziehen wäre (3.5 nennt nur „Foreign Keys inline
oder als `CONSTRAINT`", 3.7 den Rebuild als Mechanik) — die Aktionen sind Teil
des Modells, ihr Erhalt ist keine neue Regel. Die Spec bleibt, wie sie ist;
CHANGELOG „Fixed".

**Sabotage S-S4** (Aktionen wieder weggelassen): `SqliteForeignKeyActionsDiffTest`
rot (2 von 757 Tests in `driver-sqlite`) **und** beide Integrationsfälle rot,
mit dem Drift-Exit 5 im Bericht. Rücknahme per Prüfsumme belegt, danach grün
(`make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`).

**`make sample-db-types-smoke` war rot — aus einem anderen Grund.** Sein
Rollback-Schritt T5 verlangte wörtlich `schema-fingerprint-v7`, während der
Algorithmus längst bei `v16` steht (`MigrationFingerprint.ALGORITHM`;
festgeschrieben am 2026-07-03, angehoben zuletzt am 2026-09-10). Die Zusicherung
prüft jetzt, **dass** das Artefakt einen `schema-fingerprint-v<N>` nennt, nicht
welche Nummer — sonst rostet sie bei jeder Anhebung wieder fest. Danach ist der
Smoke grün (eigener Commit).

**Matrix:** unverändert, wie der Plan es erwartet — die Matrix generiert, sie
migriert nicht.


## Akzeptanzkriterien

1. Ein MySQL-Reverse mit Introducer, Backslash-Escape und Backtick-Quoting
   ist gültig und für PostgreSQL, SQLite und SQL Server portabel; PostgreSQL ↔
   MySQL meldet den CHECK nicht mehr; die MySQL-Zeile misst (P6).
2. Die Matrix fährt native Seeds und den Silent-Loss-Check mit vier Klassen;
   jeder stille Verlust ist benannt oder als bekannter Befund mit seinem Paket
   gelistet, und `--update-expectations` pinnt keinen (P0).
3. `schema migrate` gegen SQLite behält die Aktionen eines Fremdschlüssels,
   auch über einen Rebuild (S4).
4. Der SQL-Server-Reverse liefert Berechnungsausdrücke ohne T-SQL-Quoting;
   SQL Server → PostgreSQL und → MySQL messen; keine Zelle rechnet still
   falsch (P12, E1).
5. Der SQLite-Reverse liefert Constraint-Namen aus der Quelle, gebildete Namen
   sind eindeutig und stabil, ein Fremdschlüssel ohne Spaltenliste ist
   lesbar; SQLite → SQL Server misst (P11).
6. Jeder Fix fällt nachweislich mit zurückgenommenem Fix.
7. Spec, Anwenderhandbuch und CHANGELOG sind je Paket nachgezogen. Plan 1
   vergibt keinen neuen Code.

## Verifikation

1. **Nulllinie:** `:test:integration-mysql` ist gemessen (Umbrella).
   `:test:integration-sqlite` wird vor S4 gemessen, `:test:integration-mssql`
   vor P12.
2. **Je Paket:**

   | Paket | Modul | Abnahme |
   | --- | --- | --- |
   | P6 | `:adapters:driven:driver-mysql` | `:test:integration-mysql`, Matrix |
   | P0 | `examples/mcp-e2e` | `make mcp-e2e-compare-matrix` (zweimal identisch), `make mcp-e2e-roundtrip`, `make mcp-e2e-smoke`; `bash -n`, shellcheck (Container) |
   | S4 | `:adapters:driven:driver-sqlite` | `:test:integration-sqlite`, `make sample-db-types-smoke` |
   | P12 | `:adapters:driven:driver-mssql` | `:test:integration-mssql`, Matrix |
   | P11 | `:adapters:driven:driver-sqlite` | `:test:integration-sqlite`, Matrix, `make sample-db-types-smoke` |

   Integrationsläufe über
   `make integration INTEGRATION_TASKS=":test:integration-<modul>:test"`.
3. **Die Folge der Neu-Pins** (je ein Commit, je die Schlüssel in der
   Nachricht): P6 (nach E1), die Seeds aus P0, P12 (nach E1), P11. Teil 1 von
   P0 und S4 ändern die Erwartungsdatei nicht; tun sie es doch, ist das ein
   Befund.
4. **Gates:** `make solid-suppression-gate` vor jedem Commit;
   `make docker-check MODULES=…` für die berührten Treiber; eine gemeinsame
   Normalisierung in `driver-common` (P6) einmal ohne `MODULES`;
   `make docs-check`; `make doc-immutable RANGE=origin/main..HEAD` vor dem
   Push.
5. **Was gemessen ist und was nicht:** C1, D5 (`Msg 2714`) und D6 sind
   gemessen; M8, M9, M10, N2, S4, I3 und F5 sind nur im Code geprüft. Die
   Pakete messen sie zuerst.

## Offen

- **E1** (Umbrella): sperrt die Neu-Pins von P6 und P12.
- **P12, Messung 3:** plant sie eine `AlterColumnGeneration`, wird der Ausweg
  eine Eigner-Frage.
- **P11, Stopp-Regel:** eine `names*`-Fähigkeit oder ein Generator, der
  Namen schreibt, wird eine Eigner-Frage.
