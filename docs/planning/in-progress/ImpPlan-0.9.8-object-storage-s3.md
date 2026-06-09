# ImpPlan 0.9.8 — Object-Storage S3-Adapter (`adapters:driven:storage-s3`)

> Dokumenttyp: Implementierungsplan (Bau-Slice)
>
> Status: **In Progress (`in-progress/` seit 2026-06-09).** S3.0–S3.3
> abgeschlossen (Gate GO; `S3StorageSupport` + `S3ArtifactContentStore` +
> `S3UploadSegmentStore`, reviewt/konvergiert). Aktiv: S3.4 (Wiring + E2E).
>
> Referenzen:
> [`object-storage-artifact-store.md`](object-storage-artifact-store.md)
> (Architektur/Reconciliation),
> [`object-storage-s3-eval.md`](object-storage-s3-eval.md) (Lib-Verdict +
> Validierungs-Gate), [`bi-demo-compose.md`](../done/bi-demo-compose.md)
> (SeaweedFS-Setup).

---

## 1. Scope

**In Scope:** S3-kompatible Implementierungen der **Byte-Layer**-Ports
`ArtifactContentStore` + `UploadSegmentStore` in einem neuen Modul
`adapters:driven:storage-s3`; config-getriebene Selektion im CLI-MCP-Wiring;
Testcontainers-IT gegen SeaweedFS.

**Nicht in Scope:**

- Metadaten-`ArtifactStore` — bleibt wie heute (im CLI `InMemoryArtifactStore`,
  `McpCliRuntimeWiring.kt:89`); S3 betrifft **nur** die zwei Byte-Stores.
- `CheckpointStore`-Migration auf den ArtifactStore (Plan §5.2, eigener Slice).
- Eigener S3-Server, GCS/Azure, Multi-Identity-IAM, Lifecycle-Policies.
- `d-migrate data export s3://…`-Direktziele (separater CLI-Feature-Thread,
  **nicht** der Byte-Store-Adapter).

---

## 2. Architektur

### 2.1 Port-Mapping (Korrektur ggü. Eval §2)

| Port-Operation | S3-Mapping |
| -------------- | ---------- |
| `ArtifactContentStore.write(id, in, sizeBytes)` | `PutObject` (Streaming-Body, `Content-Length = expectedSizeBytes`); SHA-256 **client-seitig** mitrechnen und als **User-Metadata `x-amz-meta-sha256` (+ `x-amz-meta-size-bytes`)** mitschreiben (S3-ETag ≠ SHA-256, daher nicht aus dem ETag ableitbar). Artefakte > 5 GiB (S3-Single-PUT-Limit) → intern S3-**Multipart** (`createMultipartUpload`/`uploadPart`/`completeMultipartUpload`). |
| `openRangeRead(id, offset, length)` | `GetObject` + `Range`-Header |
| `exists` / `delete` | `HeadObject` / `DeleteObject` |
| `WriteArtifactOutcome.AlreadyExists`/`Conflict` | `HeadObject` liest die **`x-amz-meta-sha256`-User-Metadata** (+ Size) — **nicht** den ETag (MD5/Composite); SHA-Vergleich vor `PutObject` → `AlreadyExists` (gleich) / `Conflict` (abweichend). Analog zum File-Sidecar (`FileBackedArtifactContentStore.resolveExisting`). |
| **`UploadSegmentStore`** — `writeSegment` / `listSegments` / `openSegmentRangeRead` / `deleteAllForSession` | **Jedes Segment = ein eigenstaendiges S3-Objekt** unter `upload-sessions/{sessionId}/{segmentIndex}`: `PutObject` je Segment, `GetObject`+`Range` für den Segment-Range-Read, `ListObjectsV2(prefix)`, `DeleteObjects(prefix)`. **Keine** S3-Multipart-Parts. |

