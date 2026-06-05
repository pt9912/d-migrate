# AP11: Single-File-Metadatenvertrag fuer Parquet

> Dokumenttyp: Architekturentscheidung zu `parquet-export-import-evaluation.md`
>
> Status: Entwurf (2026-06-05) — **fixiert** den
> Single-File-Metadaten-Vertrag und schliesst die letzte
> Vertragsfrage vor AP12 (CLI/Factory-Wiring) und AP13
> (Entscheidungsvorlage).
>
> Referenzen: `parquet-export-import-evaluation.md` Abschnitt 8
> Arbeitspaket 11 + Abschnitt 6 (Single-File-Vertrag),
> `parquet-libraries.md` §3.1 (`withExtraMetaData` /
> `getKeyValueMetaData` in parquet-java 1.17.1),
> `parquet-manifest-format.md` (AP7-YAML-Schema des Bundle-
> Manifests), `parquet-directory-import.md` (AP8-Resolver),
> `parquet-import-input-dto.md` (AP9-Importpfad-Vertrag),
> `parquet-port-shape.md` (AP10-Reader-Port + `ChunkSchema`-
> Pflichtparameter).

---

## 1. Ziel

Hauptplan §6 nennt drei moegliche Wege fuer Single-File-Exporte
(`data export --format parquet` mit genau einer Tabelle ohne
`--split-files`):

- (A) Schema-Metadaten in Parquet-Key-Value-Metadaten im
  Datei-Footer (eingebettet).
- (B) Expliziter Sidecar-Vertrag `<datei>.manifest.yaml`
  parallel zur Parquet-Datei.
- (C) Bewusst eingeschraenkter Modus: nur Parquet-Footer +
  Ziel-JDBC-Schema beim Import, Metadatenverlust dokumentiert.

AP11 macht die bindende Wahl und legt fest, was im Footer steht,
wie der Reader das nutzt und wie sich der Single-File-Pfad zum
Bundle-Pfad (AP7-AP10) verhaelt.

---

## 2. Ausgangslage

- `parquet-libraries.md` §3.1 bestaetigt, dass parquet-java
  1.17.1 Footer-Key-Value-Metadaten verlaesslich unterstuetzt:
  `ParquetWriter.Builder#withExtraMetaData(Map<String, String>)`
  beim Schreiben, `ParquetFileReader.open(...)
  .fileMetaData.keyValueMetaData: Map<String, String>` beim
  Lesen. Der AP3-Spike hat den Pfad nicht aktiv getestet, aber
  die API ist seit parquet-mr 1.0 stabil.
- `parquet-manifest-format.md` (AP7) hat ein vollstaendiges
  YAML-Schema fuer Bundle-Manifeste festgelegt
  (`formatVersion`, `producer`, `producerVersion`,
  `exportedAt`, `schemaSource`, `tables[]` mit
  Spaltenmetadaten). Single-File ist dort als „nicht abgedeckt"
  markiert (§4.3, §11 AP11-Folgepunkt).
- `parquet-port-shape.md` §3.3 hat `ChunkSchema` zum
  Pflichtparameter des Readers gemacht. Beim Single-File-Import
  muss diese `ChunkSchema` aus **irgendeiner** Quelle vor dem
  ersten `nextChunk()` vorliegen — entweder aus dem Footer-KV
  oder aus dem Ziel-Schema (Option C).
- `parquet-libraries.md` §7 hat `data import --format parquet`
  auf `--source <pfad>` beschraenkt (kein stdin); Single-File
  ist legitime Quelle (`.parquet`-Endung), nicht nur
  Directory-Bundles.
- Es gibt **keine** bestehenden d-migrate-Konsumenten von
  Parquet-Single-File-Bundles, die wir kompatibel halten
  muessen — der erste Schnitt definiert das Format frei.

---

## 3. Optionen-Vergleich

### 3.1 Option A — Footer-Key-Value-Metadaten (eingebettet)

