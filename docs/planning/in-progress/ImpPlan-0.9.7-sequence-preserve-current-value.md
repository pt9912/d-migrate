# Implementierungsplan: 0.9.7 — Sequence preserveCurrentValue-Policy

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.3 Cross-Dialect Folge-Slice
> **Status**: open 2026-05-19.
> **Vorbedingung**: E.3 Erstscheibe (PG-Sequence-Diff-Renderer) ✅;
>                  PG-`SequenceDefinition` ✅;
>                  Live-DB-Reader-Pfade pro Dialekt ✅;
>                  F.4 Renderer-Blocker-Bridge ✅ 2026-05-19;
>                  MySQL/SQLite-Sequence-Diff-Migration *(parallele Plans)*.
> **Referenz**: `diffresult-migration-plan-2.md` §E.3 (heutige
>             Sequence-Erstscheibe pinnt ausdruecklich: „aktueller
>             Wert wird NICHT migriert“); `ImpPlan-0.9.7-mysql-sequence-diff-migration.md`;
>             `open/sqlite-sequence-emulation-plan.md`.

---

## 1. Auslöser

Die heutige Sequence-Migration (PG E.3 Erstscheibe, MySQL Emulation
in `done/mysql-sequence-emulation-plan.md`, SQLite-Plan in
`open/`) deckt nur die **deklarativen Attribute** ab —
`start`, `increment`, `minValue`, `maxValue`, `cycle`, `cache`.
Den **aktuellen Wert** (`last_value` in PG, `next_value` in
MySQL-Emulation als *nächster von `nextval()`-Rückgabewert*) migriert
keine Pipeline. Effekt:

- Operator migriert eine bestehende Tabelle mit `id` aus einer
  PG-Sequenz nach DB-Target. Die Sequenz wird mit
  `CREATE SEQUENCE … START WITH 1` neu angelegt. Bei einem
  spaeteren `INSERT` springt `nextval('seq')` auf 1 statt auf den
  vorherigen Wert + 1 → **PK-Konflikt** mit existierenden Zeilen,
  Migration scheitert beim ersten Schreibzugriff.

Das ist der Hauptgrund, warum d-migrate fuer
sequence-tragende Schemata in Produktion heute nicht
ohne manuelle Nachbearbeitung benutzt werden kann. Operatoren
muessen nach jeder Migration manuell `ALTER SEQUENCE … RESTART WITH
<observed-max>` ausfuehren.

---

## 2. Warum jetzt?

`preserveCurrentValue` ist die kritische Live-DB-Bruecke, die
Sequence-Migrationen ueberhaupt erst produktionstauglich macht.
Der DDL-Generator-Pfad (`schema generate`) deckt Schema-Strukturen
ab, aber Migrationen brauchen **Daten-Bewusstsein** fuer Sequences
genauso wie Cast-Preflight fuer Spalten-Typaenderungen
(B.2-Pattern). Ohne diesen Slice ist der MySQL-Sequence-Diff-
Slice (parallel) und der SQLite-Sequence-Plan halbgar in
Production.

Roadmap §E Rest listet explizit:
> Aktueller Sequence-Wert / Preserve-Policy

---

## 3. Scope

### 3.1 In-Scope

- Neues optionales Feld `preserveCurrentValue: Boolean = false`
  auf `SequenceDefinition` als primäre Steuerung.
- Kein neuer globaler CLI-Flag in dieser Tranche; `preserveCurrentValue`
  wird vollständig über das Schema gesteuert.
- Kandidaten für Preserve:
  - `AlterSequence`: immer.
  - `RenameSequence`: nur, wenn die Rename-Zuordnung eindeutig auf eine
    bereits verwaltete Ziel-Sequenz zeigt (Drop+Create-Fallbacks ohne
    deterministischen Vorzustand sind ausgeschlossen). Für Reversibility und
    deterministisches Recovery werden sowohl Probe- als auch Apply-Objektreferenz
    getrennt im Folge-Op geführt (`probeSequenceRef`, `applySequenceRef`):
    `applySequenceRef` identifiziert die Zielsequenz für Up (`setval`/`UPDATE`),
    `probeSequenceRef` die Ursprungssequenz für Down/Restore bei Rename.
  - `CreateSequence`: nur, wenn die Sequenz vor Migration im Ziel bereits
    deterministisch lesbar ist (idempotente/dirty Zielzustände); reine
    Neu-Erzeugung ohne Vorzustand erzeugt keinen Preserve-Follow-up.
    Ohne deterministischen Vorzustand ist der Down-Pfad explizit als
    `ROLLBACK_NOT_POSSIBLE` zu markieren.
