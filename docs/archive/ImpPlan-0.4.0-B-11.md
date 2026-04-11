# Implementierungsplan: Phase B – Schritt 11 – Golden-Master-Reader-Tests (Round-Trip)

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: B (Format-Reader)
> **Schritt**: 11
> **Status**: Umgesetzt und per Docker verifiziert
> **Referenz**: `implementation-plan-0.4.0.md` §3.5.1 (H3), §4 Phase B Schritt 11

---

## 1. Ziel

Round-Trip-Validierung aller drei Format-Pfade (JSON, YAML, CSV):
**Writer → Reader → Vergleich**. Damit wird sichergestellt, dass die
Phase-B-Reader den Output der 0.3.0-Writer (`JsonChunkWriter`,
`YamlChunkWriter`, `CsvChunkWriter`) korrekt zurücklesen können.

Zusätzlich: **H3-Property-Test** — explizite Absicherung, dass jeder
Writer bei einer Row mit ausschließlich `null`-Werten alle in
`begin(...)` übergebenen Spalten als Schlüssel im Output materialisiert.
Ohne diese Eigenschaft kippt die First-Row-Schema-Garantie aus §3.5.1,
weil der Reader das autoritative Feldset der ersten Row als Spalten-
Schema verwendet.

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `JsonChunkWriter` | 0.3.0 Phase D | ✅ Vorhanden |
| `YamlChunkWriter` | 0.3.0 Phase D | ✅ Vorhanden |
| `CsvChunkWriter` | 0.3.0 Phase D | ✅ Vorhanden |
| `JsonChunkReader` | Phase B Schritt 7 | ✅ Umgesetzt |
| `YamlChunkReader` | Phase B Schritt 8 | ✅ Umgesetzt |
| `CsvChunkReader` | Phase B Schritt 9 | ✅ Umgesetzt |
| `DataChunk`, `ColumnDescriptor` | 0.3.0 core | ✅ Vorhanden |
| `ExportOptions`, `ImportOptions` | 0.3.0 / Phase A | ✅ Vorhanden |

---

## 3. Betroffene Dateien

### 3.1 Neue Dateien

| Datei | Zweck |
|---|---|
| `d-migrate-formats/src/test/kotlin/dev/dmigrate/format/data/GoldenMasterRoundTripTest.kt` | Round-Trip-Tests (Write → Read → Vergleich) pro Format |
| `d-migrate-formats/src/test/kotlin/dev/dmigrate/format/data/H3NullRowPropertyTest.kt` | H3-Property-Test: All-Null-Row materialisiert alle Spalten |

### 3.2 Bestehende Dateien (keine Änderungen)

Alle Writer- und Reader-Implementierungen, `DataChunk`, `ColumnDescriptor`,
`ExportOptions`, `ImportOptions` werden unverändert konsumiert. Es handelt
sich um reine Test-Ergänzungen — kein Produktionscode wird geändert.

---

## 4. Design

### 4.1 Round-Trip-Mechanik

```
┌─────────────┐      bytes       ┌─────────────┐      DataChunk
│ ChunkWriter │  ──────────────► │ ChunkReader │  ──────────────► Vergleich
│ begin/write │  ByteArrayOutput │ nextChunk() │                  mit Original
│     /end    │      Stream      │             │
└─────────────┘                  └─────────────┘
```

1. **Schreiben**: `DataChunk` mit bekannten Werten wird über den
   Writer (`begin` → `write` → `end`) in einen `ByteArrayOutputStream`
   serialisiert.
2. **Lesen**: Der Output wird als `ByteArrayInputStream` an den
   entsprechenden Reader übergeben. `nextChunk()` liefert die
   gelesenen `DataChunk`s zurück.
3. **Vergleich**: Die gelesenen Chunks werden mit den Originaldaten
   verglichen — **typbewusst**, da die Reader format-native Typen
   zurückliefern (s. §4.2).

### 4.2 Typbewusster Vergleich

Die Writer serialisieren über `ValueSerializer`, die Reader parsen
format-nativ. Dabei treten erwartbare Typ-Transformationen auf:

| Original-Typ | JSON-Reader | YAML-Reader | CSV-Reader |
|---|---|---|---|
| `Int` (1) | `Long` (1L) | `Int` (1) | `String` ("1") |
| `Long` (1L) | `Long` (1L) | `Long`/`Int` | `String` ("1") |
| `Boolean` (true) | `Boolean` | `Boolean` | `String` ("true") |
| `String` ("abc") | `String` | `String` | `String` ("abc") |
| `null` | `null` | `null` | `null` (via csvNullString) |
| `Double` (1.5) | `Double` | `Double` | `String` ("1.5") |
| `BigDecimal` | `String`¹ | `String`¹ | `String` |
| `BigInteger` | `Long`/`BigInteger`² | `BigInteger`/`Long` | `String` |

