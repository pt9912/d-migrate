# Implementierungsplan: 0.9.7 — F.4 Follow-up G — Artefact Producer Wiring

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.4 Follow-up (Audit-Closure nach F.4 routine-trigger-view-renames)
> **Status**: ✅ complete 2026-05-19.
>            Sub-Slice G.1 (transactionScope-String-Drift im Plan-
>            Artefakt-Contract-Test) ✅ 2026-05-19.
>            Sub-Slice G.2 (`MigrationPlanArtifactBuilder` + CLI-Flag
>            `--plan-artefact` + Sink-Write + Runner-Emission +
>            Unit-/Integration-Tests) ✅ 2026-05-19.
>            Sub-Slice G.3 (§E.3 DoD-Checkboxen abgehakt; §11 DoD
>            Box (d) wording-refined und abgehakt) ✅ 2026-05-19.
> **Vorbedingung**: F.4 routine-trigger-view-renames Vollscheibe ✅
>                  (A.1 → F, 2026-05-18/19). Insbesondere Sub-Slice E
>                  hatte den Plan-Artefakt-Vertrag fertig modelliert,
>                  aber die Producer-Seite explizit auf einen Folge-
>                  Slice verschoben.
> **Referenz**: `docs/planning/done/ImpPlan-0.9.7-F.4-routine-trigger-view-renames.md`
>             (F.4 Vollscheibe); `diffresult-migration-plan-2.md` §11
>             (Cross-Dialekt-Regressionsmatrix DoD).

---

## 1. Auslöser

Nach dem F.4-Closing-Slice ergab ein zweiter User-Audit drei
verbleibende Lücken:

1. **`MigrationPlanArtifact` ist test-only**: das Artefakt-Contract aus
   Sub-Slice E (`hexagon/core/.../artifact/MigrationPlanArtifact.kt`)
   war modelliert, codiert (`MigrationPlanArtifactCanonicalJson`) und
   validiert (`MigrationPlanArtifactValidator`), aber an keiner Stelle
   im Produktions-CLI-Fluss instanziiert. Suche nach
   `MigrationPlanArtifact(` über `hexagon/application/` und
   `adapters/driving/` ergab null Treffer ausserhalb von `*Test.kt`.
   Folge: Operatoren konnten den signierten `migration-plan.v1`-JSON
   nie aus `schema migrate` produzieren.
2. **`transactionScope`-String-Drift im Contract-Test**: der Test
   `MigrationPlanArtifactContractTest` pinnte
   `"transactionScope": "SINGLE_STATEMENT"` im Golden-JSON. Diese
   String existiert in der Runtime-`TransactionScope`-Enum
   (`RUNNER_OWNED` / `STREAM_OWNED` / `NO_TRANSACTION`) nicht. Der
   Test dokumentierte damit einen Producer-/Consumer-Vertrag, den
   kein Produktions-Code halten kann.
3. **§E.3-DoD-Checkliste hatte sieben Boxen unchecked**, obwohl die
   Inhalte durch E.1, E.2 und F.4 inzwischen erledigt waren —
   Buchhaltungs-Drift.

Sub-Slices G.1, G.2 und G.3 schließen diese drei Punkte.

---

## 2. Sub-Slice G.1 — `transactionScope`-Drift fix ✅ 2026-05-19

### 2.1 Befund

`MigrationPlanArtifactContractTest` (eingeführt 2026-05-13 mit
Commit `a4694dbf`) pinnte `"SINGLE_STATEMENT"` als
`transactionScope`-Wert im Golden-JSON und in der Test-Fixture.
Diese String existiert in keinem der drei `TransactionScope`-
Enum-Werte (`RUNNER_OWNED`, `STREAM_OWNED`, `NO_TRANSACTION`,
definiert in
`hexagon/ports-read/.../TransactionScope.kt`). Der Sub-Slice-E-
Commit hat den Drift perpetuiert ohne ihn zu erkennen.

### 2.2 Änderung

- Beide Vorkommen (`MigrationPlanArtifactContractTest.kt:67` im
  Golden-JSON-String und `:262` im Test-Fixture-Builder) ersetzt
  durch `"RUNNER_OWNED"` — der Default-Wert auf
  `MigrationDdlStatement.transactionScope` und die Scope-Variante,
  die jeder PG-Diff-Stream für gewöhnliche Single-Statement-DDL
  emittiert.
