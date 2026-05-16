# Implementierungsplan: 0.9.7 — Routine-Capability mit konfigurierbarer Quelle

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.1-Carve-out (konfigurierbare Capability-Quelle)
> **Status**: open (geplant, noch nicht gestartet)
> **Vorbedingung**: E.1 ✅ (`docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md`)
> **Referenz**: E.1 §3 Slice C.1.a (Capability-Konfigurations-Quelle),
>             §3 Slice C.2 (`InvalidConfig`-Negativtest)

---

## 1. Ziel

Slice C.1.a der E.1-Migration liefert `RoutineCapability` /
`RoutineKindCapability` / `RoutineCapabilityResolution` als
hexagon:ports-read-Vertrag und einen dialekt-spezifischen
Default-Provider `RoutineCapabilityDefaults`. Die Defaults sind
hardcoded (PostgreSQL / MySQL gleichermassen
`enabled=true, minServerVersion=null`); es gibt keinen Pfad, der
operator-konfigurierte Capability-Mappings einliest.

Das hat zwei konkrete Konsequenzen:

1. Operatoren, die strengere Versions-Gates oder routine-spezifische
   Capability-Deaktivierungen brauchen (z.B. `CREATE OR REPLACE
   PROCEDURE` erst ab MySQL 8.0, oder Disabled für eine spezifische
   Routineart), können das aktuell nicht ausdrücken.
2. Der Renderer-Zweig
   `RoutineCapabilityResolution.InvalidConfig` →
   `MysqlDiffRoutineOps.blockCapabilityInvalid` →
   `ROUTINE_CAPABILITY_CONFIG_INVALID` ist defensive Infrastruktur
   ohne erreichbaren Production-Pfad: `resolve()` kann
   `InvalidConfig` heute nicht produzieren, weil keine Konfig-
   Quelle existiert, die invalide sein könnte. Die Plan-§3-Slice-C.2-
   Vorgabe "InvalidConfig → MANUAL_ACTION_REQUIRED via Test-Fake"
   ist ohne überschreibbare Resolution-API nicht implementierbar.

Dieser Workstream ergänzt eine konfigurierbare Capability-Quelle
(CLI und/oder YAML), die zu `InvalidConfig` führen kann, wenn der
Operator unparsable oder inkonsistent konfiguriert.

## 2. Scope

In Scope:

- Konfigurations-Quelle für `RoutineCapability`:
  - Variante A: CLI-Flag `--routine-capability=function:enabled=true,minServerVersion=8.0.0`
    (repeatable, key=value-Listen pro Routineart).
  - Variante B: YAML-Eintrag in einer existing oder neuen
    Konfigurationsdatei.
  - Beide ergänzen die Defaults, ohne den hardcoded
    `RoutineCapabilityDefaults`-Provider zu ersetzen — die
    Konfig-Quelle überschreibt selektiv.
- Parser/Validator, der bei Syntaxfehler / unparsablem
  `minServerVersion` / inkonsistenten Werten
  `RoutineCapabilityResolution.InvalidConfig` an Stelle einer
  konkreten `Active` / `Disabled`-Entscheidung liefert.
- Verkabelung: Pipeline-Schicht
  (`SchemaMigrateRenderPipeline.buildRenderOptions`) liest die
  Konfig-Quelle, kombiniert mit Defaults, und setzt
  `DdlGenerationOptions.routineCapability`.
- Renderer-Test in `MysqlDiffRoutineOpsTest` für den
  `InvalidConfig`-Pfad — schliesst die heute offene E.1-Slice-C.2-
  Coverage-Lücke (`ROUTINE_CAPABILITY_CONFIG_INVALID`-Pfad in der
  Renderer-Logik).
- Negativtest: `parseCapabilityConfig`-Test pinnt, dass
  unparsable / inkonsistente Eingaben `InvalidConfig` liefern.

Aus Scope:

- Persistenz der gewählten Capability-Konfiguration im
  `migration-plan.v1`-Artefakt — gehört in den Plan-Artefakt-
  Schema-Workstream.
- Audit-Trail wer die Konfig zuletzt geändert hat — gehört zur
  CLI-/Job-Provenance-Infrastruktur.

## 3. Acceptance Criteria

- [ ] CLI- und/oder YAML-Quelle für `RoutineCapability` ist
      implementiert und in `buildRenderOptions` verdrahtet.
- [ ] Parser produziert `RoutineCapabilityResolution.InvalidConfig`
      bei kaputter Konfiguration.
- [ ] `MysqlDiffRoutineOpsTest` pinnt den
      `ROUTINE_CAPABILITY_CONFIG_INVALID`-Pfad im Renderer.
- [ ] `RoutineCapabilityConfigParserTest` pinnt zumindest fünf
      Negativ-Eingaben (syntaktisch kaputt, unbekannter Schlüssel,
      unparsable Version, Konflikt zwischen Routinearten,
      doppelter Eintrag).
- [ ] CHANGELOG-Eintrag.

## 4. Definition of Done

- AC §3 erfüllt.
- `make docker-test` + `make docker-coverage-gate` grün.
- Plan-Datei nach `docs/planning/done/` verschoben.

## 5. Out-of-Scope-Verweis

- MySQL-Reverse-Read von Routine-Identity-Attributen — siehe
  `ImpPlan-0.9.7-mysql-routine-identity-reverse-read.md` (separater
  Carve-out).
- Validator-Regel "INVOKER + definer ist widersinnig" — siehe E.1
  §2 Carve-out.
