package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import io.kotest.core.spec.style.FunSpec
import org.testcontainers.mysql.MySQLContainer

/**
 * I-07 — Live verification that the partition/PK fixes produce DDL MySQL
 * actually accepts (string-order unit tests cannot prove grammar acceptance):
 *
 * - Partition options follow table options (`ENGINE=…` before `PARTITION BY`).
 * - An AUTO_INCREMENT column not declared first in a composite PRIMARY KEY is
 *   reordered to the front, avoiding `ERROR 1075`.
 */
class MysqlPartitionPkDdlIntegrationTest : FunSpec({

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var pool: ConnectionPool? = null
    val generator = MysqlDdlGenerator()

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MYSQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
                params = mapOf("allowPublicKeyRetrieval" to "true"),
            )
        )
    }

    afterSpec {
        pool?.close()
        container.stop()
    }

    fun executeDdl(schema: SchemaDefinition) {
        val ddl = generator.generate(schema)
        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                for (s in ddl.statements) {
                    if (s.sql.isNotBlank()) stmt.execute(s.sql)
                }
            }
        }
    }

    test("RANGE-partitioned table with reordered AUTO_INCREMENT PK is accepted by MySQL (I-07)") {
        // PK lists region_id before the AUTO_INCREMENT id → must be reordered so
        // id leads (ERROR 1075). region_id is the partition key and stays in the PK.
        val schema = SchemaDefinition(
            name = "s", version = "1.0",
            tables = mapOf(
                "events" to TableDefinition(
                    columns = linkedMapOf(
                        "region_id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                        "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                    ),
                    primaryKey = listOf("region_id", "id"),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("region_id"),
                        partitions = listOf(
                            PartitionDefinition(name = "p_low", to = "10"),
                            PartitionDefinition(name = "p_mid", to = "20"),
                            PartitionDefinition(name = "p_max", to = "MAXVALUE"),
                        ),
                    ),
                ),
            ),
        )
        executeDdl(schema) // must not throw — MySQL accepts the generated DDL
    }

    test("composite PK with non-leading AUTO_INCREMENT (no partitioning) is accepted by MySQL (I-07)") {
        val schema = SchemaDefinition(
            name = "s", version = "1.0",
            tables = mapOf(
                "line_items" to TableDefinition(
                    columns = linkedMapOf(
                        "order_id" to ColumnDefinition(type = NeutralType.BigInteger, required = true),
                        "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                    ),
                    primaryKey = listOf("order_id", "id"),
                ),
            ),
        )
        executeDdl(schema) // without the reorder this fails with ERROR 1075
    }

    test("prefix index DDL (col(n)) on a TEXT column is accepted by MySQL (I-08)") {
        val schema = SchemaDefinition(
            name = "s", version = "1.0",
            tables = mapOf(
                "docs" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                        "body" to ColumnDefinition(type = NeutralType.Text(), required = true),
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_docs_body",
                            columns = listOf(IndexColumn("body", prefixLength = 100)),
                        ),
                    ),
                ),
            ),
        )
        executeDdl(schema) // `body`(100) prefix index — without the length this fails with ERROR 1170
    }
})
