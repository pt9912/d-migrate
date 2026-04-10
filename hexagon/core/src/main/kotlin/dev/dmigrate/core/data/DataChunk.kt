package dev.dmigrate.core.data

/**
 * Ein Chunk von Tabellendaten — die zentrale Stream-Einheit für den Datenexport.
 *
 * Ein DataChunk ist ein neutrales, JDBC-freies Datenmodell. Format-Writer
 * (JSON/YAML/CSV) und Streaming-Komponenten arbeiten ausschließlich auf
 * diesem Modell, ohne den `java.sql`-Namespace zu importieren.
 *
 * Verträge:
 *
 * - **Spalten pro Chunk** statt einmalig pro Tabelle. Ein Streaming-Writer
 *   kann jeden Chunk unabhängig serialisieren — auch wenn spätere Versionen
 *   parallel verarbeiten.
 * - **`rows` als `List<Array<Any?>>`** statt `List<Map<...>>` — spart
 *   Hashmap-Allokationen bei Millionen von Rows. Format-Writer indexieren
 *   über `columns[i].name`.
 * - **Java-native Werte**, kein Mapping zu NeutralType in 0.3.0. Format-
 *   Writer dispatchen über `value::class` zur Laufzeit.
 * - **Leere Chunks erlaubt**: Bei einer leeren Tabelle MUSS der Reader genau
 *   einen DataChunk mit `columns` korrekt gefüllt und `rows = emptyList()`
 *   liefern, damit Format-Writer ihren Header schreiben können (siehe
 *   implementation-plan-0.3.0.md §6.17).
 *
 * @property table Tabellenname (kann schema-qualifiziert sein, z.B. "public.orders")
 * @property columns Spaltenmetadaten in Reihenfolge der Werte in `rows`
 * @property rows Datenzeilen — jede Zeile ist ein Array gleicher Länge wie `columns`
 * @property chunkIndex 0-basierter Index dieses Chunks innerhalb des Tabellen-Streams
 */
data class DataChunk(
    val table: String,
    val columns: List<ColumnDescriptor>,
    val rows: List<Array<Any?>>,
    val chunkIndex: Long,
) {
    /** equals/hashCode überschrieben, weil Array<Any?> in data class structurally nicht sauber vergleicht. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataChunk) return false
        if (table != other.table) return false
        if (columns != other.columns) return false
        if (chunkIndex != other.chunkIndex) return false
        if (rows.size != other.rows.size) return false
        for (i in rows.indices) {
            if (!rows[i].contentEquals(other.rows[i])) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = table.hashCode()
        result = 31 * result + columns.hashCode()
        result = 31 * result + chunkIndex.hashCode()
        for (row in rows) {
            result = 31 * result + row.contentHashCode()
        }
        return result
    }
}