- `MigrationPlanRenderedStatement.transactionScope` (Datenklasse
  im `hexagon:core`-Artefakt-Modul) erhält einen neuen KDoc-Block,
  der die kanonischen Werte (`RUNNER_OWNED` / `STREAM_OWNED` /
  `NO_TRANSACTION`) pinnt und erklärt, warum das Feld als `String`
  statt als Enum typisiert ist (Forward-Kompatibilität: ein
  zukünftiger vierter Scope-Wert wird zum unbekannten String, nicht
  zum Deserialisierungsfehler).

`sha256Hex` über den kanonischen JSON wird im Test dynamisch
recomputed; kein Hand-pinned Hash-Literal nötig.

### 2.3 Commit

`ad98e0fc fix(artifact): pin canonical TransactionScope enum names in plan-artifact contract test`

---

## 3. Sub-Slice G.2 — Artefact Producer Wiring ✅ 2026-05-19

### 3.1 Befund

Der `MigrationPlanArtifact`-Vertrag aus Sub-Slice E war fertig
modelliert, aber an keiner Stelle der Produktions-CLI-Pipeline
konstruiert. `SchemaMigrateArtefactSink` schrieb Up-SQL +
Rollback-SQL + `SchemaMigrateReport`, aber kein
`migration-plan.v1`.

### 3.2 Architektur

| Schicht | Verdrahtung |
|---|---|
| **Producer** | Neuer `MigrationPlanArtifactBuilder` (`hexagon:application`) — pure Projektion `(DiffResult, MigrationDdlResult, dialect, clock, dMigrateVersion) → MigrationPlanArtifact` |
| **Sink** | `SchemaMigrateArtefactSink.writePlanArtefact(path, artifact)` — atomic write der kanonischen JSON, Return `null` bei Erfolg, `7` bei lokalem I/O-Fehler |
| **Request** | `SchemaMigrateRequest.planArtefact: Path? = null` — optionales Feld |
| **Runner** | `SchemaMigrateRunner.maybeWritePlanArtefact(...)` zwischen Report-Build und Rollback-Compose. Wird auch im Exit-8- und `--plan-only`-Pfad emittiert |
| **CLI** | `--plan-artefact <path>` Clikt-Option in `SchemaMigrateCommand` |

### 3.3 Mapping-Vertrag (Builder)

- `operations[]`: Pro `DiffOperation` ein `MigrationPlanArtifactOperation` mit:
  - `id` = Op-ID
  - `kind` = Subtype-Klassenname (`"CreateTable"`, `"RenameView"`, ...)
  - `objectType` = `DiffObjectType.name`
  - `objectPath` = `DiffObjectRef.path`
  - `phase` = `DiffPhase.name`
  - `reversibility` = `Reversibility.name`
  - `upRisk` / `downRisk` = `MigrationPlanRisk`-DTO inkl.
    `dataTransformationMode` + optionalem Model-Version-/-ID
- `diagnostics[]`: Pro `DiffDiagnostic` → DTO mit Enum-Name-Severity
- `reversibilitySummary`:
  - `fullyReversible` = alle Ops `AUTOMATIC` oder
    `AUTOMATIC_WITH_DATA_RISK`
  - `manualRequiredOperationIds` = Ops mit `MANUAL_REQUIRED`
  - `notReversibleOperationIds` = Ops mit `NOT_REVERSIBLE`
- `renderedStatements[]`: Pro `MigrationDdlStatement`:
  - `statementId` = `"stmt-${idx+1}"` (1-based, stabil)
  - `operationIds` = Set→List
  - `sqlHash` = `RoutineBodyScrubber.preview(stmt.sql).hash` (wiederverwendet von Report-Builder)
  - `transactionScope` = `TransactionScope.name`
- `renameProjections[]`: Pro `RenameProjectionReport` aus
  `DiffResult.renameProjections` → DTO mit `candidateId`,
  `objectType`, `fromPath`, `toPath`, Overlay-Provenance,
  `renameOperationId` ODER `fallbackOperationIds` +
  `fallbackReason`
- Tail-Calls: `.withRenameProjectionExtension()` (auto-add
  `rename-projections.v1` zu `semanticExtensions` wenn
  `renameProjections` non-empty) → `.withComputedHash()`

Fehlende `current.fingerprint` oder `desired.fingerprint`
surface als `IllegalStateException` (Operator-Wiring-Bug; fail
loud statt schweigend leere Strings emittieren).

### 3.4 Insertion-Point im Runner