- Neuer `SequenceCurrentValueProbe`-Port in `hexagon:ports-read` mit
  dialect-spezifischer Implementierung:
  - **PG**: `SELECT last_value, is_called FROM <sequence_name>`
  - **MySQL** (Emulation): `SELECT next_value, managed_by, format_version FROM dmg_sequences
    WHERE name = <escaped_sequence_name>` (`next_value` ist der nächste von
    `nextval` gelieferte Wert). `managed_by` und `format_version` werden
    verwendet, und zusammen mit den in der Emulation definierten
    unterstützten `format_version`-Werten (`mysqlExpectedFormatVersions`),
    um sicherzustellen, dass die Reihe aus der d-migrate-
    Sequence-Emulation stammt.
  - **SQLite**: `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` bis die
    SQLite-Planung für `preserveCurrentValue` vorliegt (`open/sqlite-sequence-emulation-plan.md`).
- Neue Operation-Subtype:
  `AlterSequenceCurrentValue(probeSequenceRef, applySequenceRef, currentValue, isCalled: Boolean?, restoreValue: Long?, restoreIsCalled: Boolean?, revertAfterRename: Boolean = false)`
  (kein Reuse von `AlterSequence`; Daten-statement-Renderer ist separat).
- Erweiterung: Für Reversibility wird zusätzlich ein optionales
  `restoreIsCalled: Boolean?` vorgesehen.
- Renderer-Pfade pro Dialekt:
  - **PG**: `SELECT setval('<sequence_name>', <value>, <is_called>)`
    als DDL-Equivalent, wobei `<is_called>` vom Probe-Ergebnis
    übernommen wird.
  - **MySQL**: `UPDATE dmg_sequences SET next_value = <value>
    WHERE name = <escaped_sequence_name>` (kein +1).
  - **SQLite**: erst sobald der SQLite-Plan landet.
- Pipeline-Integration im `MigrationPreflightPlanner`-Flow vor
  `SchemaMigrateRenderPipeline` (`CheckPreflight` + `MigrationPreflightPlanner`):
  - Wenn `preserveCurrentValue = true` UND DB-Target verfuegbar:
    Probe vor Render; emittiere `AlterSequenceCurrentValue` mit
    dem geprobten Wert, inklusive `isCalled` (falls vom Dialekt
    geliefert) sowie optionalem `restoreValue`, sofern diese Planerzeit
    bestimmen kann.
    Für PG ist `isCalled` zwingend (kein Default), für andere
    Dialekte wird `isCalled` nicht verwendet.
  - Reverse-Daten:
    - `restoreValue`/`restoreIsCalled` werden nur gesetzt, wenn ein stabiler
      Ausgangszustand deterministisch vorliegt (bestehende Ziel-Sequenz vor der
      Migration, oder explizit dokumentierter pre-existing Snapshot).
    - Bei neu erstellten Sequenzen ohne deterministische Historie bleibt der
      Reverse-Zustand `null` und Down wird mit `ROLLBACK_NOT_POSSIBLE`.
  - Datei-zu-Datei-Modus: sobald `preserveCurrentValue = true` und mindestens eine
    relevante Sequence-Operation (`AlterSequence`, `CreateSequence`, `RenameSequence`)
    vorliegt, blockt der Plan mit `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` →
    `MANUAL_ACTION_REQUIRED`.
- F.5-Carve-out-Pattern wiederverwendet (Probe → Pipeline →
  Blocker-bei-Failure).
- Follow-up-Operations (`AlterSequenceCurrentValue`) werden deterministisch als direkte
  Folgeoperationen hinter dem jeweiligen Sequence-Diff-Op emittiert, damit
  `renderSequenceDbIdentifier`/`apply`-Namen kohärent bleiben.
- Kein CLI-Override in dieser Tranche. Die Tranche arbeitet nur mit
  `SequenceDefinition.preserveCurrentValue` (deterministische Steuerung).
