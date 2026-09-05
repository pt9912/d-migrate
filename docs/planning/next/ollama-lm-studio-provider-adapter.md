# Echter `AiProviderPort`-Adapter (Ollama) + Config-Fläche

> **Status:** Draft mit Scope (Design geklärt 2026-09-05)
> **Trigger:** Verifikation von `testdata_plan`/`testdata_execute` gegen die
> `d-migrate-mcp-sandbox` (echte Postgres-Verbindung, `server.state`-JDBC-Persistenz aktiv,
> Approval-Grant-Flow durchgespielt) zeigte: `AiMcpWiring` wird an beiden Call-Sites in
> `McpServeWiring.kt` (Zeilen ~223, ~288) ohne explizites `aiProviderRegistry`-Argument
> konstruiert und fällt damit auf den Default-Parameter `DefaultAiProviderRegistry.noOpOnly()`
> (`AiMcpWiring.kt:50`) zurück. Der generierte `testdata`-Output ist dadurch immer nur ein
> deterministischer `noop:noop:model=...:prompt=...:payload=...`-Platzhalter, nie echter,
> KI-generierter Inhalt — unabhängig davon, was `providerId`/`model` im Tool-Call anfragen. Für
> die Sandbox stand zufällig ein lokal laufender Ollama-Daemon (`localhost:11434`,
> Host-Prozess) bereit, der sich als naheliegender erster echter Provider angeboten hätte.

## Ziel

Ein erster echter `AiProviderPort`-Adapter für lokal laufende Ollama-Instanzen, der
`testdata_plan`/`testdata_execute` und `procedure_transform_plan`/`procedure_transform_execute`
optional gegen ein tatsächliches Modell laufen lässt, statt immer den `NOOP`-Platzhalter zu
liefern. `LmStudioProvider` ist bewusst **kein** Bestandteil dieses Schnitts (siehe
Owner-Entscheidung unten).

## Getroffene Entscheidungen (2026-09-05)

Die drei Design-Fragen, die einen Move nach `next/` blockiert hatten, sind entschieden:

1. **Konfigurationsvertrag**: kein eigenes `--ai-provider-config`-Flag. Der Provider-Block wird
   aus derselben Datei gelesen, die `--connection-config` bereits bekommt — analog dem
   `server.state.*`-Block, den `McpServeWiring.resolveServerStateConfigOrExit` aus genau dieser
   Datei liest. Neuer Top-Level-Schlüssel (Arbeitstitel `ai.provider.*`), kein neues CLI-Flag.
2. **Containerisierung vs. Loopback-Validator**: `mcp serve` läuft mit `--network host`, wenn ein
   `LOCAL_LOOPBACK`-Provider konfiguriert ist. `AiProviderConfigValidator.isLoopback()` bleibt
   unverändert streng (nur `localhost`/`127.0.0.1`/`::1`) — **keine** Aufweichung des Validators.
   Das ist eine Deployment-Vorbedingung, nicht eine Code-Änderung: wer `LOCAL_LOOPBACK`
   konfiguriert, betreibt den Container mit Host-Networking. Dokumentationspflicht (siehe P4
   unten) für Betreiber, die stattdessen Bridge-Networking nutzen — für die bleibt nur
   `EXTERNAL` (HTTPS + `secretRef`) nutzbar.
3. **Adapter-Scope**: nur `OllamaProvider`. `LmStudioProvider` ist zurückgestellt (kein
   `secretRef` nötig für Ollama, kleinerer erster Schnitt; `LM_STUDIO`-Konstante bleibt als
   Vorbereitung liegen, siehe `AiProviderId.kt:40`).

**Nicht entschieden — bewusst kein Termin:** ob und wann der Bau beginnt. Der Eigner hat
Meilenstein 1.5.5 nicht vorgezogen; dieser Plan ist scope-fertig, aber ohne Versions-/
Zeit-Commitment (siehe Vorbedingungen).

## Befund: was schon vorbereitet ist

- in `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/ai/AiProviderId.kt`:
  `OLLAMA`, `LM_STUDIO` sind bereits als
  `AiProviderId`-Konstanten definiert — Kommentar markiert sie explizit als
  "Vorbereitung, kein Adapter in 0.9.6".
- `AiProviderKind` (`NOOP`/`LOCAL_LOOPBACK`/`EXTERNAL`) + `AiProviderConfigValidator` sind
  fertig und getestet: `LOCAL_LOOPBACK` verlangt einen Endpoint, der **wörtlich**
  `localhost`/`127.0.0.1`/`::1` als Host trägt (`isLoopback()`), erlaubt `secretRef=null`,
  verbietet `allowExternalNetwork=true`. `EXTERNAL` verlangt HTTPS + `secretRef` +
  `allowExternalNetwork=true`.
- `AiProviderPort` (sync `fun interface`, `invoke(request): AiProviderResult`) ist stabil,
  ebenso `NoOpAiProvider` als Referenzimplementierung.
- `DefaultAiProviderRegistry` validiert Configs fail-closed, garantiert immer einen
  `NOOP`-Fallback, und verlangt einen Port pro konfiguriertem Provider — die Registry selbst
  ist bereits generisch genug für einen weiteren Provider, ohne Änderung an der Registry selbst.