```kotlin
// SchemaMigrateRunner.execute, zwischen Report-Build und Rollback-Compose:
val report = SchemaMigrateReportBuilder.build(...)
val planArtefactExit = maybeWritePlanArtefact(request, plan, withExecution, prepared.effectiveDialect)
if (planArtefactExit != null) {
    return artefactSink.emitReportAndExit(
        request, report, rollbackFinalized = null, baseExit = planArtefactExit,
    )
}
val rollbackArtefact = rollbackComposer.maybeBuildRollback(...)
return finalize(...)
```

Vor dem Exit-8-/`--plan-only`-Branching in `finalize()` platziert,
damit das Artefakt auch in Blocker- und Plan-Only-Pfaden emittiert
wird — downstream tooling braucht den Plan-Vertrag unabhängig vom
Execute-Outcome.

### 3.5 Tests

- `MigrationPlanArtifactBuilderTest` (7 Cases): Per-Field-
  Projection-Pin (kind/objectType/objectPath/phase/reversibility/
  risks), Reversibility-Summary-Aggregation über gemischte
  AUTOMATIC/MANUAL_REQUIRED/NOT_REVERSIBLE-Ops, stabile `stmt-N`-
  IDs + `RUNNER_OWNED`-Scope-Name, Diagnostics-Round-Trip mit
  Severity-Name, `renameProjections`-Round-Trip plus auto-applied
  `rename-projections.v1`-Gate validiert gegen Consumer mit
  Extension-Support, Missing-Fingerprint fail-fast.
- `SchemaMigrateRunnerTest`: neuer Case `"--plan-artefact writes a
  signed migration-plan.v1 JSON to the requested path"` —
  End-to-End-Integration mit `captureRunner()`, prüft
  `formatVersion`, lowercase dialect, `artifactHash`-Präsenz,
  CreateTable-Marker, `stmt-1`-ID, `RUNNER_OWNED`-Scope.

### 3.6 Commit

`e82844d1 feat(cli): wire migration-plan.v1 artefact producer (--plan-artefact)`

---

## 4. Sub-Slice G.3 — DoD-Buchhaltung ✅ 2026-05-19

### 4.1 §E.3 DoD (`diffresult-migration-plan-2.md`)

Sieben Checkboxen umgeschaltet von `[ ]` auf `[x]`, jede mit
Provenance-Hinweis auf den schliessenden Slice:

| Box | Wer schliesst |
|---|---|
| Workstream G abgeschlossen | G.1–G.3 in `diffresult-migration-plan-2.md` §G |
| Routine-/Trigger-/Sequence-Modellvertrag | E.1 + E.2 + F.4 A.2 Teil 1 (Per-Dialekt-Policy) |
| Body-Hash + Secret-Scrubbing | E.1 Slice F.2 (`RoutineBodyNormalizer.hash` + `RoutineBodyScrubber.preview`) |
| Down-Replace-Body-Guard | E.1 Slice C.1.b (`ROUTINE_DOWN_BODY_UNKNOWN`) |
| Dependency-Sort 5 Klassen | E.1 Slice D.1–D.4 + F.4 Sub-Slice D (`RenameSequence` als Sequence-Provider) |
| SQLite-Rebuild-Trigger-Filter | E.2 Sub-Slice C (`SqliteRebuildPlanner.classify`) |
| Pro freigeschalteter Klasse Positiv+Blocker+Rollback | Status 2026-05-19 nach F.4 Vollscheibe |

### 4.2 §11 DoD Box (d) (`diffresult-migration-plan-2.md`)

> Artifact-Compatibility-Tests decken alte Versionen, unbekannte
> Versionen, manipulierte Hashes und Secret-Scrubbing ab.

Umgeschaltet auf `[x]` mit Carve-out-Hinweis: heute existiert
genau eine `migration-plan.v1`-Format-Version, deshalb ist „alte
Versionen“ N/A. Die drei anderen Pfade sind in
`MigrationPlanArtifactContractTest` gepinnt:
`UNKNOWN_FORMAT_VERSION` blockt jede Version ausserhalb des
Supported-Sets; `HASH_MISMATCH` blockt manipulierte Hashes;
`SECRET_BEARING_PRODUCER_METADATA` + `RESERVED_PRODUCER_METADATA`
pinnen Secret-Scrubbing. Bei Einführung eines echten v2-Formats
ist der Carve-out neu zu bewerten.

### 4.3 §11 DoD Box (a/b/c) — bewusst offen

