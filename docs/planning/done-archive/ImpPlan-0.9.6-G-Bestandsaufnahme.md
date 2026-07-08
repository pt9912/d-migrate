# Phase G — AP G.1 Bestandsaufnahme und Vertragsschnitt

> **Milestone**: 0.9.6 — Beta: MCP-Server
> **Phase**: G (`KI-nahe Tools, Prompts, Tests und Dokumentation`)
> **AP**: G.1 — Bestandsaufnahme und Vertragsschnitt
> **Datum**: 2026-05-07
> **Begleitdokument zu**: `ImpPlan-0.9.6-G.md` §6 G.1
> **Ergebnistyp**: Implementierungszuschnitt pro Modul, keine neue
> Parallelarchitektur

Plan §6 G.1 verlangt: bestehende MCP-Tool-Registry und Handler-
Struktur prüfen, vorhandene Policy-/Approval-/Idempotency-/Audit-/
Error-Services identifizieren, Artifact-/Resource-Resolver für
KI-Inputs erfassen, Modulgrenzen für KI-Port und Prompt-Hygiene
festlegen, fehlende Store-/Service-Abstraktionen dokumentieren.

Dieses Dokument fasst die Bestandsaufnahme zusammen und legt
die Modul-Pfade + Vertragsschnitte für G.2–G.10 verbindlich fest.

---

## 1. Bestand: Wiederverwendbar (keine Parallelarchitektur)

### 1.1 MCP-Wirepfade

| Komponente | Pfad | Wiederverwendung |
|---|---|---|
| Tool-Registry (Phase B) | `adapters/driving/mcp/.../registry/PhaseBRegistries.kt:59-83` | KI-Tools sind als Descriptors registriert (Z. 146-148), Handler ist heute `UnsupportedToolHandler` — G.6 ersetzt Handler-Mapping über `PhaseGRegistries.defaultToolRegistry(...)` analog zu `PhaseERegistries.defaultToolRegistry`. |
| Tool-Schema-Stubs | `adapters/driving/mcp/.../schema/PhaseBToolSchemas.kt:422-443` | Stub-Schemata für `procedure_transform_plan/execute`, `testdata_plan/execute` existieren — G.5 erweitert sie auf Plan-§-5.4–5.6-Form. |
| Scope-Mapping | `adapters/driving/mcp/.../server/McpServerConfig.kt:167,214-217` | Konstante `aiExecute = setOf("dmigrate:ai:execute")` und Zuordnung der vier KI-Tools sind fertig — keine Änderung in G.5/G.6 nötig. |
| `tools/list`, `tools/call`-Dispatch | `adapters/driving/mcp/.../protocol/McpServiceImpl.kt:164-208` | Inkl. `enforceStrictInputProperties` (additionalProperties=false) — KI-Tool-Schemas müssen alle Felder explizit listen. |
| Stdio-/HTTP-Transport | `adapters/driving/mcp/.../transport/{stdio,http}/` | Generisch — kein G-spezifisches Wiring. |

### 1.2 Querschnittsdienste (Phase A–F)

