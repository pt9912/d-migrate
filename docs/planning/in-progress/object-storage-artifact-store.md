# Plan: Object-Storage-ArtifactStore

> Dokumenttyp: Architektur- und Implementierungsplan
>
> Status: **Reconciled gegen 0.9.6-Code (2026-06-09)** — vormals
> „Entwurf (2026-05-01)". Die Byte-/Metadaten-Store-Infrastruktur ist
> mit dem MCP-Server (0.9.6) bereits ausgeliefert; offen bleibt allein
> die S3-Adapter-Evaluierung (§8) als 0.9.8-Eval-Deliverable. Diese
> liegt nun als [`object-storage-s3-eval.md`](object-storage-s3-eval.md)
> vor (Empfehlung: AWS SDK for Java v2 mit `url-connection-client`,
> planungsgestuetzt — Dependency-Lock nach empirischer Validierung). Die
> S3-Implementierung wurde 2026-06-09 in den 0.9.8-Scope vorgezogen
> (vormals Phase 4), hinter dem §8-Gate + eigenem ImpPlan.
>
> Referenzen: `docs/planning/in-progress/roadmap.md`,
> `spec/job-contract.md`, `spec/ki-mcp.md`, `spec/rest-service.md`,
> `spec/grpc-service.md`

---

## 1. Ziel

`d-migrate` erzeugt zunehmend langlebige oder grosse Artefakte:

- Exportdateien
- Import-/Export-Checkpoints
- Profiling-Reports
- Schema-Snapshots
- DDL-Bundles
- Job-Ergebnisse aus MCP, REST und gRPC

Ein Object-Storage-ArtifactStore soll planen, wie diese Artefakte nicht nur
lokal im Dateisystem, sondern auch in S3-kompatiblen Speichern abgelegt werden
koennen. Der erste konkrete Zieltyp ist S3-kompatibler Object Storage; Google
Cloud Storage und Azure Blob Storage koennen spaeter ueber denselben Port
folgen.

---

## 2. Motivation

Fuer kleine lokale CLI-Laeufe reicht das Dateisystem. Fuer lange Migrationen,
Serverbetrieb und CI/CD ergeben sich andere Anforderungen:

- Artefakte muessen laenger als der Prozess leben
- Jobs laufen potenziell auf mehreren Knoten
- grosse Exporte sollen nicht auf lokale Platten angewiesen sein
- Checkpoints sollen nach Neustart oder Deployment weiter nutzbar sein
- MCP/REST/gRPC brauchen opake, sichere Artifact-IDs statt direkter Pfade

Der ArtifactStore ist damit eine gemeinsame Infrastruktur fuer CLI, Server
und spaetere Integrationen.

---

## 3. Ist-Stand: was 0.9.6 bereits geliefert hat

> **Wichtig.** Dieser Plan wurde am 2026-05-01 als Entwurf geschrieben,
> *bevor* die MCP-Server-Artefakt-Infrastruktur (0.9.6, Phasen A–F)
> ausgeliefert war. Ein Re-Sight des Codes am 2026-06-09 zeigt: der
> grosse Teil des urspruenglichen „In Scope" ist bereits gebaut — nur
> unter anderen Namen als die fruehe §5-Skizze annahm. Diese Sektion
> erdet den Plan und loest die Namenskollision auf.

### 3.1 Namens-Reconciliation

Die fruehe Skizze nannte den Byte-Layer „ArtifactStore". Im
ausgelieferten Code ist das aufgeteilt:

| Frueher Plan-Begriff (§5-Skizze) | Realer Port im Code (0.9.6) | Modul |
| --------------------------------- | --------------------------- | ----- |
| `ArtifactStore` (Byte-Layer: `put`/`openRead`) | **`ArtifactContentStore`** (`write`/`openRangeRead`/`exists`/`delete`) | `hexagon:ports-common` (`server/ports`) |
| Metadaten-Operationen (`stat`/`list`) | **`ArtifactStore`** (Metadaten-Records: `save`/`findById`/`list`/`deleteExpired`) | `hexagon:ports-common` (`server/ports`) |
| Multipart (`beginMultipart`/`appendPart`/`complete`) | **`UploadSegmentStore`** (`writeSegment`/`listSegments`/`openSegmentRangeRead`/`deleteAllForSession`) | `hexagon:ports-common` (`server/ports`) |

