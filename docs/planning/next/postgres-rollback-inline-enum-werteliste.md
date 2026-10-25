# PostgreSQL: der Rollback nach erweiterter Werteliste stellt den CHECK nicht her (Issue #61)

> **Status:** Draft mit Scope (2026-10-25). **Vorbedingung:** keine für P0; P2 wartet auf die P1-Entscheidung.
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit.
> GitHub-Issue: [#61](https://github.com/pt9912/d-migrate/issues/61). Bezug: ADR 0055 (Wertevorrat im zielbewussten
> Vergleich), ADR 0060 (`RefTypeVocabulary`, SQLite); Lastenheft-Kennungen werden in P1 gegen `spec/lastenheft-d-migrate.md`
> nachgetragen (nicht geraten).

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

**Hypothesen für den Down-Ausfall (P0 entscheidet, nicht annehmen):**

* **H1 — `op.before` trägt keine Werte.** Der Reader liefert die Spalte als `TEXT` plus CHECK-Constraint; den Wertevorrat faltet
  nur der Vergleich (`TableComparator.enumVocabularies`, `EnumCheckProjection`, ADR 0055) zur Enum-Gleichheit. Die Operation
  trägt dann `before = Text`, `inlineValues(before)` ist `null`, der Down-Pfad emittiert nur `TYPE TEXT` und kein `ADD CHECK`. Das
  passt zum beobachteten `down.sql`.
* **H2 — der neue CHECK wird im Down nicht gelöst.** Der Up legt den erweiterten CHECK unbenannt an (Servername); im Down fragt
  `enumValueCheckName` die **Datei**, die den Constraint nicht als `CHECK` führt (Inline-Enum) → `null` → kein `DROP`. Der erweiterte
  CHECK bleibt stehen, das Ergebnis aus dem Issue.
* **H3 — Meldung.** `planFullyRollbackable` hängt an `Reversibility` der Operation (`AlterColumnType` gilt als umkehrbar) und an
  Blockern des Renderers (`ctx.addBlocker(ROLLBACK_NOT_POSSIBLE)` nur bei `NOT_REVERSIBLE`); ein Down, das sein Ziel verfehlt, löst
  keinen Blocker aus. Der Renderer prüft sein Ergebnis nicht gegen den Zustand vor dem Up.

**Gegenprobe SQLite:** dort ersetzt der Tabellenneubau (benannter CHECK `ck_<tabelle>_<spalte>`, `EnumValueCheck.name`) den
Wertevorrat in beiden Richtungen; der Reader sieht den benannten CHECK als Constraint der Ist-Seite. Der Unterschied zu
PostgreSQL ist die Rendering-Strategie (Neubau vs. `ALTER … DROP/ADD CONSTRAINT` mit unbenanntem CHECK), nicht die Wertevorrats-Auflösung
von ADR 0060 (`RefTypeVocabulary` betrifft nur `ref_type`-Enums und ist laut ADR 0060 Entscheidung 2 auf SQLite gesetzt; PostgreSQL
führt den Typ selbst).

## Richtung (nach P0/P1)

| Option | Inhalt | Für | Wider |
| ------ | ------ | --- | ----- |
| (a) Down stellt die alte Werteliste her | Down: `DROP CONSTRAINT` des erweiterten CHECK (Name aus dem **Ist-Zustand vor dem Up**, nicht aus der Datei) + `ADD CHECK` mit den Werten aus `op.before`/der Ist-Seite; ggf. benannter Up-CHECK, damit der Down ihn eindeutig trifft | Rollback tut, was er verspricht; paritätisch zu SQLite | `op.before` muss die Werte tragen (Modell/Mapper) oder der Renderer holt sie aus `currentSchema`; berührt Hexagon, ggf. Fingerabdruck-Tag |
| (b) nicht als rückführbar melden | Down-Rendering des Falls setzt `ROLLBACK_NOT_POSSIBLE`, `planFullyRollbackable: false`, Exit 8 | klein, ehrlich, nur Adapter | Nutzer verliert einen Rollback, den SQLite kann; Up-only bleibt möglich |
| (c) gestaffelt | zuerst (b) als Schutz (verhindert den Fehlbefund sofort), dann (a); (b) fällt für den Fall weg, sobald (a) trägt | beseitigt die falsche Zusicherung schnell | zwei Auslieferungen, doppelte Tests |

