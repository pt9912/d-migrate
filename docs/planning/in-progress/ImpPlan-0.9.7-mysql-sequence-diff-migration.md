# Implementierungsplan: 0.9.7 — MySQL Sequence Diff-Migration

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.3 Folge-Slice (MySQL diff-basierter Sequence-Renderer)
> **Status**: open 2026-05-19.
> **Vorbedingung**: E.3 Erstscheibe (PG-Sequence-Diff-Renderer) ✅;
>                  Vollständige MySQL-Sequence-Emulation
>                  (`done/mysql-sequence-emulation-plan.md`) ✅
>                  Phase A–E2 (2026-04-21) im DDL-Generator-Pfad;
>                  F.4 Renderer-Blocker-Bridge ✅ 2026-05-19.
> **Referenz**: `done/mysql-sequence-emulation-plan.md` (Vollvariante
>             im DDL-Generator-Pfad); `diffresult-migration-plan-2.md`
>             §E.3; `spec/ddl-generation-rules.md` §7.

---

## 1. Auslöser

Die vollstaendige MySQL-Sequence-Emulation
(`done/mysql-sequence-emulation-plan.md`, Phasen A–E2, abgeschlossen
2026-04-21) liefert den DDL-Generator-Pfad: `MysqlDdlGenerator`
rendert vollstaendige Sequence-DDL aus einer
`SequenceDefinition` (helper_table-Modus mit
`dmg_sequences`-Hilfsobjekten + kanonischen Sequence-Triggern); der
`MysqlSchemaReader` faltet die Hilfsobjekte zurueck auf
`SequenceDefinition`. Compare ist sequence-stabil.

Was fehlt: der **diff-basierte** Pfad. Heute routet
`MysqlDiffDdlGenerator.categorize()` jede der vier
Sequence-`DiffOperation`-Subtypes
(`CreateSequence`, `AlterSequence`, `DropSequence`, `RenameSequence`)
zu `OpCategory.UNSUPPORTED`. Der Renderer emittiert keinen DDL und
blockt mit `DIALECT_UNSUPPORTED_OPERATION`. Operatoren koennen
sequence-betreffende Diff-Migrationen damit nicht via `schema migrate`
fahren — sie muessten den DDL-Generator-Pfad (`schema generate`) nutzen
und das resultierende Skript manuell ausfuehren.

Im Detail:

- `CreateSequence`: heute Blocker. Soll: vollstaendige Emulation
  emittieren (helper_table + Trigger).
- `AlterSequence`: heute Blocker. Soll: declarative-Attribute-Aenderung
  emittieren (z.B. `UPDATE dmg_sequences SET increment_by = …`).
- `DropSequence`: heute Blocker. Soll: helper_table + Trigger droppen.
- `RenameSequence`: F.4 Sub-Slice A.2 Teil 1 hat die Mapper-Policy
  bereits auf `RenameSupport.Blocked(MYSQL_SEQUENCE_RENAME_OUT_OF_E3)`
  gesetzt — die Renderer-Pfade landen nie hier, aber sobald dieser
  Slice MySQL-Sequence-Rendering freischaltet, kann die Policy zu
  `DropCreateFallback` upgegradet werden (Drop-Create-Fallback mit
  `RenameProvenance`).

---

## 2. Warum jetzt?

E.3 (Sequence-Rendering im Diff-Pfad) hat heute nur den PG-Slice
erledigt; MySQL und SQLite sind im Roadmap "E Rest" als offen
markiert. Die zugrundeliegende **MySQL-Emulation** ist
produktreif (Phase A–E2 abgeschlossen 0.9.4); fehlt nur die
Brueckung in den Diff-Pfad. Das ist eine vergleichsweise kleine
Bruecke fuer einen relativ grossen Funktionsgewinn — Operatoren
mit Sequence-tragenden MySQL-Schemata bekommen Diff-basierte
Migrationen ohne `schema generate`-Workaround.

---

## 3. Scope

### 3.1 In-Scope

