# Plan: Object-Storage-ArtifactStore

> Dokumenttyp: Architektur- und Implementierungsplan
>
> Status: Entwurf (2026-05-01)
>
> Referenzen: `docs/roadmap.md`, `docs/job-contract.md`,
> `docs/ki-mcp.md`, `docs/rest-service.md`, `docs/grpc-service.md`

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

## 3. Scope

### 3.1 In Scope

- Port fuer immutable Artefakte mit Metadaten
- lokale File-Implementierung als Referenz
- S3-kompatible Implementierung als erstes Remote-Ziel
- atomare bzw. resumefreundliche Upload-Strategie
- SHA-256-Validierung und Groessenmetadaten
- TTL/Retention-Metadaten fuer Serverbetrieb
- Pfad- und Credential-Trennung von fachlichen Job-IDs

### 3.2 Nicht in Scope

- eigener Object-Storage-Server
- generische Data-Lake-Verwaltung
- automatische Lifecycle-Policy-Erstellung in Cloud-Konten
- transparente Verschluesselung als Ersatz fuer Cloud-KMS
- direkter Ersatz fuer den bestehenden `CheckpointStore` im ersten Schritt

---

## 4. Architekturposition

Der ArtifactStore ist ein driven adapter hinter einem Port. Anwendungen
referenzieren Artefakte ueber IDs und Metadaten, nicht ueber konkrete Pfade:

```text
hexagon:application / driving server adapters
        |
        v
ArtifactStore port
        |
        +--> FileArtifactStore
        +--> S3ArtifactStore
```

Der bestehende dateibasierte Checkpoint-Pfad kann zunaechst bestehen bleiben.
Eine spaetere Migration kann Checkpoints als spezielle Artefakte modellieren
oder einen `CheckpointStore` auf dem ArtifactStore aufbauen.

---

## 5. Port-Skizze

Der konkrete Kotlin-Vertrag ist spaeter festzulegen. Fachlich werden diese
Operationen benoetigt:

- `put`: Artefakt schreiben und Metadaten zurueckgeben
- `openRead`: Artefakt streamend lesen
- `stat`: Metadaten lesen
- `delete` oder `expire`: Artefakt entfernen bzw. als abgelaufen markieren
- `list`: Artefakte fuer einen Job oder Principal paginiert auflisten
- `beginMultipart` / `appendPart` / `complete`: optional fuer grosse Uploads

Metadaten:

- `artifactId`
- `jobId`
- `kind` (`export`, `checkpoint`, `profile`, `schema`, `ddl`, `log`)
- `contentType`
- `sizeBytes`
- `sha256`
- `createdAt`
- `expiresAt`
- `principalId` oder Tenant-Kontext

---

## 6. Konfiguration

Vorgeschlagene spaetere `.d-migrate.yaml`-Erweiterung:

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
Credential-Aufloesung sollte dieselben Scrubbing-Regeln verwenden wie
Datenbankverbindungen.

---

## 7. Akzeptanzkriterien fuer die Planung

- Der Port trennt fachliche Artifact-IDs von Speicherpfaden.
- File- und S3-Implementierung koennen denselben Vertrag erfuellen.
- Grosse Artefakte koennen streamend gelesen und geschrieben werden.
- SHA-256-Pruefung ist Teil des Vertrags, nicht nur ein optionales CLI-Detail.
- Der Plan klaert, wie Checkpoints und Reports versioniert werden.
- Serveradapter koennen Artefakte referenzieren, ohne lokale Pfade preiszugeben.

---

## 8. Arbeitspakete

1. Bestehende Artefakt- und Checkpoint-Pfade inventarisieren.
2. Minimalen `ArtifactStore`-Port entwerfen.
3. File-Implementierung als Referenz definieren.
4. S3-kompatible Implementierung evaluieren.
5. Konfigurationsschema und Security-Regeln skizzieren.
6. Migration des MCP-/REST-/gRPC-Jobvertrags auf Artifact-Refs planen.

---

## 9. Risiken

- Remote-Artefakte machen Fehlerfaelle sichtbarer: Partial Uploads,
  Timeouts, Retention und Berechtigungen muessen deterministisch behandelt
  werden.
- Ein zu breiter Cloud-Abstraktionslayer kann schnell mehr Aufwand erzeugen
  als der erste Nutzen rechtfertigt.
- Checkpoints brauchen staerkere Konsistenzannahmen als normale Reports.
  Deshalb sollte die erste Version Checkpoints bewusst separat bewerten.