Der Name `ArtifactStore` ist im Code also der **Metadaten**-Store; der
Byte-Store heisst `ArtifactContentStore`. Dieser Plan folgt ab hier den
Code-Namen.

### 3.2 Geliefert (✅) vs. offen (⏳)

| Urspruenglicher Scope (§3.1 alt) | Stand |
| --------------------------------- | ----- |
| Port fuer immutable Artefakte mit Metadaten | ✅ `ArtifactContentStore` + `ArtifactStore` + `ArtifactRecord` |
| Lokale File-Implementierung als Referenz | ✅ `FileBackedArtifactContentStore` + `FileBackedUploadSegmentStore` (`adapters:driven:storage-file`) |
| Atomare/resumefreundliche Upload-Strategie | ✅ `UploadSegmentStore` + `FileSpoolAssembledUploadPayload` + `StreamingHashWriter` + `RangeRead` |
| SHA-256-Validierung und Groessenmetadaten | ✅ Teil des Vertrags: `WriteArtifactOutcome.Stored(sha256, sizeBytes)` + `SizeMismatch`/`Conflict`/`AlreadyExists` |
| TTL/Retention-Metadaten | ✅ `ArtifactStore.deleteExpired`/`deleteExpiredRecords` + `expiresAt` auf dem Record + Quota-Sweeper (0.9.6 Phase F) |
| Pfad-/Credential-Trennung von Job-IDs | ✅ opake Refs via `ServerResourceUri` (`dmigrate://tenants/{tenant}/artifacts/{id}`); keine lokalen Pfade nach aussen |
| **S3-kompatible Implementierung** | ⏳ **fehlt** — nur File-Backed vorhanden; keine AWS-SDK-/MinIO-Dependency im Repo |
| **MCP/REST/gRPC-Jobvertrag auf Artifact-Refs** | ◐ MCP nutzt `ServerResourceUri`-Refs bereits; REST/gRPC sind selbst noch ungebaut (1.2.0/1.1.8) — Migration ist dort mitzudenken, nicht hier zu erzwingen |

**Fazit:** Der Object-Storage-Track ist als *Infrastruktur* zu ~85 %
durch 0.9.6 vorweggenommen. Das einzig genuin offene 0.9.8-relevante
Stueck ist die **S3-Adapter-Evaluierung** (§8).

---

## 4. Architekturposition

Beide Stores sind driven adapters hinter Ports. Anwendungen referenzieren
Artefakte ueber `ServerResourceUri`-Refs und Metadaten, nicht ueber konkrete
Pfade:

```text
hexagon:application / driving server adapters
        |
        v
ArtifactStore (Metadaten)   ArtifactContentStore (Bytes)   UploadSegmentStore (Multipart)
        |                            |                              |
        |                  +--> FileBackedArtifactContentStore      +--> FileBackedUploadSegmentStore
        |                  +--> (geplant) S3ArtifactContentStore     +--> (geplant) S3UploadSegmentStore
        |
        +--> JDBC-/File-Metadaten-Adapter (0.9.6)
```

Der bestehende dateibasierte Checkpoint-Pfad bleibt bewusst getrennt
(siehe §5.2). Eine spaetere Migration kann Checkpoints als spezielle
Artefakte modellieren oder einen `CheckpointStore` auf dem
`ArtifactContentStore` aufbauen — das ist **nicht** Teil dieses Plans.

---

## 5. Port-Vertraege (Ist-Stand 0.9.6)

### 5.1 In Scope (geliefert)

Byte-Store `ArtifactContentStore`:

```kotlin
interface ArtifactContentStore {
    fun write(artifactId: String, source: InputStream, expectedSizeBytes: Long): WriteArtifactOutcome
    fun openRangeRead(artifactId: String, offset: Long, length: Long): InputStream
    fun exists(artifactId: String): Boolean
    fun delete(artifactId: String): Boolean
}
// WriteArtifactOutcome: Stored(artifactId, sha256, sizeBytes) | SizeMismatch | AlreadyExists | Conflict
```

Multipart `UploadSegmentStore`: `writeSegment` / `listSegments` /
`openSegmentRangeRead` / `deleteAllForSession` (Outcomes analog mit
`Stored`/`AlreadyStored`/`Conflict`/`SizeMismatch`).

