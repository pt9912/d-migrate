# AP7: Parquet-Manifest-Format und Import-Preflight

> Dokumenttyp: Architekturentscheidung zu `parquet-export-import-evaluation.md`
>
> Status: Entwurf (2026-06-05)
>
> Referenzen: `parquet-export-import-evaluation.md` Abschnitt 8
> Arbeitspaket 7 + Abschnitt 6 (Manifest-Anforderungen),
> `parquet-libraries.md` (insbesondere §7 zum Reader-Vertrag und §3.5
> zu DuckDB-`read_parquet`-Kompatibilitaet),
> `parquet-schema-source.md` (AP2 Schemaquelle, insbesondere §6
> `ChunkSchema`-Vertrag), `spec/architecture.md`, `spec/cli-spec.md`.

---

## 1. Ziel

Arbeitspaket 7 des Parquet-Evaluierungsplans verlangt die Skizze des
Manifest-Formats fuer Parquet-Bundle-Exporte und des Preflight-Vertrags
fuer Bundle-Importe. Das Manifest ist laut Hauptplan §6 nicht optional,
sondern verpflichtend fuer alle Multi-Table-/Directory-Exporte; ohne es
ist ein Parquet-Directory-Import nicht moeglich.

Dieser Sub-Doc

- legt die verpflichtenden und optionalen Felder des Manifests fest,
- definiert das exakte YAML-Schema (Pflicht/Optional/Typ/Bereich),
- klaert die Tabellen-zu-Datei-Aufloesung inklusive
  Kollisionsschutz fuer schema-qualifizierte Tabellen,
- spezifiziert das SHA-256-Verfahren als Opt-in mit klaren
  Bytestrom-Regeln,
- definiert die Formatversionierung und Migrationspfad-Regeln,
- beschreibt den Preflight-Vertrag (Validierungen, Fehlerklassen),
- benennt die Konsequenzen fuer `hexagon`-Ports und `adapters`-Module.

Die finale Implementierungsentscheidung ist erst nach AP8 (Directory-
Aufloesung) und AP9 (Importpfad-Vertrag) bindend; dieser Sub-Doc liefert
das Skelett, an dem AP8/AP9 weiterarbeiten.

---

## 2. Ausgangslage

Bestehende Bausteine in d-migrate (verifiziert via Code-Sichtung
2026-06-05):

- `adapters/driven/formats-parquet/` enthaelt nur den AP3-Spike; es
  gibt noch keinen `ParquetManifestReader`/`-Writer`.
- `hexagon/ports-read/.../DataChunkReaderFactory.kt` ist
  `InputStream`-basiert; Parquet bekommt laut `parquet-libraries.md`
  §7.1 einen komplementaeren `SeekableDataChunkReaderFactory`-Port,
  der Pfade akzeptiert. Dieser Port-Schnitt ist die natuerliche Stelle,
  an der der Manifest-Preflight angesetzt wird.
- `ImportInput.Directory` traegt heute nur Verzeichnis, optionalen
  Tabellenfilter und Tabellenordnung; manifestseitige
  `Tabelle -> Pfad`-Bindings sind nicht modelliert (vgl. Hauptplan §6).
  Die DTO-Erweiterung ist Sache von AP9 und liegt nicht in diesem
  Sub-Doc.
- JSON/YAML/CSV-Bundle-Exporte erzeugen heute kein Manifest; jeder
  Format-Writer schreibt eigenstaendig pro Tabelle eine Datei. Das
  Parquet-Manifest fuehrt deshalb einen Vertrag ein, den die
  bestehenden Format-Adapter nicht teilen — er ist Parquet-spezifisch.
- Im `snakeyamlEngineVersion=2.7` (`gradle.properties`) liegt bereits
  ein YAML-Writer-Pfad vor, der vom CSV/JSON/YAML-Stack genutzt wird.
  Das Parquet-Manifest kann die gleiche Bibliothek verwenden.
