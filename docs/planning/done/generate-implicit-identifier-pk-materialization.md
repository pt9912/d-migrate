# Slice: Impliziten `identifier`-PK im Generate materialisieren (MySQL-KEY + SQLite-Dedup, dialektuniform)

> Status: **Done — graduiert 2026-07-05.** AP1–AP4 implementiert, code-review-
> gehärtet und live-verifiziert (Docker-`check` grün, Typ-Smoke grün, CI grün);
> Plan-Review R1/R2 entschieden. Commits `263695b9` (Implementierung) + `5567ed14`
> (Review-Härtung). Aktiviert aus dem gleichnamigen `open/`-Ticket
> (Draft/Trigger-Watch) als **direkter Folge-Slice** der Post-Compare-Kanonisierung
> ([`../done/postcompare-type-canonicalization-slice.md`](../done/postcompare-type-canonicalization-slice.md)).
> Closure am Dateiende.
> Severity: **P2** (zwei **Runtime-Execution-Fehler** auf spec-validen Schemata:
> ein `identifier`-PK-Schema ist heute je nach Dialekt nicht anlegbar).
> Trigger: AP0-Probe-Matrix der Kanonisierung (Status-Update 2026-07-03), zwei
> `migrate --execute`-Exit-5-Fälle mit `executionError` (keine Post-Compare-Drift).
> Präzedenz: geteilte `EffectivePrimaryKey`-Regel (Fingerprint v3 + target-aware
> Comparator AP7).

## Ziel

Ein `migrate --execute` (und `schema generate`) eines **spec-validen** Schemas mit
`identifier`-getragenem Primärschlüssel endet auf **allen** Dialekten mit Exit 0 —
der effektive PK wird dialektuniform materialisiert (MySQL: KEY für die
AUTO_INCREMENT-Spalte; SQLite: kein doppelter PK; PG: `SERIAL` trägt `PRIMARY KEY`).
Geteilte Wahrheit ist dieselbe `EffectivePrimaryKey`-Ableitung, die Fingerprint und
target-aware Comparator bereits verwenden — kein neues Mapping, keine zweite Regel.

## Befund (live belegt 2026-07-03, aus AP0 der Kanonisierung)

`spec/neutral-model-spec.md` Abschnitt 13.1 definiert `identifier` als PK-tragend
(explizit **oder** über den Typ; fehlender expliziter PK ist nur Warnung E008). Der
Fingerprint kanonisiert das seit v3 (`EffectivePrimaryKey`). Der **Generate-/Diff-
Renderpfad** materialisiert die implizite PK aber nicht — zwei dialektabhängige,
zueinander **inverse** Fehlerkanten (beide `migrate --execute` Exit 5 via
`executionError`, keine Post-Compare-Drift):

| Dialekt | Fall | Symptom |
| ------- | ---- | ------- |
| **MySQL** | `identifier` **ohne** `primary_key` | `` `id` INT NOT NULL AUTO_INCREMENT `` ohne KEY → „there can be only one auto column and it must be defined as a key" (Error 1075). Nicht anlegbar. |
| **SQLite** | `identifier` **mit** explizitem `primary_key: [id]` | Inline `INTEGER PRIMARY KEY AUTOINCREMENT` **plus** Tabellen-Level `PRIMARY KEY ("id")` → SQLITE_ERROR (doppelter PK). |
| **PostgreSQL** | `identifier` ohne `primary_key` | `SERIAL` ohne `PRIMARY KEY` — DDL valide, aber die PK fehlt im Ziel → Post-Compare-/Reverse-Folge (in Ticket [`sqlite-reverse-identifier-64bit-narrowing.md`](../open/sqlite-reverse-identifier-64bit-narrowing.md) als PG-Evidenz notiert). |

Grün heute: SQLite `identifier` **ohne** expliziten PK (Inline-PK genügt); MySQL
`identifier` **mit** explizitem PK (AP0-Probe `identifier_pk` Exit 0). Genau invers.

## Code-Fakten (Exploration 2026-07-05)

- **MySQL** [`MysqlDiffTableOps.renderCreateTable`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffTableOps.kt)
  emittiert die `PRIMARY KEY (…)`-Zeile **nur** `if (op.table.primaryKey.isNotEmpty())`.
  Für `identifier`-only ist `primaryKey` leer → keine KEY-Klausel. Die
  AUTO_INCREMENT-Voranstellung existiert bereits
  ([`MysqlPrimaryKeyOrdering.autoIncrementFirst`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlPrimaryKeyOrdering.kt)).
- **SQLite** [`SqliteDiffSimpleOps.renderCreateTable`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSimpleOps.kt)
  emittiert die Tabellen-Level-`PRIMARY KEY`-Zeile für **jeden** nicht-leeren
  expliziten PK; der Typ-Mapper
  ([`SqliteTypeMapper`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt))
  rendert `identifier` inline als `INTEGER PRIMARY KEY AUTOINCREMENT` → mit
  explizitem Single-Column-PK doppelt.
- **PG** rendert `identifier` als `SERIAL` (kein Inline-PK,
  [`PostgresTypeMapper`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt))
  → braucht die Tabellen-Level-PK-Zeile, kein Dedup-Problem.
- [`EffectivePrimaryKey`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/EffectivePrimaryKey.kt)
  (core.diff, **`internal`**): expliziter PK gewinnt verbatim; sonst genau **eine**
  `identifier`-Spalte → PK; mehrere `identifier` → ambig (leer). Bereits geteilte
  Wahrheit für Fingerprint (v3) und target-aware Comparator (AP7). Aus den Adaptern
  **nicht erreichbar** (Modulgrenze + `internal`).
- Die `CreateTable`-Operation wird in
  [`OperationMapper`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/OperationMapper.kt)
  (core.diff.migration) konstruiert — der natürliche Ort, den effektiven PK zu
  materialisieren, **bevor** die Op einen Adapter-Renderer erreicht.

## Design-Entscheidungen

**D1 — Effektiven PK in core materialisieren, nicht per Adapter.** `OperationMapper`
normalisiert `CreateTable.table.primaryKey` auf `EffectivePrimaryKey.of(table)`
(nur wenn explizit leer und eindeutig ableitbar; als **Kopie**, nie die Quell-
`TableDefinition` mutieren — sonst driftet der aus derselben Quelle berechnete
Fingerprint). Reuse der internen Regel: keine Visibility-Öffnung, keine zweite
Wahrheit in drei Adaptern. Alle Dialekt-Renderer sehen dann einen expliziten
Single-`identifier`-PK; der bestehende `if (primaryKey.isNotEmpty())`-Zweig greift
uniform. Alternative (per-Adapter-Ableitung) verworfen — dupliziert die Regel und
driftet (Anti-Muster, das die Kanonisierung gerade beseitigt hat).

**D2 — SQLite dedupt Inline- gegen Tabellen-Level-PK (Pflicht-Begleiter zu D1).**
Trägt die (einzige) PK-Spalte bereits inline `INTEGER PRIMARY KEY AUTOINCREMENT`,
unterdrückt der SQLite-Renderer die Tabellen-Level-`PRIMARY KEY`-Zeile. **Ohne D2
würde D1 den heute grünen SQLite-`identifier`-only-Fall zum Doppel-PK regredieren** —
D1 und D2 sind gekoppelt und liefern zusammen. D2 behebt zugleich den bestehenden
Doppel-PK bei `identifier` + explizitem PK. Nur SQLite braucht Dedup (nur SQLite
rendert Inline-PK für `identifier`); MySQL/PG unberührt.

**D3 — PG als Gratis-Folge-Effekt in die Abnahme.** Da D1 in core sitzt,
materialisiert PG `SERIAL` nun mit `PRIMARY KEY` → der in Ticket
[`sqlite-reverse-identifier-64bit-narrowing.md`](../open/sqlite-reverse-identifier-64bit-narrowing.md)
notierte PG-Reverse-Aspekt (SERIAL-ohne-PK → `integer` + `sequence_nextval`)
entschärft sich ohne PG-spezifischen Code. In die Abnahme aufnehmen (identifier-only
PG Round-Trip Exit 0), aber kein eigenes AP.

**D4 — Nur `CreateTable`, nicht die Alter-Pfade.** Die Materialisierung greift beim
Neu-Anlegen. Bestehende-Tabellen-Diffs vergleichen den PK bereits über
`EffectivePrimaryKey` (target-aware Comparator); `AddPrimaryKey`/Alter bleiben
unangetastet.

