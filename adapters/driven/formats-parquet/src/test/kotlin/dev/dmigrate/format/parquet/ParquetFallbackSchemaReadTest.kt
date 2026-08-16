package dev.dmigrate.format.parquet

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.data.SeekableChunkSource
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * Regression fuer den Fall, der bis 1.0.0 mit einer rohen
 * `ClassCastException` aus der Parquet-Bibliothek abbrach: ein
 * Mitglied eines Bundle-Exports (`--split-files`), einzeln ueber den
 * Single-File-Pfad importiert.
 *
 * Bundle-Mitglieder tragen **keinen** Footer-KV `d-migrate.manifest`
 * (ihr Schema liegt in der `manifest.yaml` daneben). Der
 * Preflight-Fallback fuellte deshalb jede Spalte mit `NeutralType.Text`
 * — ein Platzhalter, den ein spaeterer Schritt ersetzen sollte, der nie
 * kam. `readColumn` rief daraufhin `getString` auf einer INT32-Spalte.
 *
 * **Warum es der Testsuite entging:** Der Fallback wurde nur an der
 * Preflight-Grenze geprueft, und der dortige Test schrieb den
 * Platzhalter sogar fest (`all { it.neutralType is NeutralType.Text }`).
 * Es las **kein** Test Zeilen durch ein `MANIFEST_FALLBACK`-Schema —
 * also genau vor der Stelle, an der es brach. Dieser Test schliesst
 * die Luecke: er geht durch den Reader, nicht nur bis zum Preflight.
 */
