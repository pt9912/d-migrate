package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.MssqlHashPartitionMode

/**
 * HASH-Partitionierung fuer SQL Server, nachgebaut ueber eine persistierte
 * berechnete Spalte (Sub-Slice 7d).
 *
 * SQL Server kennt nur RANGE. Der Nachbau haelt den Eimer in einer Spalte und
 * schneidet die RANGE-Funktion an den Eimergrenzen:
 *
 * ```sql
 * CREATE PARTITION FUNCTION pf_t (INT) AS RANGE RIGHT FOR VALUES (1, 2, 3);
 * CREATE PARTITION SCHEME  ps_t AS PARTITION pf_t ALL TO ([PRIMARY]);
 * CREATE TABLE t (
 *     customer_id INT NOT NULL,
 *     dmg_hash_bucket AS (ABS(CHECKSUM(customer_id) % 4)) PERSISTED
 * ) ON ps_t (dmg_hash_bucket);
 * ```
 *
 * Drei Eigenschaften sind am echten Server gemessen und praegen den Aufbau:
 *
 * 1. **Die Spalte muss `PERSISTED` sein.** Ohne das lehnt der Server sie als
 *    Partitionsspalte ab („Computed column cannot be used as a partition key
 *    if it is not persisted").
 * 2. **Jeder eindeutige Index muss die Partitionsspalte enthalten**
 *    („Partition columns for a unique index must be a subset of the index
 *    key"). Der Eimer wandert deshalb in Primaerschluessel und UNIQUE-Keys —
 *    was nur dann semantikerhaltend ist, wenn der Hash-Schluessel im
 *    jeweiligen Schluessel liegt (siehe [Verdict.WeakensUniqueKey]).
 * 3. **`ABS(CHECKSUM(x) % n)`, nicht `ABS(CHECKSUM(x)) % n`.** Erst teilen,
 *    dann Betrag: `CHECKSUM` liefert einen `int`, und `ABS` des kleinsten
 *    `int` laeuft ueber. Nach dem Modulo liegt der Wert in `(-n, n)` und der
 *    Betrag ist immer bildbar.
 */
internal object MssqlHashPartitionEmulation {

    /** Name der berechneten Spalte. Praefix wie bei den uebrigen Hilfsobjekten. */
    const val BUCKET_COLUMN: String = "dmg_hash_bucket"

    /** Der SQL-Typ der Eimerspalte — `CHECKSUM` liefert `int`. */
    const val BUCKET_TYPE: String = "INT"

    /** Das Urteil ueber eine HASH-Partitionierung im Emulationsmodus. */
    sealed interface Verdict {
        /** Emulierbar. [modulus] ist die Anzahl der Eimer. */
        data class Emulatable(val modulus: Int) : Verdict

        /**
         * Die Kinder bilden keinen vollstaendigen, gleichfoermigen Eimersatz:
         * verschiedene Moduli, Luecken oder Dubletten unter den Resten.
         */
        data class NotAUniformBucketSet(val reason: String) : Verdict

        /**
         * Der Eimer muesste in einen eindeutigen Schluessel, dessen Spalten den
         * Hash-Schluessel nicht enthalten. Der Schluessel wuerde dadurch
         * schwaecher: `UNIQUE(a)` wird zu `UNIQUE(a, bucket)`, und zwei Zeilen
         * mit gleichem `a` aber verschiedenem Hash-Wert waeren erlaubt.
         *
         * Liegt der Hash-Schluessel dagegen IM Schluessel, ist der Eimer durch
         * ihn bestimmt, und die erweiterte Form ist exakt gleichwertig.
         */
        data class WeakensUniqueKey(val keyName: String, val keyColumns: List<String>) : Verdict

        /** Der Spaltenname ist schon vergeben. */
        data object BucketColumnNameTaken : Verdict

        /**
         * Eine andere Tabelle verweist per Fremdschluessel auf diese.
         *
         * SQL Server verlangt fuer ein FK-Ziel einen eindeutigen Schluessel
         * ueber **genau** den referenzierten Spalten (Msg 1776). Die Emulation
         * haengt den Eimer an jeden eindeutigen Schluessel — danach gibt es
         * keinen mehr ueber den urspruenglichen Spalten, und jeder eingehende
         * Fremdschluessel bricht. Einen zusaetzlichen Schluessel ohne Eimer
         * anzulegen geht nicht: auch der muesste die Partitionsspalte tragen.
         */
        data class BreaksIncomingForeignKey(val fromTable: String, val constraint: String) : Verdict
    }