## Arbeitspakete

**AP0 — Reproducer bestätigen + PG-Kante prüfen.** Die AP0-Proben der Kanonisierung
(`identifier` / `identifier_pk` je Dialekt im Typ-Smoke) sind der Reproducer. Im
Plan-Review 2026-07-05 bereits bestätigt: (a) PG `identifier`-only trägt heute
**kein** `PRIMARY KEY` — [`PostgresTypeMapper`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt)
rendert `SERIAL`/`INTEGER` ohne Inline-PK; (b) `CreateTable` wird in core an **genau
einer** Site konstruiert ([`OperationMapper`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/OperationMapper.kt),
`tablesAdded`-Schleife) — der Rename-Fallback baut keine eigene `CreateTable`-Op
(nicht-foldbare Renames bleiben in `tablesAdded`), und `TableDefinition` ist immutable,
sodass `.copy()` die Quelle nicht teilt-mutiert (siehe R1). Rest-DoD: Rot-Fälle
(MySQL identifier-only; SQLite identifier + expliziter PK) als Asserts festschreiben.

**AP1 — Core-Materialisierung (D1).** `OperationMapper` zieht in der
`tablesAdded`-Schleife (die einzige `CreateTable`-Bau-Site, siehe AP0(b)) den
effektiven PK **vor** dem Op-Bau in eine Kopie
(`added.definition.copy(primaryKey = EffectivePrimaryKey.of(added.definition))`) und
verwendet diese Kopie sowohl für `table =` **als auch** für den Payload-Hash
(`CanonicalPayload.table`), damit die Op-ID inhaltskonsistent bleibt — nur wenn der
explizite PK leer und eindeutig ableitbar ist. Unit-Tests: identifier-only → PK
materialisiert; expliziter PK gewinnt; mehrere `identifier` → unverändert (ambig,
leer); Nicht-`identifier`-Tabellen ohne PK unverändert; Quell-Schema unverändert
(kein Fingerprint-Effekt); Op-ID deterministisch aus der materialisierten Payload.

**AP2 — SQLite-Dedup (D2).** `SqliteDiffSimpleOps` überspringt die Tabellen-Level-PK
für einen Single-Column-PK, dessen Spalte inline `INTEGER PRIMARY KEY AUTOINCREMENT`
trägt. Unit-Tests: identifier-only bleibt Single-PK; identifier + expliziter PK →
ein PK; Nicht-identifier expliziter PK unverändert; Multi-Column-PK unverändert.

**AP3 — Live-Abnahme im Typ-Smoke.** [`smoke-types.sh`](../../../examples/sample-db/scripts/smoke-types.sh)
erweitern: identifier-only Exit 0 auf **MySQL** (heute rot) und **PG** (Round-Trip);
identifier + expliziter PK Exit 0 auf **SQLite** (heute rot); bestehende Grün-Fälle
bleiben grün. Muster wie die bestehenden Typ-/UNIQUE-Asserts.

**AP4 — Doku.** CHANGELOG-`Fixed`-Eintrag; Folge-Notiz im 64-bit-Ticket, dass der
PG-Aspekt hier mitbehoben ist. Kein Spec-Change (die `identifier`-PK-Semantik ist
bereits korrekt spezifiziert — es ist ein Generate-Fidelity-Fix). `make docs-check`.

## Abnahme (Slice-DoD)

1. `identifier` ohne `primary_key`: `migrate --execute` → **Exit 0** auf **MySQL**
   (heute Runtime-Fehler) und **PG**; PK im Ziel materialisiert.
2. `identifier` + explizites `primary_key: [id]`: **Exit 0** auf **SQLite** (heute
   SQLITE_ERROR); genau ein PK im Ziel.
3. Bestehende Grün-Fälle bleiben grün (SQLite implizit; MySQL `identifier_pk`).
4. Reverse-Round-Trip verlustfrei (PK vorhanden, kein Doppel-PK); `schema compare`
   der beiden Stände clean.
5. Regressionstests grün; Kover ≥ 90 % je berührtem Modul; Punkte 1–3 als
   permanente Asserts im Typ-Smoke.

## Nicht-Scope

