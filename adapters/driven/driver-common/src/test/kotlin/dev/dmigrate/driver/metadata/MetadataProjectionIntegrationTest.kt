package dev.dmigrate.driver.metadata

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.sql.DriverManager

/**
 * Integration test proving that all metadata projection types work
 * with real JDBC metadata queries against SQLite in-memory.
 *
 * This demonstrates the full pipeline: JDBC query → JdbcMetadataSession
 * → typed projection — the same pattern that Phase D SchemaReader
 * implementations will use.
 */
class MetadataProjectionIntegrationTest : FunSpec({

    fun withDb(block: (JdbcMetadataSession) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE customers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        email TEXT UNIQUE
                    )
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE orders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        customer_id INTEGER NOT NULL,
                        total REAL DEFAULT 0.0,
                        FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX idx_orders_customer ON orders (customer_id)")
                stmt.execute("CREATE UNIQUE INDEX idx_customers_email ON customers (email)")
            }
            block(JdbcMetadataSession(conn))
        }
    }

    // ── TableRef ────────────────────────────────

    test("TableRef projection from sqlite_master") {
        withDb { session ->
            val rows = session.queryList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            )
            val tables = rows.map { TableRef(name = it["name"] as String) }
            tables shouldHaveSize 2
            tables[0].name shouldBe "customers"
            tables[1].name shouldBe "orders"
        }
    }

    // ── ColumnProjection ────────────────────────

    test("ColumnProjection from PRAGMA table_info") {
        withDb { session ->
            val rows = session.queryList("PRAGMA table_info('customers')")
            val columns = rows.map { row ->
                ColumnProjection(
                    name = row["name"] as String,
                    dataType = row["type"] as String,
                    isNullable = (row["notnull"] as Number).toInt() == 0,
                    columnDefault = row["dflt_value"]?.toString(),
                    ordinalPosition = (row["cid"] as Number).toInt(),
                    isAutoIncrement = (row["pk"] as Number).toInt() == 1 &&
                        (row["type"] as String).equals("INTEGER", ignoreCase = true),
                )
            }
            columns shouldHaveSize 3
            columns[0].name shouldBe "id"
            columns[0].dataType shouldBe "INTEGER"
            columns[0].isAutoIncrement shouldBe true
            columns[1].name shouldBe "name"
            columns[1].isNullable shouldBe false
            columns[2].name shouldBe "email"
            columns[2].isNullable shouldBe true
        }
    }

    // ── PrimaryKeyProjection ────────────────────

    test("PrimaryKeyProjection from PRAGMA table_info") {
        withDb { session ->
            val rows = session.queryList("PRAGMA table_info('customers')")
            val pkCols = rows.filter { (it["pk"] as Number).toInt() > 0 }
                .sortedBy { (it["pk"] as Number).toInt() }
                .map { it["name"] as String }
            val pk = PrimaryKeyProjection(columns = pkCols)
            pk.columns shouldBe listOf("id")
        }
    }

    // ── ForeignKeyProjection ────────────────────

    test("ForeignKeyProjection from PRAGMA foreign_key_list") {
        withDb { session ->
            val rows = session.queryList("PRAGMA foreign_key_list('orders')")
            val fks = rows.groupBy { it["id"] as Number }.map { (_, fkRows) ->
                val first = fkRows.first()
                ForeignKeyProjection(
                    name = "fk_${first["id"]}",
                    columns = fkRows.sortedBy { (it["seq"] as Number).toInt() }
                        .map { it["from"] as String },
                    referencedTable = first["table"] as String,
                    referencedColumns = fkRows.sortedBy { (it["seq"] as Number).toInt() }
                        .map { it["to"] as String },
                    onDelete = first["on_delete"]?.toString()?.takeIf { it != "NO ACTION" },
                    onUpdate = first["on_update"]?.toString()?.takeIf { it != "NO ACTION" },
                )
            }
            fks shouldHaveSize 1
            fks[0].columns shouldBe listOf("customer_id")
            fks[0].referencedTable shouldBe "customers"
            fks[0].referencedColumns shouldBe listOf("id")
            fks[0].onDelete shouldBe "CASCADE"
        }
    }

    // ── IndexProjection ─────────────────────────

    test("IndexProjection from PRAGMA index_list + index_info") {
        withDb { session ->
            val indexRows = session.queryList("PRAGMA index_list('orders')")
            val indices = indexRows.map { idx ->
                val indexName = idx["name"] as String
                val colRows = session.queryList("PRAGMA index_info('$indexName')")
                IndexProjection(
                    name = indexName,
                    columns = colRows.sortedBy { (it["seqno"] as Number).toInt() }
                        .map { it["name"] as String },
                    isUnique = (idx["unique"] as Number).toInt() == 1,
                )
            }
            indices shouldHaveSize 1
            indices[0].name shouldBe "idx_orders_customer"
            indices[0].columns shouldBe listOf("customer_id")
            indices[0].isUnique shouldBe false
        }
    }

    // ── ConstraintProjection ────────────────────

    test("ConstraintProjection for UNIQUE constraint from index") {
        withDb { session ->
            val indexRows = session.queryList("PRAGMA index_list('customers')")
            val uniqueIndices = indexRows.filter { (it["unique"] as Number).toInt() == 1 }
            val constraints = uniqueIndices.mapNotNull { idx ->
                val indexName = idx["name"] as String
                val colRows = session.queryList("PRAGMA index_info('$indexName')")
                // SQLite autoindex entries may have empty column info
                val cols = colRows.sortedBy { (it["seqno"] as Number).toInt() }
                    .mapNotNull { it["name"] as? String }
                if (cols.isEmpty()) return@mapNotNull null
                ConstraintProjection(
                    name = indexName,
                    type = "UNIQUE",
                    columns = cols,
                )
            }
            // At least one UNIQUE constraint on email column
            val emailConstraint = constraints.find { "email" in (it.columns ?: emptyList()) }
            emailConstraint shouldBe ConstraintProjection(
                name = emailConstraint!!.name,
                type = "UNIQUE",
                columns = listOf("email"),
            )
        }
    }

    // ── Full pipeline: session → projections ────

    test("full pipeline: all projections from single session") {
        withDb { session ->
            // Tables
            val tableRows = session.queryList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            )
            val tables = tableRows.map { TableRef(name = it["name"] as String) }
            tables.map { it.name } shouldBe listOf("customers", "orders")

            // Columns for 'orders'
            val colRows = session.queryList("PRAGMA table_info('orders')")
            val cols = colRows.map { ColumnProjection(
                name = it["name"] as String,
                dataType = it["type"] as String,
                isNullable = (it["notnull"] as Number).toInt() == 0,
                columnDefault = it["dflt_value"]?.toString(),
                ordinalPosition = (it["cid"] as Number).toInt(),
            ) }
            cols shouldHaveSize 3

            // PK for 'orders'
            val pkCols = colRows.filter { (it["pk"] as Number).toInt() > 0 }
                .map { it["name"] as String }
            PrimaryKeyProjection(pkCols).columns shouldBe listOf("id")

            // FK for 'orders'
            val fkRows = session.queryList("PRAGMA foreign_key_list('orders')")
            fkRows shouldHaveSize 1

            // Indices for 'orders'
            val idxRows = session.queryList("PRAGMA index_list('orders')")
            idxRows shouldHaveSize 1
        }
    }
})