- Bereits getroffene Design-Entscheidung aus
  [`docs/planning/done-archive/ImpPlan-0.9.6-G-Bestandsaufnahme.md`](../done-archive/ImpPlan-0.9.6-G-Bestandsaufnahme.md)
  §3.4: ohne Config bleibt es fail-closed, `procedure_transform_*`/`testdata_*` mit
  nicht-konfiguriertem Provider soll `INTERNAL_AGENT_ERROR` liefern (Server-Config-Lücke, nicht
  Policy-Lücke). **Offen für P1 (siehe unten):** das weicht vom heutigen Verhalten ab —
  `DefaultAiProviderRegistry` liefert *immer* einen `NOOP`-Fallback statt fail-closed zu
  blockieren; die Umsetzung muss klären, ob das §3.4-Verhalten (fail-closed ohne Config) jetzt
  greift oder der stille `NOOP`-Fallback für unkonfigurierte Provider-IDs beibehalten wird.

## Scope-Skizze

- **P1 — Config-Parsing**: `ai.provider.*`-Block im `--connection-config`-Dateiformat
  (`endpoint`, `defaultModel`, optional `requestTimeoutMs`, `maxOutputBytes`); Parser analog
  `McpServerStateConfigResolver`. Fehlerhafte/unvollständige Config → Server startet nicht
  (fail loud, wie beim `server.state`-Block). Klärt das §3.4-Fallback-Verhalten (s.o.).
- **P2 — `OllamaProvider`**: `AiProviderPort`-Implementierung gegen Ollamas `/api/generate`
  (synchroner HTTP-Client, kein Streaming-Consumer nötig — Antwort blockierend einsammeln bis
  Timeout oder `maxOutputBytes`). Vertrag: `Failure(TIMEOUT)` bei überschrittenem
  `requestTimeoutMs`, `Failure(OUTPUT_TOO_LARGE)` bei überschrittenem `maxOutputBytes`
  (Abbruch, keine stille Truncation).
- **P3 — Wiring**: `McpServeWiring.kt` (beide Call-Sites) reicht bei konfiguriertem
  `ai.provider`-Block eine `AiProviderRegistry` mit `OllamaProvider` statt
  `DefaultAiProviderRegistry.noOpOnly()` durch; ohne Config unverändert `noOpOnly()`.
- **P4 — Doku**: `docs/user/administrationshandbuch.md` — neuer Abschnitt "KI-Provider
  konfigurieren" (Config-Block, `--network host`-Pflicht für `LOCAL_LOOPBACK`, Verweis auf
  `EXTERNAL` als Alternative für Bridge-Networking-Deployments). `spec/mcp-server.md` bekommt
  den `ai.provider.*`-Config-Vertrag als normativen Abschnitt.

## Akzeptanzkriterien

- Mit konfiguriertem `ai.provider`-Block (Ollama-Endpoint erreichbar) liefert
  `testdata_execute` einen vom Modell tatsächlich generierten Output, kein
  `noop:...`-Platzhalter.
- Ohne Config bleibt das Verhalten unverändert (NOOP oder — je nach P1-Entscheidung —
  `INTERNAL_AGENT_ERROR`, dokumentiert in P1).
- Ollama nicht erreichbar (falscher Endpoint, Timeout) → `Failure(TIMEOUT)` mit
  `INTERNAL_AGENT_ERROR`, keine hängende Anfrage über `requestTimeoutMs` hinaus.
- Antwort größer als `maxOutputBytes` → `Failure(OUTPUT_TOO_LARGE)`, kein stillschweigend
  abgeschnittener Output.

## Roadmap-Einordnung

`docs/planning/in-progress/roadmap.md`, Meilenstein **1.5.5 „KI-Integration"**: `AiProvider`-
Interface + Plugin-System, `OllamaProvider (lokale Modelle)`, `OpenAiProvider`,
`AnthropicProvider`, `XaiProvider`/`GoogleProvider`, `RuleBasedProvider` — alle unter
[`LF-017`](../../../spec/lastenheft-d-migrate.md#lf-017). Aktueller Software-Stand liegt bei
grob Milestone ~1.2.0 (`server.state`-JDBC-Persistenz, siehe
[`persistence-jdbc-mig.md`](persistence-jdbc-mig.md)-Nachbarschaft); 1.5.5 liegt damit mehrere
Meilensteine voraus. Dieser Plan liefert nur den `OllamaProvider`-Teil von 1.5.5, nicht das
vollständige Plugin-System oder die übrigen Provider.

## Vorbedingungen

- Kein Versions-/Termin-Commitment: der Eigner hat entschieden, den Bau nicht vorzuziehen
  (Design jetzt klären, Umsetzung später). Aktiviert wird dieser Plan, sobald entweder
  Meilenstein 1.5.5 regulär ansteht oder ein neuer konkreter Bedarf (Sandbox/Pilot) ihn
  vorzieht.
- `LmStudioProvider` bleibt bewusst außerhalb dieses Plans; ein Folge-Plan für LM Studio kann
  denselben Config-/Container-Vertrag wiederverwenden, sobald es einen konkreten Trigger dafür
  gibt.

## Herkunft

Aufgefallen während der Einrichtung von `server.state` (JDBC-Persistenz für Schema-/
Artefakt-Store, Commit `bacdd9ba`) und eines End-to-End-Approval-Grant-Tests für
`testdata_execute` in der `d-migrate-mcp-sandbox` (2026-09-05). Design-Entscheidungen
(Config-Pfad, Containerisierung, Adapter-Scope) am selben Tag mit dem Eigner geklärt.