¹ BigDecimal wird vom Writer als String serialisiert (Precision-Schutz, §6.4.1).
² BigInteger: wenn im Long-Range → Long, sonst BigInteger.

Die Tests verwenden deshalb **keine naive `shouldBe`-Gleichheit** auf
den Rohwerten, sondern eine Hilfs-Funktion `assertRowsEquivalent()`, die:

- `null`-Werte exakt vergleicht
- Numerische Werte über `toBigDecimal()`-Normalisierung vergleicht
- `Boolean`-Werte exakt vergleicht
- `String`-Werte exakt vergleicht

Für **CSV** ist die Vergleichsstrategie anders: alle Werte kommen als
`String` zurück. Die Tests verwenden deshalb eine eigene Funktion
`assertCsvRowsEquivalent()` mit inline definierten
`Array<String?>`-Erwartungswerten pro Fixture.

### 4.3 Zwei Test-Klassen — klare Trennung

| Klasse | Zweck | Beweisziel |
|---|---|---|
| `GoldenMasterRoundTripTest` | Write → Read → Vergleich | Reader liest Writer-Output korrekt |
| `H3NullRowPropertyTest` | Writer-Output-Inspektion | All-Null-Row materialisiert alle Spalten (§3.5.1 H3) |

Die Trennung ist bewusst: Der H3-Test prüft den **Writer-Output**
(nicht den Reader), während der Round-Trip den **Reader** prüft. Beide
Tests zusammen sichern die Round-Trip-Kette End-to-End ab.

### 4.4 H3-Prüfstrategie: strukturelle Analyse statt Roh-String-Matching

Die H3-Tests prüfen den Writer-Output **strukturell**, nicht über
fragile Roh-String-Vergleiche. Damit brechen sie nicht bei harmlosen
Formatter-Änderungen (Spacing, Zeilenumbrüche):

| Format | Prüfmechanik |
|---|---|
| JSON | Regex-basierte Key-Extraktion: `"(\w+)"\s*:` pro Objekt-Zeile → `Set<String>` → Vergleich mit erwarteten Column-Namen. Unabhängig von Spacing nach Doppelpunkt und konkreter Wert-Darstellung. |
| YAML | SnakeYAML Engine `Load`-API: parst Output zu `List<Map<String, Any?>>` → `map.keys` pro Row → Vergleich mit erwarteten Column-Namen. Komplett formatierungsunabhängig. |
| CSV | Feld-Splitting: Header-Zeile → Column-Namen; Data-Zeilen → Feld-Anzahl == Header-Feld-Anzahl. Bereits inhärent strukturell. |

### 4.5 Test-Daten-Fixtures (inline)

Konsistent mit den bestehenden Writer-Tests (vgl. `JsonChunkWriterTest`,
`YamlChunkWriterTest`, `CsvChunkWriterTest`) werden alle Fixtures
inline im Testcode definiert. Keine externen Fixture-Dateien.

#### Gemeinsame Spalten-Definition

```kotlin
val columns = listOf(
    ColumnDescriptor("id", nullable = false),
    ColumnDescriptor("name", nullable = true),
    ColumnDescriptor("active", nullable = true),
    ColumnDescriptor("score", nullable = true),
)
```

#### Fixture-Varianten

| Fixture-Name | Rows | Zweck |
|---|---|---|
| `singleRow` | `[1, "alice", true, 9.5]` | Minimaler Smoke-Test |
| `multiRow` | 3 Rows mit gemischten Typen | Standard-Round-Trip |
| `withNulls` | `[1, null, null, null]` | Partielle Nulls |
| `allNullRow` | `[null, null, null, null]` | H3-Grenzfall (isoliert) |
| `allNullFirstThenData` | `[null,null,null,null]` + `[1,"x",true,2.5]` | H3-Regression: All-Null als erste Row, Datenzeile danach |
| `precisionRow` | `[BigDecimal("12345.6789"), "x", true, BigInteger("9000000000000000000")]` | Präzisionsschutz (§6.4.1). BigInteger im Long-Range, weil YAML Core Schema größere Integers als Double auflöst (Precision-Verlust). |
| `emptyTable` | `emptyList()` | §6.17 leere Tabelle |
| `multiChunk` | 5 Rows mit chunkSize=2 | Chunk-Boundary-Verifikation (Werte + Größen) |

---

## 5. Implementierung

### 5.1 `GoldenMasterRoundTripTest.kt`

