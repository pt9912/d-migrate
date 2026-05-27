# Implementierungsplan: 0.9.7 — Sequence preserveCurrentValue-Policy

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.3 Cross-Dialect Folge-Slice
> **Status**: Done (2026-05-21). Sub-Slices A–E implementiert,
>             `make docker-test` + `make docker-coverage-gate` grün,
>             Plan-Doc unter `done/`. Commit-Refs siehe §6.
> **Vorbedingung**: E.3 Erstscheibe (PG-Sequence-Diff-Renderer) ✅;
>                  PG-`SequenceDefinition` ✅;
>                  Live-DB-Reader-Pfade pro Dialekt ✅;
>                  F.4 Renderer-Blocker-Bridge ✅ 2026-05-19;
>                  MySQL/SQLite-Sequence-Diff-Migration *(parallele Plans)*.
> **Referenz**: `diffresult-migration-plan-2.md` §E.3 (aktuelle
>             Sequence-Erstscheibe pinnt ausdrücklich: „aktueller
>             Wert wird NICHT migriert“); `ImpPlan-0.9.7-mysql-sequence-diff-migration.md`;
>             `open/sqlite-sequence-emulation-plan.md`; ADR-0003
>             (`docs/adr/0003-cross-dialect-sequencing.md`)
>             dokumentiert die Cross-Dialect-Capability-Decisions,
>             unter denen das `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`-
>             Routing zu `DIALECT_UNSUPPORTED_OPERATION` retroaktiv
>             gepinnt ist.

---

## 1. Auslöser

Die aktuelle Sequence-Migration (PG E.3 Erstscheibe, MySQL Emulation
in `docs/planning/done/mysql-sequence-emulation-plan.md`, SQLite-Plan in
`open/sqlite-sequence-emulation-plan.md`) deckt nur die **deklarativen Attribute** ab —
`start`, `increment`, `minValue`, `maxValue`, `cycle`, `cache`.
Den **aktuellen Wert** (`last_value` in PG, `next_value` in
MySQL-Emulation als *nächster von `nextval()`-Rückgabewert*) migriert
keine Pipeline. Effekt:

- Operator migriert eine bestehende Tabelle mit `id` aus einer
  PG-Sequenz nach DB-Target. Die Sequenz wird mit
  `CREATE SEQUENCE … START WITH 1` neu angelegt. Bei einem
  späteren `INSERT` springt `nextval('seq')` auf 1 statt auf den
  vorherigen Wert + 1 → **PK-Konflikt** mit existierenden Zeilen,
  Migration scheitert beim ersten Schreibzugriff.

Das ist der Hauptgrund, warum d-migrate für
sequence-tragende Schemata in Produktion heute nicht
ohne manuelle Nachbearbeitung benutzt werden kann. Operatoren
müssen nach jeder Migration manuell `ALTER SEQUENCE … RESTART WITH
<observed-max>` ausführen.

---

## 2. Warum jetzt?

`preserveCurrentValue` ist die kritische Live-DB-Brücke, die
Sequence-Migrationen überhaupt erst produktionstauglich macht.
Der DDL-Generator-Pfad (`schema generate`) deckt Schema-Strukturen
ab, aber Migrationen brauchen **Daten-Bewusstsein** für Sequences
genauso wie Cast-Preflight für Spalten-Typänderungen
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
    Für diese Tranche ist `shouldProbeCreateSequence(op)` konservativ
    auf Rename-basierte `CreateSequence`-Fälle (`renameProvenance != null`)
    beschränkt; andere deterministische Wege werden in einem Folge-Slice ergänzt.
- Neuer `SequenceCurrentValueProbe`-Port in `hexagon:ports-read` mit
  dialect-spezifischer Implementierung:
  - **PG**: `SELECT last_value, is_called FROM <sequence_name>`
  - **MySQL** (Emulation): `SELECT next_value, managed_by, format_version FROM dmg_sequences
    WHERE name = <escaped_mysql_sequence_key>` (`next_value` ist der nächste von
    `nextval` gelieferte Wert). Die Zuordnung zu einem d-migrate verwalteten
    Emulations-Objekt erfolgt im Probe-Adapter über die Validierung von
    `managed_by` und `format_version` gegen `mysqlExpectedManagedBy`/`mysqlExpectedFormatVersions`.
    Für `dmg_sequences` ist der Lookup-Key deterministisch aus dem
    `SequenceObjectRef` abzuleiten (Name + stabiler Resolver auf schema/namespace),
    und dieselbe Funktion muss von der MySQL-Render- und Probe-Seite konsistent genutzt
    werden (`dmg_sequences` kennt keine weitere Disambiguierungsspalte). Der Read ist
    deterministisch nur, wenn exakt eine Zeile zurückkommt (`matchedRows == 1`).
  - **SQLite**: `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` bis die
    SQLite-Planung für `preserveCurrentValue` vorliegt (`open/sqlite-sequence-emulation-plan.md`).
- Neue Operation-Subtype:
  `AlterSequenceCurrentValue(pairId, probeSequenceRef, applySequenceRef, currentValue, isCalled: Boolean?, restoreValue: Long?, restoreIsCalled: Boolean?, rollbackImpossible: Boolean = false, rollbackImpossibleReason: String? = null, revertAfterRename: Boolean = false)`
  (kein Reuse von `AlterSequence`; Daten-statement-Renderer ist separat).
- Erweiterung: Für Reversibility wird zusätzlich ein optionales
  `restoreIsCalled: Boolean?` vorgesehen.
- Renderer-Pfade pro Dialekt:
  - **PG**: `SELECT setval('<sequence_name>', <value>, <is_called>)`
    als auszuführende Folgeanweisung (DML), wobei `<is_called>` vom Probe-Ergebnis
    übernommen wird.
  - **MySQL**: `UPDATE dmg_sequences SET next_value = <value>
    WHERE name = <escaped_mysql_sequence_key> AND managed_by IN (<mysqlExpectedManagedBy>) AND format_version IN (<mysqlExpectedFormatVersions>)`
    (kein +1).
  - **SQLite**: erst sobald der SQLite-Plan landet.
- Pipeline-Integration im `MigrationPreflightPlanner`-Flow vor
  `SchemaMigrateRenderPipeline` (`CheckPreflight` + `MigrationPreflightPlanner`):
  - Wenn `preserveCurrentValue = true` UND DB-Target verfügbar:
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
    probe-fähige Sequence-Kandidaten-Operation (`AlterSequence`, `RenameSequence`,
    `CreateSequence` mit `shouldProbeCreateSequence = true`) vorliegt, blockt der
    Plan mit `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` → `MANUAL_ACTION_REQUIRED`.
    `CreateSequence` mit `shouldProbeCreateSequence = false` bleibt im Datei-zu-Datei-Modus
    weiterhin `SEQUENCE_PRESERVE_NOT_FOUND` (INFO), da kein deterministischer
    Probe-Zustand vorhanden ist.
    Diese Blocker-Entscheidung hat Vorrang vor Dialekt-spezifischen
    `NOT_SUPPORTED_BY_DIALECT`-Diagnosen (z. B. SQLite), da kein stabiler Probe-Read
    in Dateiquell-Läufen möglich ist.
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
  und der Migration: wenn die App parallel während des
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

