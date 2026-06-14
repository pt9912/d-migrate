package dev.dmigrate.format.data

import dev.dmigrate.core.data.ColumnDescriptor

/**
 * Test-Bridging-Extension fuer Bestandstests, die heute
 * `writer.begin(table, columns: List<ColumnDescriptor>)`
 * aufrufen. Delegiert auf den neuen
 * `begin(table, schema: ChunkSchema)`-Vertrag (AP2 §6.2,
 * Parquet Cut A S0b) mit synthesiertem Schema
 * (`NeutralType.Text` per Default).
 *
 * Nur in Tests verwenden; produktive Aufrufer bauen
 * `ChunkSchema` aus echten JDBC-Metadaten.
 */
fun DataChunkWriter.begin(table: String, columns: List<ColumnDescriptor>) {
    begin(table, chunkSchemaOf(table, columns))
}