- Reversibility: `AlterSequenceCurrentValue` Down setzt den
  Wert auf den im Plan enthaltenen `restoreValue` zurück, sofern
  bekannt.
  Wenn kein stabiler Restore-Wert vorliegt (z. B. fehlender
  pre-existing Sequence-Snapshot oder konkurrierende `nextval`-Calls),
  resultiert Down in `ROLLBACK_NOT_POSSIBLE`.
  Diese Werte werden als `restoreValue`/`restoreIsCalled` auf der
  Follow-up-Operation geführt. Der Planer setzt den Hint deterministisch, wenn der
  Ausgangswert aus einem pre-existing target-snapshot ableitbar ist.
  Bei neu angelegten Zielen bleibt der Restore-Hint `null` und Down wird als
  `ROLLBACK_NOT_POSSIBLE` markiert.

### 3.2 Out-of-Scope

- **Atomare Konsistenz** zwischen `nextval`-Calls in der App
  und der Migration: wenn die App parallel waehrend des
  Preflights Sequenzen-Calls macht, ist der geprobte Wert
  veraltet. Mitigation: Probe + `setval` in derselben
  Transaktion / unter Tabellen-Lock — separater Slice.
- **Sequence-Ownership-Inferenz** (wer „besitzt" eine Sequenz —
  PG `OWNED BY` table.column): bleibt im DDL-Generator-Pfad,
  diff-basiert ausgeklammert.
- **Multi-Sequence-Atomarity**: wenn ein Plan mehrere Sequenzen
  preserved, sind die Probe-Punkte zeitlich auseinander; ein
  Operator, der zwischen den Probes Inserts macht, kann
  Inkonsistenzen erzeugen. Carve-out documented.

---

## 4. Vorbedingungen

| Vorbedingung | Status |
| ------------ | ------ |
| `SequenceDefinition` neutrales Modell | ✅ |
| PG-Sequence-Diff-Renderer | ✅ E.3 Erstscheibe |
| MySQL-Sequence-Emulation (DDL-Generator) | ✅ 0.9.4 |
| MySQL-Sequence-Diff-Renderer | ⚠️ parallele Plan |
| SQLite-Sequence-Plan | ⚠️ `open/sqlite-sequence-emulation-plan.md` |
| Live-DB-Probe-Pattern (Cast-Preflight) | ✅ B.2 |

---

## 5. Architektur

### 5.1 Probe-Port

```kotlin
// hexagon:ports-read
interface SequenceCurrentValueProbe {
    fun probe(connection: JdbcOperations, sequenceName: String, dialect: Dialect): SequenceCurrentValueProbeResult
}

sealed class SequenceCurrentValueProbeResult {
    data class Read(
        val value: Long,
        val matchedRows: Int = 1,
        val isCalled: Boolean? = null,
        val managedBy: String? = null,
        val formatVersion: Int? = null,
    ) : SequenceCurrentValueProbeResult()
    data class Failed(val code: String, val message: String) : SequenceCurrentValueProbeResult()
    data object NotFound : SequenceCurrentValueProbeResult() // erwarteter Vorzustand nicht vorhanden
    data object NotApplicable : SequenceCurrentValueProbeResult() // SQLite without sequence support
}

// PG muss isCalled liefern; MySQL/SQLite dürfen null setzen.
// Bei MySQL wird zusätzlich geprüft, dass managedBy/formatVersion auf ein
// von d-migrate verwaltetes Emulationsformat verweisen.
// MySQL ist zusätzlich nur deterministisch, wenn genau eine Zeile zurückkommt.
```

### 5.2 Pipeline-Integration

Analog zu `CheckPreflight` im bestehenden
`MigrationPreflightPlanner` + `SchemaMigrateRenderPipeline`-Fluss:

```
data class SequencePreserveContext(
    val sequenceOp: DiffOperation, // AlterSequence | CreateSequence | RenameSequence
    val probeSequenceRef: SequenceObjectRef,
    val applySequenceRef: SequenceObjectRef,
)

val preserveSequenceOps = plan.operations
    .filter { it is DiffOperation.AlterSequence || it is DiffOperation.CreateSequence || it is DiffOperation.RenameSequence }
    .filter { it.shouldPreserveCurrentValue() }

    val preserveSequenceCandidates = preserveSequenceOps
        .filterIsInstance<DiffOperation.AlterSequence>()
        .filter { it.shouldPreserveCurrentValue() }
        .plus(preserveSequenceOps.filterIsInstance<DiffOperation.CreateSequence>())
        .plus(preserveSequenceOps.filterIsInstance<DiffOperation.RenameSequence>().filter { shouldProbeRenameSequence(it) && it.shouldPreserveCurrentValue() })

    if (plan.isFileToFileMode && preserveSequenceOps.isNotEmpty()) {
        emitBlocker(
            "SEQUENCE_PRESERVE_REQUIRES_DB_TARGET",
            "preserveCurrentValue requires execute mode with a reachable target database.",
        )
    } else {
        val mysqlExpectedManagedBy = setOf("d-migrate")
        val mysqlExpectedFormatVersions = mysqlSequenceEmulationMetadata.supportedFormatVersions

        fun validateMysqlReadDeterminism(
            result: SequenceCurrentValueProbeResult.Read,
            probeSequenceRef: SequenceObjectRef,
        ): SequenceCurrentValueProbeResult {
            return if (result.matchedRows != 1) {
                SequenceCurrentValueProbeResult.Failed(
                    code = "SEQUENCE_PRESERVE_PROBE_FAILED",
                    message = "dmg_sequences query returned ${result.matchedRows} rows for name=${probeSequenceRef.name}",
                )
            } else result
        }

        fun isManagedDmgSequenceProbeResult(result: SequenceCurrentValueProbeResult.Read): Boolean {
            // true, wenn Dialekt-spezifische Konventionen (managedBy/formatVersion) passen.
            return result.managedBy != null
                && result.formatVersion != null
                && result.managedBy in mysqlExpectedManagedBy
                && result.formatVersion in mysqlExpectedFormatVersions
        }

        fun markRollbackNotPossibleForDown(op: DiffOperation) {
            // Implementation detail: Follow-up-Operation als nicht reversibel
            // markieren (e.g. reversibility metadata on the emitted op).
        }

    val sequencesNeedingPreservation = preserveSequenceCandidates.mapNotNull { op ->
        when (op) {
            is DiffOperation.AlterSequence -> SequencePreserveContext(
                sequenceOp = op,
                probeSequenceRef = op.objectRef,
                applySequenceRef = op.objectRef,
            )
            is DiffOperation.CreateSequence -> {
                if (!shouldProbeCreateSequence(op)) {
                    emitNote("SEQUENCE_PRESERVE_NOT_FOUND", "No existing target state for ${op.sequenceRef}; create value remains declarative.")
                    null
                } else {
                    SequencePreserveContext(
                        sequenceOp = op,
                        probeSequenceRef = op.sequenceRef,
                        applySequenceRef = op.sequenceRef,
                    )
                }
            }
            is DiffOperation.RenameSequence -> SequencePreserveContext(
                sequenceOp = op,
                probeSequenceRef = op.fromRef,
                applySequenceRef = op.toRef,
            )
            else -> throw IllegalArgumentException("unexpected sequence operation type")
        }
    }

for (ctx in sequencesNeedingPreservation) {
    val op = ctx.sequenceOp
    val probeSequenceName = when (ctx.probeSequenceRef.dialect) {
        Dialect.POSTGRES -> renderSequenceDbIdentifier(ctx.probeSequenceRef)
        else -> ctx.probeSequenceRef.name
    }
    val result = probe.probe(connection, probeSequenceName, ctx.probeSequenceRef.dialect)
    when (result) {
        is Read -> {
            val deterministicResult = when {
                ctx.probeSequenceRef.dialect == Dialect.MYSQL -> validateMysqlReadDeterminism(
                    result = result,
                    probeSequenceRef = ctx.probeSequenceRef,
                )
                else -> result
            }
            if (deterministicResult is SequenceCurrentValueProbeResult.Failed) {
                emitBlocker(
                    "SEQUENCE_PRESERVE_PROBE_FAILED",
                    deterministicResult.message,
                )
                continue
            }

            val readResult = deterministicResult as SequenceCurrentValueProbeResult.Read
            if (ctx.probeSequenceRef.dialect == Dialect.MYSQL && !isManagedDmgSequenceProbeResult(result)) {
                emitBlocker(
                    "SEQUENCE_PRESERVE_PROBE_FAILED",
                    "MySQL sequence ${ctx.probeSequenceRef} is not recognized as d-migrate-managed sequence metadata.",
                )
                continue
            }
            val isCalled = when (ctx.probeSequenceRef.dialect) {
                Dialect.POSTGRES -> {
                    if (result.isCalled == null) {
                        emitBlocker("SEQUENCE_PRESERVE_PROBE_FAILED", "PG-Probe muss is_called liefern")
                        continue
                    }
                    readResult.isCalled
                }
                else -> null
            }
            val restoreIsCalledHint = determineRestoreIsCalledHint(op)
            if (ctx.probeSequenceRef.dialect == Dialect.POSTGRES && restoreIsCalledHint == null) {
                markRollbackNotPossibleForDown(op)
            }
            emitFollowupAlterSequenceCurrentValue(
                op,
                probeSequenceRef = ctx.probeSequenceRef,
                applySequenceRef = ctx.applySequenceRef,
                currentValue = readResult.value,
                isCalled = isCalled,
                restoreValue = determineRestoreValueHint(op),
                restoreIsCalled = restoreIsCalledHint,
                revertAfterRename = op is DiffOperation.RenameSequence,
                insertAfter = true,
            )
        }
        is NotFound -> {
            if (op is DiffOperation.CreateSequence) {
                emitNote("SEQUENCE_PRESERVE_NOT_FOUND", "No existing target state for ${ctx.applySequenceRef}; create value remains declarative.")
            } else {
                emitBlocker(
                    "SEQUENCE_PRESERVE_PROBE_FAILED",
                    "No existing target state found for ${ctx.applySequenceRef}: preserve requires deterministic pre-existing value",
                )
            }
        }
        is Failed -> emitBlocker(
            "SEQUENCE_PRESERVE_PROBE_FAILED",
            "Probe failed for ${ctx.applySequenceRef}: ${result.code}: ${result.message}",
        )
        NotApplicable -> emitBlocker("SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT")
    }
}
}
```

