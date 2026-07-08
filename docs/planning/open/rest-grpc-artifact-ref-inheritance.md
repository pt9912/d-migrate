# REST-/gRPC-Jobvertrag auf Artifact-Refs

> Status: **Vorschlag** (Trigger Watch)
>
> Trigger: Abschluss des Object-Storage-S3-Adapters in 0.9.8
> ([`object-storage-artifact-store.md`](../done-archive/object-storage-artifact-store.md)
> Arbeitspaket 6 / Abschnitt 9). Der Byte-/Metadaten-ArtifactStore und der
> S3-Adapter liefern opake, tenant-skopierte `ServerResourceUri`-Refs; der
> MCP-Server nutzt sie bereits. REST und gRPC tun das noch nicht, weil es
> diese Services noch nicht gibt.
>
> Aktivierungsbedingung: sobald die REST-Service-Milestone (1.2.0) oder die
> gRPC-Service-Milestone (1.1.8) konkret geplant wird — dann wandert dieser
> Trigger als Akzeptanzkriterium in den jeweiligen Milestone-Plan (bzw. nach
> `../next/`), statt ein eigenstaendiger Slice zu werden.

---

## Worum es geht

`d-migrate` referenziert Artefakte (Exports, Profiling-Reports,
Schema-Snapshots, DDL-Bundles, Job-Ergebnisse) nach aussen ausschliesslich
ueber opake `ServerResourceUri`-Refs der Form
`dmigrate://tenants/{tenant}/artifacts/{id}` — nie ueber lokale Pfade oder
Storage-Credentials. Das ist eine Sicherheits- und Portabilitaetsauflage:

- keine lokalen Pfade in API-Antworten,
- kein Leck von Bucket-/Endpoint-/Credential-Details,
- gleiche Ref-Semantik unabhaengig vom Backend (`file` oder `s3`).

Der MCP-Server erfuellt das heute. Wenn REST (1.2.0) und gRPC (1.1.8) gebaut
werden, **muessen** ihre Jobvertraege dasselbe Ref-Modell erben, statt
direkte Pfade oder Backend-Details zu exponieren.

## Auflage an die kuenftigen Service-Milestones

1. Job-/Ergebnis-DTOs referenzieren Artefakte nur ueber `ServerResourceUri`,
   nie ueber `file:`-Pfade oder S3-URLs.
2. Lese-/Download-Endpunkte loesen Refs serverseitig auf (ueber
   `ArtifactStore`/`ArtifactContentStore`), inklusive Tenant-Scoping.
3. Credential-/Endpoint-Scrubbing analog zu DB-Verbindungen
   (0.9.1-Security-Haertung) auch in REST-/gRPC-Fehlerpfaden.
4. `openRangeRead`-Semantik (Range-Download grosser Artefakte) wird in beiden
   Protokollen sauber abgebildet.

## Warum kein eigener Slice (jetzt)

Es gibt keine eigenstaendige Scope-Arbeit: Der Port und das Ref-Modell stehen
bereits (0.9.6 + 0.9.8-S3-Adapter). Diese Auflage ist eine **Vererbung**, die
die REST-/gRPC-Milestones beim Bau honorieren — kein vorgezogener
Implementierungs-Slice. Innerhalb 0.9.8 ist sie nicht schliessbar (die
Services existieren nicht).

## Verwandte Tracker

- Architektur-/Closure-Kontext:
  [`object-storage-artifact-store.md`](../done-archive/object-storage-artifact-store.md)
  (Abschnitt 9, Closure).
- S3-Adapter-Closure:
  [`ImpPlan-0.9.8-object-storage-s3.md`](../done-archive/ImpPlan-0.9.8-object-storage-s3.md).
- Verwandter Service-Mode-Cluster (Pool/Cancellation/Rate-Limit/…):
  `carveout.md`-Zeile „Cross-JVM-Service-Mode-Vertraege (MCP/REST/gRPC)" →
  [`atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md).