Writer schreibt einen YAML-Bytestrom in
`ParquetWriter.Builder#withExtraMetaData(mapOf("d-migrate.manifest"
to yaml))`. Reader liest
`keyValueMetaData["d-migrate.manifest"]`, parst und baut
`ChunkSchema`.

- Pro: ein einziger Bytestrom auf Disk pro Tabelle; keine
  Sidecar-Kohaerenz-Probleme.
- Pro: parquet-java 1.17.1 supportet das verlaesslich
  (§2 Befund).
- Pro: DuckDB/Arrow ignorieren unbekannte Key-Value-Metadaten
  — kein Bruch der AP4/AP5-Akzeptanztests.
- Contra: Footer-Groesse waechst leicht; bei breiten Schemas
  reden wir ueber wenige KB im Footer. Vernachlaessigbar
  gegenueber den Daten-Row-Groups.
- Contra: Bytestrom ist nicht plain-text-lesbar in einem
  Hex-Editor, sondern nur ueber den Parquet-Footer-Parser.
  Operator-Inspektion braucht ein Tool, das den
  Key-Value-Block dumpt — `parquet-tools`, DuckDB
  (`SELECT * FROM parquet_kv_metadata(...)`) oder ein eigenes
  d-migrate-CLI-Unterkommando spaeter.

### 3.2 Option B — Sidecar `<datei>.manifest.yaml`

Writer schreibt das YAML-Manifest als separate Datei neben die
`.parquet`-Datei (z.B. `users.parquet` +
`users.parquet.manifest.yaml`). Reader sucht den Sidecar
explizit, parst ihn, kombiniert mit der `.parquet`-Datei.

- Pro: Operatoren koennen das Manifest mit jedem YAML-Reader
  inspizieren.
- Pro: Klar trennbar — Datei plus Metadaten als zwei Artefakte.
- Contra: Zwei Artefakte muessen gemeinsam transportiert
  werden. Beim Kopieren/Versenden kann der Sidecar
  vergessen werden; der Reader muesste dann auf Option C
  zurueckfallen oder mit einem klaren Fehler abbrechen.
- Contra: Single-File-Exporte sind explizit „eine Datei". Ein
  Sidecar-Modus ist semantisch zwei Dateien und bricht das
  Versprechen, auch wenn die zweite Datei klein ist.
- Contra: AP7-Bundle-Manifeste (multi-table) und
  Sidecar-Manifeste (single-table) waeren zwei Vertraege
  parallel — bewusst gegen die AP7 §11-Empfehlung „strikt
  Teilmenge".

### 3.3 Option C — Footer-only + Ziel-JDBC-Schema

Writer schreibt **keine** d-migrate-Metadaten. Reader liest nur
den Parquet-Footer-`MessageType` und das Ziel-JDBC-Schema; das
ChunkSchema-`NeutralType`-Modell wird beim Import aus dem
Footer-Primitive-Type plus Ziel-Spalten-Typ rekonstruiert.

- Pro: Maximale Interoperabilitaet — jede Parquet-Datei
  funktioniert, auch nicht-d-migrate-erzeugte.
- Contra: Verlustfreiheit fuer Decimal-Precision,
  Temporal-Timezone, Enum-Values etc. ist nicht garantierbar;
  das Mapping ist N:M (parquet-schema-source.md §8).
- Contra: `schemaSource` ist „Ziel-JDBC" — auf dem Importpfad
  bedeutet das, dass d-migrate jeden Parquet-File akzeptiert,
  aber Decimal/Date/Boolean-Mappings nur „best effort" sind.
- Contra: Wuerde dem AP2-Schemaquelle-Vertrag widersprechen,
  der das Schema explizit **vor** dem ersten Chunk-Lesen
  haben will.

### 3.4 Vergleich

