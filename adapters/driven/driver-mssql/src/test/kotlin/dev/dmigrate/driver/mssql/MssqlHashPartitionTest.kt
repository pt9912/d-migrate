package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MssqlHashPartitionMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Sub-Slice 7d: HASH-Partitionierung ueber eine persistierte berechnete Spalte.
 *
 * Die Bruchpunkte sind am echten Server gemessen und stehen deshalb hier fest:
 * die Spalte muss `PERSISTED` sein, und **jeder eindeutige Index muss die
 * Partitionsspalte enthalten** („Partition columns for a unique index must be a
 * subset of the index key"). Aus dem zweiten folgt der ganze Rest.
 */
class MssqlHashPartitionTest : FunSpec({

    val generator = MssqlDdlGenerator()

    fun hashOn(key: String, buckets: Int) = PartitionConfig(
        type = PartitionType.HASH,
        key = listOf(key),
        partitions = (0 until buckets).map {
            PartitionDefinition(name = "p$it", modulus = buckets, remainder = it)
        },
    )

    fun table(
        partitioning: PartitionConfig?,
        primaryKey: List<String> = emptyList(),
        constraints: List<ConstraintDefinition> = emptyList(),
        indices: List<IndexDefinition> = emptyList(),
    ) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "customer_id" to ColumnDefinition(NeutralType.Integer, required = true),
        ),
        primaryKey = primaryKey,
        constraints = constraints,
        indices = indices,
        partitioning = partitioning,
    )

    fun render(t: TableDefinition, mode: MssqlHashPartitionMode) = generator.generate(
        SchemaDefinition(name = "App", version = "1", tables = mapOf("events" to t)),
        DdlGenerationOptions(dialectContext = DdlDialectContext.MsSql(hashPartitionMode = mode)),
    )

    test("without the mode the behaviour is unchanged: E055, plain table") {
        val result = render(table(hashOn("customer_id", 4)), MssqlHashPartitionMode.ACTION_REQUIRED)
        val ddl = result.render()

        ddl shouldNotContain "PARTITION FUNCTION"
        ddl shouldContain "E055"
    }

    test("the emulation renders a persisted computed column and a RANGE function over the buckets") {
        val ddl = render(table(hashOn("customer_id", 4)), MssqlHashPartitionMode.COMPUTED_COLUMN).render()

        // Erst teilen, dann Betrag — ABS des kleinsten int liefe ueber.
        ddl shouldContain "[dmg_hash_bucket] AS (ABS(CHECKSUM([customer_id]) % 4)) PERSISTED"
        // Vier Eimer, drei Schnittpunkte.
        ddl shouldContain "CREATE PARTITION FUNCTION [pf_events] (INT) AS RANGE RIGHT FOR VALUES (1, 2, 3);"
        ddl shouldContain ") ON [ps_events] ([dmg_hash_bucket]);"
    }

    test("the emulation is reported as W145 — the bucket assignment is not the source's") {
        val result = render(table(hashOn("customer_id", 4)), MssqlHashPartitionMode.COMPUTED_COLUMN)
        result.render() shouldContain "W145"
    }

    // Gemessen: „Partition columns for a unique index must be a subset of the
    // index key." Der Eimer MUSS also in den Schluessel.
    test("the bucket joins the primary key when the hash key is part of it") {
        val t = table(hashOn("customer_id", 4), primaryKey = listOf("id", "customer_id"))
        val ddl = render(t, MssqlHashPartitionMode.COMPUTED_COLUMN).render()

        ddl shouldContain "PRIMARY KEY ([id], [customer_id], [dmg_hash_bucket])"
    }

    // Der Eimer ist eine Funktion des Hash-Schluessels. Liegt der im Schluessel,
    // ist die erweiterte Form gleichwertig; liegt er ausserhalb, wird der
    // Schluessel schwaecher — und das darf nicht still passieren.
    test("a primary key that does not contain the hash key refuses with E067") {
        val t = table(hashOn("customer_id", 4), primaryKey = listOf("id"))
        val ddl = render(t, MssqlHashPartitionMode.COMPUTED_COLUMN).render()

        ddl shouldContain "E067"
        ddl shouldContain "would weaken that key"
        ddl shouldNotContain "PARTITION FUNCTION"
    }

    test("the same rule applies to a UNIQUE constraint, not just the primary key") {
        val t = table(
            hashOn("customer_id", 4),
            constraints = listOf(
                ConstraintDefinition(name = "uq_id", type = ConstraintType.UNIQUE, columns = listOf("id")),
            ),
        )
        render(t, MssqlHashPartitionMode.COMPUTED_COLUMN).render() shouldContain "E067"
    }

    test("and to a unique index") {
        val t = table(
            hashOn("customer_id", 4),
            indices = listOf(
                IndexDefinition(name = "ux_id", columns = listOf(IndexColumn("id")), unique = true),
            ),
        )
        render(t, MssqlHashPartitionMode.COMPUTED_COLUMN).render() shouldContain "E067"
    }

    test("a non-unique index is irrelevant to the rule") {
        val t = table(
            hashOn("customer_id", 4),
            indices = listOf(IndexDefinition(name = "ix_id", columns = listOf(IndexColumn("id")))),
        )
        render(t, MssqlHashPartitionMode.COMPUTED_COLUMN).render() shouldContain "PARTITION FUNCTION"
    }

    test("a bucket set with mixed moduli refuses with E068") {
        val uneven = PartitionConfig(
            type = PartitionType.HASH,
            key = listOf("customer_id"),
            partitions = listOf(
                PartitionDefinition(name = "p0", modulus = 4, remainder = 0),
                PartitionDefinition(name = "p1", modulus = 8, remainder = 1),
            ),
        )
        render(table(uneven), MssqlHashPartitionMode.COMPUTED_COLUMN).render() shouldContain "E068"
    }

    test("a bucket set with a gap refuses with E068") {
        val gapped = PartitionConfig(
            type = PartitionType.HASH,
            key = listOf("customer_id"),
            partitions = listOf(
                PartitionDefinition(name = "p0", modulus = 4, remainder = 0),
                PartitionDefinition(name = "p2", modulus = 4, remainder = 2),
            ),
        )
        render(table(gapped), MssqlHashPartitionMode.COMPUTED_COLUMN).render() shouldContain "E068"
    }

    test("a table that already owns the bucket column name refuses") {
        val t = TableDefinition(
            columns = linkedMapOf(
                "customer_id" to ColumnDefinition(NeutralType.Integer, required = true),
                "dmg_hash_bucket" to ColumnDefinition(NeutralType.Integer),
            ),
            partitioning = hashOn("customer_id", 4),
        )
        render(t, MssqlHashPartitionMode.COMPUTED_COLUMN).render() shouldContain "E068"
    }

    test("a multi-column hash key goes through CHECKSUM unchanged") {
        val config = PartitionConfig(
            type = PartitionType.HASH,
            key = listOf("id", "customer_id"),
            partitions = (0 until 2).map { PartitionDefinition(name = "p$it", modulus = 2, remainder = it) },
        )
        render(table(config), MssqlHashPartitionMode.COMPUTED_COLUMN).render() shouldContain
            "ABS(CHECKSUM([id], [customer_id]) % 2)"
    }

    test("RANGE partitioning is untouched by the mode") {
        val ranged = PartitionConfig(
            type = PartitionType.RANGE,
            key = listOf("id"),
            partitions = listOf(
                PartitionDefinition(name = "p_low", to = listOf(dev.dmigrate.core.model.PartitionBound.Value("100"))),
            ),
        )
        val ddl = render(table(ranged), MssqlHashPartitionMode.COMPUTED_COLUMN).render()

        ddl shouldContain ") ON [ps_events] ([id]);"
        ddl shouldNotContain "dmg_hash_bucket"
    }

    test("the modulus must be at least two") {
        val single = PartitionConfig(
            type = PartitionType.HASH,
            key = listOf("customer_id"),
            partitions = listOf(PartitionDefinition(name = "p0", modulus = 1, remainder = 0)),
        )
        render(table(single), MssqlHashPartitionMode.COMPUTED_COLUMN).render() shouldContain "E068"
    }

    // D1: die Indizes kommen aus der Schema-Tabelle, nicht aus der emulierten.
    // Ohne erneutes Aufloesen entstuende ein CREATE UNIQUE INDEX OHNE
    // Partitionsspalte auf einer Tabelle, die an ihr haengt.
    test("a unique index carries the bucket in the emitted CREATE INDEX") {
        val t = table(
            hashOn("customer_id", 4),
            indices = listOf(
                IndexDefinition(
                    name = "ux_cust",
                    columns = listOf(IndexColumn("customer_id")),
                    unique = true,
                ),
            ),
        )
        val ddl = render(t, MssqlHashPartitionMode.COMPUTED_COLUMN).render()

        ddl shouldContain "CREATE UNIQUE INDEX [ux_cust] ON [events] ([customer_id], [dmg_hash_bucket]);"
    }

    // D2: `unique: true` an der Spalte ist ein eindeutiger Schluessel wie jeder
    // andere — und nach einem Round-Trip sogar der Normalfall, weil der Reverse
    // einspaltige unique Indizes darauf hebt.
    test("a column-level unique on the hash key becomes a table constraint with the bucket") {
        val t = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "customer_id" to ColumnDefinition(NeutralType.Integer, required = true, unique = true),
            ),
            partitioning = hashOn("customer_id", 4),
        )
        val ddl = render(t, MssqlHashPartitionMode.COMPUTED_COLUMN).render()

        ddl shouldContain "CONSTRAINT [uq_events_customer_id] UNIQUE ([customer_id], [dmg_hash_bucket])"
    }

    test("a column-level unique on another column refuses with E067") {
        val t = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true, unique = true),
                "customer_id" to ColumnDefinition(NeutralType.Integer, required = true),
            ),
            partitioning = hashOn("customer_id", 4),
        )
        render(t, MssqlHashPartitionMode.COMPUTED_COLUMN).render() shouldContain "E067"
    }

    // D5: SQL Server verlangt fuer ein FK-Ziel einen eindeutigen Schluessel ueber
    // GENAU den referenzierten Spalten (Msg 1776). Der Eimer nimmt den weg.
    test("an incoming foreign key refuses with E069") {
        val events = table(hashOn("customer_id", 4), primaryKey = listOf("id", "customer_id"))
        val child = TableDefinition(
            columns = linkedMapOf(
                "event_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = dev.dmigrate.core.model.ReferenceDefinition(table = "events", column = "id"),
                ),
            ),
        )
        val result = generator.generate(
            SchemaDefinition(
                name = "App", version = "1",
                tables = mapOf("events" to events, "child" to child),
            ),
            DdlGenerationOptions(
                dialectContext = DdlDialectContext.MsSql(
                    hashPartitionMode = MssqlHashPartitionMode.COMPUTED_COLUMN,
                ),
            ),
        )

        result.render() shouldContain "E069"
        result.render() shouldNotContain "dmg_hash_bucket"
    }

    test("boundaries are one fewer than the buckets") {
        MssqlHashPartitionEmulation.boundaries(4) shouldBe listOf("1", "2", "3")
    }
})
