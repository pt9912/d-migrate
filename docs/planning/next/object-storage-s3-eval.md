# Entscheidungs-Addendum: S3-Client-Library fuer den Object-Storage-Adapter

> Dokumenttyp: Entscheidungsvorlage (S3-Adapter-Evaluierung) zu
> [`object-storage-artifact-store.md`](object-storage-artifact-store.md) §8
>
> Status: **Empfehlung (planungsgestuetzt, 2026-06-09)** — Lib-Pick mit
> Begruendung und Restrisiken. Dies ist der 0.9.8-Eval-Deliverable des
> Object-Storage-Tracks. Die eigentliche Implementierung folgt als
> eigener `ImpPlan` in Phase 3/4 (siehe Plan §8/§9); ein Dependency-Lock
> erfolgt erst nach der empirischen Validierung in §8.
>
> **Update (2026-06-09):** MinIO als Fallback verworfen — die
> Community-Edition ist seit 2026-02-12 archiviert/EOL (§3.2). Fallback
> bleibt innerhalb AWS SDK v2 (Transport-Wechsel, §6); primaeres
> Test-Ziel ist SeaweedFS (§7).

---

## 1. Ziel und Abgrenzung

`object-storage-artifact-store.md` §3 hat gezeigt: die Byte-/Metadaten-/
Multipart-Store-Infrastruktur ist mit 0.9.6 bereits ausgeliefert
(`ArtifactContentStore`, `UploadSegmentStore`, `ArtifactStore`,
File-Referenz-Impls). Das einzig genuin offene Stueck ist ein
**S3-kompatibler Adapter**, der die bestehenden Ports erfuellt.

Diese Vorlage entscheidet **eine** Frage: **welche JVM-Client-Library**
fuer S3-kompatiblen Object Storage. Sie entscheidet **nicht** den
Slice-Schnitt oder Test-Vertrag — das ist Sache des spaeteren ImpPlans.

Es geht **nicht** um den Bau eines eigenen S3-Servers, generische
Data-Lake-Verwaltung oder einen `CheckpointStore`-Ersatz (Plan §5.2).

---

## 2. Anforderungs-Fit gegen die bestehenden Ports

Der Adapter muss exakt diese Vertraege erfuellen:

- `ArtifactContentStore.write(id, InputStream, expectedSizeBytes)` →
  `WriteArtifactOutcome` (mit SHA-256 + Size + `AlreadyExists`/`Conflict`-
  Idempotenz). **S3-Mapping:** `PutObject` mit Streaming-Body; SHA-256
  serverseitig nicht garantiert → muss client-seitig mitgerechnet werden
  (StreamingHashWriter-Pattern wie File-Impl).
- `ArtifactContentStore.openRangeRead(id, offset, length)` →
  **S3-Mapping:** `GetObject` mit `Range`-Header.
- `UploadSegmentStore` (Segmente) → **S3-Mapping:** S3-Multipart-Upload
  (`CreateMultipartUpload`/`UploadPart`/`CompleteMultipartUpload`).
  Nebenbedingung: S3-Mindest-Partgroesse **5 MiB** (ausser letztem Part) —
  die Segmentierung muss das respektieren.
- `delete`/`exists` → `DeleteObject`/`HeadObject`.

AWS SDK v2 bildet alle vier Operationen — inklusive der **expliziten,
einzeln geschriebenen Multipart-Segmente** des `UploadSegmentStore` —
first-class ab. MinIO deckt `write`/`openRangeRead`/`exists`/`delete` ab,
**nicht** aber den expliziten Segment-Vertrag (siehe §3.2). Die Auswahl
entscheidet sich also an **Funktions-Fit (Multipart-Primitive), Footprint,
Native-Image-Tauglichkeit, Credential-Modell und Retry-Determinismus**.

---

## 3. Kandidaten

### 3.1 AWS SDK for Java v2 — `software.amazon.awssdk:s3`

- **S3-Kompatibilitaet:** ueber `endpointOverride(URI)` + `pathStyleAccess`
  laufen MinIO, SeaweedFS (BI-Demo), Ceph/RGW und andere S3-kompatible
  Ziele am selben Client.
