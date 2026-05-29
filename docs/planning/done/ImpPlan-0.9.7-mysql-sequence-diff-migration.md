# Implementierungsplan: 0.9.7 — MySQL Sequence Diff-Migration

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.3 Folge-Slice (MySQL diff-basierter Sequence-Renderer)
> **Status**: ✅ done (2026-05-20, reduzierter Scope — siehe
>           Scope-Hinweis unten). Sub-Slices A ✅ + B ✅ + C ✅ +
>           D ✅ + E (iter 1, `c7e92bf4` + `1431fb24`) + F ✅
>           (`3bea97e7`) + G (iter 2, `aba3392f`) + H ✅
>           (`d248cd11`) + I ✅ (closing iter 3).
> **Scope-Hinweis**: Der ursprüngliche §3.1-Scope schloss einen
>           Live-DB-Drift-Check gegen vorhandene `dmg_sequences`-
>           Zeilen + Support-Objekte ein. Dieser ist in einen
>           eigenen Folge-Plan ausgegliedert
>           (`docs/planning/done/ImpPlan-0.9.7-mysql-sequence-drift-check.md`,
>           erstellt unter Sub-Slice H). "Done" bezieht sich auf
>           den verbleibenden Scope: Bootstrap-Idempotenz statt
>           Live-Drift-Validation, Mode-Gate (`E056`) statt
>           Drift-Block (`E124`).
> **Vorbedingung**: E.3 Erstscheibe (PG-Sequence-Diff-Renderer) ✅;
>                  Vollständige MySQL-Sequence-Emulation
>                  (`docs/planning/done/mysql-sequence-emulation-plan.md`) ✅
>                  Phase A–E2 (2026-04-21) im DDL-Generator-Pfad;
>                  F.4 Renderer-Blocker-Bridge ✅ 2026-05-19.
> **Referenz**: `docs/planning/done/mysql-sequence-emulation-plan.md` (Vollvariante
>             im DDL-Generator-Pfad); `docs/planning/in-progress/diffresult-migration-plan-2.md`
>             §E.3; `spec/ddl-generation-rules.md` §7;
>             ADR-0003 (`docs/adr/0003-cross-dialect-sequencing.md`)
>             pinnt die Capability-Vertrags-Decisions retroaktiv,
>             unter denen die hier verdrahtete `W114`-Emission im
>             Diff-Pfad (Sub-Slice B.1 des Schirms) das Default
>             ohne Overlay ist.

---

## 1. Auslöser

Die vollständige MySQL-Sequence-Emulation
(`docs/planning/done/mysql-sequence-emulation-plan.md`, Phasen A–E2, abgeschlossen
2026-04-21) liefert den DDL-Generator-Pfad: `MysqlDdlGenerator`
rendert vollständige Sequence-DDL aus einer
`SequenceDefinition` (helper_table-Modus mit
`dmg_sequences`-Hilfsobjekten + kanonischen Sequence-Triggern); der
`MysqlSchemaReader` rekonstruiert die Hilfsobjekte zurück zu
`SequenceDefinition`. Die Emulation ist sequence-stabil.

Was fehlt: der **diff-basierte** Pfad. Heute routet
`MysqlDiffDdlGenerator.categorize()` jede der vier
Sequence-`DiffOperation`-Subtypen
(`CreateSequence`, `AlterSequence`, `DropSequence`, `RenameSequence`)
zu `OpCategory.UNSUPPORTED`. Der Renderer emittiert keinen DDL und
blockt mit `DIALECT_UNSUPPORTED_OPERATION`. Operatoren können
sequence-betreffende Diff-Migrationen damit nicht via `schema migrate`
fahren — sie müssten den DDL-Generator-Pfad (`schema generate`) nutzen
und das resultierende Skript manuell ausführen.

Im Detail:

- `CreateSequence`: heute Blocker. Soll: vollständige Emulation
  emittieren (helper_table + Trigger).
- `AlterSequence`: heute Blocker. Soll: deklarative Attributänderung
  emittieren (z.B. `UPDATE dmg_sequences SET increment_by = …`).
- `DropSequence`: heute Blocker. Soll: helper_table + Trigger droppen.
- `RenameSequence`: F.4 Sub-Slice C hat die Mapper-Policy
  bereits auf `RenameSupport.Blocked("OBJECT_RENAME_UNSUPPORTED")`
  gesetzt — die Renderer-Pfade landen in diesem Zustand nie hier.
  Sobald dieser Slice freischaltet, kann die Policy zu
  `RenameSupport.DropCreateFallback` hochgestuft werden, damit der
  Mapper den Sequence-Rename als emuliertes `DropSequence` + `CreateSequence`
  mit `RenameProvenance` rendert.

---

## 2. Warum jetzt?

E.3 (Sequence-Rendering im Diff-Pfad) hat heute nur den PG-Slice
erledigt; MySQL und SQLite sind im verbleibenden `E.3`-Rest weiterhin
als offen markiert. Die zugrundeliegende **MySQL-Emulation** ist
produktreif (Phase A–E2 abgeschlossen 0.9.4); fehlt nur die
Überführung in den Diff-Pfad. Das ist eine vergleichsweise kleine
Brücke für einen relativ grossen Funktionsgewinn — Operatoren
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
  blockiert (`ctx.skip(op, "...", code = "E056"); ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))`).
  In `file-to-file` gibt es keine DB-Live-Objekt-Validierung, aber die gleiche
  harte Modusblockade (`E056`) bleibt bestehen. Es darf dann keinerlei SQL
  emittiert werden. Die eigentliche Guard-Logik wird im
  `MysqlDiffSequenceOps`-Renderer verankert, nicht erst indirekt
  im `MysqlDdlGenerator`.
