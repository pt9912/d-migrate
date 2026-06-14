package dev.dmigrate.format.parquet

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataChunkReader
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.example.data.Group
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.example.GroupReadSupport
import org.apache.parquet.hadoop.util.HadoopInputFile
import java.nio.file.Path

/**
 * Produktiver [DataChunkReader] fuer Parquet (S3 Cut A).
 *
 * Konstruiert mit dem bereits aus dem AP7/AP8/AP9-Preflight
 * aufgeloestem [ChunkSchema] (AP10 §3.3) und einer
 * [SeekableChunkSource.Local]-Datei.
 *
 * Vertragsdetails:
 *
 * - Konstruktion fuehrt den AP10 §3.3-Footer-vs-ChunkSchema-
 *   Konsistenzcheck (Namens-/Anzahlpruefung) durch und wirft
 *   bei Drift einen [ParquetSchemaMismatchException]. Eine
 *   vollstaendige Typgleichheit ist bewusst nicht gefordert
 *   (semantische N:M-Beziehung zwischen `NeutralType` und
 *   Parquet-Primitive).
 * - [nextChunk] liefert bis zu `chunkSize` Rows als
 *   `Array<Any?>` pro Row, gemappt ueber
 *   [ParquetGroupValueReader].
 * - [headerColumns] gibt die Spaltennamen aus dem Schema
 *   zurueck (deterministischer Snapshot bereits nach
 *   Konstruktion).
 * - [close] schliesst den unterliegenden `ParquetReader`
 *   idempotent.
 */
class ParquetChunkReader(
    private val schema: ChunkSchema,
    file: Path,
    private val chunkSize: Int,
) : DataChunkReader {

    init {
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
        verifyFooterMatchesSchema(file, schema)
    }

    private val configuration = Configuration(false)
    private val reader: ParquetReader<Group> = ParquetReader
        .builder(GroupReadSupport(), HadoopPath(file.toUri()))
        .withConf(configuration)
        .build()

    private var closed: Boolean = false
    private var exhausted: Boolean = false

    private val columnDescriptors: List<ColumnDescriptor> = schema.columns.map {
        ColumnDescriptor(name = it.name, nullable = it.nullable, sqlTypeName = null)
    }

    override fun nextChunk(): DataChunk? {
        check(!closed) { "ParquetChunkReader is closed" }
        if (exhausted) return null
        val rows = ArrayList<Array<Any?>>(chunkSize.coerceAtMost(INITIAL_ROW_CAPACITY))
        while (rows.size < chunkSize) {
            val group = reader.read() ?: run {
                exhausted = true
                return@run null
            } ?: break
            val row = arrayOfNulls<Any?>(schema.columns.size)
            for ((index, column) in schema.columns.withIndex()) {
                row[index] = ParquetGroupValueReader.readColumn(group, index, column.neutralType)
            }
            rows += row
        }
        if (rows.isEmpty()) return null
        return DataChunk(
            table = schema.table,
            columns = columnDescriptors,
            rows = rows,
            chunkIndex = nextChunkIndex++,
        )
    }

    override fun headerColumns(): List<String> = schema.columns.map { it.name }

    override fun close() {
        if (closed) return
        closed = true
        reader.close()
    }

    private var nextChunkIndex: Long = 0L

    private companion object {
        private const val INITIAL_ROW_CAPACITY = 1024
    }
}

/**
 * Wird vom [ParquetChunkReader]-Konstruktor geworfen, wenn der
 * Parquet-Footer-`MessageType` weder in Spaltenanzahl noch in
 * Spaltennamen mit dem aus dem Preflight aufgeloestem
 * [ChunkSchema] uebereinstimmt (AP10 §3.3, Fehlerklasse
 * `BUNDLE_SCHEMA_PARQUET_MISMATCH`).
 */
class ParquetSchemaMismatchException(message: String) : RuntimeException(message)

private fun verifyFooterMatchesSchema(file: Path, schema: ChunkSchema) {
    val conf = Configuration(false)
    val footer = ParquetFileReader.open(HadoopInputFile.fromPath(HadoopPath(file.toUri()), conf)).use {
        it.fileMetaData.schema
    }
    val footerNames = footer.fields.map { it.name }
    val schemaNames = schema.columns.map { it.name }
    if (footerNames.size != schemaNames.size) {
        throw ParquetSchemaMismatchException(
            "BUNDLE_SCHEMA_PARQUET_MISMATCH: table=${schema.table} " +
                "footer column count=${footerNames.size}, schema column count=${schemaNames.size}",
        )
    }
    if (footerNames != schemaNames) {
        val diff = schemaNames.indices.filter { schemaNames[it] != footerNames[it] }
            .map { "$it: schema='${schemaNames[it]}', footer='${footerNames[it]}'" }
        throw ParquetSchemaMismatchException(
            "BUNDLE_SCHEMA_PARQUET_MISMATCH: table=${schema.table} column name drift at ${diff.joinToString()}",
        )
    }
}