    /**
     * Prueft, ob [config] als Eimersatz emulierbar ist, und ob die Tabelle das
     * vertraegt. Reihenfolge ist Absicht: erst die Partitionierung selbst, dann
     * ihre Folgen fuer die Schluessel — ein kaputter Eimersatz soll nicht als
     * Schluesselproblem gemeldet werden.
     */
    fun verdict(
        tableName: String,
        table: TableDefinition,
        config: PartitionConfig,
        schema: SchemaDefinition?,
    ): Verdict {
        require(config.type == PartitionType.HASH) { "verdict() is for HASH partitioning only" }

        val moduli = config.partitions.map { it.modulus }.distinct()
        if (moduli.size != 1 || moduli.single() == null) {
            return Verdict.NotAUniformBucketSet(
                "the child partitions declare ${moduli.size} different modulus values; " +
                    "SQL Server needs one uniform bucket count",
            )
        }
        val modulus = moduli.single()!!
        if (modulus < 2) {
            return Verdict.NotAUniformBucketSet("the modulus is $modulus; at least 2 buckets are needed")
        }
        val remainders = config.partitions.mapNotNull { it.remainder }.toSortedSet()
        if (remainders.size != config.partitions.size || remainders.toList() != (0 until modulus).toList()) {
            return Verdict.NotAUniformBucketSet(
                "the child partitions do not cover the remainders 0..${modulus - 1} exactly once",
            )
        }
        if (table.columns.keys.any { it.equals(BUCKET_COLUMN, ignoreCase = true) }) {
            return Verdict.BucketColumnNameTaken
        }
        require(config.key.isNotEmpty()) { "HASH partitioning needs at least one key column" }
        uniqueKeys(table).forEach { (name, columns) ->
            if (!columns.containsAll(config.key)) {
                return Verdict.WeakensUniqueKey(name, columns)
            }
        }
        incomingForeignKey(tableName, schema)?.let { return it }
        return Verdict.Emulatable(modulus)
    }

    /**
     * Der erste eingehende Fremdschluessel, oder `null`. Geprueft werden beide
     * Formen, in denen das Modell einen FK traegt: der Constraint und die
     * spaltenstaendige `references`-Angabe.
     */
    private fun incomingForeignKey(tableName: String, schema: SchemaDefinition?): Verdict? {
        schema ?: return null
        for ((otherName, other) in schema.tables) {
            if (otherName.equals(tableName, ignoreCase = true)) continue
            other.constraints
                .firstOrNull { it.type == ConstraintType.FOREIGN_KEY && it.references?.table.equalsTable(tableName) }
                ?.let { return Verdict.BreaksIncomingForeignKey(otherName, it.name) }
            other.columns.entries
                .firstOrNull { it.value.references?.table.equalsTable(tableName) }
                ?.let { return Verdict.BreaksIncomingForeignKey(otherName, "$otherName.${it.key}") }
        }
        return null
    }

    private fun String?.equalsTable(other: String): Boolean =
        this != null && (equals(other, ignoreCase = true) || substringAfterLast('.').equals(other, ignoreCase = true))

    /**
     * Die Ausdrucksform der Eimerspalte. `CHECKSUM` nimmt mehrere Argumente,
     * ein mehrspaltiger Hash-Schluessel geht also unveraendert durch.
     */
    fun bucketExpression(config: PartitionConfig, modulus: Int, quote: (String) -> String): String {
        val args = config.key.joinToString(", ") { quote(it) }
        return "ABS(CHECKSUM($args) % $modulus)"
    }

    /**
     * Die Schnittpunkte der RANGE-Funktion: `1 .. modulus-1`. Mit `RANGE RIGHT`
     * ergibt das die Eimer `(-inf,1), [1,2), … , [modulus-1, +inf)` — also genau
     * die Werte `0 .. modulus-1`, die die Spalte annehmen kann.
     */
    fun boundaries(modulus: Int): List<String> = (1 until modulus).map { it.toString() }