- Render-Funktionen pro Subtyp:
  - `renderCreateSequence(op, ctx)` — produziert die SQL-Statements
    für die einzelne Sequence (`INSERT` nur bei fehlender
    `dmg_sequences`-Zeile, sonst Drift-Check + Trigger-Reconcile) aus der
    bestehenden `MysqlSequenceDdlSupport`-Emulation.
    Alle globalen
    Bootstrap-Objekte (`dmg_sequences`, `dmg_nextval`, `dmg_setval`) werden
    über einen zentralen Diff-Header (einmal pro Migration) erzeugt.
    Vorher wird die Support-Kanonik geprüft: `dmg_sequences`-Tabellenschema,
    `dmg_nextval`/`dmg_setval`-Signaturen, `dmg_sequences`-Row-Metadaten
    (`managed_by`, `format_version`, `next_value`-Spalte), plus verifizierte
    Trigger-Metadaten aus der Sequenz-Rekonstruktion (`MysqlSchemaReader`/
    `MysqlSequenceSupport`, Marker im Trigger-Body und `MysqlSequenceNaming`).
    Ein Trigger-Muster darf nur aus explizit verifizierten Sequenz-Metadaten
    abgeleitet werden;
    ohne harte Trigger-Zuordnung wird mit `E124` geblockt.
    Bei `dmg_*`-Objekten mit fachlich unpassender Form (`E124`) wird die
    Render-Pipeline abgebrochen. Danach gilt:
    Wenn die `dmg_sequences`-Zeile bereits existiert, erfolgt zuerst ein
    Drift-Check gegen die fachlichen Felder `increment`, `minValue`,
    `maxValue`, `cycle`, `cache` (in `dmg_sequences` als
    `increment_by`, `min_value`, `max_value`, `cycle_enabled`,
    `cache_size`) sowie `managed_by`/`format_version`:
    `start` ist in der Emulation der persistierte Laufzeitzustand
    (`next_value`) der Sequence und darf nicht als harte
    Konsistenzprüfung verwendet werden; `next_value` selbst bleibt
    Laufzeit-Read-only.
    Bei Konsistenz wird ein Trigger-Reconcile (`DROP TRIGGER IF EXISTS` +
    `CREATE TRIGGER`) ausgeführt, bei Abweichung wird `E124` geblockt; bei
    fehlender Zeile wird normaler `INSERT` gerendert. Up + Down.
  - `renderAlterSequence(op, ctx)` — produziert
    `UPDATE dmg_sequences SET …`-Statements für managed
    Felder (`increment`, `minValue`, `maxValue`,
    `cycle`, `cache`; in `dmg_sequences` als `increment_by`,
    `min_value`, `max_value`, `cycle_enabled`, `cache_size`).
    `start` ist in diesem Slice der persistierte
    Laufzeitzustand (`next_value`) und wird nicht als DDL-Attribut
    verändert. Up + Down (inverse Werte aus `op.before`) auf den
    verwalteten Deklarativfeldern (`increment`, `minValue`,
    `maxValue`, `cycle`, `cache`) ausschliesslich.
  - `renderDropSequence(op, ctx)` — droppt die durch Support-Rekonstruktion
    zugeordneten Trigger + Zeile in `dmg_sequences`. Up + Down.
  - `renderRenameSequence(op, ctx)` — `RENAME TABLE`-Pattern
    funktioniert nicht (Sequence ist eine Zeile in einem Helper-Table,
    und der Support-Pfad ist auf `HELPER_TABLE` begrenzt).
    Wenn ein echter `RenameSequence`-Op emittiert wird, ist
    `UPDATE dmg_sequences SET name = 'to' WHERE name = 'from'` + Trigger-Rebuild
    (`DROP TRIGGER` +
    `CREATE TRIGGER`) erforderlich, da MySQL kein generisches
    Trigger-Rename kennt. Die betroffenen Trigger werden aus
    rekonstruierten Support-Metadaten aufgelöst.
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
- `make docker-check` soll grün über MySQL-Driver + hexagon:core laufen.
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
  (`docs/planning/done/sqlite-sequence-emulation-plan.md`). Dieser Slice ist
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
| `MysqlNamedSequenceMode.HELPER_TABLE`-Emulation | ✅ 0.9.4 AP 6.3/6.4 | Plan-Doc: `docs/planning/done/mysql-sequence-emulation-plan.md` |
| `MysqlSchemaReader` faltet Hilfsobjekte zurück | ✅ 0.9.4 AP 6.1–6.3 | `dmg_sequences` + Support-Routinen + Trigger werden erkannt |
| F.4 RenameSequence Subtyp + Policy | ✅ 2026-05-19 | `MysqlObjectRenamePolicy` blockt heute, wartet auf Upgrade |
| F.4 Renderer-Blocker-Bridge | ✅ 2026-05-19 | `PlannerBlockerClassifier`-Pattern verfuegbar |
| MySQL Server-Version-Detection | ✅ E.1 C.2 | Vendor-String + Version für Capability-Gates |

---

## 5. Architektur

### 5.1 Re-Use vs. Duplikation