```kotlin
package dev.dmigrate.format.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.csv.CsvChunkReader
import dev.dmigrate.format.data.csv.CsvChunkWriter
import dev.dmigrate.format.data.json.JsonChunkReader
import dev.dmigrate.format.data.json.JsonChunkWriter
import dev.dmigrate.format.data.yaml.YamlChunkReader
import dev.dmigrate.format.data.yaml.YamlChunkWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger

class GoldenMasterRoundTripTest : FunSpec({

    val table = "round_trip"
    val chunkSize = 100

    val columns = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = true),
        ColumnDescriptor("active", nullable = true),
        ColumnDescriptor("score", nullable = true),
    )

    // ─── Helper ─────────────────────────────────────────────────

    fun writeJson(chunks: List<DataChunk>): ByteArray {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin(table, columns)
            chunks.forEach { w.write(it) }
            w.end()
        }
        return out.toByteArray()
    }

    fun writeYaml(chunks: List<DataChunk>): ByteArray {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).use { w ->
            w.begin(table, columns)
            chunks.forEach { w.write(it) }
            w.end()
        }
        return out.toByteArray()
    }

    fun writeCsv(
        chunks: List<DataChunk>,
        options: ExportOptions = ExportOptions(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out, options).use { w ->
            w.begin(table, columns)
            chunks.forEach { w.write(it) }
            w.end()
        }
        return out.toByteArray()
    }

    fun readAllChunks(reader: DataChunkReader): List<DataChunk> {
        val result = mutableListOf<DataChunk>()
        while (true) {
            val chunk = reader.nextChunk() ?: break
            result.add(chunk)
        }
        return result
    }

    /**
     * Typbewusster Vergleich: normalisiert numerische Werte über
     * BigDecimal, damit Int/Long/Double-Unterschiede zwischen
     * Original und Reader-Output nicht zu false-negatives führen.
     */
    fun assertValueEquivalent(expected: Any?, actual: Any?, context: String) {
        when {
            expected == null -> actual.shouldBeNull()
            expected is Number && actual is Number ->
                BigDecimal(expected.toString()) shouldBe BigDecimal(actual.toString())
            expected is Boolean -> actual shouldBe expected
            else -> actual.toString() shouldBe expected.toString()
        }
    }

    fun assertRowsEquivalent(
        expectedRows: List<Array<Any?>>,
        actualChunks: List<DataChunk>,
    ) {
        val actualRows = actualChunks.flatMap { it.rows }
        actualRows.size shouldBe expectedRows.size
        for (i in expectedRows.indices) {
            val exp = expectedRows[i]
            val act = actualRows[i]
            act.size shouldBe exp.size
            for (j in exp.indices) {
                assertValueEquivalent(exp[j], act[j], "row[$i]col[$j]")
            }
        }
    }

    fun assertCsvRowsEquivalent(
        expectedStringRows: List<Array<String?>>,
        actualChunks: List<DataChunk>,
    ) {
        val actualRows = actualChunks.flatMap { it.rows }
        actualRows.size shouldBe expectedStringRows.size
        for (i in expectedStringRows.indices) {
            val exp = expectedStringRows[i]
            val act = actualRows[i]
            act.size shouldBe exp.size
            for (j in exp.indices) {
                if (exp[j] == null) {
                    act[j].shouldBeNull()
                } else {
                    act[j].toString() shouldBe exp[j]
                }
            }
        }
    }

    // ─── Fixtures ───────────────────────────────────────────────

    val singleRow = listOf(arrayOf<Any?>(1, "alice", true, 9.5))
    val multiRow = listOf(
        arrayOf<Any?>(1, "alice", true, 9.5),
        arrayOf<Any?>(2, "bob", false, 7.0),
        arrayOf<Any?>(3, "charlie", true, 8.25),
    )
    val withNulls = listOf(arrayOf<Any?>(1, null, null, null))
    val allNullRow = listOf(arrayOf<Any?>(null, null, null, null))
    val allNullFirstThenData = listOf(
        arrayOf<Any?>(null, null, null, null),
        arrayOf<Any?>(1, "x", true, 2.5),
    )
    val precisionRow = listOf(
        arrayOf<Any?>(BigDecimal("12345.6789"), "x", true, BigInteger("9000000000000000000")),
    )

    fun chunk(rows: List<Array<Any?>>, index: Long = 0) =
        DataChunk(table, columns, rows, index)

    // ═══════════════════════════════════════════════════════════
    // JSON Round-Trip
    // ═══════════════════════════════════════════════════════════

    test("JSON round-trip: single row") {
        val bytes = writeJson(listOf(chunk(singleRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(singleRow, readAllChunks(reader))
        }
    }

    test("JSON round-trip: multiple rows") {
        val bytes = writeJson(listOf(chunk(multiRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(multiRow, readAllChunks(reader))
        }
    }

    test("JSON round-trip: null values") {
        val bytes = writeJson(listOf(chunk(withNulls)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(withNulls, readAllChunks(reader))
        }
    }

    test("JSON round-trip: all-null row (H3)") {
        val bytes = writeJson(listOf(chunk(allNullRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.flatMap { it.rows }.size shouldBe 1
            val row = chunks.first().rows.first()
            row.size shouldBe columns.size
            row.forEach { it.shouldBeNull() }
            chunks.first().columns.map { it.name } shouldBe columns.map { it.name }
        }
    }

    test("JSON round-trip: all-null first row + data row (H3 regression)") {
        val bytes = writeJson(listOf(chunk(allNullFirstThenData)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            // Reader muss trotz All-Null-First-Row das vollständige
            // Spaltenset ableiten und die zweite Row korrekt lesen.
            chunks.first().columns.map { it.name } shouldBe columns.map { it.name }
            assertRowsEquivalent(allNullFirstThenData, chunks)
        }
    }

    test("JSON round-trip: empty table") {
        val bytes = writeJson(listOf(chunk(emptyList())))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.nextChunk().shouldBeNull()
        }
    }

    test("JSON round-trip: multi-chunk with chunkSize=2") {
        val rows5 = (1..5).map { arrayOf<Any?>(it, "name$it", it % 2 == 0, it * 1.1) }
        val bytes = writeJson(listOf(chunk(rows5)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, 2).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.map { it.rows.size } shouldBe listOf(2, 2, 1)
            chunks.forEach { c ->
                c.columns.map { it.name } shouldBe columns.map { it.name }
            }
            assertRowsEquivalent(rows5, chunks)
        }
    }

    test("JSON round-trip: headerColumns matches column names") {
        val bytes = writeJson(listOf(chunk(singleRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.nextChunk()
            reader.headerColumns() shouldBe columns.map { it.name }
        }
    }

    test("JSON round-trip: column order preserved") {
        val bytes = writeJson(listOf(chunk(singleRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val c = reader.nextChunk()!!
            c.columns.map { it.name } shouldBe listOf("id", "name", "active", "score")
        }
    }

    test("JSON round-trip: BigDecimal/BigInteger precision preserved") {
        val bytes = writeJson(listOf(chunk(precisionRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            assertRowsEquivalent(precisionRow, chunks)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // YAML Round-Trip
    // ═══════════════════════════════════════════════════════════

    test("YAML round-trip: single row") {
        val bytes = writeYaml(listOf(chunk(singleRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(singleRow, readAllChunks(reader))
        }
    }

    test("YAML round-trip: multiple rows") {
        val bytes = writeYaml(listOf(chunk(multiRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(multiRow, readAllChunks(reader))
        }
    }

    test("YAML round-trip: null values") {
        val bytes = writeYaml(listOf(chunk(withNulls)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(withNulls, readAllChunks(reader))
        }
    }

    test("YAML round-trip: all-null row (H3)") {
        val bytes = writeYaml(listOf(chunk(allNullRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.flatMap { it.rows }.size shouldBe 1
            val row = chunks.first().rows.first()
            row.size shouldBe columns.size
            row.forEach { it.shouldBeNull() }
            chunks.first().columns.map { it.name } shouldBe columns.map { it.name }
        }
    }

    test("YAML round-trip: all-null first row + data row (H3 regression)") {
        val bytes = writeYaml(listOf(chunk(allNullFirstThenData)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.first().columns.map { it.name } shouldBe columns.map { it.name }
            assertRowsEquivalent(allNullFirstThenData, chunks)
        }
    }

    test("YAML round-trip: empty table") {
        val bytes = writeYaml(listOf(chunk(emptyList())))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.nextChunk().shouldBeNull()
        }
    }

    test("YAML round-trip: multi-chunk with chunkSize=2") {
        val rows5 = (1..5).map { arrayOf<Any?>(it, "name$it", it % 2 == 0, it * 1.1) }
        val bytes = writeYaml(listOf(chunk(rows5)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, 2).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.map { it.rows.size } shouldBe listOf(2, 2, 1)
            chunks.forEach { c ->
                c.columns.map { it.name } shouldBe columns.map { it.name }
            }
            assertRowsEquivalent(rows5, chunks)
        }
    }

    test("YAML round-trip: headerColumns matches column names") {
        val bytes = writeYaml(listOf(chunk(singleRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.nextChunk()
            reader.headerColumns() shouldBe columns.map { it.name }
        }
    }

    test("YAML round-trip: column order preserved") {
        val bytes = writeYaml(listOf(chunk(singleRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val c = reader.nextChunk()!!
            c.columns.map { it.name } shouldBe listOf("id", "name", "active", "score")
        }
    }

    test("YAML round-trip: BigDecimal/BigInteger precision preserved") {
        val bytes = writeYaml(listOf(chunk(precisionRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            assertRowsEquivalent(precisionRow, chunks)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CSV Round-Trip
    // ═══════════════════════════════════════════════════════════

    test("CSV round-trip: single row") {
        val bytes = writeCsv(listOf(chunk(singleRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertCsvRowsEquivalent(
                listOf(arrayOf("1", "alice", "true", "9.5")),
                readAllChunks(reader),
            )
        }
    }

    test("CSV round-trip: multiple rows") {
        val bytes = writeCsv(listOf(chunk(multiRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertCsvRowsEquivalent(
                listOf(
                    arrayOf("1", "alice", "true", "9.5"),
                    arrayOf("2", "bob", "false", "7.0"),
                    arrayOf("3", "charlie", "true", "8.25"),
                ),
                readAllChunks(reader),
            )
        }
    }

    test("CSV round-trip: null values with csvNullString") {
        val nullString = "NULL"
        val bytes = writeCsv(
            listOf(chunk(withNulls)),
            ExportOptions(csvNullString = nullString),
        )
        CsvChunkReader(
            ByteArrayInputStream(bytes), table, chunkSize,
            ImportOptions(csvNullString = nullString),
        ).use { reader ->
            assertCsvRowsEquivalent(
                listOf(arrayOf("1", null, null, null)),
                readAllChunks(reader),
            )
        }
    }

    test("CSV round-trip: all-null row (H3)") {
        val nullString = "NULL"
        val bytes = writeCsv(
            listOf(chunk(allNullRow)),
            ExportOptions(csvNullString = nullString),
        )
        CsvChunkReader(
            ByteArrayInputStream(bytes), table, chunkSize,
            ImportOptions(csvNullString = nullString),
        ).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.flatMap { it.rows }.size shouldBe 1
            val row = chunks.first().rows.first()
            row.size shouldBe columns.size
            row.forEach { it.shouldBeNull() }
        }
    }

    test("CSV round-trip: all-null first row + data row (H3 regression)") {
        val nullString = "NULL"
        val bytes = writeCsv(
            listOf(chunk(allNullFirstThenData)),
            ExportOptions(csvNullString = nullString),
        )
        CsvChunkReader(
            ByteArrayInputStream(bytes), table, chunkSize,
            ImportOptions(csvNullString = nullString),
        ).use { reader ->
            val chunks = readAllChunks(reader)
            // Erste Row all-null, zweite Row Daten — beides korrekt gelesen
            val rows = chunks.flatMap { it.rows }
            rows.size shouldBe 2
            rows[0].forEach { it.shouldBeNull() }
            rows[1][0].toString() shouldBe "1"
            reader.headerColumns() shouldBe columns.map { it.name }
        }
    }

    test("CSV round-trip: empty table (header only)") {
        val bytes = writeCsv(listOf(chunk(emptyList())))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.headerColumns() shouldBe columns.map { it.name }
            reader.nextChunk().shouldBeNull()
        }
    }

    test("CSV round-trip: multi-chunk with chunkSize=2") {
        val rows5 = (1..5).map { arrayOf<Any?>(it, "name$it", it % 2 == 0, it * 1.1) }
        val bytes = writeCsv(listOf(chunk(rows5)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, 2).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.map { it.rows.size } shouldBe listOf(2, 2, 1)
            chunks.forEach { c ->
                c.columns.map { it.name } shouldBe columns.map { it.name }
            }
            assertCsvRowsEquivalent(
                rows5.map { row -> row.map { it?.toString() }.toTypedArray() },
                chunks,
            )
        }
    }

    test("CSV round-trip: headerColumns matches column names") {
        val bytes = writeCsv(listOf(chunk(singleRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.headerColumns() shouldBe columns.map { it.name }
        }
    }

    test("CSV round-trip: column order preserved") {
        val bytes = writeCsv(listOf(chunk(singleRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val c = reader.nextChunk()!!
            c.columns.map { it.name } shouldBe listOf("id", "name", "active", "score")
        }
    }

    test("CSV round-trip: BigDecimal/BigInteger as string representation") {
        val bytes = writeCsv(listOf(chunk(precisionRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertCsvRowsEquivalent(
                listOf(arrayOf("12345.6789", "x", "true", "9000000000000000000")),
                readAllChunks(reader),
            )
        }
    }
})
```