| Kriterium | A Footer-KV | B Sidecar | C Footer-only |
| --------- | ----------- | --------- | ------------- |
| Verlustfreiheit Decimal/Temporal | ja | ja | nein |
| Single-Artefakt-Versprechen | ja | nein | ja |
| Operator-Lesbarkeit ohne Tool | nein | ja | n/a |
| Risiko verlorener Metadaten beim Kopieren | nein | ja | n/a |
| AP2-Schemaquelle-Vertrag eingehalten | ja | ja | nein |
| DuckDB-/Arrow-Akzeptanztests bleiben gruen | ja | ja | ja |

---

## 4. Entscheidung

Bindend: **Option A — Footer-Key-Value-Metadaten mit Key
`d-migrate.manifest`**.

Begruendung:

- A ist die einzige Variante, die Verlustfreiheit und
  Single-Artefakt-Versprechen gleichzeitig erfuellt.
- Der Operator-Lesbarkeits-Nachteil ist real, aber durch
  Standard-Tooling abdeckbar (`parquet-tools meta <file>`
  oder DuckDB-`parquet_kv_metadata`); AP12 kann zudem ein
  d-migrate-Subkommando `data inspect <file>` skizzieren,
  das den Block extrahiert.
- B wird abgelehnt, weil der Sidecar-Bruch beim Kopieren ein
  realer Failure-Modus ist (z.B. SCP/rsync ohne
  Wildcard-Match) und das Single-File-Versprechen untergraebt.
- C wird abgelehnt, weil es AP2 §4.4 (Schema-vor-Chunk)
  verletzt und Decimal-/Temporal-Verlust strukturell
  zulaesst.

Die endgueltige CLI- und Implementierungs-Aktivierung dieser
Entscheidung ist AP12 (Wiring).

---

## 5. Format-Spezifikation

### 5.1 Key

```
d-migrate.manifest
```

Genau ein Eintrag im `extraMetaData`-Map. Andere Keys
(z.B. `org.apache.spark.sql.parquet.row.metadata`) werden
toleriert und ignoriert.

### 5.2 Value

Ein UTF-8-YAML-Bytestrom, strukturell eine **Auspraegung** des
AP7-Bundle-Manifests (`parquet-manifest-format.md` §5):

- `formatVersion`, `producer`, `producerVersion`, `exportedAt`,
  `schemaSource` — identisch zu AP7.
