# Implementierungsplan: 0.9.7 — Routine-Capability mit konfigurierbarer Quelle

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.1-Carve-out (konfigurierbare Capability-Quelle)
> **Status**: done — Sub-Slices A (sealed `EffectiveRoutineCapability` + Parser-Kern), B (CLI/YAML resolver + pipeline wiring) und C (renderer + E2E pins, spec/CHANGELOG) umgesetzt
> **Vorbedingung**: E.1 ✅ (`docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md`)
> **Referenz**: E.1 §3 Slice C.1.a (Capability-Konfigurations-Quelle),
>             §3 Slice C.2 (`InvalidConfig`-Negativtest)

---

## 1. Ziel

Slice C.1.a der E.1-Migration liefert `RoutineCapability` /
`RoutineKindCapability` / `RoutineCapabilityResolution` als
hexagon:ports-read-Vertrag und einen dialekt-spezifischen
Default-Provider `RoutineCapabilityDefaults`. Die Defaults sind
hardcoded: PostgreSQL aktiviert `CREATE OR REPLACE`, der neutrale
MySQL-Dialekt ist seit E.1 F.11 Oracle-MySQL-konservativ
(`enabled=false` fuer Function und Procedure), und live erkannte
MariaDB-Ziele werden ueber `RoutineCapabilityDefaults.forMysqlServerVersion`
aktiviert. Es gibt noch keinen Pfad, der operator-konfigurierte
Capability-Mappings einliest.

Das hat zwei konkrete Konsequenzen:

1. Operatoren, die explizite Overrides brauchen (z.B. File-to-file
   MariaDB ohne Live-Vendor-Probe, strengere Versions-Gates oder
   routine-spezifische Capability-Deaktivierungen), können das aktuell
   nicht ausdrücken.
2. Der Renderer-Zweig
   `RoutineCapabilityResolution.InvalidConfig` →
   `MysqlDiffRoutineOps.blockCapabilityInvalid` →
   `ROUTINE_CAPABILITY_CONFIG_INVALID` ist defensive Infrastruktur
   ohne erreichbaren Production-Pfad: `resolve()` kann
   `InvalidConfig` heute nicht produzieren, weil keine Konfig-
   Quelle existiert, die invalide sein könnte. Die Plan-§3-Slice-C.2-
   Vorgabe "InvalidConfig → MANUAL_ACTION_REQUIRED via Test-Fake"
   ist ohne überschreibbare Resolution-API nicht implementierbar.

Dieser Workstream ergänzt eine konfigurierbare Capability-Quelle —
**beide gleichzeitig CLI-Flag und YAML-Eintrag** — die zu
`InvalidConfig` führen kann, wenn der Operator unparsable oder
inkonsistent konfiguriert. Im Zuge dessen wird `RoutineCapability`
zu einer **sealed `EffectiveRoutineCapability` (Valid / Invalid)**
umstrukturiert, sodass die Invaliditäts-Signalisierung Teil des
Typsystems wird statt erst im Renderer-`when`-Zweig zu landen.

## 2. Scope

### In Scope

- **Konfigurations-Quellen (beide gleichzeitig)**:
  - CLI-Flag `--routine-capability` (repeatable, key=value-Listen
    pro Routineart) — Beispiel siehe §6.1.
  - YAML-Eintrag `routineCapability:` in der existing
    `.d-migrate.yaml`-Datei (resolved via `EffectiveConfigPathResolver`,
    wie i18n und named connections) — Schema siehe §6.2.
  - **Präzedenz**: CLI > YAML > Default-Resolver
    (`RoutineCapabilityDefaults.forMysqlServerVersion` fuer MySQL mit
    Live-Version, sonst `RoutineCapabilityDefaults.forDialect`).
    Pro Routineart einzeln gemerged (CLI für `function`, YAML für
    `procedure` kombiniert mit Default für nicht gesetzte Felder).
- **Sealed `EffectiveRoutineCapability`** in `hexagon:ports-read` —
  ersetzt das bisherige `RoutineCapability`-data-class als Vertrag,
  den `DdlGenerationOptions` hält. Siehe §6.3 (API-Skizze).
  Bisheriges `RoutineCapability` wird zu `EffectiveRoutineCapability.Valid`.
- **Parser/Validator** in `hexagon:application` (nicht ports-read,
  weil er CLI-/YAML-spezifische String-Eingaben verarbeitet, nicht
  Driver-Verträge): `RoutineCapabilityConfigParser`. Bei Syntax-
  fehler / unparsablem `minServerVersion` / unbekanntem
  Schlüssel / Konflikt zwischen Quellen liefert er
  `EffectiveRoutineCapability.Invalid(reason)`.