### 5.2 `H3NullRowPropertyTest.kt`

```kotlin
package dev.dmigrate.format.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.csv.CsvChunkWriter
import dev.dmigrate.format.data.json.JsonChunkWriter
import dev.dmigrate.format.data.yaml.YamlChunkWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * H3-Property-Test (§3.5.1): Jeder Writer MUSS bei einer Row mit
 * ausschließlich null-Werten alle in begin(...) übergebenen Spalten
 * als Schlüssel im Output materialisieren.
 *
 * Wenn diese Eigenschaft verletzt wird, kann der Reader bei einem
 * Round-Trip das autoritative Feldset der ersten Row nicht korrekt
 * ableiten, und die First-Row-Schema-Garantie bricht.
 *
 * Die Prüfung erfolgt strukturell (JSON: Regex-Key-Extraktion,
 * YAML: SnakeYAML Load-API, CSV: Feld-Splitting), nicht über
 * fragile Roh-String-Vergleiche.
 */
class H3NullRowPropertyTest : FunSpec({

    val table = "h3_test"

    val columns = listOf(
        ColumnDescriptor("alpha", nullable = true),
        ColumnDescriptor("beta", nullable = true),
        ColumnDescriptor("gamma", nullable = true),
        ColumnDescriptor("delta", nullable = true),
    )

    val expectedColumnNames = columns.map { it.name }

    val allNullRow = DataChunk(
        table, columns,
        listOf(arrayOf<Any?>(null, null, null, null)),
        chunkIndex = 0,
    )

    val allNullFirstThenData = DataChunk(
        table, columns,
        listOf(
            arrayOf<Any?>(null, null, null, null),
            arrayOf<Any?>(1, "x", true, 2.5),
        ),
        chunkIndex = 0,
    )

    // ─── Helper ─────────────────────────────────────────────────

    fun writeJson(chunk: DataChunk): String {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin(table, columns)
            w.write(chunk)
            w.end()
        }
        return out.toString(Charsets.UTF_8)
    }

    fun writeYaml(chunk: DataChunk): String {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).use { w ->
            w.begin(table, columns)
            w.write(chunk)
            w.end()
        }
        return out.toString(Charsets.UTF_8)
    }

    fun writeCsv(chunk: DataChunk): String {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out).use { w ->
            w.begin(table, columns)
            w.write(chunk)
            w.end()
        }
        return out.toString(Charsets.UTF_8)
    }

    /** Extrahiert JSON-Schlüsselnamen aus einer Objekt-Zeile. */
    fun extractJsonKeys(jsonObjectLine: String): Set<String> =
        Regex(""""(\w+)"\s*:""").findAll(jsonObjectLine)
            .map { it.groupValues[1] }
            .toSet()

    /** Parst YAML-Output zu einer Liste von Maps via SnakeYAML Load-API. */
    @Suppress("UNCHECKED_CAST")
    fun parseYamlRows(yamlOutput: String): List<Map<String, Any?>> {
        val load = Load(LoadSettings.builder().build())
        val parsed = load.loadFromInputStream(
            ByteArrayInputStream(yamlOutput.toByteArray(Charsets.UTF_8)),
        ) as List<*>
        return parsed.map { it as Map<String, Any?> }
    }

    // ═══════════════════════════════════════════════════════════
    // H3 — JSON: all-null row materializes all column keys
    // ═══════════════════════════════════════════════════════════

    test("H3 JSON: all-null row contains all column keys") {
        val output = writeJson(allNullRow)
        val objectLines = output.lines().filter { it.trim().startsWith("{") }
        objectLines.size shouldBe 1
        extractJsonKeys(objectLines[0]) shouldContainExactlyInAnyOrder expectedColumnNames
    }

    test("H3 JSON: all-null first row + data row — each row has all keys") {
        val output = writeJson(allNullFirstThenData)
        val objectLines = output.lines().filter { it.trim().startsWith("{") }
        objectLines.size shouldBe 2
        for ((i, line) in objectLines.withIndex()) {
            extractJsonKeys(line) shouldContainExactlyInAnyOrder expectedColumnNames
        }
    }

    // ═══════════════════════════════════════════════════════════
    // H3 — YAML: all-null row materializes all column keys
    // ═══════════════════════════════════════════════════════════

    test("H3 YAML: all-null row contains all column keys") {
        val output = writeYaml(allNullRow)
        val rows = parseYamlRows(output)
        rows.size shouldBe 1
        rows[0].keys shouldContainExactlyInAnyOrder expectedColumnNames
    }

    test("H3 YAML: all-null first row + data row — each row has all keys") {
        val output = writeYaml(allNullFirstThenData)
        val rows = parseYamlRows(output)
        rows.size shouldBe 2
        for ((i, row) in rows.withIndex()) {
            row.keys shouldContainExactlyInAnyOrder expectedColumnNames
        }
    }

    // ═══════════════════════════════════════════════════════════
    // H3 — CSV: all-null row has correct number of delimited fields
    // ═══════════════════════════════════════════════════════════

    test("H3 CSV: header contains all column names") {
        val output = writeCsv(allNullRow)
        val headerLine = output.lines().first()
        headerLine.split(",") shouldContainExactly expectedColumnNames
    }

    test("H3 CSV: all-null row has same field count as header") {
        val output = writeCsv(allNullRow)
        val lines = output.lines().filter { it.isNotBlank() }
        lines.size shouldBe 2 // header + 1 data row
        val headerFields = lines[0].split(",").size
        lines[1].split(",").size shouldBe headerFields
    }

    test("H3 CSV: all-null first row + data row — field count consistent") {
        val output = writeCsv(allNullFirstThenData)
        val lines = output.lines().filter { it.isNotBlank() }
        lines.size shouldBe 3 // header + 2 data rows
        val headerFields = lines[0].split(",").size
        for (dataLine in lines.drop(1)) {
            dataLine.split(",").size shouldBe headerFields
        }
    }
})
```

