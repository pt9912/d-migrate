package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager

/**
 * Proves the MSSQL neutral-type projection against a REAL SQL Server instead
 * of against a second hand-written flattening table.
 *
 * For every neutral type the test renders the column the generator would
 * write, creates it, reads it back with [MssqlSchemaReader], and asserts the
 * result equals `typeCanonicalizer().canonicalize(type)`. That is the whole
 * contract of the v7 post-compare fingerprint: what the canonicaliser claims
 * the target stores must be what the target actually gives back — otherwise
 * a lossless round trip would report drift (or, worse, a lossy one would not).
 */
class MssqlNeutralTypeCanonicalizerIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2025-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    val typeMapper = MssqlTypeMapper()
    val canon = MssqlDriver().typeCanonicalizer()

    /** Die Spalte, die der Generator schreibt — Enums rendert der Spalten-Helfer. */
    fun renderedColumnType(type: NeutralType): String {
        val enumValues = (type as? NeutralType.Enum)?.values
        return if (enumValues != null) {
            typeMapper.unicodeText(MssqlTypeMapper.enumWidth(enumValues))
        } else {
            typeMapper.toSql(type)
        }
    }

    val probes: List<NeutralType> = listOf(
        NeutralType.Integer,
        NeutralType.SmallInt,
        NeutralType.BigInteger,
        NeutralType.BooleanType,
        NeutralType.Date,
        NeutralType.Time,
        NeutralType.Uuid,
        NeutralType.Xml,
        NeutralType.Binary,
        NeutralType.DateTime(),
        NeutralType.DateTime(timezone = true),
        NeutralType.Float(FloatPrecision.SINGLE),
        NeutralType.Float(FloatPrecision.DOUBLE),
        NeutralType.Decimal(10, 2),
        NeutralType.Decimal(50, 4),
        NeutralType.Text(),
        NeutralType.Text(maxLength = 255),
        NeutralType.Text(maxLength = 5000),
        NeutralType.Char(length = 10),
        NeutralType.Char(length = 5000),
        NeutralType.Identifier(),
        NeutralType.Identifier(autoIncrement = true),
        NeutralType.Json,
        NeutralType.Array(elementType = "text"),
        NeutralType.FullText,
        NeutralType.Email,
        NeutralType.Enum(values = listOf("red", "green")),
        NeutralType.Geometry(GeometryType.of("point"), srid = 4326),
        NeutralType.Geometry(GeometryType.of("polygon"), srid = 4258),
        NeutralType.Geometry(GeometryType.of("point"), srid = 3857),
    )

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE dmigrate_canon") }
        }
        config = ConnectionConfig(
            dialect = DatabaseDialect.MSSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_canon",
            user = container.username,
            password = container.password,
            ssl = SslSettings(SslMode.DISABLE),
        )
    }

    afterSpec { container.stop() }

    test("the canonical projection equals the real SQL Server round trip") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            for (type in probes) {
                val rendered = renderedColumnType(type)
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("IF OBJECT_ID('probe', 'U') IS NOT NULL DROP TABLE probe")
                        stmt.execute("CREATE TABLE probe (val $rendered)")
                    }
                }
                val reversed = MssqlSchemaReader().read(pool)
                    .schema.tables.getValue("probe").columns.getValue("val").type
                withClue("$type rendered as $rendered") {
                    reversed shouldBe canon.canonicalize(type)
                }
            }
        }
    }

    test("a refType the schema cannot resolve to values projects like the column really renders") {
        // Der schmale Fall: `refType` gesetzt, aber weder der aufgeloeste
        // Custom Type noch der Typ selbst liefern eine Werteliste. Der
        // Spalten-Helfer faellt dann auf `plainColumn` zurueck -- ungebundenes
        // NVARCHAR(MAX) -- und der Reverse liest `Text(null)`. Blieb die
        // Projektion bei `Enum(refType = …)` stehen, meldete der Post-Compare
        // Drift fuer eine Migration, die genau das Verlangte getan hat.
        val composite = CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE)
        val cases = mapOf(
            "on_composite" to NeutralType.Enum(refType = "addr"),
            "on_missing" to NeutralType.Enum(refType = "nowhere"),
        )
        val schema = SchemaDefinition(
            name = "canon", version = "1",
            customTypes = mapOf("addr" to composite),
            tables = mapOf(
                "ref_probe" to TableDefinition(
                    columns = cases.mapValues { (_, type) -> ColumnDefinition(type = type) },
                ),
            ),
        )

        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("IF OBJECT_ID('ref_probe', 'U') IS NOT NULL DROP TABLE ref_probe")
                    MssqlDdlGenerator().generate(schema).statements
                        .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
                        .map { it.joinToString("\n").trim().removeSuffix(";") }
                        .filter { it.isNotBlank() && !it.equals("GO", ignoreCase = true) }
                        .forEach { sql -> withClue("statement failed:\n$sql") { stmt.execute(sql) } }
                }
            }
            val reversed = MssqlSchemaReader().read(pool).schema.tables.getValue("ref_probe").columns
            for ((column, type) in cases) {
                withClue(column) {
                    reversed.getValue(column).type shouldBe canon.canonicalize(type, schema.customTypes)
                }
            }
        }
    }
})