Die Emulation-Logik (helper_table-DDL-Templates, Trigger-Body) lebt
heute in `MysqlDdlGenerator`. Sub-Slice A muss diese Templates
extrahieren in eine wiederverwendbare Helper-Klasse
(`MysqlSequenceEmulationTemplates` oder analog), damit
`MysqlDiffSequenceOps` sie konsumieren kann ohne den ganzen
DDL-Generator zu instanziieren. Die Helper-Klasse liefert zusätzlich einen
expliziten "bootstrap once"-Ausgabe-Mechanismus für `dmg_sequences` und
`dmg_nextval`/`dmg_setval`, der im Diff-Generator nur ein einziges Mal
emittiert wird.

Decision in Sub-Slice A: extrahieren vs. inline-Copy. Empfehlung:
extrahieren, weil sonst zwei Wartungs-Stellen.

### 5.2 Op-Subtyp-Mapping

| DiffOperation | MySQL-Rendering |
|---|---|
| `CreateSequence` | (nur `HELPER_TABLE`) **Globaler Bootstrap einmalig pro Migration**: `dmg_sequences`-Tabelle + `dmg_nextval`/`dmg_setval` (nur einmal), danach pro Sequence `INSERT INTO dmg_sequences (name, …) VALUES (…)`. Vorher wird die Support-Kanonik geprüft (`dmg_sequences`-Schema, `dmg_nextval`/`dmg_setval`-Signaturen, `dmg_sequences`-Metadaten `managed_by`, `format_version`, `next_value`-Spalte und verifizierte Trigger-Zuordnung aus der Support-Rekonstruktion): bei Abweichung `E124`-Blocker. Bei bestehender Zeile wird zuerst der Drift gegen verwaltete Felder geprüft (`increment`, `minValue`, `maxValue`, `cycle`, `cache`; plus `managed_by`, `format_version`; `next_value` wird nur als Laufzeitstatus gelesen): bei Abweichung `E124`-Blocker, bei Konsistenz `DROP TRIGGER IF EXISTS` + `CREATE TRIGGER` (idempotentes Reconcile). |
| `AlterSequence(before, after)` | `UPDATE dmg_sequences SET <changed-fields> WHERE name = …` für verwaltete Felder (`increment`, `minValue`, `maxValue`, `cycle`, `cache`; in der Tabelle `increment_by`, `min_value`, `max_value`, `cycle_enabled`, `cache_size`). |
| `DropSequence` | (nur `HELPER_TABLE`) `DROP TRIGGER IF EXISTS` für alle per Support-Rekonstruktion zugeordneten Trigger; `DELETE FROM dmg_sequences WHERE name = …` |
| `RenameSequence(from, to)` | Defensive-Fallback nur: `UPDATE dmg_sequences SET name = 'to' WHERE name = 'from'`; Trigger-Rebuild via `DROP TRIGGER IF EXISTS` + `CREATE TRIGGER` für den durch Support-Rekonstruktion zugeordneten Sequence-Trigger |

Die `dmg_sequences`-Helper-Table wird beim ersten `CreateSequence`
im Plan nach erfolgreicher Kollisionsprüfung angelegt; spaetere
`CreateSequence`-Ops nutzen sie wieder. Im `DropSequence`-Pfad
wird die Tabelle NICHT geloescht (andere Sequenzen leben darin)
— bleibt als idempotente Infrastruktur-Tabelle.
Hinweis: Trigger-Reconcile nutzt nur per Support-Rekonstruktion verifizierte
Trigger-Namen; fehlt diese sichere Zuordnung, darf kein blindes
`DROP TRIGGER`/`CREATE TRIGGER` erfolgen, sondern es wird `E124` berichtet.

### 5.3 Down-Direction

Standard-Pattern wie bei den anderen Sequence-Renderern:
- `CreateSequence` Down = `DropSequence`-Sequenz.
- `AlterSequence` Down = `UPDATE` auf `op.before`-Werte für die verwalteten
  Deklarativfelder (`increment`, `minValue`, `maxValue`, `cycle`, `cache`;
  in der Tabelle `increment_by`, `min_value`, `max_value`, `cycle_enabled`,
  `cache_size`).
- `DropSequence` Down = `CreateSequence`-Sequenz mit gespeicherter
  `SequenceDefinition`.
- `RenameSequence` ist primär kein Produktivpfad. `Up/Down` werden
  im regulären Diff-Slice nicht generiert; der produktive Fallback
  läuft über `DropSequence` + `CreateSequence` mit `RenameProvenance`.
  Direkter `RenameSequence`-Down/Up bleibt nur defensive Regression-Coverage
  (`UPDATE` + Trigger-Rebuild).

### 5.4 RenameSequence-Policy aktualisieren

`MysqlObjectRenamePolicy.classify(...)` heute:

```kotlin
DiffObjectType.SEQUENCE -> RenameSupport.Blocked(
    code = "OBJECT_RENAME_UNSUPPORTED",
    message = "MySQL sequence rendering is out of E.3 scope today; …",
)
```

Sub-Slice C wird hochgestuft zu:

```kotlin
DiffObjectType.SEQUENCE -> RenameSupport.DropCreateFallback(
    rationale = "MySQL emuliert Sequenz-Rename über UPDATE auf dmg_sequences und Trigger-Rebuild.",
)
```

