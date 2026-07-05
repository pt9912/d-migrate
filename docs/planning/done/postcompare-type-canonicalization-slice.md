# Slice: Dialektbewusste Fingerprint-Kanonisierung im Post-Compare (Typ-Abflachung + Single-Column-UNIQUE)

> Status: **Done — graduiert 2026-07-05.** Scope-Schnitt user-reviewt (R1–R3 entschieden),
> AP0 erledigt (Status-Update unten), AP1 geliefert (`cfe51d02`) + review-gehärtet R1
> (`13f4fb60`), AP2 geliefert (`b137b352`, v7 inkl. belegtem FK-Fold, Folds
> live-verifiziert), AP3 geliefert (Durchreichung + Plan-Artefakt-Algo-Feld,
> volle Typ-Matrix live grün), AP4 geliefert (`9d0bc833`, SQLite-UNIQUE-Reverse-Fold,
> live grün inkl. Rebuild-Szenario), Review-Härtung R2 (`91747294`, 5 Fixes),
> **AP7 geliefert** (`f7cde5df`, Plan-Konvergenz — live: Zweitlauf plant 0 Statements),
> AP5 geliefert (`smoke-types.sh` + `make sample-db-types-smoke`, Volllauf grün
> inkl. Rollback-Round-Trip mit v7-Artefakt = Abnahme 4).
> **AP6 geliefert (2026-07-05):** [ADR-0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md)
> (Entscheidung D1–D3) + Anwenderhandbuch-Fehlerbehebung (Fingerabdruck-
> Versionsbindung von Rollback-Artefakten/Overlays) + CHANGELOG-Eintrag.
> **Alle AP0–AP7 geliefert — Slice komplett, siehe [Closure](#closure).**
> Hervorgegangen aus dem `open/`-Ticket `sqlite-postcompare-type-flattening-drift.md`
> (aktiviert 2026-07-02, Scope-Schnitt als erster Arbeitsschritt vereinbart).
> Severity: **P2** (Korrektheitsdefekt der `migrate --execute`-Exit-Semantik: spec-valide
> Schemata können auf SQLite nie drift-frei frisch migriert werden) **plus** ein beim
> Schnitt aufgedeckter **Reverse-Fidelity-Bug** (SQLite verwirft inline-UNIQUE komplett,
> siehe Befund 2).
> Trigger: Live-Verifikation des Fulltext-Rebuild-Blocks
> ([`../done/sqlite-fulltext-rebuild-block.md`](../done/sqlite-fulltext-rebuild-block.md)),
> 2026-07-02.
> Präzedenz: Fingerprint-v3-Kanonisierung des impliziten `identifier`-PK
> ([`../done/migrate-postcompare-identifier-pk-drift.md`](../done/migrate-postcompare-identifier-pk-drift.md)).

## Ziel

Ein frisches `migrate --execute` gegen ein leeres SQLite-Ziel endet für spec-valide
Schemata mit **Exit 0** statt Drift-False-Positive **Exit 5** — durch dialektbewusste
Kanonisierung im Fingerprint-Vertrag (Typ-Abflachung + Single-Column-UNIQUE-Fold) und
Behebung des SQLite-Reverse-UNIQUE-Fidelity-Bugs. `schema compare` bleibt strukturell
unverändert streng.

## Befund 1: Typ-Abflachung (live belegt 2026-07-02)

Ein **frisches** `migrate --execute` gegen ein leeres SQLite-Ziel endet mit **Exit 5**
(„Post-execute compare detected drift"), sobald das Soll-Schema einen Typ enthält, den
[`SqliteTypeMapper`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt)
auf eine SQLite-Storage-Klasse abflacht — obwohl der Apply sauber durchläuft
(`execution.completed = true`, `executionError = null`, keine Diagnostics).

Probe-Matrix (Ein-Spalten-Schemata, Runtime-Image, jeweils frisches File-Target):

| Spaltentyp | Exit | Abflachung |
| ---------- | ---- | ---------- |
| `text` (Kontrollfall) | 0 | `TEXT` → `Text`, verlustfrei |
| `smallint` | 5 | `INTEGER` → Reverse liest `integer` |
| `biginteger` | 5 | `INTEGER` → Reverse liest `integer` |
| `boolean` | 5 | `INTEGER` → Reverse liest `integer` |
| `datetime` | 5 | `TEXT` → Reverse liest `text` |
| `decimal(10,2)` | 5 | `REAL` → Reverse liest `float` |

Mechanik: Generate flacht den Neutraltyp ab (`smallint`/`biginteger`/`boolean` →
`INTEGER`, `datetime`/`date`/`time`/`uuid`/`json`/… → `TEXT`, `decimal` → `REAL`), der
Reverse liest den deklarierten Storage-Typ zurück
([`SqliteTypeMapping`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt)),
und der Post-Compare vergleicht Neutraltypen wörtlich → Drift-False-Positive. Derselben
Familie gehören weitere, noch nicht live geprobte Kanten an (`text(50)`/`char(10)` →
`TEXT` ohne Länge, `uuid` → `TEXT`, `json` → `TEXT`); die Round-Trip-Projektion aus D1
deckt sie per Konstruktion mit ab (AP0 erweitert die Matrix).

Nur SQLite ist belegt; MySQL/PG werden in AP0 auf analoge Abflachungskanten geprobt
(z. B. MySQL `uuid`; MySQL `boolean` → `TINYINT(1)` liest der Reverse bereits als
`boolean` zurück — vermutlich Fixpunkt).

## Befund 2: UNIQUE-Asymmetrie — beim Schnitt korrigiert (Code-Exploration 2026-07-03)

Das Ursprungs-Ticket nahm an, der SQLite-Reverse lese den `sqlite_autoindex` eines
inline gerenderten `CONSTRAINT "uq_x" UNIQUE ("col")` als Spalten-`unique` zurück. **Das
stimmt nicht:**
[`SqliteMetadataQueries`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteMetadataQueries.kt)
überspringt **alle** `sqlite_autoindex_*`-Einträge, bevor
`SchemaReaderUtils.singleColumnUniqueFromIndices` /
`buildMultiColumnUniqueFromIndices`
([`SchemaReaderUtils`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/metadata/SchemaReaderUtils.kt))
sie sehen. Ein inline gerenderter UNIQUE-Constraint (Single- **und** Multi-Column) wird
beim Reverse **komplett verworfen** — die Reverse-Seite trägt weder benannten Constraint
noch `unique: true`. Nur user-erzeugte `CREATE UNIQUE INDEX` (origin `c`) werden gehoben.

Das ist über den Post-Compare hinaus ein **Fidelity-Bug**: Reverse einer Datenbank mit
inline-UNIQUE verliert den Constraint; ein anschließendes Generate erzeugt die Tabelle
**ohne** UNIQUE. (Live belegt ist bisher das Exit-5-Symptom beim FTS5-Rebuild-Slice,
[`../done/sqlite-fulltext-rebuild-recreate.md`](../done/sqlite-fulltext-rebuild-recreate.md);
der Komplett-Verlust ist Code-Befund und wird in AP0 live belegt.)

**PG/MySQL** lesen Single-Column-UNIQUE dagegen als `unique: true` zurück (Name fällt
weg). Dort ist die Asymmetrie eine reine Fingerprint-Frage: benannter Constraint im Soll
≠ Spaltenattribut im Reverse. Der
[`TableComparator`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableComparator.kt)
kanonisiert genau diese Äquivalenz in `normalizeConstraints` **bereits** (Grenze
`columns.size == 1`) — nur der Fingerprint zieht nicht nach.

## Code-Fakten (Exploration 2026-07-03)

- **Post-Compare = reiner Fingerprint-Vergleich.** `runPostCompare` in
  [`SchemaMigrateExecutionStage`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateExecutionStage.kt)
  re-introspiziert das Ziel, normalisiert und vergleicht zwei Hash-Strings; kein
  strukturierter Modell-Vergleich. Drift → Exit 5 im
  [`SchemaMigrateRunner`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateRunner.kt).
- **Fingerprint-Vertrag.**
  [`MigrationFingerprint`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/MigrationFingerprint.kt),
  `ALGORITHM = "schema-fingerprint-v6"`. Der Spaltentyp fließt an genau einer Stelle ein
  (`neutralType(col.type)` in `appendTables`); Spalten-`unique` und benannte Constraints
  werden **getrennt** projiziert (kein Fold — im Gegensatz zum `TableComparator`).
- **Persistenz-Verträge, die Fingerprints tragen:**
  - **Rollback-Artefakt**
    ([`RollbackArtefactBuilder`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/RollbackArtefactBuilder.kt)):
    `currentFingerprint` (Reverse-Pre), `desiredFingerprint` (Soll-YAML),
    `postUpFingerprint` (Reverse-Post) + `fingerprintAlgorithm` + `dialect`.
    `SchemaRollbackRunner.verifyTargetMatchesArtefact` re-berechnet den
    Ziel-Fingerprint frisch und vergleicht gegen `postUpFingerprint` (bzw.
    `allowedPostUpFingerprints`; im F.5.e-Recovery-Fall gegen den Soll-basierten
    `desiredFingerprint`). Algo-Guard + Parser-Lektion aus dem v6-Bump (`c4846667`)
    vorhanden. Ein Post-Down-Verify gegen `currentFingerprint` existiert nicht.
  - **Plan-Artefakt**
    ([`MigrationPlanArtifactBuilder`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/MigrationPlanArtifactBuilder.kt)):
    persistiert `sourceFingerprint`/`targetFingerprint` + `dialect`, ist heute
    **emit-only** (kein Rückles-/Verify-Pfad im Tool), trägt aber **kein**
    `fingerprintAlgorithm`-Feld — externe Konsumenten können die Werte ohne
    Algo-Kennung nicht interpretieren; wird in AP3 nachgerüstet (Entscheidung im
    Plan-Review 2026-07-03).
  - **Overlay-Preflight**
    ([`MigrationOverlayPreflight`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/MigrationOverlayPreflight.kt)):
    Overlays pinnen erwartete Soll-/Ist-Fingerprints und werden gegen frisch berechnete
    validiert → ein Bump invalidiert bestehende Overlays laut (Blocker-Diagnose), wie
    bei jedem bisherigen Bump.
- **Call-Sites von `MigrationFingerprint.compute` (Produktion):** Migrate-Runner
  (Post-Compare-Lambda + `currentFingerprint`/`desiredFingerprint`), Rollback-Runner
  (Verify) — alle mit Ziel-Dialekt-Kontext (`prepared.effectiveDialect`,
  `targetResolved.dialect`, `parsed.dialect`). Einzige dialekt-lose Stelle:
  `DiffPlanner.endpoint`
  ([`DiffPlanner`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/DiffPlanner.kt))
  — dessen Werte fließen aber ins Rollback- und Plan-Artefakt und (F.5.e) in den
  Recovery-Verify → Kanonisierer muss durch `DiffPlanner.plan()` durchgereicht werden.
  Ein Fingerprint-Gleichheits-Kurzschluss in der Planung existiert nicht (die Werte sind
  dort reine Metadaten).
- **`schema compare` ist fingerprint-frei** (nutzt nur den `SchemaComparator`-Pfad,
  Output-Modell ohne Fingerprint-Felder) und dialekt-mehrdeutig (beide Operanden können
  YAML sein) → bleibt per Konstruktion außerhalb des Scopes.

## Design-Entscheidungen

**D1 — Kanonisierung als Round-Trip-Projektion, nicht als Prädikat.**
`StructuralTransferTypeCompatibility`
([`TransferTypeCompatibility`](../../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/TransferTypeCompatibility.kt))
ist als paarweises Prädikat **keine Äquivalenzrelation** (reflexiv/symmetrisch, aber
nicht transitiv: `identifier`≡`integer` und `integer`≡`boolean`, aber
`identifier`≢`boolean`) — als Kanonisierungs-Substrat untauglich; zudem sind ihre
Integral-/DateTime-Sonderregeln Transfer-Semantik (Value-Widening), die im Post-Compare
echte Drift verschleiern würde. Stattdessen: eine **per-Dialekt-Projektion**
`canonicalize(t: NeutralType): NeutralType` mit der Semantik „welchen Neutraltyp liefert
der Ziel-Reverse nach dem Rendern von `t`" (Komposition der vorhandenen Vorwärts-
(`TypeMapper.toSql`) und Rückwärts-Abbildung des Drivers). Eigenschaften: **idempotent**
(Reverse-Output ist Fixpunkt) und automatisch **Identity für fidelity-erhaltende
Dialekte**. Einziger Identity-Carve-out: `geometry` — Subtyp/SRID reisen über
Dialekt-Metadaten (`AddGeometryColumn`, SRID-Attribut, PostGIS-Katalog), nicht durch
den deklarierten Typ-String, und der Reverse rekonstruiert sie; die Komposition könnte
das nicht transportieren. `fulltext` geht dagegen **durch die Komposition**
(Review-Härtung R1, siehe Status-Update): auf PG ist `tsvector` ein Fixpunkt, auf
SQLite/MySQL degradiert die Spalte real zu TEXT und der Reverse rekonstruiert nur den
FULLTEXT-**Index** — ein Identity-Carve-out hätte dort exakt die
False-Positive-Klasse dieses Slices wieder eingeführt.

**D2 — Uniform im Fingerprint-Vertrag, `schema-fingerprint-v6` → `v7`, dialekt-parametrisiert.**
Eine stille Projektion nur im Post-Compare wäre ein Vertragsbruch: der
`postUpFingerprint` wird ins Rollback-Artefakt persistiert und beim `schema rollback`
mit unverändertem `compute` re-berechnet → falscher `TARGET_STATE_MISMATCH`, den der
Algo-Guard nicht abfängt (exakt das von `c4846667` beseitigte Fehlerbild). Daher:
`MigrationFingerprint.compute(schema, canonicalizer)` (Default Identity), `ALGORITHM`
→ `v7`, **alle** Migrate-/Rollback-Call-Sites inkl. `DiffPlanner.plan()` reichen den
Ziel-Dialekt-Kanonisierer durch. Das Artefakt trägt den Dialekt bereits und der
Rollback-Verify erzwingt `TARGET_DIALECT_MISMATCH` → das Paar (Algorithmus, Dialekt)
bestimmt die Fingerprint-Funktion eindeutig, **kein neues Artefakt-Feld nötig**. Alte
v6-Artefakte lehnt der bestehende Algo-Guard laut ab
(`ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`, „regenerate").

**D3 — Scope-Entscheidung (a) ratifiziert: nur Post-Compare-/Fingerprint-Vertrag.**
`schema compare` und die Diff-Engine bleiben unberührt; ein gewolltes
`smallint→integer` bleibt dort ein echter Unterschied. Durch die Fingerprint-Freiheit
des Compare-Pfads ist das per Konstruktion erfüllt (Gegenprobe in der Abnahme). Die
gewollte Divergenz Fingerprint (dialektbewusst tolerant) ↔ Comparator (strukturell
streng) wird im `MigrationFingerprint`-KDoc dokumentiert.

**D4 — Scope-Entscheidung (b) entschieden: UNIQUE im selben Slice.**
Beide Kanonisierungen teilen denselben v7-Bump (zwei aufeinanderfolgende Bumps wären
unnötige Artefakt-Invalidierung). Der Fingerprint-Fold spiegelt
`TableComparator.normalizeConstraints` (benannter Single-Column-UNIQUE ↔
`unique: true`, Grenze `columns.size == 1`; Multi-Column bleibt benannter Constraint) —
er zieht den Fingerprint auf den bestehenden Comparator-Stand nach. Für SQLite
zusätzlich der Reverse-Fix (AP4), sonst bleibt die Reverse-Seite leer und der Fold
greift ins Leere.

**D5 — Kanonisierer als Driver-Port-Methode.**
Neues `fun interface NeutralTypeCanonicalizer` in ports-common + Methode auf
[`DatabaseDriver`](../../../hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt)
nach dem `transferCompatibility()`-Muster: Default **Identity** (konservativ — ein
Driver ohne explizite Abflachungs-Deklaration kanonisiert nichts weg), SQLite liefert
die echte Faltung; PG/MySQL nur bei in AP0 belegten Kanten. `TypeMapper` bleibt
gekapselt (wird weiterhin nicht auf dem Driver-Port exponiert).

## Arbeitspakete

**AP0 — Proben & Reproducer (vor dem Bau).**
Probe-Matrix als automatisierbare Reproducer festhalten (Ein-Spalten-Schemata gegen
frisches File-Target) und erweitern: `text(50)`, `char(10)`, `uuid`, `json`, `enum`
auf SQLite; systematische Round-Trip-Probe aller Neutraltypen auf MySQL/PG
(Kanten-Tabelle je Dialekt als AP1-Input); Multi-Column-inline-UNIQUE auf SQLite live
belegen (erwartet: Reverse verwirft). DoD: belegte Kanten-Tabelle je Dialekt;
`enum`-Erwartung geklärt (vermutlich zusätzliche CHECK-Constraint-Drift → falls ja,
eigenes Ticket, nicht dieser Slice).
**→ ERLEDIGT 2026-07-03**, Ergebnisse und Scope-Konsequenzen im
Status-Update am Dokumentende.

**AP1 — Port + SQLite-Kanonisierer.**
`NeutralTypeCanonicalizer` (ports-common) + `DatabaseDriver`-Methode (Default Identity,
D5). SQLite-Implementierung als **Live-Komposition** (Review-Entscheidung 2026-07-03):
`canonicalize(t) = SqliteTypeMapping.mapColumn(SqliteTypeMapper().toSql(t))` über einen
dünnen Adapter, der die Reverse-Notes verwirft; **explizite Identity-Ausnahme** nur für
`geometry` (Fidelity läuft nicht über den deklarierten Spaltentyp, D1; `fulltext` geht
durch die Komposition — Review-Härtung R1) — keine zweite Abbildungstabelle im
Produktionscode. Property-Tests über alle
Neutraltypen: (1) Idempotenz (`canonicalize(canonicalize(t)) == canonicalize(t)`),
(2) Übereinstimmung mit einem echten Live-Round-Trip (DDL in frisches SQLite-File
generieren, reversen, Neutraltyp vergleichen) — der Test übernimmt die
Dokumentationsfunktion der Faltung. PG/MySQL gemäß AP0-Kanten (erwartet: Identity oder
minimale Faltung, gleiche Konstruktion).

**AP2 — Fingerprint v7.**
`MigrationFingerprint.compute(schema, canonicalizeType = Identity)` (Funktionstyp-
Parameter — core darf den ports-common-Port nicht importieren, Hexagon-Richtung):
Typ-Projektion kanonisiert; UNIQUE-Fold (D4); **`required`-Kanonisierung**
(AP0-Befund, siehe Status-Update): `effectiveRequired(col) = col.required || col ∈
effectivePrimaryKey` — PK-Spalten sind semantisch NOT NULL, der PG-Reverse
materialisiert das, der Soll-Parser nicht (dialekt-neutral, Analogon zu
`effectivePrimaryKey` aus v3); **Single-Column-FK-Fold** (Scope-Erweiterung beim
AP2-Bau, live belegt per Probe `fk_colref`: Soll mit Spalten-`references` vs. Reverse
mit benanntem FK-Constraint driftet auf SQLite UND PG mit `executionError = null` —
exakt die UNIQUE-Familie; der `TableComparator` absorbiert Single-Column-FKs bereits
via `ForeignKeySignature`, der Fingerprint zieht im selben Bump nach statt später v8
zu brauchen); Fold nur auf real existierende Spalten (Constraint auf unbekannter
Spalte bleibt im Block — darf nicht still aus der Projektion fallen); `ALGORITHM` →
`schema-fingerprint-v7` mit KDoc-Historieneintrag (Rationale + Verweis hierher).
Unit-Tests: Typ-Proben hashen mit Kanonisierer gleich, ohne weiterhin verschieden
(Identity-Default); UNIQUE-/FK-Fold Single-Column beidseitig und namens-insensitiv,
Multi-Column und divergierende FK-Signaturen bleiben distinkt; `required`-Fold nur
für PK-Spalten; Ghost-Spalten-Guard.

**AP3 — Durchreichung + Artefakt-Verträge.**
Kanonisierer an alle Call-Sites: Migrate-Runner (Post-Compare-Lambda,
`currentFingerprint`/`desiredFingerprint`), `DiffPlanner.plan()` (neuer Parameter,
Default Identity), Rollback-Verify (Dialekt aus Artefakt/Target). Konsistenz-Tests:
`plan.current.fingerprint` == Runner-`currentFingerprint`; F.5.e-Recovery-Pfad
(Soll-basierter `desiredFingerprint` vs. Reverse-Re-Compute) hasht konsistent;
v6-Artefakt → Exit 8 `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` (Regressionstest);
Parser nutzt weiterhin den Artefakt-Algo für die Integritätsprüfung (`c4846667`).
Außerdem (Review-Entscheidung 2026-07-03): das **Plan-Artefakt** bekommt ein
`fingerprintAlgorithm`-Feld, konsistent zum Rollback-Artefakt —
`MigrationPlanArtifact` + Builder + kanonische JSON-Serialisierung + Validator
(non-blank); rein additiv-informativ (kein `semanticExtensions`-Flag nötig, da
Konsumenten das Feld nicht verstehen MÜSSEN, um das Artefakt korrekt zu nutzen —
es macht die persistierten Fingerprint-Werte erst extern interpretierbar).

**AP4 — SQLite-Reverse-UNIQUE-Fix.**
UNIQUE-Autoindizes (origin `u`) nicht mehr verwerfen: Single-Column → `unique: true`
auf der Spalte (PK-Autoindizes weiter überspringen); Multi-Column → benannter
Constraint mit Namen aus dem `sqlite_master`-SQL-Text (Substrat:
`SqliteCheckConstraintScanner`-Präzedenz, `9a4ea9f4`). Die
Multi-Column-Namensrekonstruktion ist **Teil dieses Slices** (Review-Entscheidung
2026-07-03) — beide Varianten sind derselbe Verwerf-Bug und werden zusammen behoben.
Round-Trip-Regressionstests: Reverse einer Tabelle mit inline-UNIQUE (single + multi)
verliert den Constraint nicht mehr; `generate(reverse)` trägt UNIQUE.

**AP5 — Live-Abnahme + permanenter Smoke.**
Der AP0-Probe-Harness wird zu einem dedizierten Smoke destilliert
(neues Script `smoke-types.sh` im Sample-DB-Harness + Make-Target
`sample-db-types-smoke`, Muster
[`smoke-fulltext-sqlite.sh`](../../../examples/sample-db/scripts/smoke-fulltext-sqlite.sh)):
Typ-Probe-Matrix je
Dialekt mit **Exit-0-Asserts** (nach dem Fix; heute wäre er rot → liefert mit dem
Fix, nicht vorgezogen), UNIQUE-Proben single+multi inkl. Fidelity-Assert
(`generate(reverse)` trägt den UNIQUE) und `schema compare`-Gegenprobe.
SQLite-Teil service-frei (File-Targets), PG/MySQL über die vorhandenen
Compose-Services. Rationale: dritter Post-Compare-Befund derselben Familie
(identifier-PK, Typen, UNIQUE) — die Familie fällt nur in der echten
CLI-Exit-Semantik auf; Sensor-Präzedenz ist der `[lite]`-Exit-0-Assert aus dem
identifier-Slice. Die Fingerprint-Mechanik bleibt bewusst auf
Unit-/Integrationstest-Ebene (AP2/AP3; Muster
`SqliteMigrateRoundTripIntegrationTest`) — der Smoke prüft nur die
End-to-End-Exit-Semantik, keine Doppelung.

**AP6 — Doku.**
ADR (nächste freie Nummer, voraussichtlich 0026): „Dialektbewusste
Fingerprint-Kanonisierung im Post-Compare" — Entscheidung D1–D3 (Projektion statt
Prädikat, v7-Bump, gewollte Divergenz zu `schema compare`). Anwenderhandbuch: Hinweis,
dass ein d-migrate-Update mit Fingerprint-Bump bestehende Rollback-Artefakte und
Overlay-Pins invalidiert (Verhalten wie bei früheren Bumps, jetzt dokumentiert).
`make docs-check` grün.

**AP7 — Plan-Konvergenz (nachgeschnitten nach Review R2, user-entschieden 2026-07-03).**
Der Migrate-Diff verglich Typen/`required`/PK dialekt-blind: Ein zweiter
`migrate --execute` mit unverändertem Soll gegen das migrierte Ziel plante
denselben No-Op-`AlterColumnType` erneut (live belegt: SQLite voller
Table-Rebuild, 10 Statements, netto null — bei JEDEM Lauf), während der
v7-Post-Compare Clean meldet. Vorbestehend (vor v7 lief derselbe Rebuild und
endete laut Exit 5); v7 entfernte das Warnsignal. Fix auf richtiger Tiefe:
**target-aware Vergleichsmodus** im
[`TableComparator`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableComparator.kt)
(optionaler `targetCanonicalization`-Parameter, Default strikt = `schema
compare` unverändert, Scope-Entscheidung a bleibt): unterdrückt Typ-Diffs, die
der Ziel-Dialekt auf denselben deklarierten Typ faltet, `required` auf
Effektiv-Basis (PK ⇒ NOT NULL) und PK-Diffs auf Effektiv-PK-Basis (geteilte
v3-Regel, extrahiert nach
[`EffectivePrimaryKey`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/EffectivePrimaryKey.kt)
— der Fingerprint delegiert). Der Runner nutzt einen optionalen
`targetAwareComparator` (Wiring liefert `SchemaComparator(canonicalizeType)`;
Test-Fakes laufen unverändert über den strikten Fallback). DoD: zweiter
migrate-Lauf plant 0 Operationen (Konvergenz-Assert auch im AP5-Smoke);
`schema compare` weiterhin strikt (Gegenprobe).

## Abnahme (Slice-DoD)

1. Alle fünf Typ-Proben der Matrix: `migrate --execute` → **Exit 0**; `text`-Kontrolle
   bleibt 0; die AP0-Zusatzproben (`text(50)`, `char(10)`, `uuid`, `json`) ebenfalls 0.
2. UNIQUE-Szenario aus dem Fulltext-Slice (benannter Single-Column-UNIQUE via
   Table-Rebuild) → **Exit 0**; Reverse-Fidelity: `generate(reverse)` trägt den UNIQUE.
   Multi-Column-inline-UNIQUE: frisches `migrate --execute` → **Exit 0**, Reverse
   rekonstruiert den benannten Constraint (Name aus `sqlite_master`), Round-Trip
   verlustfrei.
3. **Gegenprobe (a):** `schema compare` erkennt `smallint→integer` weiterhin als
   Unterschied.
3a. FK-Fold: frisches `migrate --execute` eines Schemas mit Spalten-Level
   `references:` (AP0-Nachprobe `fk_colref`) → **Exit 0** auf SQLite und PG;
   divergierende FK-Signaturen bleiben Drift (Unit-Test-Ebene).
4. Rollback-Round-Trip mit v7-Artefakt grün (migrate → rollback --execute);
   v6-Artefakt wird mit Exit 8 `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` abgelehnt.
   Ein mit `--plan-artefact` emittiertes Plan-Artefakt trägt
   `fingerprintAlgorithm = "schema-fingerprint-v7"`.
5. Regressionstests auf Fingerprint-/Compare-Ebene (AP2/AP3-Testliste); Modul-Checks
   grün, Kover ≥ 90 % pro berührtem Modul.
6. MySQL/PG: in AP0 belegte Kanten sind kanonisiert (oder belegt kantenfrei =
   Identity); keine Verhaltensänderung für fidelity-erhaltende Round-Trips.
7. Die Punkte 1–3 sind als permanenter Sensor in `make sample-db-types-smoke`
   verankert (AP5) und der Smoke ist grün.
8. `required`-Kanonisierung: `identifier` + explizites `primary_key` ohne
   ausgeschriebenes `required` → **Exit 0** auf PG (AP0-Reproducer `identifier_pk`);
   Gegenprobe: `required`-Drift auf einer **Nicht**-PK-Spalte bleibt Drift.
9. **Plan-Konvergenz (AP7):** ein zweiter `migrate --execute` mit unverändertem
   Soll gegen das frisch migrierte Ziel plant **0 Operationen** (kein
   No-Op-Rebuild); `schema compare` bleibt strikt.

## Nicht-Scope

- Kein neues Typ-Mapping (die Abflachung selbst ist korrekt und gewollt, SQLite hat nur
  vier Storage-Klassen).
- Kein Reverse-„Raten" des Ursprungstyps aus Werten.
- `schema compare` / Diff-Engine / Generate-Pfad unverändert (D3).
- `enum`-CHECK-Äquivalenz (falls AP0 sie als eigene Drift-Familie belegt → eigenes
  Ticket).
- Die `identifier`-64-bit-Entscheidung bleibt eigenes Ticket
  ([`sqlite-reverse-identifier-64bit-narrowing.md`](../open/sqlite-reverse-identifier-64bit-narrowing.md));
  dieser Slice schaltet dort Option 2 frei (Folge-Effekt).

## Review-Punkte (offen für den Plan-Review)

- ~~**R1**~~ **entschieden (Review 2026-07-03):** Live-Komposition
  `SqliteTypeMapping(SqliteTypeMapper.toSql(t))` + Property-Tests (Option A, keine
  zweite Wahrheit pflegen; eingearbeitet in AP1). Rückfallpunkt: erweist sich
  `mapColumn` im Bau als zu sperrig (Notes/Seiteneffekte), ist die explizite Tabelle
  mit kompositions-abgeleitetem Test der dokumentierte Plan B.

## Status-Update 2026-07-03 — AP0-Ergebnisse (Probe-Matrix gelaufen)

Vollständige Matrix live gelaufen (Runtime-Image, frisches Ziel je Probe; alle
Neutraltypen außer `geometry`/`fulltext`, die per D1 Identity sind und eigene Smokes
haben). Exit-5-Fälle wurden über den Report klassifiziert: **Post-Compare-Drift**
(`executionError = null`) vs. **Runtime-Execution-Fehler** (anderer Mechanismus, keine
Kanonisierungs-Kante).

**Kanten-Tabelle SQLite (16 Kanten, alle Post-Compare-Drift):**
`text(50)`→`text`, `char(10)`→`text`, `smallint`→`integer`, `biginteger`→`integer`,
`decimal(10,2)`→`float`, `boolean`→`integer`, `datetime`→`text`,
`datetime(tz)`→`text`, `date`→`text`, `time`→`text`, `uuid`→`text`, `json`→`text`,
`xml`→`text`, `email`→`text(254)`, `enum`→`text`, `array`→`text`.
Fixpunkte (Exit 0): `text`, `integer`, `float`, `binary`, `identifier` (implizit,
ohne `primary_key`). `enum` ist eine **reine Typ-Kante**: das Generate-DDL ist bloßes
`TEXT` ohne CHECK — kein zusätzliches Constraint-Drift-Ticket nötig (Werteverlust ist
Generate-Degradation, nicht Post-Compare-Thema; als eigener Befund getrackt, weil
zudem **still**:
[`../open/enum-generate-silent-degradation.md`](../open/enum-generate-silent-degradation.md)).

**Kanten-Tabelle PostgreSQL (2 Kanten):** `email`→`text(254)` (`VARCHAR(254)`),
`enum`→`text` (Generate rendert `TEXT`). Alle übrigen Typen inkl. `datetime(tz)`,
`uuid`, `json`, `xml`, `array` round-trippen treu (Exit 0).

**Kanten-Tabelle MySQL (5 Kanten):** `datetime(tz)`→`datetime` (tz-Flag fällt),
`xml`→`text`, `email`→`text(254)`, `enum`→`text`, `array(text)`→`json`. Übrige treu.
**AP1-Konsequenz:** auch PG/MySQL bekommen den Kompositions-Kanonisierer (nicht
Identity) — die Kanten ergeben sich bei Option A automatisch aus derselben
Konstruktion.

**UNIQUE (Befund 2 vollständig live belegt):**
- SQLite: inline Single- **und** Multi-Column-UNIQUE → Reverse verwirft komplett
  (Exit 5; Reverse trägt weder Constraint noch `unique: true`).
- PG/MySQL Single-Column: Reverse liest `unique: true` → Exit 5 = exakt der
  Fingerprint-Fold-Fall (D4).
- PG/MySQL Multi-Column: **Exit 0** — benannte Multi-Column-Constraints round-trippen
  bereits. **AP4-Konsequenz:** der Fold bleibt auf Single-Column begrenzt (wie D4);
  für Multi-Column genügt die SQLite-Reverse-Rekonstruktion (nach ihr vergleichen
  benannter Constraint ↔ benannter Constraint ohne Fold).
- MySQL-Probe-Hinweis: UNIQUE auf `text` **ohne** `max_length` scheitert als
  Runtime-Fehler („BLOB/TEXT column … without a key length") — bekanntes
  Präfixlängen-Terrain ([`../next/pk-constraint-prefix-length.md`](../next/pk-constraint-prefix-length.md)),
  kein neuer Befund; Proben nutzen `max_length: 50`.
- **Nachprobe `fk_colref` (AP2-Bau, 2026-07-03):** Soll mit Spalten-Level
  `references:` vs. Reverse mit benanntem FK-Constraint (SQLite synthetisiert
  `fk_0`, PG liest `child_parent_id_fkey`) → Exit 5 mit `executionError = null`
  auf **beiden** Dialekten. Dieselbe Fold-Familie wie UNIQUE → Single-Column-FK-Fold
  in AP2 aufgenommen (Comparator absorbiert bereits via `ForeignKeySignature`).

**Neuer In-Scope-Befund — PK-implizierte `required`-Asymmetrie (→ AP2, Abnahme 8):**
`identifier` + explizites `primary_key` (ohne ausgeschriebenes `required`) endet auf
PG mit Exit 5, obwohl der Reverse `identifier`/`auto_increment`/PK exakt
rekonstruiert — einzige Differenz: der PG-Reverse materialisiert `required: true` für
PK-Spalten, der Soll-Parser nicht. Gegenprobe mit `required: true` im Soll → Exit 0.
SQLite/MySQL-Reverse materialisieren `required` für PK-Spalten nicht (dort kein
Drift). Dialekt-neutrale Kanonisierung `effectiveRequired` im Fingerprint (Analogon
zu `effectivePrimaryKey`, v3) — in AP2 aufgenommen, teilt den v7-Bump.

**Nebenbefunde außerhalb des Scopes (eigenes Ticket bzw. Ticket-Ergänzung):**
- **Generate materialisiert den impliziten `identifier`-PK nicht** →
  [`../next/generate-implicit-identifier-pk-materialization.md`](../next/generate-implicit-identifier-pk-materialization.md):
  MySQL rendert `INT NOT NULL AUTO_INCREMENT` ohne KEY (Runtime-Fehler „only one auto
  column and it must be defined as a key"); SQLite rendert bei `identifier` +
  explizitem `primary_key` **doppeltes** PRIMARY KEY (SQLITE_ERROR). Beides
  Runtime-Execution-Fehler auf spec-validen Schemata, keine Post-Compare-Drift.
- **PG-Reverse rekonstruiert `identifier` ohne PK nicht** (SERIAL-only → `integer` +
  `sequence_nextval`-Default): PG-Evidenz im bestehenden Ticket
  [`sqlite-reverse-identifier-64bit-narrowing.md`](../open/sqlite-reverse-identifier-64bit-narrowing.md)
  ergänzt (mit explizitem PK rekonstruiert PG korrekt).

Der Probe-Harness (Schemata + Runner) ist der Seed für den AP5-Smoke
(`smoke-types.sh`); Roh-Ergebnisse liegen im Sample-DB-Harness unter `.cache/ap0/`
(gitignored, kein Artefakt im Repo), die Tabellen oben sind die festgehaltene Evidenz.

**Live-Stand nach AP3 (2026-07-03, volle Matrix, Runtime-Image):** alle 21
Typ-Proben **Exit 0 auf allen drei Dialekten** — sämtliche Kanten-Tabellen-Fälle
(SQLite 16, PG 2, MySQL 5) sind geschlossen; ebenso `fk_colref` (FK-Fold),
`uq_single_vc`/`uq_multi_vc` auf PG/MySQL (UNIQUE-Fold bzw. treuer Round-Trip)
und `identifier_pk` auf PG/MySQL (`required`-Fold). Verbleibende Exit-5-Fälle,
alle erwartet und getrackt: SQLite `uq_single`/`uq_multi` (Reverse verwirft
inline-UNIQUE → **AP4**); PG/MySQL `identifier` ohne explizites `primary_key`
und MySQL-UNIQUE auf `text` ohne `max_length` (Runtime-/Rekonstruktions-Familie →
[`../next/generate-implicit-identifier-pk-materialization.md`](../next/generate-implicit-identifier-pk-materialization.md)
bzw. Präfixlängen-Ticket, nicht dieser Slice).

**Live-Stand nach AP4 (2026-07-03):** SQLite `uq_single`/`uq_multi` (und die
`_vc`-Varianten) **Exit 0** — der Reverse liest Single-Column-UNIQUE als
`unique: true` und rekonstruiert Multi-Column-Constraints mit ihrem DDL-Namen
(`uq_ab`); das ursprüngliche Trigger-Szenario (benannter UNIQUE via
Table-Rebuild) endet ebenfalls Exit 0. Damit sind **alle** Post-Compare-Proben
des Slices grün; offen bleiben nur die bewusst ausgelagerten
Generate-Bug-Fälle.

**Review-Härtung R2 (2026-07-03, nach AP2–AP4):** 4 Finder-Winkel + Verifier +
eigene Live-Probe. Bestätigt und behoben (`91747294`): CHECK-/EXCLUDE-Expressions
laufen im Fold durch `ConstraintDiffContract.comparable` (CRLF/trim-Parität mit
dem Comparator); `fkSignature` delegiert an `reference()` (ein String-Format);
`registryTypeCanonicalizer` fängt nur noch den Registry-Miss (Kanonisierer-Fehler
propagieren laut); Rollback-Verify löst den Kanonisierer bevorzugt aus dem
Live-Verbindungs-Dialekt auf; synthetische `uq_N`-Namen überspringen real
rekonstruierte (uq_0-Kollision). **Haupt-Befund → AP7** (Plan-Konvergenz, live
belegt per Zweitlauf-Probe). Bewusst vertagt: Kommentar-Lücke in
`SqliteDdlScanning` (nur extern authorte DBs erreichbar, vorbestehend auch im
CHECK-Scanner) und der geteilte Comparator/Fingerprint-Kanonisierungs-Refactor
(Parität jetzt über geteilte Bausteine `comparable`/`reference`/`EffectivePrimaryKey`
+ Tests gepinnt).

**Review-Härtung R1 (2026-07-03, nach AP1):** 4 Finder-Winkel + adversarialer
Verifier über den AP1-Commit. Bestätigt und behoben:
1. **PG-`Identifier`-Carve-out zu breit** — `identifier` OHNE `auto_increment`
   rendert plain `INTEGER` (kein SERIAL) und muss zu `integer` falten; Carve-out
   jetzt nur noch für `autoIncrement = true`.
2. **PG-`Array`-Carve-out zu breit** — nur text/integer/boolean/uuid sind
   generate-bare Element-Typen, alles andere flacht real auf `TEXT[]` ab; Arrays
   gehen jetzt durch die Komposition (Element-Brücke → `mapArrayElementType`).
3. **`fulltext`-Carve-out war auf SQLite/MySQL selbst ein False-Positive-Erzeuger**
   (Spalte degradiert real zu TEXT, Reverse rekonstruiert nur den Index; der
   Fulltext-Smoke deckt nur den Index-Fall ab) — `fulltext` geht jetzt durch die
   Komposition, auf PG bleibt es als tsvector-Fixpunkt automatisch treu.
4. **Unknown-Fallback-Guard** in allen drei Kanonisierern: landet die Komposition
   im Unknown-Type-Fallback (R201/R301), wird der Input unverändert zurückgegeben —
   eine künftige Brücken-Lücke erzeugt lauten Drift statt stiller
   Text()-Falsch-Äquivalenz.
Refuted: MySQL-Enum-Fold ist korrekt (der migrate-Diff-Pfad rendert via `toSql`
bloßes TEXT — Beleg jetzt im enum-Ticket; die generate/migrate-Divergenz ist dort
als bestätigte Pfad-Inkonsistenz nachgetragen). Cleanup: Klammer-Parse-Helfer nach
`SchemaReaderUtils` konsolidiert (vorher 4 Regex-Kopien, nebenbei
Regex-Kompilierung pro `mapColumn`-Aufruf beseitigt), toter `NUMERIC`-Zweig raus,
Stub-Driver im Ports-Test dedupliziert. Bewusst NICHT gebaut: Contract-Test-Fixture
für die drei Kanonisierer-Tests (Fixpunkt-/Kanten-Tabellen sind inhärent
dialekt-spezifisch, geteilt wären nur ~5 Zeilen Idempotenz-Loop) und ein
„muss überschreiben"-Guard für künftige Driver (Identity ist für treue Dialekte
korrekt — der Sensor je Dialekt ist der AP5-Smoke).
- ~~**R2**~~ **entschieden (Review 2026-07-03):** Multi-Column-UNIQUE-Namensrekonstruktion
  bleibt im Slice (AP4 + Abnahme 2).
- ~~**R3**~~ **entschieden (Review 2026-07-03):** das Plan-Artefakt bekommt das
  `fingerprintAlgorithm`-Feld (eingearbeitet in AP3 + Abnahme 4).

## Closure

**Graduiert 2026-07-05.** Alle Arbeitspakete AP0–AP7 geliefert und live
verifiziert; kein offener Punkt mehr.

- **AP0** Proben & Reproducer (Kanten-Tabellen SQLite 16 / PG 2 / MySQL 5, UNIQUE-
  und FK-Asymmetrie, `required`-Asymmetrie) — Status-Update oben.
- **AP1** `NeutralTypeCanonicalizer` (ports-common) + SQLite/PG/MySQL-
  Kompositions-Kanonisierer (`cfe51d02`), Review-Härtung R1 (`13f4fb60`).
- **AP2** Fingerprint `v7` — Typ-Projektion + Single-Column-UNIQUE-/FK-Fold +
  `effectiveRequired` (`b137b352`).
- **AP3** Durchreichung an alle Call-Sites + Plan-Artefakt-`fingerprintAlgorithm`
  (`efb0b520`, volle Typ-Matrix live grün).
- **AP4** SQLite-Reverse-UNIQUE-Fix (single + multi, Namensrekonstruktion,
  `9d0bc833`).
- **AP7** target-aware Comparator-Modus — Plan-Konvergenz, Zweitlauf plant 0
  Statements (`f7cde5df`); Review-Härtung R2 (`91747294`).
- **AP5** permanenter Typ-Smoke `make sample-db-types-smoke` (`ebe30bb2`).
- **AP6** [ADR-0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md)
  (Entscheidung D1–D3, gewollte Divergenz Fingerprint ↔ `schema compare`) +
  Anwenderhandbuch-Fehlerbehebung + CHANGELOG. `make docs-check` grün.

**Slice-DoD (Abnahme 1–9) erfüllt.** Ausgelagerte Nebenbefunde bleiben als
eigene Tickets getrackt:
[`generate-implicit-identifier-pk-materialization.md`](../next/generate-implicit-identifier-pk-materialization.md),
[`enum-generate-silent-degradation.md`](../open/enum-generate-silent-degradation.md),
[`sqlite-reverse-identifier-64bit-narrowing.md`](../open/sqlite-reverse-identifier-64bit-narrowing.md)
(dort ist durch `v7` jetzt Option 2 freigeschaltet).