### 5.3 Hinweise

- **Kein Produktionscode** betroffen — reine Test-Ergänzung.
- Die Helper-Funktionen `writeJson`/`writeYaml`/`writeCsv` folgen
  exakt dem Muster der bestehenden Writer-Tests (`ByteArrayOutputStream`
  → Writer-Konstruktor → `begin`/`write`/`end` → `toString`).
- `readAllChunks()` konsumiert den Reader vollständig; der Caller
  schließt den Reader über `use {}`.
- Die `assertValueEquivalent()`-Funktion kapselt die Typ-Normalisierung
  und ist bewusst auf die bekannten Round-Trip-Differenzen beschränkt.
- `assertCsvRowsEquivalent()` vergleicht mit inline definierten
  `Array<String?>`-Erwartungswerten (kein Map, direkte Array-Paare).
- `H3NullRowPropertyTest` verwendet keinerlei fragile Roh-String-Vergleiche:
  JSON nutzt Regex-Key-Extraktion (`"(\w+)"\s*:`), YAML nutzt die
  SnakeYAML-Engine-`Load`-API für strukturelles Parsing, CSV nutzt
  Feld-Splitting. Formatter-Änderungen (Spacing, Zeilenumbrüche)
  brechen diese Tests nicht.

---

## 6. Tests

