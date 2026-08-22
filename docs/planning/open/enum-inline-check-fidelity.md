# Enum-Inline-Fidelity im migrate-Pfad (2b): `TEXT`+`CHECK` + Reverse-Rekonstruktion

> Status: **Vorabklärung/Entscheidung** — Design-Gabelung offen, noch nicht gebaut.
> Plan-Review 1 Runde eingearbeitet (B1–B5): Default MUSS „aus" (konform zu ADR 0027),
> Blast-Radius + Cross-Dialect-Semantik + Erkenner-Signatur + Diff↔Reverse-Kopplung
> geschärft; Balance Richtung **C/B** verschoben.
> **Wiedereinstieg (morgen):** Entscheidung treffen — **A** (Reverse-Rekonstruktion,
> Default-aus, Registry-Eintrag) / **B** (Kanonisierer-Fold, v8-Bump) / **C** (zurückstellen
> bis belegter Bedarf; Status quo W134). Siehe „## Cut-blockierende Entscheidungen".
> Danach: bei A/B Scope-Schnitt (AP + Abnahme) + `open`→`next`; bei C Ticket als
> geparkte Entscheidung belassen.
> Trigger: Folge-Slice („2b") aus dem Enum-Migrate-Slice
> ([`../done/enum-migrate-silent-degradation.md`](../done/enum-migrate-silent-degradation.md),
> Nicht-Scope). Der gelieferte Slice (Option 2a + `W134`) macht PG-inline- und
> SQLite-Enums im migrate-Pfad **laut** (bare `TEXT` + `W134`), aber **nicht treu**.
> 2b will die Werte-**Durchsetzung** (`CHECK`) + treuen Round-Trip.
> Severity/Charakter: **Fidelity-Upgrade, kein Bugfix** — der Status quo (W134, laut,
> driftfrei) ist vertretbar; ROI beim Schnitt abwägen.

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
[`reverse-preference-mechanism.md`](../../../spec/reverse-preference-mechanism.md))
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
(Slice 5 in [`mssql-dialect-scoping.md`](../in-progress/mssql-dialect-scoping.md)):

- rendert `MssqlDiff*` den CHECK **wie generate**, ist der Post-Compare-Drift
  ohne A oder B unvermeidbar;
- rendert er ihn **nicht**, divergieren generate und migrate fuer MSSQL genauso
  wie heute bei PG/SQLite — mit dem Zusatzproblem, dass die MSSQL-Goldens den
  CHECK bereits enthalten.

Das ist kein neues Ticket, sondern ein Datenpunkt zur Priorisierung: mit dem
vierten Dialekt ist „C" nicht mehr fuer alle Dialekte kostenlos.

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
