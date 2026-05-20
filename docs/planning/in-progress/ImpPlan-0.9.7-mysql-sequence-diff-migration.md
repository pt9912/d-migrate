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
  bereits auf `RenameSupport.Blocked("OBJECT_RENAME_UNSUPPORTED")`
  gesetzt — die Renderer-Pfade landen in diesem Zustand nie hier.
  Sobald dieser Slice freischaltet, kann die Policy zu
  `RenameSupport.DropCreateFallback` upgegradet werden, damit der
  Mapper den Sequence-Rename als emuliertes `DropSequence` + `CreateSequence`
  mit `RenameProvenance` rendert.

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
- Alle neuen Sequence-Diff-Renderer sind nur im
  `MysqlNamedSequenceMode.HELPER_TABLE`-Modus aktiv; bei anderem
  Modus werden sie explizit auf Diff-Ebene mit `E056` + `MANUAL_ACTION_REQUIRED`
  blockiert (`ctx.skip(..., primaryBlockedReason = MANUAL_ACTION_REQUIRED)`).
  Es darf dann keinerlei SQL emittiert werden. Die eigentliche Guard-Logik wird im
  `MysqlDiffSequenceOps`-Renderer verankert, nicht erst indirekt
  im `MysqlDdlGenerator`.
- Render-Funktionen pro Subtyp:
  - `renderCreateSequence(op, ctx)` — produziert die SQL-Statements
    für die einzelne Sequence (`INSERT`/`UPDATE` der Zeile + Trigger-Rendern)
    aus der bestehenden `MysqlDdlGenerator`-Emulation. Alle globalen
    Bootstrap-Objekte (`dmg_sequences`, `dmg_nextval`, `dmg_setval`) werden
    über einen zentralen Diff-Header (einmal pro Migration) erzeugt.
    Vorher wird die Support-Kanonik geprüft: `dmg_sequences`-Tabellenschema,
    `dmg_nextval`/`dmg_setval`-Signaturen, `dmg_sequences`-Row-Metadaten
    (`managed_by`, `format_version`, `next_value`) und der dem Sequence-Objekt
    zugeordnete Trigger-Name aus der Emulationsdefinition.
    Ein Trigger-Muster darf nur aus verifizierten Sequenz-Metadaten abgeleitet werden;
    ohne harte Trigger-Zuordnung wird mit `E124` geblockt.
    Bei `dmg_*`-Objekten mit fachlich unpassender Form (`E124`) wird die
    Render-Pipeline abgebrochen. Danach gilt:
    Wenn die `dmg_sequences`-Zeile bereits existiert, erfolgt zuerst ein
    Drift-Check gegen verwaltete Felder (`increment_by`, `min_value`,
    `max_value`, `cycle`, `cache`) sowie `managed_by`/`format_version`/`next_value`:
    `start` ist in der Emulation der persistierte Laufzeitzustand
    (`next_value`) der Sequence und darf nicht als harte
    Konsistenzprüfung verwendet werden.
    Bei Konsistenz wird ein Trigger-Reconcile (`DROP TRIGGER IF EXISTS` +
    `CREATE TRIGGER`) ausgeführt, bei Abweichung wird `E124` geblockt; bei
    fehlender Zeile wird normaler `INSERT` gerendert. Up + Down.
  - `renderAlterSequence(op, ctx)` — produziert
    `UPDATE dmg_sequences SET …`-Statements fuer managed
    Felder (`increment_by`, `min_value`, `max_value`,
    `cycle`, `cache`). `start` ist in diesem Slice der persistierte
    Laufzeitzustand (`next_value`) und wird nicht als DDL-Attribut
    verändert. Up + Down (inverse Werte aus `op.before`) auf den
    verwalteten Deklarativfeldern (`increment_by`, `min_value`,
    `max_value`, `cycle`, `cache`) ausschliesslich.
  - `renderDropSequence(op, ctx)` — droppt die dem `SequenceDefinition`-
    zugeordneten Trigger + Zeile in `dmg_sequences`. Up + Down.
  - `renderRenameSequence(op, ctx)` — `RENAME TABLE`-Pattern
    funktioniert nicht (Sequence ist eine Zeile in einem Helper-Table,
    und der Support-Path ist auf `HELPER_TABLE` begrenzt).
    Wenn ein echter `RenameSequence`-Op emittiert wird, ist `UPDATE
    dmg_sequences SET name = …` + Trigger-Rebuild (`DROP TRIGGER` +
    `CREATE TRIGGER`) erforderlich, da MySQL kein generisches
    Trigger-Rename kennt. Die betroffenen Trigger werden aus
    Sequence-Metadaten aufgelöst.
    Im F.4-Pfad ist der Rename primär als
    `DropSequence` + `CreateSequence`-Fallback gedacht, und dann darf
    dieser Renderer als Defensive-Implementierung nur als Fallback
    dienen.
    Produktiv werden Rename-Migrationspfade im Slice auf
    `DropCreateFallback` mit `RenameProvenance` gesetzt.