| Service / Store | Pfad | G-Verwendung |
|---|---|---|
| `PolicyService` + `ConfiguredPolicyService` | `hexagon/application/.../policy/PolicyService.kt`, `ConfiguredPolicyService.kt` | G.6: Policy-Required-Flow für alle drei KI-Handler. |
| `ApprovalGrantStore` | `hexagon/ports-common/.../ApprovalGrantStore.kt` | G.6: `correlationKind=approvalKey`-Bindung wie in F.3 (UploadInit-Pfad). |
| `SyncEffectIdempotencyStore` | `hexagon/ports-common/.../SyncEffectIdempotencyStore.kt` + In-Memory-Impl in `PhaseFInProcessStores.kt:15-79` | **Begrenzt wiederverwendbar** — Plan §6 G.6 fordert Parallel-Pending-Fix (Z. 1071-1073, 1084-1087). Siehe §3.1 unten. |
| `AuditScope`, `AuditFields`, `AuditContext` | `hexagon/application/.../audit/` | G.6/G.8: Around-Wrapper läuft schon für jeden `tools/call` (Phase E §7.10). KI-Handler schreiben Provider-/Modell-/Prompt-Fingerprint in `AuditFields`. |
| `SecretScrubber` | `hexagon/application/.../audit/SecretScrubber.kt` | G.4: Pattern-Library für Prompt-Hygiene wiederverwendbar. |
| `ToolErrorCode` | `hexagon/core/.../error/ToolErrorCode.kt` | G.4 + G.7: `PROMPT_HYGIENE_BLOCKED` (Z. 19) und `INTERNAL_AGENT_ERROR` (Z. 21) sind bereits drin — keine Enum-Erweiterung nötig. |
| `ErrorMapper` / `DefaultErrorMapper` | `hexagon/application/.../error/` | G.6/G.7: deckt vorhandene Codes ab; ggf. Mapping für neue typed Exceptions ergänzen. |
| `QuotaService` + `DefaultQuotaService` | `hexagon/application/.../quota/` | G.8: neue Quota-Dimension `AI_PROVIDER_REQUESTS` (Plan §6 G.8 Z. 1167-1170). |
| `SchemaStore`, `ProfileStore`, `ArtifactStore` | `hexagon/ports-common/.../*Store.kt` | G.5/G.6: Eingabe-Refs für `testdata_plan` (`schemaRef`, optional `profileRef`) und `procedure_transform_plan` (`schemaRef + procedureName`, `artifactRef`). |
| `ConnectionReferenceStore` | `hexagon/ports-common/.../ConnectionReferenceStore.kt` | G.6: optional, falls Plan-Quelle eine Connection adressiert. Heute nicht im Plan §5.4. |
| `Fingerprint`-Bausteine | `hexagon/application/.../fingerprint/` | G.4: deterministische Prompt-Fingerprints; Plan-/Execute-/Testdata-Fingerprints. |

### 1.3 Test-Infrastruktur

| Komponente | Pfad | G-Verwendung |
|---|---|---|
| stdio-IT-Pattern | `adapters/driving/mcp/.../integration/McpPhaseFStdioMultiSegmentUploadIT.kt` | G.9 stdio-IT für `procedure_transform_plan` → `procedure_transform_execute`-Roundtrip. |
| HTTP-IT-Pattern | `adapters/driving/mcp/.../integration/McpPhaseFHttpMultiSegmentUploadIT.kt` | G.9 HTTP-IT mit `testApplication` + `DisabledAuthValidator(principal=...)`. |
| Tool-Schema-Golden | `adapters/driving/mcp/src/test/resources/golden/phase-b-tool-schemas.json` | G.5 erweitert Snapshot um Phase-G-Felder. |
| `Fixtures` (PrincipalContext, tenant) | `hexagon/ports-common/.../testFixtures/.../Fixtures.kt` | G.6/G.9 für PrincipalContext mit `dmigrate:ai:execute`. |
| In-Memory-Stores | `hexagon/ports-common/src/testFixtures/.../memory/InMemory*Store.kt` | G.6/G.9 — alle Phase-F-Stores wiederverwendbar. |

---

## 2. Neue Module / Abstraktionen (G muss einführen)