class ParquetFallbackSchemaReadTest : FunSpec({

    /** Schreibt eine Datei ohne Footer-KV — die Form eines Bundle-Mitglieds. */
    fun writeBundleMemberStyle(path: java.nio.file.Path, schema: ChunkSchema, rows: List<Array<Any?>>) {
        Files.newOutputStream(path).use { out ->
            ParquetChunkWriter(out).use { writer ->
                writer.begin(schema.table, schema)
                writer.write(DataChunk(table = schema.table, columns = emptyList(), rows = rows, chunkIndex = 0L))
                writer.end()
            }
        }
    }

    test("Bundle-Mitglied ohne Footer-KV: Fallback-Schema liest gemischte Typen korrekt") {
        val tmp = Files.createTempFile("parquet-fallback-read-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val written = ChunkSchema(
                table = "t",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(
                    ChunkColumnSchema("id", false, NeutralType.Integer),
                    ChunkColumnSchema("name", true, NeutralType.Text()),
                    ChunkColumnSchema("n", true, NeutralType.Float(FloatPrecision.DOUBLE)),
                    ChunkColumnSchema("big", true, NeutralType.BigInteger),
                    ChunkColumnSchema("flag", true, NeutralType.BooleanType),
                ),
            )
            writeBundleMemberStyle(
                tmp,
                written,
                listOf(
                    arrayOf<Any?>(1, "alice", 1.5, 9_000_000_000L, true),
                    arrayOf<Any?>(2, "bob", 2.5, -1L, false),
                ),
            )

            // Preflight liefert das Fallback-Schema — ohne Manifest.
            val phase1 = ParquetSingleFilePreflight().phase1(tmp, explicitTable = "t")
            phase1.manifestPresent shouldBe false
            phase1.schema.origin shouldBe SchemaOrigin.MANIFEST_FALLBACK

            // Der Kern: durch dieses Schema wird tatsaechlich GELESEN.
            val reader = ParquetSeekableDataChunkReaderFactory().create(
                format = DataExportFormat.PARQUET,
                source = SeekableChunkSource.Local(tmp),
                table = "t",
                schema = phase1.schema,
                chunkSize = 10,
                options = FormatReadOptions(),
            )
            val chunk = reader.use { it.nextChunk() } ?: error("expected chunk, got null")

            chunk.rows.size shouldBe 2
            chunk.rows[0][0] shouldBe 1
            chunk.rows[0][1] shouldBe "alice"
            chunk.rows[0][2] shouldBe 1.5
            chunk.rows[0][3] shouldBe 9_000_000_000L
            chunk.rows[0][4] shouldBe true
            chunk.rows[1][0] shouldBe 2
            chunk.rows[1][3] shouldBe -1L
            chunk.rows[1][4] shouldBe false
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("Fallback-Schema traegt die Typen des Footers, nicht durchgehend Text") {
        val tmp = Files.createTempFile("parquet-fallback-types-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val written = ChunkSchema(
                table = "t",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(
                    ChunkColumnSchema("i", false, NeutralType.Integer),
                    ChunkColumnSchema("l", true, NeutralType.BigInteger),
                    ChunkColumnSchema("d", true, NeutralType.Float(FloatPrecision.DOUBLE)),
                    ChunkColumnSchema("f", true, NeutralType.Float(FloatPrecision.SINGLE)),
                    ChunkColumnSchema("b", true, NeutralType.BooleanType),
                    ChunkColumnSchema("s", true, NeutralType.Text()),
                    ChunkColumnSchema("day", true, NeutralType.Date),
                    ChunkColumnSchema("raw", true, NeutralType.Binary),
                ),
            )
            writeBundleMemberStyle(tmp, written, listOf(arrayOf<Any?>(null, null, null, null, null, null, null, null)))

            val schema = ParquetSingleFilePreflight().phase1(tmp, explicitTable = "t").schema
            schema.columns.map { it.neutralType } shouldBe listOf(
                NeutralType.Integer,
                NeutralType.BigInteger,
                NeutralType.Float(FloatPrecision.DOUBLE),
                NeutralType.Float(FloatPrecision.SINGLE),
                NeutralType.BooleanType,
                // Text/Email/Char/Xml/Array teilen sich BINARY+String —
                // aus dem Footer allein ist nur die allgemeinste Variante
                // ableitbar. Fuer den Lesezugriff ist das gleichwertig.
                NeutralType.Text(),
                NeutralType.Date,
                NeutralType.Binary,
            )
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("Mit Footer-KV reisen die spezifischen Typen mit, die der Footer allein nicht traegt") {
        // Gegenprobe zum Fallback: `Identifier`, `Geometry` und `Char` teilen
        // sich ihre Physik mit `Integer`, `Binary` und `Text`. Aus dem
        // MessageType allein sind sie NICHT rekonstruierbar — der Footer-KV
        // traegt sie. Deshalb schreibt das CLI-Wiring ihn seit dieser Aenderung
        // auch fuer Bundle-Mitglieder.
        val tmp = Files.createTempFile("parquet-kv-specific-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val written = ChunkSchema(
                table = "t",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(
                    ChunkColumnSchema("id", false, NeutralType.Identifier(autoIncrement = true)),
                    ChunkColumnSchema("code", true, NeutralType.Char(length = 3)),
                ),
            )
            Files.newOutputStream(tmp).use { out ->
                ParquetChunkWriter(
                    output = out,
                    extraMetaDataProvider = ParquetSingleFileManifestWriter(producerVersion = "test").provider,
                ).use { writer ->
                    writer.begin("t", written)
                    writer.write(DataChunk("t", emptyList(), listOf(arrayOf<Any?>(1, "abc")), 0L))
                    writer.end()
                }
            }

            val phase1 = ParquetSingleFilePreflight().phase1(tmp, explicitTable = "t")
            phase1.manifestPresent shouldBe true
            phase1.schema.columns.map { it.neutralType } shouldBe listOf(
                NeutralType.Identifier(autoIncrement = true),
                NeutralType.Char(length = 3),
            )

            // Ohne KV bliebe nur die Physik uebrig — das ist der Unterschied,
            // den diese Aenderung fuer Bundle-Mitglieder aufhebt.
            val bare = Files.createTempFile("parquet-kv-bare-", ".parquet")
            Files.deleteIfExists(bare)
            try {
                writeBundleMemberStyle(bare, written, listOf(arrayOf<Any?>(1, "abc")))
                val fallback = ParquetSingleFilePreflight().phase1(bare, explicitTable = "t")
                fallback.manifestPresent shouldBe false
                fallback.schema.columns.map { it.neutralType } shouldBe listOf(
                    NeutralType.Integer,
                    NeutralType.Text(),
                )
            } finally {
                Files.deleteIfExists(bare)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("Typ-Drift zwischen Footer und Schema wird als MISMATCH abgewiesen, nicht als ClassCastException") {
        val tmp = Files.createTempFile("parquet-shape-drift-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val written = ChunkSchema(
                table = "t",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(ChunkColumnSchema("id", false, NeutralType.Integer)),
            )
            writeBundleMemberStyle(tmp, written, listOf(arrayOf<Any?>(1)))

            // Genau der alte Fehlerzustand: Name stimmt, Typ ist Text.
            val wrong = ChunkSchema(
                table = "t",
                origin = SchemaOrigin.MANIFEST_FALLBACK,
                columns = listOf(ChunkColumnSchema("id", false, NeutralType.Text())),
            )
            val error = shouldThrow<ParquetSchemaMismatchException> {
                ParquetSeekableDataChunkReaderFactory().create(
                    format = DataExportFormat.PARQUET,
                    source = SeekableChunkSource.Local(tmp),
                    table = "t",
                    schema = wrong,
                    chunkSize = 10,
                    options = FormatReadOptions(),
                )
            }
            error.message!! shouldContain "column type drift"
            error.message!! shouldContain "id"
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
})
