package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionType

/**
 * `CREATE PARTITION FUNCTION` / `CREATE PARTITION SCHEME` fuer T-SQL.
 *
 * SQL Server beschreibt Partitionierung nicht an der Tabelle, sondern in zwei
 * eigenstaendigen Datenbankobjekten, an die die Tabelle sich haengt:
 *
 * ```sql
 * CREATE PARTITION FUNCTION pf_orders (date) AS RANGE RIGHT FOR VALUES ('2024-01-01');
 * CREATE PARTITION SCHEME  ps_orders AS PARTITION pf_orders ALL TO ([PRIMARY]);
 * CREATE TABLE orders (…) ON ps_orders (placed_on);
 * ```
 *
 * **`RANGE RIGHT` ist nicht gewaehlt, sondern erzwungen:** das neutrale Modell
 * beschreibt eine Partition als halboffenes Intervall `[from, to)`, und genau
 * das ist RIGHT. Bei LEFT gehoerte der Grenzwert zur unteren Partition.
 *
 * **Die Objekte sind in SQL Server datenbankweit und teilbar** -- mehrere
 * Tabellen koennen an derselben Function haengen. Das neutrale Modell traegt die
 * Partitionierung dagegen je Tabelle, und aus dieser Richtung laesst sich die
 * Teilung nicht rekonstruieren. Der Generate-Pfad legt deshalb je Tabelle ein
 * eigenes Paar an: funktional gleichwertig, physisch mehr Objekte. Wo das
 * auffaellt, sagt es `W144`.
 */
internal object MssqlPartitionDdl {

    fun functionName(table: String): String = "pf_$table"

    fun schemeName(table: String): String = "ps_$table"

    /** `ON ps_<tabelle> (spalte)` — die Klausel, die die Tabelle an das Scheme haengt. */
    fun onClause(table: String, config: PartitionConfig, quote: (String) -> String): String =
        " ON ${quote(schemeName(table))} (${quote(config.key.first())})"

    /**
     * Function und Scheme, in dieser Reihenfolge — das Scheme referenziert die
     * Function, und beide muessen vor der Tabelle stehen.
     *
     * @param storage der Ablageort aller Partitionen. SQL Server verlangt je
     *   Partition eine Filegroup; das neutrale Modell kennt keinen Ablageort,
     *   also kommt er aus den Generierungsoptionen.
     */
    fun createStatements(
        table: String,
        config: PartitionConfig,
        columnType: String,
        storage: String,
        quote: (String) -> String,
    ): List<String> = createStatementsForBoundaries(table, boundaryLiterals(config), columnType, storage, quote)

    /**
     * Dieselben zwei Objekte, aber mit vorgegebenen Grenzen.
     *
     * Die HASH-Emulation (Sub-Slice 7d) leitet ihre Grenzen nicht aus den
     * Modell-Partitionen ab, sondern aus der Eimeranzahl — sie braucht diesen
     * Einstieg, nicht [boundaryLiterals].
     */
    fun createStatementsForBoundaries(
        table: String,
        boundaries: List<String>,
        columnType: String,
        storage: String,
        quote: (String) -> String,
    ): List<String> {
        val function = buildString {
            append("CREATE PARTITION FUNCTION ${quote(functionName(table))} ($columnType) ")
            append("AS RANGE RIGHT FOR VALUES (${boundaries.joinToString(", ")});")
        }
        val scheme = "CREATE PARTITION SCHEME ${quote(schemeName(table))} " +
            "AS PARTITION ${quote(functionName(table))} ALL TO (${quote(storage)});"
        return listOf(function, scheme)
    }

    /**
     * Die Grenzwerte einer Partitionierung, in aufsteigender Reihenfolge.
     *
     * Das Modell beschreibt jede Partition mit `from`/`to`; SQL Server will die
     * Schnittpunkte. Aus n Partitionen werden n-1 Grenzen -- die obere Grenze
     * jeder Partition ausser der letzten, deren `to` `MAXVALUE` ist.
     *
     * Zeichenketten-Literale bekommen das `N`-Praefix: das Modell traegt sie in
     * PostgreSQL-Form (`'…'`), und ohne `N` vergleicht SQL Server sie in der
     * Codepage der Datenbank statt in Unicode.
     */
    fun boundaryLiterals(config: PartitionConfig): List<String> =
        config.partitions
            .mapNotNull { it.to?.firstOrNull() }
            .filterIsInstance<PartitionBound.Value>()
            .map { withUnicodePrefix(it.literal) }

    private fun withUnicodePrefix(literal: String): String =
        if (literal.startsWith("'")) "N$literal" else literal

    /** Ob diese Partitionierung ueberhaupt renderbar ist. */
    fun isRenderable(config: PartitionConfig): Boolean =
        config.type == PartitionType.RANGE && config.partitions.isNotEmpty() && config.key.size == 1
}
