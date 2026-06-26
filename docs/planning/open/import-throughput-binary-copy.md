# Import-Durchsatz: COPY-Pfad auf weitere Typen ausweiten (binär/EWKB/COPY-Text)

> Status: **Draft** (Trigger Watch)
>
> Trigger: Der COPY-Bulk-Fast-Path
> ([`import-throughput-copy-path.md`](../done/import-throughput-copy-path.md), 2026-06-25)
> ist bewusst **konservativ** geschnitten: COPY-TEXT-Format + eine Allowlist
> eindeutig text-sicherer **Skalartypen** (int/decimal/float/bool/char/varchar/
> date/time/timestamp). Geometrie (SQL-Wrap), Enum, Array, json/jsonb, interval,
> xml und bytea fallen darum auf den (mit `reWriteBatchedInserts` ohnehin
> schnelleren) Batch-INSERT zurück — korrekt, aber nicht COPY-schnell.
>
> Aktivierungsbedingung: sobald ein Workload mit hohem Anteil an genau diesen
> Typen (insb. **geometrie-lastig** oder json/array-lastig) den COPY-Durchsatz
> als Ziel hat. Dann mit ausgearbeitetem Scope nach `../next/`.
>
> Status-Update 2026-06-26: **Design-Spike erledigt** (siehe Abschnitt unten) — der
> Implementierungspfad pro Typ ist am Code geklärt (Tier A Text-Skalare, Tier B Geometrie
> als EWKB-Hex, Tier C array/bytea), Text-COPY bestätigt. Es fehlt nur der Typ-Workload-
> Trigger; dann sind Tier A/B scope-reif.

---

## Worum es geht

COPY ist kein reines Skalar-Protokoll: die heute ausgeschlossenen Typen haben
sehr wohl COPY-darstellbare Repräsentationen.

- **Geometrie.** Der *einzige* echte COPY-Blocker im Import ist das
  **SQL-Funktions-Wrapping** `ST_GeomFromWKB(?, srid[, axis-order])` in
  `AbstractTableImportSession.valuePlaceholder` — COPY kann keine Per-Wert-
  SQL-Ausdrücke ausführen. Als **EWKB-Hex** (inkl. SRID) ginge die Geometrie
  aber direkt in COPY, ganz ohne Funktionsaufruf. Damit entfiele der Wrap-Zwang,
  der den INSERT-Rückfall für Spatial-Tabellen erzwingt.
- **json/jsonb, Array, Enum, interval, xml.** Diese werden heute nicht über
  `valuePlaceholder`, sondern in `bindValue` per `PGobject`/`setObject`/`setArray`
  gebunden — sie haben Text-/Binär-Repräsentationen und sind in COPY (Text- oder
  Binär-Format) darstellbar; sie sind also keine *prinzipielle* Sperre, nur
  fiddly im Encoder.

## Erweiterungs-Skizze (zu entscheiden)

1. Die Allowlist `COPY_TEXT_SAFE_JDBC_TYPES` und den Encoder `PostgresCopyText`
   um Typen mit eindeutiger COPY-Repräsentation erweitern (json/array als
   COPY-Text, Geometrie als EWKB-Hex inkl. SRID), das `isEligible`-Gate
   entsprechend lockern.
2. Pro Typ die Verlustfreiheit weiter hart über den kanonischen 4c-SHA-256
   absichern (`make sample-db-tpch-perf` + die Spatial-Harness für Geometrie).
3. Abwägen: Text- vs. Binär-COPY. Binär ist kompakter/schneller, aber
   fehleranfälliger im Encoder; der heutige Pfad nutzt bewusst Text wegen des
   eindeutigen `\N`-NULL-Markers und der kanonischen Text-Repräsentation.

## Warum kein eigener Slice (jetzt)

Die konservative Skalar-Allowlist deckt den **häufigen Fall** ab (TPC-H und
ähnliche reine Skalar-Workloads → COPY greift für alle Tabellen). Für den Rest
ist der INSERT-Rückfall korrekt und verlustfrei; der Mehrwert dieser Erweiterung
ist reiner Durchsatz für *spezielle* Typ-Profile und damit nicht LF-blockierend.

## Verwandte Tracker

- Quelle und Closure-Kontext:
  [`import-throughput-copy-path.md`](../done/import-throughput-copy-path.md)
  (COPY-Bulk-Pfad + Closure-Abschnitt zur konservativen Allowlist).
- Komplementäre, unabhängige Achse (mehr Streams statt mehr Typen):
  [`import-throughput-parallel.md`](../next/import-throughput-parallel.md).
- Geometrie-Treue als eigene Anforderung: die Spatial-Harness
  (`make sample-db-spatial-smoke`).

---

## Design-Spike (2026-06-26): Implementierungspfad + Verlustfreiheit pro Typ

> Kein Code. Klärt am echten COPY-Fast-Path-Code, welche heute ausgeschlossenen Typen wie
> über COPY gehen, wie pro Typ die Verlustfreiheit abgesichert wird, und Text- vs. Binär-COPY.
> Befunde direkt verifiziert.

### Ist-Stand (drei Bausteine)