- `MysqlDiffDdlGenerator.categorize()` routet die vier Subtypes
  jetzt auf eine neue `OpCategory.SEQUENCE` (oder analog zur PG-
  Variante eine neue MySQL-spezifische Kategorie). `RenameSequence`
  bleibt nur als defensiver Fallback dokumentiert; produktiv wird Rename
  über die F.4-Policy auf `DropCreateFallback` umgelenkt.
- `MysqlObjectRenamePolicy.classify(...)`: die heutige
   `RenameSupport.Blocked(..."MySQL sequence rendering is out of
   E.3 scope today")` wird auf `RenameSupport.DropCreateFallback`
   (emulierter Rename) gesetzt.
- `make docker-check` gruen ueber MySQL-Driver + hexagon:core.
- Tests pro Subtyp: Positiv-Render (Up + Down), Down-Regressionen, sowie
  gezielte Blocker- und Schutzfälle für Carve-outs (z.B.
  start-Wert-/Laufzeitzustands-Update, mehrfaches `CreateSequence` in einer Migration,
  bereits vorhandene Helfer-Objekte).

### 3.2 Out-of-Scope

- **`preserveCurrentValue`-Policy**: Das aktuelle Datenmodell kennt
  noch kein separates Feld für den Runtime-Wert (`current_value`) einer
  Sequence. Die eigentliche „current value preservation“ bleibt deshalb in
  einem separaten Cross-Dialect-Plan
  (`ImpPlan-0.9.7-sequence-preserve-current-value.md`, parallel
  in-progress) und ist Out-of-Scope. Für diesen Slice gilt `start` als
  persistierter Laufzeitzustand (`next_value`) und wird nicht als
  deklaratives DDL-Attribut migriert.
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
DDL-Generator zu instantiieren. Die Helper-Klasse liefert zusätzlich einen
expliziten "bootstrap once"-Ausgabe-Mechanismus für `dmg_sequences` und
`dmg_nextval`/`dmg_setval`, der im Diff-Generator nur ein einziges Mal
emittiert wird.

Decision in Sub-Slice A: extrahieren vs. inline-Copy. Empfehlung:
extrahieren, weil sonst zwei Wartungs-Stellen.

### 5.2 Op-Subtyp-Mapping

| DiffOperation | MySQL-Rendering |
|---|---|
| `CreateSequence` | (nur `HELPER_TABLE`) **Globaler Bootstrap einmalig pro Migration**: `dmg_sequences`-Tabelle + `dmg_nextval`/`dmg_setval` (nur einmal), danach pro Sequence `INSERT INTO dmg_sequences (name, …) VALUES (…)`. Vorher wird die Support-Kanonik geprüft (`dmg_sequences`-Schema, `dmg_nextval`/`dmg_setval`-Signaturen, `dmg_sequences`-Metadaten `managed_by`, `format_version`, `next_value` sowie bekannte Trigger-Zuordnung aus der Emulationsdefinition): bei Abweichung `E124`-Blocker. Bei bestehender Zeile wird zuerst der Drift gegen verwaltete Felder geprüft (`increment_by`, `min_value`, `max_value`, `cycle`, `cache`; plus `managed_by`, `format_version`, `next_value`; `start`/persistierter Zustand nicht hart geprüft): bei Abweichung `E124`-Blocker, bei Konsistenz `DROP TRIGGER IF EXISTS` + `CREATE TRIGGER` (idempotentes Reconcile). |
| `AlterSequence(before, after)` | `UPDATE dmg_sequences SET <changed-fields> WHERE name = …` für verwaltete Felder (`increment_by`, `min_value`, `max_value`, `cycle`, `cache`). |
| `DropSequence` | (nur `HELPER_TABLE`) `DROP TRIGGER IF EXISTS` für alle dem Sequence-Objekt zugeordneten Trigger; `DELETE FROM dmg_sequences WHERE name = …` |
| `RenameSequence(from, to)` | Defensive-Fallback nur: `UPDATE dmg_sequences SET name = 'to' WHERE name = 'from'`; Trigger-Rebuild via `DROP TRIGGER IF EXISTS` + `CREATE TRIGGER` für den dem Rename-Objekt zugeordneten Sequence-Trigger |

