# `enum`-Werte gehen im `migrate`-Diff-Pfad still verloren (Generate rendert nativ)

> Status: **Scope-Schnitt 2026-07-05, Plan-Review eingearbeitet (F1–F7) — Option 2a + W134.**
> Vorabklärung geklärt, Scope entschieden (Entscheidung + Round-Trip-Matrix unten),
> Arbeitspakete + Abnahme geschnitten. Wandert mit dem ersten Code-Commit nach `in-progress/`.
> Hinweis (Review F2): Der Dateiname `enum-generate-silent-degradation.md` ist **historisch** —
> der Befund liegt im `migrate`/Diff-Pfad, **nicht** im Generate (der rendert nativ);
> Umbenennung (`…migrate-silent-degradation`) bei der Graduierung erwägen.
> Trigger: AP0-Probe-Matrix des Typ-Kanonisierungs-Slices
> ([`../done/postcompare-type-canonicalization-slice.md`](../done/postcompare-type-canonicalization-slice.md),
> Status-Update 2026-07-03) plus gezielte Lautstärke-Nachprüfung der Reports.
> Aktivierungsbedingung: Scope-Schnitt bei belegtem Fidelity-Bedarf (analog dem
> Vorgehen in [`pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md))
> oder Entscheidung für die Minimal-Variante „laute Note".
> Die frühere „## Offene Vorabklärung" ist per Code-Lesung (Runtime-HEAD, 2026-07-05)
> vollständig beantwortet — siehe „## Vorabklärung — geklärt"; offen bleibt nur die
> Scope-Wahl.

## Befund (live belegt 2026-07-03, Runtime-Image)

Ein Soll-Schema mit `val: { type: enum, values: ["red", "green"] }` rendert im
`migrate --execute`-Pfad auf **allen drei Dialekten** bloßes `TEXT`:

| Dialekt | Gerendertes DDL | Nativer Kandidat |
| ------- | --------------- | ---------------- |
| PostgreSQL | `"val" TEXT` | `CREATE TYPE … AS ENUM` |
| MySQL | `` `val` TEXT `` | natives `ENUM('red','green')` |
| SQLite | `"val" TEXT` | `TEXT` + `CHECK (val IN (…))` |

Die `values`-Liste wird dabei **vollständig verworfen** — im Ziel gibt es weder einen
nativen Enum-Typ noch einen CHECK, d. h. keine Werte-Durchsetzung. Und: der
Migrate-Report ist dazu **komplett still** (`diagnostics: []`, `blockers: []`, keine
Note auf stderr) — ein stiller Fidelity-Verlust, der dem Loud-Prinzip widerspricht
(Präzedenz: Fulltext-Degradationen sind mit Notes/W-Codes laut,
[ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md)-Muster).

Abgrenzung: Der Reverse liest das Ziel konsistent als `text` zurück; der daraus
folgende Post-Compare-Drift ist eine gewöhnliche Typ-Kante und wird vom
Kanonisierungs-Slice behandelt. **Dieses Ticket betrifft nur die Generate-Seite**
(Fidelity + Lautstärke), nicht den Post-Compare.

## Vorabklärung — geklärt (2026-07-05, Code-Lesung Runtime-HEAD)

Alle drei ursprünglich offenen Fragen sind beantwortet.

**1. Emittiert `schema generate` eine Note?** Nein — der Generate-Pfad ist ebenfalls
**still**, aber er *degradiert auch nicht*: er materialisiert Inline-Enums **nativ auf
allen drei Dialekten**. MySQL rendert `ENUM('red','green')`
(`MysqlColumnConstraintHelper.columnEnum`/`columnEnumInline`); PG und SQLite rendern
`TEXT` + `CHECK (col IN ('red','green'))`
(`PostgresColumnConstraintHelper.generateColumnSql` Inline-Zweig bzw.
`SqliteColumnConstraintHelper.generateEnumInlineColumn`); ein `refType`-Enum wird über den
Custom-Type materialisiert (PG `CREATE TYPE … AS ENUM` in
`PostgresTypeSequenceDdlSupport.generateCustomType` + Typreferenz auf der Spalte). Auf der
Generate-Seite gibt es also **nichts zu warnen** — die Fidelity ist dort schon da. Der
einzige stille Bare-`TEXT`-Fall im Generate ist ein Enum ohne `values` UND ohne auflösbaren
`refType`.

**2. Ist der PG-Generate-Pfad nativ?** **JA** (vorher „unverifiziert"). Damit ist die
Generate-vs-Migrate-Pfad-Inkonsistenz auf **allen drei Dialekten** bestätigt: der
Diff-/`migrate`-Pfad rendert überall bloßes `TEXT` über `*DiffSqlBuilders.columnLine` →
`typeMapper.toSql` (`MysqlDiffSqlBuilders`, `PostgresDiffSqlBuilders`,
`SqliteDiffSqlBuilders` — keiner hat einen Enum-Zweig), ohne Enforcement, ohne Diagnostic.
`schema generate` und `migrate` rendern damit verschiedene Spaltentypen für dasselbe Soll.
Die Vereinheitlichung ist **dialektübergreifend** zu schneiden, nicht MySQL-lokal.

**3. Verhältnis zu Custom-Types.** Geklärt: `refType`-Enums laufen bewusst über
Custom-Types; Inline-`values`-Enums sind der degradierte Inline-Pfad. Wichtig ist das
**asymmetrische Reverse-Verhalten** (bestimmt die Round-Trip-Kosten von Option 2):

- **MySQL** liest natives `ENUM(...)` als `NeutralType.Enum(values=[…])` zurück
  (`MysqlTypeMapping.extractEnumValues`) — treu.
- **PostgreSQL** liest eine `USER-DEFINED`-Enum-Spalte als `NeutralType.Enum(refType=…)` +
  `CustomTypeDefinition(kind=ENUM, values=…)` zurück
  (`PostgresTypeMapping.mapUserDefined` + `PostgresSchemaStructureReaders`) — treu **nur**
  für den `refType`-Weg; ein inline erzeugtes `TEXT`+`CHECK` liest PG als `Text`.
- **SQLite** rekonstruiert **nie** einen Enum: `TEXT` → `NeutralType.Text`, der `CHECK` wird
  unabhängig als separater `ConstraintDefinition` gelesen
  (`SqliteCheckConstraintScanner`) — der einzige verlustbehaftete Dialekt.

**Note-Fluss-Realität (entscheidet Option 1).** Der `migrate`-Report ist ein *anderes
Modell* als der Generate-Report: er trägt `DiffDiagnostic`
(→ `SchemaMigrateReport.diagnostics[]`), **nicht** `TransformationNote`. Eine laute Note im
`migrate`-Pfad ist daher kein `TransformationNote`, sondern ein
`ctx.warning(op, …, code = "W134")` in `*DiffTableOps.renderCreateTable` — **kein
Notes-Listen-Plumbing nötig** (`renderCreateTable` iteriert die Spalten bereits und hält
`op`+`ctx`). Nächster freier Cross-Dialect-W-Code: **W134** (W133 aktuell höchster).
Ledger-Eintrag in `ledger/warn-code-ledger-0.9.9.yaml` + `spec/ledger.md` nötig (das
Ledger driftet ohnehin, siehe
[`warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md)).

