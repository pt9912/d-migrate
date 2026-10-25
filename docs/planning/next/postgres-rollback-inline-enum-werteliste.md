# PostgreSQL: der Rollback nach erweiterter Werteliste stellt den CHECK nicht her (Issue #61)

> **Status:** Draft mit Scope (2026-10-25). **Vorbedingung:** keine für P0; P2 wartet auf die P1-Entscheidung.
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit.
> GitHub-Issue: [#61](https://github.com/pt9912/d-migrate/issues/61). Bezug: ADR 0055 (Wertevorrat im zielbewussten
> Vergleich), ADR 0060 (`RefTypeVocabulary`, SQLite); Lastenheft: [LF-014](../../../spec/lastenheft-d-migrate.md#lf-014) („Migrations-Rollback"),
> gegengelesen.

## Ziel

`schema migrate --generate-rollback` auf PostgreSQL liefert nach einer **erweiterten Werteliste** einer Inline-Enum-Spalte
entweder ein Down-Skript, das den alten `CHECK` wiederherstellt, oder meldet den Plan als nicht vollständig rückführbar.
Ein Exit 0 mit `planFullyRollbackable: true` und unverändert weit offenem `CHECK` entfällt.

## Problem (Issue #61, gemeldet für 1.10.0 und 1.9.1; am Code gelesen, noch nicht gemessen)

Tabelle `t (id integer, e enum values [a, b] required default a)`, Datenbank aus `schema generate --target postgresql`
(`e TEXT … CHECK ("e" IN ('a','b'))`), Datei mit `values: [a, b, c]`. Der Up-Pfad erweitert den CHECK; `down.sql` enthält nur
`ALTER TABLE "t" ALTER COLUMN "e" TYPE TEXT;`, `schema rollback --execute --allow-destructive` endet mit Exit 0, der CHECK lautet
weiter `= ANY (ARRAY['a','b','c'])`, `INSERT … 'c'` gelingt. Auf SQLite lehnt die Datenbank `c` nach dem Rollback ab.

**Wie der Up-Pfad den CHECK ersetzt:**
[`PostgresDiffTableOps.renderAlterColumnType`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffTableOps.kt)
wählt den Zieltyp je Richtung (`UP`: `op.after`, `DOWN`: `op.before`), löst den bisherigen Wertevorrat über
`dropExistingEnumValueCheck` (`DROP CONSTRAINT IF EXISTS <Katalogname>`) und fügt über `EnumValueCheck.clause` einen neuen
`ADD CHECK (… IN (…))` an, **wenn** `EnumValueCheck.inlineValues(targetType)` die Werte kennt. Es gibt keine eigene
`ModifyEnum`-Operation (gesucht, nicht vorhanden): eine Wertelistenänderung läuft als `AlterColumnType` über
[`OperationMapper.mapColumnChange`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/OperationMapper.kt).
Der Katalogname kommt aus
[`PostgresDiffRenderContext.enumValueCheckName`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffRenderContext.kt),
das für `UP` das **Ist-** (`currentSchema`), für `DOWN` das **Soll-Schema** (`desiredSchema`, die Datei) befragt.

**Ursache am Code (zwei Lücken, die nur zusammen den Befund ergeben; P0 bestätigt sie durch Messung):**

* **L1 — `op.before` trägt keine Werte.** Der Reader liefert `TEXT` plus CHECK-Constraint; den Wertevorrat faltet nur der Vergleich
  (`TableComparator.enumVocabularies`, `EnumCheckProjection`, ADR 0055). Im Down ist `targetType = op.before = Text`
  (`PostgresDiffTableOps.kt:201`), `typeUnchanged` ist `true`, `inlineValues(Text)` ist `null` → nur `TYPE TEXT`, kein `ADD CHECK`.
* **L2 — der erweiterte CHECK wird im Down nicht gelöst.** `enumValueCheckName` (`PostgresDiffRenderContext.kt:338-344`) befragt im
  Down das Soll-Schema, also die Datei; diese führt den Wertevorrat inline, nicht als `CHECK`-Constraint → `null` → kein `DROP`.
* **Folge für den Fix:** wer nur L1 behebt, erzeugt `ADD CHECK (… IN ('a','b'))` **ohne** `DROP` — alter (erweiterter) und neuer
  CHECK stehen nebeneinander; wer nur L2 behebt, löst den CHECK und lässt die Spalte ungeprüft. Option (a) muss **beide** Lücken
  schließen.
* **L3 — die Meldung.** `AlterColumnType` ist `AUTOMATIC_WITH_DATA_RISK` (`DiffOperation.kt:172`); der Blocker
  `ROLLBACK_NOT_POSSIBLE` entsteht im Renderer nur für `NOT_REVERSIBLE` (`PostgresDiffDdlGenerator.kt:77-96`). Ein Down, das sein Ziel
  verfehlt, löst keinen Blocker aus; der Renderer prüft sein Ergebnis nicht gegen den Zustand vor dem Up.

**Gegenprobe SQLite:** dort ersetzt der Tabellenneubau (benannter CHECK `ck_<tabelle>_<spalte>`, `EnumValueCheck.name`) den
Wertevorrat in beiden Richtungen; der Reader sieht den benannten CHECK als Constraint der Ist-Seite. Der Unterschied zu
PostgreSQL ist die Rendering-Strategie (Neubau vs. `ALTER … DROP/ADD CONSTRAINT` mit unbenanntem CHECK), nicht ADR 0060:
`RefTypeVocabulary` betrifft nur `ref_type`-Enums, und das Dialekt-Gate (ADR 0060, Entscheidung 2) setzt `resolvesRefTypeVocabulary` nur für
SQLite; PostgreSQL führt den Typ selbst. Der Inline-Pfad hier hängt an ADR 0055.

## Richtung (nach P0/P1)

| Option | Inhalt | Für | Wider |
| ------ | ------ | --- | ----- |
| (a) Down stellt die alte Werteliste her | beide Lücken schließen: L1 (Werte der Ist-Seite kennen) **und** L2 (den erweiterten CHECK im Down lösen) | Rollback tut, was er verspricht; paritätisch zu SQLite | Namensfrage (siehe unten); die Werte liefert `op.before` (Modell/Mapper, Tag-Folge) oder der Renderer aus `currentSchema` (nur Adapter) |
| (b) nicht als rückführbar melden | der Renderer setzt `ctx.addBlocker(ROLLBACK_NOT_POSSIBLE)` für diesen Fall **unabhängig von `op.reversibility`** (Adapter-only); setzt man stattdessen die Reversibility der Operation herab, ist es eine Hexagon-Änderung | klein, ehrlich | Nutzer verliert einen Rollback, den SQLite kann |
| (c) gestaffelt | (b) zuerst als Schutz, dann (a); (b) fällt für den Fall weg | beseitigt die falsche Zusicherung schnell | zwei Auslieferungen; die (b)-Tests (Exit 8, Blocker) müssen mit (a) umgeschrieben werden |

**Begriffe:** „(a) mit (b) als Rückfall" (Blocker nur für Fälle, die der Renderer nicht herstellen kann) ist faktisch ein Dauer-(c)
und kein reines (a). Vorläufige Empfehlung: **(a) mit (b) als Rückfall**, weil der Grundsatz „der Rollback stellt her oder
verweigert, nie meldet ok und tut nichts" so dauerhaft gilt; (c) als zeitliche Staffelung nur, wenn (a) nicht im selben Slice trägt.
Endgültig in P1.

**Namensfrage (P1 entscheidet).** Der Down wird vor dem Up gerendert; der Servername des **neuen** Up-CHECK ist beim Rendern
unbekannt, und der Kommentar an `enumValueCheckName` (`PostgresDiffRenderContext.kt:328-337`) warnt vor erratenen Namen (Kollision,
Ziffernanhang, Kürzung). Kandidaten:
1. Name aus dem **Ist-Namen** ableiten — sicher nur, wenn der alte Name durch den `DROP` frei wird (der Up löst den alten CHECK vor
   dem Anlegen, der neue bekäme dann denselben Servernamen); in P0 messen, auch bei Kollision und langen Namen.
2. Der Up legt den CHECK **explizit benannt** an (`EnumValueCheck.namedClause`, `ck_<tabelle>_<spalte>`): der Down kennt den Namen.
   Ändert den Up-Text, DDL-Goldens und Renderer-Unit-Tests — berührt damit den Up-Pfad (Ausnahme vom Nicht-Scope, Eigner-Entscheidung)
   und kann Drift gegen bestehende Datenbanken mit Servernamen erzeugen.
3. `DROP CONSTRAINT IF EXISTS` mit abgeleitetem Namen, und wo der Name nicht sicher ist, Fallback auf (b).

## Phasen

* **P0 Messung** (Integrationstest gegen PostgreSQL, Muster `PostgresMigrateRoundTripIntegrationTest` /
  `PostgresCustomTypeFileFormMigrateIntegrationTest`; PG-Version aus `TestImages.POSTGRESQL` angeben). Je Fall: Diff-Operationen
  (`before`/`after`), `down.sql`, `planFullyRollbackable`, Exit, Katalog-CHECK nach `schema rollback --execute --allow-destructive`,
  `INSERT` des abgelehnten Werts. Fälle:
  1. Werteliste **erweitert** (`[a,b]` → `[a,b,c]`) — der Befund des Issues;
  2. Werteliste **verkürzt** (`[a,b,c]` → `[a,b]`; Down muss `c` wieder erlauben);
  3. **Inline** (`values`) gegen **benannte Domain/`ref_type`** (nativer Enum-Typ, `AlterCustomType`-Pfad) — Gegenprobe, dass dieser
     Pfad nicht betroffen ist;
  4. **mehrere** Enum-Spalten in einer Tabelle, nur eine geändert, und zwei geändert;
  5. Rollback **nach `--execute`** (echter Hin-und-Rückweg) und Rollback gegen eine Datei (`file:`);
  6. **Gegenprobe SQLite**, dasselbe Schema (Soll: `c` abgelehnt);
  7. benannter vom Nutzer geschriebener CHECK statt Generate-Form (Servername vs. `ck_…`);
  8. Werteliste **und** Typ gleichzeitig geändert;
  9. nur die **Reihenfolge** der Werte geändert (soll kein Rollback-Statement erzeugen);
  10. **mehrere** Wertelisten-CHECKs an einer Spalte;
  11. `NOT VALID`-CHECK;
  12. gelesene Form `= ANY (ARRAY[…])` (Katalog) gegen die Generate-Form `IN (…)`; Name des Up-CHECK nach Ausführung (Namensfrage).
  Ergebnis: L1/L2/L3 bestätigt oder verworfen, mit Messwerten im Plan.
* **P1 Entscheidung:** Option (a)/(b)/(c); ob `op.before` Werte tragen muss; ADR-/Tag-Bedarf. Nummern **zum Aktivierungszeitpunkt
  nächster freier** ADR und nächster freier `schema-fingerprint-v…`-Tag (nicht vorab festlegen; andere Pläne konkurrieren darum).
  Greift die Lösung nur den Adapter-Renderer an, ändert sich Abdruck und Hexagon nicht → kein Tag, vermutlich kein ADR (Eigner
  bestätigt). Außerdem: wird die Rollback-Spec (`spec/` Rollback-/Migrationsregeln, `ddl-generation-rules.md`) berührt?
* **P2 Umsetzung mit Tests:**
  * Integrationstest gegen PostgreSQL: Up erweitern, `down.sql` anwenden, `INSERT … 'c'` wird von der Datenbank abgelehnt, `a`/`b`
    gelingen; CHECK-Definition im Katalog entspricht dem Zustand vor dem Up. Rot ohne die Änderung (der aktuelle Befund), absichtlich
    falsche Zusicherung einmal rot gesehen.
  * Fälle aus P0 (verkürzt, mehrere Spalten, benannter CHECK) je ein Test; bei Option (b) zusätzlich: Exit 8,
    `planFullyRollbackable: false`, `ROLLBACK_NOT_POSSIBLE` mit klarer Meldung.
  * Renderer-Unit-Test (`PostgresDiffDdlGenerator` DOWN): erwartete Statements in Reihenfolge `DROP CONSTRAINT` → `ADD CHECK`.
  * **Matrix** (`test/cross-dialect-matrix`): `Kind.ROLLBACK` setzt `generateRollback = true` und fängt die `.rollback.sql` in
    `rollbackBody`. Die Zelle läuft aber **file-zu-file im planOnly-Modus** (`MatrixSweepRunner.kt:163-170`): beide Seiten sind
    Inline-Enums mit Werten, `op.before` trägt sie, es gibt keinen Katalognamen. Sie bildet daher **nicht** den Live-Fehler ab; ohne
    die Änderung rendert der Down dort `ADD CHECK` ohne `DROP` (anderes Symptom). „Rot ohne die Änderung" belegt also nur dieses
    file-mode-Symptom, nicht das Issue. Ehrliche Varianten: eine Zelle mit **benanntem** Up-CHECK (Namensfrage Kandidat 2), die
    `DROP`+`ADD` pinnt, oder bei (b) eine Blocker-Zelle (Exit 8). Ein neuer Workstream in `MatrixWorkstreams` ist wahrscheinlich nötig.
    `carve-outs.yaml`: eine Zeile, die „Rollback je Dialekt" für diesen Fall abdeckt, gibt es nicht (G.2 `kind: blocker` `permanent`
    zielt auf `NOT_REVERSIBLE`; die ROLLBACK-Carve-outs sind `dialect: "*"` mit `ownerTests`). P0 benennt, welche Zeile die neue
    Zelle ablöst oder dass der neue Workstream keine ablöst. „Zweiter Lauf `operationsTotal` 0" gehört in den Integrationstest, nicht in
    die Matrix. Die Ausführung gegen die Datenbank belegt nur der Integrationstest.