Box (a) „Jeder umgesetzte Workstream hat mindestens einen
Positivpfad und einen blockierenden Pfad“, (b) „Report- und
Exit-Code-Erwartungen sind in Tests gepinnt“ und (c)
„Rollback-Verhalten ist getestet oder mit Blocker-Begruendung
ausgeschlossen“ bleiben offen — diese erfordern einen
workstream-übergreifenden Audit-Sweep über alle bestehenden Tests,
nicht punktuelle Code-Änderungen. G-Slice deckt sie nicht; ein
folgender Sweep-Slice (H?) ist die natürliche Schliessung.

---

## 5. Auswirkungen / Carve-outs

- **Plan-Artefakt im CLI nicht mehr deferred**: jeder Aufruf mit
  `--plan-artefact <path>` erzeugt eine produktionsreife
  `migration-plan.v1`-JSON. Operatoren können den Artefakt für
  Audit, Diff-Vergleich und Downstream-Tooling konsumieren.
- **`--report` und `--plan-artefact` sind unabhängig**: beide
  schreiben ihren eigenen Pfad atomar, der Runner emittiert beide
  in jedem Pfad ausser bei Plan-Build-Fehlern.
- **Producer-Bug-Surface bleibt aktiv**: wenn `renameProjections`
  non-empty ist und der Producer (z.B. ein zukünftiger Custom-
  Wrapper) den `rename-projections.v1`-Gate vergisst, blockt der
  Validator mit
  `PLAN_ARTIFACT_RENAME_PROJECTIONS_REQUIRE_EXTENSION`. Der
  Builder ruft `withRenameProjectionExtension()` automatisch auf,
  der Bug-Pfad gilt nur für andere Producer.

### 5.1 Out of G-Scope

- §11 DoD Box (a) / (b) / (c) — Workstream-übergreifender
  Audit-Sweep über Positiv/Blocker/Exit-Code/Rollback-Tests.
- E Rest (MySQL/SQLite-Sequence-Emulation, aktueller Sequence-
  Wert / Preserve-Policy, SQLite-Trigger-Reverse-Read).
- F.5 Rest (echte CHECK/EXCLUDE-Änderungen mit Dialekt-Vertrag).
- Coverage/QA-Verbreiterung über Workstreams hinweg.
- rollback-sql v1 BEGIN-Heuristik-Fallback (`SchemaRollbackRunner.splitLegacyArtefactBody`) —
  bewusste, dokumentierte Kompatibilitäts-Carve-out per
  `diffresult-migration-plan-2.md`-Header.

---

## 6. Definition of Done

- [x] `MigrationPlanArtifactBuilder` projiziert `DiffResult` +
      `MigrationDdlResult` in `MigrationPlanArtifact` und ist über
      `MigrationPlanArtifactValidator` validierbar.
- [x] `--plan-artefact <path>` CLI-Flag emittiert den Artefakt
      atomar und exit-fehlt-routet wie `--report`.
- [x] `MigrationPlanArtifactContractTest` pinnt kanonische
      `TransactionScope`-Werte; das `"SINGLE_STATEMENT"`-Drift ist
      beseitigt.
- [x] §E.3 DoD vollständig abgehakt mit Provenance.
- [x] §11 DoD Box (d) abgehakt mit Carve-out-Note (single-version
      Reality).
- [x] `spec/cli-spec.md` §6.1 dokumentiert `--plan-artefact`
      Option + `migration-plan.v1`-Artefakt-Vertrag (Felder,
      Validator-Codes, Semantic-Extension-Gates).
- [x] `CHANGELOG.md` [Unreleased] §Added: G.1 + G.2 dokumentiert.
- [x] Roadmap §F.4-Rest cross-linkt G-Plan-Doc.
- [x] `make docker-check` grün über `hexagon:core`,
      `hexagon:application`, `adapters:driving:cli` und alle drei
      Dialekt-Treiber.
- [x] `make docs-check` grün (kein neuer broken-link).

---

## 7. Commits

| Slice | Commit | Beschreibung |
|---|---|---|
| G.1 | `ad98e0fc` | fix(artifact): pin canonical TransactionScope enum names in plan-artifact contract test |
| G.2 + G.3 | `e82844d1` | feat(cli): wire migration-plan.v1 artefact producer (--plan-artefact) |
| D | (folgt) | docs: cli-spec artefact section + CHANGELOG + roadmap + G plan-doc |
