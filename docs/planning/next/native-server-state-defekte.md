# Native: der JDBC-Server-State ist im Native-Binary nicht benutzbar

> **Status:** Entwurf mit Scope (2026-09-16). Reproduziert gegen das **ausgelieferte**
> `ghcr.io/pt9912/d-migrate:1.7.1-native`.
> **Vorbedingung / Gate:** keins. Der Slice baut auf
> [`native-e2e-regression-gate.md`](native-e2e-regression-gate.md) auf — dessen Hebel
> (`DMIGRATE_CLI_BIN`) ist gebaut und bewiesen, offen ist dort die CI-Verdrahtung.
> **Aktivierung:** Move nach `../in-progress/` beim ersten Implementierungs-Commit.

## Befund (gemessen 2026-09-16, gegen das ausgelieferte 1.7.1-native)

Ein Konsumentenprojekt meldet: jeder MCP-Tool-Call, der Job-/Schema-/Artefakt-Records
aus dem **JDBC-Backend** liest, endet mit `INTERNAL_AGENT_ERROR`. Betroffen sind
`schema_list`, `schema_generate` mit `schemaRef` und `schema_reverse_start`;
unbetroffen `capabilities_list` und `schema_validate` mit Inline-Schema.

**Reproduziert im Repo** (JVM gegen Native, dieselbe Konfiguration, derselbe Store):

```
State:      dmigrate_native_repro (Postgres), gefuellt via schema_reverse_start  → jobs=1, schemas=1
JVM   1.7.1:  schema_list → {"schemas":[{"schemaId":"sch-71f9149161e148c5", …}]}   ✅
Native 1.7.1-native: schema_list → {"code":"INTERNAL_AGENT_ERROR", …}              ❌
```

**Gefuellt hat den Store die JVM**, nicht das Native-Binary: der Schreibpfad
braucht dieselben Codecs, an denen das Native-Binary scheitert — ein nativer
Fuellversuch waere schon dort gescheitert. Der A/B ist damit Lesen gegen Lesen.

**Der leere Store ist die Falle.** Ein erster Versuch gegen einen frisch migrierten,
leeren State lief auf **beiden** Artefakten durch — `SchemaIndexEntryJson.fromJson`
wird nur gerufen, wenn eine Zeile existiert. Deshalb braucht das Repro einen
*bestehenden* State; ein Test gegen einen leeren Store belegt hier nichts.

**Und `schema_generate` mit `schemaRef` ist im Repo kein sauberer A/B.** Der
Aufruf liefert:

```
JVM    "schema parse failed: artifact art-71f9149161e148c5 not found"
Native INTERNAL_AGENT_ERROR
```

