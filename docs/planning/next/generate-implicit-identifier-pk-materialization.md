# Slice: Impliziten `identifier`-PK im Generate materialisieren (MySQL-KEY + SQLite-Dedup, dialektuniform)

> Status: **Scope-Schnitt 2026-07-05 (Plan-Review offen).** Aktiviert aus dem
> gleichnamigen `open/`-Ticket (Draft/Trigger-Watch) als **direkter Folge-Slice**
> der Post-Compare-Kanonisierung
> ([`../done/postcompare-type-canonicalization-slice.md`](../done/postcompare-type-canonicalization-slice.md)) —
> wie dort vorgemerkt. Wandert mit dem ersten Code-Commit nach `in-progress/`.
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
(`identifier` / `identifier_pk` je Dialekt im Typ-Smoke) sind der Reproducer. Prüfen:
(a) PG identifier-only Generate-DDL trägt heute **kein** `PRIMARY KEY` (erwartet);
(b) genaue `OperationMapper`-Site der `CreateTable`-Konstruktion + dass die
Quell-`TableDefinition` nicht geteilt-mutiert wird. DoD: Rot-Fälle als Asserts,
PG-Kante geklärt.

**AP1 — Core-Materialisierung (D1).** `OperationMapper` setzt für `CreateTable` den
effektiven PK, wenn explizit leer. Unit-Tests: identifier-only → PK materialisiert;
expliziter PK gewinnt; mehrere `identifier` → unverändert (ambig, leer);
Nicht-`identifier`-Tabellen ohne PK unverändert; Quell-Schema unverändert (kein
Fingerprint-Effekt).

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

## Review-Punkte (offen für den Plan-Review)

- **R1 — Ort der Materialisierung (D1).** Zentral in `OperationMapper` (Empfehlung:
  eine Wahrheit, Adapter bleiben dumm) vs. je Renderer-Eintritt. Rückfallpunkt:
  erweist sich die `OperationMapper`-Normalisierung als zu invasiv für andere
  Op-Konsumenten, ist ein dedizierter Pre-Render-Schritt Plan B.
- **R2 — PG in der Abnahme (D3).** Als vollwertiges Abnahmekriterium (Empfehlung,
  da Gratis-Effekt und für das 64-bit-Ticket relevant) vs. nur als Folge-Notiz.