Die `dmg_sequences`-Helper-Table wird beim ersten `CreateSequence`
im Plan nach erfolgreicher Kollisionsprüfung angelegt; spaetere
`CreateSequence`-Ops nutzen sie wieder. Im `DropSequence`-Pfad
wird die Tabelle NICHT geloescht (andere Sequenzen leben darin)
— bleibt als idempotente Infrastruktur-Tabelle.
Hinweis: Trigger-Reconcile nutzt nur im `SequenceDefinition` hinterlegte
Trigger-Namensmetadaten; fehlt diese sichere Zuordnung, darf kein blindes
`DROP TRIGGER`/`CREATE TRIGGER` erfolgen, sondern es wird `E124` berichtet.

### 5.3 Down-Direction

Standard-Pattern wie bei den anderen Sequence-Renderern:
- `CreateSequence` Down = `DropSequence`-Sequenz.
- `AlterSequence` Down = `UPDATE` auf `op.before`-Werte für die verwalteten
  Deklarativfelder (`increment_by`, `min_value`, `max_value`, `cycle`, `cache`).
- `DropSequence` Down = `CreateSequence`-Sequenz mit gespeicherter
  `SequenceDefinition`.
- `RenameSequence` ist primär kein Produktivpfad. `Up/Down` werden
  im regulären Diff-Slice nicht generiert; der produktive Fallback
  läuft über `DropSequence` + `CreateSequence` mit `RenameProvenance`.
  Direkter `RenameSequence`-Down/Up bleibt nur defensive Regression-Coverage
  (`UPDATE` + Trigger-Rebuild).

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
DiffObjectType.SEQUENCE -> RenameSupport.DropCreateFallback(
    message = "MySQL emuliert Sequenz-Rename über UPDATE auf dmg_sequences und Trigger-Rebuild.",
)
```

Begruendung: In F.4 ist der primäre Sequenz-Rename-Pfad
`DropCreateFallback`, also `DropSequence` + `CreateSequence` mit
`RenameProvenance`. Produktiv darf in diesem Slice kein direkter
`RenameSequence`-Renderer-Output entstehen; dieser Pfad muss in den
Diff-Regeln über die Fallback-Policy abgedeckt sein. Der direkte
`RenameSequence`-Subtyp bleibt nur als Regression/Defensive-Coverage
erlaubt; wenn er trotzdem emittiert wird, übernimmt ihn
`MysqlDiffSequenceOps.renderRenameSequence`.

---

## 6. Sub-Slice-Schnitt

### Sub-Slice A — Template-Extraktion

- `MysqlSequenceEmulationTemplates` extrahiert aus
  `MysqlDdlGenerator` die helper_table-DDL, INSERT-Template,
  Support-Funktions-Templates und Sequence-Trigger-Templates.
- Existierende `MysqlDdlGenerator`-Tests bleiben gruen.
- Keine Verhaltensaenderung sonst.

### Sub-Slice B — Diff-Render-Pfade

- `MysqlDiffSequenceOps` mit `renderCreateSequence` /
  `renderAlterSequence` / `renderDropSequence` / `renderRenameSequence`
  (alle Up + Down; `RenameSequence` nur Defensive-Case).
  - Erstellt wird ein `MysqlSequenceMigrationContext`-Tracking für die
    einmalige Emission von `dmg_sequences`/`dmg_nextval`/`dmg_setval`.
- `MysqlDiffDdlGenerator.categorize()` routet die vier Subtypes auf
  neue/bestehende `OpCategory.SEQUENCE`; `RenameSequence` wird als
  Defense-Fallback ausgewiesen.
- Tests: pro Subtyp Up/Down-SQL-Pin.

### Sub-Slice C — `RenameSequence` Pfad

- `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` upgraded.
- `MysqlDiffSequenceOps.renderRenameSequence` als defensive Implementierung
  ergänzt.
- Tests: Mapper emittiert in diesem Slice primär den
  `DropCreateFallback`-Pfad (`DropSequence` + `CreateSequence`) mit
  `RenameProvenance`; `RenameSequence` ist nur Regressionstest und
  soll bei direkter Emission `UPDATE dmg_sequences` + Trigger-Rebuild
  liefern.

### Sub-Slice D — F.4 Sub-Slice D Integration

- `SequenceDefaultReprojector` wird fuer den `RenameProvenance`-Fall
  im `DropCreateFallback` erweitert: bei `DropSequence`/`CreateSequence`
  nach Sequence-Rename darf die Projektion die neuen Default-Verweise
  auf den umbenannten Sequenznamen neu verdrahten.
  Direkter `RenameSequence` bleibt Regressionstest + defensive Coverage.
- Tests prüfen den `DropCreateFallback`-Effekt bei
  `SequenceNextVal`-Defaults (z.B. `CreateTable`/`AlterColumnDefault`)
  und die `RenameProvenance`-gekapselten `DropSequence` + `CreateSequence`.

### Sub-Slice E — Closing

- §E.3-DoD im master plan Eintrag fuer MySQL ergänzen.
- CHANGELOG-Eintrag `### Added`.
- `spec/cli-spec.md` MySQL-Sequence-Renderbarkeit dokumentieren.
- Plan-Doc nach `done/`.

