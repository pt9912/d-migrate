package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Die HASH-Emulation gegen echtes SQL Server: angelegt wie der Generate-Pfad
 * sie schreibt, gelesen wie der Reverse sie sieht.
 *
 * SQL Server kennt nur RANGE. Ohne die Wiedererkennung liest der Reverse
 * genau das zurueck — eine RANGE-Partitionierung ueber einer Eimerspalte,
 * die im Soll-Schema gar nicht vorkommt. Soll und Ist wichen dann in
 * Partitionstyp, Schluessel **und** Spaltenbestand ab, und `schema migrate`
 * plante bei jedem Lauf erneut dieselbe Aenderung.
 *
 * Was kein Unit-Test zeigen kann: welche Form der Server aus dem erzeugten
 * Ausdruck macht. Er schreibt ihn um.
 */
class MssqlHashPartitionRoundTripIntegrationTest : FunSpec({

    val container = startMssqlContainer()

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = poolFor(container, "dmigrate_hash_roundtrip")
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun exec(vararg sqls: String) = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
    }

    /**
     * Die Emulation so, wie `MssqlHashPartitionEmulation` sie schreibt. Die
     * `SET`-Optionen sind Pflicht: ohne `QUOTED_IDENTIFIER ON` lehnt der
     * Server eine berechnete Partitionsspalte mit `Msg 1934` ab — dieselbe
     * Praeambel, die `DialectCapabilities.MSSQL_SCRIPT_PREAMBLE` fuehrt.
     */
    beforeSpec {
        exec(
            "SET QUOTED_IDENTIFIER ON",
            "SET ANSI_NULLS ON",
            "CREATE PARTITION FUNCTION pf_orders (INT) AS RANGE RIGHT FOR VALUES (1, 2, 3)",
            "CREATE PARTITION SCHEME ps_orders AS PARTITION pf_orders ALL TO ([PRIMARY])",
            """
            CREATE TABLE orders (
                customer_id INT NOT NULL,
                amount INT NULL,
                dmg_hash_bucket AS (ABS(CHECKSUM(customer_id) % 4)) PERSISTED NOT NULL,
                CONSTRAINT pk_orders PRIMARY KEY (customer_id, dmg_hash_bucket)
            ) ON ps_orders (dmg_hash_bucket)
            """.trimIndent(),
            // Ein gewoehnlicher Index auf der emulierten Tabelle: SQL Server
            // haengt die Partitionsspalte an jeden ausgerichteten Index an.
            "CREATE INDEX ix_orders_amount ON orders(amount)",
            // Gegenprobe: RANGE-partitioniert AUF einer berechneten Spalte
            // desselben Namens, aber mit einem anderen Ausdruck. Nur so ist
            // der Rueckfall wirklich geprueft -- eine unpartitionierte
            // Tabelle scheitert schon an der Partitionsabfrage.
            "CREATE PARTITION FUNCTION pf_decoys (INT) AS RANGE RIGHT FOR VALUES (1, 2, 3)",
            "CREATE PARTITION SCHEME ps_decoys AS PARTITION pf_decoys ALL TO ([PRIMARY])",
            """
            CREATE TABLE decoys (
                id INT NOT NULL,
                dmg_hash_bucket AS (id % 4) PERSISTED NOT NULL
            ) ON ps_decoys (dmg_hash_bucket)
            """.trimIndent(),
        )
    }

    test("the emulated HASH partitioning reads back as HASH, not as RANGE") {
        val table = MssqlSchemaReader().read(pool, SchemaReadOptions()).schema.tables.getValue("orders")
        val partitioning = withClue("keine Partitionierung gelesen") { table.partitioning!! }

        partitioning.type shouldBe PartitionType.HASH
        // Der Fachschluessel, nicht die Eimerspalte.
        partitioning.key shouldContainExactly listOf("customer_id")
        partitioning.partitions.size shouldBe 4
        partitioning.partitions.map { it.remainder } shouldContainExactly listOf(0, 1, 2, 3)
        partitioning.partitions.all { it.modulus == 4 } shouldBe true
    }

    test("the bucket column leaves the model, and the primary key with it") {
        val table = MssqlSchemaReader().read(pool, SchemaReadOptions()).schema.tables.getValue("orders")

        withClue("gelesene Spalten: ${table.columns.keys}") {
            table.columns.keys shouldContainExactly setOf("customer_id", "amount")
        }
        // SQL Server verlangt die Partitionsspalte in jedem eindeutigen
        // Schluessel; im Soll-Schema stand sie nie.
        table.primaryKey shouldContainExactly listOf("customer_id")
    }

    test("SQL Server appends the partition column to an aligned index; it does not reach the model") {
        // Sie kommt als SCHLUESSELspalte mit key_ordinal = 0 zurueck und
        // sortierte damit sogar vor der echten Spalte.
        val table = MssqlSchemaReader().read(pool, SchemaReadOptions()).schema.tables.getValue("orders")
        val index = table.indices.single { it.name == "ix_orders_amount" }
        index.columnNames shouldContainExactly listOf("amount")
    }

    test("a partitioned table whose bucket-shaped column carries a different expression falls back to RANGE") {
        val table = MssqlSchemaReader().read(pool, SchemaReadOptions()).schema.tables.getValue("decoys")

        withClue("der Ausdruck passt nicht -- es darf nicht als Emulation gelten") {
            table.partitioning!!.type shouldBe PartitionType.RANGE
        }
        // Und die Spalte bleibt, was sie ist: eine Spalte des Schemas.
        table.columns.keys shouldContainExactly setOf("id", "dmg_hash_bucket")
    }
})