Metadaten `ArtifactStore`: `save` / `findById` / `list` (paginiert,
`ArtifactListFilter` mit owner/kind/jobRef/Zeitfenster) / `deleteExpired` /
`deleteExpiredRecords`.

Metadaten-Felder (`ArtifactRecord` / `ArtifactUploadMetadata`):
`artifactId`, `resourceUri`, `kind` (`ArtifactKind`: `SCHEMA`, `PROFILE`,
`DIFF`, `DATA_EXPORT`, `UPLOAD_INPUT`, `OTHER`), `contentType`, `format`,
`sizeBytes`, `sha256`, `createdAt`/`expiresAt`, Principal-/Tenant-Kontext,
Bundle-Hints (`bundleFormat`/`manifestPath`/`manifestFingerprint`).

### 5.2 Bewusst nicht abgedeckt

- eigener Object-Storage-Server
- generische Data-Lake-Verwaltung
- automatische Lifecycle-Policy-Erstellung in Cloud-Konten
- transparente Verschluesselung als Ersatz fuer Cloud-KMS
- direkter Ersatz fuer den bestehenden `CheckpointStore` (Checkpoints
  brauchen staerkere Konsistenzannahmen — separat bewerten, §10)
- ein `checkpoint`-`ArtifactKind`: Checkpoints sind heute **kein**
  Artefakt-Kind (die fruehe §5-Skizze listete `checkpoint`/`ddl`/`log` —
  der reale `ArtifactKind`-Enum tut das nicht)

---

## 6. Konfiguration (vorgeschlagen, fuer den S3-Adapter)

Vorgeschlagene spaetere `.d-migrate.yaml`-Erweiterung, sobald der
S3-Adapter gebaut wird:

```yaml
artifacts:
  store: s3 # file | s3
  file:
    root: ".d-migrate/artifacts"
  s3:
    endpoint: "https://s3.example.com"
    bucket: "d-migrate-artifacts"
    prefix: "prod/"
    region: "eu-central-1"
    credentials:
      provider: env
```

Credentials duerfen nicht in Logs oder Reports erscheinen. Die konkrete
Credential-Aufloesung muss dieselben Scrubbing-Regeln verwenden wie
Datenbankverbindungen (0.9.1-Security-Haertung).

---

## 7. Akzeptanzkriterien fuer die Planung

| Kriterium | Stand |
| --------- | ----- |
| Port trennt fachliche Artifact-IDs von Speicherpfaden | ✅ `ServerResourceUri` opak/tenant-scoped |
| File- und S3-Impl koennen denselben Vertrag erfuellen | ◐ Vertrag (`ArtifactContentStore`) steht; S3-Impl muss ihn noch erfuellen |
| Grosse Artefakte streamend lesbar/schreibbar | ✅ `InputStream`-basiert + `openRangeRead` + Multipart |
| SHA-256-Pruefung Teil des Vertrags, nicht nur CLI-Detail | ✅ `WriteArtifactOutcome.Stored.sha256` + `Conflict` |
| Plan klaert Versionierung von Checkpoints/Reports | ◐ Reports/Exports via `ArtifactKind`; Checkpoints bewusst separat (§5.2/§10) |
| Serveradapter referenzieren ohne lokale Pfade | ✅ MCP nutzt `ServerResourceUri` |

---

## 8. Offene Evaluierung: S3-Adapter (0.9.8-Deliverable)

Das einzig genuin offene Stueck. Diese Evaluierung ist der
0.9.8-Eval-Beitrag; die Implementierung wurde 2026-06-09 ebenfalls in
0.9.8 gezogen (hinter dem §8-Gate + eigenem ImpPlan).

> **Erledigt (2026-06-09):** Die Evaluierung liegt als eigenes Addendum
> [`object-storage-s3-eval.md`](object-storage-s3-eval.md) vor.
> **Empfehlung: AWS SDK for Java v2 (`software.amazon.awssdk:s3`) mit
> `url-connection-client`** (Native-Image-first-class, Netty vermeidbar).
> Der Fallback bleibt **innerhalb AWS SDK v2** (Transport-Wechsel
> `apache-client`/CRT) — **kein** Vendor-Wechsel; MinIO-Client ist wegen
> **EOL + Native-Image** disqualifiziert (Addendum §6; funktional koennte
> MinIO die Byte-Store-Ports erfuellen — Einzelobjekte je Segment).
> Planungsgestuetzt — der Dependency-Lock erfolgt nach der empirischen
> Validierung (Footprint/Native-Image/Multipart-5-MiB) im Addendum §8.