- **Keine Spec-Änderung** — `identifier` trägt PK-Semantik laut Spec bereits korrekt.
- **Kein Multi-`identifier`-Auto-PK** — mehrere `identifier`-Spalten bleiben ambig
  (unverändert; explizit `primary_key` verlangt).
- **Der 64-bit-`identifier`-Vertrag** bleibt eigenes Ticket
  ([`sqlite-reverse-identifier-64bit-narrowing.md`](../open/sqlite-reverse-identifier-64bit-narrowing.md));
  dieser Slice berührt nur die PK-Materialisierung, nicht die Wertebereichsbreite.
- **`enum`-Generate-Degradation** ist separat
  ([`enum-generate-silent-degradation.md`](../open/enum-generate-silent-degradation.md)).

## Plan-Review-Entscheidungen (erledigt 2026-07-05)

Beide Review-Punkte sind gegen den Code verifiziert und entschieden; der Plan ist
bau-reif.

- **R1 — Ort der Materialisierung (D1): ENTSCHIEDEN — zentral in `OperationMapper`.**
  [`EffectivePrimaryKey`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/EffectivePrimaryKey.kt)
  liegt `internal` in `hexagon/core` (`core.diff`); `OperationMapper`
  (`core.diff.migration`) liegt im **selben Modul** → Zugriff ohne
  Visibility-Öffnung, als dritter Konsument neben `MigrationFingerprint` und dem
  target-aware `TableComparator` (AP7). Die Per-Renderer-Alternative ist
  ausgeschlossen: die Adapter-Module (`driver-mysql`/`-sqlite`/`-postgresql`)
  erreichen das `internal object` nicht — sie erzwängen entweder eine öffentliche
  API für eine diff-interne Regel oder die Drei-Adapter-Duplikation (genau die
  Drift, die die Kanonisierung beseitigt hat).
  **`OperationMapper`-Plan-B (dedizierter Pre-Render-Pass) entfällt:** die
  Exploration 2026-07-05 belegt genau **eine** `CreateTable`-Bau-Site in core
  (`tablesAdded`-Schleife); nicht-foldbare Renames bleiben in `tablesAdded` und
  laufen über dieselbe Site, der Rename-Fallback baut keine eigene `CreateTable`-Op.
  Ein zweiter Pass würde also keine weitere Site abdecken. Op-ID-Konsistenz ist
  über die Kopie-vor-Bau-Reihenfolge gesichert (siehe AP1).
- **R2 — PG in der Abnahme (D3): ENTSCHIEDEN — vollwertiges Abnahmekriterium.**
  Inkrementelle Testkosten ≈ null (PG läuft bereits in `smoke-types.sh`), Nutzen
  real: D1 materialisiert PG `SERIAL` künftig **mit** `PRIMARY KEY`, der Reverse
  liest einen expliziten PK zurück, und `EffectivePrimaryKey.of` stimmt auf Ziel-
  und Soll-Seite überein — die im Ticket
  [`sqlite-reverse-identifier-64bit-narrowing.md`](../open/sqlite-reverse-identifier-64bit-narrowing.md)
  notierte PG-Reverse-Folge (SERIAL-ohne-PK → `integer` + `sequence_nextval`) wird
  damit **live** geschlossen statt nur behauptet. Bleibt als PG-Klausel in
  DoD-Punkt 1 + eigener PG-`identifier`-only-Round-Trip-Assert; **kein eigenes AP**
  (wie D3).

Offengelassen (bewusst, außerhalb dieses Slice): Der SQLite-Typ-Mapper rendert
**jede** `identifier`-Spalte inline als `INTEGER PRIMARY KEY AUTOINCREMENT` — bei
mehreren `identifier`-Spalten also mehrfach. Das ist eine vorbestehende latente
Kante, die vom „Kein Multi-`identifier`-Auto-PK"-Nicht-Scope gedeckt ist (mehrere
`identifier` bleiben ambig → `EffectivePrimaryKey` leer → D1 materialisiert nichts,
D2 dedupt nicht); dieser Slice verschärft sie nicht und behebt sie nicht.

## Review-Härtung (Code-Review 2026-07-05, nach AP1–AP4)

Adversarialer Code-Review über den Implementierungs-Commit (`263695b9`), 4 gezielte
Kanten + allgemeiner Korrektheits-Sweep. **Ein bestätigter Befund behoben, alle
übrigen Kanten mit Code-Beleg widerlegt.**

