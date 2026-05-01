# Evaluierung: Parquet-Export und -Import

> Dokumenttyp: Evaluierungs- und Architekturplan
>
> Status: Entwurf (2026-05-01)
>
> Referenzen: `docs/roadmap.md`, `docs/architecture.md`,
> `docs/cli-spec.md`, `docs/connection-config-spec.md`

---

## 1. Ziel

`d-migrate` unterstuetzt heute streaming-basierten Datenexport und -import
ueber JSON, YAML und CSV. Parquet soll evaluiert werden, weil es als
spaltenorientiertes Format einen direkten Anschluss an DuckDB, Apache Arrow
und spaetere Lakehouse-Ziele bietet.

Ziel dieser Evaluierung ist nicht sofort ein vollstaendiger Lakehouse-Adapter,
sondern ein belastbarer Entscheidungsrahmen:

- ob Parquet als zusaetzliches `DataExportFormat` sinnvoll ist
- welche Bibliothek in Kotlin/JVM wartbar einsetzbar ist
- wie Schema-, Typ- und Nullability-Informationen erhalten bleiben
- ob Import/Export weiterhin chunk-weise und speicherschonend bleibt
- welche Kompatibilitaet mit DuckDB und Arrow praktisch nachweisbar ist

---

## 2. Motivation

Parquet waere fuer `d-migrate` besonders nuetzlich in vier Szenarien:

- grosse Exporte, bei denen CSV/JSON zu gross oder zu langsam werden
- Analyse und Profiling exportierter Daten mit DuckDB
- reproduzierbare Migrationsartefakte fuer QA und Pilotvalidierung
- spaetere Anschlussfaehigkeit an Iceberg, Delta Lake oder andere
  Open-Table-Format-Ziele

Der Nutzen liegt primaer in Interoperabilitaet und Performance, nicht in
einem neuen fachlichen Migrationsmodell.

---

## 3. Scope

### 3.1 In Scope

- Evaluierung eines Parquet-Writers fuer `data export`
- Evaluierung eines Parquet-Readers fuer `data import`
- Abbildung des neutralen `DataChunk`-Modells auf Parquet-Spalten
- Umgang mit Decimal, Temporal, Binary, UUID, JSON, Arrays und Geometry
- Multi-Table-Export mit stabilen Dateinamen
- Kompatibilitaetsprobe mit DuckDB (`read_parquet`)
- Entscheidungsvorlage fuer einen spaeteren Implementierungsplan

### 3.2 Nicht in Scope

- Iceberg-/Delta-/Hudi-Tabellenverwaltung
- eigener Arrow-Ausfuehrungs- oder Query-Engine-Adapter
- Parquet als Ersatz fuer CSV/JSON/YAML
- automatische Schema-Evolution ueber mehrere Exportgenerationen
- Object-Storage-Implementierung; diese wird separat geplant

---

## 4. Architekturposition

Parquet sollte als Format-Adapter unter `adapters:driven:formats` beginnen.
Der bestehende Port-Schnitt bleibt massgeblich:

```text
hexagon:application
        |
        v
hexagon:ports-read / ports-write
        |
        v
adapters:driven:formats
        |
        v
ParquetChunkReader / ParquetChunkWriter
```

Ein spaeterer Arrow- oder Lakehouse-Adapter darf darauf aufbauen, sollte aber
nicht in den ersten Parquet-Schnitt hineingezogen werden.

---

## 5. Offene Architekturfragen

| Frage | Bewertungskriterium |
| ----- | ------------------- |
| Welche JVM-Bibliothek? | Wartung, Lizenz, Native-Image-Auswirkung, Streaming-Faehigkeit |
| Wie werden mehrere Tabellen abgelegt? | Verzeichnisstruktur, Sidecar-Metadaten, Import-Ergonomie |
| Wie stabil ist das Typmapping? | Round-trip PostgreSQL/MySQL/SQLite, Decimal/Temporal/UUID/Binary |
| Wie wird Geometry serialisiert? | WKB bevorzugt, optional WKT als spaeterer Modus |
| Wie wird JSON serialisiert? | String vs. Binary/JSON logical type, DuckDB-Kompatibilitaet |
| Wie wird Schema-Metadatenverlust vermieden? | Sidecar mit neutralem Schema oder TableManifest |

---

## 6. Vorgeschlagener CLI-Zielvertrag

Der bestehende Format-Parameter koennte erweitert werden:

```text
d-migrate data export --source prod --tables users,orders \
  --format parquet --output out/export

d-migrate data import --target staging \
  --format parquet --input out/export
```

Fuer Multi-Table-Exporte sollte `--output` ein Verzeichnis sein:

```text
out/export/
  manifest.yaml
  users.parquet
  orders.parquet
```

Das Manifest enthaelt mindestens:

- Formatversion
- Tabellenliste
- Spaltenreihenfolge
- neutrale Typinformationen, soweit verfuegbar
- Exportzeitpunkt
- optional SHA-256 pro Datei

---

## 7. Akzeptanzkriterien fuer die Evaluierung

- Ein Beispiel-Export kann mit DuckDB gelesen werden.
- Ein Round-trip `PostgreSQL -> Parquet -> PostgreSQL` bleibt fuer
  Kern-Datentypen verlustfrei.
- Ein Round-trip `MySQL -> Parquet -> SQLite` dokumentiert erwartete
  Typdegradierungen statt sie still zu verschweigen.
- Speicherverbrauch bleibt chunk-begrenzt; keine Tabelle wird vollstaendig
  in den Heap geladen.
- Die Evaluierung dokumentiert klar, ob Parquet in 1.x umgesetzt werden soll.

---

## 8. Arbeitspakete

1. JVM-Parquet-Bibliotheken gegen Lizenz, API und Streaming-Verhalten pruefen.
2. Prototyp fuer `ParquetChunkWriter` mit minimalem Typmapping bauen.
3. Prototyp gegen DuckDB lesen lassen und Typen inspizieren.
4. Importpfad fuer denselben Prototyp pruefen.
5. Manifest-Format skizzieren.
6. Entscheidungsvorlage mit Aufwand, Risiken und empfohlenem Scope erstellen.

---

## 9. Risiken

- Parquet-Bibliotheken koennen die GraalVM-Native-Image-Planung erschweren.
- Komplexe Typen wie Geometry, JSON und Arrays koennen ohne Sidecar
  semantische Informationen verlieren.
- Parquet ist spaltenorientiert; sehr kleine Tabellen profitieren kaum.
- Eine zu fruehe Lakehouse-Abstraktion wuerde den bestehenden Format-Adapter
  unnoetig verkomplizieren.