- Neue Datei: `MysqlDiffSequenceOps` in
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/`,
  analog zu `PostgresDiffSequenceOps`.
- Render-Funktionen pro Subtyp:
  - `renderCreateSequence(op, ctx)` — produziert die vier
    DDL-Statements (helper_table CREATE, optionaler initial INSERT,
    Sequence-Trigger CREATE) aus der bestehenden
    `MysqlDdlGenerator`-Emulation. Up + Down.
  - `renderAlterSequence(op, ctx)` — produziert
    `UPDATE dmg_sequences SET …`-Statements fuer declarative
    Attribute (start, increment, min/max, cycle, cache). Up + Down
    (inverse Werte aus `op.before`).
  - `renderDropSequence(op, ctx)` — droppt Trigger + Zeile in
    `dmg_sequences`. Up + Down.
  - `renderRenameSequence(op, ctx)` — `RENAME TABLE`-Pattern
    funktioniert nicht (Sequence ist eine Zeile in einem Helper-
    Table, nicht das Helper-Table selbst). Stattdessen
    `UPDATE dmg_sequences SET sequence_name = …` plus optionales
    Trigger-Rename (`RENAME` auf den Sequence-Trigger). Up + Down.
- `MysqlDiffDdlGenerator.categorize()` routet die vier Subtypes
  jetzt auf eine neue `OpCategory.SEQUENCE` (oder analog zur PG-
  Variante eine neue MySQL-spezifische Kategorie). `RenameSequence`
  wandert von `UNSUPPORTED` nach `SEQUENCE`.
- `MysqlObjectRenamePolicy.classify(...)`: die heutige
  `RenameSupport.Blocked(..."MySQL sequence rendering is out of
  E.3 scope today")` wird zu `RenameSupport.DropCreateFallback`
  (oder `RenameSupport.Native` falls der UPDATE-basierte Rename
  als nativ zaehlt — Decision in Sub-Slice C).
- `make docker-check` gruen ueber MySQL-Driver + hexagon:core.
- Tests pro Subtyp: Positiv-Render (Up + Down) und Blocker fuer
  Carve-outs (z.B. `current_value`-Migration, siehe §3.2).

### 3.2 Out-of-Scope

- **`preserveCurrentValue`-Policy**: live-Lesen des aktuellen
  Sequence-Werts und nachgelagertes `ALTER`-Rendering. Bleibt
  ausgeklammert in einem eigenen Cross-Dialect-Plan
  (`ImpPlan-0.9.7-sequence-preserve-current-value.md`, parallel
  in-progress).
- **SQLite-Sequence-Diff**: eigener Plan
  (`open/sqlite-sequence-emulation-plan.md`). Dieser Slice ist
  MySQL-only.
- **Cross-Dialect-Transfer von Sequences zwischen Dialekten** (z.B.
  PG → MySQL): eigener Architektur-Plan
  (`ImpPlan-0.9.7-cross-dialect-sequencing.md`, parallel).
- **MariaDB-native Sequences** (`CREATE SEQUENCE`). MariaDB hat
  seit 10.3 echte Sequenzen, aber d-migrate's emulation-pfad
  bleibt einheitlich; native MariaDB-Sequences sind ein optionaler
  zukuenftiger Pfad mit eigenem Capability-Gate.

---

## 4. Vorbedingungen

| Vorbedingung | Status | Kommentar |
| ------------ | ------ | --------- |
| `MysqlDdlGenerator.helper_table`-Emulation | ✅ 0.9.4 AP 6.3/6.4 | Plan-Doc: `done/mysql-sequence-emulation-plan.md` |
| `MysqlSchemaReader` faltet Hilfsobjekte zurueck | ✅ 0.9.4 AP 6.1–6.3 | `dmg_sequences` + Support-Routinen + Trigger werden erkannt |
| F.4 RenameSequence Subtyp + Policy | ✅ 2026-05-19 | `MysqlObjectRenamePolicy` blockt heute, wartet auf Upgrade |
| F.4 Renderer-Blocker-Bridge | ✅ 2026-05-19 | `PlannerBlockerClassifier`-Pattern verfuegbar |
| MySQL Server-Version-Detection | ✅ E.1 C.2 | Vendor-String + Version fuer Capability-Gates |

---

## 5. Architektur

### 5.1 Re-Use vs. Duplikation

Die Emulation-Logik (helper_table-DDL-Templates, Trigger-Body) lebt
heute in `MysqlDdlGenerator`. Sub-Slice A muss diese Templates
extrahieren in eine wiederverwendbare Helper-Klasse
(`MysqlSequenceEmulationTemplates` oder analog), damit
`MysqlDiffSequenceOps` sie konsumieren kann ohne den ganzen
DDL-Generator zu instantiieren.

Decision in Sub-Slice A: extrahieren vs. inline-Copy. Empfehlung:
extrahieren, weil sonst zwei Wartungs-Stellen.

### 5.2 Op-Subtyp-Mapping

| DiffOperation | MySQL-Rendering |
|---|---|
| `CreateSequence` | `CREATE TABLE IF NOT EXISTS dmg_sequences (…)` (idempotent — einmaliges Setup); `INSERT INTO dmg_sequences (name, …) VALUES (…)`; `CREATE TRIGGER` fuer den Sequence-Trigger |
| `AlterSequence(before, after)` | `UPDATE dmg_sequences SET <changed-fields> WHERE name = …` |
| `DropSequence` | `DROP TRIGGER` fuer Sequence-Trigger; `DELETE FROM dmg_sequences WHERE name = …` |
| `RenameSequence(from, to)` | `UPDATE dmg_sequences SET name = 'to' WHERE name = 'from'`; optional Trigger-Rename via Drop+Create |

Die `dmg_sequences`-Helper-Table wird beim ersten `CreateSequence`
in einem Plan via `CREATE TABLE IF NOT EXISTS` angelegt; spaetere
`CreateSequence`-Ops nutzen sie wieder. Im `DropSequence`-Pfad
wird die Tabelle NICHT geloescht (andere Sequenzen leben darin)
— bleibt als idempotente Infrastruktur-Tabelle.

### 5.3 Down-Direction

Standard-Pattern wie bei den anderen Sequence-Renderern:
- `CreateSequence` Down = `DropSequence`-Sequenz.
- `AlterSequence` Down = `UPDATE` auf `op.before`-Werte.
- `DropSequence` Down = `CreateSequence`-Sequenz mit gespeicherter
  `SequenceDefinition`.
- `RenameSequence` Down = inverse `UPDATE`.

### 5.4 RenameSequence-Policy upgraden

`MysqlObjectRenamePolicy.classify(...)` heute:

```kotlin
DiffObjectType.SEQUENCE -> RenameSupport.Blocked(
    code = "OBJECT_RENAME_UNSUPPORTED",
    message = "MySQL sequence rendering is out of E.3 scope today; …",
)
```

Sub-Slice C upgradet zu:

```kotlin
DiffObjectType.SEQUENCE -> RenameSupport.Native
```

Begruendung: `UPDATE dmg_sequences SET name = …` ist ein
einzelner DDL-aequivalenter Schritt; konzeptionell ist das ein
natives Rename. Der `RenameSequence`-`DiffOperation`-Subtyp
existiert und wird vom neuen `MysqlDiffSequenceOps.renderRenameSequence`
bedient.

Alternative: `RenameSupport.DropCreateFallback("MySQL emuliert
Sequence-Rename via dmg_sequences-UPDATE, vergleichbar mit
Drop+Create")`. Decision in Sub-Slice C.

---

## 6. Sub-Slice-Schnitt

### Sub-Slice A — Template-Extraktion

- `MysqlSequenceEmulationTemplates` extrahiert aus
  `MysqlDdlGenerator` die helper_table-DDL, INSERT-Template und
  Sequence-Trigger-Template.
- Existierende `MysqlDdlGenerator`-Tests bleiben gruen.
- Keine Verhaltensaenderung sonst.

### Sub-Slice B — Diff-Render-Pfade

- `MysqlDiffSequenceOps` mit `renderCreateSequence` /
  `renderAlterSequence` / `renderDropSequence` (alle Up + Down).
- `MysqlDiffDdlGenerator.categorize()` routet die drei Subtypes
  auf neue/bestehende `OpCategory.SEQUENCE`.
- Tests: pro Subtyp Up/Down-SQL-Pin.

### Sub-Slice C — `RenameSequence` Pfad

- `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` upgraded.
- `MysqlDiffSequenceOps.renderRenameSequence` ergänzt.
- `MysqlDiffDdlGenerator.categorize()` routet `RenameSequence` auf
  `SEQUENCE`.
- Tests: Mapper emittiert `RenameSequence`-Op fuer MySQL;
  Renderer emittiert `UPDATE dmg_sequences …`.

### Sub-Slice D — F.4 Sub-Slice D Integration

- `SequenceDefaultReprojector` (existiert seit F.4 D) wirkt
  automatisch auf MySQL — keine Aenderung noetig, weil der
  Reprojector dialekt-neutral ist.
- Test pinnt: MySQL + `RenameSequence` + `CreateTable mit
  SequenceNextVal-default(old)` → Plan rewrited Default auf `new`.

### Sub-Slice E — Closing

- §E.3-DoD im master plan Eintrag fuer MySQL ergänzen.
- CHANGELOG-Eintrag `### Added`.
- `spec/cli-spec.md` MySQL-Sequence-Renderbarkeit dokumentieren.
- Plan-Doc nach `done/`.

---

## 7. Akzeptanzkriterien

- [ ] `MysqlDiffSequenceOps` rendert `CreateSequence`,
      `AlterSequence`, `DropSequence` und `RenameSequence` in beide
      Richtungen.
- [ ] `MysqlDiffDdlGenerator.categorize()` routet die vier
      Subtypes nicht mehr auf `UNSUPPORTED`.
- [ ] `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` liefert
      `RenameSupport.Native` (oder `DropCreateFallback`, je
      nach Sub-Slice-C-Entscheidung).
- [ ] Bestehende `MysqlDdlGenerator`-Tests bleiben gruen
      (Template-Extraktion ist nicht-destruktiv).
- [ ] F.4 Sub-Slice D `SequenceDefaultReprojector` wirkt fuer
      MySQL pino-genau.
- [ ] Pro Subtyp je ein Positiv-Test (Up + Down) und ein
      Blocker-Test fuer ein Carve-out (z.B. `preserveCurrentValue`).
- [ ] `make docker-check` gruen.

---

## 8. Definition of Done (§13-Template)

- [ ] **Betroffener Modus**: alle Modi (file-to-file, file-to-DB,
      execute, rollback).
- [ ] **Renderbare Operationen + Blocker**: CREATE/ALTER/DROP/RENAME
      Sequence rendern; `preserveCurrentValue`-Operationen bleiben
      `MANUAL_ACTION_REQUIRED` bis der Cross-Dialect-Plan landet.
- [ ] **Neue Diagnostics / Blocker / primaryBlockedReason**: keine
      neuen Codes; alte
      `"MySQL sequence rendering is out of E.3 scope today"`-Blocker
      verschwindet.
- [ ] **Up- und Down-Verhalten**: getrennt gepinnt pro Subtyp.
- [ ] **Report-/Metadatenfelder**: bestehende
      `objectType = "SEQUENCE"`-Konvention; keine Aenderung.
- [ ] **Betroffene Dialekte**: nur MySQL.
- [ ] **F.0-Erfuellung**: irrelevant.
- [ ] **Positive und blockierende Testpfade**: siehe §7.
- [ ] **Rollback-Test oder Begruendung**: Standard-Down-Pfad fuer
      jeden Subtyp.
- [ ] **Datei-zu-Datei-Verhalten**: identisch zum DB-Pfad
      (`preserveCurrentValue` nicht relevant in Datei-zu-Datei).
- [ ] **Bestehende 0.9.7-Vertraege unveraendert**: bestehende
      `MysqlDdlGenerator`-Pfade bleiben unveraendert. F.4
      RenameSequence-Mapper-Pfad wird aktiviert.
- [ ] **Slice kann unabhaengig implementiert und verifiziert
      werden**: ja, Sub-Slices A → B → C → D sequentiell, E paperwork.

---

## 9. Out-of-Scope / Folge-Themen

- **`preserveCurrentValue`-Policy**: eigener Cross-Dialect-Plan
  (`ImpPlan-0.9.7-sequence-preserve-current-value.md`).
- **SQLite-Sequence-Diff**: eigener Plan
  (`open/sqlite-sequence-emulation-plan.md`).
- **Cross-Dialect-Sequence-Transfer**: Architektur-Plan
  (`ImpPlan-0.9.7-cross-dialect-sequencing.md`).
- **MariaDB-native Sequences** (10.3+): koennte ueber einen
  Capability-Gate zukuenftig den Emulation-Pfad ersetzen; kein
  0.9.7-Scope.
- **Sequence-`cache`-Attribut**: MySQL hat kein direkt
  vergleichbares Konzept; `cache` wird heute in
  `dmg_sequences` als deklaratives Feld gehalten, aber nicht
  semantisch ausgewertet. Bewusster Carve-out.

---

## 10. Risiken

### 10.1 `dmg_sequences`-Helper-Table-Ko-Existenz

Wenn das Live-Schema bereits eine Tabelle `dmg_sequences` aus
Phase A–E2 hat, muss `CreateSequence` ueber `IF NOT EXISTS`
arbeiten — sonst Fehler bei der ersten Migration. Mitigation:
Template-Konstanten verwenden bereits `CREATE TABLE IF NOT EXISTS`.

### 10.2 Trigger-Body-Stabilitaet

Der Sequence-Trigger-Body ist im DDL-Generator-Pfad gut getestet
(Phase A–E2). Im Diff-Pfad emittiert der Renderer den gleichen
Body — kein neuer Test-Korpus noetig, aber ein Cross-Pfad-Pin
(`schema generate` vs. `schema migrate` produzieren das gleiche
Trigger-DDL) ist sinnvoll.

### 10.3 RenameSequence semantisch ≠ ALTER TABLE RENAME

Der MySQL-Rename ist ein `UPDATE` auf einer Helper-Zeile, nicht
ein DDL-RENAME. Das passt nicht 1:1 zur PG- oder zur
sqlite-Bedeutung. Mitigation: Renderer kommentiert die emittierte
SQL ausreichend, damit Operatoren das Rebuild-Modell verstehen.

---

## 11. Erwartete Commit-Reihenfolge

| Sub-Slice | Commit-Subjekt-Skizze |
|---|---|
| A | `refactor(mysql): extract sequence-emulation templates from MysqlDdlGenerator` |
| B | `feat(mysql): diff-based CreateSequence / AlterSequence / DropSequence` |
| C | `feat(mysql): RenameSequence via UPDATE dmg_sequences + policy upgrade` |
| D | `test(mysql): SequenceDefaultReprojector pins for MySQL` |
| E | `docs(plan): MySQL Sequence Diff-Migration closing` |