* **P3 Doku:** Handbuch §Rollback in `docs/user/anwenderhandbuch.md` (nur Ist-Zustand: was ein Rollback nach Wertelistenänderung auf
  PostgreSQL tut bzw. dass er verweigert; Grenzen), `CHANGELOG.md` unter `[Unreleased]` → „Fixed" mit Verweis auf #61,
  `spec/ddl-generation-rules.md` nur falls eine Render-/Rollback-Regel sich ändert (dort mitziehen, nicht nachträglich), ADR gemäß
  P1 falls nötig. `docs/planning/next/README.md`/`done/` nachführen.

## Nicht-Scope

Andere Dialekte (MySQL nativer `ENUM`, SQL Server, Oracle) — in P0 nur als Lesebefund, falls dort dieselbe Lücke auftritt: dann
eigener `open/`-Eintrag. `AlterCustomType` (nativer PG-Enum) und `ref_type`-Rollback. Der Up-Pfad, **soweit** die Namensfrage (Kandidat 2) nicht anders entscheidet (Eigner).

## Akzeptanz

* Nach `schema migrate --execute --generate-rollback` und `schema rollback --execute --allow-destructive` auf PostgreSQL lehnt die
  Datenbank `INSERT … 'c'` ab (Option a) **oder** der Migrate endet mit Exit 8 und `planFullyRollbackable: false` (Option b). Der Fall
  „Exit 0, `planFullyRollbackable: true`, CHECK unverändert" tritt nicht mehr auf — belegt durch den Integrationstest.
* Verkürzte Werteliste: nach dem Rollback ist der zuvor abgelehnte Wert wieder erlaubt.
* Jede P0-Fallzeile, die einen Fehler misst, hat einen Test, der ohne die Änderung fehlschlägt; ausgenommen sind die grünen
  Gegenproben (Fall 3 `ref_type`/native Enum, Fall 6 SQLite), die grün bleiben müssen. Absichtlich falsche Zusicherung einmal rot gesehen.
* Die Matrix-Zelle (benannter Up-CHECK bzw. Blocker-Zelle) oder die dokumentierte Begründung, warum keine anlegbar ist, steht; im
  Integrationstest liefert nach dem Rollback ein zweiter Migrate `operationsTotal` 0.
* Handbuch beschreibt nur das gelieferte Verhalten; `CHANGELOG.md` nennt #61.
* Gates: `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module (einmal ohne `MODULES=`, falls eine geteilte
  Signatur wechselt), `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`, `make solid-suppression-gate`,
  `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
