# Plan: Catalog-Publisher und Lakehouse-Zieladapter

> Dokumenttyp: Architektur- und Integrationsplan
>
> Status: Entwurf (2026-05-01)
>
> Referenzen: `docs/roadmap.md`,
> `docs/parquet-export-import-evaluation.md`,
> `docs/object-storage-artifact-store.md`,
> `docs/profiling-data-quality-export.md`

---

## 1. Ziel

`d-migrate` erzeugt bei Migrationen wertvolle Metadaten:

- neutrale Schema-Snapshots
- Schema-Diffs
- DDL-Bundles
- Profiling-Reports
- Datenqualitaets- und Kompatibilitaetshinweise
- Export-/Import-Artefakt-Metadaten

Diese Informationen sollen perspektivisch in externe Metadata Catalogs
publiziert werden koennen. Optional soll spaeter bewertet werden, ob
`d-migrate` auch Lakehouse-Zieladapter fuer Iceberg oder Delta Lake anbieten
soll.

Die Prioritaet liegt klar auf dem Catalog-Publisher. Lakehouse-Zieladapter
sind eine spaetere Option und setzen Parquet-Export sowie Object Storage
voraus.

---

## 2. Motivation

Migrationen enden nicht bei DDL und Datenkopie. Teams muessen nachvollziehen:

- welches Schema wann migriert wurde
- welche Unterschiede zwischen Quelle und Ziel bestanden
- welche Profiling-Warnungen relevant waren
- welche Artefakte zu welchem Lauf gehoeren
- welche Datenqualitaetsregeln aus der Migration entstanden sind

Metadata Catalogs sind dafuer der richtige Integrationspunkt. `d-migrate`
soll diese Systeme beliefern, aber nicht selbst ein Catalog werden.

---

## 3. Scope

### 3.1 In Scope

- neutraler `MetadataPublisher`-Port
- Publizieren von Schema-Snapshots, Diffs, Profiling-Reports und Artefaktrefs
- Mapping auf mindestens einen ersten Catalog-Zieltyp
- Linkage zu Job- und Artifact-IDs aus MCP/REST/gRPC
- Dokumentation der minimalen Metadatenvertraege
- Evaluierung von Iceberg/Delta als spaetere Zieladapter

### 3.2 Nicht in Scope

- eigener Metadata Catalog
- eigene Governance- oder Lineage-Plattform
- vollstaendige OpenLineage-Abdeckung im ersten Schritt
- Iceberg-/Delta-Implementierung vor Parquet- und Object-Storage-Grundlage
- Query-Engine-Integration fuer Lakehouse-Tabellen

---

## 4. Architekturposition

Der Catalog-Publisher gehoert hinter einen driven-adapter-Port:

```text
hexagon:application
        |
        v
MetadataPublisher port
        |
        +--> FileMetadataPublisher
        +--> DataHubPublisher
        +--> OpenMetadataPublisher
        +--> GravitinoPublisher
        +--> UnityCatalogPublisher
        +--> PolarisPublisher
```

Die konkrete Liste ist nicht bindend. Wichtig ist, dass das Domain-Modell
keinen harten Bezug auf ein einzelnes Catalog-Produkt bekommt.

---

## 5. Catalog-Publisher-Zielbild

Ein erster Publisher sollte diese Informationen uebertragen koennen:

- Datenquelle und Zieldatenbank
- Tabellen, Spalten und neutrale Typen
- Primaer-/Fremdschluessel und relevante Constraints
- Profiling-Zusammenfassung pro Tabelle und Spalte
- Warnungen mit Severity und Code
- Schema-Diff-Zusammenfassung
- Links oder IDs zu Artefakten im ArtifactStore
- Migrationslauf-Metadaten (`jobId`, Zeitpunkt, Version von `d-migrate`)

Ein moeglicher CLI-Vertrag:

```text
d-migrate metadata publish \
  --schema schema.yaml \
  --profile profile.json \
  --diff diff.json \
  --target datahub \
  --output-report publish.report.json
```

Alternativ kann Publishing spaeter direkt an Jobs gekoppelt werden:

```text
d-migrate data profile --source prod \
  --publish-metadata datahub
```

Der entkoppelte Befehl ist fuer den ersten Schnitt besser testbar.

---

## 6. Lakehouse-Zieladapter

Iceberg- oder Delta-Zieladapter sind nur sinnvoll, wenn vorher diese
Grundlagen stehen:

- Parquet-Export/-Import ist entschieden und stabil
- Object-Storage-ArtifactStore ist entworfen oder implementiert
- Manifest-/Schema-Sidecars sind versioniert
- Typmapping fuer komplexe Typen ist dokumentiert

Ein spaeterer Zieladapter koennte dann aus relationalen Quellen eine
Lakehouse-nahe Zielstruktur erzeugen:

```text
d-migrate data export --source prod \
  --target-format iceberg \
  --output s3://bucket/path/table-set
```

Fuer eine erste Bewertung reicht aber eine Machbarkeitsanalyse:

- Ist Iceberg realistischer als Delta fuer einen JVM/Kotlin-Adapter?
- Soll `d-migrate` Tabellen selbst committen oder nur vorbereitete Parquet-
  und Manifest-Artefakte erzeugen?
- Wie werden Schema Evolution und Partitionierung abgebildet?
- Welche Rolle spielt ein Catalog wie Gravitino, Polaris oder Unity Catalog?

---

## 7. Abgrenzung

`d-migrate` bleibt ein Migrations- und Datenmanagement-Tool. Es soll:

- Metadaten publizieren
- Lakehouse-Anschlussfaehigkeit vorbereiten
- optional Daten in Lakehouse-kompatible Artefakte schreiben

Es soll nicht:

- Catalog-Governance nachbauen
- Query-Engines verwalten
- ein eigener Lakehouse-Control-Plane-Anbieter werden
- CDC oder dauerhafte Replikation fuer Lakehouse-Ziele ersetzen

---

## 8. Akzeptanzkriterien fuer 1.6.0

- Ein `MetadataPublisher`-Port ist fachlich spezifiziert.
- Mindestens ein konkretes Catalog-Ziel ist fuer einen ersten Adapter
  ausgewaehlt oder begruendet zurueckgestellt.
- Das Mapping von d-migrate-Artefakten auf Catalog-Entitaeten ist dokumentiert.
- Lakehouse-Zieladapter sind als Option mit klaren Vorbedingungen bewertet.
- Die Entscheidung haengt explizit von Parquet- und Object-Storage-Ergebnissen
  ab.

---

## 9. Arbeitspakete

1. Relevante d-migrate-Metadatenobjekte inventarisieren.
2. Neutralen `MetadataPublisher`-Vertrag skizzieren.
3. Candidate-Catalogs vergleichen: DataHub, OpenMetadata, Gravitino,
   Unity Catalog, Polaris.
4. Erstes Zielsystem fuer einen Prototyp auswaehlen.
5. Mapping fuer Schema, Profiling, Diff und Artefaktrefs definieren.
6. Lakehouse-Zieladapter gegen Parquet/Object-Storage-Abhaengigkeiten
   bewerten.
7. Implementierungsplan fuer den ersten Publisher ableiten.

---

## 10. Risiken

- Catalog-APIs und Produktgrenzen unterscheiden sich stark.
- Ein zu generischer Publisher-Port kann zu abstrakt werden und wenig
  praktischen Nutzen liefern.
- Lakehouse-Zieladapter koennen deutlich mehr Aufwand erzeugen als ein
  Migrationswerkzeug tragen sollte.
- Ohne Parquet- und Object-Storage-Grundlage waere eine Iceberg-/Delta-
  Implementierung zu frueh gekoppelt.