Begründung: In F.4 ist der primäre Sequenz-Rename-Pfad
`DropCreateFallback`, also `DropSequence` + `CreateSequence` mit
`RenameProvenance`. Produktiv darf in diesem Slice kein direkter
`RenameSequence`-Renderer-Output entstehen; dieser Pfad muss in den
Diff-Regeln über die Fallback-Policy abgedeckt sein. Der direkte
`RenameSequence`-Subtyp bleibt nur als Regression/Defensive-Coverage
erlaubt; wenn er trotzdem emittiert wird, übernimmt ihn
`MysqlDiffSequenceOps.renderRenameSequence`.

---

## 6. Sub-Slice-Schnittstellen

### Sub-Slice A — Template-Extraktion ✅ (2026-05-20, `edc1fb9d` + Review `4336284d`)

  - `MysqlSequenceEmulationTemplates` (oder besser Wiederverwendung von
  `MysqlSequenceDdlSupport`) aus `MysqlDdlGenerator` die
  helper_table-DDL, INSERT-Template, Support-Funktions-Templates und
  Sequence-Trigger-Templates.
- Existierende `MysqlDdlGenerator`-Tests bleiben grün.
- Keine Verhaltensänderung sonst.

### Sub-Slice B — Diff-Render-Pfade ✅ (2026-05-20, `28598cde` + Review `7c2b8bec`)

- `MysqlDiffSequenceOps` mit `renderCreateSequence` /
  `renderAlterSequence` / `renderDropSequence` / `renderRenameSequence`
  (alle Up + Down; `RenameSequence` nur Defensive-Case).
  - Erstellt wird ein `MysqlSequenceMigrationContext`-Tracking für die
    einmalige Emission von `dmg_sequences`/`dmg_nextval`/`dmg_setval`.
- `MysqlDiffDdlGenerator.categorize()` routet die vier Subtypes auf
  neue/bestehende `OpCategory.SEQUENCE`; `RenameSequence` wird als
  Defense-Fallback ausgewiesen.
- Tests: pro Subtyp Up/Down-SQL-Pin.

### Sub-Slice C — `RenameSequence` Pfad ✅ (2026-05-20, `d3724a33` + Review `93aa3e40`)

- `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` wird aktualisiert.
- `MysqlDiffSequenceOps.renderRenameSequence` als defensive Implementierung
  ergänzt.
- Tests: Mapper emittiert in diesem Slice primär den
  `DropCreateFallback`-Pfad (`DropSequence` + `CreateSequence`) mit
  `RenameProvenance`; `RenameSequence` ist nur Regressionstest und
  soll bei direkter Emission `UPDATE dmg_sequences` + Trigger-Rebuild
  liefern.

### Sub-Slice D — F.4 Sub-Slice D Integration ✅ (2026-05-20, `0bda4f15`)

- `SequenceDefaultReprojector` wird für den `RenameProvenance`-Fall
  im `DropCreateFallback` erweitert: bei `DropSequence`/`CreateSequence`
  nach Sequence-Rename darf die Projektion die neuen Default-Verweise
  auf den umbenannten Sequenznamen neu verdrahten.
  Direkter `RenameSequence` bleibt Regressionstest + defensive Coverage.
- Tests prüfen den `DropCreateFallback`-Effekt bei
  `SequenceNextVal`-Defaults (z.B. `CreateTable`/`AlterColumnDefault`)
  und die `RenameProvenance`-gekapselten `DropSequence` + `CreateSequence`.

### Sub-Slice E — Closing (Iteration 1, `c7e92bf4` + `1431fb24`, re-opened)

- §E.3-DoD im master plan Eintrag für MySQL ergänzen.
- CHANGELOG-Eintrag `### Added`.
- `spec/cli-spec.md` MySQL-Sequence-Renderbarkeit dokumentieren.
- Plan-Doc nach `done/`.

Anmerkung: Die Closing-Review (post-`1431fb24`) deckte vier echte
Funktionslücken auf, die Sub-Slice F adressiert. Die Doku aus
Sub-Slice E (CHANGELOG, spec, Roadmap) bleibt im wesentlichen
korrekt — die spec beschreibt den Endzustand nach Sub-Slice F;
das CHANGELOG wird in der zweiten Closing-Iteration um Sub-Slice F
erweitert.

### Sub-Slice F — Closing-Follow-up ✅ (2026-05-20, `3bea97e7`)

Adressiert die vier Findings der Closing-Review:

1. **Bootstrap-Idempotenz** (high): `emitBootstrapIfNeeded`
   emittiert heute `CREATE TABLE dmg_sequences` + `CREATE FUNCTION
   dmg_nextval` / `dmg_setval` ohne Existenz-Check. Eine Migration
   gegen eine DB, die schon helper_table-Sequenzen nutzt, läuft
   gegen "table already exists". Fix:
   `MysqlSequenceEmulationTemplates.supportTableSql` nutzt
   `CREATE TABLE IF NOT EXISTS`; `nextval`/`setval`-Routinen
   nutzen `DROP FUNCTION IF EXISTS … ;` vor `CREATE FUNCTION …`.
   Auch die DDL-Generator-Pipeline profitiert (idempotent gegen
   wiederholte `schema generate`-Läufe).