> **Eval-Korrektur (vor/mit dem Bau einzucheckenden Commit):**
> [`object-storage-s3-eval.md`](object-storage-s3-eval.md) §2 mappt
> `UploadSegmentStore` faelschlich auf S3-Multipart und disqualifiziert
> MinIO ueber fehlende public Multipart-Primitive (§3.2 + §4-Matrix-Zeile
> „Port-Fit `UploadSegmentStore`"). Das ist falsch: `openSegmentRangeRead`
> verlangt das Range-Lesen eines **bereits geschriebenen** Segments, und
> S3-Multipart-Parts sind vor `CompleteMultipartUpload` **nicht** einzeln
> GET-bar. Der Vertrag braucht einzeln les-/schreibbare Objekte, **kein**
> Multipart. Damit erfuellt auch MinIOs `putObject`/`getObject(range)` den
> Vertrag — die MinIO-Disqualifikation steht **allein** auf
> **Governance/EOL** (korrekt) + **Native-Image** (korrekt), nicht auf dem
> Multipart-Argument. Das **Verdict (AWS SDK v2) bleibt** unveraendert.
> Zu korrigieren: Eval §2 (Mapping), §3.2 (MinIO-Multipart-Absatz),
> §4-Matrix (Port-Fit-Zeile), §6 (Begruendung 3 nicht auf Multipart-Fit
> stuetzen). Native S3-Multipart bleibt nur fuer `ArtifactContentStore.write`
> grosser Artefakte relevant.

### 2.2 Modul + Wiring

- Neues Modul `adapters:driven:storage-s3` analog `storage-file`:
  `api(project(":hexagon:ports-common"))` + AWS-SDK-Deps; `kover` minBound 90.
  `settings.gradle.kts`: `include("adapters:driven:storage-s3")`.
  Version `awsSdkVersion` in `gradle.properties` (kein Version-Catalog).
  **Achtung (S3.0-Befund 2026-06-09):** der `deps`-Stage im `Dockerfile`
  kopiert Build-Files per **verbosem Per-Modul-COPY-Block** — fuer ein neues
  Modul **muss** dort eine `COPY …/storage-s3/build.gradle.kts …`-Zeile
  ergaenzt werden, sonst werden die Deps im `deps`-Stage **still nicht**
  aufgeloest (Footprint misst dann faelschlich 0). Erledigt.
- **Wiring-Punkt:** `McpCliRuntimeWiring.runtimeWiring` (`adapters/driving/cli/.../McpCliRuntimeWiring.kt:88-90`)
  konstruiert heute hart `FileBackedUploadSegmentStore(stateDir)` +
  `FileBackedArtifactContentStore(stateDir)`. Hier kommt der config-getriebene
  Branch `artifacts.store: file | s3` → File-backed (Bestand) vs.
  `S3ArtifactContentStore`/`S3UploadSegmentStore`. Config aus Eval §6
  (`.d-migrate.yaml` `artifacts.s3.{endpoint,bucket,prefix,region,credentials}`).
- **Retention/Cleanup:** `McpServeRunner.kt:235/244` ruft die
  `FileBacked*.cleanupOrphans`-Companions. S3 braucht ein Aequivalent
  (Prefix-Sweep) **oder** stuetzt sich auf den metadaten-getriebenen
  `ArtifactStore.deleteExpiredRecords`-Sweeper (bevorzugt — siehe S3.4).

### 2.3 SHA-256

`StreamingHashWriter` ist `internal` zu `storage-file` → **nicht**
moduluebergreifend nutzbar. Das S3-Modul rechnet SHA-256 selbst beim Upload
(`DigestInputStream`/Tee in den `PutObject`-Body) und nutzt das geteilte
`sha256Hex` aus `dev.dmigrate.core.util` (`HexEncoding.kt:28`, public — wie
der File-Adapter) statt `MessageDigest`/`toHex` neu zu verdrahten. Der
errechnete SHA wird als Objekt-User-Metadata persistiert (§2.1), damit der
`HeadObject`-Idempotenz-Pfad ihn zurueckliest — der ETag taugt dafuer nicht.

---

### 2.4 S3-Client-Konfiguration (S3.0-gate-validiert gegen SeaweedFS)

Der produktive `S3Client` (Wiring S3.1/S3.4, genutzt von S3.2/S3.3) **muss**
so gebaut werden — empirisch gegen SeaweedFS verifiziert:

- `endpointOverride(<artifacts.s3.endpoint>)` + `S3Configuration.pathStyleAccessEnabled(true)`.
- `httpClient(UrlConnectionHttpClient.create())` (Eval-Verdict; sync, kein Netty/Apache).
- **`requestChecksumCalculation(WHEN_REQUIRED)` + `responseChecksumValidation(WHEN_REQUIRED)`**
  — **Pflicht**: ohne das brechen **alle** Body-Operationen gegen SeaweedFS mit
  `Content-Md5 not valid` (400), weil AWS SDK v2 >= 2.30 per Default
  Integritaets-Checksums (aws-chunked) rechnet, die SeaweedFS nicht akzeptiert
  (S3.0-Befund 2026-06-09).
- Credentials aus `artifacts.s3.credentials` (`StaticCredentialsProvider`) bzw.
  `DefaultCredentialsProviderChain`; Scrubbing wie DB-Verbindungen (0.9.1).

---

## 3. Slice-Schnitt (gate-first)

| Slice | Inhalt |
| ----- | ------ |
| **S3.0 — §8-Validierungs-Gate (Spike)** | **Vor** Dependency-Lock: (1) Footprint — Fat-JAR-Delta mit `s3` + `url-connection-client` (Default-Transports `exclude`d) gegen das S10b-/Distributions-Budget; (2) Native-Image-Smoke + **Body-Streaming**-Check (streamt `PutObject` bei gesetztem `Content-Length` statt vollzupuffern?); (3) Multipart-/Range-Compat gegen SeaweedFS-Container + ob SeaweedFS die **User-Metadata `x-amz-meta-*` bei `HeadObject`** korrekt zurueckgibt (Voraussetzung fuer den SHA-Idempotenz-Pfad, §2.1); (4) Idempotenz/Retry + `DefaultCredentialsProviderChain` gegen das `credentials.provider`-Schema. **Verdikt entscheidet:** AWS-SDK + `url-connection-client` locken — oder Ausweich-Transport (`apache-client`/CRT) bzw. Fallback (Eval §6). **Teil-Ergebnis 2026-06-09:** (1) Footprint ✅ — AWS `s3` + `url-connection-client` loesen sauber auf (29 Jars / ~8,3 MB, **kein** Netty/Apache, keine Konflikte); (2) Native-Image **deferred → 1.0.0-Cut** (kein GraalVM); (3) SeaweedFS-Compat ✅ — PutObject+`x-amz-meta-sha256`→HeadObject (Metadata kommt zurueck), Range-GET, Multipart (5-MiB-Part) gruen (`:test:integration-storage-s3` SeaweedFsS3SpikeTest); (4) Idempotenz-Basis ✅. **Befund:** AWS SDK v2 (>= 2.30) Default-Checksums brechen gegen SeaweedFS (`Content-Md5 not valid`, 400) → Client mit `requestChecksumCalculation`/`responseChecksumValidation = WHEN_REQUIRED` (siehe §2.4). **Gate-Verdikt: GO** — AWS SDK v2 + `url-connection-client` gelockt. |
| **S3.1 — Modul + Dependency + Config** | ✅ (2026-06-09) `storage-s3`-Modul + AWS-SDK-Dependency; `S3StorageConfig` (Credential-redigierter `toString`) + `S3ClientFactory` (gate-validierte Client-Config §2.4). `artifacts`-YAML-**Parser** folgt mit dem CLI-Wiring (S3.4). |
| **S3.2 — `S3ArtifactContentStore`** | ✅ (2026-06-09) `write`/`openRangeRead`/`exists`/`delete`; SHA als `x-amz-meta-sha256` (+ `size-bytes`); `WriteArtifactOutcome` `Stored`/`SizeMismatch`/`AlreadyExists`/`Conflict` (HeadObject-Metadata + SHA-Vergleich); Range-Bounds-Validierung; Multipart-Pfad > 8 MiB (Abort-on-Failure); Per-Key-Lock. **Coverage:** mockk-Unit-Tests in-Modul (alle Branches, `koverVerify` 90% gruen) **+** Vertragssuite/Multipart vs SeaweedFS — **kein** Ledger-Exclude (der Coverage-`docker build`-Stage kann keine Testcontainers fahren, daher Unit-Tests in-Modul Pflicht). |
| **S3.3 — `S3UploadSegmentStore`** | ✅ (2026-06-09) Segmente als Einzelobjekte (`segments/<session>/<index>`) mit `x-amz-meta-sha256`/`size-bytes`/`segment-offset`; `WriteSegmentOutcome` `Stored`/`AlreadyStored`/`Conflict`/`SizeMismatch`; `listSegments` rekonstruiert via HeadObject (sortiert nach Index); `listSegments`/`deleteAllForSession` **paginiert** (`isTruncated`/`continuationToken`, Batch-Delete 1000). Shared-Util-Extraktion erledigt: `S3StorageSupport` (Hash, ID/Range-Validierung, HeadObject-404, Single/Multipart-Put) — beide S3-Stores nutzen ihn (modul-lokal; core-Promotion vs. storage-file bleibt eigener Refactor). mockk-Unit-Tests + `UploadSegmentStoreContractTests` vs SeaweedFS gruen; koverVerify 90%. |
| **S3.4 — Wiring + Retention** | `McpCliRuntimeWiring`-config-Branch (`artifacts.store: file\|s3`) + `artifacts`-YAML-Parser; S3-Orphan-Cleanup bzw. metadaten-getriebener Sweeper. |
| **S3.5 — Testcontainers-IT (SeaweedFS)** | `GenericContainer("chrislusf/seaweedfs:4.31")`, `server -s3 -s3.config=…`, Port 8333, `s3.config`-Identity (bi-demo §5.3); die wiederverwendbaren **testFixtures-Vertragssuiten `ArtifactContentStoreContractTests` + `UploadSegmentStoreContractTests`** (aus `testImplementation(testFixtures(project(":hexagon:ports-common")))`) **subclassen** — exakt wie `FileBackedArtifactContentStoreTest` —, **nicht** eine eigene Suite nachbauen; + S3-spezifisch (Range, grosse Objekte/Multipart, Idempotenz, Pagination). `ArtifactContentStore`-Subclass ✅ (2026-06-09, S3.2 vorgezogen); `UploadSegmentStore`-Subclass folgt mit S3.3. **Plus E2E (nach S3.4):** voller CLI/MCP-Pfad (`mcp serve` mit `artifacts.store: s3` → `artifact_upload`/`data_import` → S3-Store → SeaweedFS), z. B. in `test/e2e-cli` oder einem eigenen Flow — vorher gibt es nichts End-to-End zu treiben (Store ist erst ab S3.4 verdrahtet). |
| **S3.6 — Footprint-/Native-Image-Recheck + Closure** | Re-Messung gegen S3.0-Baseline; Doku/CHANGELOG; ImpPlan → `done/`. |

---

## 4. Definition of Done

1. Beide Byte-Stores erfuellen die Port-Vertraege gegen SeaweedFS-IT gruen
   (MinIO/echtes S3 optional als Zweitprobe).
2. Die testFixtures-Vertragssuiten `ArtifactContentStoreContractTests` +
   `UploadSegmentStoreContractTests` sind gegen die S3-Impl subclassed und
   gruen (inkl. der `Stored`/`AlreadyExists`/`Conflict`-Idempotenzfaelle).
3. `artifacts.store: s3` waehlt im MCP-Wiring die S3-Stores; Credentials
   erscheinen **nicht** in Logs/Reports.
4. §8-Gate-Ergebnisse (S3.0) dokumentiert; Dependency final gelockt.
5. `kover` ≥ 90 % im neuen Modul; `make docker-check` (Repo) gruen.
6. **Eval-Addendum-Korrektur** (§2/§3.2/§4/§6, siehe §2.1) ist committet.

---

## 5. Bewusst NICHT in 0.9.8

- Metadaten-`ArtifactStore`-Persistenz (bleibt wie heute).
- `CheckpointStore`-auf-ArtifactStore.
- GCS/Azure, Multi-Identity-IAM, Lifecycle-Policies, Server-seitige
  Verschluesselung.
- `data export s3://…`-CLI-Direktziele.

---

## 6. Risiken / offene Punkte

- **Footprint/Native-Image** (Gate S3.0): kann einen Transport- oder
  Lib-Wechsel erzwingen — deshalb gate-first, kein vorzeitiger Lock.
- **SeaweedFS-Compat-Flaeche** enger als AWS (Range/ETag/Multipart) —
  gegen das reale Demo-Ziel verifizieren, kein offizielles TC-Modul.
- **`url-connection-client`-Body-Pufferung** — expliziter Gate-Check (S3.0/2).
- **5-GiB-Single-PUT-Grenze** → Multipart-Pfad in S3.2 fuer grosse Artefakte
  noetig (das ist die *einzige* Stelle, an der native S3-Multipart zaehlt).
- **Kein atomares create-if-absent (Semantik-Abweichung):** der File-Adapter
  garantiert „exactly-one-`Stored`" unter Concurrency via Per-Key-Lock +
  `ATOMIC_MOVE`/create-new (`FileBackedArtifactContentStore`). S3/SeaweedFS
  sind default **last-writer-wins**; atomares create-if-absent gibt es bei S3
  erst neuerdings via `If-None-Match: *`, SeaweedFS-Support ist unklar. Die
  Contract-Suite testet hier nur **sequenziell** → kein Contract-Blocker, aber
  eine reale Semantik-Abweichung; im Gate (S3.0) gegen SeaweedFS pruefen.
- **SeaweedFS-User-Metadata-Abhaengigkeit:** der ganze Idempotenz-Pfad
  (`AlreadyExists`/`Conflict`) haengt daran, dass `HeadObject` die
  `x-amz-meta-sha256`-Metadata zurueckgibt — Gate-Punkt in S3.0.

---

## 7. Folgeaufgaben

- **Eval-Korrektur-Commit** (§2.1) — moeglichst vor S3.0, damit Plan und
  Eval konsistent sind.
- ✅ Promotion `next/` → `in-progress/` (2026-06-09, mit den drei
  Track-Docs; externe Links nachgezogen).
- Erwaegung: das Footprint-Gate (S3.0) mit dem **1.0.0-Native-Image-Cut**
  buendeln, statt den Footprint zweimal zu messen (Roadmap-0.9.8-Hinweis).
- **Review-Runde-1-Dispositionen (2026-06-09, konvergiert nach Runde 2):**
  - gefixt: Striped-Locks (Memory-Leak), Multipart-Abort via finally
    (Orphan-Uploads), `requireSafeId`-Allowlist (Parity zu File).
  - **mit S3.3:** gemeinsame Byte-Store-Utils extrahieren (Streaming-Hash
    `copyAndHash` + `requireSafeId`/`PathSafety`), wenn der `UploadSegmentStore`
    der dritte Nutzer wird (ImpPlan §2.3-Schwelle).
  - **eigener Refactor:** Byte-Store-Basis fuer die write-Outcome-Entscheidung
    (Stored/AlreadyExists/Conflict), die File + S3 heute duplizieren.
  - akzeptiert: Multipart-Part als 8-MiB-`ByteArray` (vertretbar), `delete`-
    2-RTT + cross-JVM-last-writer-wins (dokumentiert §6).
