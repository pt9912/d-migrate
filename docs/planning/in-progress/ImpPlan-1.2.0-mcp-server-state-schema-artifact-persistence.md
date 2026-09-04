# ImpPlan 1.2.0 — MCP Server-State: Schema-/Artefakt-Persistenz + Approval-Drift-Warnung

> **Status:** Draft, bereit zur Umsetzung (2026-09-04). Ausgelöst durch
> einen extern zugeleiteten, unabhängig gegen den Code verifizierten
> Bugreport zu `mcp serve --server-state`. **Vorbedingung:** Keine harte
> Blockade.
> **Review-Nachzug (2026-09-04):** unabhängiger Codebase-Review vor
> Implementierungsstart bestätigte alle Datei:Zeile-Zitate im Kontext-
> Abschnitt, fand aber zwei blockierende Design-/Infrastruktur-Lücken
> (AE-2s `register()`-Muster löst die Race nicht, die es lösen soll;
> die Testinfrastruktur zielte auf das falsche Modul und baute
> bestehende Contract-Test-Suiten neu statt sie wiederzuverwenden) sowie
> eine dritte, sonst zuverlässig rot laufende Lücke (Kover-90%-Excludes
> für die neuen Postgres-only-Klassen fehlten). Alle drei in AE-1, AE-2
> und „Neue/geänderte Dateien" unten aufgelöst.

## Kontext / Ist-Stand (verifiziert)

Drei ursprünglich behauptete Befunde, alle drei unabhängig gegen die
aktuelle `main` geprüft, nicht nur übernommen:

1. **`schemaStore`/`artifactStore` bleiben mit `--server-state`
   trotzdem In-Memory — bestätigt.**
   `DefaultServerStateFactory.build()` in
   `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeWiring.kt`
   (`McpServeWiring.kt:91-94`) ersetzt in `phaseC.copy(...)`
   ausschließlich `jobStore` und `quotaService` durch JDBC-
   Implementierungen. `McpCliRuntimeWiring.runtimeWiring()` in
   `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpCliRuntimeWiring.kt`
   (`McpCliRuntimeWiring.kt:107,109`) setzt `artifactStore =
   InMemoryArtifactStore()` und `schemaStore = InMemorySchemaStore()`
   fest, unabhängig davon, ob `--server-state` gesetzt ist.
   `JdbcSchemaStore`/`JdbcArtifactStore` existieren im Repo nicht.
   **Keine normative Deckung dieser Lücke**: kein ADR erwähnt
   `server-state`/Phase E, `spec/mcp-server.md` erwähnt `server.state`
   überhaupt nicht, und die im Code zitierten
   [LN-011](../../../spec/lastenheft-d-migrate.md#ln-011)/
   [LN-017](../../../spec/lastenheft-d-migrate.md#ln-017)/
   [LN-027](../../../spec/lastenheft-d-migrate.md#ln-027)
   (Konsistenz/Progress/Audit) scopen das nicht auf „Job/Quota
   persistent, Katalog ephemer". Der begleitende Code-Kommentar
   (`McpCliRuntimeWiring.kt:53-58`) beschreibt den Ist-Zustand korrekt,
   ist aber keine Entscheidung — ein Kommentar ist nicht normativ.
   Praktische Folge: reverse-engineerte Schemas und über
   `testdata_plan`/`procedure_transform_plan` erzeugte Artefakte
   überleben keinen Server-Neustart, auch nicht im „produktiven"
   JDBC-Modus.
2. **Zwei unabhängige Persistenz-Schalter, die auseinanderfallen
   können — bestätigt.** `JobStartOrchestrator.markAwaitingAndChallenge()`
   in `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt`
   (`JobStartOrchestrator.kt:370`) legt die wartende Approval-Challenge
   über `idempotencyStore.markAwaitingApproval(...)` ab. Ohne
   `--server-state` ist der `idempotencyStore`
   `InMemoryIdempotencyStore()` (`McpServeWiring.kt:265`,
   `buildInMemory()`), **auch wenn** `--approval-grants-file` gesetzt
   ist und den `approvalGrantStore` (`McpServeWiring.kt:292-293`)
   durabel macht. Ein Operator, der nur `--approval-grants-file` setzt
   (naheliegend, wenn er noch keine Server-State-DB betreiben will),
   bekommt einen durablen Grant-Store, aber eine flüchtige Erinnerung
   daran, worauf sich ein Grant bezieht — nach einem Neustart validiert
   `AiToolApprovalSupport.validateGrant()`/`ApprovedRetryService` gegen
   nichts mehr.
3. **`testdata_execute` crasht mit unbehandelter Exception statt sauber
   abzulehnen — NICHT bestätigt** (Live-Verifikation gegen den echten
   MCP-Server, `d-migrate:dev`-Runtime-Image, zwei reale `testdata_execute`-
   Aufrufe): ein Aufruf ohne `targetTable` liefert sauber
   `VALIDATION_ERROR`, ein Aufruf mit einer erfundenen `planArtifactId`
   liefert sauber `RESOURCE_NOT_FOUND` — beides **vor** jeder
   Policy-Entscheidung, kein Stacktrace, kein `-32603`, kein
   `INTERNAL_AGENT_ERROR` im Server-`stderr`. Der Erfolgspfad mit einem
   echten, über `testdata_plan` erzeugten Plan-Artefakt wurde aus
   Aufwandsgründen nicht getestet (bräuchte `schema_reverse_start` gegen
   eine echte DB + Job-Poll) — ein tieferliegender Bug dort ist damit
   nicht ausgeschlossen, aber die konkrete „unbehandelte
   IllegalStateException"-Behauptung ist auf den getesteten Pfaden
   widerlegt. **Separat bestätigt, aber kosmetisch statt Crash:**
   `AiMcpRegistries.kt`s Klassen-KDoc (Zeile ~18-19) behauptet noch,
   `testdata_execute` bliebe „bewusst auf `UnsupportedToolHandler`" —
   tatsächlich verdrahtet dieselbe Datei es auf den echten
   `TestdataExecuteHandler` (Zeile ~131). Live bestätigt: ein echter
   `capabilities_list`-Aufruf liefert für `testdata_execute` weiterhin
   `"title": "... not part of 0.9.6 scope"` und
   `"errorCodes": ["UNSUPPORTED_TOOL_OPERATION"]`, obwohl das Tool
   nachweislich `VALIDATION_ERROR`/`RESOURCE_NOT_FOUND` liefert — der
   MCP-Contract informiert einen Client falsch über das eigene Tool.
   Dieser Slice behebt **nur** die Doku-Drift (String + KDoc, siehe
   AP4); Befund 3 selbst ist kein Persistenz-Thema und bleibt nicht Teil
   der Architektur-Entscheidungen unten.

## Scope

`mcp serve --server-state` persistiert **vollständig**, was der Betreiber
darunter erwarten würde: Schema-/Artefakt-Katalog zusätzlich zu
Job/Quota/Idempotency. Additiv, kein bestehender Contract ändert sich —
`--server-state` aktiviert automatisch die neuen JDBC-Stores, kein neues
Flag nötig. Dazu eine kleine, unabhängige Startup-Warnung für Befund 2.

## Architektur-Entscheidungen

**AE-1 — `JdbcSchemaStore`/`JdbcArtifactStore` folgen exakt dem
`JdbcJobStore`-Muster.** Eine JSONB-Spalte trägt den vollständigen
Domänen-Record (Source of Truth, analog `JobRecordJson` —
`adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/job/JobRecordJson.kt`),
plus extrahierte Spalten für Filter/Sortierung. Neue, additive Flyway-
Migration `V2__schema_artifact_stores.sql` (V1 bleibt unverändert):

```sql
CREATE TABLE schema_index_entries (
    tenant_id    TEXT        NOT NULL,
    schema_id    TEXT        NOT NULL,
    entry        JSONB       NOT NULL,   -- SchemaIndexEntry serialized
    job_ref      TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, schema_id)
);
CREATE INDEX schema_index_expiry ON schema_index_entries (expires_at);
CREATE INDEX schema_index_job_ref ON schema_index_entries (tenant_id, job_ref)
    WHERE job_ref IS NOT NULL;

CREATE TABLE artifact_records (
    tenant_id          TEXT        NOT NULL,
    artifact_id        TEXT        NOT NULL,
    record             JSONB       NOT NULL,  -- ArtifactRecord serialized
    kind               TEXT        NOT NULL,
    owner_principal_id TEXT        NOT NULL,
    job_ref            TEXT,
    created_at         TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, artifact_id)
);
CREATE INDEX artifact_records_expiry ON artifact_records (expires_at);
CREATE INDEX artifact_records_owner ON artifact_records (tenant_id, owner_principal_id);
CREATE INDEX artifact_records_kind ON artifact_records (tenant_id, kind);
CREATE INDEX artifact_records_job_ref ON artifact_records (tenant_id, job_ref)
    WHERE job_ref IS NOT NULL;
```

**Review-Ergänzung — Kover-90%-Gate für `:adapters:driven:persistence-jdbc`
muss mitgezogen werden, sonst läuft `make docker-check MODULES=":adapters:driven:persistence-jdbc"`
zuverlässig rot.** Das Modul schließt alle bestehenden Postgres-only-
JDBC-Klassen (`JdbcJobStore*`, `JdbcIdempotencyStore*`,
`JdbcQuotaStore*`, ...) explizit aus der modul-lokalen 90%-Kover-Pflicht
aus (`adapters/driven/persistence-jdbc/build.gradle.kts`,
`build.gradle.kts:39-72`, Begründung: gedeckt durch Contract-Tests
unter `-PintegrationTests` in
einem anderen Modul, nicht durch modul-lokale Unit-Tests). `JdbcSchemaStore*`
und `JdbcArtifactStore*` gehören aus demselben Grund in denselben
`excludes { classes(...) }`-Block — UND als neue Zeile in
`docs/coverage/excludes-ledger.md` (Disposition
`refactor-plan: docs/planning/next/adapter-coverage-uplift.md`, analog
den bestehenden Einträgen dort), sonst schlägt
`scripts/verify-kover-excludes-ledger.py` (Teil von `make docs-check`)
fehl. `SchemaIndexEntryJson.kt`/`ArtifactRecordJson.kt` bleiben bewusst
**außerhalb** dieses Excludes — sie sind reine JSON-Codecs ohne
SQL/Postgres-Bezug (anders als `QuotaJson`, das laut Kommentar an
Ort und Stelle ebenfalls exkludiert ist, weil es nur zusammen mit dem
SQL-Zugriffscode sinnvoll testbar ist) und bekommen stattdessen eigene,
modul-lokale Unit-Tests (siehe „Neue/geänderte Dateien").

**AE-2 — `SchemaStore.register()` mapped auf `INSERT ... ON CONFLICT
DO NOTHING RETURNING *`, NICHT auf `SELECT ... FOR UPDATE` +
bedingtes `INSERT` (Review-Korrektur, ursprünglich blockierender
Befund).** Die ursprüngliche Annahme — `SELECT ... FOR UPDATE` prüft
vor dem Schreiben, ob schon ein Eintrag existiert, genau wie
`InMemorySchemaStore.register()`s `ConcurrentHashMap.compute(key)` —
übersieht, dass `SELECT ... FOR UPDATE` **keine Zeile sperren kann, die
noch nicht existiert**: `compute()` sperrt den Bucket atomar über den
gesamten Lookup-Entscheiden-Schreiben-Zyklus, auch im
Neuregistrierungsfall. Zwei parallele `register()`-Aufrufe mit
identischem `schemaId` (der Regelfall laut Port-Doc — `register()`
existiert genau für den **Replay** eines erfolgreichen
Finalisierungs-Uploads, also für nahezu gleichzeitige Aufrufe mit
demselben deterministischen `schemaId`) sähen im `SELECT`-Zweig beide
"kein Eintrag", entschieden beide auf `Registered`, und der zweite
`INSERT` schlüge mit einer unbehandelten Unique-Violation auf
`PRIMARY KEY (tenant_id, schema_id)` fehl — ein Fehlerpfad, den die
In-Memory-Version per Konstruktion gar nicht kennt.

**Fix:** dasselbe Muster wie bereits `JdbcIdempotencyStore` im selben
Modul etabliert (`JdbcIdempotencyStore.kt:20-28`s eigener Doc-Kommentar:
"INSERT…ON CONFLICT DO NOTHING fuer den Hot-Path; SELECT…FOR UPDATE fuer
Recovery- und Claim-Pfade"). `register()` versucht zuerst
`INSERT ... ON CONFLICT (tenant_id, schema_id) DO NOTHING RETURNING *`:
kommt eine Zeile zurück → `Registered` (die INSERT hat gewonnen, kein
Wettlauf möglich, Postgres serialisiert das selbst). Kommt keine Zeile
zurück (Konflikt), erst dann `SELECT ... FOR UPDATE` auf die jetzt
sicher existierende Zeile zur Konfliktauflösung: `artifactRef` des
gefundenen Eintrags gleich dem übergebenen → `AlreadyRegistered`
(No-op, gibt den **gespeicherten** Eintrag zurück, NICHT den neu
übergebenen — Replay-Stabilität für `createdAt`/`labels`); `artifactRef`
weicht ab → `Conflict` (kein Schreiben). Präzisierung gegenüber der
ursprünglichen Formulierung: der existierende Eintrag wird über den
Primärschlüssel `(tenantId, schemaId)` gefunden, nicht über
`(tenantId, artifactRef)` gesucht — `artifactRef` ist nur das
Vergleichsfeld, das zwischen `AlreadyRegistered` und `Conflict`
entscheidet.

**AE-3 — `DefaultServerStateFactory.build()` erweitert `phaseC.copy(...)`
um `schemaStore`/`artifactStore`.** Kein neues CLI-Flag, keine neue
Config — die bestehende `--server-state`-DataSource/Migration-Kette wird
nur vollständiger genutzt. `stderr`-Startzeile
(`McpServeWiring.kt:218-222`, „MCP server-state: persistent backend
enabled …") bleibt unverändert (keine Vertragsänderung, nur intern mehr
Stores).

**AE-4 — `JdbcArtifactStore.deleteExpiredRecords()` überschreibt den
`ArtifactStore`-Default korrekt statt ihn zu erben.** Der Default
(`ArtifactStore.kt:59-62`) delegiert an `deleteExpired()` und liefert
`emptyList()` — für Stores, die „per-record cleanup data" nicht liefern
können. `JdbcArtifactStore` KANN das (JSONB trägt den vollen Record) und
MUSS es liefern: der Retention-Sweeper
(`ArtifactRetentionService`) braucht die gelöschten Records, um
Byte-Quotas freizugeben und die zugehörigen Content-Store-Payloads
(Datei/S3) zu löschen — sonst leaken abgelaufene, JDBC-getrackte
Artefakte ihre Bytes dauerhaft, sobald `--server-state` aktiv ist.

**AE-5 — Startup-Warnung bei `--approval-grants-file` ohne
`--server-state`, kein Hard-Fail.** Beide Flags sind für sich genommen
gültige, unabhängige Konfigurationen (z. B. ein Operator, der bewusst nur
Job-Läufe flüchtig halten will, aber Freigaben über einen Neustart
retten möchte — dieser Fall bleibt erlaubt, nur die Divergenz-Falle wird
sichtbar gemacht). Ort: `McpServeWiring.buildInMemory()` in
`adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeWiring.kt`
(`McpServeWiring.kt:257-264`, genau der Zweig, der ohne
`--server-state` erreicht wird und bereits Zugriff auf
`approvalGrantsFile`/`stderr` als Instanzfelder hat):

```
"Warning: --approval-grants-file is set without --server-state — " +
"grants are durable, but the pending approval requests they validate " +
"against are not. They do not survive a restart."
```

## Neue/geänderte Dateien

- `adapters/driven/persistence-jdbc/src/main/resources/db/migration/V2__schema_artifact_stores.sql` <!-- d-check:ignore (Zielbild: entsteht in diesem Slice; ADR 0011) -->
  (neu) — siehe AE-1.
- `adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/schema/JdbcSchemaStore.kt` <!-- d-check:ignore (Zielbild: entsteht in diesem Slice; ADR 0011) -->
  (neu) — `SchemaStore`-Implementierung, AE-2.
- `adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/schema/SchemaIndexEntryJson.kt` <!-- d-check:ignore (Zielbild: entsteht in diesem Slice; ADR 0011) -->
  (neu) — JSON-Codec, analog `JobRecordJson.kt`.
- `adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/artifact/JdbcArtifactStore.kt` <!-- d-check:ignore (Zielbild: entsteht in diesem Slice; ADR 0011) -->
  (neu) — `ArtifactStore`-Implementierung, AE-4.
- `adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/artifact/ArtifactRecordJson.kt` <!-- d-check:ignore (Zielbild: entsteht in diesem Slice; ADR 0011) -->
  (neu) — JSON-Codec, analog `JobRecordJson.kt`.
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeWiring.kt`
  — `DefaultServerStateFactory.build()` (AE-3), `buildInMemory()` (AE-5).
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/AiMcpRegistries.kt`
  — Klassen-KDoc korrigieren (Befund 3, Doku-Drift): `testdata_execute`
  ist real verdrahtet, nicht mehr auf `UnsupportedToolHandler`.
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/McpContractRegistries.kt`
  — `testdata_execute`-Beschreibung auf den tatsächlichen Funktionsstand
  aktualisieren (Befund 3, analog dem bereits vorhandenen
  `testdata_plan`-Text).
- `docs/user/administrationshandbuch.md` §10.3/§10.1 — Backup-/Recovery-
  Hinweis um Schema-/Artefakt-Tabellen ergänzen (bislang nur „Owner-
  Counts/Idempotency-Einträge" genannt); Hinweis zur Grants-vs-
  Idempotency-Divergenz (AE-5) in der Nähe von `--approval-grants-file`.
- `adapters/driven/persistence-jdbc/build.gradle.kts` — Kover-`excludes`
  um `JdbcSchemaStore*`/`JdbcArtifactStore*` erweitern (Review-Ergänzung
  zu AE-1).
- `docs/coverage/excludes-ledger.md` — zwei neue Zeilen für die
  vorstehenden Excludes (`refactor-plan:
  docs/planning/next/adapter-coverage-uplift.md`, analog den
  bestehenden `JdbcJobStore*`/`JdbcQuotaStore*`-Zeilen dort).
- Tests (**Review-Korrektur**, ursprünglich falsches Modul/falsches
  Namensmuster — die per-Store-Contract-Suiten `SchemaStoreContractTests`/
  `ArtifactStoreContractTests` existieren bereits als geteilte
  Kotest-Fixtures und werden bereits von `InMemorySchemaStore`/
  `InMemoryArtifactStore` konsumiert; `test/integration-server-state`
  enthält keine `Jdbc*StoreTest`-Dateien, sondern ausschließlich
  End-to-End-Pipeline-Tests):
  - `test/integration-persistence-jdbc/src/test/kotlin/dev/dmigrate/server/persistence/jdbc/schema/JdbcSchemaStoreContractTest.kt` <!-- d-check:ignore (Zielbild: entsteht in diesem Slice; ADR 0011) -->
    (neu) — `class JdbcSchemaStoreContractTest : SchemaStoreContractTests({ ... })`,
    analog `test/integration-persistence-jdbc/src/test/kotlin/dev/dmigrate/server/persistence/jdbc/job/JdbcJobStoreContractTest.kt`.
  - `test/integration-persistence-jdbc/src/test/kotlin/dev/dmigrate/server/persistence/jdbc/artifact/JdbcArtifactStoreContractTest.kt` <!-- d-check:ignore (Zielbild: entsteht in diesem Slice; ADR 0011) -->
    (neu) — `class JdbcArtifactStoreContractTest : ArtifactStoreContractTests({ ... })`.
  - `adapters/driven/persistence-jdbc/src/test/kotlin/dev/dmigrate/server/persistence/jdbc/schema/SchemaIndexEntryJsonTest.kt` <!-- d-check:ignore (Zielbild: entsteht in diesem Slice; ADR 0011) -->
    (neu) — modul-lokaler Rundreise-Test ohne Testcontainers, analog
    `adapters/driven/persistence-jdbc/src/test/kotlin/dev/dmigrate/server/persistence/jdbc/job/JobRecordJsonTest.kt`
    (deckt AE-1s Kover-Ausnahme für die Codec-Klassen, s. o.).
  - `adapters/driven/persistence-jdbc/src/test/kotlin/dev/dmigrate/server/persistence/jdbc/artifact/ArtifactRecordJsonTest.kt` <!-- d-check:ignore (Zielbild: entsteht in diesem Slice; ADR 0011) -->
    (neu) — wie vorstehend, für `ArtifactRecordJson`.
  - `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/McpServeWiringTest.kt` — zwei
    Ergänzungen: (a) `schemaStore`/`artifactStore` landen im
    `ServerStateBundle` (neuer `test`/`context`, lokale Fake-
    `ServerStateFactory` analog dem bestehenden Testmuster dort — es
    gibt **keine** separate `DefaultServerStateFactoryTest.kt`-Datei,
    die Behauptung im ursprünglichen Plan-Entwurf war falsch); (b) die
    Warnung aus AE-5 erscheint nur bei
    `approvalGrantsFile != null && state == null`.

## Phasen

- **AP1 — Flyway-Migration + JSON-Codecs.** `V2__schema_artifact_stores.sql`,
  `SchemaIndexEntryJson.kt`/`SchemaIndexEntryJsonTest.kt`,
  `ArtifactRecordJson.kt`/`ArtifactRecordJsonTest.kt`, Kover-Excludes +
  `excludes-ledger.md`-Zeilen (AE-1-Review-Ergänzung).
- **AP2 — `JdbcSchemaStore`.** Vollständige `SchemaStore`-Implementierung
  inkl. `register()` (AE-2), `JdbcSchemaStoreContractTest : SchemaStoreContractTests`
  gegen Testcontainers-Postgres.
- **AP3 — `JdbcArtifactStore`.** Vollständige `ArtifactStore`-
  Implementierung inkl. `deleteExpiredRecords()` (AE-4),
  `JdbcArtifactStoreContractTest : ArtifactStoreContractTests` gegen
  Testcontainers-Postgres.
- **AP4 — Wiring + Befund-3-Doku-Fix.** `DefaultServerStateFactory`/
  `buildInMemory()`-Änderungen (AE-3/AE-5); `AiMcpRegistries.kt`-KDoc +
  `McpContractRegistries.kt`-Beschreibung korrigieren.
- **AP5 — Doku.** `administrationshandbuch.md` §10.1/§10.3.

## Akzeptanzkriterien

- [ ] `mcp serve --server-state ...`: ein per `schema_reverse_start`
      erzeugtes Schema und ein per `testdata_plan` erzeugtes
      Plan-Artefakt sind nach einem Server-Neustart weiterhin über
      `resources/read`/`artifact_get` erreichbar (Live-Smoke gegen echtes
      Postgres).
- [ ] Ohne `--server-state` bleibt das Verhalten byte-identisch zu heute
      (In-Memory, reine additive Erweiterung).
- [ ] `SchemaStore.register()` liefert `Registered`/`AlreadyRegistered`/
      `Conflict` exakt wie `InMemorySchemaStore` bei denselben drei
      Szenarien — abgedeckt durch dieselbe geteilte
      `SchemaStoreContractTests`-Fixture-Matrix
      (`hexagon/ports-common/src/testFixtures/kotlin/dev/dmigrate/server/ports/contract/SchemaStoreContractTests.kt`),
      nicht neu geschrieben.
- [ ] Zwei parallele `register()`-Aufrufe mit identischem `schemaId`
      (Replay-Wettlauf, AE-2-Review-Korrektur) liefern deterministisch
      `Registered` + `AlreadyRegistered`, nie eine unbehandelte
      Unique-Violation — Regressionstest, der genau den in AE-2
      durchgerechneten Race-Fall abdeckt.
- [ ] Ein abgelaufenes, JDBC-getracktes Artefakt wird vom Retention-
      Sweeper tatsächlich aus dem Content-Store gelöscht (nicht nur aus
      der Metadaten-Tabelle) — Regressionstest für AE-4.
- [ ] `--approval-grants-file` ohne `--server-state` erzeugt die
      Warnzeile auf stderr; mit `--server-state` oder ohne
      `--approval-grants-file` erscheint sie nicht.
- [ ] `AiMcpRegistries.kt`-KDoc und `McpContractRegistries.kt`s
      `testdata_execute`-Beschreibung widersprechen der echten Verdrahtung
      nicht mehr.
- [ ] `make docker-check MODULES=":adapters:driven:persistence-jdbc"`
      und `MODULES=":adapters:driving:cli :adapters:driving:mcp"` grün
      (inkl. Kover-90%-Verify — AE-1-Review-Ergänzung).
- [ ] `make integration INTEGRATION_TASKS=":test:integration-persistence-jdbc:test"`
      grün (JDBC-Stores brauchen echtes Postgres via Testcontainers;
      Review-Korrektur — `test:integration-server-state` ist das falsche
      Modul, siehe „Neue/geänderte Dateien").
- [ ] `make docs-check` grün (inkl.
      `scripts/verify-kover-excludes-ledger.py`).

## Nicht-Scope

- Befund 3 selbst (ein möglicher tieferliegender Bug im
  `testdata_execute`-Erfolgspfad mit echtem Plan-Artefakt) — nicht
  bestätigt, kein Persistenz-Thema; bei Bedarf eigenes Ticket nach einem
  vollen Plan→Execute-Live-Roundtrip.
- Ein CLI-Flag, um Schema-/Artefakt-Persistenz unabhängig von Job/Quota
  ein-/auszuschalten — `--server-state` bleibt ein einziger Schalter für
  den gesamten Server-State.
- Migration bestehender In-Memory-Daten beim ersten Umstieg auf
  `--server-state` — es gibt nichts zu migrieren (In-Memory-Daten
  überleben den Prozess ohnehin nicht).
- SQLite-/MySQL-Varianten der neuen JDBC-Stores — Server-State-DB ist
  wie bisher Postgres-only (siehe `V1__server_state_initial.sql`-Kopf).

## Verifikation

1. `make docker-check MODULES=":adapters:driven:persistence-jdbc"` (AP1-AP3).
2. `make docker-check MODULES=":adapters:driving:cli :adapters:driving:mcp"` (AP4).
3. Einmal `make docker-check` ohne `MODULES` (geteilte `ServerStateBundle`-
   und `McpRuntimeWiring`-Signaturen).
4. `make integration INTEGRATION_TASKS=":test:integration-persistence-jdbc:test"`.
5. `make docs-check` nach den Doku-Änderungen.
6. `make solid-suppression-gate` vor jedem Commit.
7. Manueller Live-Smoke: `mcp serve --server-state ...` gegen echtes
   Postgres, `schema_reverse_start` + Poll, Server-Neustart, `resources/read`
   auf denselben `schemaRef` — muss weiterhin auflösen.

## Referenzen

- `adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/job/JdbcJobStore.kt`
  — Struktur-Vorbild (JSONB + extrahierte Spalten, Lock-dann-Schreiben).
- `adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/job/JobRecordJson.kt`
  — JSON-Codec-Vorbild.
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/server/ports/SchemaStore.kt`,
  `hexagon/ports-common/src/main/kotlin/dev/dmigrate/server/ports/ArtifactStore.kt`
  — zu implementierende Ports.
- `adapters/driven/persistence-jdbc/src/main/resources/db/migration/V1__server_state_initial.sql`
  — bestehende Migration, Vorbild für Tabellen-/Index-Stil.
- `docs/user/administrationshandbuch.md` §10.1/§10.3 — Upgrade-/Backup-
  Hinweise, die die neuen Tabellen aufnehmen müssen.
- `adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/idempotency/JdbcIdempotencyStore.kt`
  — `INSERT ... ON CONFLICT DO NOTHING`-Vorbild für AE-2 (Review-Fund).
- `hexagon/ports-common/src/testFixtures/kotlin/dev/dmigrate/server/ports/contract/SchemaStoreContractTests.kt`,
  `hexagon/ports-common/src/testFixtures/kotlin/dev/dmigrate/server/ports/contract/ArtifactStoreContractTests.kt`
  — geteilte Kotest-Contract-Suiten, wiederzuverwenden statt neu zu
  schreiben (Review-Fund).
- `test/integration-persistence-jdbc/src/test/kotlin/dev/dmigrate/server/persistence/jdbc/job/JdbcJobStoreContractTest.kt`
  — Vorbild dafür, wie eine geteilte Contract-Suite gegen einen
  Testcontainers-Postgres verdrahtet wird.
- `adapters/driven/persistence-jdbc/build.gradle.kts`,
  `docs/coverage/excludes-ledger.md` — Kover-Excludes-Konvention für
  Postgres-only-JDBC-Klassen (Review-Fund, AE-1-Ergänzung).