- **Verkabelung**: `SchemaMigrateRenderPipeline.buildRenderOptions`
  (Zeile 154–181) ruft Parser-Resolution statt des Default-Resolvers
  direkt und übergibt das Ergebnis an
  `DdlGenerationOptions.routineCapability` (Typ wird zu
  `EffectiveRoutineCapability`).
- **Renderer-Anpassung**: `MysqlDiffRoutineOps.resolveCapability`
  pattern-matcht jetzt zuerst auf `Invalid`, bevor die Kind-spezifische
  `resolve()`-Logik läuft. Erlaubt das C.2-Coverage-Pinning, weil
  der `Invalid`-Pfad durch sealed-class-Konstruktion im Test
  trivial erzeugt werden kann.
- **Tests**:
  - `RoutineCapabilityConfigParserTest` pinnt fünf Negativ-Eingaben.
  - `MysqlDiffRoutineOpsTest` pinnt den
    `ROUTINE_CAPABILITY_CONFIG_INVALID`-Pfad gegen
    `EffectiveRoutineCapability.Invalid("…")`.
  - End-to-End: `SchemaMigrateCommandTest` mit einer
    `.d-migrate.yaml`, die invalide `routineCapability` enthält →
    Exit-Code + Manifest-Block.

### Aus Scope

- Persistenz der gewählten Capability-Konfiguration im
  `migration-plan.v1`-Artefakt — gehört in den Plan-Artefakt-
  Schema-Workstream.
- Audit-Trail wer die Konfig zuletzt geändert hat — gehört zur
  CLI-/Job-Provenance-Infrastruktur.
- MySQL-Reverse-Read von Routine-Identity-Attributen — siehe
  `ImpPlan-0.9.7-mysql-routine-identity-reverse-read.md`.
- Validator-Regel "INVOKER + definer ist widersinnig" — siehe E.1
  §2 Carve-out.

## 3. Acceptance Criteria

- [ ] CLI-Flag `--routine-capability` ist implementiert und
      repeatable.
- [ ] YAML-Eintrag `routineCapability:` ist implementiert und
      via `EffectiveConfigPathResolver` geladen.
- [ ] Präzedenzregel CLI > YAML > Defaults ist verdrahtet und durch
      Test gepinnt.
- [ ] `EffectiveRoutineCapability` ist sealed (`Valid` / `Invalid`)
      und `DdlGenerationOptions.routineCapability` trägt diesen Typ.
- [ ] `RoutineCapabilityConfigParserTest` pinnt zumindest fünf
      Negativ-Eingaben (syntaktisch kaputt, unbekannter Schlüssel,
      unparsable Version, Konflikt CLI ↔ YAML für gleiche
      Routineart, doppelter Eintrag in derselben Quelle).
- [ ] `MysqlDiffRoutineOpsTest` pinnt den
      `ROUTINE_CAPABILITY_CONFIG_INVALID`-Pfad im Renderer mit
      `EffectiveRoutineCapability.Invalid(...)`.
- [ ] `SchemaMigrateCommandTest` End-to-End: invalide YAML-
      Capability → `ROUTINE_CAPABILITY_CONFIG_INVALID` Manifest-Block.
- [ ] CLI-Spec (`spec/cli-spec.md`) ist um das `--routine-capability`-
      Flag und den `.d-migrate.yaml`-Eintrag ergänzt.
- [ ] CHANGELOG-Eintrag.

## 4. Definition of Done

- AC §3 erfüllt.
- `make docker-test` + `make docker-coverage-gate` grün (≥90% pro
  Modul, inkl. der drei neuen Sub-Slices).
- Plan-Datei nach `docs/planning/done/` verschoben.
- API-Migrations-Notiz im CHANGELOG (siehe §7).

## 5. Sub-Slice-Schnitt

Der Workstream wird in **drei Sub-Slices** zerlegt, in dieser
Reihenfolge zu implementieren. Jeder Sub-Slice ist eigenständig
review-fähig und schliesst mit grünem `docker-test` /
`docker-coverage-gate` ab.

### Sub-Slice A — `EffectiveRoutineCapability` + Parser-Kern (hexagon)

Schwerpunkt: Typsystem und reiner Parser, keine Pipeline-Änderung.

