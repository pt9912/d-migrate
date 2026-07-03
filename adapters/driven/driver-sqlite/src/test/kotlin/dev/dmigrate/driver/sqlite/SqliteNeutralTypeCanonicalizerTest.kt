package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.DriverManager

/**
 * Pins the SQLite canonicalisation (live composition `reverse(toSql(t))`)
 * against the AP0 edge table of the post-compare canonicalisation slice and —
 * via the real-round-trip property — against actual SQLite behaviour, so the
 * composition can never drift silently from what the reader reads back.
 */
class SqliteNeutralTypeCanonicalizerTest : FunSpec({

    val canon = SqliteNeutralTypeCanonicalizer

    // AP0-Kanten-Tabelle (Slice postcompare-type-canonicalization, 2026-07-03):
    // Fixpunkte sind genau die vier Storage-Klassen + identifier (AUTOINCREMENT).
    val expected = mapOf<NeutralType, NeutralType>(
        NeutralType.Text() to NeutralType.Text(),
        NeutralType.Integer to NeutralType.Integer,
        NeutralType.Float() to NeutralType.Float(),
        NeutralType.Binary to NeutralType.Binary,
        NeutralType.Identifier() to NeutralType.Identifier(autoIncrement = true),
        NeutralType.Identifier(autoIncrement = true) to NeutralType.Identifier(autoIncrement = true),
        NeutralType.Text(maxLength = 50) to NeutralType.Text(),
        NeutralType.Char(length = 10) to NeutralType.Text(),
        NeutralType.SmallInt to NeutralType.Integer,
        NeutralType.BigInteger to NeutralType.Integer,
        NeutralType.BooleanType to NeutralType.Integer,
        NeutralType.Float(FloatPrecision.SINGLE) to NeutralType.Float(),
        NeutralType.Decimal(10, 2) to NeutralType.Float(),
        NeutralType.DateTime() to NeutralType.Text(),
        NeutralType.DateTime(timezone = true) to NeutralType.Text(),
        NeutralType.Date to NeutralType.Text(),
        NeutralType.Time to NeutralType.Text(),
        NeutralType.Uuid to NeutralType.Text(),
        NeutralType.Json to NeutralType.Text(),
        NeutralType.Xml to NeutralType.Text(),
        NeutralType.Email to NeutralType.Text(),
        NeutralType.Enum(values = listOf("red", "green")) to NeutralType.Text(),
        NeutralType.Array(elementType = "text") to NeutralType.Text(),
    )

    test("canonical projection matches the AP0 edge table") {
        for ((type, canonical) in expected) {
            canon.canonicalize(type) shouldBe canonical
        }
    }

    test("geometry and fulltext stay identity (fidelity travels outside the declared type)") {
        val geometry = NeutralType.Geometry(GeometryType.of("point"), srid = 4326)
        canon.canonicalize(geometry) shouldBe geometry
        canon.canonicalize(NeutralType.FullText) shouldBe NeutralType.FullText
    }

    test("projection is idempotent") {
        for (type in expected.keys + NeutralType.Geometry() + NeutralType.FullText) {
            val once = canon.canonicalize(type)
            canon.canonicalize(once) shouldBe once
        }
    }

    test("composition equals the real SQLite round trip (generate DDL, reverse, compare)") {
        val reader = SqliteSchemaReader()
        val typeMapper = SqliteTypeMapper()

        fun pool(conn: Connection) = object : ConnectionPool {
            override val dialect = DatabaseDialect.SQLITE
            override fun borrow(): DatabaseConnection = JdbcDatabaseConnection(conn)
            override fun activeConnections(): Int = 1
            override fun close() {}
        }

        for (type in expected.keys) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""CREATE TABLE probe ("val" ${typeMapper.toSql(type)})""")
                }
                val schema = reader.read(pool(conn)).schema
                val reversed = schema.tables.getValue("probe").columns.getValue("val").type
                reversed shouldBe canon.canonicalize(type)
            }
        }
    }
})
