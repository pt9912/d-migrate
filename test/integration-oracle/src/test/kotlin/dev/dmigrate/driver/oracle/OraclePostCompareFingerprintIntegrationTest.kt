package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.oracle.OracleContainer
import java.sql.Connection
import java.time.Duration

/**
 * Das Postcompare-Invariant für Oracle.
 *
 * `schema migrate --execute` schließt mit einem Vergleich des v8-Fingerprints
 * des gewünschten Schemas gegen den Fingerprint dessen, was das Ziel
 * tatsächlich hält. Das funktioniert nur, wenn Oracles
 * [dev.dmigrate.driver.NeutralTypeCanonicalizer] exakt das faltet, was der
 * reale Round-Trip abflacht. Dieser Beleg generiert die DDL, wendet sie auf
 * ein echtes Oracle an, liest zurück und vergleicht die Fingerprints unter
 * der Oracle-Projektion — ohne den Diff-Renderer zu brauchen.
 *
 * Der zweite Test ist der unterscheidende: OHNE die Projektion muss der
 * Fingerprint driften, sonst wäre der erste Test hohl.
 */
class OraclePostCompareFingerprintIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))

    val canonicalize: (NeutralType) -> NeutralType = OracleDriver().typeCanonicalizer()::canonicalize

    fun column(type: NeutralType, required: Boolean = false) =
        ColumnDefinition(type = type, required = required)

    // Bewusst nur Spalten plus PLAIN PK (kein `autoIncrement`): der
    // Fingerprint deckt auch Constraints/Indizes ab, deren
    // Reverse-Materialisierung eine eigene Frage ist. Hier soll die
    // TYP-Projektion die einzige Variable sein. Eine `IDENTITY`-PK waere
    // hier KEIN geeigneter Fixpunkt -- Oracles Identity-Spalte haengt an
    // einer echten, aber system-generierten Sequenz (`ISEQ$$_n`), deren Name
    // beim Reverse in `ColumnGeneration.Identity.sequenceName` landet. Das
    // ist eine METADATEN-, keine TYP-Frage; sie hat mit Sub-Slice 5e-2 einen
    // eigenen Hook bekommen (`canonicalizeGeneration`), und der
    // Identity-Test unten belegt die Oracle-Seite davon.
    // `f_plain_id` unten deckt `Identifier(autoIncrement=false)` bereits ab
    // (keine IDENTITY-Klausel, kein Sequenzname).
    // Kein Geometry-Probe: Oracle Spatial ist unscoped (canGenerateSpatial()
    // = false blockt jede Tabelle mit Geometrie-Spalten vor der Generierung).
    val typeProbe = SchemaDefinition(
        name = "fingerprint_types",
        version = "1.0",
        tables = mapOf(
            "probe" to TableDefinition(
                primaryKey = listOf("id"),
                columns = linkedMapOf(
                    "id" to column(NeutralType.Identifier(autoIncrement = false), required = true),
                    // Fixpunkte
                    "n_int" to column(NeutralType.Integer),
                    "n_big" to column(NeutralType.BigInteger),
                    "n_bool" to column(NeutralType.BooleanType),
                    "n_tstz" to column(NeutralType.DateTime(timezone = true)),
                    "n_real" to column(NeutralType.Float(FloatPrecision.SINGLE)),
                    "n_text255" to column(NeutralType.Text(maxLength = 255)),
                    "n_json" to column(NeutralType.Json),
                    "n_xml" to column(NeutralType.Xml),
                    // Abflachungen — genau diese Spalten braucht die Projektion
                    "f_plain_id" to column(NeutralType.Identifier()),
                    "f_uuid" to column(NeutralType.Uuid),
                    "f_date" to column(NeutralType.Date),
                    "f_text_long" to column(NeutralType.Text(maxLength = 4001)),
                    "f_char_long" to column(NeutralType.Char(length = 2001)),
                    "f_decimal" to column(NeutralType.Decimal(50, 4)),
                    "f_decimal_bool" to column(NeutralType.Decimal(1, 0)),
                    "f_array" to column(NeutralType.Array(elementType = "text")),
                    "f_fulltext" to column(NeutralType.FullText),
                    "f_email" to column(NeutralType.Email),
                ),
            ),
        ),
    )

    fun configFor(database: String) = ConnectionConfig(
        dialect = DatabaseDialect.ORACLE,
        host = container.host,
        port = container.oraclePort,
        database = database,
        user = container.username,
        password = container.password,
    )

    lateinit var typeConfig: ConnectionConfig

    // Der Header-Kommentar (generateHeader) ist ein reiner Kommentarblock ohne
    // ausfuehrbaren Inhalt -- anders als T-SQL akzeptiert Oracles JDBC-Treiber
    // ihn nicht als eigenstaendiges `execute()` (ORA-00900).
    fun isExecutable(sql: String) = sql.lines().any { it.isNotBlank() && !it.trimStart().startsWith("--") }

    fun executeStatements(conn: Connection, statements: List<DdlStatement>) {
        conn.createStatement().use { stmt ->
            for (statement in statements) {
                val sql = statement.sql.trimEnd().removeSuffix(";")
                if (isExecutable(sql)) stmt.execute(sql)
            }
        }
    }

    fun applySchema(config: ConnectionConfig, schema: SchemaDefinition) {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val result = OracleDdlGenerator().generate(schema, DdlGenerationOptions())
            check(result.statements.any { it.sql.contains("CREATE TABLE", ignoreCase = true) }) {
                "generate emitted no CREATE TABLE: " + result.notes.joinToString { "${it.code} ${it.message}" }
            }
            pool.borrow().asJdbc().use { conn -> executeStatements(conn, result.statements) }
        }
    }

    beforeSpec {
        container.start()
        // gvenzl/oracle-free legt genau eine PDB an (container.databaseName) --
        // anders als MSSQL keine zweite Datenbank je Probe hier nötig, ein
        // Enum-Probe entfällt (die CHECK-Absorption ist bereits im MSSQL-Beleg
        // gedeckt; hier soll die TYP-Projektion die einzige Variable sein).
        typeConfig = configFor(container.databaseName)
        applySchema(typeConfig, typeProbe)
    }

    afterSpec { container.stop() }

    test("post-compare fingerprint of the desired schema matches the reverse-read target") {
        HikariConnectionPoolFactory.create(typeConfig).use { pool ->
            val actual = OracleSchemaReader().read(pool).schema
            MigrationFingerprint.project(actual, canonicalize) shouldBe
                MigrationFingerprint.project(typeProbe, canonicalize)
            MigrationFingerprint.compute(actual, canonicalize) shouldBe
                MigrationFingerprint.compute(typeProbe, canonicalize)
        }
    }

    test("without the Oracle projection the same round trip reads as drift") {
        HikariConnectionPoolFactory.create(typeConfig).use { pool ->
            val actual = OracleSchemaReader().read(pool).schema
            MigrationFingerprint.compute(actual) shouldNotBe MigrationFingerprint.compute(typeProbe)
        }
    }

    /**
     * Die gemessene Praemisse, auf der `canonicalizeGeneration` beruht: der
     * Reverse liefert fuer eine IDENTITY-Spalte einen Sequenznamen, den das
     * Soll-Schema nicht tragen kann -- er entsteht erst beim `CREATE TABLE`
     * und ist system-vergeben.
     *
     * Die VERDRAHTUNG des Hooks steht woanders
     * (`SchemaMigrateGenerationCanonicalizationWiringTest`, sabotage-belegt);
     * dieses Modul kennt `hexagon:application` nicht und soll es auch nicht
     * kennen. Hier zaehlt allein die Oracle-Tatsache.
     */
    test("an IDENTITY column reverse-reads a system-generated sequence name the desired cannot carry") {
        val identityProbe = SchemaDefinition(
            name = "identity_probe",
            version = "1.0.0",
            tables = mapOf(
                "id_probe" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true))),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        applySchema(typeConfig, identityProbe)

        HikariConnectionPoolFactory.create(typeConfig).use { pool ->
            val actual = OracleSchemaReader().read(pool).schema
            val identity = actual.tables.getValue("id_probe").columns.getValue("id").generation
                .shouldBeInstanceOf<ColumnGeneration.Identity>()
            // System-vergeben: das kann ein Anwender im Soll nicht
            // hinschreiben, und der Wert wechselt bei jedem Neuanlegen.
            identity.sequenceName.shouldNotBeNull().shouldStartWith("ISEQ")

            // Ohne Faltung driftet der Abdruck genau daran -- die beiden
            // Schemata unterscheiden sich in nichts sonst.
            MigrationFingerprint.compute(onlyIdProbe(actual), canonicalize) shouldNotBe
                MigrationFingerprint.compute(identityProbe, canonicalize)
        }
    }
})

/** Reduziert das reverse-gelesene Schema auf die Identity-Probe, damit der Vergleich nur sie sieht. */
private fun onlyIdProbe(schema: SchemaDefinition): SchemaDefinition =
    SchemaDefinition(
        name = "identity_probe",
        version = "1.0.0",
        tables = mapOf("id_probe" to schema.tables.getValue("id_probe")),
    )