### 6.1 Teststrategie

Zwei orthogonale Achsen:

1. **Round-Trip (GoldenMasterRoundTripTest)**: Writer → Reader →
   Vergleich. Beweist, dass der Reader den Writer-Output korrekt
   interpretiert. **30 Tests** (10 pro Format).

2. **H3-Property (H3NullRowPropertyTest)**: Direkter Writer-Output-
   Inspektion via struktureller Analyse. Beweist, dass alle Spalten
   auch bei All-Null-Rows materialisiert werden. **7 Tests**
   (2 JSON + 2 YAML + 3 CSV).

### 6.2 Testfälle — GoldenMasterRoundTripTest

| # | Testname | Format | Prüfung |
|---|---|---|---|
| 1 | `JSON round-trip: single row` | JSON | 1 Row → Write → Read → Werte äquivalent |
| 2 | `JSON round-trip: multiple rows` | JSON | 3 Rows → alle Werte korrekt |
| 3 | `JSON round-trip: null values` | JSON | Partielle Nulls → korrekt als `null` gelesen |
| 4 | `JSON round-trip: all-null row (H3)` | JSON | All-Null → 4 Spalten, alle null, Column-Namen korrekt |
| 5 | `JSON round-trip: all-null first row + data row (H3 regression)` | JSON | Column-Set vollständig, beide Rows korrekt gelesen |
| 6 | `JSON round-trip: empty table` | JSON | `[]` → `nextChunk()` == null |
| 7 | `JSON round-trip: multi-chunk` | JSON | 5 Rows, chunkSize=2 → Chunks [2,2,1], Werte + Spalten korrekt |
| 8 | `JSON round-trip: headerColumns` | JSON | `headerColumns()` == Column-Namen |
| 9 | `JSON round-trip: column order` | JSON | Spaltenreihenfolge erhalten |
| 10 | `JSON round-trip: BigDecimal/BigInteger precision` | JSON | Präzision über `assertRowsEquivalent` erhalten |
| 11 | `YAML round-trip: single row` | YAML | Analog zu JSON #1 |
| 12 | `YAML round-trip: multiple rows` | YAML | Analog zu JSON #2 |
| 13 | `YAML round-trip: null values` | YAML | Analog zu JSON #3 |
| 14 | `YAML round-trip: all-null row (H3)` | YAML | Analog zu JSON #4 |
| 15 | `YAML round-trip: all-null first row + data row (H3 regression)` | YAML | Analog zu JSON #5 |
| 16 | `YAML round-trip: empty table` | YAML | `[]` → `nextChunk()` == null |
| 17 | `YAML round-trip: multi-chunk` | YAML | Analog zu JSON #7 |
| 18 | `YAML round-trip: headerColumns` | YAML | Analog zu JSON #8 |
| 19 | `YAML round-trip: column order` | YAML | Analog zu JSON #9 |
| 20 | `YAML round-trip: BigDecimal/BigInteger precision` | YAML | Analog zu JSON #10 |
| 21 | `CSV round-trip: single row` | CSV | Werte als Strings verglichen |
| 22 | `CSV round-trip: multiple rows` | CSV | 3 Rows als Strings |
| 23 | `CSV round-trip: null values` | CSV | csvNullString="NULL" → `null` gelesen |
| 24 | `CSV round-trip: all-null row (H3)` | CSV | All-Null mit csvNullString → alle null |
| 25 | `CSV round-trip: all-null first row + data row (H3 regression)` | CSV | Beide Rows korrekt, Header vollständig |
| 26 | `CSV round-trip: empty table` | CSV | Header-only → headerColumns korrekt, nextChunk null |
| 27 | `CSV round-trip: multi-chunk` | CSV | 5 Rows, chunkSize=2 → Chunks [2,2,1], Werte + Spalten korrekt |
| 28 | `CSV round-trip: headerColumns` | CSV | `headerColumns()` == Column-Namen |
| 29 | `CSV round-trip: column order` | CSV | Spaltenreihenfolge erhalten |
| 30 | `CSV round-trip: BigDecimal/BigInteger as string` | CSV | String-Repräsentation korrekt |