- `parquet-schema-source.md` §6 fuehrt einen `ChunkSchema`-Vertrag
  ein (formatseitig, mit JDBC-Hints + neutralem Typ); die
  Manifest-Felder leiten sich davon ab, nicht von
  `ColumnDescriptor` (das traegt nur name/nullable/`sqlTypeName`).

Folgerung: Das Manifest ist ein neuer, Parquet-spezifischer
**Bundle-Header-Vertrag**. Er teilt sich Format-Bibliothek (snakeyaml)
und Hexagon-Position (`adapters:driven:formats-parquet`) mit dem
restlichen Parquet-Adapter, aber traegt keinen direkten Kontakt zu
JSON/YAML/CSV-Codecs.

---

## 3. Anforderungen an das Manifest

Aus Hauptplan §6 (Manifest enthaelt mindestens ...) und §7
(Akzeptanzkriterien) abgeleitet:

- M1 Manifest ist verpflichtend fuer Multi-Table-/Directory-Exporte
  und wird im Bundle-Verzeichnis als `manifest.yaml` abgelegt.
- M2 Manifest traegt eine **Formatversion** (semver-aehnlich), damit
  Reader inkompatible Aenderungen erkennen koennen.
- M3 Manifest traegt die Tabellenliste in Exportreihenfolge.
- M4 Manifest traegt fuer jede Tabelle ein explizites
  `Tabelle -> Datei`-Mapping, inklusive schema-qualifizierter
  Tabellennamen (z.B. `public.orders` vs. `analytics.orders`).
- M5 Manifest traegt fuer jede Tabelle die Spaltenreihenfolge und
  neutrale Typinformationen, soweit verfuegbar (`NeutralType`).
- M6 Manifest traegt den Ursprung des Typmappings pro Tabelle
  (`schema-reader`, `jdbc-metadata`, `manifest-fallback`); Hauptplan
  §6 nennt das explizit als Pflichtinhalt.
- M7 Manifest traegt JDBC-/SQL-Hints, soweit fuer verlustfreie
  Parquet-Schemaerzeugung noetig (originaler SQL-Typname,
  JDBC-Typcode, Precision/Scale fuer Decimal, Temporal-/Timezone-
  Hinweise) — das ist das Spiegelbild des AP2-`ChunkSchema`.
- M8 Manifest traegt Nullability pro Spalte.
- M9 Manifest traegt den Exportzeitpunkt (UTC, ISO-8601).
- M10 Manifest darf optional SHA-256 pro Datei tragen; das Verfahren
  ist deterministisch und im Preflight verifizierbar.
- M11 Manifest darf keine Felder enthalten, die der Reader nicht
  versteht — unbekannte Felder fuehren in strikt kompatiblen
  Major-Versionen zum Fehler, in additiven Minor-Versionen zur
  Warnung (siehe §8).
- M12 Preflight muss alle referenzierten Dateien existieren, regulaer
  und innerhalb des Importverzeichnisses (kein Pfadausbruch) sein.
- M13 Unbekannte `.parquet`-Dateien im Bundle (nicht im Manifest
  referenziert) werden im Preflight abgelehnt, **nicht** still
  importiert (Hauptplan §6).
- M14 Jede Datei ist hoechstens einer Tabelle zugeordnet.
- M15 Schema-qualifizierte und unqualifizierte Tabellen werden ueber
  das Manifest eindeutig aufgeloest; gleichnamige Tabellen in
  verschiedenen Schemas brauchen unterschiedliche Dateinamen.
- M16 Optionale SHA-256-Werte werden vor dem Streaming-Import
  geprueft, nicht waehrend.
- M17 Manifest-Validierung ist die einzige Quelle fuer die
  Tabellenordnung des Imports; der Streaming-Import darf nicht
  erneut aus Dateinamen inferieren.

---

## 4. Format-Optionen

### 4.1 YAML vs. JSON

Hauptplan §6 nennt explizit `manifest.yaml`. Begruendung:

- YAML ist im d-migrate-Stack bereits etabliert (`snakeyaml-engine`
  als bestehende Format-Bibliothek, vgl. `gradle.properties`).
  Kein neuer Parser im Distributions-Artefakt.