- **HTTP-Transport pluggable:** `url-connection-client` (JDK-`HttpURLConnection`,
  **kein Netty/Apache**, schlankster Footprint) | `apache-client` | `netty-nio-client`
  | **AWS CRT** (`aws-crt-client`, nativer Common-Runtime-Layer).
- **Native-Image:** out-of-box-Support **seit 2.16.1** (SDK liefert
  GraalVM-Reachability-Metadaten); CRT-Client-Native-Image **seit 2.28.7**
  mit reduzierter Archivgroesse/Startzeit.
- **Multipart:** die Low-Level-Primitive (`createMultipartUpload`/
  `uploadPart`/`completeMultipartUpload`) sind beim **sync `S3Client`**
  public und first-class — genau das, was der `UploadSegmentStore`-Vertrag
  (explizite, einzeln geschriebene Segmente) braucht. Hinweis: der
  High-Level-`S3TransferManager` und das automatische `.multipartEnabled`
  sind **CRT-/async-only** und auf dem hier gewaehlten sync-Transport (§6)
  **nicht** nutzbar — Multipart wird manuell getrieben, was zum
  `UploadSegmentStore` ohnehin passt.
- **Lizenz:** Apache 2.0.
- **Kosten:** groesserer transitiver Modul-Footprint (auth, regions,
  http-client-spi, profiles, …) — relevant fuer den 1.0.0-Native-Image-/
  Distributions-Cut (dieselbe Spannung wie beim Parquet-/Hadoop-Footprint).
  Mit `url-connection-client` und Exclude der Default-HTTP-Clients deutlich
  reduzierbar.

### 3.2 MinIO Java Client — `io.minio:minio`