Die im Addendum bewerteten Achsen:

1. **Client-Library.** Optionen:
   - AWS SDK for Java v2 (`software.amazon.awssdk:s3`) — Standard, async,
     `endpoint-override` deckt S3-kompatible Ziele (MinIO/SeaweedFS/Ceph) ab;
     groesserer Footprint (relevant fuer den 0.9.8/1.0.0-Native-Image-/
     Distributions-Cut, vgl. Parquet-Hadoop-Footprint-Thema).
   - MinIO Java Client (`io.minio:minio`) — schlanker, S3-kompatibel-fokussiert,
     eigener HTTP-Stack.
   - Kriterien: Footprint, Streaming-/Multipart-API-Fit gegen
     `ArtifactContentStore`/`UploadSegmentStore`, Credential-Provider-Modell,
     Native-Image-Tauglichkeit, Lizenz.
2. **Segment-/Multipart-Mapping.** `UploadSegmentStore`-Segmente =
   **eigenstaendige S3-Objekte** (kein Multipart — Addendum §2). S3-Multipart
   nur fuer `ArtifactContentStore.write` grosser Artefakte (> 5 GiB;
   5-MiB-Mindest-Partgroesse, `complete`-Reihenfolge + ETag-Sammlung).
3. **Range-Read.** `openRangeRead(offset, length)` ↔ S3 `Range`-Header.
4. **Fehler-/Retry-Determinismus.** Partial Uploads, Timeouts, 5xx-Retry,
   Idempotenz gegen den bestehenden `AlreadyExists`/`Conflict`-Vertrag.
5. **Konfig-/Security.** Schema aus §6 + Scrubbing-Regeln festziehen.

**Liefergegenstand der Evaluierung:** ein Entscheidungs-Addendum (Lib-Pick +
Begruendung + Risiken), analog
[`parquet-decision-template.md`](../done/parquet-decision-template.md). Die
BI-Demo (`examples/bi-demo/`, SeaweedFS als S3-Ziel) liefert eine
reproduzierbare Probe-Umgebung fuer die Eval.

---

## 9. Arbeitspakete

1. ✅ Bestehende Artefakt-/Checkpoint-Pfade inventarisieren — Ergebnis: §3.
2. ✅ Minimalen Byte-Port entwerfen — `ArtifactContentStore` (0.9.6).
3. ✅ File-Implementierung als Referenz — `FileBackedArtifactContentStore` (0.9.6).
4. ✅ S3-kompatible Implementierung evaluieren — Empfehlung AWS SDK v2,
   siehe [`object-storage-s3-eval.md`](object-storage-s3-eval.md);
   Bau 2026-06-09 in 0.9.8 vorgezogen, hinter §8-Gate + eigenem ImpPlan.
5. ◐ Konfigurationsschema und Security-Regeln skizzieren — §6 (Entwurf steht).
6. ⏳ Migration des MCP-/REST-/gRPC-Jobvertrags auf Artifact-Refs planen —
   MCP nutzt Refs bereits; REST (1.2.0) und gRPC (1.1.8) erben das Modell beim
   Bau dieser Services.

---

## 10. Risiken

- Remote-Artefakte machen Fehlerfaelle sichtbarer: Partial Uploads,
  Timeouts, Retention und Berechtigungen muessen deterministisch behandelt
  werden — gegen den bestehenden `WriteArtifactOutcome`-Vertrag abbilden.
- Ein zu breiter Cloud-Abstraktionslayer kann schnell mehr Aufwand erzeugen
  als der erste Nutzen rechtfertigt. Mitigation: der Port existiert bereits
  schmal (§5.1); der S3-Adapter fuellt nur ihn.
- Footprint: ein S3-SDK kann den Distributions-/Native-Image-Cut belasten —
  dieselbe Spannung wie beim Parquet-/Hadoop-Footprint (1.0.0-Thema).
- Checkpoints brauchen staerkere Konsistenzannahmen als normale Reports.
  Deshalb bleiben sie bewusst auf dem separaten `CheckpointStore` und werden
  erst in einem eigenen Folge-Slice bewertet.