---

## 7. Akzeptanzkriterien

- [ ] `MysqlDiffSequenceOps` rendert `CreateSequence`,
      `AlterSequence` und `DropSequence` in beide Richtungen.
- [ ] `MysqlSequenceEmulationTemplates`/`MysqlDiffSequenceOps` emittieren
  `dmg_sequences` + `dmg_nextval`/`dmg_setval` exakt einmal pro
  Migrationslauf in einer kontrollierten Reihenfolge.
- [ ] Bestehende Diff-Tests, die MySQL-Sequenz-Operationen noch als
  `DIALECT_UNSUPPORTED_OPERATION` erwarten, werden auf den neuen
  `E056`/`MANUAL_ACTION_REQUIRED`-Pfad oder auf neue
  `SEQUENCE`-Renderer-Assertions umgestellt.
- [ ] `MysqlDiffDdlGenerator.categorize()` routet die vier
      Subtypes nicht mehr auf `UNSUPPORTED`; `RenameSequence`
      verbleibt als Defensive-Fallback/Regression-Case.
- [ ] `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` liefert
      `RenameSupport.DropCreateFallback` (emulierte Rename-Strategie).
- [ ] Bei `MysqlNamedSequenceMode != HELPER_TABLE` werden Sequence-Diff-Operationen
      weiterhin geblockt (`E056`), kein SQL wird emittiert.
- [ ] Datei-zu-Datei-Mode rendert keine Blocker gegen bestehende
      `dmg_*`-Objekte, sondern erzeugt direkt SQL nach Plan; nur
      `E056` ist weiterhin modusspezifisch und gilt als harte Sperre.
- [ ] Bestehende `MysqlDdlGenerator`-Tests bleiben gruen
      (Template-Extraktion ist nicht-destruktiv).
- [ ] F.4 Sub-Slice D `SequenceDefaultReprojector` mappt
      `RenameProvenance` auf `SequenceDefault`/`SequenceNextVal` korrekt
      auf `DropSequence` + `CreateSequence` im Fallback-Pfad.
- [ ] Pro Subtyp je ein Positiv-Test (Up + Down) und ein
      Blocker-Test fuer ein Carve-out (z.B. Laufzeit-`start` mit bereits
      vorhandener `dmg_sequences`-Zeile und nicht kompatiblen
      statischen Werten).
