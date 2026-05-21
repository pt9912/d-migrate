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
`open/sqlite-sequence-emulation-plan.md`) deckt nur die **deklarativen Attribute** ab —
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
    Ohne deterministischen Vorzustand ist der Down-Pfad für den
    *Current-Value-Teil* explizit als `ROLLBACK_NOT_POSSIBLE` zu markieren
    (die eigentliche `CreateSequence`-`DROP`-Reversibilität bleibt davon
    getrennt).
- Neuer `SequenceCurrentValueProbe`-Port in `hexagon:ports-read` mit
  dialect-spezifischer Implementierung:
  - **PG**: `SELECT last_value, is_called FROM <sequence_name>`
  - **MySQL** (Emulation): `SELECT next_value, managed_by, format_version FROM dmg_sequences
    WHERE name = <escaped_mysql_sequence_key> AND managed_by = 'd-migrate' AND format_version IN (<mysqlExpectedFormatVersions>)` (`next_value` ist der nächste von
    `nextval` gelieferte Wert). `managed_by` und `format_version` werden
    verwendet, und zusammen mit den in der Emulation definierten
    unterstützten `format_version`-Werten (`mysqlExpectedFormatVersions`),
    um sicherzustellen, dass die Reihe aus der d-migrate-
    Sequence-Emulation stammt.
    Für `dmg_sequences` ist der Lookup-Key deterministisch aus dem
    `SequenceObjectRef` abzuleiten (Name + stabiler Resolver auf schema/namespace),
    und dieselbe Funktion muss von der MySQL-Render- und Probe-Seite konsistent genutzt
    werden (`dmg_sequences` kennt keine weitere Disambiguierungsspalte).
  - **SQLite**: `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` bis die
    SQLite-Planung für `preserveCurrentValue` vorliegt (`open/sqlite-sequence-emulation-plan.md`).
- Neue Operation-Subtype:
  `AlterSequenceCurrentValue(pairId, probeSequenceRef, applySequenceRef, currentValue, isCalled: Boolean?, restoreValue: Long?, restoreIsCalled: Boolean?, rollbackImpossible: Boolean = false, rollbackImpossibleReason: String? = null, revertAfterRename: Boolean = false)`
  (kein Reuse von `AlterSequence`; Daten-statement-Renderer ist separat).
- Erweiterung: Für Reversibility wird zusätzlich ein optionales
  `restoreIsCalled: Boolean?` vorgesehen.
- Renderer-Pfade pro Dialekt:
  - **PG**: `SELECT setval('<sequence_name>', <value>, <is_called>)`
    als DDL-Equivalent, wobei `<is_called>` vom Probe-Ergebnis
    übernommen wird.
  - **MySQL**: `UPDATE dmg_sequences SET next_value = <value>
    WHERE name = <escaped_mysql_sequence_key> AND managed_by = 'd-migrate' AND format_version IN (<mysqlExpectedFormatVersions>)`
    (kein +1).
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
      Als primäre Quelle gilt die erfolgreiche Probe (`Read`) auf dem
      jeweiligen Sequenzziel/-ursprung; daraus werden `restoreValue` (für alle
      Dialekte) und für PG `restoreIsCalled` deterministisch abgeleitet.
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
- **Multi-Sequence-Atomicity**: wenn ein Plan mehrere Sequenzen
  preserved, sind die Probe-Punkte zeitlich auseinander; ein
  Operator, der zwischen den Probes Inserts macht, kann
  Inkonsistenzen erzeugen. Carve-out dokumentiert.

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
    fun probe(connection: JdbcOperations, sequenceRef: SequenceObjectRef): SequenceCurrentValueProbeResult
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