- **Gate** `PostgresCopyFastPath.isEligible`: lässt einen Chunk nur zu, wenn **keine** Spalte
  Geometrie/Enum ist **und** jeder `jdbcType` in `COPY_TEXT_SAFE_JDBC_TYPES` liegt (int/decimal/
  float/bool/char/varchar/date/time/timestamp). Sonst Rückfall auf Batch-INSERT.
- **Encoder** `PostgresCopyText`: COPY-TEXT (TAB-getrennt, `\n`-terminiert, `\N`=NULL, Escaping
  `\`/tab/nl/cr). `field()` verarbeitet `null`/`String`/`BigDecimal`/`Boolean` und **sonst
  `escape(value.toString())`** — beliebige kanonische Text-Repräsentation geht also bereits durch.
- **INSERT-Bindung** `PostgresTableImportSession.bindValue` zeigt, wie die heute Ausgeschlossenen
  gebunden werden: Geometrie `setBytes(WKB)` + `ST_GeomFromWKB(?, srid)`-Wrap; json/jsonb/interval/
  xml/enum als `pgObject(typeName, value.toString())` (also bereits Text!); array via `setArray`.

### Befund pro Typ (was COPY braucht)

| Typ | COPY-Repräsentation | Encoder-Arbeit | Aufwand |
|---|---|---|---|
| **json/jsonb** | Wert ist bereits JSON-**Text** → als COPY-TEXT-Feld; PG parst Text→json(b) | keine (bestehender `else`-Zweig + Escaping) | **klein** |
| **enum (benannt)** | Label ist ein String; COPY-TEXT casts Text→enum. Nur das `isEnum`-Gate streichen | keine | **klein** |
| **uuid / interval / xml** | kanonischer Text (`toString()`); xml-Sonderzeichen escaped der Encoder | keine | **klein** |
| **Geometrie** | **EWKB-Hex** (trägt SRID inline) direkt in die geometry-Spalte — **ohne** `ST_GeomFromWKB`-Funktion → der einzige echte COPY-Blocker entfällt | byte[] WKB+SRID → EWKB-Hex assemblieren (Endianness, SRID-Flag 0x20000000) | **mittel** |
| **array** | PG-Array-Literal `{…}` mit Element-Quoting/-Escaping/NULL | eigener Array-Literal-Encoder (verschachtelt, fiddly) | **mittel** |
| **bytea** | `\x<hex>` im COPY-TEXT (Wechselwirkung mit COPY-Backslash-Escaping) | hex + `\x`-Marker, escaping-bewusst | **mittel** |

### Antworten auf die offenen Fragen

- **Geometrie (der Hebel):** Der Wrap-Zwang `ST_GeomFromWKB(?, srid)` (`valuePlaceholder`) ist die
  *einzige* prinzipielle COPY-Sperre für Spatial. EWKB-Hex umgeht ihn (PostGIS-Geometrie-Input
  akzeptiert EWKB-Hex direkt). **Caveat (code-belegt):** EWKB ist **PG-only** — MySQL versteht das
  EWKB-SRID-Flag nicht (`PostgresDataReader`-Kommentar). Der EWKB-COPY-Pfad ist also **PG-Ziel-
  spezifisch**; die SRID liegt pro Zielspalte vor (`TargetColumn.srid`), der Importer kann EWKB-Hex
  aus WKB+SRID zusammensetzen.
- **Text vs. Binär-COPY:** Alle Kandidaten haben eine **Text**-Repräsentation → COPY-TEXT bleibt der
  pragmatische Pfad (eindeutiger `\N`-NULL-Marker, kanonische Repr, geringes Encoder-Risiko).
  **Binär-COPY** ist kompakter, aber pro Typ binär-fehleranfällig (Layout/Längen/OIDs) und eine
  **orthogonale** Optimierung — nicht Teil dieser Typ-Ausweitung.
- **Verlustfreiheit:** pro Typ hart über den kanonischen 4c-SHA-256 (`make sample-db-tpch-perf` bzw.
  dedizierte Fixtures) + die Spatial-Harness (`make sample-db-spatial-smoke`) für Geometrie.

### Gestufter Slice-Zuschnitt

1. **Tier A — Text-Skalare (klein, hohe Sicherheit):** json/jsonb, enum (`isEnum`-Gate streichen),
   uuid, interval, xml. `COPY_TEXT_SAFE_JDBC_TYPES`/`isEligible` lockern; der `else`-Encoderzweig
   trägt sie bereits. Pro Typ 4c-SHA-Fixture.
2. **Tier B — Geometrie als EWKB-Hex:** der eigentliche Durchsatz-Win für Spatial-Workloads.
   PG-Ziel-spezifisch; EWKB-Hex aus WKB+SRID. Spatial-Harness als Abnahme.
3. **Tier C — array + bytea (fiddly):** eigener Array-Literal- bzw. `\x`-hex-Encoder, höheres
   Encoder-Risiko. Optionaler Folge-Sub-Slice.

Damit ist der Implementierungspfad pro Typ geklärt; Tier A/B sind scope-reif, sobald ein
geometrie-/json-lastiger Workload den Trigger setzt.