- [ ] Für Slice A selbst wird `preserveCurrentValue` nicht implementiert
      (kein Modellfeld dafür vorhanden); es bleibt im separaten
      `ImpPlan-0.9.7-sequence-preserve-current-value.md`.
- [ ] `CreateSequence`-Render erzeugt bei bestehender Zeile einen
      expliziten Drift-Check gegen `increment_by`, `min_value`, `max_value`,
      `cycle`, `cache` statt stillen `INSERT ... ON DUP KEY UPDATE`;
      bei inkonsistenten Werten wird Blocker gemeldet.
- [ ] `make docker-check` gruen.

---

## 8. Definition of Done (§13-Template)

- [ ] **Betroffener Modus**: alle Modi (file-to-file, file-to-DB,
      execute, rollback).
- [ ] **Mode-spezifische Validierung**:
      - execute/file-to-DB: `E056` zuerst, danach `E124`-Prüfungen gegen
        bestehende `dmg_*`-Objekte (bei vorhandenem DB-Kontext).
      - file-to-file: nur DDL-Emission; keine `dmg_*`-Kanonik auf
        vorhandene DB-Objekte, nur `E056` als Modusblocker.
- [ ] **Renderbare Operationen + Blocker**: CREATE/ALTER/DROP/RENAME
      Sequence rendern; `preserveCurrentValue`-Implementierung bleibt
      separat im Cross-Dialect-Plan.
      Hinweis: `RENAME SEQUENCE` ist im Standardpfad via
      `DropCreateFallback` (Down/Up für direkten `RenameSequence` nicht
      Teil des Produktivpfads).
- [ ] **Neue Diagnostics / Blocker / primaryBlockedReason**: keine
      neuen Codes. Das alte
      `"MySQL sequence rendering is out of E.3 scope today"`-Motiv
      verschwindet; neu gilt konsequent `E056` für nicht-`HELPER_TABLE`
      und `E124` für echte Objektkollisionen + nicht-kanonische
      Supportobjekte.
      Kollisionen gegen bestehende `dmg_sequences`/`dmg_nextval`/
      `dmg_setval` oder passende Triggernamen werden als expliziter
      `E124`-Blocker emittiert, nicht per `IF EXISTS` versteckt.
      `preserveCurrentValue` bleibt im Folge-Plan; dieser Slice rendert
      daher keine separaten `current_value`-Blocker.
- [ ] **Up- und Down-Verhalten**: getrennt gepinnt pro Subtyp.
- [ ] **Report-/Metadatenfelder**: bestehende
      `objectType = "SEQUENCE"`-Konvention; keine Aenderung.
- [ ] **Betroffene Dialekte**: nur MySQL.
- [ ] **F.0-Erfuellung**: irrelevant.
- [ ] **Positive und blockierende Testpfade**: siehe §7.
- [ ] **Rollback-Test oder Begruendung**: Standard-Down-Pfad fuer
      jeden Subtyp.
- [ ] **Datei-zu-Datei-Verhalten**: identisch zum DB-Pfad
      (`preserveCurrentValue` nicht relevant in Datei-zu-Datei),
      aber ohne Live-Kontrollen auf bestehende `dmg_*`-Objekte.
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

### 10.1 `dmg_sequences`-Helper-Table- und Support-Objekt-Kanonizität

Wenn das Live-Schema bereits ein Objekt `dmg_sequences` enthält, darf
`CreateSequence` nicht blind weiterlaufen. Mitigation:
`MysqlDiffSequenceOps` prüft vor dem Create/Insert die Existenz und
Kanonik der Support-Objekte: `dmg_sequences`, `dmg_nextval`,
`dmg_setval` sowie den zugehörigen Sequence-Trigger
`dmg_seq_<table>_<column>_<hash>_bi` inklusive Marker im Body.
Bei fachlich unpassendem Vorhandensein (falsche Signatur/Marker oder
abweichendes Objekt/Typ) wird ein `E124`-Blocker statt stiller
`IF NOT EXISTS`-Logik ausgegeben.
Zusätzlich gilt für sequenzspezifische Namens-Kollisionen:
bei bereits vorhandener Zeile in `dmg_sequences` wird zunächst ein Drift-Check
gegen verwaltete Felder (`increment_by`, `min_value`, `max_value`,
`cycle`, `cache`) durchgeführt; `start` (der persistierte
`next_value`-Zustand) ist kein harter Vergleichsanker, da es den
aktuellen Laufzeitstatus widerspiegelt.
Nur bei vollständiger Konsistenz wird ein Trigger-Reconcile gerendert,
ansonsten wird ebenfalls `E124` erzeugt.

