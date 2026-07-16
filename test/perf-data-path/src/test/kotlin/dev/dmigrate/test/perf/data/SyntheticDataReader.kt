package dev.dmigrate.test.perf.data

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.data.ChunkSequence
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin

/**
 * LN-005: synthetischer, **lazy** [DataReader] — generiert [rowCount] Zeilen mit einer
 * [payloadWidth]-Zeichen-Payload chunk-weise (`ArrayList(chunkSize)` pro Iteration),
 * ohne je alle Zeilen gleichzeitig zu halten. Kein Backing-DB; der [ConnectionPool]
 * wird ignoriert. Spiegelt den Laziness-Vertrag von `JdbcChunkSequence`, damit der
 * Heap-Cap-Test die **Konsumenten**-Seite (Chunk-Loop + Format-Writer) auf bounded
 * memory prüfen kann, ohne eine echte >10-TB-Quelle.
 */
class SyntheticDataReader(
    private val rowCount: Long,
    private val payloadWidth: Int,
    private val table: String = "synthetic",
) : DataReader {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
    ): ChunkSequence = SyntheticChunkSequence(this.table, rowCount, chunkSize, payloadWidth)
}

private class SyntheticChunkSequence(
    private val table: String,
    private val rowCount: Long,
    private val chunkSize: Int,
    private val payloadWidth: Int,
) : ChunkSequence {

    override val schema = ChunkSchema(
        table = table,
        origin = SchemaOrigin.JDBC_METADATA,
        columns = listOf(
            ChunkColumnSchema("id", nullable = false, neutralType = NeutralType.BigInteger),
            ChunkColumnSchema("payload", nullable = false, neutralType = NeutralType.Text()),
        ),
    )

    private var consumed = false

    override fun iterator(): Iterator<DataChunk> {
        check(!consumed) { "ChunkSequence is single-use" }
        consumed = true
        return object : Iterator<DataChunk> {
            private var emitted = 0L
            private var chunkIndex = 0L

            override fun hasNext(): Boolean = emitted < rowCount

            override fun next(): DataChunk {
                if (!hasNext()) throw NoSuchElementException()
                val n = minOf(chunkSize.toLong(), rowCount - emitted).toInt()
                val rows = ArrayList<Array<Any?>>(n)
                repeat(n) {
                    val id = emitted + it
                    // Pro Zeile eine frische, breite Payload (kein Interning/Dedup),
                    // damit das Gesamtvolumen real dem Heap gegenübersteht.
                    rows.add(arrayOf<Any?>(id, "row-$id-".padEnd(payloadWidth, 'x')))
                }
                emitted += n
                return DataChunk(table = table, columns = emptyList(), rows = rows, chunkIndex = chunkIndex++)
            }
        }
    }

    override fun close() { /* no-op — kein Backing-Resource */ }
}

/** LN-005: [ConnectionPool], den der [SyntheticDataReader] ignoriert (borrow wird nie aufgerufen). */
object NoopConnectionPool : ConnectionPool {
    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL
    override fun borrow(): DatabaseConnection = error("SyntheticDataReader must not borrow a connection")
    override fun activeConnections(): Int = 0
    override fun close() { /* no-op */ }
}