2. **Column-Default-Renderer für `SequenceNextVal`** (high): Der
   Diff-Pfad routet `CreateTable` / `AddColumn` / `AlterColumnDefault`
   durch `MysqlDiffSqlBuilders.columnLine` → `MysqlTypeMapper.toDefaultSql`,
   und letzteres wirft `error("SequenceNextVal requires
   helper_table mode (not yet implemented in 6.3)")` für jede
   Spalte mit `DefaultValue.SequenceNextVal`. Damit ist Sub-Slice
   D's Reprojector zwar korrekt, aber der gerenderte Plan crasht
   beim ersten Tabellenop mit Sequence-Default. Fix:
   `MysqlDiffSqlBuilders.columnLine` erkennt SequenceNextVal-
   Defaults separat (analog `AbstractDdlGenerator.columnSql` →
   `resolveSequenceDefault`-Bypass) und emittiert KEINE `DEFAULT`-
   Klausel; pro betroffener Spalte wird der Support-Trigger
   (BEFORE INSERT) lazy direkt nach dem Tabellen-/Spalten-Op
   emittiert. AlterColumnDefault muss Direction-aware drop +
   create für Trigger handhaben.
3. **start-only `AlterSequence`-Operation wird still verworfen**
   (medium): `renderAlterSequence` ist nach dem Sub-Slice-B-
   Review-Fix so geschrieben, dass es bei leerem managed-fields-
   Delta einfach `return`'t ohne `emit`/`skip`/`addBlocker`. Eine
   Sequenz-Diff, deren einzige Änderung `start` ist (Laufzeitstatus,
   deferred zu `preserveCurrentValue`), wird im Report weder als
   gerendert noch als skipped markiert — die Op verschwindet
   einfach. Fix: explizit `ctx.skip` mit Info-Diagnostic
   `MYSQL_SEQUENCE_RUNTIME_STATE_NO_OP` + Hinweis auf den Folge-
   Slice, kein Blocker (es ist keine Funktionalitätslücke des
   Operators).
4. **§10.1 Plan-Doc-Konsistenz** (low): §10.1 beschreibt den
   Drift-/Kanonik-Check als implementierte Mitigation, während §7
   und §8 ihn explizit als deferred markieren. Fix: §10.1 auf
   "geplant für Folge-Slice" umformulieren, mit Verweis auf den
   eigenen Plan-Doc-Stub.

Tests:
- Bootstrap-IF-NOT-EXISTS / DROP+CREATE-Pattern im
  `MysqlSequenceEmulationTemplatesTest` (snapshot-Update).
- start-only `AlterSequence` → skip + Info-Diagnostic
  (`MysqlDiffSequenceOpsTest`).
- `CreateTable` mit SequenceNextVal-Default rendert sauber: keine
  DEFAULT-Klausel + nachfolgender Trigger
  (`MysqlDiffSequenceOpsTest` neuer Case oder eigene Test-Datei).
- `AddColumn` analog.
- `AlterColumnDefault` (4 Fälle: SeqNextVal → SeqNextVal-other,
  SeqNextVal → konstanter Wert, konstanter Wert → SeqNextVal,
  Direction-Swap im DOWN).
- Mode-Gate für Column-Default-Pfad: bei !HELPER_TABLE → E056
  analog zum Sequence-Op-Pfad.

### Sub-Slice G — Closing-Iteration 2 (`aba3392f`, re-opened)

- §E.3-DoD im master plan + Roadmap auf wirklich done setzen.
- CHANGELOG-Eintrag erweitern um Sub-Slice F.
- `spec/cli-spec.md` Column-Default-Trigger-Pfad ergänzen.
- Plan-Doc zurück nach `done/`.

Anmerkung: Die Closing-iter-2-Review (post-`aba3392f`) deckte
zwei zusätzliche Findings auf — siehe Sub-Slice H.

### Sub-Slice H — DELIMITER + Scope-Korrektur ✅ (2026-05-20, `d248cd11`)

Adressiert die zwei Findings der Closing-iter-2-Review:

1. **DELIMITER `//` im Diff-Pfad bricht `schema migrate --execute`**
   (high): `MysqlSequenceEmulationTemplates.nextvalRoutineSql` /
   `setvalRoutineSql` / `sequenceTriggerSql` umschließen ihren
   Body mit `DELIMITER //\n…END //\nDELIMITER ;`. Sowohl der
   DDL-Generator-Pfad (`MysqlSequenceDdlSupport.generateSupport*`)
   als auch der Diff-Pfad (`MysqlDiffSequenceOps.emitBootstrap*` /
   `emitSupportTriggerForColumn`) übernehmen diesen String 1:1.
   `DELIMITER` ist aber kein Server-SQL, sondern eine
   MySQL-CLI-Client-Direktive — JDBC wirft `SQLException` beim
   Submit. Das widerspricht dem etablierten
   `MysqlDiffRoutineOps`-Vertrag (Routine-Diff-Artefakte sind
   delimiterfrei). Folge: `schema migrate --execute` schlägt fehl
   sobald irgendein Sequence-Bootstrap oder Support-Trigger
   emittiert wird. Render-Tests bleiben grün, weil sie nur die
   String-Form prüfen.

   Fix:
   - Templates produzieren delimiterfreien Body (`END` statt
     `END //…DELIMITER ;`); die DELIMITER-Wrappung wandert in
     einen neuen Helper `wrapWithDelimiter(body)` an
     `MysqlSequenceEmulationTemplates`.
   - DDL-Generator-Pfad (`generateSupportFunctions` /
     `generateSupportTriggers` in `MysqlSequenceDdlSupport`)
     ruft `wrapWithDelimiter` explizit, damit die `--target
     mysql --output file.sql`-Artefakte für die mysql-CLI
     ausführbar bleiben (Fixtures `full-featured.mysql.helper-
     table.*.sql` bleiben mit DELIMITER drin).
   - Diff-Pfad (`emitBootstrapIfNeeded` /
     `emitSupportTriggerForColumn`) nutzt die delimiterfreien
     Templates direkt; JDBC submittiert das BEGIN…END-Body
     als einzelnes Statement.
   - Templates-Snapshot-Test + Diff-Tests entsprechend
     aktualisiert (DELIMITER-Substrings nicht mehr im Diff-
     Output erwartet).

