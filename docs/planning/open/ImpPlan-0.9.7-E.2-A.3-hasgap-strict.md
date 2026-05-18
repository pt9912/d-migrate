# Implementierungsplan: 0.9.7 — E.2 Sub-Slice A.3 (`hasGap`-Wiring + Strict-Mode)

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.2 Trigger-Migration — Sub-Slice A.3
> **Status**: open (geplant, noch nicht gestartet)
> **Vorbedingung**: E.2 A.1 ✅ (`OperationRisk.hasGap`-Feld vorhanden;
>                  `TriggerCapability` + `TriggerCapabilityDefaults`),
>                  E.2 A.2 ✅ (PostgreSQL-Renderer emittiert
>                  `W_TRIGGER_REPLACE_GAP`-Warning).
> **Vorbedingung fuer**: E.2 Sub-Slice B (MySQL Renderer) — MySQL
>                  rendert `ReplaceTrigger` immer Drop+Create und
>                  konsumiert dieselbe Strict-Mode-Infrastruktur.
> **Referenz**: `docs/planning/in-progress/ImpPlan-0.9.7-E.2-trigger-rendering.md`
>             §2 Scope-Carve-out ("Replace-Fallback ist eine bewusste,
>             nicht stille Entscheidung"), §3 Sub-Slice A.1 Foundation
>             (`OperationRisk.hasGap`, `--strict`-Vertrag).

---

## 1. Ziel

Zwei Carve-outs aus E.2 A.1/A.2 schliessen, die in den vorherigen
Sub-Slices bewusst herausgezogen wurden, weil sie ein End-to-End-
Wiring vom Mapper bis zum CLI-Layer brauchen:

1. **`OperationRisk.hasGap` wird gesetzt** — A.1 hat das Feld
   eingefuehrt, A.2 nutzt es noch nicht. Heute markiert nichts auf
   der Operation, dass `ReplaceTrigger` ueber Drop+Create gerendert
   wird. Der Renderer emittiert die Luecke nur als
   `W_TRIGGER_REPLACE_GAP`-WARNING im Diagnostics-Stream; ein Consumer
   muss den Code matchen, statt das maschinenlesbare Risk-Feld auf
   der Operation zu lesen.
2. **`--strict`-Mode liftet hasGap zu `MANUAL_ACTION_REQUIRED`** — A.1
   hat den Vertrag dokumentiert (Plan §2: "im `--strict`-Pfad
   blockierender `MANUAL_ACTION_REQUIRED`"), aber kein CLI-Flag und
   keinen Lift-Pfad. Operatoren, die einen Trigger-Effekt-Gap
   ausschliessen wollen, koennen heute nichts dafuer tun ausser den
   Diagnostic-Stream zu pruefen.

Plan-Doc-Wortlaut, der diesen Slice rechtfertigt
(`ImpPlan-0.9.7-E.2-trigger-rendering.md §2`):

> "Replace-Fallback ist eine bewusste, nicht stille Entscheidung …
> jeder `ReplaceTrigger`, der ueber Drop+Create gerendert wird, traegt
> eine explizite `REPLACE_TRIGGER_VIA_DROP_CREATE`-Capability-
> Decision, eine `OperationRisks(up = OperationRisk(hasGap = true),
> ...)`-Markierung und emittiert eine Display-/Report-Warnung
> (`W_TRIGGER_REPLACE_GAP`). Im `--strict`-Pfad blockt der
> Mapper-/Planner mit `MANUAL_ACTION_REQUIRED`."

## 2. Scope

**In Scope:**

- Capability-Resolution im Mapper-/Planner-Layer:
  - `DiffPlanner.plan(...)` (oder ein Post-Map-Schritt) konsumiert die
    `TriggerCapability` (Foundation in A.1) ueber einen dialekt-
    bewussten Planning-Context und setzt
    `ReplaceTrigger.risks.up.hasGap = true` (sowie analog
    `down.hasGap`), wenn die Capability fuer den Ziel-Dialekt
    `TriggerCapabilityResolution.Disabled` ergibt.
  - Native-Replace-Faelle (PG-14+) tragen `hasGap = false` (Default).
  - Der Mapper darf die Capability nicht aus globalen Renderern oder
    spaeteren Render-Preflights ableiten — sie kommt vor `plan()`
    in den Core-Kontext.
- CLI-Flag `--strict-gap-operations` (Name im Slice final geklaert)
  an `schema migrate` und `schema rollback`. Standard: `false`.
- `DdlGenerationOptions.strictGapOperations: Boolean = false` als
  Carrier zwischen CLI-Layer und Renderer/Result-Builder.
- Lift-Pfad im Renderer bzw. einem zentralen Post-Render-Schritt:
  wenn `op.risks.<direction>.hasGap == true` und
  `options.strictGapOperations == true`, blockt der Pfad mit
  `MANUAL_ACTION_REQUIRED` und emittiert keine Statements fuer diese
  Operation. Andernfalls bleibt der bestehende Drop+Create-Pfad +
  `W_TRIGGER_REPLACE_GAP`-Warning unveraendert.
- Die `hasGap`-Markierung muss im `migration-plan.v1`-Artefakt
  serialisiert sein, damit downstream-Consumer (Reports, externe
  Tooling) sie ohne Diagnostic-Code-Match auslesen koennen.
- Tests:
  - Mapper-Pin: Capability `Disabled` setzt `hasGap = true` auf der
    `ReplaceTrigger`-Operation; Capability `Active` setzt
    `hasGap = false`.
  - Default-Pfad (`strictGapOperations = false`): PG-13 Replace
    emittiert Drop+Create + Warning, kein Blocker.
  - Strict-Pfad (`strictGapOperations = true`): PG-13 Replace blockt
    mit `MANUAL_ACTION_REQUIRED`, keine Statements.
  - Strict-Pfad mit Capability `Active` (PG-14+): kein Lift, keine
    Aenderung am bestehenden Verhalten.
  - Plan-Artefakt-Roundtrip: `hasGap` ist in `migration-plan.v1`
    serialisiert und beim Read-back wieder vorhanden.

**Aus Scope (Carve-outs):**

- MySQL- und SQLite-Renderer (Sub-Slice B / C).
- Andere `hasGap`-emittierende Operationen jenseits `ReplaceTrigger`
  (z.B. `ReplaceFunction` ohne `CREATE OR REPLACE`). Der Lift-Pfad ist
  generisch (er liest `OperationRisk.hasGap`); welche Mapper das Feld
  setzen, bleibt pro Operation eine Mapper-Entscheidung. A.3 verdrahtet
  nur den Trigger-Pfad; andere Faelle sind separate Slices.
- Reverse-Lift: ein hasGap-Down ohne hasGap-Up oder umgekehrt ist nicht
  modelliert. Der Slice setzt `hasGap` symmetrisch auf `up`/`down`,
  wo beide Drop+Create-Statements eine Luecke erzeugen, oder asymmetrisch
  per dokumentierter Entscheidung.

## 3. Architektur

### 3.1 Planning-Context

Heute nimmt `DiffPlanner.plan(...)` einen
`RenameProjectionCapabilities`-Parameter. Der Slice fuegt entweder
einen neuen `TriggerPlanningContext` hinzu oder erweitert den
bestehenden Capability-Carrier um Trigger-Felder. Der Application-/
CLI-Layer baut den Context aus `DatabaseDialect` und ggf. der live
`postgresMajorVersion`.

```kotlin
internal data class TriggerPlanningContext(
    val capability: TriggerCapability,
    val postgresMajorVersion: Int? = null,
)
```

Der Mapper-/Post-Map-Schritt walked alle `ReplaceTrigger`-Operationen
und ruft `capability.resolve(postgresMajorVersion)`. Bei `Disabled`
wird die Operation mit `hasGap = true` versehen (via
`.copy(risks = risks.copy(up = up.copy(hasGap = true), down = ...))`).

### 3.2 CLI-Wiring

Neue Option an `SchemaMigrateCommand` / `SchemaRollbackCommand`:

```kotlin
val strictGapOperations by option(
    "--strict-gap-operations",
    help = "Block operations that render with a multi-statement visibility gap " +
        "(e.g. ReplaceTrigger via Drop+Create on PostgreSQL < 14). Default off.",
).flag()
```

`SchemaMigrateRenderPipeline.buildRenderOptions(...)` propagiert das
in `DdlGenerationOptions.strictGapOperations`.

### 3.3 Lift-Pfad

Pragmatischer Ort: ein zentraler Pre-Emit-Check im Renderer-Kontext
(`PostgresDiffRenderContext.emit(...)` oder ein vorgelagerter
Dispatcher-Schritt) liest den Risk und entscheidet:

```kotlin
fun emit(op: DiffOperation, sql: String, hints: ...) {
    val direction = if (this.direction == UP) op.risks.up else op.risks.down
    if (direction?.hasGap == true && options.strictGapOperations) {
        skip(op, "...", code = "OPERATION_HAS_GAP_STRICT_BLOCKED")
        addBlocker(MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return
    }
    // existing emit path
}
```

Alternative: ein Wrapper um `PostgresTriggerDdlHelper.emitDropCreateReplaceFallback`,
der den Check vor dem Render macht. Slice entscheidet im PR.

`PostgresDiffRenderContext` ist heute PG-only; B/C nutzen analoge
Kontext-Klassen, also entweder pro Renderer einbauen oder einen
gemeinsamen Pre-Emit-Hook als Renderer-Mixin/Funktion in
`hexagon:ports-read` (z.B. `MigrationDdlPort`-Helfer) extrahieren.

### 3.4 Plan-Artefakt

`migration-plan.v1` serialisiert heute `OperationRisks`. Der Slice
muss pruefen, ob `hasGap` automatisch durch die data-class-
Serialisierung gefangen wird (Jackson + Default `false` → vorhanden)
oder ob ein expliziter Eintrag und Goldenness-Update noetig ist.

## 4. Akzeptanzkriterien

- [ ] `DiffPlanner.plan(...)` konsumiert einen
      `TriggerPlanningContext` (oder erweitert den bestehenden
      Capability-Carrier) und setzt `ReplaceTrigger.risks.up.hasGap`
      basierend auf `TriggerCapability.resolve(...)`.
- [ ] PG-14+ Mapper-Pin: `Active` → `hasGap = false`.
- [ ] PG-13 / file-only Mapper-Pin: `Disabled` → `hasGap = true`.
- [ ] CLI-Flag `--strict-gap-operations` an `schema migrate` /
      `schema rollback`; Default `false`.
- [ ] `DdlGenerationOptions.strictGapOperations` propagiert das Flag.
- [ ] Renderer-Lift: bei `hasGap && strictGapOperations` blockt der
      Pfad mit `MANUAL_ACTION_REQUIRED`, emittiert keine Statements
      und keine Drop+Create-Warning (weil die Operation nicht
      ausgefuehrt wird).
- [ ] Default-Pfad bleibt unveraendert: Drop+Create + Warning;
      `W_TRIGGER_REPLACE_GAP`-WARNING wird nicht doppelt emittiert.
- [ ] PG-14+ Strict-Pfad: kein Lift, kein Blocker, identisches
      Verhalten zu Default.
- [ ] `migration-plan.v1` Goldenness: `hasGap` ist im Artefakt
      serialisiert; Compat-Test pinnt entweder ein bekanntes Feld oder
      ein `requiredFeatures`/`semanticExtensions`-Gate, falls die
      Serialisierung sich aendert.
- [ ] `spec/cli-spec.md` §6.1 dokumentiert `--strict-gap-operations`
      und den Lift-Vertrag (`hasGap → MANUAL_ACTION_REQUIRED`).
- [ ] `roadmap.md` / E.2-Hauptplan-Header markieren A.3 als
      abgeschlossen.

## 5. Definition of Done

- [ ] Alle Akzeptanzkriterien aus §4 erfuellt.
- [ ] `make docker-test` gruen, Output in `/tmp/build.log`.
- [ ] Coverage je betroffenem Modul ≥ 90%.
- [ ] Plan-Datei nach `docs/planning/done/` verschoben.
- [ ] E.2-Hauptplan-Status um den A.3-Abschluss aktualisiert.
- [ ] Vorbedingung fuer Sub-Slice B (MySQL) ist gruen.

## 6. Risiken

### 6.1 Mapper kennt keine Dialect-Capabilities heute

Der Core-Mapper darf nicht direkt von `DatabaseDialect` abhaengen
(Hexagonal-Boundary). Der Slice fuehrt einen core-lokalen
`TriggerPlanningContext` ein, den der Application-/CLI-Layer aus
`DatabaseDialect` befuellt. Das ist analog zum F.4-Pattern
(`ObjectRenamePlanningContext`).

### 6.2 Lift-Pfad pro Renderer vs. zentral

Drei Renderer brauchen denselben Lift-Check. Wenn er pro Renderer in
den Kontext-Klassen lebt, gibt es Drift-Risiko. Slice prueft, ob ein
gemeinsamer `MigrationDdlEmitHelper` in `hexagon:ports-read` praktikabel
ist, statt drei separate Implementierungen.

### 6.3 Asymmetrie up/down

Eine `ReplaceTrigger`-Operation hat sowohl `risks.up` als auch
`risks.down`. Der Slice muss entscheiden, ob `hasGap` symmetrisch
fuer beide Richtungen gesetzt wird (Drop+Create-Down hat dieselbe
Luecke) oder asymmetrisch. Default: symmetrisch.

### 6.4 Strict-Flag-Naming

`--strict-gap-operations` ist beschreibend, aber lang. Alternativen:
`--no-gap-operations`, `--block-multi-statement-fallbacks`,
`--strict-replace`. Im Slice abstimmen.

## 7. Out-of-Scope-Verweis

- MySQL- und SQLite-Trigger-Renderer (Sub-Slice B / C).
- Andere Operationen, die `hasGap = true` rechtfertigen koennten
  (z.B. eine zukuenftige `ReplaceFunction`-Variante ohne
  `CREATE OR REPLACE`): A.3 verdrahtet nur den Trigger-Pfad, der Lift
  ist aber generisch.
- Erweiterung der Strict-Semantik auf andere Risk-Flags
  (`destructive`, `dataLossPossible` etc.) — die haben ihre eigenen
  bestehenden Confirmation-Pfade und sind nicht Teil von A.3.