```kotlin
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

fun sequenceRefForRename(renameOp: DiffOperation.RenameSequence, name: String): SequenceObjectRef {
    // Deterministischer Resolver: Name aus Rename (fromName/toName), Namespace/Dialekt aus dem Rename-Kontext ableiten.
    return SequenceObjectRef.forRenameTarget(renameOp.objectRef, sequenceName = name) // to be implemented once in planner utilities
}

val sequencesNeedingPreservation = preserveSequenceCandidates.mapNotNull { op ->
        when (op) {
            is DiffOperation.AlterSequence -> SequencePreserveContext(
                sequenceOp = op,
                probeSequenceRef = op.objectRef,
                applySequenceRef = op.objectRef,
                pairId = "alter:${op.id}",
            )
            is DiffOperation.CreateSequence -> {
                if (!shouldProbeCreateSequence(op)) {
                    emitNote("SEQUENCE_PRESERVE_NOT_FOUND", "No existing target state for ${op.objectRef}; create value remains declarative.")
                    null
                } else {
                    SequencePreserveContext(
                        sequenceOp = op,
                        probeSequenceRef = op.objectRef,
                        applySequenceRef = op.objectRef,
                        pairId = "create:${op.id}",
                    )
                }
            }
            is DiffOperation.RenameSequence -> SequencePreserveContext(
                sequenceOp = op,
                probeSequenceRef = sequenceRefForRename(op, op.fromName),
                applySequenceRef = sequenceRefForRename(op, op.toName),
                pairId = "rename:${op.id}",
            )
            else -> throw IllegalArgumentException("unexpected sequence operation type")
        }
    }

    if (!request.execute) {
        // File-only / plan-only: Stage ist nicht aktiv.
    } else if (request.isFileToFileMode && sequencesNeedingPreservation.isNotEmpty()) {
        // Datei-zu-Datei kann keinen deterministischen Preflight ausführen;
        // nur probe-fähige Preservation-Operationen (Read-Pfad) werden geblockt.
        // Reine CreateSequence ohne lesbaren Vorzustand wurde bereits auf
        // `SEQUENCE_PRESERVE_NOT_FOUND` reduziert und landet hier nicht.
        // MANUAL_ACTION_REQUIRED geblockt.
        emitBlocker(
            "SEQUENCE_PRESERVE_REQUIRES_DB_TARGET",
            "preserveCurrentValue requires execute mode with a reachable target database.",
        )
    } else if (sequenceCurrentValueProbe == null) {
        for (ctx in sequencesNeedingPreservation) {
            emitNote(
                "SEQUENCE_PRESERVE_NOT_RUN_POLICY",
                "No sequence probe adapter configured for ${ctx.applySequenceRef}; preserve policy is active but probe/follow-up are intentionally skipped.",
            )
        }
    } else {
        val hasMysqlSequenceCandidates = sequencesNeedingPreservation.any { it.probeSequenceRef.dialect == Dialect.MYSQL }
        val mysqlExpectedManagedBy =
            if (hasMysqlSequenceCandidates) {
                mysqlSequenceEmulationMetadata.supportedManagedBy
            } else {
                emptyList<String>()
            }
        val mysqlExpectedFormatVersions =
            if (hasMysqlSequenceCandidates) {
                mysqlSequenceEmulationMetadata.supportedFormatVersions
            } else {
                emptyList<Int>()
            }
        if (hasMysqlSequenceCandidates && mysqlExpectedManagedBy.isEmpty()) {
            emitBlocker(
                "SEQUENCE_PRESERVE_CONFIG_INVALID",
                "MySQL sequence metadata is missing supported managed-by markers; cannot perform a deterministic preserve run.",
            )
            return
        }
        if (hasMysqlSequenceCandidates && mysqlExpectedFormatVersions.isEmpty()) {
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
            probeSequenceRef: SequenceObjectRef,
            readResult: SequenceCurrentValueProbeResult.Read,
        ): RestoreHints {
            // Restore-Hinweise deterministisch ableiten; Probe ist primäre Quelle.
            // Restore value is always derived from the deterministic probe snapshot.
            val restoreValueHint = readResult.value
            val restoreIsCalledHint = when (probeSequenceRef.dialect) {
                Dialect.POSTGRESQL -> readResult.isCalled
                else -> null // Nicht-PG-Dialekte benötigen restoreIsCalled derzeit nicht.
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
                Dialect.POSTGRESQL -> {
                    if (readResult.isCalled == null) {
                        emitBlocker("SEQUENCE_PRESERVE_PROBE_FAILED", "PG-Probe muss is_called liefern")
                        continue
                    }
                    readResult.isCalled
                }
                else -> null
            }
            val restoreHints = resolveRestoreHints(ctx.probeSequenceRef, readResult)
            val isRollbackPossible = restoreHints.restoreValueHint != null && (ctx.probeSequenceRef.dialect != Dialect.POSTGRESQL || restoreHints.restoreIsCalledHint != null)
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
- Für PG wird `restoreIsCalled` aus `readResult.isCalled` geliefert.
- Für Nicht-PG bleibt `restoreIsCalled` aktuell `null`, weil es dort für die
  Reversibilität nicht benötigt wird.
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
`pairId` ist beziehungsunabhängig eindeutig (z. B. `alter:<op.id>`, `rename:<op.id>`),
um Kollisionen bei identischen Sequenznamen in unterschiedlichen Schema-Kontexten
oder mehrfachen Rename-Schritten auszuschließen.

### 5.4 Dialekt-Render-Matrix

| Dialekt | Render |
|---|---|
| PG | `SELECT setval('<seq>', <value>, <isCalled>);` |
| MySQL | `UPDATE dmg_sequences SET next_value = <value> WHERE name = <escaped_mysql_sequence_key> AND managed_by IN (<mysqlExpectedManagedBy>) AND format_version IN (<mysqlExpectedFormatVersions>);` |
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

| Sub-Slice | Inhalt | Commit |
|---|---|---|
| A | `preserveCurrentValue`-Feld + YAML-Codec + `SequenceObjectRef`-Werttyp + `SequenceCurrentValueProbe`-Port + `AlterSequenceCurrentValue`-Subtyp **+ PG-Renderer (`setval`) + MySQL-Renderer (`UPDATE dmg_sequences`) + SQLite-Block (`DIALECT_UNSUPPORTED_OPERATION`)** | `29ade761` (+ Refactor `12f4b812` für `SUPPORTED_MANAGED_BY: Set`) |
| B | PG-Probe-Adapter (JDBC live-DB read) | `c93e44ef` |
| C | MySQL-Probe-Adapter (JDBC live-DB read, inkl. `mysqlSequenceLookupKey`-Helper) | `4eb0f525` (+ Test-Fix `86f188a6` für testcontainers-Permission) |
| D | Pipeline-Integration im `MigrationPreflightPlanner`-Flow vor Render (probe → emit) | `257908fd` |
| E | Schema-Doku + Closing | *this commit* |

SQLite folgt aus `open/sqlite-sequence-emulation-plan.md`.

**Re-Schnitt-Begründung (2026-05-21):** Der ursprüngliche Schnitt
deferred die PG-/MySQL-Renderer nach B/C. Das ist nicht haltbar,
weil `AlterSequenceCurrentValue` als neuer Subtyp in der sealed
`DiffOperation`-Hierarchie ALLE drei Dialekt-Diff-DDL-Generatoren
zwingt, eine Branch zu haben (exhaustiver `categorize`-Switch).
„UNSUPPORTED-Routing als Stopgap bis B/C" wäre ein Carve-out im
Produktionscode — Reviewer könnten echte vs. transitorische
UNSUPPORTED-Routings nicht mehr unterscheiden. Daher ziehen die
PG-/MySQL-Renderer in dieselbe Tranche wie der DiffOp-Subtyp; B/C
reduzieren sich auf die echten Live-DB-JDBC-Probes.

### 6.1 Sub-Slice A — Foundations + dialect render paths (Detail-DoD)

Sub-Slice A liefert die Datentypen UND die Renderer-Pfade für alle
drei Dialekte — ohne Planner-Emit und ohne Live-DB-Probe-Adapter (das
kommt in B/C/D). Die Renderer landen in derselben Tranche wie der
DiffOp-Subtyp, weil der Strukturzwang das verlangt (siehe §6
Re-Schnitt-Begründung).

**Artefakte (Produktionscode):**

- `SequenceDefinition.preserveCurrentValue: Boolean = false` (default-additiv,
  rückwärtskompatibel) in `hexagon/core/.../model/SequenceDefinition.kt`.
- YAML-Codec für das neue Feld in
  `adapters/driven/formats/.../SchemaNodeProgrammabilityParsers.kt` (read+write;
  fehlendes Feld → `false`). Ohne den Codec ist `preserveCurrentValue` aus
  User-Schemas unerreichbar — A ist erst dann nutzbar.
- `SequenceObjectRef` als neuer Werttyp in
  `hexagon/core/.../diff/migration/SequenceObjectRef.kt` mit Feldern für
  Sequenz-Name, optionalem Schema/Namespace und `RenameProjectionDialect`
  (Core-local boundary-Mirror; Application-Layer mapped
  `DatabaseDialect` → `RenameProjectionDialect` am Planner-Boundary,
  identisch zum F.4 Rename- und Trigger-Planning-Pfad).
  Wird in §5.1 (Probe-Port) und §5.3 (`AlterSequenceCurrentValue`)
  verwendet — muss daher zusammen mit Port + DiffOp landen, sonst sind
  beide nicht baubar.
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
- **PG-Renderer**: `PostgresDiffSequenceOps.renderAlterSequenceCurrentValue`
  emittiert `SELECT setval('<seq>', <value>, <isCalled>)` als
  ausführbare Folgeanweisung (DML). `AlterSequenceCurrentValue` landet
  in `OpCategory.SEQUENCE`, `renderSequenceOp` dispatched darauf.
- **MySQL-Renderer**: `MysqlDiffSequenceOps.renderAlterSequenceCurrentValue`
  emittiert `UPDATE dmg_sequences SET next_value = <value> WHERE name =
  <escaped> AND managed_by IN (<mysqlExpectedManagedBy>) AND format_version IN
  (<mysqlExpectedFormatVersions>)`. `AlterSequenceCurrentValue` landet
  in `OpCategory.SEQUENCE`, `renderSequenceOp` dispatched darauf.
  (`mysqlSequenceLookupKey`-Helper wird hier eingeführt — wird von C
  beim Probe-Adapter wiederverwendet.)
- **SQLite-Routing**: `AlterSequenceCurrentValue` landet in
  `OpCategory.UNSUPPORTED` → `DIALECT_UNSUPPORTED_OPERATION`. **Echter
  Endzustand** (SQLite hat keine Sequence-Emulation; das ändert sich
  erst, wenn `open/sqlite-sequence-emulation-plan.md` umgesetzt wird).
  Kein „kommt später"-Carve-out.

**Artefakte (Tests):**

- `SequenceDefinitionTest` — Default-Wert pinned, `copy()`-Roundtrip.
- YAML-Codec-Roundtrip-Test (eigene Test-Datei) — missing field →
  `false`, `true`/`false` echtes Mapping, `false` auf Write-Seite elided.
- `SequenceObjectRefTest` — Konstruktion, Equality, Dialect-Branching.
- `SequenceCurrentValueProbeTest` — Sealed-Class-Shape: alle vier
  Subtypen konstruierbar, equals/hashCode für `Read`/`Failed`.
- `DiffOperationDefaultsTest` (extend) — `AlterSequenceCurrentValue`-Konstruktion +
  `withDependencies`/`withId`-Invarianten.
- **PG-Renderer-Test**: `PostgresDiffSequenceOpsTest` (oder eigene
  Datei) — pinnt `setval`-Output für `isCalled=true`/`false` und
  korrektes Quoting des Sequenznamens.
- **MySQL-Renderer-Test**: `MysqlDiffSequenceOpsTest` — pinnt
  `UPDATE dmg_sequences`-Output inkl. der `managed_by`-/`format_version`-
  Filter und escape des `name`-Literals.
- **SQLite-Renderer-Test**: pinnt, dass `AlterSequenceCurrentValue`
  zu `DIALECT_UNSUPPORTED_OPERATION` führt (Endzustand-Check).

**Definition of Done (A):**

- [x] `SequenceDefinition.preserveCurrentValue` existiert mit Default
      `false`; bestehende Code-Pfade kompilieren ohne Änderung.
- [x] YAML-Roundtrip pinnt: fehlendes Feld → `false`, `true`/`false`
      werden gelesen/geschrieben.
- [x] `SequenceObjectRef` ist als eigener Werttyp angelegt (nicht
      Alias auf `DiffObjectRef`); Dialekt-Feld ist
      `RenameProjectionDialect` (`hexagon:core` darf nicht von
      `ports-common`'s `DatabaseDialect` abhängen — Application-Layer
      mapped am Planner-Boundary).
- [x] `SequenceCurrentValueProbe`-Port kompiliert; sealed
      `SequenceCurrentValueProbeResult` deckt alle vier Outcomes
      ohne `else`-Zweige in Tests ab.
- [x] `AlterSequenceCurrentValue` ist als `DiffOperation`-Subtyp
      konstruierbar mit allen Feldern aus §3.1/§5.3.
- [x] **PG-Diff-DDL-Generator** rendert `AlterSequenceCurrentValue`
      als `SELECT setval('<seq>', <value>, <isCalled>)`. `isCalled`
      muss aus dem DiffOp-Feld propagiert werden (PG verlangt es).
- [x] **MySQL-Diff-DDL-Generator** rendert `AlterSequenceCurrentValue`
      als `UPDATE dmg_sequences SET next_value = <value> WHERE name =
      <escaped> AND managed_by IN (<mysqlExpectedManagedBy>) AND format_version IN
      (<mysqlExpectedFormatVersions>)`. Statement betrifft exakt eine
      Zeile (`expectedAffectedRows = 1`).
- [x] **SQLite-Diff-DDL-Generator** mapped
      `AlterSequenceCurrentValue` deterministisch auf
      `DIALECT_UNSUPPORTED_OPERATION` — Endzustand, kein
      Carve-out-Kommentar im Code.
- [x] Kein Planner-Emit (`AlterSequenceCurrentValue` kommt nicht von
      `DiffPlanner`), kein Live-DB-Probe-Adapter in A. Die Renderer
      sind über synthetische DiffResult-Inputs unit-getestet.
- [x] Tests laufen via `make docker-test MODULES=":hexagon:core
      :hexagon:ports-read :adapters:driven:formats
      :adapters:driven:driver-postgresql :adapters:driven:driver-mysql
      :adapters:driven:driver-sqlite"` grün.