Die JVM scheitert dort **nicht** am Lesen des Index-Eintrags, sondern am
**fehlenden Artefakt**: der Eintrag ueberlebt in der JDBC-DB, die Bytes liegen
aber unter `--mcp-state-dir` — und dessen Default ist ein **CLI-eigenes
temporaeres** Verzeichnis („deleted on stop", im stderr jedes Laufs benannt).
Zwei Prozesse, ein persistenter Metadaten-Store und ein fluessiger Inhalts-Store
ergeben einen **haengenden Verweis**.

Der `schema_list`-A/B oben ist davon unberuehrt und bleibt der Beleg; der
`schema_generate`-Leg zeigt nur, dass native **frueher** stirbt (an den
Metadaten), waehrend die JVM weiterkommt und dann am Artefakt scheitert.

### Ursache 1 — die Reflexionsmetadaten fehlen (der eigentliche Defekt)

Die fünf JSON-Codecs des JDBC-Adapters serialisieren ihre Wire-Records mit
`jackson-module-kotlin`, das die `private data class` per **kotlin-reflect**
introspiziert. Im Native-Image braucht das Registrierung. Gemessen:

| Datei | Einträge | aus `server.persistence.jdbc` |
| ----- | -------- | ----------------------------- |
| `…/cli-manual/reflect-config.json` | 88 | **0** |
| `…/cli/reachability-metadata.json` | (auto) | **0** |

Die Metadaten erhebt der Tracing-Agent, gefahren von `scripts/native-probe.sh`.
Dessen `mcp serve`-Leg macht **nur einen `initialize`-Handshake** — kein
`tools/call`, kein `server.state`. Die Codecs werden also nie getraced.

Die Sonde kennt diese Fehlerklasse und benennt sie selbst: das Audit-Log dient als
Deckungsnachweis, und ein früherer Stand führte vor, wie „beide Male sah der Lauf
richtig aus" (`export flyway` berührte die Flyway-Library nie).

### Ursache 2 — Flyway kann im Native-Image keine `resource:`-Location scannen

Im Native-stderr, zweimal je Start:

```
[WARN]  o.f.c.i.s.classpath.ClassPathScanner - Unable to scan location: /db/migration
        (unsupported protocol: resource)
[ERROR] o.f.core.internal.command.DbMigrate - Schema "public" has version 2,
        but no migration could be resolved in the configured locations !
```

Auf einem **frischen** Native-Deployment heisst das: `server.state.migrations.auto`
findet die Migrationen nicht und legt die Store-Tabellen nicht an. Im gemessenen
Lauf blieb es folgenlos, weil der State schon migriert war — die Fehlerzeile steht
trotzdem bei jedem Start.

**Und es hat zwei unabhaengige Ursachen, nicht eine.** Die Migrationen sind
naemlich **gar nicht im Image**: die beiden Dateien liegen in
`persistence-jdbc/src/main/resources/db/migration/`, aber die einzige
Ressourcen-Registrierung fuer native-image ist
`-H:IncludeResourceBundles=messages.messages` (`cli/build.gradle.kts:104`) — es
gibt **kein** `resource-config.json`, und die `resources`-Liste der Metadaten
kennt `db/migration` nicht (0 Treffer, gemessen). Ein reparierter Scanner
faende also **nichts**. Erst beide Fixes zusammen tragen P4s DoD.

### Ursache 3 — die Ursache **wird geloggt**, ist aber nicht erreichbar

> **Korrigiert nach Review.** Der erste Entwurf behauptete, es gebe „kein
> Gegenstueck: keine Zeile, kein Ausnahmetyp". Das ist **falsch** — die Zeile
> existiert und ist sogar begruendet.

`McpServiceImpl.kt:383-390` loggt die Ursache **mit vollem Throwable**, und der
Kommentar darueber nennt wortgleich den Zweck („sonst ist ein serverseitiger
Defekt fuer den Betreiber unauffindbar"), samt der Begruendung fuer die Stufe:
*„DEBUG-Level, damit der WARN-Default-Betrieb ruhig bleibt; zur Diagnose
`-Dlogback...=DEBUG`."* Dazu existiert eine Audit-Spur
(`errorCode=INTERNAL_AGENT_ERROR`, opt-in ueber `logging.audit.enabled`).

**Der Defekt ist die Erreichbarkeit, nicht die Zeile:**

| | |
| --- | --- |
| `logback.xml:9` | `root level="WARN"` |
| Produktiver Code, der eine Stufe setzt | **keiner** (`setLevel`/`LoggerContext` kommen nur in einem Test-Helfer vor) |
| `--verbose` | behauptet im Hilfetext „DEBUG level", setzt aber nur ein `CliContext`-Feld — **keine** Logback-Stufe |

Wer das Binary betreibt, muesste von der `-Dlogback...=DEBUG`-Empfehlung im
Quellkommentar wissen. Das ist genau die Klasse, die der Repo-Praezedenzfall
`cli-manual/README.md` beschreibt („was nicht laeuft, kann der Agent nicht
aufzeichnen") — nur auf der Betreiber-Seite.

### Nebenbefund (kein Native-Defekt) — persistenter Metadaten-State, fluessiger Inhalts-State

Beim Nachmessen von `schema_generate` aufgefallen und **nicht** native-spezifisch:
`server.state` legt Jobs, Schemas und Artefakt-**Records** dauerhaft in der
JDBC-DB ab, die Artefakt-**Bytes** aber unter `--mcp-state-dir`, dessen Default
ein temporaeres Verzeichnis ist. Ein `schemaRef` aus einem frueheren Prozess
zeigt damit ins Leere (`artifact not found`) — ein Deployment mit persistentem
State ist so nicht betreibbar, unabhaengig vom Native-Binary.

**Ausdruecklich kein Native-Defekt:** beide Artefakte scheitern hier gleich —
die JVM kommt nur weiter und laeuft dann in denselben fehlenden Verweis. Der
Befund gehoert deshalb neben diesen Slice, nicht in seine Ursachenliste.

Ob das ein Default-Fehler ist (dann muesste `server.state` einen persistenten
Byte-Store erzwingen oder verlangen) oder eine Betreiber-Aufgabe (dann gehoert
es in die Administrationsdoku), ist eine **eigene Entscheidung** — sie wird hier
nicht vorweggenommen, der Befund aber festgehalten.

**Und die Administrationsdoku ist an dieser Stelle ungenau** (beim Nachsehen
dieses Befunds aufgefallen, nicht Teil des Slice): sie nennt die History-Tabelle
`flyway_phase_e_history`, waehrend der Code `flyway_server_state_history` nutzt
(`JdbcMigrationRunner.kt:65`), und sie verweist auf ein Flag `--server-state`,
das es nicht gibt — real ist `server.state.jdbcUrl` (`McpServerStateConfig.kt:42`).
Wer hier dokumentiert, korrigiert das mit.

### Einordnung

Dritter Fall desselben Musters wie die zwei in
[`native-e2e-regression-gate.md`](native-e2e-regression-gate.md) dokumentierten:
ein Defekt, den **weder der Sondenlauf noch der Build** sieht, weil beide nur
Konstruktion und Handshake prüfen. Beide Altfälle gaben Exit 0 bzw. einen grünen
Build zurück.

## Ziel

Der JDBC-Server-State ist im Native-Binary benutzbar, die Sonden erheben die dafür
nötigen Metadaten, und ein Gate fällt, wenn eine künftige Erweiterung des Stores
denselben Weg geht.

## Abgrenzung

- **Nicht** die Metadaten von Hand nachtragen. Die Datei ist mit 88 Einträgen
  bereits handgepflegt, und genau das ist der Grund, warum sie den JDBC-Adapter
  nicht kennt: eine zweite Handliste driftet wie die erste.
- **Nicht** `@RegisterForReflection` als Sofortmaßnahme. Es wäre pro Wire-Record
  zu setzen und damit wieder eine Liste, die man vergessen kann.
- **Nicht** der Auslieferungsweg des 1.7.1-native (Rücknahme, Kennzeichnung) — das
  ist eine Release-Entscheidung, nicht Teil dieses Slices.

## Arbeitspakete

### P1 — Die Sonde fährt den JDBC-Store

`scripts/native-probe.sh` bekommt einen Leg, der `mcp serve` mit
`server.state` startet und **schreibende wie lesende** Store-Operationen
ausfuehrt: `schema_reverse_start`, `job_status_get`, `schema_list`. Erst dann
tracen Agent und Audit-Log die Codecs.

**Der Store muss zur BAUZEIT erreichbar werden — nicht beim Sondenlauf.**
Der erste Entwurf zeigte auf `make native-probe`; das ist der falsche Lauf. Die
Metadaten erhebt der Agent **waehrend des Builds**:
`docker/native-image.Dockerfile:132` ruft `native-probe.sh` in der
`native-agent`-Stage als `RUN` auf. Ein Build-`RUN` kann kein Compose-Netz und
keinen Nachbar-`docker run` benutzen; `make native-probe` (`native.mk:116`)
liefert dagegen **gar keine** Metadaten. Gebraucht wird also
Build-Zeit-Konnektivitaet — etwa `RUN --network=host` gegen einen Postgres auf
dem Host.

**Ein Policy-File ist Pflicht, sonst bleiben zwei Codecs unerreichbar.**
`ApprovalChallengeJson.toJson` laeuft nur im
`PolicyDecision.RequiresApproval`-Zweig (`JdbcIdempotencyStore.kt:187`), und
`QuotaJson` nur im erlaubten Pfad. Ohne `--policy-file` ist die Regelliste leer
und der Default `Deny("policy:no-rule")` — jeder Start endet als `PolicyDenied`,
und beide Codecs werden **nie** getraced. Der Harness weiss das:
`examples/mcp-e2e/policy-rules.yaml` ist genau dafuer da, samt
`challenge`-Regel.

**SQLite ist der naheliegende billige Weg und gemessen untauglich — und zwar
breit, nicht an einer Stelle.** Im Adapter ist die **ganze Flaeche**
PG-spezifisch: `?::jsonb`, `::text`/`::timestamptz`, `?::text IS NULL`, `>>`,
mehrfaches `RETURNING` (u. a. `JdbcArtifactStore.kt:41`,
`JdbcSchemaStore.kt:43`, `JdbcJobStore.kt:60`, `JdbcIdempotencyStore.kt:194`),
und die DDL selbst nutzt JSONB/TIMESTAMPTZ samt Hinweis „do NOT port verbatim
to other dialects" (`V1__server_state_initial.sql`). Wer SQLite als Store
will, baut **kein** Ein-Statement-Paket, sondern einen Dialekt-Port.

**DoD:** Der Agent-Lauf erzeugt Eintraege fuer die Wire-Klassen der fuenf
Codecs — im `git diff` der Metadaten sichtbar. Zu zielen ist auf die
**verschachtelten Klassen**, nicht auf die Codec-Objekte:
`JobRecordJson$JobRecordWire`, `JobRecordJson$ServerResourceUriWire`,
`SchemaIndexEntryJson$...`, `ArtifactRecordJson$ArtifactRecordWire`,
`QuotaJson$QuotaKeyWire`/`$QuotaReservationWire`,
`ApprovalChallengeJson$...` — plus die eingebetteten Domain-Records.

### P2 — Metadaten neu erheben und einchecken

`make native-agent`, Diff prüfen (die `persistence.jdbc`-Einträge müssen
auftauchen), einchecken.

**DoD:** Der native `schema_list` gegen einen **gefüllten** Store antwortet wie die
JVM.

### P3 — Das Gate deckt die Store-Fläche

Der Hebel aus [`native-e2e-regression-gate.md`](native-e2e-regression-gate.md)
fährt bisher `McpRealCliSubprocessTest` und `McpS3SubprocessE2ETest` — beide ohne
JDBC-Store. Die Suite bekommt einen Fall, der gegen einen **bestandsgefüllten**
JDBC-State liest, plus die CI-Verdrahtung.

**DoD:** Das Gate fällt mit zurückgenommener P1/P2-Metadatenänderung. Und es fällt
nicht auf einem **leeren** Store — der Fall muss einen Datensatz anlegen und ihn in
einem **zweiten** Prozess lesen.

### P4 — Die drei Nebenursachen

- **Flyway (zwei Ursachen):** *erst* die Migrationen ins Image holen
  (`IncludeResources`/`resource-config.json`), *dann* den Scanner — ein Fix allein
  bringt nichts (s. Ursache 2, zweiter Absatz).
- **Unereichbare Ursache:** die **vorhandene** Zeile erreichbar machen — die
  Verbosity-Verdrahtung herstellen (`--verbose` soll halten, was sein Hilfetext
  verspricht) oder die Stufe dokumentieren. **Keine zweite Log-Zeile bauen:**
  sie waere genauso stumm. Die Sanitisierung fuer den **Client** bleibt
  unangetastet.
- **Persistenter State, fluessiger Inhalt** (Nebenbefund): entweder den
  Byte-Store an den Metadaten-State koppeln oder die Betreiber-Auflage
  dokumentieren.

**DoD:** Ein frisches Native-Deployment mit `migrations.auto` legt die Store-Tabellen
an; ein `INTERNAL_AGENT_ERROR` ist im Log mit einer Ausnahme belegt.

## Akzeptanzkriterien

1. `schema_list` gegen einen gefüllten JDBC-State liefert mit dem Native-Binary
   dasselbe wie mit der JVM.
2. Die Metadaten tragen alle fünf Wire-Codecs — im eingecheckten Diff sichtbar.
3. Das Gate fällt, wenn die Registrierung fehlt, und es fällt **nicht** bei leerem
   Store (die Falle ist gepinnt).
4. Ein frisches Native-Deployment migriert seinen State selbst.
5. Der Nebenbefund ist **entschieden**: entweder laeuft ein persistent
   konfigurierter Server ueber Prozessgrenzen hinweg (Byte-Store gekoppelt),
   oder die Auflage steht in der Administrationsdoku.

## Verifikation

1. **Der A/B-Lauf aus dem Befund** ist die Abnahme: JVM und Native gegen denselben
   gefüllten State, gleiches Ergebnis. Er ist im Slice dokumentiert und
   reproduzierbar (`/tmp/native-repro`-Aufbau: `server.state` + `schema_reverse_start`
   zum Füllen).
2. **Sabotage:** Metadaten-Einträge entfernen → Gate rot; zurücknehmen → grün.
3. `make native-agent`, Diff lesen.
4. Der lokale Bau ist jetzt gedrosselt (`NATIVE_MAX_RAM_PERCENTAGE=50`,
   `NATIVE_PARALLELISM=2`, s. `make/native.mk`) — gemessen 6m57s bei 7,37 GB Peak.

## Offen (nicht Teil dieses Slices)

- **Der Auslieferungsweg.** Die nativen Binaries von `v1.7.1` sind betroffen; ob das
  ein Patch-Release (1.7.2) wird oder eine Kennzeichnung wie bei den
  teilpublizierten v1.0.1/v1.0.2, entscheidet der Eigner.
- **Warum es nur den JDBC-Adapter trifft.** Nachgesehen: `persistence-memory`
  enthält **keine** Serialisierung (kein `ObjectMapper`, `toJson` oder Gson in
  irgendeiner der `InMemory*`-Klassen) — die Stores halten Objekte. Deshalb ist
  ausschließlich der **persistente** Betrieb betroffen, konsistent damit, dass
  die In-Memory-Nutzung des Konsumenten unauffällig war.