| Modul / Klasse | Pfad | Eingeführt in AP |
|---|---|---|
| **`AiProviderPort`** + Datenmodelle | `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/ai/` | G.2 |
| `AiProviderRequest`, `AiProviderResult` (sealed) | gleicher Pfad | G.2 |
| `AiProviderError` (typed Failures, kein Stacktrace-Leak) | gleicher Pfad | G.2 |
| **`NoOpAiProvider`** (deterministische Defaults, kein Netzwerk) | gleicher Pfad | G.2 |
| **`AiProviderConfig`** + `AiProviderRegistry` | gleicher Pfad | G.3 |
| **`PromptHygieneService`** + `DefaultPromptHygieneService` | `hexagon/application/.../audit/prompt/` (Subpaket von audit/, weil Hygiene = Scrubbing-Verwandt) | G.4 |
| **`AiToolOutcomeStore`** Port + `InProcessAiToolOutcomeStore` | `hexagon/ports-common/.../AiToolOutcomeStore.kt` + `adapters/driving/mcp/.../registry/PhaseGInProcessStores.kt` | G.6 |
| **`AiArtifactMetadataStore`** Port + In-Memory-Impl | `hexagon/ports-common/.../AiArtifactMetadataStore.kt` + In-Memory-Impl | G.6 |
| **`PhaseGRegistries.defaultToolRegistry`** + Wiring | `adapters/driving/mcp/.../registry/PhaseGRegistries.kt` | G.6 |
| **`ProcedureTransformPlanHandler`**, **`ProcedureTransformExecuteHandler`**, **`TestdataPlanHandler`** | `adapters/driving/mcp/.../registry/` | G.6 |
| **`PromptRegistry`** + drei Pflichtprompts | `adapters/driving/mcp/.../prompts/` | G.7 |
| **`PromptsListHandler`**, **`PromptsGetHandler`** | `adapters/driving/mcp/.../prompts/` | G.7 |
| Erweiterung `McpServiceImpl` um `prompts/list`, `prompts/get` | `adapters/driving/mcp/.../protocol/McpServiceImpl.kt` (additiv) | G.7 |
| Erweiterung `McpServerConfig.DEFAULT_SCOPE_MAPPING` um `prompts/list`, `prompts/get` (`dmigrate:read`) | `McpServerConfig.kt` | G.7 |
| `capabilities.prompts` in `ServerCapabilities` | `McpServiceImpl.kt:135-140` | G.7 |
| Neue Limits: `maxAiPromptBytes`, `maxAiResponseBytes` | `McpLimitsConfig.kt` | G.3 |

---

## 3. Verbindliche Entscheidungen (Vertragsschnitt)

### 3.1 Parallel-Pending-Reserve-Fix

**Entscheidung**: G.6 erweitert den Vertrag von `SyncEffectIdempotencyStore`
um eine Single-Writer-Semantik **nicht** durch Plan-F-Store-Bruch,
sondern durch eine **G-spezifische Erweiterung**: ein neuer
`AiToolOutcomeStore` mit Lease-/Reclaim-Pattern (analog zu
`UploadInitClaimStore` in `PhaseFInProcessStores.kt:81-146`).

**Begründung**: Phase F-Pfade laufen sequentiell durch denselben
Wirepfad (Init → Upload → Finalize); parallele identische Init-Retries
sind selten und vom Grant-Issuer entkoppelt. Die KI-Pfade dagegen
verteilen `approvalKey` an Agent-Retry-Loops, die häufig parallel
identische Aufrufe absetzen — der „erneut Reserved"-Fehler des
heutigen Stores würde sonst doppelte Provider-Kosten erzeugen.

**Schnittstelle**: `AiToolOutcomeStore.acquire(scope, fingerprint, leaseDuration) → AcquireResult`
mit Outcomes:
- `Acquired(claimId, leaseExpiresAt)` — Single-Writer, andere Aufrufer warten
- `Existing(outcome: AiToolOutcome)` — terminales Ergebnis (SUCCEEDED/FAILED) replayen
- `Pending(claimId, leaseExpiresAt)` — anderer Caller hält die Lease, retrybar
- `Conflict(reason)` — anderer Payload-Fingerprint im selben Scope

`commit(claimId, outcome: AiToolOutcome)` → durabel speichern.
`reclaimExpired()` → Cleanup für Crash-Pfade.

### 3.2 ArtifactKind-Strategie

**Entscheidung**: Plan-§-5.4-Pfad-A (`ArtifactKind.OTHER` +
verpflichtendes `wireArtifactKind`/`aiIntent`/Provenance-Metadatenmodell).