- Das Manifest ist primaer ein **Mensch-lesbarer Bundle-Header** —
  Operatoren sollen es ohne Tooling inspizieren koennen, um z.B.
  Tabelle-zu-Datei-Bindings zu verifizieren. JSON ist dafuer
  weniger geeignet (keine Kommentare, keine Block-Skalare).
- Maschinen-Lesbarkeit ist trotzdem voll gegeben; jeder JSON-Reader
  kann den YAML-Subset, den wir hier schreiben, ueber einen
  YAML→JSON-Konverter konsumieren (das Manifest verwendet
  ausschliesslich strikte Skalar-, Sequenz- und Map-Knoten, keine
  Anchors/Aliases/Tags).

Entscheidung: **YAML**, mit der Einschraenkung „nur strikte
Skalar-/Sequenz-/Map-Knoten, keine Anchors/Aliases/Tags". Das haelt
das Manifest fuer beliebige YAML-Reader und ToolGenerationen lesbar.

### 4.2 Manifest am Bundle-Wurzel vs. in einem Sidecar-Verzeichnis

- (a) `out/export/manifest.yaml` plus Parquet-Dateien daneben
  (Hauptplan §6 Beispiel).
- (b) `out/export/_manifest/manifest.yaml` + `out/export/data/*.parquet`.

Entscheidung: **(a) flach am Bundle-Wurzel.** Begruendung:

- Konsistent mit dem Hauptplan-Beispiel.
- Keine kuenstliche Trennung zwischen „Bundle-Header" und „Datendateien",
  die im Streaming-Import ohnehin gemeinsam validiert werden.
- Vermeidet eine zweite Verzeichnisebene, die das DuckDB-/Arrow-
  `read_parquet`-Akzeptanztest-Werkzeug nicht erwartet.

### 4.3 Eingebettete Metadaten vs. Sidecar

Single-File-Exporte (Hauptplan §6 Single-File-Vertrag) sind **nicht
Teil dieses Sub-Docs** — die werden in AP11 separat entschieden
(Parquet-Footer-Key-Value-Metadaten vs. expliziter Sidecar). Hier
geht es ausschliesslich um **Bundle-/Directory-Exporte**, fuer die
das Manifest verpflichtend ist.

---

## 5. Manifest-Schema-Spezifikation

### 5.1 Top-Level-Struktur

```yaml
formatVersion: "1.0"            # M2, Pflicht
producer: "d-migrate"           # Pflicht, fixer String
producerVersion: "0.9.8"        # Pflicht, semver
exportedAt: "2026-06-05T08:42:17Z"  # M9, Pflicht, UTC ISO-8601
schemaSource: "jdbc-metadata"   # M6, Pflicht (siehe 5.3)
tables:                          # M3, Pflicht, Sequenz
  - table: "public.orders"
    file: "orders.parquet"
    rowCount: 4711              # Optional, informativ
    sha256: "a3f5b…"            # M10, optional
    columns: [...]              # siehe 5.4
```

### 5.2 Felder im Detail

| Feld | Pflicht | Typ | Bedeutung |
| ---- | ------- | --- | --------- |
| `formatVersion` | ja | String, semver-aehnlich (Major.Minor) | M2/§8 Versionierung |
| `producer` | ja | String, fix `"d-migrate"` | Erkennbarkeit fremder Bundles |
| `producerVersion` | ja | String, d-migrate-Version | Diagnose; nicht funktional ausgewertet |
| `exportedAt` | ja | String, UTC ISO-8601 | M9 |
| `schemaSource` | ja | enum: `schema-reader`, `jdbc-metadata`, `manifest-fallback` | M6 |
| `tables` | ja | Sequenz | M3, M4 |
| `tables[].table` | ja | String, optional schema-qualifiziert | M4, M15 |
| `tables[].file` | ja | String, relativer Pfad im Bundle | M4, M12 |
| `tables[].rowCount` | nein | Integer (>= 0) | informativ; nicht im Preflight ausgewertet |
| `tables[].sha256` | nein | String, 64 Hex-Zeichen | M10, M16 |
| `tables[].columns` | ja | Sequenz | M5, M7, M8 |
| `tables[].columns[].name` | ja | String | Spaltenname |
| `tables[].columns[].nullable` | ja | Boolean | M8 |
| `tables[].columns[].neutralType` | nein | Map (siehe 5.4) | M5 (`NeutralType`-Repr) |
| `tables[].columns[].sqlTypeName` | nein | String | M7 (Originaltypname) |
| `tables[].columns[].jdbcType` | nein | Integer | M7 (`java.sql.Types`-Konstante) |
| `tables[].columns[].precision` | nein | Integer | M7 (Decimal) |
| `tables[].columns[].scale` | nein | Integer | M7 (Decimal) |
| `tables[].columns[].timezone` | nein | String | M7 (Temporal-Hint, z.B. `UTC` oder `"-"`) |

