# Plan: Object-Storage-ArtifactStore

> Dokumenttyp: Architektur- und Implementierungsplan
>
> Status: **Abgeschlossen (2026-06-14)** — nach `done/` verschoben; das
> Endergebnis fasst die [Closure](#closure)-Sektion zusammen. Der gesamte
> 0.9.8-relevante Scope ist geliefert: die S3-Adapter-Evaluierung
> (Abschnitt 8) liegt als
> [`object-storage-s3-eval.md`](../in-progress/object-storage-s3-eval.md)
> vor (Verdict: AWS SDK for Java v2 mit `url-connection-client`), und die
> S3-Implementierung ist mit
> [`ImpPlan-0.9.8-object-storage-s3.md`](ImpPlan-0.9.8-object-storage-s3.md)
> **abgeschlossen 2026-06-12** (S3.0–S3.6, Footprint +8,02 MiB). Bewusst auf
> Folge-Milestones vertagt: die Migration des REST-/gRPC-Jobvertrags auf
> Artifact-Refs (Arbeitspaket 6, Abschnitt 9) → Trigger
> [`rest-grpc-artifact-ref-inheritance.md`](../open/rest-grpc-artifact-ref-inheritance.md).
> Vormals „Reconciled gegen 0.9.6-Code (2026-06-09)" / „Entwurf (2026-05-01)".
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
| **S3-kompatible Implementierung** | ✅ **geliefert (2026-06-12)** — Modul `adapters:driven:storage-s3` (`S3ArtifactContentStore`/`S3UploadSegmentStore`), AWS SDK v2 + `url-connection-client`, SeaweedFS-Vertragssuiten gruen; Footprint +8,02 MiB ([`ImpPlan-0.9.8-object-storage-s3.md`](ImpPlan-0.9.8-object-storage-s3.md)) |
| **MCP/REST/gRPC-Jobvertrag auf Artifact-Refs** | ◐ MCP nutzt `ServerResourceUri`-Refs bereits; REST/gRPC sind selbst noch ungebaut (1.2.0/1.1.8) — Migration ist dort mitzudenken, nicht hier zu erzwingen. Vertagt als Trigger [`rest-grpc-artifact-ref-inheritance.md`](../open/rest-grpc-artifact-ref-inheritance.md) |

**Fazit:** Der Object-Storage-Track ist als *Infrastruktur* zu ~85 %
durch 0.9.6 vorweggenommen. Das zuletzt offene 0.9.8-Stueck —
S3-Adapter-Evaluierung (Abschnitt 8) **und** -Implementierung — ist seit
2026-06-12 geliefert; das Endergebnis fasst die [Closure](#closure)-Sektion
zusammen.

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
| File- und S3-Impl koennen denselben Vertrag erfuellen | ✅ Vertrag (`ArtifactContentStore`) steht; S3-Impl erfuellt ihn — Vertragssuiten S3.2/S3.3 gegen SeaweedFS gruen |
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
> [`object-storage-s3-eval.md`](../in-progress/object-storage-s3-eval.md) vor.
> **Implementierung abgeschlossen (2026-06-12):**
> [`ImpPlan-0.9.8-object-storage-s3.md`](ImpPlan-0.9.8-object-storage-s3.md)
> (S3.0–S3.6).
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
[`parquet-decision-template.md`](parquet-decision-template.md). Die
BI-Demo (`examples/bi-demo/`, SeaweedFS als S3-Ziel) liefert eine
reproduzierbare Probe-Umgebung fuer die Eval.

---

## 9. Arbeitspakete

1. ✅ Bestehende Artefakt-/Checkpoint-Pfade inventarisieren — Ergebnis: §3.
2. ✅ Minimalen Byte-Port entwerfen — `ArtifactContentStore` (0.9.6).
3. ✅ File-Implementierung als Referenz — `FileBackedArtifactContentStore` (0.9.6).
4. ✅ S3-kompatible Implementierung evaluieren — Empfehlung AWS SDK v2,
   siehe [`object-storage-s3-eval.md`](../in-progress/object-storage-s3-eval.md);
   **Bau abgeschlossen 2026-06-12** (S3.0–S3.6,
   [`ImpPlan-0.9.8-object-storage-s3.md`](ImpPlan-0.9.8-object-storage-s3.md)).
5. ✅ Konfigurationsschema und Security-Regeln — der Abschnitt-6-Entwurf ist
   produktiv umgesetzt als `ArtifactStorageConfig` + `ArtifactsConfigLoader`
   (S3.4a, snakeyaml); Credential-Scrubbing analog DB-Verbindungen aktiv.
6. ⏳ **Vertagt (Folge-Milestones).** Migration des REST-/gRPC-Jobvertrags auf
   Artifact-Refs — MCP nutzt Refs bereits; REST (1.2.0) und gRPC (1.1.8) erben
   das Modell beim Bau dieser Services. Trigger:
   [`rest-grpc-artifact-ref-inheritance.md`](../open/rest-grpc-artifact-ref-inheritance.md).

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

---

## Closure

> Verschoben nach `done/` am 2026-06-14. Diese Sektion fasst den Endstand
> zusammen; der Plan-Korpus oben bleibt als historischer Architektur-/
> Reconciliation-Kontext erhalten.

### Was dieser Plan erreicht hat

Der Plan hat 2026-06-09 den urspruenglichen „ArtifactStore"-Entwurf
(2026-05-01) gegen den real ausgelieferten 0.9.6-Code abgeglichen
(Namens-Reconciliation, Abschnitt 3) und den verbleibenden offenen Scope
auf **einen einzigen** Punkt eingeengt: den S3-Adapter. Dieser ist
inzwischen vollstaendig geliefert.

### Endstand der Arbeitspakete (Abschnitt 9)

| AP | Gegenstand | Endstand |
| -- | ---------- | -------- |
| 1 | Artefakt-/Checkpoint-Pfade inventarisiert | ✅ 0.9.6 (Abschnitt 3) |
| 2 | Byte-Port `ArtifactContentStore` | ✅ 0.9.6 |
| 3 | File-Referenz-Impl | ✅ 0.9.6 |
| 4 | S3-Impl evaluieren **+ bauen** | ✅ Verdict AWS SDK v2; Bau abgeschlossen 2026-06-12 (S3.0–S3.6) |
| 5 | Config-Schema + Security | ✅ `ArtifactStorageConfig`/`ArtifactsConfigLoader` (S3.4a) |
| 6 | REST/gRPC-Jobvertrag auf Artifact-Refs | ⏳ **vertagt** → Folge-Milestones (siehe unten) |

### Geliefert (S3-Adapter, 2026-06-12)

- Modul `adapters:driven:storage-s3` mit `S3ArtifactContentStore` +
  `S3UploadSegmentStore` (AWS SDK for Java v2 + `url-connection-client`).
- `artifacts.store: s3`-Konfiguration (Abschnitt-6-Schema) inkl.
  MCP-Wiring und Credential-Scrubbing.
- Vertragssuiten gegen SeaweedFS (Testcontainers) + MCP-Protokoll-E2E
  (Subprocess); Footprint +8,02 MiB am Release-JAR.
- Detail-Closure:
  [`ImpPlan-0.9.8-object-storage-s3.md`](ImpPlan-0.9.8-object-storage-s3.md);
  Verdict: [`object-storage-s3-eval.md`](../in-progress/object-storage-s3-eval.md).

### Bewusst vertagt (nicht 0.9.8)

- **REST-/gRPC-Jobvertrag auf Artifact-Refs (AP 6).** MCP nutzt die opaken
  `ServerResourceUri`-Refs bereits; REST (1.2.0) und gRPC (1.1.8) existieren
  noch nicht und erben das Modell erst beim Bau dieser Services. Diese Auflage
  ist innerhalb 0.9.8 nicht schliessbar und als eigener Trigger festgehalten:
  [`rest-grpc-artifact-ref-inheritance.md`](../open/rest-grpc-artifact-ref-inheritance.md).
- **Weitere S3-Folgearbeiten** (Prefix-Sweep fuer Retention,
  Native-Image-Recheck im 1.0.0-Cut, Metadaten-Persistenz) sind im S3-ImpPlan
  (Abschnitt 5/7) verortet und ebenfalls nicht Teil von 0.9.8.
- **CheckpointStore-Migration** bleibt wie in Abschnitt 5.2/10 begruendet ein
  separater Folge-Slice.