**Begründung**: Pfad-B (Core-Enum-Erweiterung) ist breaking für
Phase-A–F-Goldens, Store-Contracts und Spec-Tabellen. Pfad-A ist
metadaten-tragend, blockiert keine bestehenden Tests und ist
spezifisch für KI-Artefakte mit eigener Provenance-Struktur.

**Persistenzort**: separater **`AiArtifactMetadataStore`** (nicht
ArtifactRecord-Extension), weil:
- KI-Provenance-Felder (Plan §5.4 Z. 739-747) sind reichhaltig
  (Prompt-Fingerprints, Plan-Refs, Provider-/Modell-Metadaten) und
  würden ArtifactRecord für Phase-A–F-Verwender aufblähen.
- `ArtifactStore.list()` filtert heute nach Core-`ArtifactKind` —
  KI-Listen brauchen Filter über `wireArtifactKind`, eine separate
  Store-API ist sauberer als ein optionaler Filter-Discriminator.
- Atomarität: `ArtifactStore.save()` + `AiArtifactMetadataStore.save()`
  laufen im selben durable Commit (gleicher Tx-Boundary wie heute
  `JobStore` + `IdempotencyStore`).

**Lifecycle**: Cleanup ist an Artefakt-Retention gebunden — wenn
`ArtifactStore.deleteExpired()` läuft, löscht es auch die zugehörigen
`AiArtifactMetadata`-Einträge (Phase F's `ArtifactRetentionService`
wird in G.6 erweitert).

### 3.3 Source-Resolution für `procedure_transform_plan`

Plan §5.4 erlaubt drei Eingabevarianten: `procedureRef`, `artifactRef`,
oder `schemaRef + procedureName`. **Entscheidung**: keine neue
`ProcedureStore`-Abstraktion; Resolution über bestehende Stores:
- `procedureRef` → ServerResourceUri auf einen `ArtifactRecord` mit
  `ArtifactKind.OTHER` + `wireArtifactKind=stored-procedure`
  (vom Caller via `artifact_upload_init` mit dieser Provenance hochgeladen)
- `artifactRef` → bestehender `ArtifactStore.findById`
- `schemaRef + procedureName` → `SchemaStore.findById` + Lookup im
  Schema-Inhalt durch den Handler (Schema enthält Procedure-Definitions)

**Carve-out**: Wenn ein Caller heute eine Procedure als rohen
SQL-Text hochlädt, ist er für die Provenance-Metadaten verantwortlich
(`wireArtifactKind=stored-procedure` beim Upload-Init setzen). Ein
separater Procedure-Upload-Tool ist nicht Teil von 0.9.6.

### 3.4 Provider-Konfiguration (G.3)

**Default**: `NoOpAiProvider` für jeden Tenant ohne explizite Konfig.
Tests, lokale Entwickler-Setups und ungeflaggte CI-Läufe nutzen ihn.

**Externe Provider** (OpenAI, Anthropic, Ollama, LM Studio): nur via
explizite YAML-Konfiguration **plus** Policy-Allow für `aiExecute`.
Konfigurationspfad: `--ai-provider-config <path>` analog zu
`--cursor-keyring-file`. Ohne Config bleibt fail-closed —
`procedure_transform_*`/`testdata_plan` mit nicht-konfiguriertem
Provider liefert `INTERNAL_AGENT_ERROR` (nicht `POLICY_DENIED`,
weil das Server-Config-Lücke vs. Policy-Lücke ist).

**Auth-pflichtige Provider** brauchen `secretRef`; lokale (Ollama/
LM Studio) erlauben `secretRef=null`.

---

## 4. Erste File-Skeleton-Liste für G.2