### 5.3 `schemaSource`

Spiegelt die Vorentscheidung aus `parquet-schema-source.md` §5:

- `schema-reader` — vor dem Streaming gerufener
  `SchemaReader.read(...)`; `neutralType` ist gefuellt, JDBC-Hints
  sind optional.
- `jdbc-metadata` — `ResultSetMetaData` der Exportquery liefert
  `sqlTypeName`/`jdbcType`/`precision`/`scale`; `neutralType` ist
  optional und nur dann gefuellt, wenn ein Mapping eindeutig
  ableitbar ist (vgl. `parquet-schema-source.md` §8 Mapping-Tabelle).
- `manifest-fallback` — der Schreiber konnte weder
  `SchemaReader`- noch `JDBC`-Quellen sicher konsultieren (z.B.
  bei einer reinen CSV-zu-Parquet-Konvertierung); die
  Typinformationen sind explizit best-effort.

`schemaSource` ist genau einmal pro Bundle gesetzt, nicht pro
Tabelle. Mischbetrieb ist nicht vorgesehen; der Producer entscheidet
sich beim Export-Start fuer eine Quelle.

### 5.4 `neutralType`

Repraesentation der Sealed-Hierarchie aus
`hexagon/core/.../model/NeutralType.kt` als YAML-Map. Konvention:
ein `kind`-Diskriminator plus typspezifische Felder.

Beispiele:

```yaml
neutralType: { kind: "Integer" }
neutralType: { kind: "Long" }
neutralType: { kind: "Decimal", precision: 12, scale: 4 }
neutralType: { kind: "Varchar" }
neutralType: { kind: "Text" }
neutralType: { kind: "Date" }
neutralType: { kind: "DateTime", timezone: "UTC" }
neutralType: { kind: "Boolean" }
neutralType: { kind: "Binary" }
neutralType: { kind: "Uuid" }
neutralType: { kind: "Json" }
neutralType: { kind: "Geometry", geometryType: "POINT", srid: 4326 }
neutralType: { kind: "Enum", values: ["red", "green", "blue"] }
neutralType: { kind: "Array", element: { kind: "Integer" } }
```

Die konkrete `kind`-Liste, Pflichtfelder pro Variante und die
JDBC-Mapping-Tabelle leben in `parquet-schema-source.md` §8 und werden
in AP12 (CLI-/Factory-Wiring-Skizze) finalisiert. Der vorliegende
Sub-Doc legt nur die YAML-Repraesentation fest, nicht die
Sealed-Hierarchie selbst.

### 5.5 Vollstaendiges Beispiel