- **Stand / Governance (entscheidend):** Apache 2.0, letzter Client-Release
  v9.0.1 (2025-09-27). **Aber:** das MinIO-Oekosystem laeuft aus — die
  Server-Community-Edition wurde Mai 2025 entkernt (Admin-UI/OIDC raus),
  ging Dez 2025 in „Maintenance Mode" und ist seit **2026-02-12 archiviert /
  „no longer maintained"** (Pivot auf das kommerzielle AIStor; CE-Preis
  ab 96 k USD/Jahr). Der Java-Client laeuft formal noch, teilt aber das
  Governance-Risiko des Parent-Projekts (keine aktive Issue-/PR-Review,
  Security-Fixes nur case-by-case). Community-Alternativen-Konsens:
  SeaweedFS/Ceph/Garage. **Im Projekt bereits vollzogen:** die BI-Demo hat
  MinIO durch SeaweedFS ersetzt ([`bi-demo-compose.md`](../done/bi-demo-compose.md)
  §5.3 + Risk #9 RESOLVED).
- **S3-Kompatibilitaet:** auf S3-kompatible Stores ausgelegt; broad
  kompatibel, aber Sicht eines Vendors auf den S3-Vertrag.
- **HTTP-Transport:** fest auf **OkHttp** (>= 4.8.1) → zieht OkHttp +
  Kotlin-stdlib + okio als Transitiven; dazu simplexml/commons-compress.
  Historische OkHttp-Versionsfriktion dokumentiert
  (minio-java #1298 „Unsupported OkHttp", #1681 „HttpUrl not found").
- **Native-Image:** **nicht** first-class dokumentiert; OkHttp + Kotlin-
  Reflection erfordern erfahrungsgemaess manuelle Reachability-Config.
- **API:** schlank und direkt (`putObject`/`getObject`). **Multipart-
  Luecke (entscheidend):** der MinIO-Java-Client exponiert **keine**
  Low-Level-Multipart-Primitive als public API — `createMultipartUpload`/
  `uploadPart` liegen intern in `S3Base`; oeffentlich ist nur das
  **auto-multipartende `putObject`**. `composeObject` ist server-side
  Compose (vorhandene Objekte zusammenfuegen, eigenes 5-MiB-Quell-Minimum),
  **kein** client-getriebener Multipart-Upload. Damit erfuellt MinIO den
  `UploadSegmentStore`-Vertrag (explizite `writeSegment`/`listSegments`)
  **nicht** ohne Reflection/Fork.

### 3.3 Warum nur diese zwei Kandidaten

CRT-Standalone (`aws-crt`) und Apache jclouds wurden verworfen, ohne in die
Matrix zu gehen: CRT ist async-only (passt nicht zum gewaehlten sync-Pfad,
§6) und bringt eine native Bibliothek mit (Native-Image-/Packaging-
Komplexitaet); jclouds ist breit, aber schwergewichtig und in der Wartung
deutlich traeger. Beide loesen kein Problem besser als die zwei bewerteten
Optionen.

---

## 4. Bewertungsmatrix

Gewichtung nach Projektkontext: Native-Image-Cut ist fuer 1.0.0 real
(S10b-Finding, Distributions-Cut), darum hoch gewichtet.

| Kriterium (Gewicht) | AWS SDK v2 | MinIO-Client |
| -------------------- | ---------- | ------------ |
| **Port-Fit — `UploadSegmentStore` (hoch)** | ✓ public Low-Level-Multipart-Primitive | ✗ keine public Primitive, nur auto-`putObject` (§3.2) |
| Port-Fit — `write`/`openRangeRead`/`exists`/`delete` | ✓ voll | ✓ voll |
| S3-Kompatibilitaet (MinIO/SeaweedFS/Ceph) | ✓ via endpoint-override | ✓ nativ |
| **Native-Image-Tauglichkeit (hoch)** | ✓ dokumentiert/first-class | ◐ undokumentiert, manueller Aufwand |
| **Footprint (hoch)** | ◐ gross, aber via url-connection-client + Excludes schlankbar | ◐ kleiner Kern, aber OkHttp+Kotlin-Transitiven |
| **Credential-Provider-Modell** | ✓ `DefaultCredentialsProviderChain` (env/profile/IAM/STS) | ◐ manuelles Credentialing |
| **Fehler-/Retry-Determinismus** | ✓ konfigurierbare `RetryStrategy` + typisierte Fehlerklassen | ◐ einfacher, weniger granular |
| HTTP-Transport-Kontrolle | ✓ pluggable (Netty vermeidbar) | ✗ an OkHttp gebunden |
| **Wartung/Governance (hoch)** | ✓ AWS-Referenz, breit getestet, langfristig gepflegt | ✗ Parent-Projekt 2026-02 archiviert/EOL, Oekosystem im Auslauf (§3.2) |
| Spaetere Multi-Cloud (GCS/Azure) — *nicht entscheidungsrelevant, nur Nicht-Sperr-Hinweis (§1 Scope)* | ◐ separate SDKs, gleicher Port | ✗ S3-only |
| Lizenz | ✓ Apache 2.0 | ✓ Apache 2.0 |

---

## 5. Risiken

- **Footprint vs. Native-Image (AWS SDK v2):** der Default-Pull ist gross.
  Mitigation: nur `s3` + `url-connection-client`, Default-HTTP-Clients
  (Netty/Apache) per `exclude` entfernen; gegen den S10b-Native-Image-
  Findings-Pfad recheck-en (siehe §8).
- **Native-Image-Unbekannte (MinIO):** OkHttp/Kotlin-Reflection-Config ist
  Erfahrungswert, kein dokumentierter Pfad — Risiko, dass der 1.0.0-
  Native-Image-Cut daran haengt.
- **SHA-256-Vertrag:** S3 liefert kein SHA-256 als First-Class-ETag
  (ETag = MD5 bzw. Multipart-Composite). Der Adapter **muss** den
  SHA-256 selbst streamend mitrechnen, um `WriteArtifactOutcome.Stored.sha256`
  zu erfuellen — gilt fuer **beide** Libs gleichermassen.
- **Multipart-5-MiB-Minimum:** die `UploadSegment`-Groessen muessen >= 5 MiB
  sein (ausser letztem Part), sonst lehnt S3 `CompleteMultipartUpload` ab —
  ein bestehender File-Multipart-Test deckt das nicht ab.
- **`url-connection-client`-Funktionsgrenzen:** kein HTTP/2, kein async,
  historisch Tendenz zur Request-Body-Pufferung. Kritisch fuer den Store:
  streamt `PutObject` den Body bei gesetztem `Content-Length`
  (= bekanntes `expectedSizeBytes`) ohne Vollpufferung? Erwartung ja, aber
  als expliziter Check ins Gate (§8.2), nicht als Annahme.

---

## 6. Empfehlung (Verdict)

**Empfehlung: AWS SDK for Java v2 (`software.amazon.awssdk:s3`) mit dem
`url-connection-client`-Transport.**

Begruendung:

1. **Native-Image** ist der ausschlaggebende, projektspezifisch hoch
   gewichtete Faktor (1.0.0-Cut). AWS SDK v2 hat einen dokumentierten,
   first-class-Pfad; MinIO nicht.
2. **HTTP-Transport-Kontrolle:** `url-connection-client` vermeidet Netty
   und haelt den Footprint klein — die einzige reale AWS-SDK-Schwaeche
   (Groesse) ist damit direkt adressierbar, ohne an OkHttp gebunden zu sein.
3. **Referenz-Semantik:** Retry, Signing, Multipart und Fehlerklassen
   entsprechen dem S3-Vertrag 1:1 — weniger Risiko bei den
   `WriteArtifactOutcome`-Fehlerfaellen (§5).
4. **Zukunft:** derselbe Port traegt spaeter GCS/Azure ueber deren SDKs;
   die Entscheidung sperrt nichts.

Hinweis zur Matrix (§4): der hoch gewichtete **Footprint** ist **kein**
Differenzierer (beide ◐ — AWS gross-aber-schlankbar, MinIO klein-aber-
OkHttp+Kotlin). Die Entscheidung haengt damit allein an **Multipart-Fit
(`UploadSegmentStore`), Native-Image und Governance** — alle drei klar
pro AWS SDK v2.

**Fallback bleibt innerhalb AWS SDK v2** — kein Vendor-Wechsel: scheitert
`url-connection-client` am Body-Streaming-/Footprint-Gate (§8), ist der
Ausweich-Transport `apache-client` (sync, robustes Request-Streaming) oder
der CRT-Client. Wichtig: ein CRT-Fallback bedeutet **nicht** auto-Multipart
— der `S3AsyncClient` (inkl. CRT) exponiert die Low-Level-Primitive
`createMultipartUpload`/`uploadPart`/`completeMultipartUpload` als
`CompletableFuture`, sodass der `UploadSegmentStore`-Vertrag (explizite
Segmente) auch async gewahrt bleibt. Der `S3TransferManager`/`.multipartEnabled`
(auto-Multipart) ist **nicht** Teil des Fallbacks — genau diese Auto-Variante
war das K.-o.-Kriterium gegen MinIO (§3.2).

**MinIO-Client ist kein tragfaehiger Fallback mehr:** das Parent-Projekt ist
seit 2026-02-12 archiviert/„no longer maintained" (§3.2; vgl.
[`bi-demo-compose.md`](../done/bi-demo-compose.md) §5.3 + Risk #9, wo
SeaweedFS MinIO im Demo-Stack bereits abgeloest hat), dazu der Native-Image-
und der `UploadSegmentStore`-Multipart-Nachteil. Er bleibt nur ein Notnagel,
falls AWS SDK v2 gaenzlich ausfaellt — dann mit der dokumentierten
Port-Konzession (auto-`putObject` statt expliziter Segmente).

**Korroboration:** die BI-Demo standardisiert ihren S3-Client bereits auf den
**AWS-Pfad** (`amazon/aws-cli` gegen SeaweedFS via `--endpoint-url`), nachdem
sie `minio/mc` wegen des CE-Source-Only-Risikos verworfen hat
([`bi-demo-compose.md`](../done/bi-demo-compose.md) §5.3) — dieselbe
Client-Linie, die dieses Addendum fuer den Adapter empfiehlt.

---

## 7. Dependency-/Modul-Skizze (nicht-bindend, fuer den ImpPlan)

Projektkonvention: kein Version-Catalog — Versionen in `gradle.properties`,
Deps direkt im `build.gradle.kts` des neuen Moduls.

- Neues Modul `adapters:driven:storage-s3` (analog `adapters:driven:storage-file`).
- `software.amazon.awssdk:s3` + `software.amazon.awssdk:url-connection-client`,
  Default-Transports (`netty-nio-client`/`apache-client`) per `exclude` raus.
- Test: **SeaweedFS** als primaeres IT-Ziel (demo-aligned —
  [`bi-demo-compose.md`](../done/bi-demo-compose.md) §5.3:
  `chrislusf/seaweedfs:4.31`, `server -s3 -s3.config=…`, S3-API auf Port
  8333, PutObject verlangt eine `s3.config`-Identity). Mangels offiziellem
  Testcontainers-Modul via `GenericContainer` + eigener Wait-Strategy
  (`aws s3api list-buckets`-Probe analog `seaweed-init`). **MinIO-Server
  als Baseline entfaellt** (CE archiviert, §3.2); eine zweite, AWS-nahe
  Probe — falls gewuenscht — eher via LocalStack/echtem S3 als via
  archiviertem MinIO.

---

## 8. Offene empirische Validierung (Gate vor Dependency-Lock)

Dieses Addendum ist planungsgestuetzt — **kein** Code-Spike. Bevor der
ImpPlan die Dependency festnagelt, ist zu pruefen:

1. **Footprint-Messung:** Fat-JAR-Delta mit `s3` + `url-connection-client`
   (Default-Transports excluded) gegen die heutige CLI — gegen das
   S10b-Native-Image-/Distributions-Budget.
2. **Native-Image-Smoke + Body-Streaming:** minimaler `PutObject`/
   `GetObject`-Range gegen das SeaweedFS-Testziel (§7) unter Native-Image
   (Reachability-Metadaten ok?); dabei verifizieren, dass
   `url-connection-client` den `PutObject`-Body bei gesetztem
   `Content-Length` **streamt** statt vollzupuffern (§5).
3. **Multipart-5-MiB-Reproducer gegen SeaweedFS:** Segment < 5 MiB →
   erwarteter `CompleteMultipartUpload`-Reject; Vertrag der
   `UploadSegmentStore`-Segmentierung dagegen festziehen. SeaweedFS hat
   eine engere S3-Compat-Flaeche als AWS — Multipart-ETag/Range explizit
   gegen das reale Demo-Ziel verifizieren (kein TC-Modul, §7).
4. **Idempotenz/Retry + Credentials:** das SDK-Retry-/Fehlerklassen-
   Verhalten auf den `WriteArtifactOutcome`-Vertrag abbilden —
   deterministisches `AlreadyExists` bei Re-`write` desselben `artifactId`
   mit gleichem SHA, `Conflict` bei abweichendem SHA; und die
   Credential-Aufloesung (`DefaultCredentialsProviderChain`) gegen das
   `credentials.provider`-Schema (Plan §6) festziehen.

Erst nach 1–4 wird die Empfehlung in §6 zum bindenden Lock.

---

## 9. Quellen

- [GraalVM Native Image Support in the AWS SDK for Java 2.x — AWS Developer Tools Blog](https://aws.amazon.com/blogs/developer/graalvm-native-image-support-in-the-aws-sdk-for-java-2-x/)
- [Set up a GraalVM Native Image project that uses the AWS SDK for Java 2.x — AWS Docs](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-graalvm.html)
- [AWS CRT Client for Java adds GraalVM Native Image support — AWS Developer Tools Blog](https://aws.amazon.com/blogs/developer/aws-crt-client-for-java-adds-graalvm-native-image-support/)
- [minio/minio-java — GitHub](https://github.com/minio/minio-java)
- [minio/minio-java Releases](https://github.com/minio/minio-java/releases)
- [minio-java #1298 — Unsupported OkHttp library](https://github.com/minio/minio-java/issues/1298)
- [MinIO Maintenance Mode — minio/minio #21714](https://github.com/minio/minio/issues/21714)
- [MinIO removes management features from Community Edition — Blocks & Files](https://blocksandfiles.com/2025/06/19/minio-removes-management-features-from-basic-community-edition-object-storage-code/)
- [MinIO in Maintenance Mode: Open Source Alternatives — InfoQ](https://www.infoq.com/news/2025/12/minio-s3-api-alternatives/)
- Intern: [`bi-demo-compose.md`](../done/bi-demo-compose.md) §5.3 + Risk #9 — SeaweedFS-statt-MinIO-Entscheid + AWS-CLI-Client-Wahl
