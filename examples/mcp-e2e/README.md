# MCP-E2E-Harness

Reproduzierbare End-to-End-Prüfung des `d-migrate`-MCP-Servers gegen das
**echte, gebaute Runtime-Image** — docker-compose + Bash-Skripte, exakt
analog [`../sample-db/`](../sample-db/README.md) und
[`../bi-demo/`](../bi-demo/README.md): **kein** Testcontainers, **kein**
Gradle-Testmodul.

- Plan: [`../../docs/planning/done/mcp-real-e2e-scope-matrix.md`](../../docs/planning/done/mcp-real-e2e-scope-matrix.md) (Teil B)

## Die Datenbanken

Der Stack traegt **alle fuenf Dialekte**, die d-migrate unterstuetzt:

| Dienst | Dialekt | Start |
| ------ | ------- | ----- |
| `postgres` | PostgreSQL | schnell |
| `mysql` | MySQL | schnell |
| `mssql` | SQL Server | ~30 s |
| `oracle` | Oracle | 2-3 Minuten, nur unter `--profile oracle` |
| — | SQLite | kein Dienst: eine Datei unter `out/` |

`make mcp-e2e-up` startet die drei schnellen; Oracle kommt nur mit
`mcp-e2e-roundtrip-oracle` dazu, weil sein Kaltstart die uebrigen Pfade
aufhalten wuerde.

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
  scope-verweigert werden. `policy-rules.yaml` (universelle Allow-Regel)
  hebt zusätzlich den fail-closed-Policy-Default auf — vier der fünf
  `*_start`-Tools (`schema_reverse_start`, `schema_compare_start`,
  `data_profile_start`, `data_transfer_start`) bekommen echte,
  tenant-scoped Argumente und laufen damit **wirklich durch**: kein
  `POLICY_DENIED`, keine `VALIDATION_ERROR`, sondern ein echter Job
  (`jobId` + `resourceUri`) gegen die echte `postgres`-Verbindung.
  `data_import_start` bleibt bewusst bei `VALIDATION_ERROR` stehen — sein
  Handler löst `artifactId`/`sourceArtifactRef` gegen den Artefakt-Store
  auf, bevor `PolicyService` je aufgerufen wird; das bräuchte eine vorab
  angelegte Upload-Session (mehrere zusätzliche Aufrufe), keinen
  Argument-Fix.
- **`connections/list?checkLive=true`** gegen den echten `postgres`-Service
  — erwartet `REACHABLE` für die konfigurierte `mcp_e2e_pg`-Verbindung.
- **`noscope`-Token** (keine Scopes): **alle 31 Einträge** müssen
  scope-verweigert werden, in der jeweils passenden Form (7
  JSON-RPC-Protokollmethoden → `InvalidRequest`, 24 Tool-Namen via
  `tools/call` → `FORBIDDEN_PRINCIPAL` mit dem korrekten Scope-Namen).

Deckt sich vollständig mit Teil A
(`test/e2e-cli/.../McpScopeEnforcementMatrixTest.kt`) — dieser Harness
prüft dieselbe Matrix zusätzlich gegen das echte, gebaute Image.

## Cross-Dialekt-Hin-und-Her (`smoke-cross-dialect-roundtrip.sh`)

Der Scope-Smoke prueft MCP-Scopes gegen **eine** Postgres-Verbindung;
`examples/sample-db/` prueft Cross-Dialekt-Migrationen ohne diese
Dialekt-Matrix. Dieses Skript schliesst die Luecke: der ganze Weg

    Schema -> generate --target X -> anwenden -> reverse -> compare

fuer jeden Dialekt, gegen echte Server und das echte Image.

**Warum die CLI und nicht MCP.** Migrationen brauchen ein „DDL anwenden";
die MCP-Tools kennen das nicht (sie arbeiten ueber Artefakte und Jobs). Fuer
die Schema-*Qualitaet* ist die Ebene gleichgueltig — geprueft wird das Image
und der Server, nicht das Transportmittel. Der MCP-Weg bleibt beim
Scope-Smoke.

**Was es prueft.** Nicht nur „laeuft durch", sondern dass der Vergleich
nichts meldet, was keine Aenderung ist: das Skript faellt, sobald ein
**unveraenderter Fremdschluessel** als geaendert erscheint. Genau den hatte
ein Konsumentenprojekt gemeldet — SQL Server liest `ON DELETE NO ACTION`
explizit aus dem Katalog, PostgreSQL laesst die Aktion weg.

Die Quellfixture traegt zwei Konstrukte, an denen zwei offene
Konsumentenbefunde haengen: ein benanntes UNIQUE auf einer **ungebundenen**
`text`-Spalte (`uq_customer_external_ref`) und die Sicht `order_summary`.
Ohne sie sieht der Vergleich sie nicht — und der Reverse liest Sichten nur mit
`--include-views`, das der Lauf deshalb setzt.

Gemessener Stand (2026-09-15, `d-migrate:dev` aus `main`, 1.8.0-SNAPSHOT):

| Dialekt | Funde | Zusammensetzung |
| ------- | ----- | --------------- |
| PostgreSQL | 0 | perfekter Round-Trip |
| MySQL | 4 | 3 Tabellen, 1 Typ |
| SQL Server | 4 | 3 Tabellen, 1 Typ |
| SQLite | 5 | 3 Tabellen (Typdynamik), 1 Typ, 1 Sicht |
| Oracle | 4 | 3 Tabellen (Identity-Metadaten, Enum inline), 1 Typ |

Die Funde sind **erwartet und erklaert**, nicht unterdrueckt — der Harness
pinnt sie nicht, er zeigt sie. Was er **verbietet**, ist der FK-Fehlalarm.

**Jeder Dialekt wird angewandt.** Der Lauf faehrt die erzeugte DDL gegen den
echten Server, bevor er zurueckliest — PostgreSQL, MySQL und SQL Server ueber
ihren Client im Container, **SQLite** ueber `sqlite3` auf dem Host (die Datei
liegt im gemounteten `out/`) und **Oracle** ueber `sqlplus` im Oracle-Image.
Ohne diesen Schritt laese der Reverse eine leere Datenbank zurueck, und jede
Fundzeile waere eine Aussage ueber den fehlenden Apply statt ueber das Schema.

## Benutzung

```sh
make docker-build IMAGE_TAG=dev   # einmalig: d-migrate:dev-Runtime-Image
make mcp-e2e-smoke                # up + voller Scope-Matrix-Lauf
make mcp-e2e-roundtrip            # Hin-und-Her-Migrationen, alle schnellen Dialekte
make mcp-e2e-roundtrip-oracle     # dasselbe mit Oracle (2-3 Min Kaltstart extra)
make mcp-e2e-down                 # Container stoppen (Volume bleibt)
make mcp-e2e-purge                # Container + Volume entfernen
```

Voraussetzungen am Host: `docker`, `docker compose`, `jq` sowie **`sqlite3`**
(fuer den SQLite-Leg des Roundtrips — die Datenbank ist eine Datei, es gibt
keinen Dienst). Der Stack bleibt
nach dem Lauf stehen (Cleanup über `mcp-e2e-down`/`-purge`).

## Sicherheit

`stdio-tokens.yaml` und die Rohtoken in `scripts/smoke-scope-matrix.sh` sind
**fest verdrahtete Dev-Only-Werte** für einen lokalen, isolierten
Compose-Stack — keine echten Secrets, nicht für Produktion.