    /**
     * Die Tabelle, wie sie mit Eimer aussieht: der Eimer haengt an jedem
     * eindeutigen Schluessel. [verdict] hat vorher geprueft, dass das keinen
     * davon schwaecht — der Eimer ist eine Funktion des Hash-Schluessels, und
     * der liegt in jedem dieser Schluessel, also ist die erweiterte Form
     * gleichwertig.
     *
     * Die berechnete Spalte SELBST steht hier nicht drin: das neutrale Modell
     * kann `AS (…) PERSISTED` nicht ausdruecken, und sie hineinzuluegen hiesse,
     * sie beim naechsten Vergleich als echte Spalte wiederzufinden. Ihre
     * Deklaration liefert [bucketColumnLine] direkt als Zeile.
     */
    fun withBucketInUniqueKeys(tableName: String, table: TableDefinition): TableDefinition = table.copy(
        // Ein spaltenstaendiges `unique` kann den Eimer nicht tragen — es ist
        // per Definition einspaltig. Es wird deshalb zu einem Tabellen-Constraint
        // mit demselben Namen, den der Generator ihm ohnehin gegeben haette.
        columns = table.columns.mapValues { (_, column) ->
            if (column.unique) column.copy(unique = false) else column
        }.let { LinkedHashMap(it) },
        primaryKey = if (table.primaryKey.isEmpty()) table.primaryKey else table.primaryKey + BUCKET_COLUMN,
        constraints = liftedColumnUniques(tableName, table) + table.constraints.map { constraint ->
            if (constraint.type == ConstraintType.UNIQUE && constraint.columns != null) {
                constraint.copy(columns = constraint.columns!! + BUCKET_COLUMN)
            } else {
                constraint
            }
        },
        indices = table.indices.map { index ->
            if (index.unique) index.copy(columns = index.columns + IndexColumn(BUCKET_COLUMN)) else index
        },
    )

    /**
     * Spaltenstaendige `unique`-Marken als Tabellen-Constraints mit Eimer.
     * Der Name folgt [MssqlConstraintNames.unique], damit sich am erzeugten
     * DDL nichts ausser der zusaetzlichen Spalte aendert.
     */
    private fun liftedColumnUniques(tableName: String, table: TableDefinition): List<ConstraintDefinition> =
        table.columns.filter { it.value.unique }.map { (name, _) ->
            ConstraintDefinition(
                name = MssqlConstraintNames.unique(tableName, name),
                type = ConstraintType.UNIQUE,
                columns = listOf(name, BUCKET_COLUMN),
            )
        }

    /** `[dmg_hash_bucket] AS (ABS(CHECKSUM([k]) % 4)) PERSISTED` */
    fun bucketColumnLine(config: PartitionConfig, modulus: Int, quote: (String) -> String): String =
        "${quote(BUCKET_COLUMN)} AS (${bucketExpression(config, modulus, quote)}) PERSISTED"

    /**
     * Alle eindeutigen Schluessel der Tabelle als (Name, Spalten).
     *
     * Das schliesst das **spaltenstaendige** `unique: true` ein — es rendert als
     * einspaltiger UNIQUE-Constraint und ist damit genau so ein eindeutiger
     * Index wie die anderen. Nach einem Round-Trip ist das sogar der Normalfall:
     * der Reverse hebt einspaltige unique Indizes auf dieses Feld.
     */
    private fun uniqueKeys(table: TableDefinition): List<Pair<String, List<String>>> = buildList {
        if (table.primaryKey.isNotEmpty()) add("PRIMARY KEY" to table.primaryKey)
        table.columns.forEach { (name, column) ->
            if (column.unique) add("UNIQUE($name)" to listOf(name))
        }
        // Ein UNIQUE ohne Spalten bindet keinen Schluessel und wird uebergangen.
        // Ein namenloser Index dagegen zaehlt sehr wohl — er bekommt seinen
        // Namen erst beim Rendern, ist aber genauso eindeutig.
        table.constraints
            .filter { it.type == ConstraintType.UNIQUE }
            .forEach { constraint -> constraint.columns?.let { add(constraint.name to it) } }
        table.indices
            .filter { it.unique }
            .forEach { index -> add((index.name ?: "unnamed index") to index.columns.map { it.name }) }
    }
}