- [x] `make docker-coverage-gate` grün (kein Coverage-Regression auf
      bestehende Module).

**Bewusst nicht in A:**

- Diagnose-Codes (`SEQUENCE_PRESERVE_*`) — landen erst, wo sie tatsächlich
  emittiert werden (D).
- `PlannerBlockerClassifier`-Mapping — gehört zur Diagnose-Emission, in D.
- **Live-DB-JDBC-Probe-Adapter** (PG → `SELECT last_value, is_called
  FROM <seq>`; MySQL → `SELECT next_value, managed_by, format_version
  FROM dmg_sequences ...`) — in B (PG) und C (MySQL).
- `MigrationPreflightPlanner`-Erweiterung (probe → emit
  `AlterSequenceCurrentValue`) — D.
- File-target-Blocker und Plan-Artefakt-Integration — D; Schema-Doku — E.

### 6.2 Sub-Slice B — PG-Probe-Adapter (Detail-DoD)

Sub-Slice B liefert die JDBC-Implementierung der
[`SequenceCurrentValueProbe`] aus Sub-Slice A für PostgreSQL.
Reine Read-Side-Arbeit — keine Renderer-Änderungen (die sind in A
erledigt), kein Planner-Emit (das ist D).

**Artefakt (Produktionscode):**

- `adapters/driven/driver-postgresql/.../PostgresSequenceCurrentValueProbe.kt`
  *(neu)*: implementiert
  `SequenceCurrentValueProbe.probe(connection, sequenceRef)`.
  Query-Shape:
  `SELECT last_value, is_called FROM <schema>.<sequence_name>`
  (quoted via `SqlIdentifiers.quoteQualifiedIdentifier` für
  `<schema>.<name>`-Form; ohne Schema unquoted-resolved über die
  Connection's `search_path`).
  Mapping pro SQLSTATE:
  - `42P01` *undefined_table*: Sequenz existiert nicht → `NotFound`.
  - `42501` *insufficient_privilege*: Lese-Rechte fehlen →
    `Failed("PROBE_PERMISSION_DENIED", …)`.
  - Andere `SQLException`: → `Failed("PROBE_QUERY_FAILED", …)`.
  Bei `Read`-Outcome: `isCalled` MUSS aus dem `is_called`-Spaltenwert
  gefüllt werden (PG-Probe-Vertrag).

**Artefakt (Tests):**

- `PostgresSequenceCurrentValueProbeTest` (Unit, mit JDBC-Mock):
  pinnt das Query-SQL für mit/ohne Schema-Qualifier, das Mapping der
  drei SQLSTATEs, und dass `isCalled` korrekt aus dem ResultSet
  propagiert wird.
- `PostgresSequenceCurrentValueProbeIntegrationTest` (live PG via
  testcontainers): pinnt End-to-End gegen eine echte Sequenz —
  initial nach `CREATE SEQUENCE` (is_called=false), nach `nextval`
  (is_called=true, last_value=1), und MISSING bei nicht-existierender
  Sequenz. Lauf via `make integration` (`-PintegrationTests`),
  konsistent mit dem `MysqlSequenceCanonicityProbeIntegrationTest`-
  Pattern aus dem Drift-Check-Workstream.

**Definition of Done (B):**

- [x] `PostgresSequenceCurrentValueProbe` implementiert
      `SequenceCurrentValueProbe` und gibt für eine existierende
      Sequenz `Read(value, matchedRows=1, isCalled=...)` zurück.
- [x] Sequenz nicht vorhanden (SQLSTATE `42P01`) → `NotFound`.
- [x] Lese-Recht fehlt (SQLSTATE `42501`) →
      `Failed("PROBE_PERMISSION_DENIED", message)`.
- [x] Andere `SQLException` → `Failed("PROBE_QUERY_FAILED", message)`.
- [x] Schema-qualifizierte Sequenzen werden korrekt gequoted; ohne
      Schema-Angabe bleibt der Lookup unqualifiziert (PG resolved
      via `search_path`).
- [x] Unit-Test mit JDBC-Mock pinnt das Query-SQL und die drei
      SQLSTATE-Branches.
- [x] Integration-Test (`make integration`) pinnt End-to-End gegen
      einen echten PostgreSQL-Container.
- [x] Kein Planner-Emit, kein CLI-Wiring in B. Der Probe-Adapter ist
      noch nicht in `SchemaMigrateCommand` gewired — das passiert in
      D.
- [x] `make docker-test MODULES=":adapters:driven:driver-postgresql"`
      grün.
- [x] `make docker-coverage-gate` grün.

**Bewusst nicht in B:**

- MySQL-Probe-Adapter — in C.
- Pipeline-Integration / Planner-Emit — in D.
- CLI-Wiring des Probes auf `SchemaMigrateCommand` — in D.
- Diagnose-Codes (`SEQUENCE_PRESERVE_PROBE_FAILED` etc.) — in D.

### 6.3 Sub-Slice C — MySQL-Probe-Adapter (Detail-DoD)

Sub-Slice C liefert die JDBC-Implementierung der
[`SequenceCurrentValueProbe`] für MySQL gegen die Helper-Table-
Emulation (`dmg_sequences`). Reine Read-Side-Arbeit — Renderer ist
in A erledigt, Planner-Emit in D, kein CLI-Wiring.

**Wichtige Unterschiede zu B (PG):**

- Query operiert auf `dmg_sequences` (Helper-Table), nicht direkt
  auf einer Sequence-Relation.
- `managed_by` und `format_version` werden geprüft, damit
  operator-eingefügte Zeilen nicht versehentlich als preserve-Target
  surfaced werden. Mismatch → `Failed`, nicht `Read`.
- MySQL hat kein `is_called` — `Read.isCalled` bleibt `null`.
- Das Fehlen der `dmg_sequences`-Tabelle (Helper-Table noch nicht
  gebootstrappt) ist ein Read-State, kein Konfigurationsfehler:
  mapped zu `NotFound`, sodass `CreateSequence`-Pfade (welche die
  Helper-Table erst aufsetzen) sauber durchlaufen.

**Artefakt (Produktionscode):**

- `adapters/driven/driver-mysql/.../MysqlSequenceCurrentValueProbe.kt`
  *(neu)*: implementiert `SequenceCurrentValueProbe.probe(connection,
  sequenceRef)`. Query-Shape:
  ```sql
  SELECT `next_value`, `managed_by`, `format_version`
  FROM `dmg_sequences`
  WHERE `name` = '<lookupKey>'
  ```
  (Filter auf `managed_by` / `format_version` passieren in Kotlin,
  damit ein `managed_by` außerhalb von `supportedManagedBy` als strukturierter
  `Failed` surfaced, nicht als false-positive `NotFound`.)
  Der `lookupKey` kommt deterministisch aus
  `MysqlSequenceSupportNaming.lookupKey(sequenceRef)` (siehe A).
  Mapping pro MySQL-Error-Code:
  - `1146` *ER_NO_SUCH_TABLE* (`dmg_sequences` existiert nicht) →
    `NotFound` (Helper-Table-Bootstrap fehlt noch).
  - `1142` *ER_TABLEACCESS_DENIED_ERROR* (SELECT-Recht fehlt) →
    `Failed("PROBE_PERMISSION_DENIED", …)`.
  - Andere `SQLException` → `Failed("PROBE_QUERY_FAILED", …)`.
  - ResultSet leer → `NotFound` (Sequence-Row nicht in Helper-Table).
  - Row gefunden aber `managed_by` außerhalb von `supportedManagedBy` →
    `Failed("PROBE_UNMANAGED_ROW", …)`.
  - Row gefunden aber `format_version` nicht in
    `SUPPORTED_FORMAT_VERSIONS` →
    `Failed("PROBE_UNKNOWN_FORMAT_VERSION", …)`.
  - Mehr als 1 Row (defensiv; PK auf `name` macht das unmöglich) →
    `Failed("PROBE_AMBIGUOUS_ROW", …)`.

**Artefakt (Tests):**

- `MysqlSequenceCurrentValueProbeTest` (Unit mit JDBC-Mock): pinnt
  Query-SQL inkl. Lookup-Key, alle sechs Outcome-Branches
  (Read, NotFound × 2 (table missing, row missing), Failed × 4
  (permission, unmanaged, unknown-format-version, query-failed)),
  und Statement/ResultSet-close-Garantien.
- `MysqlSequenceCurrentValueProbeIntegrationTest` (live MySQL via
  testcontainers): pinnt End-to-End gegen `dmg_sequences` —
  bootstrapped Helper-Table + frische Row → Read; Row nach
  `dmg_nextval()` → Read mit hochgezähltem `next_value`; gelöschte
  Row → NotFound; nicht-existente `dmg_sequences` → NotFound;
  unmanaged-row (manuelles INSERT mit `managed_by = 'other'`) →
  Failed(`PROBE_UNMANAGED_ROW`). Lauf via `make integration`.

**Definition of Done (C):**

- [x] `MysqlSequenceCurrentValueProbe` implementiert
      `SequenceCurrentValueProbe` und gibt für eine d-migrate-managed
      Helper-Table-Row `Read(value=next_value, matchedRows=1,
      isCalled=null, managedBy in `supportedManagedBy`, formatVersion=…)` zurück.
- [x] `dmg_sequences` nicht vorhanden (MySQL error 1146) → `NotFound`.
- [x] Sequence-Row nicht vorhanden (0-row SELECT) → `NotFound`.
- [x] Lese-Recht fehlt (MySQL error 1142) →
      `Failed("PROBE_PERMISSION_DENIED", …)`.
- [x] `managed_by` außerhalb von `supportedManagedBy` → `Failed("PROBE_UNMANAGED_ROW", …)`.
- [x] `format_version` außerhalb
      `MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS` →
      `Failed("PROBE_UNKNOWN_FORMAT_VERSION", …)`.
- [x] `lookupKey` wird über `MysqlSequenceSupportNaming.lookupKey`
      ermittelt (single source of truth mit dem A-Renderer).
- [x] Unit-Test mit JDBC-Mock pinnt das Query-SQL und alle Error-
      Branches.
- [x] Integration-Test (`make integration`) pinnt End-to-End gegen
      einen echten MySQL-Container, inkl. Bootstrap → Read,
      `dmg_nextval` → Read, unmanaged-row → Failed.
- [x] Kein Planner-Emit, kein CLI-Wiring in C.
- [x] `make docker-test MODULES=":adapters:driven:driver-mysql
      :test:integration-mysql"` grün.
- [x] `make docker-coverage-gate` grün.

**Bewusst nicht in C:**

- Pipeline-Integration / Planner-Emit — in D.
- CLI-Wiring des Probes — in D.
- Diagnose-Codes (`SEQUENCE_PRESERVE_PROBE_FAILED` etc.) — in D.
- File-target-Blocker — in D.

### 6.4 Sub-Slice D — Pipeline-Integration + Planner-Emit (Detail-DoD)

Sub-Slice D verdrahtet die in A/B/C entstandenen Foundations und
Probe-Adapter zur ersten produktiv-nutzbaren Pipeline:

```
plan(initial)
   → SequencePreserveStage.run(probeFn, request, target, dialect, plan)
       → for each candidate parent op:
            (pre-probe gate?) → probe → emit follow-up or diagnostic
   → augmented plan (operations + follow-ups + dependencies)
   → render
```

D ist die größte Tranche der Workstream-Folge: Stage,
Probe-Wiring (PG + MySQL Runner + dispatcher in
`SchemaMigrateCommand`), Plan-Augmentation, Diagnose-Code-
Emission inkl. `PlannerBlockerClassifier`-Mapping, und die
Rename-Down-Ordering aus §5.2 / §7. E (Schema-Doku + Closing)
bleibt absichtlich separat, damit D in einem reviewbaren Schritt
landet.

**Was D NICHT macht** (siehe §6 Re-Schnitt für Begründung):

- Keine Renderer-Änderungen — die sind in A erledigt.
- Keine Probe-Adapter-Logik — die kommen aus B (PG) und C (MySQL).
- Keine User-/Schema-Doku → in E.

#### 6.4.1 Kandidatenfilter (vor Probe)

Bevor der Stage probt, filtert er die Sequence-Op-Kandidaten nach
Plan-Doc §3.1:

- **`AlterSequence`**: Kandidat, wenn die Quelle der Op eine Sequenz mit
  `preserveCurrentValue = true` ist (aus der `before`- oder `after`-
  `SequenceDefinition`, je nach Direction).
- **`CreateSequence`**: Kandidat ALS Probe-Kandidat **nur**, wenn
  `shouldProbeCreateSequence(op) = true` (Plan-Doc §5.2). Das ist
  ein Pre-Probe-Gate: heute kann der Planner nicht deterministisch
  feststellen, ob eine `CreateSequence` einen pre-existing Vorzustand
  am Target hat. In dieser Tranche ist die Funktion konservativ:
  - Wenn `op.renameProvenance != null` (Create-aus-Rename-Fallback) →
    `true` (es gibt einen Vorzustand unter altem Namen).
  - Sonst → `false`, INFO-Diagnose `SEQUENCE_PRESERVE_NOT_FOUND` mit
    dem Plan-Doc-§3.1-Hinweis, dass der Current-Value-Restore als
    `ROLLBACK_NOT_POSSIBLE` zu behandeln ist.
  Ein zukünftiger Folge-Slice kann `shouldProbeCreateSequence` zu
  einer echten Target-Probe erweitern (z.B. „existiert die Sequenz
  unter diesem Namen schon"); für jetzt ist die deterministische
  Konservativität wichtiger als Vollständigkeit.
- **`RenameSequence`**: Kandidat, wenn `shouldProbeRenameSequence(op) =
  true`. Definiert als: Rename-Quelle ist eine verwaltete Sequenz mit
  `preserveCurrentValue = true` UND die Zuordnung ist eindeutig
  (kein Drop+Create-Fallback). Für Drop+Create-Fallbacks erzeugt der
  Renamer ohnehin separate `DropSequence` + `CreateSequence` ops, die
  über ihre eigenen Branches laufen.
- **`DropSequence`**: **Kein Kandidat.** Plan-Doc §3.1 listet Drop
  nicht in der Kandidaten-Tabelle. Drop entfernt das Target ohnehin;
  ein Follow-up-Setval/Update wäre semantisch leer. Der Stage filtert
  Drop-Ops vor jeder Probe-Entscheidung raus.

Plan ohne Kandidaten nach diesem Filter → Stage `NotRun`, kein
Probe-Aufruf.

#### 6.4.2 Probe-Fn-Vertrag

Der Per-Op-Probe-Aufruf läuft über folgende Funktionssignatur (analog
zum Drift-Check-Pattern; `MysqlSequenceCanonicityProbeFn` ist die
Vorlage):

```kotlin
// hexagon:application
typealias SequenceCurrentValueProbeFn = (
    target: CompareOperand.Database,
    configPath: Path?,
    sequenceRef: SequenceObjectRef,
) -> SequenceCurrentValueProbeResult
```

`SchemaMigrateRunner` bekommt EINEN Parameter
`sequenceCurrentValueProbe: SequenceCurrentValueProbeFn? = null`;
der CLI-Adapter resolvt das pro Dialekt über einen
`SequenceCurrentValueProbeDispatcher` (siehe CLI-Wiring unten). So
bleibt die Runner-Signatur kompakt und der Per-Dialekt-Resolver lebt
am Boundary, wo er hingehört.

Per-Op-Probe (nicht batch): identisch zur Drift-Check-Praxis und
Plan-Doc §10 („Op-by-op Probe ist OK; in der Praxis sind Sequence-Ops
selten so zahlreich, dass eine Batch-Probe nötig wäre").

#### 6.4.3 Stage-Skip-Pfade

`SequencePreserveStage.run(...)` priorisiert den File-Target-Blocker
vor allen anderen Skip-Pfaden — eine Probe gegen ein File-Target ist
strukturell nicht möglich, also fängt der Stage das BEVOR andere
Routing-Entscheidungen anfallen:

1. **File-Target + preserve-Kandidaten → Failed(`SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`)**.
   Sobald mindestens eine probe-fähige Kandidat-Op (`AlterSequence`,
   `RenameSequence`, `CreateSequence` mit `shouldProbeCreateSequence
   = true`) vorliegt UND `target !is CompareOperand.Database`, blockt
   der Stage per Kandidat mit `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`
   → `MANUAL_ACTION_REQUIRED`.
   Diese Priorisierung gilt unabhängig vom `--execute`-Flag: für
   `--execute` + File-Target greift `SchemaMigratePreparation`
   bereits am CLI-Boundary mit Exit 2, daher fällt dieser Branch in
   der Praxis vor allem für `--plan-only`-Läufe mit File-Target
   und `preserveCurrentValue = true` an — und verhindert dort
   einen still-unvollständigen Migrationsplan (ohne preserve-
   Follow-ups). Vorrang über Dialekt-`NOT_SUPPORTED_BY_DIALECT`,
   weil ohne Live-DB kein Probe entscheiden kann ob die Operation
   überhaupt zustande käme.
2. **File-Target ohne Kandidaten → `NotRun`** ohne Diagnose.

Ist der File-Target-Pfad nicht aktiv (target ist DB), gibt es diese
weiteren Outcomes:

- `!request.execute` mit DB-Target → `NotRun`. Plan-only gegen DB
  öffnet keine Probe-Connection ohne Operator-Intent (analog zum
  Drift-Check-Stage). Der ggf. unvollständige Plan ist hier explizit
  in Ordnung — der Operator hat das DB-Target gesetzt und sieht,
  dass es kein `--execute` ist.
- `dialect ∉ {POSTGRESQL, MYSQL}` — z.B. SQLite: pro Kandidat-Op wird
  eine Block-Diagnose `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`
  emittiert; **die Probe wird gar nicht aufgerufen** (es gibt für
  SQLite keine Probe-Fn). Damit ist die ehemals doppelte SQLite-
  Routing-Tabelle aus dem ursprünglichen §6.4-Draft aufgelöst:
  ausschließlich der Stage-Skip-Pfad triggert die Block-Diagnose;
  der Emitter sieht für SQLite nie eine Probe-Result-Eingabe.
- `sequenceCurrentValueProbe == null` — keine Probe-Fn wired (z.B.
  reine Unit-Tests, Test-DI mit Null-Probe oder fehlende Test-DB-Mocks). Emittiert
  pro Kandidat-Op eine non-blocking INFO-Diagnose `SEQUENCE_PRESERVE_NOT_RUN_POLICY`.
  Die Preserve-Policy ist aktiv, aber es wird bewusst kein Probe/Follow-up
  ausgeführt.
  Dieser Pfad ist absichtlich nur für Test-/Fallback-Szenarien vorgesehen.
  In echten `--execute`-Produktivpfaden ist über `SchemaMigrateCommand` ein
  Probe-Dispatcher zu verdrahten; hier darf dieser Branch nicht mehr erreicht
  werden.
- Keine Kandidat-Op nach §6.4.1-Filter — `NotRun` ohne Diagnose.

#### 6.4.4 Stage-Initial-Check (vor dem ersten Probe-Aufruf)

`MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS.isEmpty()` bei vorhandenen
MySQL-Kandidaten-Operations → der Stage emittiert einen einzelnen
Top-Level-`SEQUENCE_PRESERVE_CONFIG_INVALID`-Blocker mit
`MANUAL_ACTION_REQUIRED` und kehrt mit `Failed` zurück, OHNE irgendeinen
Probe-Aufruf zu öffnen. Die Konstante ist heute hartkodiert und nie leer;
der Check ist defensiv gegen eine zukünftige Refactor-Regression, in der die
Konstante zur Config-getriebenen Quelle wird.
Bei reinen PG- oder SQLite-Preserver-Kandidaten ohne MySQL-Anteile darf dieser
Check nicht greifen.
Lokation: ganz am Anfang des Stage-`run(...)`-Bodies, nach den
Skip-Pfaden aus §6.4.3.

#### 6.4.5 Routing-Tabelle (Stage-intern, keine eigene Datei)

Statt einer separaten `SequencePreserveEmitter`-Datei in `hexagon:core`
(was nicht ginge, weil `SequenceCurrentValueProbeResult` in
`hexagon:ports-read` lebt und `hexagon:core` von dort nicht abhängt)
und eines separaten `SequencePreservePlanInjector` lebt die Routing-
und Augmentations-Logik als **private Funktion innerhalb von
`SequencePreserveStage`**:

```kotlin
private fun routeProbeResult(
    parentOp: DiffOperation,
    probeResult: SequenceCurrentValueProbeResult,
): RouteOutcome  // sealed: FollowUp(op), Info(diag), Block(diag)
```

Tabelle pro `(parentOp, probeResult)`:

| Parent-Op | Probe-Result | Outcome |
|---|---|---|
| `AlterSequence` (Kandidat) | `Read(value, matchedRows = 1)` | `FollowUp(AlterSequenceCurrentValue(…))` |
| `AlterSequence` (Kandidat) | `Read(value, matchedRows != 1)` | `Block(SEQUENCE_PRESERVE_PROBE_FAILED)` |
| `AlterSequence` (Kandidat) | `NotFound` | `Block(SEQUENCE_PRESERVE_PROBE_FAILED)` — Plan erwartete einen deterministischen Vorzustand. |
| `AlterSequence` (Kandidat) | `Failed(code, message)` | `Block(SEQUENCE_PRESERVE_PROBE_FAILED)` — Code im Message. |
| `RenameSequence` (Kandidat) | `Read(value, matchedRows = 1)` | `FollowUp(AlterSequenceCurrentValue(probe=fromRef, apply=toRef, revertAfterRename=true, …))` |
| `RenameSequence` (Kandidat) | `Read(value, matchedRows != 1)` | `Block(SEQUENCE_PRESERVE_PROBE_FAILED)` |
| `RenameSequence` (Kandidat) | `NotFound` | `Block(SEQUENCE_PRESERVE_PROBE_FAILED)` |
| `RenameSequence` (Kandidat) | `Failed(...)` | `Block(SEQUENCE_PRESERVE_PROBE_FAILED)`. |
| `CreateSequence` mit `shouldProbeCreateSequence = true` | `Read(value, matchedRows = 1)` | `FollowUp(AlterSequenceCurrentValue(probe=apply=op.ref, …))` |
| `CreateSequence` mit `shouldProbeCreateSequence = true` | `Read(value, matchedRows != 1)` | `Block(SEQUENCE_PRESERVE_PROBE_FAILED)` |
| `CreateSequence` mit `shouldProbeCreateSequence = true` | `NotFound` | `Info(SEQUENCE_PRESERVE_NOT_FOUND)` — nur Create-Path: kein deterministischer Vorzustand, daher kein Blocker; Current-Value-Restore ist `ROLLBACK_NOT_POSSIBLE` zu dokumentieren. |
| `CreateSequence` mit `shouldProbeCreateSequence = true` | `Failed(...)` | `Block(SEQUENCE_PRESERVE_PROBE_FAILED)`. |
| `DropSequence` | — (nicht Kandidat) | — (Stage filtert raus, kommt nicht in die Routing-Tabelle) |
| SQLite (jede Op) | — (Stage-Skip greift vorher) | — (Stage-Skip-Pfad emittiert Block, nicht Routing) |

`Read`-Outcome → der Stage baut den `AlterSequenceCurrentValue`-Follow-up
inkl. `pairId`, `probeSequenceRef`, `applySequenceRef`, `currentValue`,
`isCalled` (PG: aus Probe; MySQL/SQLite: `null`), Restore-Hints aus
Plan-Doc §5.2 `resolveRestoreHints`. `revertAfterRename = true` nur
für Rename-Op-Kandidaten. Defaults für `rollbackImpossible` /
`rollbackImpossibleReason` aus den Restore-Hints.

#### 6.4.6 Plan-Augmentation

Folge-Ops werden direkt am Ende des Stage-`run(...)`-Aufrufs in die
`DiffResult.operations`-Liste eingewoben:

- Reihenfolge: jede `AlterSequenceCurrentValue`-Op landet direkt
  hinter ihrer parent-Op im `operations`-Stream
  (`operations.subList(0, parentIdx+1) + followUp +
  operations.subList(parentIdx+1, ...)`).
- Dependencies des Follow-ups: `dependencies = setOf(parentOp.id)`,
  damit ein zukünftiger Top-Sort die Reihenfolge nicht verletzt.
- Down-Reihenfolge bei `RenameSequence` mit `revertAfterRename = true`:
  Plan-Doc §5.2 verlangt `RenameSequence-Down` VOR
  `AlterSequenceCurrentValue-Down` mit gleichem `pairId`. Das wird
  durch das Down-Renderer-Ordering im Diff-DDL-Generator umgesetzt
  (nicht im Stage); der Stage stellt nur sicher, dass `pairId` und
  `revertAfterRename` korrekt gesetzt sind. Down-Ordering-Check
  separat in der zugehörigen Down-Render-Test-Suite.

#### 6.4.7 Plan-Artefakt-Interaktion (F.4 G.2)

`maybeWritePlanArtefact(request, plan, …)` in `SchemaMigrateRunner`
heute mit dem PRE-Render-`plan` aufgerufen. Mit D muss der
`plan`-Parameter durch das vom Stage AUGMENTIERTE `DiffResult`
ersetzt werden, BEVOR `maybeWritePlanArtefact` läuft. Sonst zeigt das
signierte `migration-plan.v1`-Artefakt nicht den tatsächlich
auszuführenden Op-Stream — ein Operator, der nur das Artefakt liest,
würde `setval`/`UPDATE`-Statements übersehen.

Implementierungs-Punkt: `SchemaMigrateRunner.execute(...)` ruft
`renderPipeline.run(...)` auf; die Pipeline hält den augmentierten
Plan; der Runner verwendet diesen für Plan-Artefakt UND für Report.
Aktuell verzweigt die Plan-Artefakt-Schreib-Logik zwischen Blocker-
Pfad und normalem Pfad — beide müssen denselben augmentierten Plan
sehen.

#### Artefakte (Produktionscode)

**Stage + Routing:**

- `hexagon/application/.../cli/commands/SequencePreserveStage.kt`
  *(neu)*: analog zu `MysqlSequenceCanonicityStage`. Drei-State-
  Outcome (`Succeeded(augmentedPlan)` / `Failed(message, diagnostics)`
  / `NotRun`). Enthält:
  - Kandidaten-Filter aus §6.4.1
  - Skip-Pfade aus §6.4.3
  - Initial-Check aus §6.4.4
  - Routing-Tabelle aus §6.4.5 (als private Funktion mit sealed
    `RouteOutcome`)
  - Plan-Augmentations-Logik aus §6.4.6 (private Funktion, keine
    eigene Datei).
- `SequenceCurrentValueProbeFn` typealias (Definition siehe §6.4.2)
  in derselben Datei oder im Stage-Begleiter.

**Diagnose-Codes + Classifier:**

- `hexagon/core/.../diff/migration/PlannerBlockerClassifier.kt`
  (extend): Mapping für die vier Blocker-Codes:
  - `SEQUENCE_PRESERVE_PROBE_FAILED` → `MANUAL_ACTION_REQUIRED`
  - `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` →
    `DIALECT_UNSUPPORTED_OPERATION`
  - `SEQUENCE_PRESERVE_CONFIG_INVALID` → `MANUAL_ACTION_REQUIRED`
  - `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` → `MANUAL_ACTION_REQUIRED`
  - INFO-Codes (`SEQUENCE_PRESERVE_NOT_FOUND`,
    `SEQUENCE_PRESERVE_NOT_RUN_POLICY`) bleiben außerhalb der
    Classifier-Tabelle — sie sind keine Blocker, werden also nicht
    klassifiziert.

**Pipeline-Integration:**

- `hexagon/application/.../cli/commands/SchemaMigrateRenderPipeline.kt`
  (extend): zwischen `MigrationPreflightPlanner.plan(...)` und
  `renderer.generateUp(...)` läuft `SequencePreserveStage.run(...)`.
  Wenn `Outcome.Succeeded` → der augmentierte Plan ersetzt das
  Pipeline-internal `plan`-Field für alle nachfolgenden Schritte.
  Wenn `Outcome.Failed` → das Render-Result wird mit dem
  `Failed`-Diagnostics-Bündel gemerged; auch im Blocker-Pfad wird der
  vom Stage augmentierte Plan (inkl. bereits generierter Follow-ups/Diagnosen)
  für `maybeWritePlanArtefact` verwendet.
- `hexagon/application/.../cli/commands/SchemaMigrateRunner.kt`
  (extend): EIN neuer Konstruktor-Parameter
  `sequenceCurrentValueProbe: SequenceCurrentValueProbeFn? = null`;
  pass-through an `SchemaMigrateRenderPipeline`. Plan-Artefakt-
  Schreib-Logik (§6.4.7) bekommt den augmentierten Plan
  zurückgereicht.

**CLI-Wiring:**

- `adapters/driving/cli/.../cli/commands/SequenceCurrentValueProbeRunner.kt`
  *(neu)*: dialect-dispatchender Runner. Bekommt eine
  `CompareOperand.Database` + `configPath` + `SequenceObjectRef`,
  öffnet einen Hikari-Pool, borrowt eine Connection, dispatcht auf
  `PostgresSequenceCurrentValueProbe` (B) oder
  `MysqlSequenceCurrentValueProbe` (C) abhängig vom
  `SequenceObjectRef.dialect`. Schließt den Pool nach jedem Aufruf
  (oder verwendet einen Pool-pro-Plan-Cache; Implementierungs-Detail).
- `adapters/driving/cli/.../cli/commands/SchemaMigrateCommand.kt`
  (extend): reicht `SequenceCurrentValueProbeRunner::probe` an
  `SchemaMigrateRunner`'s `sequenceCurrentValueProbe`-Parameter
  durch. (Per-Dialekt-Probe-Auswahl passiert im Runner intern; aus
  CLI-Sicht ist es nur eine Lambda.)

#### Artefakte (Tests)

**Unit (hexagon:application):**

- `SequencePreserveStageTest`: Skip-Pfade (§6.4.3), Initial-Check
  (§6.4.4), Kandidaten-Filter (§6.4.1) inkl. Drop-out, Routing
  (§6.4.5) für jede Tabellenzeile, Plan-Augmentation (§6.4.6:
  Follow-up an richtiger Position, Dependencies, `pairId`,
  `revertAfterRename`-Setting), Exception-Path (Probe throws →
  Failed mit Stamping pro betroffener parent-Op).
- `PlannerBlockerClassifierSequencePreserveTest`
  (in `hexagon:core` falls Classifier dort liegt; sonst in
  `hexagon:application`): pinnt das Mapping der vier Blocker-Codes
  + INFO-Codes außerhalb der Tabelle.

**Runner-Level E2E (hexagon:application):**

- `SchemaMigrateRunnerSequencePreserveTest` (analog zum
  `SchemaMigrateRunnerMysqlSequenceCanonicityProbeTest`-Pattern):
  pinnt Probe → augmentierter Plan → MigrationDdlResult →
  SchemaMigrateReport — inkl. Plan-Artefakt-Snapshot, der die
  Follow-up-Op enthält. Cases:
  - PG `Read` → AlterSequenceCurrentValue im Statement-Stream
  - MySQL `Read` → UPDATE-Statement-Output
  - PG `NotFound` mit `CreateSequence`-Parent →
    `SEQUENCE_PRESERVE_NOT_FOUND` INFO, kein Follow-up, exit 0
  - PG `NotFound` mit `AlterSequence`-Parent →
    `SEQUENCE_PRESERVE_PROBE_FAILED` Blocker, exit 8
  - Probe throws → exit 8 mit
    `SEQUENCE_PRESERVE_PROBE_FAILED`
  - SQLite + `preserveCurrentValue=true` →
    `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` Blocker
  - Plan-Artefakt (`--plan-artefact`)-Output zeigt die Follow-up-Op
    (§6.4.7-Check).

**Integration (test:integration-postgresql / test:integration-mysql):**

- Eigene Integration-Tests sind NICHT in D — B (PG) und C (MySQL)
  haben die Probe-Live-Verifikation; D's runtime-Verhalten ist
  oberhalb des Probes und durch die Runner-E2E-Tests adäquat
  abgedeckt. Eine End-to-End-Integration mit echter Sequence-
  Migration und `preserveCurrentValue = true` kommt in E.

#### Definition of Done (D)

- [x] `SequencePreserveStage` läuft auf `--execute` gegen MySQL- und
      PG-Targets, wenn der Plan mindestens eine Kandidat-Sequence-Op
      mit `preserveCurrentValue = true` enthält (§6.4.1-Filter).
- [x] Für jede Kandidat-Op läuft der dialect-spezifische Probe;
      Outcome wird deterministisch nach §6.4.5-Tabelle in einen
      Follow-up oder eine Diagnose übersetzt. **DropSequence** läuft
      nicht durch die Routing-Tabelle (vorher gefiltert).
- [x] `CreateSequence` mit `shouldProbeCreateSequence = false` →
      INFO-Diagnose `SEQUENCE_PRESERVE_NOT_FOUND` OHNE Probe-Aufruf.
- [x] `AlterSequenceCurrentValue`-Follow-ups landen direkt hinter
      ihrer parent-Op im finalen `DiffResult.operations`-Stream
      (§6.4.6). `dependencies = setOf(parentOp.id)`.
- [x] `RenameSequence`-Follow-ups setzen `revertAfterRename = true`;
      Down-Reihenfolge ist `RenameSequence-Down` vor
      `AlterSequenceCurrentValue-Down` mit gleicher `pairId`
      (umgesetzt im Diff-Down-Renderer; im Stage nur Flag-Setzung). Der
      `pairId` ist op-id-basiert und daher eindeutig.
- [x] Probe-Exceptions blocken mit
      `SEQUENCE_PRESERVE_PROBE_FAILED`; jede betroffene parent-Op
      bekommt eine eigene Diagnose mit propagiertem Probe-`code`.
- [x] `SQLite` + `preserveCurrentValue = true` blockt über den
      Stage-Skip-Pfad mit `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`
      (kein Probe-Aufruf wird gestartet).
- [x] `MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS.isEmpty()` →
      Stage emittiert `SEQUENCE_PRESERVE_CONFIG_INVALID` BEVOR
      irgendein Probe öffnet (§6.4.4), falls mindestens eine
      MySQL-Kandidaten-Op existiert.
- [x] `SEQUENCE_PRESERVE_NOT_RUN_POLICY` ist nur dann aktiv, wenn der
      Planer bewusst ohne Probe-Adapter läuft (z. B. Null-Probe-Fn in
      Tests/DI oder ähnliches), bleibt INFO und erzeugt keinen Blocker.
- [x] `PlannerBlockerClassifier` mapped:
      - `SEQUENCE_PRESERVE_PROBE_FAILED` → `MANUAL_ACTION_REQUIRED`
      - `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` →
        `DIALECT_UNSUPPORTED_OPERATION`
      - `SEQUENCE_PRESERVE_CONFIG_INVALID` →
        `MANUAL_ACTION_REQUIRED`
      - `SEQUENCE_PRESERVE_NOT_FOUND` und
        `SEQUENCE_PRESERVE_NOT_RUN_POLICY` bleiben INFO (kein
        Mapping-Eintrag).
- [x] CLI (`SchemaMigrateCommand`) wired ein einziges Probe-Lambda
      (Dispatcher) an `SchemaMigrateRunner`; `schema migrate
      --execute` gegen ein MySQL- oder PG-Target mit einer
      `preserveCurrentValue = true`-Sequence läuft End-to-End ohne
      manuelles Eingreifen.
- [x] Plan-Artefakt (`--plan-artefact`) reflektiert den augmentierten
      Plan inkl. `AlterSequenceCurrentValue`-Follow-ups (§6.4.7).
- [x] Kein neues Report-Feld in `SchemaMigrateReport` — die
      Follow-up-Ops surfacen über `operations`/`statements`; die
      neuen Diagnose-Codes über `diagnostics`.
- [x] `make docker-test MODULES=":hexagon:core :hexagon:application
      :adapters:driving:cli"` grün.
- [x] `make docker-coverage-gate` grün.

#### Bewusst nicht in D

- Schema-Spec-Doku (`preserveCurrentValue`-Feld in
  `spec/neutral-model-spec.md`) — in E.
- CHANGELOG-Eintrag für die Workstream — in E.
- SQLite-Sequence-Emulation — separater Plan
  (`docs/planning/open/sqlite-sequence-emulation-plan.md`).
- Atomare `BEGIN; SELECT FOR UPDATE; setval; COMMIT`-Wrappers
  unter Lock — separater Folge-Slice (siehe §10 Risiken).

### 6.5 Sub-Slice E — Schema-Doku + Closing (Detail-DoD)

Reines Doku-Slice; **kein Produktionscode**. Schließt die Workstream
mit drei Artefakten:

1. **Schema-Spec** (`spec/neutral-model-spec.md` §9): erklärt das neue
   `preserve_current_value`-YAML-Feld auf Sequence-Definitionen,
   inklusive Renderer-Matrix (PG `setval`, MySQL `UPDATE
   dmg_sequences`, SQLite Blocker) und Down-Renderer-Hinweis
   (Restore-Hint nur bei deterministischem Vorzustand).
2. **CHANGELOG** (`CHANGELOG.md`): ein Eintrag unter `### Added`,
   der A/B/C/D zusammenfasst — analog zum Drift-Check-Eintrag
   (Probe & Port, Renderer-Pfade, Pipeline-Integration, Diagnose-
   Codes, Carve-outs).
3. **Plan-Doc** (diese Datei): Status auf `Done` setzen,
   §6-Tabelle bekommt Commit-Refs pro Sub-Slice, `git mv` nach
   `docs/planning/done/`.

**Artefakte (Dokumentation):**

- `spec/neutral-model-spec.md` §9 (extend):
  - Neue Yaml-Beispielzeile `preserve_current_value: true`.
  - Erklärung der Semantik: opt-in pro Sequence; der Renderer
    emittiert nach `CreateSequence` (nur mit deterministischem
    Vorzustand) / `AlterSequence` / `RenameSequence` einen
    `setval`/`UPDATE`-Follow-up, der das Target auf den
    probed `last_value`/`next_value` setzt.
  - Hinweis: `--execute` + DB-Target Pflicht; File-Mode unterstützt
    die Probe nicht.
  - SQLite-Carve-out: `DIALECT_UNSUPPORTED_OPERATION` bis ein
    SQLite-Sequence-Emulationsplan landet.
  - `preserve_current_value: false` (oder fehlend) = Default,
    bestehende Pipelines unverändert.
- `CHANGELOG.md` (extend, `### Added`):
  - Ein Bullet "0.9.7 E.3 Folge-Slice — Sequence preserveCurrentValue
    (Sub-Slices A–E)" mit Commit-Refs und Plan-Doc-Pfad.
  - Sub-Bullets: Probe & Port (B/C), Renderer-Pfade (A), Pipeline-
    Integration & Planner-Emit (D), Diagnose-Codes (D), CLI-Wiring
    (D), Carve-outs (CreateSequence-Pre-Probe-Gate konservativ,
    SQLite UNSUPPORTED, atomare Probe+setval out-of-scope).
- `docs/planning/in-progress/…sequence-preserve-current-value.md`:
  - `> **Status**: Done (2026-05-21).` im Frontmatter.
  - §6-Tabelle mit Commit-SHAs pro Sub-Slice.
  - `git mv` nach `docs/planning/done/`.

**Definition of Done (E):**

- [x] `spec/neutral-model-spec.md` §9 dokumentiert
      `preserve_current_value` mit Yaml-Beispiel, Renderer-Matrix
      und SQLite-Carve-out.
- [x] `CHANGELOG.md` enthält einen einzelnen Workstream-Eintrag
      unter `### Added` mit allen Commit-Refs A–D.
- [x] Plan-Doc ist in `docs/planning/done/` (umbenannt via
      `git mv`); Status auf `Done` gesetzt.
- [x] §6-Tabelle im Plan-Doc bekommt Commit-Refs pro Sub-Slice.
- [x] `make docker-test` grün (Doku-only-Edit; nur Cache-
      Validierung erwartet).
- [x] `make docker-coverage-gate` grün.
- [x] Doku-Konsistenz: keine Markdown-Linter-Errors, alle
      Querverweise auf den `done/`-Plan-Doc-Pfad stimmen.

**Bewusst nicht in E:**

- Kein Code-Edit. Wenn beim Doku-Schreiben eine Lücke auffällt,
  wird sie in einem separaten Folge-Slice geschlossen, nicht in E
  reingezogen.
- SQLite-Sequence-Emulation, atomare Probe+setval, Multi-Sequence-
  Atomicity, Sequence-Ownership-Inferenz — alle separater Plan /
  Folge-Slice (siehe §9 Out-of-Scope und §10 Risiken).

---

## 7. Akzeptanzkriterien

- [x] `SequenceDefinition.preserveCurrentValue` ist im Schema-Modell
      definiert und ist die einzige Schaltstelle für diese Tranche.
- [x] PG-Probe liest `last_value`; PG-Renderer emittiert
      `SELECT setval('<seq>', <value>, <isCalled>)` mit korrekt
      propagiertem `isCalled`.
- [x] PG-Probe übermittelt bei `Read` ein nicht-null `isCalled`.
- [x] MySQL-Probe liest `dmg_sequences.next_value`; MySQL-Renderer
      emittiert `UPDATE dmg_sequences …`.
- [x] Konfigurationsabweichung im MySQL-Flow (`mysqlExpectedFormatVersions` leer) blockt den
      Planner deterministisch mit `SEQUENCE_PRESERVE_CONFIG_INVALID`, wenn mindestens eine
      MySQL-Kandidaten-Op vorliegt.
- [x] MySQL-Probe validiert `managed_by`/`format_version` gegen ein
  bekanntes d-migrate Sequenz-Emulationsformat (`supportedManagedBy` inkl.
  `supportedFormatVersions`), sonst wird
  `SEQUENCE_PRESERVE_PROBE_FAILED` gesetzt.
- [x] MySQL-Probe und MySQL-Renderer verwenden dieselbe Resolverfunktion
      für den `dmg_sequences.name`-Lookup-Key aus `SequenceObjectRef`
      (gleicher `mysql_sequence_key` für Probe/Render-Phase).
- [x] `SEQUENCE_PRESERVE_NOT_FOUND` ist als INFO für `CreateSequence` ohne deterministischen Vorzustand definiert
      (kein Blocker) und dokumentiert explizit die `ROLLBACK_NOT_POSSIBLE`-Auswirkung
      für den Current-Value-Teil.
- [x] `SEQUENCE_PRESERVE_NOT_RUN_POLICY` ist als INFO definiert, nur für
      Test-/Fallback-Pfade aktiv und dokumentiert bewusstes Nicht-Ausführen
      von Probe/Follow-up ohne Blockerwirkung.
- [x] MySQL-Probe schlägt fehl (`SEQUENCE_PRESERVE_PROBE_FAILED`), wenn
      die Abfrage auf `dmg_sequences` mehr als eine deterministische Trefferzeile liefert
      oder keine eindeutig matcht.
- [x] MySQL-Renderer führt `UPDATE` nur auf genau eine determinierte Zeile aus
      (`1` affected row); Up/Forward nutzt bei 0 oder >1 betroffenen Zeilen
      `SEQUENCE_PRESERVE_PROBE_FAILED`, Down/rollback kennzeichnet explizit
      `ROLLBACK_NOT_POSSIBLE`.
- [x] `CreateSequence` mit fehlendem deterministischem Vorzustand emittiert
      `SEQUENCE_PRESERVE_NOT_FOUND` als Hinweis und erzeugt keinen Blocker.
      Der Hinweis enthält explizit, dass der Current-Value-Restore als
      `ROLLBACK_NOT_POSSIBLE` zu behandeln ist; `AlterSequence`/`RenameSequence`
      ohne Vorzustand blocken mit `SEQUENCE_PRESERVE_PROBE_FAILED`.
- [x] Für `RenameSequence` wird `AlterSequenceCurrentValue` deterministisch mit
      `probeSequenceRef` (old/origin) und `applySequenceRef` (new/target)
      emittiert; Down-Renderer wendet den Restore auf `probeSequenceRef` an.
- [x] Bei `RenameSequence` mit `revertAfterRename = true` ist die Down-Reihenfolge
      durchgängig fest: Rename-Rückoperation (`RenameSequence` down) **vor** der
      `AlterSequenceCurrentValue`-Restore-Operation.
      Die Reihenfolge ist über denselben `pairId` nachweisbar und wird in der
      Assertion deterministisch geprüft.
- [x] MySQL-Prüfung/Restore nutzt `mysqlExpectedFormatVersions` aus der
      Emulations-Definition (nicht einen einzelnen hartkodierten Wert im
      Renderer/Planner), und `format_version` wird über diese Menge evaluiert.
- [x] Follow-up-Operationen werden direkt im Anschluss an die jeweilige
      Sequence-Operation emittiert (keine Umordnung durch
      allgemeine Plan-Sortierung).
- [x] Datei-zu-Datei-Modus mit `preserveCurrentValue = true` und mindestens
      einer probe-fähigen Sequence-Operation blockt vorrangig mit
      `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`; reine `CreateSequence` ohne
      deterministischen Vorzustand darf nur `SEQUENCE_PRESERVE_NOT_FOUND`
      ausgeben.
- [x] Probe-Failure blockt mit
      `SEQUENCE_PRESERVE_PROBE_FAILED`.
- [x] SQLite im Execute-Modus blockt mit
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`.
- [x] Datei-zu-Datei + SQLite mit `preserveCurrentValue = true` blockt weiterhin
      mit `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` (Dateiquell hat Vorrang vor
      Dialektblockern).
- [x] Reversibility: `AlterSequenceCurrentValue` Down nutzt den im
      Plan gespeicherten `restoreValue` und setzt damit den
      vor-Up-Wert wieder zurück; fehlt der Wert, wird
      `ROLLBACK_NOT_POSSIBLE` ausgewiesen.
- [x] Für `AlterSequenceCurrentValue` ist `restoreValue` exakt in den Fällen gesetzt, in denen ein
      deterministischer Ausgangszustand bekannt ist; `restoreIsCalled` ist für PG dort
      ebenfalls verpflichtend und wird aus dem PG-Probefeld `is_called` übernommen,
      sonst ist Down als `ROLLBACK_NOT_POSSIBLE` dokumentiert.
- [x] Für alle Nicht-PG-Op ist `restoreIsCalled` optional.
      Für die übrigen Fälle
      ist Down explizit als `ROLLBACK_NOT_POSSIBLE` dokumentiert.
- [x] Pro Dialekt mindestens je ein Positiv- und ein
      Blocker-Test, inklusive eines Rename-Up/Down-Reversibility-Tests.

---

## 8. Definition of Done (§13-Template)

- [x] **Modus**: execute (Probe braucht Live-DB).
- [x] **Renderbare Ops**: `AlterSequenceCurrentValue` auf PG/MySQL.
- [x] **Neue Diagnostics**: `SEQUENCE_PRESERVE_PROBE_FAILED`,
      `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`,
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`,
      `SEQUENCE_PRESERVE_CONFIG_INVALID`. Alle vier
      mappen über `PlannerBlockerClassifier` auf: `SEQUENCE_PRESERVE_PROBE_FAILED`,
      `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` und
      `SEQUENCE_PRESERVE_CONFIG_INVALID` auf `MANUAL_ACTION_REQUIRED`;
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` auf
      `DIALECT_UNSUPPORTED_OPERATION`.
- [x] **Hinweisdiagnose**: `SEQUENCE_PRESERVE_NOT_FOUND` wird im Report
      ohne Blocker-Klasse ausgegeben, wenn eine `CreateSequence`-Operation
      keinen lesbaren Vorzustand hat.
      Für diesen Fall ist der Current-Value-Teil explizit als
      `ROLLBACK_NOT_POSSIBLE` dokumentiert.
- [x] **Up / Down getrennt**: Up = `setval`/`UPDATE`; Down =
      `setval`/`UPDATE` auf den gespeicherten `restoreValue` und für PG
      zwingend `restoreIsCalled`, sonst explizit `ROLLBACK_NOT_POSSIBLE`.
- [x] **Rename-Down-Ordering**: bei `RenameSequence` wird die
      `AlterSequenceCurrentValue`-Rollback-Operation deterministisch
      nach der zugehörigen Rename-Rückoperation ausgeführt.
- [x] **Report-Felder**: keine neuen.
- [x] **Dialekte**: PG (positiv), MySQL (positiv), SQLite
      (blocker).
- [x] **F.0-Erfüllung**: irrelevant.
- [x] **Positive + Blocker-Tests**: siehe §7.
- [x] **Rollback-Test**: explizit gepinnt für alle drei
      Dialekte; SQLite-Blocker ist auch Rollback-Blocker.
- [x] **Datei-zu-Datei**: blockt, weil keine Live-DB.
- [x] **Bestehende Verträge unveraendert**: bestehende
      Sequence-Slices bleiben grün.

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
  klar, welche Sequenz zu welcher Spalte gehört. Mitigation:
  diese Tranche behandelt benannte Sequences nur über den
  `SequenceDefinition`-Namen; Inferenz aus PG-Reverse-Read
  ist bereits in `MysqlSchemaReader` und `PostgresSchemaReader`
  gepinnt.
