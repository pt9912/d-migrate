# MCP-E2E-Harness

Reproduzierbare End-to-End-Prüfung des `d-migrate`-MCP-Servers gegen das
**echte, gebaute Runtime-Image** — docker-compose + Bash-Skripte, exakt
analog [`../sample-db/`](../sample-db/README.md) und
[`../bi-demo/`](../bi-demo/README.md): **kein** Testcontainers, **kein**
Gradle-Testmodul.

- Plan: [`../../docs/planning/done/mcp-real-e2e-scope-matrix.md`](../../docs/planning/done/mcp-real-e2e-scope-matrix.md) (Teil B)

## Warum es diesen Harness gibt

Zwei bestehende Testebenen decken das MCP-Protokoll bereits ab
(In-Process-Szenarien, JVM-Real-Subprozess in `test/e2e-cli`), aber **keine
läuft gegen das tatsächlich gebaute Docker-Image** — genau die Ebene, die
Packaging-spezifische Defekte findet (fehlende Dateien im Image,
Non-root-Berechtigungen, Native-Image-Reflection-Lücken). Ausgelöst durch
eine manuelle Live-Prüfung, bei der `connections/list` fälschlich als
"nicht erreichbar" gemeldet wurde — der Code war korrekt, aber es gab
keinen automatisierten Beleg dafür.

## Was der Smoke prüft

`scripts/smoke-scope-matrix.sh` fährt `mcp serve --transport stdio` als
echten Container-Prozess (`docker compose run -T`, NDJSON-Requests per
stdin, Stdin-EOF beendet den Server sauber):

- **`admin`-Token** (alle Scopes via `isAdmin`): **alle 31 Einträge** aus
  `McpServerConfig.DEFAULT_SCOPE_MAPPING` (dieselbe Matrix wie Teil As
  `McpScopeEnforcementMatrixTest.kt` — hier im Skript gespiegelt, da Bash
  die Kotlin-Map nicht zur Laufzeit introspektieren kann) dürfen nicht
  scope-verweigert werden. Für die fünf `*_start`-Tools
  (`schema_reverse_start`, `schema_compare_start`, `data_profile_start`,
  `data_import_start`, `data_transfer_start`) zählt `POLICY_DENIED` (kein
  `--policy-file` verdrahtet, fail-closed-Default) ausdrücklich als
  Erfolg — es beweist, dass die Scope-Prüfung durchließ.
- **`connections/list?checkLive=true`** gegen den echten `postgres`-Service
  — erwartet `REACHABLE` für die konfigurierte `mcp_e2e_pg`-Verbindung.
- **`noscope`-Token** (keine Scopes): **alle 31 Einträge** müssen
  scope-verweigert werden, in der jeweils passenden Form (7
  JSON-RPC-Protokollmethoden → `InvalidRequest`, 24 Tool-Namen via
  `tools/call` → `FORBIDDEN_PRINCIPAL` mit dem korrekten Scope-Namen).

Deckt sich vollständig mit Teil A
(`test/e2e-cli/.../McpScopeEnforcementMatrixTest.kt`) — dieser Harness
prüft dieselbe Matrix zusätzlich gegen das echte, gebaute Image.

## Benutzung

```sh
make docker-build IMAGE_TAG=dev   # einmalig: d-migrate:dev-Runtime-Image
make mcp-e2e-smoke                # up + voller Scope-Matrix-Lauf
make mcp-e2e-down                 # Container stoppen (Volume bleibt)
make mcp-e2e-purge                # Container + Volume entfernen
```

Voraussetzungen am Host: `docker`, `docker compose`, `jq`. Der Stack bleibt
nach dem Lauf stehen (Cleanup über `mcp-e2e-down`/`-purge`).

## Sicherheit

`stdio-tokens.yaml` und die Rohtoken in `scripts/smoke-scope-matrix.sh` sind
**fest verdrahtete Dev-Only-Werte** für einen lokalen, isolierten
Compose-Stack — keine echten Secrets, nicht für Produktion.