/**
 * Der aufgeloeste Emulationsplan einer Tabelle — das, was Generate- und
 * Diff-Pfad gleichermassen brauchen, damit beide dieselbe Entscheidung treffen.
 */
internal data class MssqlHashPartitionPlan(
    /** Die Tabelle mit Eimer in allen eindeutigen Schluesseln. */
    val table: TableDefinition,
    /** Die Deklarationszeile der berechneten Spalte. */
    val bucketLine: String,
    /** Die Grenzen der RANGE-Funktion. */
    val boundaries: List<String>,
    val modulus: Int,
)

/**
 * Loest den Emulationsplan auf, oder sagt mit einer Note, warum nicht.
 *
 * Gibt `null` zurueck, wenn die Tabelle gar nicht HASH-partitioniert ist —
 * dann ist nichts zu tun und auch nichts zu melden.
 */
internal fun resolveHashPartitionPlan(
    tableName: String,
    table: TableDefinition,
    mode: MssqlHashPartitionMode,
    quote: (String) -> String,
    schema: SchemaDefinition? = null,
): MssqlHashPartitionOutcome? {
    val config = table.partitioning?.takeIf { it.type == PartitionType.HASH } ?: return null
    if (mode == MssqlHashPartitionMode.ACTION_REQUIRED) {
        return MssqlHashPartitionOutcome.ModeNotEnabled
    }
    return when (val verdict = MssqlHashPartitionEmulation.verdict(tableName, table, config, schema)) {
        is MssqlHashPartitionEmulation.Verdict.Emulatable -> MssqlHashPartitionOutcome.Planned(
            MssqlHashPartitionPlan(
                table = MssqlHashPartitionEmulation.withBucketInUniqueKeys(tableName, table),
                bucketLine = MssqlHashPartitionEmulation.bucketColumnLine(config, verdict.modulus, quote),
                boundaries = MssqlHashPartitionEmulation.boundaries(verdict.modulus),
                modulus = verdict.modulus,
            ),
        )
        is MssqlHashPartitionEmulation.Verdict.NotAUniformBucketSet -> MssqlHashPartitionOutcome.Refused(
            code = "E068",
            reason = "HASH partitioning of table '$tableName' cannot be emulated: ${verdict.reason}.",
            hint = "Declare one partition per bucket with the same modulus and the remainders 0..n-1.",
        )
        is MssqlHashPartitionEmulation.Verdict.WeakensUniqueKey -> MssqlHashPartitionOutcome.Refused(
            code = "E067",
            reason = "HASH partitioning of table '$tableName' cannot be emulated: SQL Server requires the " +
                "partitioning column to be part of every unique index, but '${verdict.keyName}' " +
                "(${verdict.keyColumns.joinToString(", ")}) does not contain the hash key " +
                "(${config.key.joinToString(", ")}). Adding the bucket would weaken that key.",
            hint = "Include the hash key in the unique key, or partition by a column the key already contains.",
        )
        is MssqlHashPartitionEmulation.Verdict.BreaksIncomingForeignKey -> MssqlHashPartitionOutcome.Refused(
            code = "E069",
            reason = "HASH partitioning of table '$tableName' cannot be emulated: table " +
                "'${verdict.fromTable}' references it (${verdict.constraint}), and SQL Server requires the " +
                "referenced key to cover exactly the referenced columns. The emulation adds the bucket " +
                "column to every unique key, which leaves no such key.",
            hint = "Drop the incoming foreign key, or partition a table nothing references.",
        )
        MssqlHashPartitionEmulation.Verdict.BucketColumnNameTaken -> MssqlHashPartitionOutcome.Refused(
            code = "E068",
            reason = "HASH partitioning of table '$tableName' cannot be emulated: the emulation needs a " +
                "column named '${MssqlHashPartitionEmulation.BUCKET_COLUMN}', and the table already has one.",
            hint = "Rename the existing column.",
        )
    }
}

internal sealed interface MssqlHashPartitionOutcome {
    /** Der Emulationsmodus ist nicht eingeschaltet — heutiges E055-Verhalten. */
    data object ModeNotEnabled : MssqlHashPartitionOutcome

    data class Planned(val plan: MssqlHashPartitionPlan) : MssqlHashPartitionOutcome

    data class Refused(val code: String, val reason: String, val hint: String) : MssqlHashPartitionOutcome
}
