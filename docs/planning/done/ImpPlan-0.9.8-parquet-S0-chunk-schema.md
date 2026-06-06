# S0 — ChunkSchema-Typanlage + Dockerfile-Warmup-Fixup

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md)
> §3 S0).
>
> Status: Closed (2026-06-06). Erster Implementierungs-Slice
> auf `feature/parquet-0.9.8`.

---

## 1. Scope

Zwei voneinander unabhaengige, aber bewusst gebuendelte
Artefakte:

1. **AP2.a — `ChunkSchema` + `ChunkColumnSchema` +
   `SchemaOrigin` in `hexagon:ports-common`.** Per
   [`parquet-schema-source.md`](parquet-schema-source.md)
   §6.1 bindend; `neutralType: NeutralType` non-null.
   Keine Caller-Migration (das ist S0b).
2. **Dockerfile-Warmup-Fixup fuer `formats-parquet`.**
   `settings.gradle.kts:24` listete das Modul, der
   Dockerfile-Warmup-Block hatte das `build.gradle.kts`
   aber nicht kopiert. Folge: `make docker-* MODULES=":adapters:driven:formats-parquet"`
   waere im naechsten Slice (S10a) gestolpert.

## 2. S0/S0b-Split (Befund-Audit 2026-06-06)

Der Umbrella sah urspruenglich einen einzelnen S0-Slice
vor, der Typanlage **und** `DataChunkWriter.begin`-Migration
zusammenfuehrte. Beim Implementierungs-Start hat sich
gezeigt:

- AP2 selbst hat eine Vier-Schritt-Reihenfolge AP2.a →
  AP2.b → AP2.c → AP2.d
  ([`parquet-schema-source.md`](parquet-schema-source.md)
  §7).
- `ChunkColumnSchema.neutralType` ist non-null (§6.1).
- `StreamingExporter` muss `ChunkSchema` **vor** dem
  ersten `begin`-Aufruf bauen (§6.4) — d.h. die
  Migration kann nicht ohne AP2.b-Mapping und
  AP2.c-Nullability-Resolver lauffaehig sein.
- `TableExporter` ruft heute `writer.begin(table,
  chunk.columns)`. Ohne AP2.b/c-Resolution waere die
  einzige Migrationsoption ein
  `NeutralType.Text()`-Placeholder fuer alle Spalten
  oder ein nullable-Deviation am AP2-Vertrag — beides
  widerspricht Memo [[no-carveouts]].

Konsequenz: Umbrella §3 splittet S0 in:

- **S0** (dieser Slice): nur AP2.a (Typanlage) +
  Dockerfile-Warmup.
- **S0b** (Folge-Slice): AP2.b (Mapping-Tabelle) + AP2.c
  (Nullability-Resolver) + AP2.d
  (`DataChunkWriter.begin`-Migration + JSON/YAML/CSV-
  Writer-Anpassung).

Reihenfolge im Umbrella wurde auf
`S0 → S0b → S2 → S10a → S3 → S10b → …` aktualisiert; AP13
§8.5 entsprechend nachgezogen.

## 3. Lieferumfang

### 3.1 Code

- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/format/data/ChunkSchema.kt`
  — drei Top-Level-Deklarationen: `ChunkSchema`,
  `ChunkColumnSchema`, `SchemaOrigin`. KDoc verweist auf
  AP2 §6.1 + AP9 §5 (MANIFEST_FALLBACK).
- Package bewusst `dev.dmigrate.format.data` (analog zu
  `DataExportFormat`), nicht
  `dev.dmigrate.ports.common.schema` wie AP2 §6.1
  illustrativ — die Modulwahl `hexagon:ports-common`
  bleibt bindend, die Package-Konvention im Modul ist
  `dev.dmigrate.format.data`.

### 3.2 Infrastruktur

- `Dockerfile` Zeile 84 (nach
  `adapters/driven/formats/build.gradle.kts`): neue
  COPY-Zeile fuer
  `adapters/driven/formats-parquet/build.gradle.kts`.
  Reihenfolge im Warmup-Block: alphabetisch nach
  bestehender Konvention waere `formats-parquet` direkt
  nach `formats` — diese Position wurde gewaehlt.

## 4. Was bewusst NICHT in S0 ist

- **Keine `DataChunkWriter`-Signaturaenderung.** Bleibt
  `begin(table: String, columns: List<ColumnDescriptor>)`.
- **Keine JSON/YAML/CSV-Writer-Anpassung.** Bleiben bei
  `ColumnDescriptor`.
- **Keine `TableExporter`/`StreamingExporter`-Anpassung.**
- **Kein JDBC→NeutralType-Mapping**, kein
  Nullability-Resolver.

Alle vier Punkte sind S0b-Inhalte.

## 5. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| `ChunkSchema` in `hexagon:ports-common` | `ls hexagon/ports-common/src/main/kotlin/dev/dmigrate/format/data/ChunkSchema.kt` | Datei vorhanden, drei Typen exportiert |
| Dockerfile-Warmup mit `formats-parquet` | `grep "formats-parquet/build.gradle.kts" Dockerfile` | Treffer Zeile 84 |
| Repo-Build gruen | `make docker-check` | BUILD SUCCESSFUL (399 actionable tasks) |
| Formats- und Parquet-Spike-Tests gruen | `make docker-test MODULES=":adapters:driven:formats :adapters:driven:formats-parquet"` | BUILD SUCCESSFUL (39 actionable tasks) |
| Sealed-Sweep ohne Befund-Drift | `make parquet-sweep` | Keine neuen Treffer auf `ChunkSchema`/`SchemaOrigin` (frische Typen, keine `when`-Konsumenten in S0) |

## 6. Folgeaufgaben

- **S0b** ist als naechster Slice geplant; loest die
  AP2.b/c/d-Arbeiten und macht `DataChunkWriter.begin`
  `schema`-tauglich.
- Befund-Rueckspiel-Kandidat fuer
  [`parquet-schema-source.md`](parquet-schema-source.md)
  §6.1: Package-Wahl
  (`dev.dmigrate.format.data` statt
  `dev.dmigrate.ports.common.schema`) ggf. nachziehen,
  damit AP2 die tatsaechliche Konvention spiegelt — wird
  erst in S0b-PR adressiert, weil S0b den Code aktiv
  konsumiert.