2. **Scope-Reduktion explizit machen** (medium): §3.1 listete
   einen Live-DB-Drift-Check gegen vorhandene `dmg_sequences`-
   Zeilen, `dmg_*`-Support-Objekte und Trigger-Body-Marker
   ein. §10.1 wurde in Sub-Slice F auf "Bootstrap-Idempotenz
   + Folge-Slice" gesetzt, aber der Plan-Doc-Status sagt seit
   Sub-Slice G "✅ done". Beide Aussagen sind nur dann
   konsistent, wenn der Scope nachträglich reduziert wurde —
   das wird hier explizit gemacht:
   - Neuer Folge-Plan-Stub
     `docs/planning/done/ImpPlan-0.9.7-mysql-sequence-drift-check.md`
     mit Scope (E124-Probe-Adapter, Drift-Check pro Feld,
     Trigger-Body-Marker-Validation), §10.1 verlinkt darauf.
   - Status-Header zeigt explizit, dass "done" sich auf den
     reduzierten Scope bezieht.
   - §11 Commit-Tabelle ergänzt um Sub-Slice H + erneutes
     Closing.

### Sub-Slice I — Closing-Iteration 3 ✅ (2026-05-20)

- Plan-Doc-Status auf done (reduzierter Scope) setzen.
- CHANGELOG erweitern um Sub-Slice H.
- `spec/cli-spec.md` MySQL-Sequence-Diff-Block: DELIMITER-
  Korrektur dokumentieren.
- Plan-Doc zurück nach `done/`.

---

## 7. Akzeptanzkriterien

Stand 2026-05-20: alle Boxes ausser Drift-Check abgehakt. Drift-Check
ist explizit auf eine Folge-Slice verschoben (siehe §9 + Sub-Slice E).

- [x] `MysqlDiffSequenceOps` rendert `CreateSequence`,
      `AlterSequence` und `DropSequence` in beide Richtungen.
      `RenameSequence` ist nur als defensive Regression abgedeckt.
      *(Sub-Slice B, `MysqlDiffSequenceOpsTest`.)*