Kriterien: Reicht die Information im Renderer (`currentSchema` ist im `PostgresDiffRenderContext` vorhanden) — dann ist (a) ein
Adapter-Fix ohne Hexagon-Änderung und ohne Tag. Muss `op.before` selbst Werte tragen, ist es eine Modell-/Mapper-Änderung
(Tag-Bump, ADR). **Vorläufige Empfehlung: (a), mit (b) als Rückfall** für Fälle, die der Renderer nicht herstellen kann
(unbekannter Katalogname, mehrere Wertelisten-CHECKs an einer Spalte, `NOT VALID`) — damit gilt der Grundsatz „der Rollback stellt
her oder verweigert", nie „meldet ok und tut nichts". Endgültig in P1.

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
  7. benannter vom Nutzer geschriebener CHECK statt Generate-Form (Servername vs. `ck_…`).
  Ergebnis: H1/H2/H3 bestätigt oder verworfen, mit Messwerten im Plan.
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
  * **Matrix** (`test/cross-dialect-matrix`): die Matrix sieht Rollback — `Kind.ROLLBACK` setzt `generateRollback = true` und fängt
    den Inhalt der `.rollback.sql` in `rollbackBody`, allerdings nur im file-mode und ohne Ausführung. Eine ROLLBACK-Zelle
    „Inline-Enum, Werteliste erweitert" für PostgreSQL pinnt den Down-Text (enthält `DROP CONSTRAINT` und `ADD CHECK`, nicht nur
    `TYPE TEXT`) bzw. bei (b) den Blocker. Bisher liegen die ROLLBACK-Zellen als Carve-out `dialect: "*"` (`fixtures/carve-outs.yaml`);
    P0 prüft, ob eine echte Zelle ohne neuen Workstream anlegbar ist, sonst Workstream in `MatrixWorkstreams` ergänzen. Fixture zuerst,
    rot ohne die Änderung. Die Ausführung gegen die Datenbank bleibt Sache des Integrationstests — die Matrix belegt sie nicht.
* **P3 Doku:** Handbuch §Rollback in `docs/user/anwenderhandbuch.md` (nur Ist-Zustand: was ein Rollback nach Wertelistenänderung auf
  PostgreSQL tut bzw. dass er verweigert; Grenzen), `CHANGELOG.md` unter `[Unreleased]` → „Fixed" mit Verweis auf #61,
  `spec/ddl-generation-rules.md` nur falls eine Render-/Rollback-Regel sich ändert (dort mitziehen, nicht nachträglich), ADR gemäß
  P1 falls nötig. `docs/planning/next/README.md`/`done/` nachführen.

## Nicht-Scope

Andere Dialekte (MySQL nativer `ENUM`, SQL Server, Oracle) — in P0 nur als Lesebefund, falls dort dieselbe Lücke auftritt: dann
eigener `open/`-Eintrag. `AlterCustomType` (nativer PG-Enum) und `ref_type`-Rollback. Der Up-Pfad (arbeitet laut Issue korrekt).

## Akzeptanz

* Nach `schema migrate --execute --generate-rollback` und `schema rollback --execute --allow-destructive` auf PostgreSQL lehnt die
  Datenbank `INSERT … 'c'` ab (Option a) **oder** der Migrate endet mit Exit 8 und `planFullyRollbackable: false` (Option b). Der Fall
  „Exit 0, `planFullyRollbackable: true`, CHECK unverändert" tritt nicht mehr auf — belegt durch den Integrationstest.
* Verkürzte Werteliste: nach dem Rollback ist der zuvor abgelehnte Wert wieder erlaubt.
* Jede P0-Fallzeile hat einen Test, der ohne die Änderung fehlschlägt; die SQLite-Gegenprobe bleibt grün.
* Die Matrix-ROLLBACK-Zelle (oder die dokumentierte Begründung, warum keine anlegbar ist) steht; zweiter Lauf `operationsTotal` 0.
* Handbuch beschreibt nur das gelieferte Verhalten; `CHANGELOG.md` nennt #61.
* Gates: `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module (einmal ohne `MODULES=`, falls eine geteilte
  Signatur wechselt), `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`, `make solid-suppression-gate`,
  `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