- `tables` enthaelt genau **einen** Eintrag.
- `tables[0].file` ist **nicht** belegt — der Footer-KV-
  Inhalt verweist nicht auf seine eigene Datei, der Reader
  weiss die Datei aus dem `--source`-Pfad. AP7 §5.2 wurde
  beim AP11-Abschluss von „Pflicht" auf „bedingt" angepasst:
  Pflicht im Bundle-Manifest, optional im Single-File-Footer-
  KV. Damit ist das AP11-YAML eine **konditionell strikte
  Teilmenge** des Bundle-YAML — kein zweites Schema, ein
  Parser-Pfad mit einer Validierungs-Vorbedingung
  („context == bundle ? file required : file ignored").
- `tables[0].sha256` ist **nicht** belegt; ein Datei-Hash
  ueber den eigenen Bytestrom (inkl. des Hash-Felds) ist
  zirkulaer. Resume-Integritaet wird ueber den externen
  Datei-Hash im Checkpoint geregelt (§6.4), nicht ueber das
  Footer-KV.
- `tables[0].rowCount`, `tables[0].columns` — identisch zu AP7.

Beispiel:

```yaml
formatVersion: "1.0"
producer: "d-migrate"
producerVersion: "0.9.8"
exportedAt: "2026-06-05T08:42:17Z"
schemaSource: "jdbc-metadata"
tables:
  - table: "public.orders"
    rowCount: 4711
    columns:
      - name: "id"
        nullable: false
        sqlTypeName: "int8"
        jdbcType: -5
        neutralType: { kind: "Long" }
      - name: "total"
        nullable: false
        sqlTypeName: "numeric"
        jdbcType: 2
        precision: 12
        scale: 2
        neutralType: { kind: "Decimal", precision: 12, scale: 2 }
```

### 5.3 Wenn der Key fehlt

Fehlender `d-migrate.manifest`-Key ist **kein Fehler im
Preflight** — die Datei ist trotzdem eine valide Parquet-
Datei und kann gelesen werden. Aber:

- Der `SeekableDataChunkReaderFactory.create(...)`-Aufruf
  braucht ein `ChunkSchema` (AP10 §3.3, Pflichtparameter).
  Ohne `d-migrate.manifest` baut der CLI-Resolver die
  `ChunkSchema` aus **Footer-`MessageType` + Ziel-JDBC-
  Schema** — das ist faktisch Option C als Fallback fuer
  fremde Parquet-Dateien.
- CLI-Warnung: `Note: file 'users.parquet' has no d-migrate
  manifest; using parquet-footer + target-jdbc-schema (lossy
  for decimal precision / temporal timezone).`
- Decimal-Precision/Scale, Temporal-Timezone, Enum-Werte
  koennen verloren gehen; das wird im AP13-Risikoblock
  benannt.

Das ist explizit „best effort", nicht „nicht unterstuetzt".
Wer Verlustfreiheit will, exportiert die Datei mit d-migrate
und hat den Key automatisch drin.

### 5.4 Single-File-Auto-Detection

`data import --source users.parquet` ohne explizites
`--format`:

- Endung `.parquet` -> `DataExportFormat.PARQUET`
  (`DataImportHelpers.inferFormatFromExtension`-
  Erweiterung in AP9 §7.5).
- Heuristik **nicht** auf den `d-migrate.manifest`-Key
  zurueckfallen; die Endung reicht. Andere Tools
  (Spark/Hive) produzieren auch Parquet-Dateien ohne unseren
  Key — sie sollen lesbar bleiben (§5.3).

### 5.5 Tabellennamens-Aufloesung (`--table` vs. Footer)

`DataImportHelpers.toImportInput` (Z. 136-138) wirft heute
`IllegalArgumentException`, wenn `--table` bei einem
Single-File-Import fehlt. Fuer Parquet-Single-File wird
diese Bedingung gelockert, weil der Footer-KV bereits einen
Tabellennamen tragen kann (`tables[0].table`). AP11 legt die
folgende Precedence bindend fest:

1. **`--table` explizit gesetzt** — CLI-Wert gewinnt.
   - Footer-KV vorhanden: CLI- und Footer-Wert werden im
     Preflight verglichen.
     - Identisch: OK.
     - Verschieden: Fehler
       `PARQUET_SINGLE_FILE_TABLE_MISMATCH` (kein
       stillschweigender Override; der Operator hat
       offensichtlich eine falsche Annahme).
   - Footer-KV fehlt oder kein `tables[0].table` darin:
     CLI-Wert wird ungeprueft akzeptiert.
2. **`--table` nicht gesetzt** und Footer-KV vorhanden mit
   `tables[0].table` — Footer-Wert wird verwendet. Die
   Diagnose (`stderr`) nennt den uebernommenen Wert
   einmalig: `Note: target table 'public.orders' resolved
   from d-migrate manifest in 'users.parquet'`.
3. **`--table` nicht gesetzt** und Footer-KV fehlt — Fehler
   `PARQUET_SINGLE_FILE_TABLE_REQUIRED` (analog zur
   bestehenden Diagnose, aber mit explizitem Parquet-
   Hinweis: „specify --table or export with the d-migrate
   parquet writer to embed it").

Die heutige Pflichtbedingung an `--table` fuer
JSON/YAML/CSV-Single-File bleibt unveraendert.

---

## 6. Reader-/Writer-Vertrag

### 6.1 Writer-Pfad

```text
data export --source prod --table orders --format parquet --output orders.parquet
```

1. `StreamingExporter` baut `ChunkSchema` (AP2 §4.4).
2. Writer serialisiert `ChunkSchema` plus Header-Metadaten
   (`producer`/`exportedAt`/`formatVersion`/`schemaSource`)
   in den AP11-YAML-Bytestrom (§5.2).
3. `ExampleParquetWriter.builder(...)
   .withExtraMetaData(mapOf("d-migrate.manifest" to yaml))
   ...`.

Stdout-Single-File ist mit dem `PositionOutputStream`-Pfad
(AP10 §3.4) genauso moeglich; der Footer wird beim
`close()` finalisiert und enthaelt das Key-Value-Set.

### 6.2 Reader-Pfad — Footer-Parsing endet im CLI-/Adapter-Preflight

```text
data import --source orders.parquet [--table public.orders]
```

Wichtige Eigenschaft: das Footer-Parsing passiert **nicht** im
`TableImporter`. `adapters:driven:streaming` haengt heute nicht
an `adapters:driven:formats-parquet`
(`adapters/driven/streaming/build.gradle.kts`), und das soll so
bleiben — der Streaming-Layer sieht nur port-neutrale Daten.
Der Resolver-/Preflight-Pfad uebersetzt also die `.parquet`-
Datei in eine bereits aufgeloeste, schema-tragende
Seekable-Variante:

1. CLI-Resolver erkennt `.parquet`-Endung und Single-File-Modus
   (`DataImportHelpers.resolveFormat`, vgl. AP9 §7.5).
2. Der Resolver delegiert an einen neuen
   `ParquetSingleFilePreflight` im Parquet-Adapter:
   - oeffnet den Parquet-Footer via `ParquetFileReader.open(...)`,
   - prueft `keyValueMetaData["d-migrate.manifest"]`:
     - Vorhanden: `ParquetSingleFileManifestReader` parst den
       YAML-Bytestrom (§5.2), baut die `ChunkSchema` analog
       AP8 §6.2 Stufe 1.
     - Fehlend: `ChunkSchema` aus Footer-`MessageType` plus
       Ziel-JDBC-Schema (§5.3); CLI-Warnung
       `PARQUET_SINGLE_FILE_NO_MANIFEST_USING_FOOTER`.
   - liefert ein adapter-internes
     `ResolvedParquetSingleFile(path, table, schema,
     contentSha256?)`-DTO (`contentSha256` siehe §6.4 zum
     Resume).
3. CLI-Schicht uebersetzt das adapter-interne DTO an der
   Port-Grenze in die port-neutrale Seekable-Variante des
   `ImportInputResolver`-Ergebnisses (vgl. AP10 §5.4):
   `ResolvedTableInput.Seekable(table, source =
   SeekableChunkSource.Local(path), schema, expectedSha256 =
   contentSha256)`.
4. `StreamingImporter` / `TableImporter` sehen
   `ResolvedTableInput.Seekable` — exakt dieselbe Variante,
   die der Bundle-Pfad produziert. Der `TableImporter`-
   Format-Zweig (`PARQUET -> seekableFactory.create(...)`)
   funktioniert identisch fuer Bundle und Single-File; es
   gibt keinen Parquet-Sonderpfad im Streaming-Layer.

`ImportInput.SingleFile` (AP9 §4.1) traegt **kein**
Schema-Feld und bleibt Parquet-frei. Die Schema-Information
wird **vor** dem Streaming-Layer im
`ResolvedTableInput.Seekable` materialisiert.

### 6.3 Single-File ohne `--source`-Pfad (stdin)

`parquet-libraries.md` §7 hat stdin fuer den Import bereits
abgelehnt — Parquet-Reader sind seekbar, nicht stream-fest.
AP11 bestaetigt das: Single-File-Stdin-Import ist nicht
unterstuetzt und scheitert im CLI-Preflight mit
`PARQUET_STDIN_NOT_SUPPORTED` (AP12-Fehlercode).

### 6.4 Resume fuer Single-File-Parquet

AP8 §8.1 hat fuer Bundle-Imports festgelegt: Resume ist nur
moeglich, wenn jede Resume-Tabelle einen Datei-Hash hat —
sonst koennte eine ausgetauschte Datei unerkannt
uebersprungen werden. Fuer Bundles kommt der Hash aus dem
Manifest (`tables[].sha256`). Fuer Single-File-Parquet
existiert dieser Manifest-Hash nicht (§5.2: zirkulaer), aber
das Resume-Sicherheitsproblem ist identisch.

Loesung — **externer Inhalts-Hash im Checkpoint**:

- Beim Initial-Lauf berechnet der
  `ParquetSingleFilePreflight` (§7.1) einen SHA-256 ueber
  den **gesamten Parquet-Dateibytestrom auf Disk**
  (Header + Row Groups + Footer; analog AP7 §7.2 fuer
  Bundle-Hashes). Der Hash wird in
  `ResolvedTableInput.Seekable.expectedSha256` befuellt
  und vom CLI-Pfad in den Checkpoint geschrieben.
- Persistenz erfolgt ueber dieselbe sealed
  `CheckpointOperationSpecifics`-Hierarchie wie beim
  Bundle (AP9 §4.2): neuer Subtyp
  `SingleFileCheckpointSpecifics(
  bundleKind = "parquet-single-file",
  contentSha256: String, table: String)`.
- Beim Resume berechnet der Preflight den Hash erneut und
  vergleicht. Mismatch ist Fehler
  `PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT`.

Trade-off bewusst: Resume-Aktivierung kostet bei jedem Lauf
einen vollstaendigen Datei-Read fuer den Hash. Das ist auf
ueblichen Single-File-Groessen (MB-Bereich) vernachlaessigbar;
auf Multi-GB-Single-File-Exports ist es real und sollte vom
Operator durch Bundle-Imports vermieden werden — was die
korrekte Entscheidung ist, weil Bundle-Imports Resume
billiger machen (Manifest-Hash ist Producer-seitig
berechnet, nicht Importer-seitig).

Kein-Resume-Modus (`--resume` nicht gesetzt) verzichtet auf
die Hash-Berechnung; der Initial-Hash wird trotzdem
geschrieben, damit ein **spaeterer** Resume-Lauf einen
Vergleichswert hat. CLI-Operatoren, die Resume nie nutzen
wollen, koennen das via `--no-checkpoint` abschalten
(AP12-Flag); das ist aber Sache von AP12, nicht AP11.

---

## 7. Konsequenzen fuer Code

### 7.1 `adapters:driven:formats-parquet`

- `ParquetSingleFileManifestWriter` (neu) — serialisiert
  `ChunkSchema` plus Header-Felder zu YAML, ruft
  `withExtraMetaData(...)`.
- `ParquetSingleFileManifestReader` (neu) — liest
  `keyValueMetaData["d-migrate.manifest"]`, parst zu einem
  Bundle-Manifest-mit-genau-einer-Tabelle und liefert die
  `ChunkSchema`. Wenn Key fehlt, gibt er `null` zurueck (der
  Preflight entscheidet ueber den Fallback).
- `ParquetSingleFilePreflight` (neu) — Adapter-interner
  Eintrittspunkt fuer den Single-File-Pfad. Kombiniert
  Footer-Lesen, `ParquetSingleFileManifestReader`-Aufruf
  und ggf. Footer-`MessageType`-/Ziel-JDBC-Fallback;
  berechnet den Datei-Hash fuer Resume (§6.4) und liefert
  ein adapter-internes `ResolvedParquetSingleFile`-DTO. Das
  ist die einzige Stelle, an der Footer-Parsing
  stattfindet — Streaming-Layer bleibt parquet-frei.

### 7.2 `adapters:driven:streaming`

- **Keine** Parquet-Dependency, kein Footer-Parsing.
  `adapters/driven/streaming/build.gradle.kts` bleibt
  unveraendert.
- Sealed `ResolvedTableInput` bekommt die Seekable-Variante
  laut AP10 §5.4 — derselbe Datentyp wird vom
  Bundle-Pfad und vom Single-File-Pfad befuellt. Der
  `TableImporter`-Zweig
  (`PARQUET -> seekableFactory.create(...)`) macht keinen
  Unterschied zwischen Bundle und Single-File.

### 7.3 `hexagon:application`

- `DataImportHelpers.resolveFormat` aus AP9 §7.5 ist bereits
  vorbereitet (Endung `.parquet` -> `PARQUET`).
- Neuer Aufruf von `ParquetSingleFilePreflight` im
  Single-File-Pfad, bevor `ImportInput.SingleFile` in den
  `ImportInputResolver` geht. Der Preflight ergaenzt das
  resolved `ResolvedTableInput.Seekable`-Tupel; die
  Tabellennamens-Aufloesung (`--table` vs. Footer) ist in
  §5.5 geregelt.

### 7.4 `adapters:driving:cli`

- AP12 macht das Wiring:
  - Format-Resolver erkennt `.parquet`-Single-File.
  - `ParquetSingleFilePreflight`-Aufruf inklusive
    Diagnose-Mapping (`PARQUET_SINGLE_FILE_NO_MANIFEST_USING_FOOTER`,
    `PARQUET_SINGLE_FILE_TABLE_MISMATCH` aus §5.5).
  - Optionales spaeteres `data inspect <file>`-Subkommando
    (out-of-scope fuer AP11).

### 7.5 Bewusst KEINE Aenderung

- AP7-Bundle-Manifest-Schema bleibt strukturell bestehen.
  Einzige Anpassung: `tables[].file` von Pflicht auf
  optional (siehe §5.2 plus AP7-Korrektur-Notiz im
  Plan-Doc); im Bundle-Manifest bleibt das Feld weiter
  Pflicht, der SingleFile-Footer-KV laesst es weg.
- Reader-Port aus AP10 (`SeekableDataChunkReaderFactory`)
  bleibt unveraendert; der `ChunkSchema`-Pflichtparameter
  passt zum AP11-Vertrag.
- `ImportInput.SingleFile`-DTO (AP9 §4.1) bleibt
  Parquet-frei: kein neues `schema`-Feld, kein neuer
  Subtyp. Die Schema-Information wird **vor** dem
  Streaming-Layer im `ResolvedTableInput.Seekable`
  materialisiert.

---

## 8. Risiken

- **Footer-KV-Groesse bei sehr breiten Schemas**: bei
  Tabellen mit hunderten Spalten waechst der YAML-Bytestrom
  auf zweistellige KB. Pro Datei ist das vernachlaessigbar
  (Footer ist anyway nur einmal pro Datei), aber wer
  Millionen kleiner Parquet-Dateien hat, sollte Bundle-
  Manifeste (AP7) statt Single-File-Footer-KV nutzen. AP12
  sollte das in der CLI-Doku erwaehnen.
- **Fremder Parquet-File ohne `d-migrate.manifest`**: der
  Fallback aus §5.3 ist „best effort" und verliert
  Decimal-Precision / Temporal-Timezone / Enum-Werte. CLI-
  Warnung ist Pflicht; AP13 muss das im Risikoblock der
  Entscheidungsvorlage benennen.
- **YAML-Parser-Konsistenz**: AP7 §4 hat „strict YAML, keine
  Anchors/Aliases/Tags" festgelegt. AP11 erbt das. Wenn ein
  Operator manuell einen Footer-KV-Block manipuliert
  (z.B. mit `parquet-tools rewrite-extra-metadata`) und
  Anchors einbaut, schlaegt der Reader fehl —
  beabsichtigt, weil unkontrollierte Tag-Resolution ein
  Sicherheitsproblem ist.
- **DuckDB/Arrow ignorieren unbekannte Keys verlaesslich?**
  Verifiziert: ja, beide stoeren sich nicht an
  Custom-Key-Value-Metadaten. AP12 sollte trotzdem einen
  Smoke-Test ergaenzen, der `read_parquet` und
  `arrow-vector`-Inspektion gegen eine d-migrate-erzeugte
  Single-File-Parquet laufen laesst.