### 10.2 `MysqlSequenceDdlSupport`-Guard im Diff-Pfad

`renderCreateSequence`/`renderAlterSequence`/`renderDropSequence`/`renderRenameSequence`
sind im Nicht-`HELPER_TABLE`-Modus strikt verboten:
`ctx.skip(op, ..., code = "E056", primaryBlockedReason = MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))`.
Es darf anschließend keine SQL-Emission mehr stattfinden. Erst danach kann SQL
für HELPER_TABLE gerendert werden.

Prioritaet im Diff-Pfad:
- `E056`-Blocker (falscher Sequenz-Modus) ist der harte First-Check im Renderer.
- `E124`-Blocker (nicht-kanonische/inkonsistente Support-Objekte oder Drift bei bestehender
  `dmg_sequences`-Zeile) wird erst geprüft, wenn der Modus-Guard erfolgreich war.

Datei-zu-Datei-Planer werden explizit separiert:
- In file-to-file wird keine Live-Objekt-Kanonik geprüft, da kein DB-Zustand vorliegt;
  es werden ausschließlich SQL-Statements gerendert.
- `E124` aus Support-Objekt- und Driftprüfung bleibt auf execute/file-to-db-Pfade beschraenkt,
  nicht auf file-to-file.
- `E056` wird weiterhin in allen Modi als harte Sperre bei
  `MysqlNamedSequenceMode != HELPER_TABLE` ausgegeben.

Damit ist die Quelle für die Diff-Pfad-Diagnose eindeutig und verhindert,
dass bei aktivem Blocker zusätzlich SQL ausgegeben werden kann.
Diese Guard-Schicht macht den Diff-Pfad explizit mode-korrekt und reduziert
die Abhängigkeit von impliziten Verhalten.

### 10.3 Trigger-Body-Stabilitaet

Der Sequence-Trigger-Body ist im DDL-Generator-Pfad gut getestet
(Phase A–E2). Im Diff-Pfad emittiert der Renderer den gleichen
Body — kein neuer Test-Korpus noetig, aber ein Cross-Pfad-Pin
(`schema generate` vs. `schema migrate` produzieren das gleiche
Trigger-DDL) ist sinnvoll.

### 10.4 RenameSequence semantisch ≠ ALTER TABLE RENAME

Der MySQL-Rename ist ein `UPDATE` auf einer Helper-Zeile, nicht
ein DDL-RENAME. MySQL kennt kein Trigger-Rename-Statement; der Weg
bleibt daher emuliert über `DROP TRIGGER` + `CREATE TRIGGER`.
Mitigation: Rename bleibt `DropCreateFallback` mit klarer
RenameProvenance-/Rollback-Dokumentation im Plan.

### 10.5 `MysqlSequenceDdlSupport`-Guard auf Moduskonfiguration

Wenn `mysqlNamedSequenceMode` nicht auf `HELPER_TABLE` steht,
muessen auch vorhandene `ALTER/DROP/RENAME SEQUENCE`-Diffops
als nicht-renderbar behandelt werden.
Der DDL-Pfad kann bereits `E056` liefern; der Diff-Pfad darf davon aber
nicht abhängig sein und muss weiterhin die explizite Guard-Kaskade aus
Abschnitt 10.2 umsetzen.


---

## 11. Erwartete Commit-Reihenfolge

| Sub-Slice | Commit-Subjekt-Skizze |
|---|---|
| A | `refactor(mysql): extract sequence-emulation templates from MysqlDdlGenerator` |
| B | `feat(mysql): diff-based CreateSequence / AlterSequence / DropSequence` |
| C | `feat(mysql): RenameSequence via UPDATE dmg_sequences + policy upgrade` |
| D | `test(mysql): SequenceDefaultReprojector pins for MySQL` |
| E | `docs(plan): MySQL Sequence Diff-Migration closing` |