### 6.3 Testfälle — H3NullRowPropertyTest

| # | Testname | Format | Prüfung |
|---|---|---|---|
| 1 | `H3 JSON: all-null row contains all column keys` | JSON | Regex-Key-Extraktion → Key-Set == erwartete Spalten |
| 2 | `H3 JSON: all-null first + data — each row has all keys` | JSON | Beide Objekt-Zeilen haben vollständiges Key-Set |
| 3 | `H3 YAML: all-null row contains all column keys` | YAML | SnakeYAML Load → `map.keys` == erwartete Spalten |
| 4 | `H3 YAML: all-null first + data — each row has all keys` | YAML | Beide Maps haben vollständiges Key-Set |
| 5 | `H3 CSV: header contains all column names` | CSV | Header-Zeile == `alpha,beta,gamma,delta` |
| 6 | `H3 CSV: all-null row has same field count as header` | CSV | Data-Row hat 4 Felder |
| 7 | `H3 CSV: all-null first + data — field count consistent` | CSV | Alle Rows haben gleiche Feldanzahl |

---

## 7. Coverage-Ziel

Dieser Schritt fügt **nur Tests** hinzu — kein neuer Produktionscode.
Die Tests erhöhen die Coverage der bestehenden Writer- und
Reader-Klassen. Der Coverage-Beitrag ist indirekt: die Tests
durchlaufen die vollständige Serialisierungs-/Deserialisierungskette
(ValueSerializer → Format-Writer → Format-Reader) und decken damit
vor allem die null-Handling-Pfade und Präzisions-Pfade ab, die in den
einzelnen Writer-/Reader-Tests noch nicht als Round-Trip getestet waren.