- [x] `MysqlSequenceEmulationTemplates` / `MysqlDiffSequenceOps` emittieren
  `dmg_sequences` + `dmg_nextval`/`dmg_setval` exakt einmal pro
  Migrationslauf in einer kontrollierten Reihenfolge.
  *(Sub-Slice B, `MysqlSequenceMigrationContext` + Test "Two
  CreateSequence ops in one migration emit the bootstrap exactly
  once".)*
- [x] Bestehende Diff-Tests, die MySQL-Sequenz-Operationen noch als
  `DIALECT_UNSUPPORTED_OPERATION` erwarten, werden auf den neuen
  `E056`/`MANUAL_ACTION_REQUIRED`-Pfad oder auf neue
  `SEQUENCE`-Renderer-Assertions umgestellt.
  *(Sub-Slice B, `MysqlDiffDdlGeneratorTest` "Sequence ops without
  helper_table mode block with E056".)*
- [x] `MysqlDiffDdlGenerator.categorize()` routet die vier
      Subtypes nicht mehr auf `UNSUPPORTED`; `RenameSequence`
      verbleibt als Defensive-Fallback/Regression-Case.
      *(Sub-Slice B, neue `OpCategory.SEQUENCE`.)*
- [x] `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` liefert
      `RenameSupport.DropCreateFallback` (emulierte Rename-Strategie).
      Damit wird `RenameSequence` nicht mehr produktiv gerendert.
      *(Sub-Slice C, `ObjectRenamePolicyTest` + `RenameObjectMapperTest`
      MySQL-Case.)*
- [x] Bei `MysqlNamedSequenceMode != HELPER_TABLE` werden Sequence-Diff-Operationen
      weiterhin geblockt (`E056`), kein SQL wird emittiert.
      *(Sub-Slice B, `ensureHelperMode`-Gate.)*
- [x] Datei-zu-Datei-Mode rendert keine DB-live Blocker gegen bestehende
      `dmg_*`-Objekte, sondern erzeugt direkt SQL nach Plan; nur
      `E056` ist weiterhin modusspezifisch und gilt als harte Sperre.
      *(Sub-Slice B: keine Drift-Checks im Renderer; modusspezifisch
      bleibt nur das `ensureHelperMode`-Gate.)*
- [x] Bestehende `MysqlDdlGenerator`-Tests bleiben grün
      (Template-Extraktion ist nicht-destruktiv).
      *(Sub-Slice A, byte-identische Templates verifiziert über
      `MysqlDdlGeneratorTestPart3` / `MysqlDdlGeneratorSequenceTest`
      shouldContain-Pins.)*
- [x] F.4 Sub-Slice D `SequenceDefaultReprojector` mappt
      `RenameProvenance` auf `SequenceDefault`/`SequenceNextVal` korrekt
      auf `DropSequence` + `CreateSequence` im Fallback-Pfad.
      *(Sub-Slice D, drei neue MySQL-Tests in
      `SequenceDefaultReprojectorTest`.)*
- [x] Pro Subtyp je ein Positiv-Test (Up + Down) und ein
      Blocker-Test für ein Carve-out.
      *(Sub-Slice B: Up/Down pro Subtyp + Mode-Gate-Blocker. Drift-Check
      als Folge-Slice; bewusst deferred.)*
- [x] Für Slice A selbst wird `preserveCurrentValue` nicht implementiert
      (kein Modellfeld dafür vorhanden); es bleibt im separaten
      `ImpPlan-0.9.7-sequence-preserve-current-value.md`.
      *(Sub-Slice B: `updateRowSql` setzt nur die verwalteten Felder;
      `start`/`next_value` bleiben unangetastet.)*
- [ ] `CreateSequence`-Render erzeugt bei bestehender Zeile einen
      expliziten Drift-Check gegen `increment`, `minValue`, `maxValue`,
      `cycle`, `cache` statt stillen `INSERT ... ON DUP KEY UPDATE`;
      bei inkonsistenten Werten wird Blocker gemeldet.
      *(Bewusst deferred — analog F.5 E.3 braucht das einen Live-DB-Probe-
      Adapter; eigener Folge-Slice. Siehe §9.)*
- [x] `make docker-check` grün.
      *(Stand 2026-05-20 nach Sub-Slices A–D.)*

---

## 8. Definition of Done (§13-Template)

Stand 2026-05-20: alle Boxes ausser dem E124-Drift-Check abgehakt. Der
Drift-Check braucht einen Live-DB-Probe-Adapter (analog F.5 E.3's
`CheckPreflightProbe`) und ist auf eine Folge-Slice verschoben.

- [x] **Betroffener Modus**: alle Modi (file-to-file, file-to-DB,
      execute, rollback). `MysqlNamedSequenceMode != HELPER_TABLE`
      blockt vor jeder anderen Renderlogik via `E056`.
- [ ] **Mode-spezifische Validierung**:
      - execute/file-to-DB: `E056` zuerst, danach `E124`-Prüfungen gegen
        bestehende `dmg_*`-Objekte (bei vorhandenem DB-Kontext).
        *(E124-Pfad deferred — siehe §9 "Drift-Check + Support-Kanonik".)*
      - file-to-file: nur DDL-Emission; keine DB-live `dmg_*`-Kanonik auf
        vorhandene Objekte, nur `E056` als Modusblocker. *(Sub-Slice B.)*
- [x] **Renderbare Operationen + Blocker**: CREATE/ALTER/DROP/RENAME
      Sequence rendern (Sub-Slice B + C); `preserveCurrentValue`-Implementierung
      bleibt separat im Cross-Dialect-Plan.
      `RENAME SEQUENCE` läuft im Standardpfad via
      `DropCreateFallback` (Sub-Slice C); Direct-RenameSequence ist
      defensive Regression-Coverage.
- [x] **Neue Diagnostics / Blocker / Blocker-Reason**: keine
      neuen Codes außer der Wiederverwendung von `E056` für nicht-
      `HELPER_TABLE`. Das alte
      `"MySQL sequence rendering is out of E.3 scope today"`-Motiv
      verschwindet (Sub-Slice C). E124-Drift-Checks gegen bestehende
      `dmg_sequences`/`dmg_nextval`/`dmg_setval` oder passende
      Triggernamen sind nicht in diesem Slice (siehe §9).
      `preserveCurrentValue` bleibt im Folge-Plan.
- [x] **Up- und Down-Verhalten**: getrennt gepinnt pro Subtyp.
      *(`MysqlDiffSequenceOpsTest`.)*
- [x] **Report-/Metadatenfelder**: bestehende
      `objectType = "SEQUENCE"`-Konvention; keine Änderung.
- [x] **Betroffene Dialekte**: nur MySQL.
- [x] **F.0-Erfuellung**: irrelevant (Sub-Slice C aktiviert den
      Rename-Overlay-Pfad nur für MySQL — Overlay-Vertrag selbst bleibt
      unverändert).
- [x] **Positive und blockierende Testpfade**: siehe §7.
- [x] **Rollback-Test oder Begründung**: Standard-Down-Pfad für
      jeden Subtyp (`MysqlDiffSequenceOpsTest`).
- [x] **Datei-zu-Datei-Verhalten**: reine Plan-basierte DDL-Emission
      ohne Live-Kontrollen auf bestehende `dmg_*`-Objekte
      (`preserveCurrentValue` nicht relevant in Datei-zu-Datei).
- [x] **Bestehende 0.9.7-Vertraege unveraendert**: `MysqlDdlGenerator`-
      Pfade unverändert (Templates byte-identisch nach
      Sub-Slice A); F.4 RenameSequence-Mapper-Pfad wird aktiviert
      (Sub-Slice C).
- [x] **Slice kann unabhängig implementiert und verifiziert
      werden**: ja, Sub-Slices A → B → C → D sequentiell, E
      Closing. *(A–E sequentiell abgeschlossen.)*

---

## 9. Out-of-Scope / Folge-Themen

- **`preserveCurrentValue`-Policy**: eigener Cross-Dialect-Plan
  (`ImpPlan-0.9.7-sequence-preserve-current-value.md`).
- **SQLite-Sequence-Diff**: eigener Plan
  (`docs/planning/done/sqlite-sequence-emulation-plan.md`).
- **Cross-Dialect-Sequence-Transfer**: Architektur-Plan
  (`ImpPlan-0.9.7-cross-dialect-sequencing.md`).
- **MariaDB-native Sequences** (10.3+): koennte über einen
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
`CreateSequence` nicht blind weiterlaufen. Mitigation in zwei
Schichten:

**Heute (Sub-Slice F)**: Bootstrap-Idempotenz. Die Templates emittieren
`CREATE TABLE IF NOT EXISTS dmg_sequences (...)` und
`DROP FUNCTION IF EXISTS dmg_nextval; CREATE FUNCTION dmg_nextval(...)`
(analog für `dmg_setval`). Damit bricht eine Migration gegen eine DB
mit existierenden Support-Objekten nicht beim Bootstrap-Schritt; bei
abweichender Spaltensignatur in `dmg_sequences` schlägt allerdings
der spätere `INSERT` fehl — das ist eine bewusste Schwelle, die der
zweite Schicht-Slice anhebt.

**Folge-Slice (Drift-Check, geplant)**: Live-DB-Probe gegen `dmg_sequences`-
Spaltensignatur, `dmg_nextval`/`dmg_setval`-Body-Marker und
`dmg_seq_<table16>_<column16>_<hash10>_bi`-Trigger-Body-Marker (analog
F.5 E.3's `CheckPreflightProbe`-Adapter). Bei abweichender Kanonik
(falsche Signatur, fehlender Marker, anderes Objekt/Typ) erzeugt
der Probe-Gate einen `E124`-Blocker statt stiller `IF NOT EXISTS`-
Toleranz. Zusätzlich: bei bereits vorhandener `dmg_sequences`-Zeile
ein Drift-Check gegen die fachlichen Felder (`increment`, `minValue`,
`maxValue`, `cycle`, `cache`); `start` (`next_value`-Zustand) ist
kein harter Vergleichsanker. Bei voller Konsistenz Trigger-Reconcile;
sonst `E124`.

Plan-Doc-Stub:
`docs/planning/done/ImpPlan-0.9.7-mysql-sequence-drift-check.md`
(Sub-Slice H, 2026-05-20). Sub-Slice H des aktuellen Plans
dokumentiert den Scope-Cut explizit; §7 + §8 markieren die
zugehörigen Akzeptanzkriterien als deferred.

### 10.2 `MysqlSequenceDdlSupport`-Guard im Diff-Pfad

`renderCreateSequence`/`renderAlterSequence`/`renderDropSequence`/`renderRenameSequence`
sind im Nicht-`HELPER_TABLE`-Modus strikt verboten:
`ctx.skip(op, ..., code = "E056"); ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))`.
Es darf anschließend keine SQL-Emission mehr stattfinden. Erst danach kann SQL
für HELPER_TABLE gerendert werden.

Prioritaet im Diff-Pfad:
- `E056`-Blocker (falscher Sequenz-Modus) ist der harte First-Check im Renderer.
- `E124`-Blocker (nicht-kanonische/inkonsistente Support-Objekte oder Drift bei bestehender
  `dmg_sequences`-Zeile) wird erst geprüft, wenn der Modus-Guard erfolgreich war.

Datei-zu-Datei-Pfade werden explizit separiert:
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
müssen auch vorhandene `ALTER/DROP/RENAME SEQUENCE`-Diffops
als nicht-renderbar behandelt werden.
Der DDL-Pfad kann bereits `E056` liefern; der Diff-Pfad darf davon aber
nicht abhängig sein und muss weiterhin die explizite Guard-Kaskade aus
Abschnitt 10.2 umsetzen.


---

## 11. Erwartete Commit-Reihenfolge

| Sub-Slice | Status | Commit(s) |
|---|---|---|
| A | ✅ | `edc1fb9d refactor(mysql): E.3 Sub-Slice A — extract MysqlSequenceEmulationTemplates` + Review `4336284d chore(mysql): E.3 Sub-Slice A review follow-ups` |
| B | ✅ | `28598cde feat(mysql): E.3 Sub-Slice B — diff renderer for sequence operations` + Review `7c2b8bec chore(mysql): E.3 Sub-Slice B review follow-ups` |
| C | ✅ | `d3724a33 feat(mysql): E.3 Sub-Slice C — RenameSequence policy + defensive renderer` + Review `93aa3e40 chore(mysql): E.3 Sub-Slice C review follow-ups` |
| D | ✅ | `0bda4f15 feat(core): E.3 Sub-Slice D — SequenceDefaultReprojector handles DropCreateFallback` |
| E (iter 1) | ✅ | `c7e92bf4 docs(plan): E.3 MySQL Sequence Diff-Migration closing` + `1431fb24 docs(spec): E.3 closing review follow-ups` (re-opened wegen Sub-Slice F-Findings) |
| F | ✅ | `3bea97e7 feat(mysql): E.3 Sub-Slice F — bootstrap idempotency + column-default rendering + start-only skip` |
| G (iter 2) | ✅ | `aba3392f docs(plan): E.3 MySQL Sequence Diff-Migration closing iter 2` (re-opened — Findings) |
| H | ✅ | `d248cd11 feat(mysql): E.3 Sub-Slice H — delimiter-free diff routines + drift-check follow-up plan + scope clarification` |
| I (iter 3) | ✅ | `docs(plan): E.3 MySQL Sequence Diff-Migration closing iter 3` |
