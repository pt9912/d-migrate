package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.capabilityGenerationCanonicalizer
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/**
 * `stored: false` ueberlebt bis in die Datenbank — auf einem PostgreSQL, das
 * die virtuelle Form kennt.
 *
 * Vorher war das eine geschlossene Kette stiller Degradierung, und zwar gegen
 * genau die Version, gegen die diese Suite laeuft (18.6): die
 * Faehigkeits-Faltung nahm den Unterschied weg, der Renderer schrieb unbedingt
 * `STORED`, der Server legte `attgenerated = 's'` an, und der Leser las das
 * korrekt zurueck. Der Round-Trip war sauber — und die Angabe des Autors weg.
 * Kein Glied der Kette meldete etwas.
 *
 * Was kein Unit-Test zeigen kann: dass der Server die Form wirklich so ablegt,
 * wie sie geschrieben wurde. `pg_attribute.attgenerated` ist die einzige
 * Stelle, die es sagt.
 */
class PostgresVirtualComputedColumnIntegrationTest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGRESQL)
    lateinit var pool: ConnectionPool

    beforeSpec {
        DatabaseDriverRegistry.register(PostgresDriver())
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun schemaWith(stored: Boolean) = SchemaDefinition(
        name = "pg_virtual", version = "1",
        tables = mapOf(
            "order_line" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                    "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
                    "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2), required = true),
                    "line_total" to ColumnDefinition(
                        NeutralType.Decimal(14, 2),
                        generation = ColumnGeneration.Computed("quantity * unit_price", stored = stored),
                    ),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun migrate(want: SchemaDefinition): Pair<Int, Int> {
        val tmp = createTempDirectory("pg-virtual")
        return try {
            var statements = 0
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ ->
                    val read = PostgresSchemaReader().read(pool)
                    ResolvedSchemaOperand(
                        reference = "live-pg",
                        schema = read.schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.POSTGRESQL,
                        serverVersion = read.serverVersion,
                    )
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    statements += stmts.size
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.POSTGRESQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            val report = runCatching { java.nio.file.Files.readString(tmp.resolve("report.json")) }.getOrElse { "" }
            withClue(errors.joinToString("; ") + " || " + report) { exit shouldBe 0 }
            exit to statements
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun generatedKind(column: String): String? = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """SELECT attgenerated FROM pg_attribute
                   WHERE attrelid = 'order_line'::regclass AND attname = '$column'""",
            ).use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    test("a virtual computed column really lands as virtual") {
        migrate(schemaWith(stored = false))

        withClue("'v' = virtuell, 's' = gespeichert") { generatedKind("line_total") shouldBe "v" }
    }

    test("the reverse reads it back as virtual, and a second run plans nothing") {
        val read = PostgresSchemaReader().read(pool).schema
        val generation = read.tables.getValue("order_line").columns.getValue("line_total").generation

        withClue(generation.toString()) {
            (generation as? ColumnGeneration.Computed)?.stored shouldBe false
        }
        migrate(schemaWith(stored = false)).second shouldBe 0
    }

    test("the capability fold no longer hides the difference on this server") {
        val version = PostgresSchemaReader().read(pool).serverVersion
        val fold = capabilityGenerationCanonicalizer(DatabaseDialect.POSTGRESQL, version)

        val virtual = ColumnGeneration.Computed("quantity * unit_price", stored = false)
        withClue("auf 18 ist `stored` eine Wahl, keine Konstante") {
            (fold(virtual) as ColumnGeneration.Computed).stored shouldBe false
        }
    }
})
