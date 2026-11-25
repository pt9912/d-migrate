package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
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
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import kotlin.io.path.createTempDirectory

/**
 * ADR 0069 gegen echtes PostgreSQL: `values_constraint` benennt den CHECK einer Inline-Enum. Ohne den Namen vergibt
 * PostgreSQL ihn selbst (`<tabelle>_<spalte>_check`). Mit dem Namen legt `schema migrate` den CHECK unter ihm an,
 * der zweite Lauf plant nichts, und eine geaenderte Werteliste oder ein geaenderter Name ersetzt den CHECK.
 */
class PostgresEnumCheckNameIntegrationTest : FunSpec({

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

    fun schemaWith(name: String?, vararg values: String) = SchemaDefinition(
        name = "repro", version = "1.0.0",
        tables = mapOf("obj_device" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "a_state" to ColumnDefinition(
                    NeutralType.Enum(values = values.toList(), valuesConstraintName = name),
                ),
            ),
            primaryKey = listOf("id"),
        )),
    )

    /** Ein `schema migrate --execute`-Lauf; liefert die ausgefuehrten Anweisungen. */
    fun migrate(want: SchemaDefinition): List<String> {
        val tmp = createTempDirectory("pg-same-name-check")
        try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ -> ResolvedSchemaOperand(
                    reference = "live-pg",
                    schema = PostgresSchemaReader().read(pool).schema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                ) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
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
            withClue(errors.joinToString("; ")) { exit shouldBe 0 }
            return executed
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun dropTable() = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { it.execute("DROP TABLE IF EXISTS obj_device") }
    }

    fun checkNames(): List<String> = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'obj_device'::regclass AND contype = 'c' ORDER BY 1",
            ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    fun accepts(id: Int, value: String): Boolean = runCatching {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("INSERT INTO obj_device (id, a_state) VALUES ($id, '$value')") }
        }
    }.exceptionOrNull().let { it == null || it !is SQLException }

    test("angelegt: der CHECK traegt den gesetzten Namen, der zweite Lauf plant nichts") {
        dropTable()
        val desired = schemaWith("ck_a_state", "ACTIVE", "DISABLED")

        migrate(desired)

        checkNames() shouldBe listOf("ck_a_state")
        migrate(desired) shouldBe emptyList()
    }

    test("eine erweiterte Liste ersetzt den CHECK unter demselben Namen, die Datenbank nimmt den neuen Wert an") {
        dropTable()
        migrate(schemaWith("ck_a_state", "ACTIVE", "DISABLED"))

        val statements = migrate(schemaWith("ck_a_state", "ACTIVE", "DISABLED", "LOCKED"))

        withClue(statements.toString()) {
            statements.count { it.contains("DROP CONSTRAINT") } shouldBe 1
            statements.count { it.contains("ADD CONSTRAINT \"ck_a_state\"") } shouldBe 1
        }
        checkNames() shouldBe listOf("ck_a_state")
        accepts(1, "LOCKED") shouldBe true
        accepts(2, "WHATEVER") shouldBe false
        migrate(schemaWith("ck_a_state", "ACTIVE", "DISABLED", "LOCKED")) shouldBe emptyList()
    }

    test("nur der Name aendert sich: DROP des alten, ADD des neuen, danach plant der Lauf nichts") {
        dropTable()
        migrate(schemaWith("ck_old", "ACTIVE", "DISABLED"))

        val statements = migrate(schemaWith("ck_new", "ACTIVE", "DISABLED"))

        withClue(statements.toString()) {
            statements.count { it.contains("DROP CONSTRAINT") } shouldBe 1
            statements.count { it.contains("ADD CONSTRAINT \"ck_new\"") } shouldBe 1
        }
        checkNames() shouldBe listOf("ck_new")
        migrate(schemaWith("ck_new", "ACTIVE", "DISABLED")) shouldBe emptyList()
    }

    test("eine unbenannte Enum, die der Server benannt hat, bekommt den gesetzten Namen") {
        dropTable()
        migrate(schemaWith(null, "ACTIVE", "DISABLED"))
        checkNames() shouldBe listOf("obj_device_a_state_check")

        val statements = migrate(schemaWith("ck_a_state", "ACTIVE", "DISABLED"))

        withClue(statements.toString()) {
            statements.single { it.contains("DROP CONSTRAINT") }.contains("obj_device_a_state_check") shouldBe true
        }
        checkNames() shouldBe listOf("ck_a_state")
    }

    test("ohne gesetzten Namen zaehlt der Name der Datenbank nicht: der Lauf plant nichts") {
        dropTable()
        migrate(schemaWith("ck_a_state", "ACTIVE", "DISABLED"))

        migrate(schemaWith(null, "ACTIVE", "DISABLED")) shouldBe emptyList()
        checkNames() shouldBe listOf("ck_a_state")
    }
})
