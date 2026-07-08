package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.PartitionBoundScanner
import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * AP6.1 (ADR 0020): MySQL-Reverse-Capture der Partitionierung aus
 * `information_schema.PARTITIONS`. Erfasst die **MySQL-native** Form:
 * - RANGE: `PARTITION_DESCRIPTION` = `VALUES LESS THAN`-Obergrenze → `to`.
 * - LIST: `PARTITION_DESCRIPTION` = `VALUES IN`-Liste → `values`.
 * - HASH: keine Grenze; nur benannte Kinder.
 *
 * **AP6.5 (ADR 0020 §6):** MySQL speichert die native Form verlustarm, aber knapper als PG;
 * der Reader hebt sie ins **vollständige** neutrale Modell, das die volle PG-Semantik trägt
 * (`reconstructNeutralBounds`): RANGE-`from` aus der Kontiguität, HASH-`modulus`/`remainder`
 * aus `PARTITIONS n`. So generiert der bestehende PG-Generator unverändert valides PG-DDL.
 *
 * Das Top-Level-Splitting teilt sich den [PartitionBoundScanner] mit dem PG-Parser
 * (`PostgresPartitionBoundParser`) — beide Formen sind dieselbe Klammer-/Quote-bewusste
 * Komma-Trennung (AP6-Review P3 #9).
 */
internal object MysqlPartitionReader {

    fun read(session: JdbcOperations, schemaName: String, table: String): PartitionConfig? {
        val rows = MysqlMetadataQueries.listPartitions(session, schemaName, table)
        if (rows.isEmpty()) return null
        val method = (rows.first()["partition_method"] as? String)?.uppercase() ?: return null
        val type = when {
            method.startsWith("RANGE") -> PartitionType.RANGE
            method.startsWith("LIST") -> PartitionType.LIST
            method.contains("HASH") || method.contains("KEY") -> PartitionType.HASH
            else -> return null
        }
        // §6/#6: Nur **spaltenbasierte** Partitionierung ist verlustfrei ins neutrale Modell
        // (Spaltenliste als `key`) abbildbar. Eine funktions-/ausdrucksbasierte Form
        // (`PARTITION BY RANGE (YEAR(order_date))` → expression `year(\`order_date\`)`) hat im
        // neutralen Modell keinen Ausdruck-Schlüssel; sie als Spalte zu erfassen ergäbe einen
        // Müll-Key (`RANGE COLUMNS (\`year(order_date)\`)`). Solche Tabellen werden daher NICHT
        // als partitioniert erfasst (kein Falsch-Round-Trip). d-migrate-eigene Tabellen nutzen
        // immer die COLUMNS-Form und sind nicht betroffen.
        val key = parseColumnKey(rows.first()["partition_expression"] as? String) ?: return null
        val partitions = rows.mapNotNull { row -> parsePartition(row, type) }
        return PartitionConfig(type = type, key = key, partitions = reconstructNeutralBounds(partitions, type))
    }

    /**
     * AP6.5 (ADR 0020 §6): MySQLs knappere native Form ins vollständige neutrale Modell heben.
     * - RANGE: `from` aus der Kontiguität (`fromₙ = toₙ₋₁`, erstes `from = MINVALUE`).
     * - HASH: `modulus = n`, `remainder = Ordinalindex` (MySQL `PARTITIONS n` verteilt selbst).
     * - LIST: trägt seine `values` bereits vollständig.
     * Der tz-Verlust aus AP6.2 ist nicht invertierbar (MySQL `DATETIME` hat die Zone nie
     * gespeichert) — die Grenzen bleiben wie reverse-erfasst (UTC-Annahme, kein Raten).
     */
    private fun reconstructNeutralBounds(
        partitions: List<PartitionDefinition>,
        type: PartitionType,
    ): List<PartitionDefinition> = when (type) {
        PartitionType.RANGE -> reconstructRangeFrom(partitions)
        PartitionType.HASH -> partitions.mapIndexed { i, p -> p.copy(modulus = partitions.size, remainder = i) }
        PartitionType.LIST -> partitions
    }

    /**
     * `fromₙ = toₙ₋₁`; das erste `from` = `MINVALUE` je Schlüsselspalte (Arität aus `to`).
     *
     * Eine Partition ohne `to` ist aus validem MySQL nicht erreichbar (RANGE liefert immer
     * eine `VALUES LESS THAN`-Obergrenze, mittiges `MAXVALUE` ist verboten). Träfe sie doch
     * auf, wird `prevUpper` **nicht** fortgetragen (das ergäbe eine überlappende `from`),
     * sondern auf `null` zurückgesetzt, sodass die nächste Partition wieder mit `MINVALUE`
     * beginnt — defensiv konsistent statt still überlappend (AP6-Review P3 #15).
     */
    private fun reconstructRangeFrom(partitions: List<PartitionDefinition>): List<PartitionDefinition> {
        var prevUpper: List<PartitionBound>? = null
        return partitions.map { p ->
            val from = prevUpper ?: p.to?.map { PartitionBound.MinValue } ?: listOf(PartitionBound.MinValue)
            prevUpper = p.to
            p.copy(from = from)
        }
    }

    /**
     * `PARTITION_EXPRESSION` (`` `payment_date` `` bzw. `` `a`,`b` ``) → Spaltenliste
     * (Backticks gestrippt). Gibt `null` zurück, wenn der Ausdruck **keine reine Spaltenliste**
     * ist (z. B. `year(\`order_date\`)`) — diese funktions-basierte Form ist nicht ins
     * neutrale Spalten-Modell abbildbar (#6).
     */
    private fun parseColumnKey(expression: String?): List<String>? {
        if (expression.isNullOrBlank()) return null
        val parts = PartitionBoundScanner.splitTopLevel(expression).map { it.trim() }
        if (parts.isEmpty() || parts.any { !isPlainColumnRef(it) }) return null
        return parts.map { it.removeSurrounding("`") }
    }

    /** Eine einfache Spaltenreferenz: `` `name` `` oder `name` — kein Funktionsaufruf/Ausdruck. */
    private fun isPlainColumnRef(token: String): Boolean = when {
        token.startsWith("`") -> token.endsWith("`") && token.length >= 2 &&
            !token.substring(1, token.length - 1).contains('`')
        else -> token.isNotEmpty() && token.none { it == '(' || it == ')' || it.isWhitespace() }
    }

    private fun parsePartition(row: Map<String, Any?>, type: PartitionType): PartitionDefinition? {
        val name = row["partition_name"] as? String ?: return null
        val description = (row["partition_description"] as? String)?.trim()
        return when (type) {
            PartitionType.RANGE -> PartitionDefinition(name = name, to = parseRangeUpperBound(description))
            PartitionType.LIST -> PartitionDefinition(
                name = name,
                values = description?.let { PartitionBoundScanner.splitTopLevel(it) },
            )
            PartitionType.HASH -> PartitionDefinition(name = name)
        }
    }

    /** `VALUES LESS THAN`-Wert(e): `MAXVALUE` → Sentinel, sonst Literal; mehrspaltig = Tupel. */
    private fun parseRangeUpperBound(description: String?): List<PartitionBound>? {
        if (description.isNullOrBlank()) return null
        return PartitionBoundScanner.splitTopLevel(description).map { value ->
            if (value.equals("MAXVALUE", ignoreCase = true)) PartitionBound.MaxValue
            else PartitionBound.Value(value)
        }
    }
}
