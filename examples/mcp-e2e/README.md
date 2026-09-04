# MCP-E2E-Harness

Reproduzierbare End-to-End-Prüfung des `d-migrate`-MCP-Servers gegen das
**echte, gebaute Runtime-Image** — docker-compose + Bash-Skripte, exakt
analog [`../sample-db/`](../sample-db/README.md) und
[`../bi-demo/`](../bi-demo/README.md): **kein** Testcontainers, **kein**
Gradle-Testmodul.

- Plan: [`../../docs/planning/next/mcp-real-e2e-scope-matrix.md`](../../docs/planning/next/mcp-real-e2e-scope-matrix.md) (Teil B)

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

- **`admin`-Token** (alle Scopes via `isAdmin`): ein Vertreter pro
  Scope-Bucket (`resources/list`, `schema_reverse_start`, `job_cancel`,
  `data_import_start`, `artifact_upload_abort`, `testdata_plan`,
  `connections/list`) darf nicht scope-verweigert werden. Für die
  `*_start`-Tools zählt `POLICY_DENIED` (kein `--policy-file` verdrahtet,
  fail-closed-Default) ausdrücklich als Erfolg — es beweist, dass die
  Scope-Prüfung durchließ.
- **`connections/list?checkLive=true`** gegen den echten `postgres`-Service
  — erwartet `REACHABLE` für die konfigurierte `mcp_e2e_pg`-Verbindung.
- **`restricted`-Token** (nur `dmigrate:read`): `connections/list` — der
  einzige `dmigrate:admin`-gated Eintrag — muss scope-verweigert werden.

Die vollständige, aus `DEFAULT_SCOPE_MAPPING` generierte Matrix (alle 31
Einträge, beide Richtungen) ist Teil A
(`test/e2e-cli/.../McpScopeEnforcementMatrixTest.kt`) — dieser Harness
prüft dieselbe Logik zusätzlich gegen das gebaute Image, nicht redundant
exhaustiv.

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