```
hexagon/application/src/main/kotlin/dev/dmigrate/server/application/ai/
├── AiProviderPort.kt              # fun interface AiProviderPort
├── AiProviderRequest.kt           # data class (prompt, model, refs, timeout, fp)
├── AiProviderResult.kt            # sealed (Success/Failure)
├── AiProviderError.kt             # enum (TIMEOUT, RATE_LIMITED, BAD_REQUEST, ...)
├── NoOpAiProvider.kt              # deterministischer Default
└── (G.3 später) AiProviderConfig.kt, AiProviderRegistry.kt
```

```
hexagon/application/src/test/kotlin/dev/dmigrate/server/application/ai/
└── NoOpAiProviderTest.kt          # deterministische Outputs, keine Netzwerkaufrufe
```

`AiProviderPort` Skeleton-Form (verbindlich):

```kotlin
fun interface AiProviderPort {
    suspend fun invoke(request: AiProviderRequest): AiProviderResult
}
```

`AiProviderResult`:

```kotlin
sealed interface AiProviderResult {
    data class Success(
        val output: String,
        val outputFingerprint: String,
        val providerMeta: ProviderMeta,
    ) : AiProviderResult

    data class Failure(
        val error: AiProviderError,
        val message: String,
        val retryable: Boolean,
    ) : AiProviderResult
}
```

---

## 5. Plan-Carve-outs aus G.6 (jetzt schon klar)

Diese werden nicht in G.6 gelöst, sondern nur dokumentiert /
verifiziert, weil sie entweder reine Audit-Pflicht sind oder einen
separaten AP brauchen würden:

- **Streaming-Provider-Aufrufe** (Plan §5 erwähnt sie nicht): Phase G
  bleibt synchron, `AiProviderPort.invoke` ist suspend, aber kein
  Token-Streaming. Streaming-Variante würde MCP-Push-Notifications
  brauchen (bisher `subscribe=false`, §12.16).
- **Multi-Provider-Failover**: Plan §5 enthält nur „optionale
  Provider-Auswahl"; kein Failover bei Timeout/Rate-Limit. Caller
  müssen retryen.
- **Cost-Tracking pro Provider**: Provider-Quota wird als
  `AI_PROVIDER_REQUESTS`-Counter modelliert, nicht als monetärer
  Wert. Cost-Tracking ist 0.9.7-Concern.

---

## 6. Akzeptanz für G.1

- ✅ Tool-Registry- und Handler-Struktur dokumentiert (§1.1).
- ✅ Policy-/Approval-/Idempotency-/Audit-/Error-Services
  identifiziert und mit AP-Zuordnung versehen (§1.2).
- ✅ Artifact-/Resource-Resolver für KI-Inputs erfasst (§1.2 +
  §3.3).
- ✅ Modulgrenzen für KI-Port und Prompt-Hygiene festgelegt (§2).
- ✅ Fehlende Store-/Service-Abstraktionen dokumentiert (§2 + §3).
- ✅ Verbindliche Entscheidungen für Carve-out-anfällige Punkte
  (Parallel-Pending, ArtifactKind, ProcedureStore, Provider-
  Konfiguration) getroffen (§3).
- ✅ Erste Datei-Skeletons für G.2 spezifiziert (§4).

Damit ist G.1 abgeschlossen — keine Code-Änderung erforderlich,
nur dieses Begleitdokument als Vertragsschnitt.

---

## 7. Folgende APs (unverändert aus Plan §6)

- **G.2** AiProviderPort + NoOpProvider
- **G.3** Provider-Konfiguration fail-closed
- **G.4** Prompt-Hygiene und Secret-Scrubbing
- **G.5** KI-nahe Tool-Schemas und Registry
- **G.6** KI-nahe Tool-Handler (mit AiToolOutcomeStore + AiArtifactMetadataStore)
- **G.7** Prompt-Registry und MCP-Prompt-Methoden
- **G.8** Quotas, Timeouts und Audit-Golden-Tests
- **G.9** 0.9.6-End-to-End-Testabdeckung
- **G.10** Dokumentation und Roadmap-Abschluss
