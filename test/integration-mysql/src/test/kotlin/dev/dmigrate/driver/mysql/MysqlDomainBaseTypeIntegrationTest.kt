package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer

/**
 * Eine Domain-Spalte auf MySQL: das erzeugte DDL fuer jeden Basistyp wird
 * ausgefuehrt (Unit-Tests belegen die Grammatik nicht), und der gelesene
 * `column_type` ist der erwartete -- auch fuer die E053-Ersatzspalte `TEXT`.
 */
class MysqlDomainBaseTypeIntegrationTest : FunSpec({

    val container = MySQLContainer(TestImages.MYSQL)
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var pool: ConnectionPool? = null

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
            ),
        )
    }

    afterSpec {
        pool?.close()
        container.stop()
    }

    // Basistyp, precision, scale -> column_type, den MySQL liest.
    data class Row(val baseType: String, val precision: Int?, val scale: Int?, val columnType: String)

    val rows = listOf(
        Row("integer", null, null, "int"),
        Row("int4", null, null, "int"),
        Row("bigint", null, null, "bigint"),
        Row("int8", null, null, "bigint"),
        Row("smallint", null, null, "smallint"),
        Row("bool", null, null, "tinyint(1)"),
        Row("boolean", null, null, "tinyint(1)"),
        Row("real", null, null, "float"),
        Row("float8", null, null, "double"),
        Row("double precision", null, null, "double"),
        Row("numeric", 10, 2, "decimal(10,2)"),
        Row("decimal", 5, null, "decimal(5,0)"),
        Row("text", null, null, "text"),
        Row("character varying", null, null, "text"),
        Row("varchar", 50, null, "varchar(50)"),
        Row("char", null, null, "char(1)"),
        Row("uuid", null, null, "char(36)"),
        Row("jsonb", null, null, "json"),
        Row("xml", null, null, "text"),
        Row("bytea", null, null, "blob"),
        Row("date", null, null, "date"),
        Row("time without time zone", null, null, "time"),
        Row("timestamp without time zone", null, null, "datetime"),
        Row("timestamptz", null, null, "datetime"),
        Row("timestamptz(3)", null, null, "datetime"),
        // Ohne Gegenstueck: E053 mit der Ersatzspalte TEXT.
        Row("numeric", null, null, "text"),
        Row("bit", null, null, "text"),
        Row("bit(3)", null, null, "text"),
        Row("integer[]", null, null, "text"),
        Row("varbit(7)", null, null, "text"),
        Row("timetz", null, null, "text"),
        Row("bpchar", null, null, "text"),
        Row("money", null, null, "text"),
        Row("interval", null, null, "text"),
        Row("inet", null, null, "text"),
    )

    test("das DDL jedes Domain-Basistyps wird von MySQL angenommen und ergibt den erwarteten Spaltentyp") {
        val tables = rows.indices.associate {
            "dom_$it" to TableDefinition(columns = linkedMapOf("c" to domainColumn(it)))
        }
        val schema = SchemaDefinition(
            name = "s", version = "1.0", tables = tables,
            customTypes = rows.withIndex().associate { (i, r) ->
                "d_$i" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN, baseType = r.baseType, precision = r.precision, scale = r.scale,
                )
            },
        )
        val ddl = MysqlDdlGenerator().generate(schema)
        pool!!.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                for (s in ddl.statements) if (s.sql.isNotBlank()) stmt.execute(s.sql)
            }
            val actual = rows.indices.associate { i ->
                conn.prepareStatement(
                    "SELECT column_type FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = 'c'",
                ).use { ps ->
                    ps.setString(1, "dom_$i")
                    ps.executeQuery().use { rs -> rs.next(); i to rs.getString(1) }
                }
            }
            val expected = rows.indices.associate { i -> i to rows[i].columnType }
            actual shouldBe expected
        }
    }
})

private fun domainColumn(index: Int) = ColumnDefinition(type = NeutralType.Enum(refType = "d_$index"))
