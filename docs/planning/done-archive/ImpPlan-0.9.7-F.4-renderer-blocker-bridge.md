# Implementierungsplan: 0.9.7 — F.4 Renderer-Blocker-Bridge

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.4 Follow-up nach G-Slices (Audit-Closure §11 DoD Box (b))
> **Status**: ✅ complete 2026-05-19.
>            Sub-Slice A (Classifier + Renderer-Wiring +
>            Unit-/Integration-Tests) ✅ 2026-05-19 (Commit `3b9db807`).
>            Sub-Slice B (CHANGELOG + Roadmap + §11 DoD Box (b) Carve-out resolved
>            + Plan-Doc nach `done/`) ✅ 2026-05-19.
> **Vorbedingung**: F.4 routine-trigger-view-renames Vollscheibe ✅
>                  (A.1 → F, 2026-05-18/19); F.4 G Artefakt-Producer-Wiring ✅
>                  (G.1 + G.2 + G.3, 2026-05-19); §11 DoD Box (b) Audit
>                  ✅ (H.3, 2026-05-19 — Carve-out hier dokumentiert).
> **Referenz**: `done-archive/ImpPlan-0.9.7-F.4-routine-trigger-view-renames.md`
>              §5.2 ("`BLOCKED` ist ein Mapper-/Planner-Ergebnis, kein
>              Renderer-Ergebnis. … `DIALECT_UNSUPPORTED_OPERATION`
>              bleibt Renderer-Faellen vorbehalten"); `diffresult-migration-plan-2.md`
>              §11 DoD Box (b) Carve-out;
>              `done-archive/ImpPlan-0.9.7-F.4-G-artefact-producer-wiring.md`.

---

## 1. Auslöser

Die F.4-Vollscheibe-Plan-Doc §5.2 reserviert
`MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED` explizit für
Mapper-/Planner-Phase-Blockaden — z.B. wenn der
`ObjectRenamePolicy.classify(...)` für einen Rename-Kandidat
`RenameSupport.Blocked` zurückgibt (materialized view rename,
body-drift, missing prior body in der Drop+Create-Fallback-
Variante, SQLite-Routinen-Carve-out, MySQL/SQLite-Sequence-Carve-out).

Diesen Reason muss der Report als `primaryBlockedReason` an
Consumer durchreichen, damit Tooling F.4-spezifische Blockaden von
generischen `DIALECT_UNSUPPORTED_OPERATION`-Fällen (dialekt-
unrenderbare Operation, z.B. `AlterCustomType`) unterscheiden kann.

**Wiring-Befund 2026-05-19 (H.3-Audit)**: Der PG-Renderer
(`PostgresDiffRenderContext.kt:292-307`) wickelt ALLE
BLOCKER-Schweregrad-Diagnostics aus `DiffResult.diagnostics`
pauschal in einen einzelnen `MigrationBlocker(reason =
DIALECT_UNSUPPORTED_OPERATION)`. Der MySQL-Renderer
(`MysqlDiffRenderContext.kt:163-176`) und der SQLite-Renderer
(`SqliteDiffRenderContext.kt:305-318`) machen es identisch. Das
heißt:

- Der F.4-Mapper emittiert einen
  `DiffDiagnostic(code = "OBJECT_RENAME_UNSUPPORTED",
  severity = BLOCKER)` korrekt.
- Der Renderer-Wrap überschreibt die Reason auf
  `DIALECT_UNSUPPORTED_OPERATION`.
- Das Report-Top-Level-`primaryBlockedReason` ist deshalb für jeden
  F.4-Planner-Blocker `DIALECT_UNSUPPORTED_OPERATION` und nie
  `OBJECT_RENAME_UNSUPPORTED`.

Der Vertrag ist damit halbiert: der Mapper-Code spricht „F.4
spezifischer Block", der Report-Output spricht „dialekt-generischer
Block". Tooling, das die beiden Reason-Klassen unterscheiden will,
kann es nicht.

---

## 2. Warum jetzt?

§11 DoD Box (b) Audit (committed in `ffc6970e`, 2026-05-19) hat den
Bug explizit als Carve-out dokumentiert und Box (b) mit dem
Vorbehalt abgehakt, dass dieser Folge-Slice den Vertrag wieder
zusammenführt. Der Audit zeigte:

- Alle sieben dokumentierten Exit-Codes (0/2/3/4/5/7/8) sind
  gepinnt.
- Sechs von sieben CLI-spec-relevanten `primaryBlockedReason`-
  Werten sind als primary gepinnt.
- Der siebte, `OBJECT_RENAME_UNSUPPORTED`, ist NICHT als primary
  pinned, weil der Renderer-Wrap ihn auf
  `DIALECT_UNSUPPORTED_OPERATION` kollabiert.

Ohne diesen Slice bleibt §11 Box (b) mit einer dokumentierten
Wiring-Lücke ✅, aber der F.4-Vertrag (§5.2) und das
`MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED`-KDoc
(`MigrationBlocker.kt:74-94`) widersprechen der Laufzeit.

---

## 3. Scope

### 3.1 In-Scope

- Neuer `PlannerBlockerClassifier` in `hexagon:ports-read`
  (gleiches Modul wie `MigrationBlockedReason` / `MigrationBlocker`,
  damit die Mapping-Tabelle eine Heimat hat). Erste Mapping-
  Tabelle:
  - `"OBJECT_RENAME_UNSUPPORTED"` → `MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED`
  - alle anderen Codes (`CONSTRAINT_NOT_DIFFABLE`,
    `MATERIALIZED_VIEW_DIFF_UNSUPPORTED`, etc.) → Default
    `MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION`
    (bestehender Vertrag bleibt).
- PG/MySQL/SQLite `*DiffRenderContext.toResult()` benutzt den
  Classifier statt Hardcode:
  - planner-blockers per `classifier.classify(diag.code)` in
    Reason-Buckets gruppieren;
  - pro Reason-Bucket einen `MigrationBlocker` mit den zugehörigen
    `diagnostics` emittieren.
- Unit-Tests für den Classifier.
- End-to-End-Runner-Test (`SchemaMigrateRunnerTest` oder
  `SchemaMigratePrePlanOverlayGateTest`), der einen F.4-blockierten
  Renderpath (z.B. materialized view rename oder body-drift in der
  Routine) durch den Runner schickt und assertet, dass die
  Stdout-Report-Zeile `"primaryBlockedReason":"OBJECT_RENAME_UNSUPPORTED"`
  trägt.

### 3.2 Out-of-Scope

- Andere Mapper-/Planner-emitted-Codes, die heute auf
  `DIALECT_UNSUPPORTED_OPERATION` aggregiert werden, aber eigentlich
  einen spezifischeren Reason verdienen (z.B.
  `CONSTRAINT_NOT_DIFFABLE` mit `MANUAL_ACTION_REQUIRED`?). Dieser
  Slice mappt EINEN Code spezifisch; weitere Codes folgen
  bedarfsgetrieben (siehe §9).
- Veränderung des `MigrationOverlayPreflight`-Klassifikators
  (`MigrationOverlayPreflight.kt:341-354`) — der mappt heute
  bewusst den OVERLAY-seitigen `"OBJECT_RENAME_UNSUPPORTED"`-String-
  Code auf `RENAME_MAPPING_INVALID` (Plan-2 §F.4 carve-out wegen
  Backward-Kompat). Das ist eine ANDERE Schiene als die Renderer-
  Wrap, die dieser Slice anfasst.
- CLI-Surface-Änderung. Kein neues Flag, keine neue Exit-Code-
  Bedeutung.
- Spec-Updates über die nötige `cli-spec.md`-Carve-out-Entfernung
  hinaus.

---

## 4. Vorbedingungen

| Vorbedingung | Status | Kommentar |
| ------------ | ------ | --------- |
| F.4 routine-trigger-view-renames Vollscheibe | ✅ 2026-05-19 | Mapper emittiert die Diagnostic; Renderer wickelt sie heute falsch |
| F.4 G Artefakt-Producer-Wiring | ✅ 2026-05-19 | `--plan-artefact` würde nach diesem Slice den korrekten Reason im Artefakt führen |
| §11 DoD Box (b) Audit | ✅ 2026-05-19 | Carve-out hier dokumentiert |
| `MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED` Enum-Wert | ✅ A.1 Foundation | Ordinal 10, KDoc Plan-2 §F.4 §5.2 |

---

## 5. Architektur

### 5.1 `PlannerBlockerClassifier`

Neue Datei: `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/migration/PlannerBlockerClassifier.kt`

```kotlin
package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.DiffDiagnostic

/**
 * Maps a planner-emitted BLOCKER-severity `DiffDiagnostic.code` to
 * the [MigrationBlockedReason] enum value that should surface as the
 * Report-level `primaryBlockedReason`. Renderers consult this
 * classifier instead of hard-coding `DIALECT_UNSUPPORTED_OPERATION`
 * for every planner-emitted diagnostic.
 *
 * Defaults to [MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION]
 * for unknown / legacy codes so the existing contract (e.g.
 * `CONSTRAINT_NOT_DIFFABLE` from F.5) stays unchanged.
 */
object PlannerBlockerClassifier {

    fun classify(code: String): MigrationBlockedReason = when (code) {
        OBJECT_RENAME_UNSUPPORTED_CODE -> MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED
        else -> MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    private const val OBJECT_RENAME_UNSUPPORTED_CODE: String = "OBJECT_RENAME_UNSUPPORTED"
}
```

### 5.2 Renderer-Wiring (PG / MySQL / SQLite)

Heute (`PostgresDiffRenderContext.kt:299-306`, identisch in MySQL /
SQLite):

```kotlin
val effectiveBlockers = if (plannerBlockers.isNotEmpty()) {
    blockers + MigrationBlocker(
        reason = MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
        diagnostics = plannerBlockers,
    )
} else {
    blockers
}
```

Neu:

```kotlin
val effectiveBlockers = if (plannerBlockers.isEmpty()) {
    blockers
} else {
    val grouped = plannerBlockers.groupBy { PlannerBlockerClassifier.classify(it.code) }
    blockers + grouped.map { (reason, diags) ->
        MigrationBlocker(reason = reason, diagnostics = diags)
    }
}
```

Vertrag:

- Pro classified Reason ein eigener `MigrationBlocker` mit den
  passenden Diagnostics.
- Die Reihenfolge der emittierten Blocker ist stabil
  (`groupBy` → `Map`-iteration: Kotlin garantiert
  Insertion-Order auf `LinkedHashMap`, der `groupBy`-Default).
  Bei mehreren Reasons gewinnt diejenige, deren erste Diagnostic
  zuerst auftritt — `effectiveBlockers.first().reason` definiert
  `primaryBlockedReason`.
- Die bestehende `MigrationDdlResult.primaryBlockedReason =
  effectiveBlockers.firstOrNull()?.reason`-Logik bleibt unverändert.

### 5.3 Reason-Validation-Vertrag

`MigrationDdlResult`-init pinnt heute:

```kotlin
require(blockers.any { it.reason == primaryBlockedReason }) {
    "primaryBlockedReason must appear in blockers list"
}
```

Bleibt gültig: der erste Blocker liefert den Reason, der Reason ist
per Definition in der Liste.

---

## 6. Sub-Slice-Schnitt

### Sub-Slice A — Classifier + Renderer-Wiring + Unit-/Integration-Tests

- `PlannerBlockerClassifier` in `hexagon:ports-read`.
- `PostgresDiffRenderContext` / `MysqlDiffRenderContext` /
  `SqliteDiffRenderContext` `toResult()` umstellen.
- `PlannerBlockerClassifierTest`: pinnt das Mapping
  (`OBJECT_RENAME_UNSUPPORTED` → enum-Wert; alle anderen Codes →
  `DIALECT_UNSUPPORTED_OPERATION`).
- End-to-end-Test in `SchemaMigrateRunnerTest`: F.4-Mapper-Blocker
  via materialized-view-rename oder body-drift-Routine surfacet als
  `primaryBlockedReason = OBJECT_RENAME_UNSUPPORTED` im Stdout-
  Report; Exit 8.
- Bestehende Tests müssen grün bleiben, insbesondere:
  - `SchemaMigrateRunnerTest::renderer-side blockers (DIALECT_UNSUPPORTED) yield exit 8`;
  - F.5-Pfad (`DiffPlannerF5ConstraintTest`) liefert weiterhin
    `DIALECT_UNSUPPORTED_OPERATION`-Primary, weil
    `CONSTRAINT_NOT_DIFFABLE` nicht klassifiziert wird.

### Sub-Slice B — Closing

- §11 DoD Box (b) Carve-out auf „✅ resolved 2026-05-19" aktualisieren
  (im selben Eintrag, kein neuer Box-Status).
- `CHANGELOG.md` [Unreleased] §Fixed Eintrag.
- Roadmap §Coverage/QA aktualisieren (Box (b) Carve-out
  entfernen).
- Plan-Doc nach `docs/planning/done/`.

---

## 7. Akzeptanzkriterien

- [ ] `PlannerBlockerClassifier.classify("OBJECT_RENAME_UNSUPPORTED")` →
      `MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED`.
- [ ] `PlannerBlockerClassifier.classify(<jeder andere String>)` →
      `MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION`.
- [ ] PG/MySQL/SQLite-`DiffRenderContext.toResult()` benutzen den
      Classifier; `effectiveBlockers` enthält pro classified Reason
      einen eigenen Eintrag.
- [ ] End-to-End-Runner-Test pinnt, dass ein F.4-Planner-Blocker als
      `primaryBlockedReason = OBJECT_RENAME_UNSUPPORTED` im Report
      auftaucht (Exit 8).
- [ ] Bestehender PG-Test `renderer-side blockers
      (DIALECT_UNSUPPORTED) yield exit 8` bleibt grün — generische
      `DIALECT_UNSUPPORTED_OPERATION`-Pfade sind unverändert.
- [ ] F.5-Pfad mit `CONSTRAINT_NOT_DIFFABLE` produziert weiterhin
      `primaryBlockedReason = DIALECT_UNSUPPORTED_OPERATION`.
- [ ] §11 DoD Box (b) Carve-out auf „✅ resolved" aktualisiert.
- [ ] `make docker-check` grün über hexagon:core, hexagon:ports-read,
      hexagon:application, PG/MySQL/SQLite-Driver.
- [ ] `make docs-check` grün.

---

## 8. Definition of Done (§13-Template)

- [ ] **Betroffener Modus**: alle Modi (file-to-file, file-to-DB,
      execute, rollback, plan-only) — der Bug surftet überall, weil
      `primaryBlockedReason` in jedem Modus aus dem `MigrationDdlResult`
      stammt.
- [ ] **Renderbare Operationen + Blocker**: keine neuen
      renderbaren Operationen, keine neuen Blocker. Der Fix verändert
      nur die Reason, mit der bestehende Blockaden klassifiziert
      werden. F.4-Planner-Blockaden behalten Exit 8 und
      `MIGRATION_BLOCKED`-Status; nur `primaryBlockedReason`
      wechselt von `DIALECT_UNSUPPORTED_OPERATION` (heute) zu
      `OBJECT_RENAME_UNSUPPORTED` (neu).
- [ ] **Neue Diagnostics / Blocker / primaryBlockedReason**: keine
      neuen Diagnostic-Codes; `primaryBlockedReason`-Wert
      `OBJECT_RENAME_UNSUPPORTED` jetzt ENDE-TO-ENDE durchreichbar
      (Enum-Wert existierte seit F.4 A.1).
- [ ] **Up- und Down-Verhalten**: identisch — Blockaden verhindern
      das Rendern beider Richtungen, der Reason ist
      richtungs-unabhängig.
- [ ] **Report-/Metadatenfelder**: `report.summary.primaryBlockedReason`
      ändert sich für F.4-Mapper-Blockaden von
      `DIALECT_UNSUPPORTED_OPERATION` zu `OBJECT_RENAME_UNSUPPORTED`.
      Das ist ein **breaking change** für nachgelagerte
      Consumer, die das Feld bisher als
      `DIALECT_UNSUPPORTED_OPERATION` interpretiert haben — siehe
      §9 Out-of-Scope für die Migration-Empfehlung.
- [ ] **Betroffene Dialekte**: PG, MySQL, SQLite — alle drei
      Renderer haben dieselbe Wrap-Logik und müssen synchron
      umgestellt werden.
- [ ] **F.0-Erfüllung**: irrelevant — kein neuer Overlay-Input.
- [ ] **Positive und blockierende Testpfade**: Sub-Slice A.
- [ ] **Rollback-Test oder Begründung**: kein dediziertes
      Rollback-Verhalten (Blockaden verhindern auch das Rendern der
      Rollback-Direction); generischer Rollback-Pfad bleibt
      unverändert.
- [ ] **Datei-zu-Datei-Verhalten**: identisch (siehe Modus oben).
- [ ] **Bestehende 0.9.7-Verträge unverändert**: bis auf den
      `primaryBlockedReason`-Wechsel für F.4-Mapper-Blockaden bleibt
      alles gleich. Der Wechsel ist die ursprüngliche Vertragsabsicht
      aus F.4 §5.2; bestehende Tests, die
      `DIALECT_UNSUPPORTED_OPERATION` für F.4-Materialized-View-
      Rename / Body-Drift pinnen, müssten ohnehin neugeschrieben
      werden (heute keine solchen Tests bekannt, siehe H.3 Audit).
- [ ] **Slice kann unabhängig implementiert und verifiziert
      werden**: ja — eine neue Datei (Classifier) + drei Renderer-
      Anpassungen + Tests; keine Cross-Dependency zu offenen
      Workstreams.

---

## 9. Out-of-Scope / Folge-Themen

- **Andere planner-blocker-Codes mit eigenem Reason-Mapping**:
  - `CONSTRAINT_NOT_DIFFABLE` (F.5) könnte als
    `MANUAL_ACTION_REQUIRED` klassifiziert werden statt
    `DIALECT_UNSUPPORTED_OPERATION`. Heute mappt der Classifier-
    Default — bedarfsgetrieben in einem späteren Slice nachziehen.
  - `MATERIALIZED_VIEW_DIFF_UNSUPPORTED` (D.3b) — ähnliche Frage.
  - Empfehlung: separater Sweep-Slice, der pro Code prüft, ob ein
    spezifischerer Reason existiert.

- **Backward-Kompatibilität für Consumer mit Hardcoded
  `DIALECT_UNSUPPORTED_OPERATION`-Erwartung**: dieser Slice
  ÄNDERT den `primaryBlockedReason` für F.4-Materialized-View-
  Rename und Body-Drift-Routinen. Heute existieren keine bekannten
  Consumer-Tests, die diesen Wert pinnen (H.3 Audit-Sweep), aber
  externe Tooling könnte betroffen sein. Empfehlung: CHANGELOG-
  Eintrag unter `### Changed` oder `### Fixed` mit klarem Hinweis.

- **`SchemaMigrateReport`-Schema-Doku**: `report.summary.primaryBlockedReason`
  in `spec/cli-spec.md` listet bisher 7 Werte. Mit diesem Slice
  wird ein achter (`OBJECT_RENAME_UNSUPPORTED`) als Top-Level-
  Primary möglich — die Liste in §6.1 müsste eventuell ergänzt
  werden. Heute steht dort: "`OBJECT_RENAME_UNSUPPORTED`" wird
  bereits als möglicher Reason erwähnt (im F.4-Workflow-Abschnitt).
  Prüfen, ob das ausreicht; ggf. nachschärfen in Sub-Slice B.

---

## 10. Risiken

### 10.1 Multiple-Blocker-Reihenfolge

`MigrationDdlResult.primaryBlockedReason` ist
`effectiveBlockers.firstOrNull()?.reason`. Wenn der Plan sowohl
einen F.4-Block (`OBJECT_RENAME_UNSUPPORTED`) als auch z.B. einen
F.5-Block (`CONSTRAINT_NOT_DIFFABLE` → `DIALECT_UNSUPPORTED_OPERATION`)
hat, ist der `primary` der ZUERST emittierte Blocker. Heute werden
ALLE planner-blockers in EINEM Blocker mit
`DIALECT_UNSUPPORTED_OPERATION` zusammengefasst, deshalb existiert
diese Ambiguität nicht. Nach dem Fix kann sie auftreten.

Mitigation: Der Test pinnt einen Plan mit GENAU EINEM
F.4-Mapper-Blocker; gemischte Multi-Blocker-Pläne sind nicht der
Hauptfall. Wenn sie auftauchen, sieht der Consumer
`blockers[]` mit beiden Einträgen — `primary` ist nur ein
Komfort-Shortcut.

### 10.2 Tests, die `effectiveBlockers.size == 1` erwarten

Wenn irgendein Test heute davon ausgeht, dass planner-blockers immer
genau EIN Blocker-Eintrag sind, bricht er bei mehreren classified
Reasons. H.3-Audit hat keinen solchen Test gefunden, aber Sub-Slice
A muss alle PG/MySQL/SQLite-Tests grün lassen — Bruchstellen
finden via `docker-check`.

### 10.3 Implizite OVERLAY-Klassifikator-Interaktion

`MigrationOverlayPreflight.classifyDiagnostic(...)` mappt den
String-Code `OBJECT_RENAME_UNSUPPORTED` heute auf
`MigrationBlockedReason.RENAME_MAPPING_INVALID` (mit der Begründung
in Zeile 364-372: "Forward-looking renderer diagnostic the Routine-/
Trigger-/View-Rename slice will emit"). Dieser Klassifikator läuft
PRE-PLAN; der neue `PlannerBlockerClassifier` läuft im
Renderer POST-PLAN. Beide Schienen existieren parallel:

- Pre-plan overlay-validator-Pfad: `OBJECT_RENAME_UNSUPPORTED` →
  `RENAME_MAPPING_INVALID` (bleibt).
- Post-plan mapper-/planner-Pfad: `OBJECT_RENAME_UNSUPPORTED` →
  `OBJECT_RENAME_UNSUPPORTED` (neu).

Das ist mit dem `MigrationBlocker.kt:74-94`-KDoc bewusst so
modelliert. Sub-Slice A darf den Pre-Plan-Klassifikator nicht
ändern.

---

## 11. Erwartete Commit-Reihenfolge

| Sub-Slice | Commit-Subjekt-Skizze |
|---|---|
| A | `feat(blockers): preserve OBJECT_RENAME_UNSUPPORTED as primary reason through renderer wrapping` |
| B | `docs(plan): §11 DoD Box (b) Carve-out resolved by renderer-blocker-bridge` |

---

## 12. Hinweise für Reviewer

- Der Slice ist **wiring-only**, keine neuen Features, keine neue
  CLI-Surface, keine neuen Diagnostic-Codes, keine neuen Exit-Codes.
- Der einzige semantische Effekt: `primaryBlockedReason`-Wert ändert
  sich für F.4-Mapper-Blockaden von `DIALECT_UNSUPPORTED_OPERATION`
  zu `OBJECT_RENAME_UNSUPPORTED`. Das ist die ursprüngliche
  F.4-§5.2-Vertragsabsicht.
- Der Renderer-Wrap-Code ist dialekt-symmetrisch (PG/MySQL/SQLite
  haben identische Patterns) — der Fix sollte symmetrisch landen.
- Falls Reviewer einen Carve-out für „kann F.5 / D.3b auch ihre
  spezifischen Reasons bekommen" einbringt, ist das §9
  Out-of-Scope; bitte als eigenen Slice anhängen.
