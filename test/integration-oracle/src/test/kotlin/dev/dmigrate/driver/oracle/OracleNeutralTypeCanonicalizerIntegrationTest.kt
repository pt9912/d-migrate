package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Belegt die Oracle-Projektion gegen ein ECHTES Oracle statt gegen eine
 * zweite handgeschriebene Abflachungstabelle -- analog
 * `MssqlNeutralTypeCanonicalizerIntegrationTest`, hier zusaetzlich
 * motiviert dadurch, dass Slice 3 bereits zweimal eine angenommene
 * Oracle-Eigenschaft live widerlegt hat (`OVERRIDING SYSTEM VALUE`,
 * `ALTER SEQUENCE` auf Identity-Sequenzen). Fuer jeden Sondentyp wird die
 * Spalte gerendert, die der Generator schreiben wuerde, angelegt, mit
 * [OracleSchemaReader] zurueckgelesen und gegen
 * `typeCanonicalizer().canonicalize(type)` verglichen.
 */
class OracleNeutralTypeCanonicalizerIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))

    val typeMapper = OracleTypeMapper()
    val canon = OracleDriver().typeCanonicalizer()

    /** Die Spalte, die der Generator schreibt -- Enums rendert der Spalten-Helfer. */
    fun renderedColumnType(type: NeutralType): String {
        val enumValues = (type as? NeutralType.Enum)?.values
        return if (enumValues != null) {
            "VARCHAR2(${OracleTypeMapper.enumWidth(enumValues)})"
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
        NeutralType.Json,
        NeutralType.Xml,
        NeutralType.Binary,
        NeutralType.DateTime(),
        NeutralType.DateTime(timezone = true),
        NeutralType.Float(FloatPrecision.SINGLE),
        NeutralType.Float(FloatPrecision.DOUBLE),
        NeutralType.Decimal(10, 2),
        NeutralType.Decimal(1, 0),
        NeutralType.Decimal(3, 0),
        NeutralType.Decimal(10, 0),
        NeutralType.Decimal(50, 4),
        NeutralType.Text(maxLength = null),
        NeutralType.Text(maxLength = 255),
        NeutralType.Text(maxLength = 4001),
        NeutralType.Char(length = 10),
        NeutralType.Char(length = 2001),
        NeutralType.Identifier(),
        NeutralType.Identifier(autoIncrement = true),
        NeutralType.Array(elementType = "text"),
        NeutralType.FullText,
        NeutralType.Email,
        NeutralType.Enum(values = listOf("red", "green")),
    )

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        config = ConnectionConfig(
            dialect = DatabaseDialect.ORACLE,
            host = container.host,
            port = container.oraclePort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
    }

    afterSpec { container.stop() }

    test("the canonical projection equals the real Oracle round trip") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            for (type in probes) {
                val rendered = renderedColumnType(type)
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        // Quoted-lowercase wie der reale generate-Pfad -- ein
                        // unquoted Bezeichner faltet auf Grossschreibung und der
                        // Reader adressiert konsequent quoted-lowercase (ORA-00942
                        // sonst, siehe Slice-2-Review-Fund).
                        stmt.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"probe\"'; EXCEPTION WHEN OTHERS THEN NULL; END;")
                        stmt.execute("CREATE TABLE \"probe\" (\"val\" $rendered)")
                    }
                }
                val reversed = OracleSchemaReader().read(pool)
                    .schema.tables.getValue("probe").columns.getValue("val").type
                withClue("$type rendered as $rendered") {
                    reversed shouldBe canon.canonicalize(type)
                }
            }
        }
    }
})