// MySQL-Helfer für deterministic name mapping:
// val mysqlSequenceKey: String = formatMysqlDmgSequenceKey(sequenceRef)
// -> escaped via escapeMysqlStringLiteral(mysqlSequenceKey)
// -> Query/Update WHERE name = :mysqlSequenceKey
// (gleiche Funktion für Probe und Renderer)
```

### 5.2 Pipeline-Integration

Analog zu `CheckPreflight` im bestehenden
`MigrationPreflightPlanner` + `SchemaMigrateRenderPipeline`-Fluss:

```
data class SequencePreserveContext(
    val sequenceOp: DiffOperation, // AlterSequence | CreateSequence | RenameSequence
    val probeSequenceRef: SequenceObjectRef,
    val applySequenceRef: SequenceObjectRef,
    val pairId: String,
)

val preserveSequenceOps = plan.operations
    .filter { it is DiffOperation.AlterSequence || it is DiffOperation.CreateSequence || it is DiffOperation.RenameSequence }
    .filter { it.shouldPreserveCurrentValue() }

val preserveSequenceCandidates = preserveSequenceOps
    .filterIsInstance<DiffOperation.AlterSequence>()
    .plus(preserveSequenceOps.filterIsInstance<DiffOperation.CreateSequence>())
    .plus(preserveSequenceOps.filterIsInstance<DiffOperation.RenameSequence>().filter { shouldProbeRenameSequence(it) && it.shouldPreserveCurrentValue() })

