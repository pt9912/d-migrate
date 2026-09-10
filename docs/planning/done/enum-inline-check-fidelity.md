# Enum-Inline-Fidelity im migrate-Pfad (2b): `TEXT`+`CHECK`

> Status: **GEBAUT** (2026-09-10). Die Eigner-Entscheidung (Weg B, unten) ist
> umgesetzt, und zwar an beiden Stellen, die der Migrationslauf braucht:
> im Fingerabdruck ([ADR 0048](../../adr/0048-enum-wertevorrat-im-fingerprint.md),
> `v8`) und im zielbewussten Vergleich
> ([ADR 0055](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)).
> PostgreSQL und SQLite rendern den `CHECK` jetzt auch im Migrationspfad.
>
> **Der Befund war schwerer als das Ticket annahm.** Es führte den Status quo
> als „laut, driftfrei" und die Arbeit als „Fidelity-Upgrade, kein Bugfix".
> Gegen echte Container gemessen (2026-09-10) stimmte beides nicht:
>
> | Dialekt | gemessen, vor dieser Arbeit |
> | --- | --- |
> | PostgreSQL, SQLite | eine Enum-Spalte liess `schema migrate` bei **keinem** Lauf konvergieren — Exit 5 auch auf leerer Datenbank. Der authored Wertevorrat geht seit `v8` in den Abdruck ein, das Ziel trug ihn nirgends. |
> | SQL Server, Oracle | der **zweite** Lauf gegen unverändertes Soll löste den `CHECK`, den der erste angelegt hatte, und meldete danach Drift. Eine geänderte Werteliste wurde nie migriert. |
>
> Die Annahme „für PostgreSQL und SQLite folgenlos" in ADR 0048 betrachtete nur
> die Constraint-Seite; die Typseite trägt den Vorrat aber ebenso.

## Der Kern-Konflikt (Ist-Stand code-belegt 2026-07-05)

Heute round-trippt ein Enum **driftfrei**: authored `enum` → migrate bare `TEXT` →
reverse `text` → Post-Compare kanonisiert beide via `toSql` → `TEXT` → kein Drift.
Der Preis: keine Werte-Durchsetzung (→ `W134`).

2b will `CHECK (col IN (…))`. Aber sobald der Diff den CHECK rendert:

- authored: `enum`, **kein** separater CHECK.
- reversed: `text` **+ ein CHECK-`ConstraintDefinition`** — es gibt **keine**
  Enum-Rekonstruktion heute (SQLite:
  [`SqliteCheckConstraintScanner`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteCheckConstraintScanner.kt)
  liest `col IN (…)` als eigenständigen CHECK; PG:
  [`PostgresSchemaStructureReaders`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt)).
- Post-Compare: Typen matchen (beide → `TEXT`), aber die reversed-Seite trägt einen
  **CHECK, den die authored-Seite nicht hat → neue Constraint-Kante (Exit 5)**.

**2b-Diff allein erzeugt also genau den Drift, den es vermeiden will.** Der CHECK muss
beim Reverse zurück in den Enum gefaltet werden — das ist der „größte Reverse-Anteil".

## Design-Gabelung

| Ansatz | Was | Trade-off |
| ------ | --- | --------- |
| **A — Reverse-Rekonstruktion** | Reverse erkennt d-migrates Enum-CHECK-Form (`col IN ('string-literals')` auf TEXT-Spalte) → faltet zu `Enum(values)` + **unterdrückt** den separaten CHECK | Root-Fix, **kein** Fingerprint-Bump. „Ist ein `col IN (…)`-CHECK ein Enum?" ist **inhärent mehrdeutig** (Fremd-DBs) → ein [ADR-0027](../../adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md)-Registry-Fall (Reverse-Präferenz). |
| **B — Kanonisierer-Fold** | Post-Compare behandelt authored `enum` ≡ reversed `text`+`CHECK(col IN values)` | Fold spannt **Typ + Constraint** — der v7-Kanonisierer ist `(NeutralType)→NeutralType`, sieht Constraints nicht → neuer tabellen-ebener Mechanismus + wahrscheinlich **v8-Bump**. Genau das, was ADR 0027 vermied. |
| **C — Status quo** | Nichts; bare `TEXT` + `W134` bleibt | Keine Durchsetzung, aber laut + driftfrei. 2b nicht bauen. |

## Empfehlung (nach Plan-Review geschärft — Balance verschoben)