`shouldProbeCreateSequence(createOp)` gilt nur für explizit vorher vorhandene und
deterministisch lesbare Ziel-Sequenzen (`createOp.priorTargetState != null` o. ä.).
`shouldProbeRenameSequence(renameOp)` gilt nur, wenn die Rename-Zuordnung eindeutig auf
eine verwaltete Ziel-Sequenz zeigt (keine Auflösung über heuristische
Fallbacks).

// renderSequenceDbIdentifier liefert nur für PG den schematisch-quotierten
// DB-Identifikator (z. B. "public.my_seq"). Für MySQL/SQLite-Emulation wird der
// unqualifizierte Sequenzname benötigt (z. B. "my_seq").

`determineRestoreValueHint(op)` leitet den Reverse-Zustand deterministisch aus dem Planer-Kontext ab:
- Bei bestehender Ziel-Sequenz wird der vor-Migrationszustand als `restoreValue` und optional `restoreIsCalled` gesetzt.
- Bei neu anzulegenden Sequenzen bleibt der Reverse-Hint `null`.
- Bei fehlendem deterministischen Snapshot wird ebenfalls `null` zurückgegeben.

`determineRestoreValueHint(op)` / `determineRestoreIsCalledHint(op)` dürfen nur dann Werte liefern,
wenn der Wert aus einem pre-existing Snapshot eindeutig bestimmt ist; andernfalls werden sie `null`.
Ein Wert gilt als deterministisch, wenn die Ziel-Sequenz vor `AlterSequenceCurrentValue`
stabil gelesen werden kann und der Probe-Pfad ohne Fallback erfolgreich ist.

`AlterSequenceCurrentValue` nutzt für Down explizit:
- `applySequenceRef` für Up/Forward-Pfad (die neue Zielsequenz nach `RenameSequence`,
  sonst das betroffene Objekt).
- `probeSequenceRef` für Down/Restore nur bei Rename-Fällen, damit ein Rename deterministisch
  auf den Quellnamen zurückrollt.

`revertAfterRename` signalisiert die Reverse-Order: bei `RenameSequence`
muß die Down-Operation auf `probeSequenceRef` **nach** der zugehörigen Rename-Rückoperation
(new -> old) ausgeführt werden.

Für PG gilt: `restoreIsCalled` ist für den Down-Pfad verpflichtend.
Fehlt dieser, ist `AlterSequenceCurrentValue` als `ROLLBACK_NOT_POSSIBLE`
zu kennzeichnen. Das ist kein zusätzlicher Planer-Blocker, sondern
konzeptionell ein explizit dokumentierter nicht unterstützter Down-Pfad.

### 5.3 Operation-Modell