val sequencesNeedingPreservation = preserveSequenceCandidates.mapNotNull { op ->
        when (op) {
            is DiffOperation.AlterSequence -> SequencePreserveContext(
                sequenceOp = op,
                probeSequenceRef = op.objectRef,
                applySequenceRef = op.objectRef,
                pairId = "alter:${op.objectRef}",
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
                        pairId = "create:${op.sequenceRef}",
                    )
                }
            }
            is DiffOperation.RenameSequence -> SequencePreserveContext(
                sequenceOp = op,
                probeSequenceRef = op.fromRef,
                applySequenceRef = op.toRef,
                pairId = "rename:${op.fromRef}->${op.toRef}",
            )
            else -> throw IllegalArgumentException("unexpected sequence operation type")
        }
    }

    if (plan.isFileToFileMode && sequencesNeedingPreservation.isNotEmpty()) {
        // Datei-zu-Datei kann keinen deterministischen Preflight ausführen;
        // nur probe-fähige Preservation-Operationen (Read-Pfad) werden geblockt.
        // Reine CreateSequence ohne lesbaren Vorzustand wurde bereits auf
        // `SEQUENCE_PRESERVE_NOT_FOUND` reduziert und landet hier nicht.
        // MANUAL_ACTION_REQUIRED geblockt.
        emitBlocker(
            "SEQUENCE_PRESERVE_REQUIRES_DB_TARGET",
            "preserveCurrentValue requires execute mode with a reachable target database.",
        )
    } else {
        val mysqlExpectedManagedBy = setOf("d-migrate")
        val mysqlExpectedFormatVersions = mysqlSequenceEmulationMetadata.supportedFormatVersions
        if (mysqlExpectedFormatVersions.isEmpty()) {
            emitBlocker(
                "SEQUENCE_PRESERVE_CONFIG_INVALID",
                "MySQL sequence metadata is missing supported format versions; cannot perform a deterministic preserve run.",
            )
            return
        }
        fun mysqlSequenceLookupKey(ref: SequenceObjectRef): String = renderMysqlDmgSequenceLookupKey(ref)

        fun validateMysqlReadDeterminism(
            result: SequenceCurrentValueProbeResult.Read,
            probeSequenceRef: SequenceObjectRef,
        ): SequenceCurrentValueProbeResult {
            return if (result.matchedRows != 1) {
                SequenceCurrentValueProbeResult.Failed(
                    code = "SEQUENCE_PRESERVE_PROBE_FAILED",
                    message = "dmg_sequences query returned ${result.matchedRows} rows for nameKey=${mysqlSequenceLookupKey(probeSequenceRef)}",
                )
            } else result
        }

        fun isManagedDmgSequenceProbeResult(result: SequenceCurrentValueProbeResult.Read): Boolean {
            // Probe und Renderer müssen exakt dieselbe Resolverfunktion und dieselben
            // Validierungsregeln gegen managedBy/formatVersion nutzen.
            return result.managedBy != null
                && result.formatVersion != null
                && result.managedBy in mysqlExpectedManagedBy
                && result.formatVersion in mysqlExpectedFormatVersions
        }

        data class RestoreHints(
            val restoreValueHint: Long?,
            val restoreIsCalledHint: Boolean?,
        )

        fun resolveRestoreHints(
            op: DiffOperation,
            probeSequenceRef: SequenceObjectRef,
            readResult: SequenceCurrentValueProbeResult.Read,
        ): RestoreHints {
            // Restore-Hinweise deterministisch ableiten; Probe ist primäre Quelle.
            // Restore value is always derived from the deterministic probe snapshot.
            val restoreValueHint = readResult.value
            val restoreIsCalledHint = when (probeSequenceRef.dialect) {
                Dialect.POSTGRES -> readResult.isCalled
                else -> determineRestoreIsCalledHint(op)
            }
            return RestoreHints(
                restoreValueHint = restoreValueHint,
                restoreIsCalledHint = restoreIsCalledHint,
            )
        }

for (ctx in sequencesNeedingPreservation) {
    val op = ctx.sequenceOp
    val result = probe.probe(connection, ctx.probeSequenceRef)
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
            if (ctx.probeSequenceRef.dialect == Dialect.MYSQL && !isManagedDmgSequenceProbeResult(readResult)) {
                emitBlocker(
                    "SEQUENCE_PRESERVE_PROBE_FAILED",
                    "MySQL sequence ${ctx.probeSequenceRef} is not recognized as d-migrate-managed sequence metadata.",
                )
                continue
            }
            val isCalled = when (ctx.probeSequenceRef.dialect) {
                Dialect.POSTGRES -> {
                    if (readResult.isCalled == null) {
                        emitBlocker("SEQUENCE_PRESERVE_PROBE_FAILED", "PG-Probe muss is_called liefern")
                        continue
                    }
                    readResult.isCalled
                }
                else -> null
            }
            val restoreHints = resolveRestoreHints(op, ctx.probeSequenceRef, readResult)
            val isRollbackPossible = restoreHints.restoreValueHint != null && (ctx.probeSequenceRef.dialect != Dialect.POSTGRES || restoreHints.restoreIsCalledHint != null)
            val rollbackNotPossible = if (isRollbackPossible) {
                null
            } else {
                val reason = if (restoreHints.restoreValueHint == null) {
                    "deterministic restore snapshot unavailable"
                } else {
                    "PG restore_is_called missing"
                }
                reason
            }
            emitFollowupAlterSequenceCurrentValue(
                op,
                pairId = ctx.pairId,
                probeSequenceRef = ctx.probeSequenceRef,
                applySequenceRef = ctx.applySequenceRef,
                currentValue = readResult.value,
                isCalled = isCalled,
                restoreValue = restoreHints.restoreValueHint,
                restoreIsCalled = restoreHints.restoreIsCalledHint,
                rollbackImpossible = !isRollbackPossible,
                rollbackImpossibleReason = rollbackNotPossible,
                revertAfterRename = op is DiffOperation.RenameSequence,
                insertAfter = true,
            )
        }
        is NotFound -> {
            if (op is DiffOperation.CreateSequence) {
                emitNote(
                    "SEQUENCE_PRESERVE_NOT_FOUND",
                    "No existing target state for ${ctx.applySequenceRef}; create value remains declarative, " +
                    "current-value rollback is not possible and must be treated as ROLLBACK_NOT_POSSIBLE.",
                )
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

// Caller übergibt `SequenceObjectRef`; jede Dialekt-Implementierung kapselt eigenes
// Naming/Quoting (inkl. Literalbildung) intern.

`resolveRestoreHints(op, probeSequenceRef, readResult)` leitet den Reverse-Zustand deterministisch aus Planer-Kontext und Probe ab:
- Als Primärquelle dient der konkrete Probe-Wert (`readResult.value`) als `restoreValue`.
- Für PG wird `restoreIsCalled` aus `readResult.is_called` geliefert.
- Für Nicht-PG dient `restoreIsCalled` als optionales Zusatzfeld aus
  `determineRestoreIsCalledHint(op)`.
- Bei neu anzulegenden Sequenzen ohne deterministische Historie bleibt `restoreValue`
  `null` und der Down-Pfad für den Current-Value-Teil ist als `ROLLBACK_NOT_POSSIBLE`
  zu kennzeichnen.
  Für diesen Pfad soll `resolveRestoreHints` nicht aufgerufen werden; die Dokumentation
  erfolgt über die `SEQUENCE_PRESERVE_NOT_FOUND`-Hinweisdiagnose mit explizitem
  Hinweis auf fehlende Deterministik.

`AlterSequenceCurrentValue` nutzt für Down explizit:
- `applySequenceRef` für Up/Forward-Pfad (die neue Zielsequenz nach `RenameSequence`,
  sonst das betroffene Objekt).
- `probeSequenceRef` für Down/Restore nur bei Rename-Fällen, damit ein Rename deterministisch
  auf den Quellnamen zurückrollt.

`revertAfterRename` signalisiert die Reverse-Order: bei `RenameSequence`
muß die Down-Operation auf `probeSequenceRef` **nach** der zugehörigen Rename-Rückoperation
(new -> old) ausgeführt werden.

Die Reihenfolge ist lokal über `revertAfterRename` deterministisch zu erzwingen:
`emitFollowupAlterSequenceCurrentValue(..., revertAfterRename = true, insertAfter = true)` und ein
vergleichbarer Down-Emitter-Ordering-Block schreiben einen festen Reihenfolgen-Constraint von
`RenameSequence`-Down zu `AlterSequenceCurrentValue`-Down.

Implementierungs-Constraint:
- Jede `AlterSequenceCurrentValue`-Down-Op mit `revertAfterRename = true` MUSS in der
  Down-Emission nach der passenden `RenameSequence`-Down-Op mit derselben `pairId`
  ausgegeben werden.
- Bei Gruppen-Bildung im Sortierer ist ein lokaler Ordering-Key verpflichtend:
  `(pairId, step)` mit `step = RenameDown (0)`, `AlterSequenceCurrentValue-Down (1)`.

Für PG gilt: `restoreIsCalled` ist für den Down-Pfad verpflichtend.
Fehlt dieser, ist `AlterSequenceCurrentValue` als `ROLLBACK_NOT_POSSIBLE`
zu kennzeichnen. Das ist kein zusätzlicher Planer-Blocker, sondern
konzeptionell ein explizit dokumentierter nicht unterstützter Down-Pfad.

### 5.3 Operation-Modell

Festlegung: neuer Subtyp `AlterSequenceCurrentValue` mit
`pairId`, `probeSequenceRef`, `applySequenceRef`, `currentValue`, optionalem `isCalled`,
`restoreValue`, optionalem `restoreIsCalled`, optionalem `revertAfterRename`
und Reversibility-Metadaten `rollbackImpossible`, optionaler `rollbackImpossibleReason`
(`pairId` dient zur Rename-Down-Kopplung; für PG muss Down zwingend einen
non-null `restoreIsCalled` liefern),
weil der Render-Pfad fundamental anders ist (Daten-Statement statt DDL).

### 5.4 Dialekt-Render-Matrix

| Dialekt | Render |
|---|---|
| PG | `SELECT setval('<seq>', <value>, <isCalled>);` |
| MySQL | `UPDATE dmg_sequences SET next_value = <value> WHERE name = <escaped_mysql_sequence_key> AND managed_by = 'd-migrate' AND format_version IN (<mysqlExpectedFormatVersions>);` |
| SQLite | Blocker bis SQLite-Sequence-Plan landet |

MySQL-Renderer müssen sicherstellen, dass genau eine Zeile betroffen ist;
der Statement-Execution-Step muss das betroffene Row-Count-Metadatum als
`expectedAffectedRows = 1` prüfen.

- Bei Up/Forward-Phase: bei 0 oder >1 betroffenen Zeilen ist der Run als
  `SEQUENCE_PRESERVE_PROBE_FAILED` zu behandeln (nicht deterministischer Live-Zustand).
- Bei `rollback`: bei 0 oder >1 betroffenen Zeilen ist der Downpfad explizit als
  `ROLLBACK_NOT_POSSIBLE` zu kennzeichnen.

`<escaped_mysql_sequence_key>` ist als SQL-literal-seitig escaped String zu rendern
(z. B. über vorhandene Dialekt-Quoting-Helfer), nicht als unformatierter Identifier einzusetzen.

---

## 6. Sub-Slice-Schnitt

| Sub-Slice | Inhalt |
|---|---|
| A | `preserveCurrentValue`-Feld + YAML-Codec + `SequenceObjectRef`-Werttyp + `SequenceCurrentValueProbe`-Port + `AlterSequenceCurrentValue`-Subtyp |
| B | PG-Probe + PG-Renderer fuer `setval` |
| C | MySQL-Probe + MySQL-Renderer fuer `UPDATE dmg_sequences` (inkl. `mysqlSequenceLookupKey`-Helper) |
| D | Pipeline-Integration im `MigrationPreflightPlanner`-Flow vor Render (probe → emit) |
| E | Datei-zu-Datei-Blocker + Schema-Doku + Closing |

SQLite folgt aus `open/sqlite-sequence-emulation-plan.md`.

### 6.1 Sub-Slice A — Foundations (Detail-DoD)

Sub-Slice A liefert die reinen Datentypen, an die B/C/D anknüpfen. **Kein
Renderer-, Probe- oder Planner-Code in A** — diese Sub-Slices sind
isoliert lieferbar.

**Artefakte (Produktionscode):**

- `SequenceDefinition.preserveCurrentValue: Boolean = false` (default-additiv,
  rückwärtskompatibel) in `hexagon/core/.../model/SequenceDefinition.kt`.
- YAML-Codec für das neue Feld in
  `adapters/driven/formats/.../SchemaNodeProgrammabilityParsers.kt` (read+write;
  fehlendes Feld → `false`). Ohne den Codec ist `preserveCurrentValue` aus
  User-Schemas unerreichbar — A ist erst dann nutzbar.
- `SequenceObjectRef` als neuer Werttyp in
  `hexagon/core/.../diff/migration/SequenceObjectRef.kt` mit Feldern für
  Sequenz-Name, optionalem Schema/Namespace und `DatabaseDialect`. Wird in
  §5.1 (Probe-Port) und §5.3 (`AlterSequenceCurrentValue`) verwendet — muss
  daher zusammen mit Port + DiffOp landen, sonst sind beide nicht baubar.
- `SequenceCurrentValueProbe`-Port + sealed
  `SequenceCurrentValueProbeResult` (`Read{value, matchedRows, isCalled?,
  managedBy?, formatVersion?}`, `Failed{code, message}`, `NotFound`,
  `NotApplicable`) in `hexagon/ports-read/.../driver/SequenceCurrentValueProbe.kt`.
- `AlterSequenceCurrentValue`-DiffOperation-Subtyp in
  `hexagon/core/.../diff/migration/DiffOperation.kt` mit allen Feldern aus
  §3.1/§5.3 (`pairId`, `probeSequenceRef: SequenceObjectRef`,
  `applySequenceRef: SequenceObjectRef`, `currentValue: Long`, `isCalled:
  Boolean?`, `restoreValue: Long?`, `restoreIsCalled: Boolean?`,
  `rollbackImpossible: Boolean = false`, `rollbackImpossibleReason: String?`,
  `revertAfterRename: Boolean = false`).

**Artefakte (Tests):**

- `SequenceDefinitionTest` — Default-Wert pinned, `copy()`-Roundtrip.
- YAML-Codec-Roundtrip-Test (bestehende `SchemaNode…Test`-Datei
  erweitern) — missing field → `false`, `true`/`false` echtes Mapping.
- `SequenceObjectRefTest` — Konstruktion, Equality, Dialect-Branching.
- `SequenceCurrentValueProbeTest` — Sealed-Class-Shape: alle vier
  Subtypen konstruierbar, equals/hashCode für `Read`/`Failed`.
- `DiffOperationTest` (extend) — `AlterSequenceCurrentValue`-Konstruktion +
  `withDependencies`/`withId`-Invarianten.

**Definition of Done (A):**

- [ ] `SequenceDefinition.preserveCurrentValue` existiert mit Default
      `false`; bestehende Code-Pfade kompilieren ohne Änderung.
- [ ] YAML-Roundtrip pinnt: fehlendes Feld → `false`, `true`/`false`
      werden gelesen/geschrieben.
- [ ] `SequenceObjectRef` ist als eigener Werttyp angelegt (nicht
      Alias auf `DiffObjectRef`); Dialekt-Feld ist `DatabaseDialect`
      (Code-Konvention; Plan-Doc-Pseudocode `Dialect.POSTGRES` mapped
      darauf ab).
- [ ] `SequenceCurrentValueProbe`-Port kompiliert; sealed
      `SequenceCurrentValueProbeResult` deckt alle vier Outcomes
      ohne `else`-Zweige in Tests ab.
- [ ] `AlterSequenceCurrentValue` ist als `DiffOperation`-Subtyp
      konstruierbar mit allen Feldern aus §3.1/§5.3; partial `when`-
      Branches in den 7 bestehenden Sequence-Op-Konsumenten
      (`SqliteCastPreflightStage`, `MysqlSequenceCanonicityStage`,
      `RoutineDependencyAnalyzer`, drei Dialekt-DDL-Generatoren,
      `MysqlSequenceCanonicityProbeRunner`) bleiben unverändert weil
      sie `else`-Fallback haben — Build grün.
- [ ] Kein Planner-Emit, kein Renderer-Code, kein Probe-Adapter in A.
- [ ] Tests laufen via `make docker-test MODULES=":hexagon:core
      :hexagon:ports-read :adapters:driven:formats"` grün.

**Bewusst nicht in A:**

- Diagnose-Codes (`SEQUENCE_PRESERVE_*`) — landen erst, wo sie tatsächlich
  emittiert werden (B/C/D).
- `PlannerBlockerClassifier`-Mapping — gehört zur Diagnose-Emission, nicht zu A.
- `mysqlSequenceLookupKey` / `formatMysqlDmgSequenceKey` — MySQL-spezifisch,
  in C.
- Renderer-Pfade pro Dialekt — B (PG) und C (MySQL).
- `MigrationPreflightPlanner`-Erweiterung — D.
- File-target-Blocker und Schema-Doku — E.

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
- [ ] Konfigurationsabweichung im MySQL-Flow (`mysqlExpectedFormatVersions` leer) blockt den
      Planner deterministisch mit `SEQUENCE_PRESERVE_CONFIG_INVALID`.
- [ ] MySQL-Probe validiert `managed_by`/`format_version` gegen ein
      bekanntes d-migrate Sequenz-Emulationsformat, sonst wird
      `SEQUENCE_PRESERVE_PROBE_FAILED` gesetzt.
- [ ] MySQL-Probe und MySQL-Renderer verwenden dieselbe Resolverfunktion
      für den `dmg_sequences.name`-Lookup-Key aus `SequenceObjectRef`
      (gleicher `mysql_sequence_key` für Probe/Render-Phase).
- [ ] MySQL-Probe schlägt fehl (`SEQUENCE_PRESERVE_PROBE_FAILED`), wenn
      die Abfrage auf `dmg_sequences` mehr als eine deterministische Trefferzeile liefert
      oder keine eindeutig matcht.
- [ ] MySQL-Renderer führt `UPDATE` nur auf genau eine determinierte Zeile aus
      (`1` affected row); Up/Forward nutzt bei 0 oder >1 betroffenen Zeilen
      `SEQUENCE_PRESERVE_PROBE_FAILED`, Down/rollback kennzeichnet explizit
      `ROLLBACK_NOT_POSSIBLE`.
- [ ] `CreateSequence` mit fehlendem deterministischem Vorzustand emittiert
      `SEQUENCE_PRESERVE_NOT_FOUND` als Hinweis und erzeugt keinen Blocker.
      Der Hinweis enthält explizit, dass der Current-Value-Restore als
      `ROLLBACK_NOT_POSSIBLE` zu behandeln ist; `AlterSequence`/`RenameSequence`
      ohne Vorzustand blocken mit `SEQUENCE_PRESERVE_PROBE_FAILED`.
- [ ] Für `RenameSequence` wird `AlterSequenceCurrentValue` deterministisch mit
      `probeSequenceRef` (old/origin) und `applySequenceRef` (new/target)
      emittiert; Down-Renderer wendet den Restore auf `probeSequenceRef` an.
- [ ] Bei `RenameSequence` mit `revertAfterRename = true` ist die Down-Reihenfolge
      durchgängig fest: Rename-Rückoperation (`RenameSequence` down) **vor** der
      `AlterSequenceCurrentValue`-Restore-Operation.
      Die Reihenfolge ist über denselben `pairId` nachweisbar und wird in der
      Assertion deterministisch geprüft.
- [ ] MySQL-Prüfung/Restore nutzt `mysqlExpectedFormatVersions` aus der
      Emulations-Definition (nicht einen einzelnen hartkodierten Wert im
      Renderer/Planner), und `format_version` wird über diese Menge evaluiert.
- [ ] Follow-up-Operationen werden direkt im Anschluss an die jeweilige
      Sequence-Operation emittiert (keine Umordnung durch
      allgemeine Plan-Sortierung).
- [ ] Datei-zu-Datei-Modus mit `preserveCurrentValue = true` und mindestens
      einer probe-fähigen Sequence-Operation blockt mit
      `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`; reine `CreateSequence` ohne
      deterministischen Vorzustand darf nur `SEQUENCE_PRESERVE_NOT_FOUND`
      ausgeben.
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
      ebenfalls verpflichtend und wird aus dem PG-Probefeld `is_called` übernommen,
      sonst ist Down als `ROLLBACK_NOT_POSSIBLE` dokumentiert.
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
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`,
      `SEQUENCE_PRESERVE_CONFIG_INVALID`. Alle vier
      mappen über `PlannerBlockerClassifier` auf: `SEQUENCE_PRESERVE_PROBE_FAILED`,
      `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` und
      `SEQUENCE_PRESERVE_CONFIG_INVALID` auf `MANUAL_ACTION_REQUIRED`;
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` auf
      `DIALECT_UNSUPPORTED_OPERATION`.
- [ ] **Hinweisdiagnose**: `SEQUENCE_PRESERVE_NOT_FOUND` wird im Report
      ohne Blocker-Klasse ausgegeben, wenn eine `CreateSequence`-Operation
      keinen lesbaren Vorzustand hat.
      Für diesen Fall ist der Current-Value-Teil explizit als
      `ROLLBACK_NOT_POSSIBLE` dokumentiert.
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
- Multi-Sequence-Atomicity.
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