```yaml
formatVersion: "1.0"
producer: "d-migrate"
producerVersion: "0.9.8"
exportedAt: "2026-06-05T08:42:17Z"
schemaSource: "jdbc-metadata"
tables:
  - table: "public.orders"
    file: "public.orders.parquet"
    rowCount: 1500
    sha256: "9b74c9897bac770ffc029102a200c5de57c0a6f9f5e1f3a3d28b9b3e0d6b2c5e"
    columns:
      - name: "id"
        nullable: false
        sqlTypeName: "int8"
        jdbcType: -5            # java.sql.Types.BIGINT
        neutralType: { kind: "Long" }
      - name: "total"
        nullable: false
        sqlTypeName: "numeric"
        jdbcType: 2              # java.sql.Types.NUMERIC
        precision: 12
        scale: 2
        neutralType: { kind: "Decimal", precision: 12, scale: 2 }
      - name: "created_at"
        nullable: false
        sqlTypeName: "timestamptz"
        jdbcType: 2014           # java.sql.Types.TIMESTAMP_WITH_TIMEZONE
        timezone: "UTC"
        neutralType: { kind: "DateTime", timezone: "UTC" }
  - table: "analytics.orders"
    file: "analytics.orders.parquet"
    rowCount: 320
    columns:
      - name: "order_id"
        nullable: false
        sqlTypeName: "int8"
        jdbcType: -5
        neutralType: { kind: "Long" }
```

---

## 6. Tabellen-zu-Datei-Aufloesung

### 6.1 Stabile Dateinamen

- Der Producer SOLL als Default `<schema>.<table>.parquet` schreiben,
  um Kollisionen zwischen gleichnamigen Tabellen in verschiedenen
  Schemas (M15) zu vermeiden.
- Fuer unqualifizierte Tabellen ist `<table>.parquet` zulaessig.
- Der Reader leitet die Bindung **ausschliesslich** aus dem Manifest-
  Feld `tables[].file` ab; Dateinamen-Inferenz ist verboten (M17).

### 6.2 Kollisionsschutz im Preflight

Der Preflight lehnt das Bundle ab, wenn:

- (K1) zwei Manifest-Eintraege dieselbe `table` (case-sensitive) tragen,
- (K2) zwei Manifest-Eintraege dieselbe `file` tragen,
- (K3) eine `file` ausserhalb des Bundle-Verzeichnisses liegt (kein
  `..`, kein absoluter Pfad, kein Symlink-Ausbruch),
- (K4) eine im Manifest referenzierte Datei nicht existiert oder
  nicht regulaer ist,
- (K5) eine `.parquet`-Datei im Bundle nicht im Manifest referenziert
  ist (M13). Implementierungshinweis: Die Aufzaehlung verwendet
  ausschliesslich `Files.list(bundleDir)` und die NIO-Dateiendung
  `.parquet`; Hadoop-Sidecars wie `.<datei>.parquet.crc` werden nicht
  als `.parquet`-Dateien erkannt und stoeren den Preflight nicht
  (vgl. `parquet-libraries.md` §7 / AP6-Befund).

### 6.3 Schema-qualifizierte Tabellen

Reader und Writer behandeln `schema.table` als atomaren String; der
Preflight zerlegt ihn nicht. Damit bleiben Tabellennamen mit Punkten
in Quote-Zeichen (z.B. `"public"."odd.name"`) im Manifest moeglich,
sofern der Producer sie korrekt schreibt. Der Reader vergleicht
lexikalisch.

---

## 7. SHA-256-Verfahren

### 7.1 Opt-in

- SHA-256 ist optional. Producer setzt das Feld pro Tabelle entweder
  vollstaendig oder gar nicht. Mischbetrieb (manche Tabellen mit,
  manche ohne) ist erlaubt.
- Das CLI-Flag `--manifest-sha256` (genauer Name AP12) aktiviert die
  Berechnung auf der Writer-Seite.
- Der Reader verifiziert genau dann, wenn das Feld gesetzt ist.

### 7.2 Bytestrom

- Gehasht wird der **fertige Parquet-Datei-Inhalt nach Writer-Close**
  (Header + Row Groups + Footer), nicht der unkomprimierte Inhalt.
  Das macht den Hash gegen Compression-Codec-Wechsel sensitiv und
  schliesst stille Modifikationen am Footer ein.
- `.crc`-Sidecars werden NICHT in den Hash einbezogen — sie sind
  Hadoop-spezifisch und werden im produktiven Pfad ohnehin
  unterdrueckt (vgl. `parquet-libraries.md` §7 / AP6-Befund).

### 7.3 Darstellung