Der Plan-Review (B1–B5) hat die Kosten von **Ansatz A** höher belegt als zunächst
dargestellt und die Balance verschoben. **Wenn** 2b gebaut wird, bleibt A (Reverse-
Rekonstruktion als Registry-Eintrag,
[`dialect-preference-mechanism.md`](../../../spec/dialect-preference-mechanism.md))
der prinzipiellste Root-Fix — aber mit drei geschärften Vorbehalten:

- **Default MUSS „aus" sein (B1).** [ADR 0027](../../adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md)
  (Entscheidung 1) + Spec §1 schreiben einen konservativen, **byte-identischen** Default
  ohne Regression vor. Heute liest der Reverse `col IN (…)` als `text` + separaten CHECK;
  der ADR-konforme Default ist damit **„aus"**, nicht „an" (meine ursprüngliche Neigung
  war eine ADR-Verletzung). **Zielkonflikt:** mit Default „aus" liefert 2b den treuen
  Enum-Round-Trip **nur per Opt-in** — per Default bleibt es `TEXT` + W134, das eigentliche
  2b-Ziel wird by-default nicht erreicht. Diese Spannung ist zu entscheiden.
- **Breiter Blast-Radius (B4).** A wirkt an der **Reverse-Wurzel** — es ändert
  `schema reverse`, `data transfer` und jedes `generate` aus einem reversten Modell
  (Goldens, Cross-Dialect-Ausgaben), **nicht nur** den migrate-Post-Compare. Ansatz B
  bliebe im Post-Compare (schmaler Radius) — ein bislang ungenannter Trade-off **zugunsten B**.
- **Cross-Dialect nicht semantik-neutral (B2).** Ein gefalteter Fremd-CHECK
  `status IN ('a','b')` wird über den MySQL-2a-Pfad zu nativem `ENUM('a','b')` — andere
  Ordnungs-/Speicher-/Validierungssemantik als ein CHECK. „An" als Default wäre
  cross-dialekt riskant, nicht nur „konservativ falsch geraten".

**Konsequenz:** Die Gabelung ist offener als zuerst dargestellt. Angesichts der Kosten
(A: Radius + Default-Zielkonflikt) bzw. des v8-Bumps (B) ist **C (Status quo, W134) eine
legitime Default-Wahl** — 2b erst bei belegtem Fidelity-Bedarf bauen (Fidelity-Upgrade,
kein Bugfix).

## Cut-blockierende Entscheidungen (vor `next/`)

1. **Ansatz A / B / C** — der Review verschob die Balance Richtung C/B; A bleibt der
   Root-Fix, aber mit Radius- und Default-Kosten.
2. **Default der Reverse-Präferenz (falls A):** ADR-konform **„aus"** (B1) — womit die
   Treue nur Opt-in ist. „An" wäre eine Regression gegen ADR 0027 und cross-dialekt riskant (B2).
3. **Kopplung Diff↔Reverse — Struktur, nicht Detail (B5).** Rendert der Diff `CHECK`
   **immer** (deterministische Vorwärts-Durchsetzung, ändert Goldens für alle Enum-Nutzer,
   erzeugt aber mit Default-aus-Reverse genau die CHECK-Kante) — oder toggelt **eine**
   Präferenz beide Seiten (Default = heutiges bare-`TEXT`+W134)? Das bestimmt Testmatrix,
   Golden-Auswirkung und ob DoD-4 (`generate == migrate`) für Inline überhaupt erreichbar
   ist. Muss **vor** `next/` fallen.
4. **Erkenner-Signatur (falls A, B3) — exakt festlegen:** Ganz-Ausdruck-Match,
   **single-column**, **nur String-Literale**, TEXT-Spalte. **Ausgeschlossen:** `col IN
   (1,2,3)` (numerisch), `col NOT IN (…)`, zusammengesetzt (`… AND …`), funktionsbasiert
   (`lower(col) IN …`), mehrspaltig. Zu gierig korrumpiert das Neutralmodell (Fremd-CHECK →
   falscher Enum), zu eng verfehlt d-migrates eigene Ausgabe.

## MSSQL als vierter Dialekt — live belegt (2026-08-22, Slice 4)

Der MSSQL-Slice verschiebt die Gabelung, weil SQL Server der erste Dialekt ist,
dessen **generate**-Pfad den CHECK von Anfang an rendert und dessen Reverse ihn
zurueckliest. Belegt vom Integrationstest
[`MssqlPostCompareFingerprintIntegrationTest`](../../../test/integration-mssql/src/test/kotlin/dev/dmigrate/driver/mssql/MssqlPostCompareFingerprintIntegrationTest.kt)
gegen ein echtes SQL Server 2022:

- authored `enum(values)` → generate `NVARCHAR(<laengster Wert>)` +
  `CONSTRAINT ck_<t>_<c> CHECK (col IN (…))`
  ([`MssqlColumnConstraintHelper`](../../../adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlColumnConstraintHelper.kt)),
- reverse liest `text(5)` **plus** einen eigenstaendigen CHECK,
- die v7-Typprojektion faltet die TYP-Seite sauber
  (`enum(red,green)` ≡ `text(5)`), die Constraint-Kante bleibt.

Das ist genau der im Abschnitt „Der Kern-Konflikt" beschriebene Drift — bei
PG/SQLite erst nach einem 2b-Bau, bei MSSQL **schon heute im generate-Pfad**.
Der Unterschied ist praktisch: der Status quo „C" (bare `TEXT` + `W134`,
driftfrei) existiert fuer MSSQL gar nicht, denn der Generator rendert bereits
treu. Damit faellt die Entscheidung spaetestens mit dem MSSQL-Diff-Pfad
(Slice 5 in [`mssql-dialect-scoping.md`](../done/mssql-dialect-scoping.md)):

- rendert `MssqlDiff*` den CHECK **wie generate**, ist der Post-Compare-Drift
  ohne A oder B unvermeidbar;
- rendert er ihn **nicht**, divergieren generate und migrate fuer MSSQL genauso
  wie heute bei PG/SQLite — mit dem Zusatzproblem, dass die MSSQL-Goldens den
  CHECK bereits enthalten.

Das ist kein neues Ticket, sondern ein Datenpunkt zur Priorisierung: mit dem
vierten Dialekt ist „C" nicht mehr fuer alle Dialekte kostenlos.

## Nachtrag 2026-08-23: die Entscheidung blockiert MSSQL-Sub-Slice 5e

Nach dem Bau der MSSQL-Sub-Slices 5a–5d ist die Lage schärfer als oben
beschrieben — zwei Dinge sind jetzt belegt statt vermutet.

**1. Der Diff-Pfad rendert den CHECK bereits.** Die frühere Formulierung „mit
dem MSSQL-Diff-Pfad fällt die Entscheidung" unterstellte, das Rendern sei noch
offen. Ist es nicht: `CreateTable` und `AddColumn` nutzen seit 5a den
Spalten-Helfer des Generate-Pfads, `AlterCustomType` fächert seit 5c auf jede
nutzende Spalte auf, und der CHECK ist seit 5c Teil des
Abhängigkeits-Tanzes. Generate und migrate bauen dieselbe Tabelle — das war
nie die Frage.

**2. Der Postcompare schlägt dadurch bei JEDER Enum-Spalte an.**
[`SchemaMigrateExecutionStage.runPostCompare`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateExecutionStage.kt)
vergleicht das nach `--execute` frisch zurückgelesene Ziel gegen das authored
Soll-Schema, und
[`MigrationFingerprint`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/MigrationFingerprint.kt)
führt Constraints namentlich (`constraints[n]`, `constraint=<name>`). Authored
steht dort ein `enum` ohne Constraint, zurückgelesen ein `ck_<t>_<c>` — die
Fingerprints unterscheiden sich zwangsläufig. Eine fehlerfrei gelaufene
Migration meldet damit Drift.