Festlegung: neuer Subtyp `AlterSequenceCurrentValue` mit
`probeSequenceRef`, `applySequenceRef`, `currentValue`, optionalem `isCalled`,
optionalem `restoreValue`
und optionalem `restoreIsCalled` (für PG muss Down zwingend einen
non-null `restoreIsCalled` liefern),
weil der Render-Pfad fundamental anders ist (Daten-Statement statt DDL).

### 5.4 Dialekt-Render-Matrix

| Dialekt | Render |
|---|---|
| PG | `SELECT setval('<seq>', <value>, <isCalled>);` |
| MySQL | `UPDATE dmg_sequences SET next_value = <value> WHERE name = <escaped_sequence_name> AND managed_by = 'd-migrate' AND format_version IN (<supported_format_versions>);` |
| SQLite | Blocker bis SQLite-Sequence-Plan landet |

`<escaped_sequence_name>` ist als SQL-literal-seitig escaped String zu rendern
(z. B. über vorhandene Dialekt-Quoting-Helfer), nicht als unformatierter Identifier
einzusetzen.

---

## 6. Sub-Slice-Schnitt

| Sub-Slice | Inhalt |
|---|---|
| A | `preserveCurrentValue`-Feld + `SequenceCurrentValueProbe`-Port + `AlterSequenceCurrentValue`-Subtyp |
| B | PG-Probe + PG-Renderer fuer `setval` |
| C | MySQL-Probe + MySQL-Renderer fuer `UPDATE dmg_sequences` |
| D | Pipeline-Integration im `MigrationPreflightPlanner`-Flow vor Render (probe → emit) |
| E | Datei-zu-Datei-Blocker + Schema-Doku + Closing |

SQLite folgt aus `open/sqlite-sequence-emulation-plan.md`.

---

## 7. Akzeptanzkriterien

- [ ] `SequenceDefinition.preserveCurrentValue` ist im Schema-Modell
      definiert und ist die einzige Schaltstelle für diese Tranche.
- [ ] PG-Probe liest `last_value`; PG-Renderer emittiert
      `SELECT setval('<seq>', <value>, <isCalled>)` mit korrekt
      propagiertem `isCalled`.
- [ ] PG-Probe übermittelt bei `Read` ein nicht-null `isCalled`.
- [ ] MySQL-Probe liest `dmg_sequences.next_value`; MySQL-Renderer
      emittiert `UPDATE dmg_sequences …`.
- [ ] MySQL-Probe validiert `managed_by`/`format_version` gegen ein
      bekanntes d-migrate Sequenz-Emulationsformat, sonst wird
      `SEQUENCE_PRESERVE_PROBE_FAILED` gesetzt.
- [ ] MySQL-Probe schlägt fehl (`SEQUENCE_PRESERVE_PROBE_FAILED`), wenn
      die Abfrage auf `dmg_sequences` mehr als eine deterministische Trefferzeile liefert
      oder keine eindeutig matcht.
- [ ] `CreateSequence` mit fehlendem deterministischem Vorzustand emittiert
      `SEQUENCE_PRESERVE_NOT_FOUND` als Hinweis und erzeugt keinen
      Blocker; `AlterSequence`/`RenameSequence` ohne Vorzustand blocken mit
      `SEQUENCE_PRESERVE_PROBE_FAILED`.
- [ ] Für `RenameSequence` wird `AlterSequenceCurrentValue` deterministisch mit
      `probeSequenceRef` (old/origin) und `applySequenceRef` (new/target)
      emittiert; Down-Renderer wendet den Restore auf `probeSequenceRef` an.
- [ ] Bei `RenameSequence` mit `revertAfterRename = true` ist die Down-Reihenfolge
      durchgängig fest: Rename-Rückoperation (`RenameSequence` down) **vor** der
      `AlterSequenceCurrentValue`-Restore-Operation.
- [ ] MySQL-Prüfung/Restore nutzt `mysqlExpectedFormatVersions` aus der
      Emulations-Definition (nicht einen einzelnen hartkodierten Wert im
      Renderer/Planner), und `format_version` wird über diese Menge evaluiert.
- [ ] Follow-up-Operationen werden direkt im Anschluss an die jeweilige
      Sequence-Operation emittiert (keine Umordnung durch
      allgemeine Plan-Sortierung).
- [ ] Datei-zu-Datei-Modus mit `preserveCurrentValue = true`
      blockt mit `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`.
- [ ] Probe-Failure blockt mit
      `SEQUENCE_PRESERVE_PROBE_FAILED`.
