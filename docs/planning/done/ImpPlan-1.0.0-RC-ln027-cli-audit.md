# ImpPlan 1.0.0-RC — LN-027: Audit-Logging der CLI-DB-Operationen

> Status: **DONE / graduiert** (2026-07-11, Review 1 eingearbeitet; siehe „## Closure"). Schließt den letzten
> [`LN-027`](../../../spec/lastenheft-d-migrate.md#ln-027)-Gap: die Audit-Kette ist
> verdrahtet, emittiert aber nur im MCP-Dispatcher — die CLI-DB-Operationen
> emittieren keine Audit-Events (Roadmap-Milestone-1.0.0-RC, Fußnote ⁴).

## Kontext / Ist-Stand

Der Audit-Kern existiert vollständig (gebaut in `ImpPlan-0.9.6-A`):
`AuditSink` (Port, `hexagon:ports-common`) · `AuditEvent` (`hexagon:core`) ·
`AuditScope.around(context, fields, block)` (around/finally-Emitter,
`hexagon:application`) · `AuditContext` · `AuditFields` · `SecretScrubber` ·
`LoggingAuditSink` (SLF4J, serialisiert bereits einzeiliges JSON; Doc:
„Persistent sinks (DB, file) follow"). Emittiert wird nur im
MCP (`McpServiceImpl`). Modul-Abhängigkeiten bereits verdrahtet
(`cli → hexagon:application + driver-common + audit-logging`).

## Spec-Vertrag (Zielbild)

[`connection-config-spec.md`](../../../spec/connection-config-spec.md) `logging.audit`:

```yaml
logging:
  audit:
    enabled: true                 # Audit-Log für DB-Operationen (Default: false)
    file: ".d-migrate/audit.log"  # Audit-Log-Datei
```

(Die separate `ai.audit`/`directory`-Config ist der KI-Audit-Trail — Nicht-Scope.)

## Scope (user-abgestimmt 2026-07-11)

- **Format:** JSONL — ein `AuditEvent`-JSON-Objekt pro Zeile (dieselbe
  Serialisierung wie `LoggingAuditSink`).
- **Kriterium:** genau die Operationen, die eine **DB-Verbindung öffnen**.
- **Default:** opt-in — ohne `logging.audit.enabled: true` kein Audit, keine Datei.

### Operationsliste + Audit-Bedingung

| Operation | `toolName` | Audit-Bedingung |
|---|---|---|
| `schema reverse` | `schema.reverse` | immer (liest DB) |
| `schema migrate` | `schema.migrate` | immer (führt gegen DB aus / preflight-probes) |
| `schema rollback` | `schema.rollback` | **nur `--execute`** (dry-run öffnet keine DB) |
| `schema compare` | `schema.compare` | **nur wenn ≥1 Operand `CompareOperand.Database`** |
| `data export` | `data.export` | immer (Quelle = DB) |
| `data import` | `data.import` | immer (Ziel = DB) |
| `data transfer` | `data.transfer` | immer (DB→DB) |
| `data profile` | `data.profile` | immer (liest DB) — Review-Fix: DB-berührend, gehört dazu |

## Nicht-Scope

- KI-Audit-Trail (`ai.audit`/`directory`) — separater Vertrag.
- Datei-only-Operationen ohne DB-Zugriff: `generate`, `validate`, `compare
  file/file`, `rollback` dry-run, `export flyway|liquibase|django|knex`.
- [`LN-028`](../../../spec/lastenheft-d-migrate.md#ln-028) (RBAC), Log-Rotation/Retention (Betrieb; Datei wächst append-only).

## Architektur-Entscheidungen (Review 1)

**E1 — CLI ist exit-code-getrieben, nicht exception-getrieben.** `AuditScope.around`
leitet `SUCCESS/FAILURE` aus geworfenen Exceptions ab (`AuditScope.kt:41-59`,
`buildEvent`-Outcome via `errorCode`). Die CLI-Runner werfen nicht — sie fangen
alles und geben `Int`-Exit-Codes zurück (z. B. `DataTransferRunner` `return 4/5/3`,
`SchemaReverseRunner` `return 4/7/2`). In `around{}` gewickelt kehrt der Block
**immer** normal zurück → jedes Event wäre `SUCCESS`. **Deshalb NICHT `AuditScope`
für die CLI**, sondern ein eigener **exit-code-getriebener `CliAuditRecorder`**:
`record(toolName, resourceRefs, block: () -> Int): Int` — misst Dauer, ruft
`block()`, mappt Exit `0 → SUCCESS`, `≠0 → FAILURE`, emittiert und gibt den
`Int` unverändert zurück. `AuditScope` bleibt der MCP-Pfad (unverändert).

**E2 — Exit-Code im Event (kein lossy ToolErrorCode-Mapping).** CLI-Exit-Codes
(2/3/4/5/7/8) haben keine 1:1-Abbildung auf `ToolErrorCode` (MCP-Taxonomie).
`AuditEvent` wird **additiv** um `exitCode: Int? = null` erweitert; CLI setzt ihn,
MCP lässt ihn `null` (backward-compatible; nur additives JSON-Feld). `errorCode`
(ToolErrorCode) bleibt CLI-seitig `null`.

**E3 — Audit ist best-effort.** Wirft `JsonlFileAuditSink.emit` (Rechte, Platte
voll), darf das die Operation **nicht** abstürzen lassen. `CliAuditRecorder`
fängt Sink-Fehler, loggt eine Warnung (`dev.dmigrate.audit`-Logger) und gibt den
ursprünglichen Exit-Code zurück. (Gegensatz zu `AuditScope`, das im `finally`
emittiert und Sink-Fehler propagiert.)

**E4 — Wrap-Grenze = `Wiring.execute` (ganze Invocation).** Auch
Pre-Connection-Validierungsfehler (z. B. `data transfer` bad `--filter` → Exit 2)
werden so mit-auditiert — konsistent mit dem „early failures"-Design und durch das
Exit-Code-Mapping (E1) korrekt als `FAILURE`/`exitCode=2` erfasst.

**E5 — Scrubbing nur einmal, via `SecretScrubber`.** Der Recorder scrubbt
`resourceRefs` über `SecretScrubber::scrub` (fängt zusätzlich `Bearer`/`tok_`, die
`LogScrubber` nicht kennt). Wirings reichen **rohe** Refs durch — kein
Vor-Scrubbing.

**E6 — Injektionsnaht.** `CliAuditRecorder` wird pro Wiring als Parameter injiziert
(Default = aus `AuditSettings` gebaute Instanz oder `NoOpCliAuditRecorder` bei
opt-out), analog zum bestehenden `*WiringFactory`-Muster. Tests injizieren einen
`RecordingCliAuditRecorder` (In-Memory) → Unit-Assertion je Event; zusätzlich ein
End-to-End-Test über Temp-Config (`enabled:true`) + Temp-Datei.

## Komponenten

1. **`AuditEvent` + `exitCode: Int?`** (`hexagon:core`) — additiv; `AuditEventTest`
   + `AuditSinkContractTests`/`InMemoryAuditSink` nachziehen.
2. **`AuditEventJson`** (`adapters/driven/audit-logging`) — Serializer aus
   `LoggingAuditSink` extrahiert (unverändertes Output + `exitCode` wenn gesetzt);
   `LoggingAuditSink` + `JsonlFileAuditSink` nutzen ihn.
3. **`JsonlFileAuditSink`** (`adapters/driven/audit-logging`) — append
   `AuditEventJson.serialize(event) + "\n"`, Parent-Verzeichnis anlegen.
4. **`AuditSettingsResolver`** (`adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/AuditSettingsResolver.kt`) — liest
   `logging.audit.enabled` (Default `false`) + `logging.audit.file` (Default
   `.d-migrate/audit.log`); Muster wie `I18nSettingsResolver`.
5. **`CliAuditRecorder`** (`adapters/driving/cli`) — Interface + `Default`
   (baut Event, scrubbt, emittiert best-effort) + `NoOp` (ruft `block()` direkt).
6. **Verdrahtung** der 8 Wirings gemäß Bedingungstabelle.

## Phasen (AP)

- **AP1 — Core + Sink:** `AuditEvent.exitCode` + `AuditEventJson`-Extraktion +
  `JsonlFileAuditSink` + Unit-Tests (append/Verzeichnis/JSONL/exitCode-Feld;
  `AuditSinkContractTests` grün; `LoggingAuditSink`-Output unverändert).
- **AP2 — Config-Resolver:** `AuditSettingsResolver` + Tests (Defaults, fehlende
  Section, non-mapping-Fehler).
- **AP3 — Recorder:** `CliAuditRecorder` (Default/NoOp) + Tests: Exit `0 → SUCCESS`,
  `≠0 → FAILURE + exitCode`, Refs SecretScrubbed, Sink-Fehler best-effort
  geschluckt, opt-out → block direkt (kein Event/keine Datei).
- **AP4 — Wiring:** Recorder in die 8 Wirings (bedingt für compare/rollback);
  Wiring-Tests je SUCCESS **und** FAILURE mit korrektem `toolName`/`exitCode`/
  gescrubbten Refs (In-Memory-Recorder) + 1 E2E-Test (Temp-Config+Datei).
- **AP5 — Doku/Spec/Roadmap:** User-Guide (Audit aktivieren), Spec `logging.audit`
  **Default `false` ergänzen** (Review-Fix), CHANGELOG, Roadmap [`LN-027`](../../../spec/lastenheft-d-migrate.md#ln-027) 🚧⁴ → ✅.

## Definition of Done

- `logging.audit.enabled: true` → jede der 8 DB-Ops (compare/rollback bedingt)
  schreibt genau ein JSONL-`AuditEvent` (`outcome` aus Exit-Code, `exitCode`,
  gescrubbte Refs, `durationMs`) nach `logging.audit.file`.
- opt-out (Default) → kein Event, keine Datei, Bestandsverhalten byte-identisch.
- Audit-Schreibfehler crasht die Operation nicht (best-effort, E3).
- Keine geheimen Werte in der Audit-Datei (SecretScrubber verifiziert).
- Alle berührten Module `:check` grün (test + detekt + koverVerify-90%); `docs-check` grün.
- Roadmap [`LN-027`](../../../spec/lastenheft-d-migrate.md#ln-027) auf ✅; Spec-Default dokumentiert.

## Closure (2026-07-11)

Alle AP erledigt:
- **AP1** — `AuditEvent.exitCode: Int?` (additiv); `AuditEventJson` aus `LoggingAuditSink`
  extrahiert (Output unverändert); `JsonlFileAuditSink` (append, Parent-Anlage) +
  `JsonlFileAuditSinkTest` (inkl. byte-identisch zu `LoggingAuditSink`).
- **AP2** — `AuditSettingsResolver` (`logging.audit`, Default `false`/`.d-migrate/audit.log`) +
  Test (Defaults/fehlende Section/non-mapping/non-boolean).
- **AP3** — `CliAuditRecorder` (Default exit-code-getrieben + best-effort + `SecretScrubber`;
  `NoOp`; `cliAuditRecorder(configPath)` best-effort auf Config-Fehler) + `CliAuditRecorderTest`
  (SUCCESS/FAILURE/Scrub/Sink-Fehler/Block-Throw/NoOp/E2E).
- **AP4** — Recorder in alle 8 Wirings (compare bedingt auf DB-Operand via `CompareOperandParser`,
  rollback bedingt auf `--execute`); Body je nach `executeInner` extrahiert (non-inline-Lambda-safe);
  Metadaten-Tests (SpyRecorder) + E2E; `cli:check` grün.
- **AP5** — Spec-Default ergänzt, `administrationshandbuch` §8.1/§6.6, CHANGELOG, Roadmap [`LN-027`](../../../spec/lastenheft-d-migrate.md#ln-027) ✅.

Review-1-Blocker (Exit-Code vs Exception) gelöst via eigenem exit-code-getriebenem
`CliAuditRecorder` (nicht `AuditScope`) + additivem `AuditEvent.exitCode`. Alle 7 Review-Punkte
im Plan oben adressiert (E1–E6 + Bedingungstabelle + Spec-Default).