- `hexagon:ports-read`:
  - Neuer sealed-Vertrag `EffectiveRoutineCapability` (siehe §6.3).
  - `RoutineCapability` wird zu `EffectiveRoutineCapability.Valid`
    umbenannt / portiert; bestehende Aufrufe in MysqlDiffRoutineOps,
    SchemaMigrateRenderPipeline, RoutineCapabilityDefaults, Tests
    folgen der neuen Form.
	  - `RoutineCapabilityDefaults` liefert weiterhin `Valid`-Instanzen;
	    das Verhalten der Defaults bleibt wie in E.1 F.11 gepinnt
	    (Oracle MySQL konservativ, MariaDB live aktiviert).
- `hexagon:application` (neues Package
  `dev.dmigrate.server.application.routine`):
  - `RoutineCapabilityConfigParser` — reiner String→Resolution-
    Parser (kein I/O). Eingabe: Map `kind → key=value`-Liste +
    optionale YAML-Map (beide bereits zerlegt). Ausgabe:
    `EffectiveRoutineCapability`.
- Tests:
  - `RoutineCapabilityConfigParserTest` (5 Negativ + 3 Positiv).
  - Update `RoutineCapabilityTest` für neue sealed-Form.
  - Update `MysqlDiffRoutineOpsTest`-Fixtures (Konstruktion
    via `EffectiveRoutineCapability.Valid(...)`).
- **Nicht** in diesem Sub-Slice: CLI-Flag, YAML-Loading, Pipeline-
  Verdrahtung. Der bisherige Default-Pfad bleibt verkabelt.

### Sub-Slice B — Pipeline-Wiring + CliConfig-YAML-Eintrag

Schwerpunkt: Konfig-Quellen einlesen, mergen, präzedieren.

- `adapters/driving/cli`:
  - Neuer `RoutineCapabilityConfigResolver` in
    `dev.dmigrate.cli.config` (analog zu `I18nSettingsResolver`):
    lädt `.d-migrate.yaml` über `EffectiveConfigPathResolver`,
    parst Sektion `routineCapability:` mit SnakeYAML, übergibt
    rohe Maps an den Parser aus Sub-Slice A.
  - `SchemaMigrateCommand` (und andere `schema-migrate`-relevante
    Commands, sofern betroffen) erhält Option
    `--routine-capability` (multiple, clikt). Roh-Strings werden
    an den Resolver durchgereicht.
- `hexagon:application`:
  - `SchemaMigrateRenderPipeline.buildRenderOptions` erhält
    `EffectiveRoutineCapability` als Parameter (statt selbst zu
    konstruieren). `SchemaMigrateRunner` reicht das Resolver-Ergebnis
    durch.
	  - Default-Pfad bleibt funktionsfähig: ohne CLI-Flag und ohne
	    YAML-Eintrag liefert der Resolver fuer MySQL mit Live-Version
	    `RoutineCapabilityDefaults.forMysqlServerVersion(mysqlServerVersion)`,
	    sonst `RoutineCapabilityDefaults.forDialect(dialect)` (also `Valid`).
- Tests:
  - `RoutineCapabilityConfigResolverTest` pinnt
    Präzedenz CLI > YAML > Defaults und „kein Eintrag" =
    `Valid(defaults)`.
  - `SchemaMigrateRenderPipelineTest`-Fixture-Update für den
    neuen Konstruktor-Parameter.
- **Nicht** in diesem Sub-Slice: End-to-End-Renderer-Test mit
  echtem CLI-Run. Renderer wird weiterhin durch Sub-Slice-A-
  Negativtests indirekt abgedeckt; der echte CLI-E2E-Pin folgt in C.

### Sub-Slice C — Renderer-Pin + Acceptance-Test

Schwerpunkt: das eigentliche E.1-Slice-C.2-Coverage-Loch schliessen.

- `adapters/driven/driver-mysql`:
  - `MysqlDiffRoutineOpsTest` neuer Test:
    `routineCapability = EffectiveRoutineCapability.Invalid("bad
    config")` → `MysqlDiffRoutineOps.renderReplace` emittiert
    `ROUTINE_CAPABILITY_CONFIG_INVALID` MANUAL_ACTION_REQUIRED
    Block mit dem Reason-String im Body.
- `adapters/driving/cli`:
  - `SchemaMigrateCommandTest` End-to-End:
    `.d-migrate.yaml` mit unparsable `routineCapability:` →
    Exit-Code des Commands + Manifest enthält
    `ROUTINE_CAPABILITY_CONFIG_INVALID`-Block.