- [ ] SQLite blockt mit
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`.
- [ ] Reversibility: `AlterSequenceCurrentValue` Down nutzt den im
      Plan gespeicherten `restoreValue` und setzt damit den
      vor-Up-Wert wieder zurueck; fehlt der Wert, wird
      `ROLLBACK_NOT_POSSIBLE` ausgewiesen.
- [ ] Für `AlterSequenceCurrentValue` ist `restoreValue` exakt in den Fällen gesetzt, in denen ein
      deterministischer Ausgangszustand bekannt ist; `restoreIsCalled` ist für PG dort
      ebenfalls verpflichtend, sonst ist Down als `ROLLBACK_NOT_POSSIBLE` dokumentiert.
- [ ] Für alle Nicht-PG-Op ist `restoreIsCalled` optional.
      Für die übrigen Fälle
      ist Down explizit als `ROLLBACK_NOT_POSSIBLE` dokumentiert.
- [ ] Pro Dialekt mindestens je ein Positiv- und ein
      Blocker-Test, inklusive eines Rename-Up/Down-Reversibility-Tests.

---

## 8. Definition of Done (§13-Template)

- [ ] **Modus**: execute (Probe braucht Live-DB).
- [ ] **Renderbare Ops**: `AlterSequenceCurrentValue` auf PG/MySQL.
- [ ] **Neue Diagnostics**: `SEQUENCE_PRESERVE_PROBE_FAILED`,
      `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`,
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`. Alle drei
      mappen ueber `PlannerBlockerClassifier` auf
      `MANUAL_ACTION_REQUIRED` bzw.
      `DIALECT_UNSUPPORTED_OPERATION`.
- [ ] **Hinweisdiagnose**: `SEQUENCE_PRESERVE_NOT_FOUND` wird im Report
      ohne Blocker-Klasse ausgegeben, wenn eine `CreateSequence`-Operation
      keinen lesbaren Vorzustand hat.
      Für diesen Fall ist `ROLLBACK_NOT_POSSIBLE` verpflichtend.
- [ ] **Up / Down getrennt**: Up = `setval`/`UPDATE`; Down =
      `setval`/`UPDATE` auf den gespeicherten `restoreValue` und für PG
      zwingend `restoreIsCalled`, sonst explizit `ROLLBACK_NOT_POSSIBLE`.
- [ ] **Rename-Down-Ordering**: bei `RenameSequence` wird die
      `AlterSequenceCurrentValue`-Rollback-Operation deterministisch
      nach der zugehörigen Rename-Rückoperation ausgeführt.
- [ ] **Report-Felder**: keine neuen.
- [ ] **Dialekte**: PG (positiv), MySQL (positiv), SQLite
      (blocker).
- [ ] **F.0-Erfuellung**: irrelevant.
- [ ] **Positive + Blocker-Tests**: siehe §7.
- [ ] **Rollback-Test**: explizit gepinnt fuer alle drei
      Dialekte; SQLite-Blocker ist auch Rollback-Blocker.
- [ ] **Datei-zu-Datei**: blockt, weil keine Live-DB.
- [ ] **Bestehende Vertraege unveraendert**: bestehende
      Sequence-Slices bleiben gruen.

---

## 9. Out-of-Scope / Folge-Themen

- Atomare Probe + setval unter Lock.
- Multi-Sequence-Atomarity.
- Sequence-Ownership-Inferenz.
- SQLite-Sequence-Vollvariante mit preserve-Pfad.

---

## 10. Risiken

- **Probe-Race-Conditions**: Wenn die App parallel zwischen
  Probe und Setval `nextval` aufruft, ist der gesetzte Wert
  veraltet. Mitigation: Pipeline-Doku schreibt klar, dass der
  Operator den Schreibverkehr stoppt; eine kuenftige Tranche
  kann `LOCK TABLES` / `BEGIN; SELECT FOR UPDATE`-Wrapper
  ergaenzen.
- **Sequence-Ownership-Sichtbarkeit**: ohne `OWNED BY` ist nicht
  klar, welche Sequenz zu welcher Spalte gehoert. Mitigation:
  diese Tranche behandelt benannte Sequences nur ueber den
  `SequenceDefinition`-Namen; Inferenz aus PG-Reverse-Read
  ist bereits in `MysqlSchemaReader` und `PostgresSchemaReader`
  gepinnt.
