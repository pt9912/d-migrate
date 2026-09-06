package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.PartitionBoundScanner
import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Liest die Partitionierung einer Oracle-Tabelle aus `ALL_PART_TABLES`,
 * `ALL_PART_KEY_COLUMNS` und `ALL_TAB_PARTITIONS` (ADR 0052, Slice 7).
 *
 * Alle Formen unten sind gegen `gvenzl/oracle-free:23` gemessen, nicht der
 * Dokumentation entnommen:
 *
 * | angelegt als | `HIGH_VALUE` |
 * | --- | --- |
 * | `RANGE` auf `DATE` | `TO_DATE(' 2024-01-01 00:00:00', 'SYYYY-MM-DD HH24:MI:SS', 'NLS_CALENDAR=GREGORIAN')` |
 * | `RANGE` mehrspaltig | `10, 100` bzw. `MAXVALUE, MAXVALUE` |
 * | `LIST` | `'A', 'B'` bzw. `DEFAULT` |
 * | `HASH` | `null` |
 *
 * Zwei Dinge folgen daraus unmittelbar: die `TO_DATE`-Form traegt selbst
 * Kommata, ein mehrspaltiger Wert darf also nur auf **oberster** Ebene
 * getrennt werden ([PartitionBoundScanner]) — und Oracle-HASH fuehrt weder
 * Modulus noch Remainder, die Felder des neutralen Modells bleiben dort leer.
 */
internal object OraclePartitionReader {

    /**
     * [interval] und [subpartitioningType] sind die beiden Formen, fuer die
     * das neutrale Modell keinen Begriff hat. Sie werden gefuehrt statt
     * verschwiegen, damit der Leser sie melden kann.
     */
    data class PartitionScan(
        val config: PartitionConfig,
        val interval: String?,
        val subpartitioningType: String?,
    )

    fun read(session: JdbcOperations, schema: String, table: String): PartitionScan? {
        val head = session.querySingle(
            """
            SELECT partitioning_type, subpartitioning_type, interval
            FROM all_part_tables
            WHERE owner = ? AND table_name = ?
            """.trimIndent(),
            schema,
            table,
        ) ?: return null

        val type = partitionType(head["partitioning_type"] as? String) ?: return null
        val key = session.queryList(
            """
            SELECT column_name
            FROM all_part_key_columns
            WHERE owner = ? AND name = ? AND object_type = 'TABLE'
            ORDER BY column_position
            """.trimIndent(),
            schema,
            table,
        ).map { it.string("column_name") }

        val partitions = session.queryList(
            """
            SELECT partition_name, partition_position, high_value
            FROM all_tab_partitions
            WHERE table_owner = ? AND table_name = ?
            ORDER BY partition_position
            """.trimIndent(),
            schema,
            table,
        ).map { row -> partition(row.string("partition_name"), row["high_value"]?.toString(), type) }

        val subpartitioning = (head["subpartitioning_type"] as? String)?.takeUnless { it == "NONE" }
        return PartitionScan(
            config = PartitionConfig(type = type, key = key, partitions = partitions),
            interval = (head["interval"] as? String)?.trim()?.ifEmpty { null },
            subpartitioningType = subpartitioning,
        )
    }

    private fun partitionType(catalog: String?): PartitionType? = when (catalog) {
        "RANGE" -> PartitionType.RANGE
        "LIST" -> PartitionType.LIST
        "HASH" -> PartitionType.HASH
        // REFERENCE und SYSTEM haben keine neutrale Entsprechung; sie als
        // RANGE zu raten ergaebe eine Tabelle, die anders partitioniert
        // wieder entstuende.
        else -> null
    }

    private fun partition(
        name: String,
        highValue: String?,
        type: PartitionType,
    ): PartitionDefinition = when (type) {
        // Oracle verteilt HASH selbst -- kein HIGH_VALUE, kein Modulus.
        PartitionType.HASH -> PartitionDefinition(name = name)
        PartitionType.LIST ->
            if (highValue?.trim() == "DEFAULT") {
                PartitionDefinition(name = name, isDefault = true)
            } else {
                PartitionDefinition(
                    name = name,
                    values = PartitionBoundScanner.splitTopLevel(highValue.orEmpty())
                        .map { canonicalizeLiteral(it) },
                )
            }
        PartitionType.RANGE -> PartitionDefinition(
            name = name,
            to = PartitionBoundScanner.splitTopLevel(highValue.orEmpty()).map { bound(it) },
        )
    }

    private fun bound(raw: String): PartitionBound =
        if (raw.trim().equals("MAXVALUE", ignoreCase = true)) {
            PartitionBound.MaxValue
        } else {
            PartitionBound.Value(canonicalizeLiteral(raw))
        }

    /**
     * Oracles Katalogform auf die kanonische Form des neutralen Modells —
     * dieselbe, die PostgreSQL und MySQL liefern (`'2024-01-01 00:00:00'`).
     * Ohne diese Umkehrung stuende Oracle-Syntax im neutralen Modell, und ein
     * Cross-Dialect-Vergleich meldete Drift fuer denselben Grenzwert.
     *
     * Der Zeitanteil bleibt **stehen**, auch wenn er Mitternacht ist: Oracles
     * `DATE` traegt eine Uhrzeit, und der Katalog fuehrt sie. Ob das gegen
     * eine als reines Datum geschriebene Soll-Grenze passt, entscheidet die
     * Fingerabdruck-Projektion (`capabilityPartitionCanonicalizer`) — hier
     * waere es geraten, dort ist es eine Dialekt-Eigenschaft.
     */
    private fun canonicalizeLiteral(raw: String): String {
        val trimmed = raw.trim()
        val inner = TO_DATE_CALL.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: return trimmed
        return "'${inner.removeSurrounding("'").trim()}'"
    }

    /** `TO_DATE(<literal>, <maske>[, <nls>])` — nur das erste Argument traegt den Wert. */
    private val TO_DATE_CALL = Regex("^TO_(?:DATE|TIMESTAMP)\\s*\\((.+?),\\s*'[^']*'(?:\\s*,\\s*'[^']*')?\\s*\\)$")
}