## Round-Trip-Matrix (falls Option 2: Diff-Pfad materialisiert wie Generate)

| Dialekt | Diff emittiert dann | Reverse liest | Post-Compare |
| ------- | ------------------- | ------------- | ------------ |
| MySQL | natives `ENUM('red','green')` | `Enum(values=[…])` | **sauber** (treu) |
| PG `refType` | Typreferenz + `CREATE TYPE … AS ENUM` | `Enum(refType)` + CustomType | **sauber** (treu) |
| PG inline | `TEXT CHECK (col IN (…))` | `Text` (keine Rekonstruktion) | **neue CHECK-Kante** |
| SQLite inline | `TEXT CHECK (col IN (…))` | `Text` + separater CHECK | **neue CHECK-Kante** |

→ Option 2 zerfällt sauber in **2a** (MySQL native + PG `refType` — treuer Round-Trip mit
bestehendem Reverse, **keine** neuen Post-Compare-Kanten) und **2b** (PG-inline +
SQLite-inline — braucht ein Reverse-Pendant „`TEXT` + `CHECK (col IN …)` → `Enum`" oder
einen Kanonisierer-Fold, sonst neue Post-Compare-Kanten). 2a ist ein sauberer Sub-Slice,
kein Stopgap-Carve-out.

## Scope-Optionen

> **Entschieden 2026-07-05: Option 2a + laute Residual.** Der Diff-Pfad materialisiert
> die treuen Fälle wie Generate (MySQL natives `ENUM`, PG `refType`-Typreferenz) über den
> bestehenden `*ColumnConstraintHelper`; die verbleibenden Inline-Fälle (PG + SQLite)
> werden mit `W134` laut gemacht (kein DDL-Change dort). Ergebnis: `migrate` == `generate`
> für die treuen Fälle, keine neuen Post-Compare-Kanten, nichts bleibt still. **2b**
> (Inline-Fidelity + Reverse-Rekonstruktion) bleibt als sauber geschnittener Folge-Slice.
> Umsetzung noch nicht begonnen.