- Lowercase Hex, 64 Zeichen, ohne `sha256:`-Praefix. Begruendung:
  zweite-Form-Familien (`sha512:`, `blake3:`) sind nicht geplant;
  ein Praefix waere semantisch leer.

### 7.4 Verifikation

- Preflight liest die Datei einmal komplett in einen Streaming-Digest
  (`MessageDigest.getInstance("SHA-256")`) und vergleicht.
- Mismatch fuehrt zu Preflight-Fehler `MANIFEST_SHA256_MISMATCH`; der
  Streaming-Import startet nicht.
- Fehlende Datei fuehrt zu `MANIFEST_FILE_MISSING` (siehe §9).

---

## 8. Format-Versionierung

### 8.1 Schema

`formatVersion: "<MAJOR>.<MINOR>"`, mit folgenden Regeln:

- **MAJOR-Bump** signalisiert inkompatible Aenderungen
  (Feld entfernt, Feld-Semantik geaendert, Pflichtfeld neu, Bytestrom-
  Berechnung fuer SHA-256 geaendert). Reader mit kleinerem MAJOR
  lehnen das Bundle im Preflight ab.
- **MINOR-Bump** signalisiert additive Aenderungen (neues optionales
  Feld). Reader mit kleinerem MINOR lesen das Bundle, ignorieren
  unbekannte Felder, geben aber eine Warnung mit Liste der ignorierten
  Felder.
- Kein PATCH-Segment; das Manifest ist klein genug, dass eine
  zweistufige Versionierung reicht.

### 8.2 Startversion

`formatVersion: "1.0"`. AP12 entscheidet, ob 0.x-Bundles ueberhaupt
zugelassen werden; dieser Sub-Doc empfiehlt: nein, der erste Schnitt
startet direkt mit 1.0 und reserviert 0.x fuer interne Vor-Releases.

### 8.3 Migration

- Eine Migration vorhandener Bundles (Reader liest 1.0 und schreibt
  1.1) ist nicht geplant. Manifest-Generationen sind durch den
  Export-Zeitpunkt voneinander getrennt; Reader liest, was im Bundle
  steht.
- Unbekannte Felder bei gleicher MAJOR-Version sind kein Fehler
  (siehe M11/§8.1).

---

## 9. Preflight-Vertrag

### 9.1 Ablauf

Der Preflight laeuft genau einmal vor dem Streaming-Import (kein
inkrementeller Modus). Reihenfolge der Pruefungen:

1. Bundle-Verzeichnis existiert, ist regulaer, lesbar.
2. `manifest.yaml` existiert, ist lesbar, parsebar (snakeyaml-engine
   ohne Anchors/Tags).
3. `formatVersion` ist semver-aehnlich und kompatibel zum Reader
   (§8.1).
4. `producer == "d-migrate"`, sonst Warnung (Fremd-Bundles werden
   nicht aktiv abgelehnt, aber markiert).
5. Pflichtfelder pro Tabelle vorhanden, `tables` nicht leer.
6. Kollisionsschutz K1-K5 (§6.2).
7. `schemaSource` ist eines der drei Enum-Werte.
8. Optionale SHA-256-Werte pruefen (§7.4).
9. Ergebnis: ein `ResolvedParquetBundle`-DTO mit Tabellenordnung,
   `Tabelle -> Pfad`-Bindings und (sofern vorhanden) Spaltenmetadaten
   pro Tabelle. AP9 definiert das DTO im Detail.

### 9.2 Fehlerklassen

Stabile Identifier fuer Diagnose und CLI-Mapping:

| Code | Bedeutung |
| ---- | --------- |
| `MANIFEST_NOT_FOUND` | Bundle-Verzeichnis hat keine `manifest.yaml` (M1, M13). |
| `MANIFEST_PARSE_ERROR` | YAML nicht parsebar oder enthaelt Anchors/Tags. |
| `MANIFEST_VERSION_INCOMPATIBLE` | `formatVersion` MAJOR > Reader-MAJOR. |
| `MANIFEST_FIELD_MISSING` | Pflichtfeld fehlt (Tabelle/Spalte/Top-Level). |
| `MANIFEST_FIELD_INVALID` | Pflichtfeld vorhanden, aber falscher Typ/Wertebereich. |
| `MANIFEST_TABLE_DUPLICATE` | Zwei Eintraege fuer dieselbe `table` (K1). |
| `MANIFEST_FILE_DUPLICATE` | Zwei Eintraege fuer dieselbe `file` (K2). |
| `MANIFEST_FILE_OUTSIDE_BUNDLE` | `file` zeigt aus dem Verzeichnis heraus (K3). |
| `MANIFEST_FILE_MISSING` | Referenzierte Datei existiert nicht oder ist nicht regulaer (K4). |
| `MANIFEST_FILE_UNREFERENCED` | `.parquet` im Bundle, nicht im Manifest (K5/M13). |
| `MANIFEST_SHA256_MISMATCH` | Berechneter Digest weicht von `sha256` ab (§7.4). |

CLI-Mapping (Wortlaut, Exit-Codes) ist Sache von AP12.

### 9.3 Streaming-Import-Verhalten nach Preflight

- Preflight liefert `ResolvedParquetBundle`. Der Streaming-Import
  iteriert ausschliesslich ueber dessen Tabellenordnung und
  Datei-Bindings.
- `SeekableDataChunkReaderFactory` wird pro Tabelle mit dem
  manifestseitig aufgeloesten `SeekableChunkSource.Local(path)` und
  dem `ChunkSchema` (aus den Manifest-Spaltenmetadaten gebaut)
  aufgerufen.
- Kein erneutes Verzeichnis-Listing waehrend des Streaming-Imports;
  damit ist Dateinamen-Inferenz strukturell ausgeschlossen (M17).

---

## 10. Konsequenzen fuer Code

### 10.1 `adapters:driven:formats-parquet`

Neuer Code im selben Modul (parquet-libraries.md §3.1 Empfehlung):

- `ParquetBundleManifest`-Datentyp (interne Sealed-Hierarchie fuer
  `NeutralType`-YAML, plus Top-Level-Record).
- `ParquetManifestWriter` — schreibt `manifest.yaml` aus
  `ChunkSchema`-Liste plus optionalen SHA-256-Werten. Nutzt
  `snakeyaml-engine` 2.7.
- `ParquetManifestReader` — parst `manifest.yaml`, validiert gegen
  Schema, liefert `ParquetBundleManifest` oder `ManifestError` (sealed).
- `ParquetBundlePreflight` — fuehrt §9.1-Schritte aus, kombiniert
  `ParquetManifestReader` mit Filesystem-Pruefungen und optionaler
  SHA-256-Verifikation. Liefert `ResolvedParquetBundle` oder
  `PreflightError` (sealed mit den Codes aus §9.2).

### 10.2 `hexagon:ports-read`

Aus `parquet-libraries.md` §7.1 ist `SeekableDataChunkReaderFactory`
bereits skizziert. AP9 legt fest, ob

- (a) `ImportInput.Directory` um ein optionales
  `parquetManifest: ResolvedParquetBundle?` erweitert wird, oder
- (b) ein neues `ResolvedParquetBundleInput` als eigener
  `ImportInput`-Subtyp eingefuehrt wird.

Dieser Sub-Doc bevorzugt (b), weil der Preflight-Vertrag dann
strukturell sichtbar wird (kein Magic-Field auf einer generischen
DTO). Die endgueltige Entscheidung gehoert aber zu AP9.

### 10.3 `hexagon:application`

- Der Import-Resolver erhaelt einen Pfad fuer Parquet-Directory-
  Bundles: `Pfad -> ParquetBundlePreflight -> ResolvedParquetBundle
  -> SeekableDataChunkReaderFactory`.
- Andere Formate (JSON/YAML/CSV) bleiben auf
  `DataChunkReaderFactory.create(InputStream, ...)`; es gibt keinen
  Cross-Format-Manifest-Vertrag.

### 10.4 `adapters:driving:cli`