- Doku/Spec:
  - `spec/cli-spec.md`: Sektion für `--routine-capability` plus
    Verweis auf `.d-migrate.yaml`-Schema (§6.2 dieses Plans).
  - CHANGELOG-Eintrag (Breaking-Change-Notiz für
    `DdlGenerationOptions.routineCapability`, siehe §7).
- Plan-Datei nach `docs/planning/done/` verschieben.

## 6. Konkrete Spezifikation

### 6.1 CLI-Flag-Format

```
--routine-capability=<kind>:<key>=<value>[,<key>=<value>...]
```

- `<kind>` ∈ `function`, `procedure` (lowercase).
- Bekannte Keys: `enabled` (`true` / `false`),
  `minServerVersion` (semver-artig, parsbar via
  `MysqlServerVersion.parse`).
- Flag ist repeatable: pro `kind` maximal ein Vorkommen; doppeltes
  Vorkommen für dieselbe `kind` ⇒ `Invalid(reason="duplicate
  --routine-capability for kind=function")`.
- Unbekannte Keys ⇒ `Invalid`. Unbekannte `kind` ⇒ `Invalid`.

**Beispiele** (gültig):

```
--routine-capability=function:enabled=true,minServerVersion=8.0.0
--routine-capability=procedure:enabled=false
```

**Beispiel** (invalid, gepinnt durch Test):

```
--routine-capability=function:enabled=yes      # "yes" ist kein Bool
--routine-capability=function:enabled=true,minServerVersion=not-a-version
--routine-capability=trigger:enabled=true      # unbekannter kind
--routine-capability=function:foo=bar          # unbekannter key
--routine-capability=function:enabled=true \
--routine-capability=function:enabled=false    # duplicate kind
```

### 6.2 YAML-Schema (`.d-migrate.yaml`)

```yaml
# Optional. Fehlt der Eintrag, gelten RoutineCapabilityDefaults.
routineCapability:
  function:
    enabled: true
    minServerVersion: "8.0.0"   # nur fuer MySQL relevant
  procedure:
    enabled: false
```

- Auf Top-Level-Map mit Schluesseln `function` und `procedure`
  (beide optional; fehlend ⇒ Default).
- `minServerVersion` als String (vermeidet YAML-Float-Coercion,
  die `8.0` zu `8.0` numerisch macht). Andere Typen ⇒ `Invalid`.
- Unbekannte Top-Level-Schluessel unter `routineCapability:` ⇒
  `Invalid`. Unbekannte Keys pro Routineart analog.

### 6.3 Sealed `EffectiveRoutineCapability` (API-Skizze)

```kotlin
// hexagon/ports-read/.../driver/EffectiveRoutineCapability.kt
sealed interface EffectiveRoutineCapability {
    data class Valid(
        val function: RoutineKindCapability,
        val procedure: RoutineKindCapability,
    ) : EffectiveRoutineCapability {
        fun forKind(kind: RoutineKind): RoutineKindCapability = when (kind) {
            RoutineKind.FUNCTION -> function
            RoutineKind.PROCEDURE -> procedure
        }
    }

    /**
     * Konfigurations-Quelle (CLI oder YAML) ist strukturell
     * defekt. [reason] wird in den Renderer-Manifest-Block
     * uebernommen (ROUTINE_CAPABILITY_CONFIG_INVALID).
     */
    data class Invalid(val reason: String) : EffectiveRoutineCapability
}
```

`MysqlDiffRoutineOps.resolveCapability` (Auszug):

```kotlin
private fun resolveCapability(
    cap: EffectiveRoutineCapability,
    kind: RoutineKind,
    serverVersion: MysqlServerVersion?,
): RoutineCapabilityResolution = when (cap) {
    is EffectiveRoutineCapability.Invalid ->
        RoutineCapabilityResolution.InvalidConfig
    is EffectiveRoutineCapability.Valid ->
        cap.forKind(kind).resolve(serverVersion)
}
```

Damit wird `InvalidConfig` aus dem `RoutineCapabilityResolution`
(weiterhin `Active` / `Disabled` / `InvalidConfig`) erreichbar.
Die `InvalidConfig`-Variante in `RoutineCapabilityResolution` bleibt
bestehen — sie ist die Renderer-Sprache und wird jetzt produktiv
aus dem `Invalid`-Vertrag heraus erzeugt.

### 6.4 Modul-Grenzen