1. **Minimal (laute Note):** `W134`-Diagnostic im `migrate`-Pfad für die degradierenden
   Fälle (PG + SQLite inline), kein DDL-Change. Behebt nur die Lautstärke; die
   Generate-vs-Migrate-Inkonsistenz bleibt bestehen. Ledger-Eintrag nach dem Muster aus
   [`warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md).
2a. **Fidelity-clean:** Diff-Pfad materialisiert die **treuen** Fälle wie Generate (MySQL
   natives `ENUM`, PG `refType`-Typreferenz) über den bestehenden `*ColumnConstraintHelper`.
   Behebt die Inkonsistenz real ohne neue Post-Compare-Kanten. Ergänzt um `W134` für die
   verbleibenden Inline-Fälle bleibt nichts still (empfohlener Interim-Schnitt).
2b. **Fidelity-full:** zusätzlich PG-inline + SQLite-inline `TEXT`+`CHECK` im Diff-Pfad
   **und** Reverse-Rekonstruktion `TEXT`+`CHECK (col IN …)` → `Enum` (bzw. Kanonisierer-Fold),
   damit alles treu round-trippt. Größter Schnitt (eigener Reverse-Anteil).

## Code-Fakten (Diff-Pfad-Sites, Exploration 2026-07-05)

- **Der Ausreißer:** die Diff-Column-Render-Sites haben keinen Enum-Zweig —
  [`MysqlDiffSqlBuilders.columnLine`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt)
  (Zeile 26),
  [`PostgresDiffSqlBuilders.columnLine`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffSqlBuilders.kt)
  (Zeile 28),
  [`SqliteDiffSqlBuilders.columnLine`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSqlBuilders.kt)
  — alle gehen über `typeMapper.toSql` → bloßes `TEXT`.
- **MySQL-Quelle zum Spiegeln:**
  [`MysqlColumnConstraintHelper.columnEnum`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlColumnConstraintHelper.kt)
  (Zeile 54, `→ columnEnumInline` Zeile 72).
- **PG-`CreateCustomType`-Op rendert `CREATE TYPE … AS ENUM` bereits**
  ([`PostgresDiffOtherOps.renderCreateCustomType`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffOtherOps.kt)
  Zeile 232) — für ein `refType`-Enum muss also **nur** die Diff-Column die (quotierte)
  Typreferenz statt `TEXT` rendern; der Typ selbst entsteht über den `CreateCustomType`-Op.
- **W134-Residual-Sites (Review F1 — nicht nur CreateTable!):** eine degradierende
  Enum-Spalte (PG inline-`values`, SQLite alle) kommt über **drei** Renderpfade, alle über
  `columnLine`: `renderCreateTable`, `renderAddColumn` (`ALTER TABLE ADD COLUMN`,
  [`PostgresDiffTableOps`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffTableOps.kt)
  Zeile 91 / `SqliteDiffSimpleOps.renderAddColumn`) und der SQLite-Table-Rebuild
  ([`SqliteRebuildRenderer`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt)
  Zeile 578). `W134` muss an **allen** feuern, sonst degradiert ein per `ADD COLUMN`/Rebuild
  ankommendes Enum weiter still. Mechanik: `ctx.warning(op, …, code)` existiert (PG
  `PostgresDiffTableOps` Zeile 159, SQLite `SqliteDiffSimpleOps` Zeile 138); WARNING-
  Diagnostics reichen ungefiltert durch `toResult()` in `MigrationDdlResult.diagnostics` →
  `SchemaMigrateReport.diagnostics[]` (nur Planner-Diagnostics werden auf BLOCKER gefiltert)
  — kein Notes-Plumbing. **W133 höchster → W134 frei** (W198 reservierte Sonderbande,
  kollidiert nicht). MySQL braucht kein W134 (2a ist nativ auf allen Pfaden, da `columnLine`
  gefixt wird).

## Arbeitspakete (Option 2a + W134)

**AP1 — MySQL-Diff nativer `ENUM`.** `MysqlDiffSqlBuilders.columnLine` bekommt einen
Enum-Zweig. **Teilbare Einheit (Review F5):** `MysqlColumnConstraintHelper.columnEnumInline(colName,
col, values)` (schmale Signatur, Zeile 72) — **nicht** `columnEnum` (braucht
`tableName`+`schema`; für MySQL irrelevant, da Enums dort stets Inline-Werte tragen). Diese
Funktion an eine für Generate-Helper **und** `DiffSqlBuilders` erreichbare Stelle extrahieren.
Unit-Test: Diff rendert `ENUM('red','green')` (nicht `TEXT`); Round-Trip clean (Reverse liest
ENUM→`Enum` schon).

**AP2 — PG-Diff `refType`-Typreferenz.** `PostgresDiffSqlBuilders.columnLine` rendert
für ein `refType`-Enum die quotierte Typreferenz statt `TEXT`; der `CreateCustomType`-Op
emittiert `CREATE TYPE` bereits. **Ordering ist evtl. eine NEUE Kante (Review F4):** heute
rendert die Spalte bloßes `TEXT`, also existiert keine `col.refType → CustomType`-
Abhängigkeit; nach AP2 muss `CreateCustomType` vor `CreateTable`/`AddColumn` liegen. Daher
(a) prüfen, ob der Dependency-Graph diese Kante schon modelliert, (b) sie **ergänzen**, falls
nicht. Tests: Frisch-Migrate-Ordering (Type vor Table) **und** `ADD COLUMN` gegen einen
**bereits existierenden** Typ (kein `CreateCustomType`-Op → die Referenz muss trotzdem
korrekt rendern). **Inline-values-Enum ohne `refType` fällt bewusst in AP3** (W134), nicht hierher.

**AP3 — W134-Residual (alle Renderpfade) + Ledger.** Für die degradierenden Inline-Fälle
(PG inline-`values` ohne `refType`; SQLite alle Enums) `W134`-Diagnostic an **allen drei**
Column-Render-Sites (Review F1): `renderCreateTable`, `renderAddColumn` **und** der
SQLite-Rebuild (`SqliteRebuildRenderer`) — sonst degradiert ein per `ADD COLUMN`/Rebuild
ankommendes Enum weiter still. **Nur UP-Richtung** (Review F6: der DOWN-Zweig von
`renderCreateTable` = `DROP TABLE` darf kein Doppel-Warning erzeugen). Kein DDL-Change dort;
ein gemeinsames Prädikat „degradierende Enum-Spalte?" + Emit-Helper vermeidet Duplikat über
die Sites. Ledger-Eintrag in `ledger/warn-code-ledger-0.9.9.yaml` + `spec/ledger.md`.
Unit-Tests: Report trägt `W134` für die Inline-Fälle über CreateTable **und** AddColumn,
**nicht** für MySQL / PG-`refType`; kein W134 in DOWN.

**AP4 — Doku.** CHANGELOG-`Fixed`/`Added`; Ticket graduieren (Closure); `make docs-check`.

**AP5 — Live-Abnahme.** Frisches `migrate --execute`: MySQL-`enum` → Ziel `ENUM('…')`;
PG-`refType`-Enum → `CREATE TYPE … AS ENUM` + typisierte Spalte; PG-inline + SQLite →
`TEXT` + **`W134` im Report**. `schema compare`/Post-Compare der treuen Fälle clean.

## Abnahme (Slice-DoD)

1. MySQL: frisches `migrate --execute` eines `enum`-Schemas → Ziel-Spalte
   `ENUM('red','green')` (nicht `TEXT`); Post-Compare/Round-Trip clean (kein neuer Drift).
2. PG `refType`-Enum: `CREATE TYPE … AS ENUM` + Spalte referenziert den Typ; Round-Trip clean.
3. PG inline-`values` + SQLite: Ziel bleibt `TEXT`, aber der Report trägt **`W134`**
   (nicht mehr still).
4. `schema generate` == `migrate` für die treuen Fälle (MySQL, PG `refType`).
5. **Keine neuen Post-Compare-Kanten** (2a-Invariante); Tests grün, Kover ≥ 90 % je
   berührtem Modul; Ledger-Eintrag vorhanden. (Die neuen Enum-Zweige sind reine,
   unit-testbare Funktionen — `columnLine`, `renderCreateTable`/`renderAddColumn` — und
   landen trotz der Live-JDBC-Excludes der `driver-*`-Module auf nicht-exkludierten Pfaden;
   Test-Placement entsprechend, Review F7.)

> **Residuale Divergenz (Review F3, bewusst):** für PG-inline + SQLite rendert `generate`
> weiterhin `TEXT`+`CHECK` (mit Enforcement), `migrate` bloßes `TEXT`+`W134` (ohne). Die
> `generate`≠`migrate`-Divergenz bleibt dort bestehen — 2a+W134 macht sie **laut**, schließt
> sie aber **nicht**; das leistet erst **2b**. DoD-4 gilt daher bewusst nur für die treuen Fälle.

## Nicht-Scope

- **2b (Inline-Fidelity + Reverse-Rekonstruktion)** — PG-inline + SQLite `TEXT`+`CHECK`
  im Diff-Pfad **plus** Reverse-Pendant `TEXT`+`CHECK (col IN …)` → `Enum` (bzw.
  Kanonisierer-Fold). Eigener Folge-Slice (größter Reverse-Anteil); bis dahin trägt W134.
- Keine Änderung am Kanonisierungs-Slice (dort bleibt `enum` eine Typ-Kante; sollte
  2b später native Typen einführen, ändern sich die Kanten-Tabellen dort
  mit — der Kompositions-Kanonisierer folgt automatisch).