Für Sub-Slice 5e („`schema migrate` ist für mssql nutzbar") ist das kein
Fidelity-Wunsch mehr, sondern ein Blocker: das Kommando wäre für jedes Schema
mit Enum-Spalte unbenutzbar.

### Was das für die Gabelung heißt

- **A (Reverse-Rekonstruktion)** hat bei MSSQL einen Unterscheider, den PG und
  SQLite nicht haben: d-migrate benennt den CHECK selbst
  (`ck_<tabelle>_<spalte>`, [`MssqlConstraintNames`](../../../adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlConstraintNames.kt)).
  Ein konventionsbenannter einspaltiger `IN`-Listen-CHECK auf einer
  NVARCHAR-Spalte ist damit kein Ratespiel. Der Zielkonflikt aus B1 bleibt
  aber: ADR 0027 verlangt einen konservativen Default, und mit „aus" bleibt der
  Drift per Default bestehen — 5e wäre weiterhin blockiert.
- **B (Fold im Vergleich)** löst genau den blockierenden Teil und sonst nichts.
  Als **symmetrische Normalisierung vor dem Fingerprint** formuliert — auf
  beiden Seiten Constraints entfernen, die dem Muster „konventionsbenannt +
  einspaltig + `IN`-Liste über genau diese Spalte" entsprechen — ist die Regel
  präzise statt heuristisch, und `schema reverse` bleibt zeichengleich.
- **C (Status quo)** existiert für MSSQL nicht.
- **D (Rückzug)**, hier der Vollständigkeit halber: den CHECK im
  MSSQL-Generate-Pfad weglassen und wie PG/SQLite auf `W134` zurückfallen. Das
  stellt Driftfreiheit her, gibt aber die Werte-Durchsetzung auf, die SQL
  Server heute als einziger Dialekt hat, und ändert die Goldens.

### Empfehlung

**B, als symmetrische Vor-Fingerprint-Normalisierung** — schmalster Radius, kein
Konflikt mit ADR 0027, und die Namenskonvention macht die Regel exakt. A bleibt
danach als eigentlicher Root-Fix möglich, wenn ein treuer Enum-Round-Trip auch
in `schema reverse` gewünscht ist; die beiden schließen sich nicht aus.

### Wie die drei bestehenden Dialekte es heute halten (nachgesehen 2026-08-23)

| Dialekt | Enum mit `refType` | Enum inline (nur Werte) |
| --- | --- | --- |
| **MySQL** | nativer `ENUM(...)` an der Spalte | nativer `ENUM(...)`; der Reverse liest ihn als `enum` zurück — **das Problem existiert dort nicht** |
| **PostgreSQL** | nativer `CREATE TYPE … AS ENUM`, round-trippt sauber | *generate*: `TEXT + CHECK (col IN (…))` (unbenannt) — *migrate*: **bare TEXT ohne CHECK** plus lautes `W134` |
| **SQLite** | wie PG inline | bare TEXT + `W134` |
| **MS SQL Server** | NVARCHAR(n) + benannter CHECK (kein nativer Typ) | dasselbe |

Zwei von drei Dialekten verzichten im Migrate-Pfad also **bewusst** auf die
Durchsetzung, nehmen `generate ≠ migrate` in Kauf und machen es über `W134`
sichtbar. MSSQL ist der Ausreißer, weil sein Spalten-Helfer zwischen beiden
Pfaden geteilt ist.

**Die Asymmetrie, die gegen ein blosses Nachziehen spricht:** PGs
`W134`-Hinweis („model the enum as a custom type") ist ein echter Ausweg — dort
entsteht dann ein nativer Typ. Bei MSSQL gibt es diesen Ausweg nicht, weil auch
ein `refType`-Enum zu NVARCHAR + CHECK wird. Unter Weg D hätte SQL Server also
**keine** Möglichkeit, Werte-Durchsetzung über `migrate` zu bekommen.

## Entscheidung (Eigner, 2026-08-23): Weg B

Der Vergleich wird normalisiert, die gerenderte Datenbank bleibt wie sie ist.

**Verfeinerung gegenüber der ursprünglichen Formulierung:** nicht „den CHECK
auf beiden Seiten entfernen" — das machte eine ganze Constraint-Form für die
Drift-Erkennung unsichtbar. Stattdessen **beide Darstellungen auf dieselbe
kanonische Form bringen**: eine Textspalte, über der genau ein CHECK der Form
`<spalte> IN (<String-Literale>)` liegt, projiziert als `Enum(values)` **ohne**
diesen Constraint. Damit gilt:

- authored `enum(red, green)` → `Enum([red, green])`
- zurückgelesen `text(5)` + `CHECK (mood IN ('red','green'))` → `Enum([red, green])`
- ein absichtlich von Hand geschriebener `IN`-CHECK verhält sich auf beiden
  Seiten gleich — fehlt er im Ziel, meldet der Vergleich weiterhin Drift.

Die Regel ist formbasiert, nicht namensbasiert, und damit dialektunabhängig.

**Konsequenz, die den Schnitt bestimmt:** die Projektion liegt in
[`MigrationFingerprint`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/MigrationFingerprint.kt)
(`hexagon/core`), gilt für **alle** Dialekte, und der Algorithmus-Stempel geht
selbst in den Hash ein (`schema-fingerprint-v7`). Es braucht also einen
**v8-Sprung**, und der berührt gespeicherte Plan-Artefakte,
`allowedPostUpFingerprints` und die Rollback-Drift-Prüfung.

Das ist damit **kein Teil von MSSQL-5e**, sondern ein eigener, dialekt-
übergreifender Schnitt, von dem 5e abhängt. Er ist geschnitten als
[`fingerprint-v8-enum-check-projection.md`](../done/fingerprint-v8-enum-check-projection.md).

## Code-Fakten

- Generate rendert bereits treu: PG
  [`PostgresColumnConstraintHelper`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresColumnConstraintHelper.kt)
  Inline-Zweig `TEXT` + `CHECK (col IN (…))`; SQLite
  [`SqliteColumnConstraintHelper`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteColumnConstraintHelper.kt)
  (`generateEnumInlineColumn`). Der Diff-Pfad ist der Ausreißer (bare `TEXT` + `W134`):
  [`PostgresDiffSqlBuilders`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffSqlBuilders.kt)
  / [`SqliteDiffSqlBuilders`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSqlBuilders.kt).
- `W134` (`SqliteEnumDegradation` + PG/MySQL-Helper) retiriert für die dann-treuen
  PG-inline/SQLite-Fälle; bleibt für echte Rest-Degradation (dangling `refType`).

## Nicht-Scope

- MySQL (natives `ENUM`) und PG-`refType` (`CREATE TYPE`) sind bereits treu (2a) —
  unberührt.
- Keine Änderung an der v7-Typ-Kanonisierung, falls Ansatz A (Root-Fix am Reverse).
- **Kein „nur migrate" (Review B4):** falls A, ist der Reverse-Wurzel-Eingriff
  **explizit In-Scope** über `schema reverse` / `data transfer` / `generate`-aus-reversten-
  Modellen (Goldens, Cross-Dialect) — nicht als migrate-lokal darstellen.

## Was gebaut wurde (2026-09-10)

- **Eine Erkennung für beide Projektionen.** `EnumCheckProjection` liegt in
  `dev.dmigrate.core.diff` und wird von `MigrationFingerprint` **und**
  `TableComparator` benutzt. Der Wertevorrat ist im Vergleich eine eigene
  Dimension der Spalte: der Zieldialekt faltet `enum` und Textspalte auf
  denselben Typ, über den Typvergleich wäre eine geänderte Werteliste also
  nicht mehr zu sehen.
- **PostgreSQLs Normalform wird gelesen** (`spalte = ANY (ARRAY['a'::text, …])`,
  Casts an Literalen und Spalte, einelementige Liste als `=`), gegen einen
  echten Container gemessen und dort festgehalten
  (`PostgresEnumCheckReverseFormIntegrationTest`). Dafür `schema-fingerprint-v16`.
- **Der Diff-Pfad rendert den CHECK** — PostgreSQL unbenannt (der Server
  benennt selbst), SQLite benannt (`ck_<tabelle>_<spalte>`), weil ein
  unbenannter Constraint aus `sqlite_master` nicht als Constraint zurückkommt.
  Die Klausel selbst kommt aus `EnumValueCheck` in `driver-common`, damit
  `generate` und `migrate` wortgleich schreiben.
- **Zwei Stellen fragten nach einer Umwandlung, wo keine stattfindet**: der
  PostgreSQL-ALTER verlangte eine `USING`-Angabe, SQLites Cast-Matrix eine
  Freigabe. Beide fragen jetzt erst, wenn sich der deklarierte Typ ändert.
- **`W134` retiriert** für die dann treuen Fälle und bleibt für den Rest: ein
  `enum` ohne Werte und ohne `ref_type`.

Abgenommen live: `PostgresEnumMigrateConvergenceIntegrationTest`,
`SqliteEnumMigrateConvergenceIntegrationTest`,
`MssqlEnumMigrateConvergenceIntegrationTest` — anlegen, zweiter Lauf mit null
Operationen, geänderte Werteliste, und die Datenbank weist einen Wert außerhalb
des Vorrats wirklich ab.

## Nicht mitgebaut

- **Weg A (Reverse rekonstruiert den Enum)** bleibt unangetastet: der Reverse
  liefert weiterhin Textspalte plus Constraint, nur der Vergleich sieht beide
  als dasselbe. Der Zielkonflikt aus
  [ADR 0027](../../adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md)
  stellt sich damit nicht.
- **Oracles ALTER-Pfad** rendert den gebundenen `VARCHAR2` + `CHECK` nur im
  `CreateTable`; eine Spalte, die per ALTER zum Enum wird, bekommt ungebundenes
  `VARCHAR2(4000)` und `W134`. Eigenes Ticket:
  [`oracle-alter-column-enum-check.md`](../open/oracle-alter-column-enum-check.md).
- **MySQLs Charset-Introducer-Form** wird nicht erkannt — folgenlos, weil MySQL
  einen nativen `ENUM` hat und dort kein CHECK entsteht (bekannte Grenze in
  ADR 0048).