- Neuer Format-Switch im `data import`-Resolver fuer Parquet-
  Directory-Bundles (AP12).
- Mapping von `PreflightError`-Codes (§9.2) auf CLI-Exit-Codes und
  Fehlertexte (AP12).
- Neues Flag `--manifest-sha256` fuer den Export-Pfad (AP12).

### 10.5 Keine Aenderung an JSON/YAML/CSV-Adaptern

Der Manifest-Vertrag ist Parquet-spezifisch. Bestehende
Format-Adapter und ihre Tests bleiben unberuehrt.

---

## 11. Offene Punkte fuer Folge-APs

- **AP8** (Directory-Aufloesung): Wie wird `tables[].file` aufgeloest,
  wenn das Bundle ueber Unterverzeichnisse organisiert ist
  (z.B. partitioniert)? Dieser Sub-Doc geht von einem flachen Bundle
  aus; partitionierte Bundles brauchen ein separates Konstrukt.
- **AP9** (Import-DTO): Wahl zwischen (a) Erweiterung von
  `ImportInput.Directory` und (b) neuem `ResolvedParquetBundleInput`-
  Subtyp. Dieser Sub-Doc empfiehlt (b); AP9 entscheidet bindend.
- **AP11** (Single-File-Metadaten): Single-File-Exporte werden nicht
  von diesem Sub-Doc abgedeckt. AP11 entscheidet zwischen
  Parquet-Footer-Key-Value-Metadaten und einem expliziten Sidecar
  `<datei>.manifest.yaml`. Falls Sidecar gewaehlt wird, sollte sein
  YAML-Schema strikt eine Teilmenge dieses Bundle-Manifests sein
  (gleiche Felder, ein einzelner `tables`-Eintrag).
- **AP12** (CLI- und Factory-Wiring): konkretes CLI-Flag fuer
  SHA-256, Exit-Code-Mapping, Format-Autodetection-Regel fuer
  `manifest.yaml`, Checkpoint-Fingerprint-Inhalt.
- **NeutralType-Repraesentation**: §5.4 listet die `kind`-Diskriminatoren
  beispielhaft. Die vollstaendige Liste plus YAML-Mapping pro Variante
  wird in AP12 finalisiert; bis dahin gilt §5.4 als Skelett.

---

## 12. Risiken

- Das Manifest waechst mit der Spaltenzahl linear; bei sehr breiten
  Tabellen (z.B. 500 Spalten) wird `manifest.yaml` im Megabyte-
  Bereich liegen. Das ist tolerierbar — der Reader liest es einmal —
  aber das CLI sollte fuer extrem grosse Manifeste eine Diagnose
  geben (z.B. „2.3 MB manifest, 47 Tabellen mit 12500 Spalten").
- Schema-qualifizierte Tabellen mit Punkten im Namen
  (z.B. `"public"."odd.name"`) erzeugen schnell ambivalente
  Dateinamen. Der Default-Producer sollte solche Tabellennamen
  bewusst eskapieren oder ablehnen; die Default-Konvention
  `<schema>.<table>.parquet` aus §6.1 schuetzt nur gegen den
  haeufigen Fall.
- SHA-256-Verifikation kostet einen kompletten Datei-Read vor dem
  Streaming-Import; bei grossen Bundles ist das nicht
  vernachlaessigbar. `--manifest-sha256` muss bewusst aktiviert
  werden; der Default ist „nicht pruefen" (vgl. §7.1).
- Eine Aenderung an der `NeutralType`-Sealed-Hierarchie verlangt
  einen MAJOR-Bump des `formatVersion`, weil das `kind`-Vokabular
  Teil des stabilen YAML-Vertrags ist. AP12 muss den Migrationspfad
  fuer kuenftige `NeutralType`-Erweiterungen explizit dokumentieren.
- Reader gegen Producer mit groesserem MAJOR sehen das Bundle als
  inkompatibel und schlagen fehl. Das ist gewollt, fuehrt aber zu
  einer harten Lock-in-Wirkung pro Major-Version; der Producer
  sollte daher Major-Bumps konservativ einsetzen.