- **Befund (MEDIUM, CONFIRMED) — zweiter SQLite-`CREATE TABLE`-Emitter fehlte im
  Dedup.** AP2 fixte nur `SqliteDiffSimpleOps.renderCreateTable`; der **Rebuild**-Pfad
  [`SqliteRebuildRenderer.buildCreateTempSql`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildRenderer.kt)
  trug denselben un-deduplizierten Table-Level-PK. **Durch AP2 erstmals erreichbar:**
  eine `identifier`+PK-Tabelle ist jetzt anlegbar, ein späterer Reshape erzwingt einen
  Rebuild → Doppel-PK → `SQLITE_ERROR`. Von Unit-Tests + Smoke verfehlt (kein
  Rebuild-auf-identifier-PK-Fall). **Fix:** Dedup in **ein** geteiltes
  [`SqliteDiffSqlBuilders.primaryKeyClause`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSqlBuilders.kt)
  gezogen, das beide Diff-Emitter nutzen (kann auf keinem Pfad mehr vergessen werden);
  Regressions-Unit-Test + Live-Smoke-Rebuild-Fall (`identifier_pk_rb`) ergänzt.
- **Dritter Emitter geprüft (Review-Ergänzung):** der reine `schema generate`-Pfad
  [`SqliteTableDdlSupport`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTableDdlSupport.kt)
  dedupt **bereits** (sogar breiter, inkl. `ColumnGeneration.Identity`) — bestätigt
  zugleich, dass die Dedup-Bedingung des Diff-Pfads korrekt nur `NeutralType.Identifier`
  prüft (nur dieser Typ rendert im Diff-`columnLine` inline-PK).
- **Widerlegt (mit Beleg):** AddColumn-Scope-Grenze (D4, vorbestehende DB-Grenze,
  unverändert); composite PK mit `identifier`-Member (strikt vorbestehend, nicht neu);
  PG/MySQL-Konvergenz für `identifier`-only (faltet über `EffectivePrimaryKey`);
  Geometry-Interaktion (PK-Spalte nie Geometry); Shared-State/Op-ID/Ghost-PK
  (`copy` mutiert nicht, IDs selbstkonsistent).

Re-Verifikation: `:adapters:driven:driver-sqlite:check` grün (inkl. neuem Test),
`make sample-db-types-smoke` grün (inkl. Rebuild-Fall). Änderung ist
`driver-sqlite`-intern (keine Signatur-/Cross-Modul-Wirkung) → kein erneuter Full-Check.

## Closure

**Graduiert 2026-07-05.** Alle Arbeitspakete geliefert, live-verifiziert,
code-review-gehärtet, committet und gepusht (CI grün).

- **AP1** `OperationMapper` materialisiert den effektiven PK zentral (interne
  `EffectivePrimaryKey`-Regel, Kopie speist `table=` + Op-ID-Payload) — `263695b9`.
- **AP2** `SqliteDiffSimpleOps` dedupt Inline- vs. Tabellen-PK für Single-Column-
  `identifier` — `263695b9`.
- **AP3** Typ-Smoke `[PK]`-Fälle (SQLite `identifier_pk` frisch + Rebuild, MySQL
  `identifier`-only, PG `identifier`-only PK via `information_schema`) —
  `263695b9`, Rebuild-Fall `5567ed14`.
- **AP4** CHANGELOG + 64bit-Ticket-Update + Lifecycle-Graduierung — `263695b9`.
- **Review-Härtung** geteilter `SqliteDiffSqlBuilders.primaryKeyClause` für beide
  Diff-Emitter (Rebuild-Emitter-Doppel-PK, vom Code-Review gefunden) — `5567ed14`.

**DoD 1–5 erfüllt** (Live-Smoke + Docker-`check` + CI grün). Folge-Tickets bleiben
getrackt: [`enum-generate-silent-degradation.md`](../open/enum-generate-silent-degradation.md)
(Scope-Entscheidung Option 1/2 nach diesem Slice offen) und
[`sqlite-reverse-identifier-64bit-narrowing.md`](../open/sqlite-reverse-identifier-64bit-narrowing.md)
(PG-`identifier`-PK-Aspekt hier mitbehoben; nur noch die 64-bit-Wertebereichsfrage /
Option 2 offen).
