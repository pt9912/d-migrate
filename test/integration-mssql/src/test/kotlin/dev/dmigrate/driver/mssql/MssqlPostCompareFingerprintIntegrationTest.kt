package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager

/**
 * Slice 4 (docs/planning/in-progress/mssql-dialect-scoping.md): the
 * post-compare invariant for SQL Server, one slice before the migrate path
 * that will consume it.
 *
 * `schema migrate --execute` finishes by re-reading the target and comparing
 * the v7 fingerprint of the desired schema against the fingerprint of what
 * the target actually holds. That comparison only works if the TARGET's
 * [dev.dmigrate.driver.NeutralTypeCanonicalizer] folds exactly what T-SQL
 * flattens. This spec proves it end-to-end without needing the diff renderer:
 * generate the DDL, apply it to a real SQL Server, reverse-read, and compare
 * the two fingerprints under the MSSQL projection.
 *
 * The second test is the discriminating one — WITHOUT the projection the
 * fingerprints must differ, otherwise the first would be vacuous.
 *
 * The third pins a gap the projection does NOT close and cannot close,
 * because it is about constraints rather than types:
 * `docs/planning/open/enum-inline-check-fidelity.md`.
 */
class MssqlPostCompareFingerprintIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    val canonicalize = MssqlDriver().typeCanonicalizer()::canonicalize

    fun column(type: NeutralType, required: Boolean = false) =
        ColumnDefinition(type = type, required = required)

    // Bewusst nur Spalten plus Identity-PK: der Fingerprint deckt auch
    // Constraints/Indizes ab, deren Reverse-Materialisierung eine eigene
    // Frage ist (v7-Folds 2-4). Hier soll die TYP-Projektion die einzige
    // Variable sein — der Enum-Fall steht deshalb in einer eigenen Datenbank.
    val typeProbe = SchemaDefinition(
        name = "fingerprint_types",
        version = "1.0",
        tables = mapOf(
            "probe" to TableDefinition(
                primaryKey = listOf("id"),
                columns = linkedMapOf(
                    "id" to column(NeutralType.Identifier(autoIncrement = true), required = true),
                    // Fixpunkte
                    "n_int" to column(NeutralType.Integer),
                    "n_big" to column(NeutralType.BigInteger),
                    "n_bool" to column(NeutralType.BooleanType),
                    "n_uuid" to column(NeutralType.Uuid),
                    "n_tstz" to column(NeutralType.DateTime(timezone = true)),
                    "n_real" to column(NeutralType.Float(FloatPrecision.SINGLE)),
                    "n_text255" to column(NeutralType.Text(maxLength = 255)),
                    // Abflachungen — genau diese Spalten braucht die Projektion
                    "f_plain_id" to column(NeutralType.Identifier()),
                    "f_text_long" to column(NeutralType.Text(maxLength = 5000)),
                    "f_char_long" to column(NeutralType.Char(length = 5000)),
                    "f_decimal" to column(NeutralType.Decimal(50, 4)),
                    "f_json" to column(NeutralType.Json),
                    "f_array" to column(NeutralType.Array(elementType = "text")),
                    "f_fulltext" to column(NeutralType.FullText),
                    "f_email" to column(NeutralType.Email),
                    "f_geo" to column(NeutralType.Geometry(GeometryType.of("point"), srid = 4326)),
                ),
            ),
        ),
    )

    val enumProbe = SchemaDefinition(
        name = "fingerprint_enum",
        version = "1.0",
        tables = mapOf(
            "probe" to TableDefinition(
                primaryKey = listOf("id"),
                columns = linkedMapOf(
                    "id" to column(NeutralType.Identifier(autoIncrement = true), required = true),
                    "mood" to column(NeutralType.Enum(values = listOf("red", "green"))),
                ),
            ),
        ),
    )

    fun configFor(database: String) = ConnectionConfig(
        dialect = DatabaseDialect.MSSQL,
        host = container.host,
        port = container.firstMappedPort,
        database = database,
        user = container.username,
        password = container.password,
        ssl = SslSettings(SslMode.DISABLE),
    )

    lateinit var typeConfig: ConnectionConfig
    lateinit var enumConfig: ConnectionConfig

    fun applySchema(config: ConnectionConfig, schema: SchemaDefinition) {
        HikariConnectionPoolFactory.create(config).use { pool ->
            // NATIVE ist Pflicht, sobald eine Geometriespalte im Spiel ist —
            // sonst blockt der Generator die ganze Tabelle und der Vergleich
            // liefe gegen ein leeres Schema.
            val result = MssqlDdlGenerator()
                .generate(schema, DdlGenerationOptions(spatialProfile = SpatialProfile.NATIVE))
            check(result.statements.any { it.sql.contains("CREATE TABLE", ignoreCase = true) }) {
                "generate emitted no CREATE TABLE: " + result.notes.joinToString { "${it.code} ${it.message}" }
            }
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    // Statementweise wie der d-migrate-Runner — GO-Batches sind
                    // reine Skript-Darstellung (Slice 2a), kein T-SQL.
                    for (statement in result.statements) {
                        stmt.execute(statement.sql.trimEnd().removeSuffix(";"))
                    }
                }
            }
        }
    }

    beforeSpec {
        container.start()
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE DATABASE dmigrate_fp_types")
                stmt.execute("CREATE DATABASE dmigrate_fp_enum")
            }
        }
        typeConfig = configFor("dmigrate_fp_types")
        enumConfig = configFor("dmigrate_fp_enum")
        applySchema(typeConfig, typeProbe)
        applySchema(enumConfig, enumProbe)
    }

    afterSpec { container.stop() }

    test("post-compare fingerprint of the desired schema matches the reverse-read target") {
        HikariConnectionPoolFactory.create(typeConfig).use { pool ->
            val actual = MssqlSchemaReader().read(pool).schema
            // Erst die Projektion vergleichen: ein Hash-Mismatch allein sagt
            // nicht, WELCHES Feld gewandert ist.
            MigrationFingerprint.project(actual, canonicalize) shouldBe
                MigrationFingerprint.project(typeProbe, canonicalize)
            MigrationFingerprint.compute(actual, canonicalize) shouldBe
                MigrationFingerprint.compute(typeProbe, canonicalize)
        }
    }

    test("without the MSSQL projection the same round trip reads as drift") {
        HikariConnectionPoolFactory.create(typeConfig).use { pool ->
            val actual = MssqlSchemaReader().read(pool).schema
            MigrationFingerprint.compute(actual) shouldNotBe MigrationFingerprint.compute(typeProbe)
        }
    }

    test("known gap: the enum CHECK is materialised by the reverse, the type itself folds") {
        HikariConnectionPoolFactory.create(enumConfig).use { pool ->
            val actual = MssqlSchemaReader().read(pool).schema.tables.getValue("probe")
            // Die TYP-Seite stimmt: NVARCHAR(<laengster Wert>) faltet auf text(5).
            actual.columns.getValue("mood").type shouldBe
                canonicalize(NeutralType.Enum(values = listOf("red", "green")))
            // Die CONSTRAINT-Seite nicht: das gewuenschte Schema traegt den
            // Wertevorrat im Typ, das Ziel als benannten CHECK. Der v7-Fingerprint
            // absorbiert das nicht (Folds 2-4 decken UNIQUE/FK/required ab).
            // Ticket: docs/planning/open/enum-inline-check-fidelity.md
            enumProbe.tables.getValue("probe").constraints.shouldBeEmpty()
            actual.constraints.map { it.type } shouldBe listOf(ConstraintType.CHECK)
            val reverseSchema = SchemaDefinition(
                name = enumProbe.name,
                version = enumProbe.version,
                tables = mapOf("probe" to actual),
            )
            MigrationFingerprint.compute(reverseSchema, canonicalize) shouldNotBe
                MigrationFingerprint.compute(enumProbe, canonicalize)
        }
    }
})