Gesamtziel pro Modul: ≥ 90 % (siehe `implementation-plan-0.4.0.md` §8.4).

---

## 8. Build & Verifizierung

```bash
# Nur d-migrate-formats testen (schneller Feedback-Loop)
docker build --target build \
  --build-arg GRADLE_TASKS=":d-migrate-formats:test" \
  -t d-migrate:phase-b11 .

# Vollständiger Build inkl. Coverage-Verification
docker build -t d-migrate:dev .
```

---

## 9. Abnahmekriterien

- [x] `GoldenMasterRoundTripTest` — alle 30 Round-Trip-Tests grün
- [x] `H3NullRowPropertyTest` — alle 7 H3-Property-Tests grün
- [x] JSON Round-Trip: Write → Read → Werte äquivalent (typbewusst, inkl. BigDecimal/BigInteger)
- [x] YAML Round-Trip: Write → Read → Werte äquivalent (typbewusst, inkl. BigDecimal/BigInteger)
- [x] CSV Round-Trip: Write → Read → Werte als Strings korrekt (inkl. BigDecimal/BigInteger)
- [x] H3: All-Null-Row materialisiert alle Spalten in jedem Format (strukturell geprüft)
- [x] H3 Regression: All-Null-First-Row + Datenzeile → Column-Set bleibt vollständig
- [x] Multi-Chunk-Tests prüfen Werte, Spalten und Chunk-Größen
- [x] Coverage ≥ 90 % für `d-migrate-formats`
- [x] Docker-Build (`docker build -t d-migrate:dev .`) ist grün
- [x] Kein neuer Produktionscode, nur Test-Klassen

---

## 10. Offene Punkte

Keine.