| Layer                          | Typ / Datei                                           |
|--------------------------------|-------------------------------------------------------|
| `hexagon:ports-read`           | `EffectiveRoutineCapability` (sealed, neu)            |
| `hexagon:ports-read`           | `RoutineCapabilityDefaults` (umstellen auf `Valid`)   |
| `hexagon:ports-read`           | `DdlGenerationOptions.routineCapability: Effective…`  |
| `hexagon:application`          | `RoutineCapabilityConfigParser` (rein, kein I/O)      |
| `hexagon:application`          | `SchemaMigrateRenderPipeline` (Konstr.-Parameter)     |
| `adapters/driven/driver-mysql` | `MysqlDiffRoutineOps.resolveCapability` (Pattern)     |
| `adapters/driving/cli`         | `RoutineCapabilityConfigResolver` (CLI + YAML I/O)    |
| `adapters/driving/cli`         | `SchemaMigrateCommand` (`--routine-capability` Flag)  |

`hexagon:core` bleibt unberuehrt (ZERO external deps).

## 7. Breaking-Change-Migration

`DdlGenerationOptions.routineCapability` wechselt von
`RoutineCapability` zu `EffectiveRoutineCapability`. Das ist
intern (Modul-private), aber alle Test-Fixtures und der Renderer-
Code-Pfad sind betroffen. Der Plan macht folgende Annahmen:

- `RoutineCapability` wird zu `EffectiveRoutineCapability.Valid`
  umbenannt (Verlust des Top-Level-Namens akzeptiert).
- `RoutineCapabilityDefaults.forDialect(dialect)` und
  `RoutineCapabilityDefaults.forMysqlServerVersion(...)` ändern die
  Rückgabe-Signatur zu `EffectiveRoutineCapability` (immer `Valid`).
  Alle Aufrufer (Pipeline, Tests) ziehen mit.
- Bestehende `RoutineCapability(...)`-Konstruktionen in Tests
  werden zu `EffectiveRoutineCapability.Valid(...)` mechanisch
  umgeschrieben (Sub-Slice A).

Migrationsnotiz im CHANGELOG:

> Internal API change: `DdlGenerationOptions.routineCapability` is
> now `EffectiveRoutineCapability` (sealed: `Valid` / `Invalid`)
> instead of `RoutineCapability`. The previous data class is
> available as `EffectiveRoutineCapability.Valid` with identical
> fields. No user-facing migration; affects only embedders /
> extension code.

## 8. Risiken / Stolperfallen

- **YAML-Coercion `minServerVersion: 8.0` → Float**: Parser muss
  Float-Eingaben ablehnen und `Invalid` produzieren — Negativ-Pin
  in `RoutineCapabilityConfigParserTest`.
- **`MysqlServerVersion.parse` semantics**: Falls der Parser
  Patch-Level zwingend braucht (`8.0` vs `8.0.0`), spiegelt das
  Plan-Test wider.
- **Backwards-Compat mit existing E.1-Tests**: alle
  `RoutineCapability(...)`-Konstruktionen müssen in **derselben PR**
  / demselben Sub-Slice (A) angepasst werden, sonst Build rot.
  Sub-Slice A muss atomar bauen.
- **Renderer-Pfad-Test ohne Parser-Roundtrip**: Sub-Slice C pinnt
  den Renderer direkt via `EffectiveRoutineCapability.Invalid(...)`,
  nicht über Parser → Resolver → Renderer. Letzteres deckt der
  E2E-`SchemaMigrateCommandTest` ab.
- **Kover-Coverage-Gate für Sub-Slice A**: `Invalid`-Konstruktor
  wird nur im Parser-Negativfall instantiiert. Sicherstellen, dass
  alle fünf Negativ-Eingaben unterschiedliche `Invalid.reason`-
  Pfade triggern (sonst zählt Kover Branches nicht voll).
- **Konflikt-Erkennung CLI ↔ YAML**: spezifizieren, ob CLI für
  `function` UND YAML für `function` als „Konflikt" gilt (⇒
  Invalid) oder als „CLI gewinnt" (⇒ Valid). Plan: **CLI gewinnt
  pro Routineart**, kein Conflict-Invalid. Test pinnt das.

## 9. Out-of-Scope-Verweis

- MySQL-Reverse-Read von Routine-Identity-Attributen — siehe
  `ImpPlan-0.9.7-mysql-routine-identity-reverse-read.md` (separater
  Carve-out).
- Validator-Regel "INVOKER + definer ist widersinnig" — siehe E.1
  §2 Carve-out.
- Persistenz der Capability-Config im Plan-Artefakt — Plan-
  Artefakt-Schema-Workstream.
