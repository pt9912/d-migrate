package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
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
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * Ein benannter Typ, den die Datei in ihrer Form schreibt und die Datenbank in der
 * Form des Katalogs liefert, ist **derselbe** Typ (Issue #51).
 *
 * Das Soll entsteht aus dem Lesen der Datenbank, mit den Typen in Datei-Form
 * ([fileForm]): so bleibt der Typ der einzige Unterschied, und eine zusaetzliche
 * Spalte ist die einzige echte Aenderung. Die Datei-Form ist die des Parsers:
 * `fields` leer statt fehlend, kein `ordinal` an Composite-Feldern.
 */
class PostgresCustomTypeFileFormMigrateIntegrationTest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGRESQL)
    lateinit var pool: ConnectionPool

    beforeSpec {
        DatabaseDriverRegistry.register(PostgresDriver())
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL, host = container.host, port = container.firstMappedPort,
                database = container.databaseName, user = container.username, password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    beforeEach {
        sql("DROP SCHEMA public CASCADE", "CREATE SCHEMA public", pool = pool)
    }

    fun read(): SchemaDefinition = PostgresSchemaReader().read(pool).schema

    /** Der Typ so, wie ihn der Datei-Parser liefert. */
    fun fileForm(type: CustomTypeDefinition, description: String? = null): CustomTypeDefinition = type.copy(
        fields = (type.fields ?: emptyMap()).mapValues { (_, f) -> f.copy(ordinal = null) },
        description = description,
    )

    fun withExtraColumn(schema: SchemaDefinition) = schema.copy(
        tables = schema.tables.mapValues { (_, t) ->
            t.copy(columns = LinkedHashMap(t.columns).also { it["extra"] = ColumnDefinition(NeutralType.Text()) })
        },
    )

    data class Outcome(val exit: Int, val report: String, val errors: String)

    fun migrate(want: SchemaDefinition, rollbackOutput: Path? = null): Outcome {
        val tmp = createTempDirectory("pg-ct-file-form")
        try {
            val errors = mutableListOf<String>()
            var report = ""
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ -> ResolvedSchemaOperand(
                    reference = "live-pg", schema = read(), validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                ) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
                renderReport = { r, _ -> r.toString().also { report = it } },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}", target = "db:placeholder",
                    dialect = DatabaseDialect.POSTGRESQL, report = tmp.resolve("report.json"), execute = true,
                    rollbackOutput = rollbackOutput, generateRollback = rollbackOutput != null,
                ),
            )
            return Outcome(exit, report, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** Wie [migrate], erwartet Exit 0 und genau die zusaetzliche Spalte als einzige Operation. */
    fun migratesOnlyTheColumn(want: SchemaDefinition) {
        val o = migrate(want)
        withClue("${o.errors} ${o.report.take(900)}") {
            o.exit shouldBe 0
            o.report.contains("kind=AlterCustomType") shouldBe false
            o.report.contains("operationsTotal=1") shouldBe true
        }
        // Ein zweiter Lauf ist ohne Operationen.
        val again = migrate(want)
        withClue("${again.errors} ${again.report.take(900)}") {
            again.exit shouldBe 0
            again.report.contains("operationsTotal=0") shouldBe true
        }
    }

    fun typedTo(read: SchemaDefinition, types: Map<String, CustomTypeDefinition>) = withExtraColumn(read).copy(customTypes = types)

    test("Enum mit zwei Werten in Datei-Form (fields leer statt fehlend)") {
        sql(
            "CREATE TYPE ee AS ENUM ('a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL DEFAULT 'a', PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        migratesOnlyTheColumn(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t) }))
    }

    test("Enum mit drei Werten behaelt die Reihenfolge") {
        sql(
            "CREATE TYPE ee AS ENUM ('c', 'a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        migratesOnlyTheColumn(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t) }))
    }

    test("Composite ohne ordinal an den Feldern") {
        sql(
            "CREATE TYPE pt AS (x integer, y integer)",
            "CREATE TABLE t (id integer NOT NULL, p pt, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        migratesOnlyTheColumn(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t) }))
    }

    test("Composite-Feld mit required und unique in der Datei") {
        sql(
            "CREATE TYPE pt AS (x integer, y integer)",
            "CREATE TABLE t (id integer NOT NULL, p pt, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        val decorated = db.customTypes.mapValues { (_, t) ->
            fileForm(t).let { f ->
                f.copy(fields = f.fields!!.mapValues { (_, col) -> col.copy(required = true, unique = true) })
            }
        }
        migratesOnlyTheColumn(typedTo(db, decorated))
    }

    test("Typ mit description in der Datei") {
        sql(
            "CREATE TYPE ee AS ENUM ('a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        migratesOnlyTheColumn(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t, description = "Zustand") }))
    }

    test("der Rueckweg stellt den Ausgangszustand her") {
        sql(
            "CREATE TYPE ee AS ENUM ('a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        val tmp = createTempDirectory("pg-ct-file-form-rollback")
        try {
            val down = tmp.resolve("down.sql")
            val up = migrate(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t) }), rollbackOutput = down)
            withClue("${up.errors} ${up.report.take(900)}") { up.exit shouldBe 0 }
            read().tables.getValue("t").columns.keys shouldBe setOf("id", "e", "extra")

            val exit = SchemaRollbackRunner(
                dbLoader = { _, _ -> ResolvedSchemaOperand("live-pg", read(), ValidationResult(), dialect = DatabaseDialect.POSTGRESQL) },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            ).execute(
                SchemaRollbackRequest(source = down, target = "db:placeholder", execute = true, allowDestructive = true),
            )
            exit shouldBe 0
            read().tables.getValue("t").columns.keys shouldBe db.tables.getValue("t").columns.keys
            read().customTypes.keys shouldBe setOf("ee")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("eine echte Aenderung der Werte blockiert weiter") {
        sql(
            "CREATE TYPE ee AS ENUM ('a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        val changed = db.customTypes.mapValues { (_, t) -> fileForm(t).copy(values = listOf("a", "b", "c")) }
        val o = migrate(typedTo(db, changed))
        withClue("${o.errors} ${o.report.take(900)}") {
            o.exit shouldBe 8
            o.report.contains("kind=AlterCustomType") shouldBe true
            o.report.contains("DIALECT_UNSUPPORTED_OPERATION") shouldBe true
        }
    }

    test("eine echte Aenderung einer Domain oder eines Composite blockiert weiter") {
        sql(
            "CREATE DOMAIN pos AS integer CHECK (VALUE > 0)",
            "CREATE TYPE pt AS (x integer, y integer)",
            "CREATE TABLE t (id integer NOT NULL, n pos, p pt, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        val domainChanged = db.customTypes.mapValues { (name, t) ->
            if (name == "pos") fileForm(t).copy(check = "VALUE > 5") else fileForm(t)
        }
        migrate(typedTo(db, domainChanged)).exit shouldBe 8
        val compositeChanged = db.customTypes.mapValues { (name, t) ->
            if (name == "pt") {
                fileForm(t).let { f -> f.copy(fields = f.fields!! + ("z" to ColumnDefinition(NeutralType.Integer))) }
            } else {
                fileForm(t)
            }
        }
        migrate(typedTo(db, compositeChanged)).exit shouldBe 8
    }

    // ── Domain in Autorenform (Issue #52): so, wie ein Autor sie schreibt, nicht wie der Katalog ──

    fun authored(base: String, check: String? = null, precision: Int? = null, scale: Int? = null) =
        CustomTypeDefinition(
            kind = CustomTypeKind.DOMAIN, baseType = base, check = check, precision = precision, scale = scale,
            fields = emptyMap(),
        )

    /** Das Soll: die gelesene Datenbank, die Domain `d` in Autorenform, dazu die eine zusaetzliche Spalte. */
    fun withAuthoredDomain(db: SchemaDefinition, domain: CustomTypeDefinition) =
        typedTo(db, db.customTypes + ("d" to domain))

    fun domainTable(vararg ddl: String) = sql(
        *ddl, "CREATE TABLE t (id integer NOT NULL, v d, PRIMARY KEY (id))", pool = pool,
    )

    val sameDomains = mapOf(
        "integer" to ("CREATE DOMAIN d AS integer CHECK (VALUE > 0)" to authored("integer", "VALUE > 0")),
        "integer ohne check" to ("CREATE DOMAIN d AS integer" to authored("integer")),
        "bigint" to ("CREATE DOMAIN d AS bigint CHECK (VALUE > 0)" to authored("bigint", "VALUE > 0")),
        "int8 als Synonym" to ("CREATE DOMAIN d AS bigint CHECK (VALUE > 0)" to authored("int8", "VALUE > 0")),
        "smallint" to ("CREATE DOMAIN d AS smallint CHECK (VALUE > 0)" to authored("smallint", "VALUE > 0")),
        "real" to ("CREATE DOMAIN d AS real CHECK (VALUE > 0)" to authored("real", "VALUE > 0")),
        "double precision" to
            ("CREATE DOMAIN d AS double precision CHECK (VALUE > 0)" to authored("double precision", "VALUE > 0")),
        "decimal(10,2)" to
            ("CREATE DOMAIN d AS decimal(10,2) CHECK (VALUE >= 0)" to authored("decimal", "VALUE >= 0", 10, 2)),
        "numeric(10,2) eingebettet" to
            ("CREATE DOMAIN d AS decimal(10,2) CHECK (VALUE >= 0)" to authored("NUMERIC(10,2)", "VALUE >= 0")),
        "numeric" to ("CREATE DOMAIN d AS numeric CHECK (VALUE >= 0)" to authored("numeric", "VALUE >= 0")),
        "text" to ("CREATE DOMAIN d AS text CHECK (length(VALUE) > 0)" to authored("text", "length(VALUE) > 0")),
        "bpchar(5)" to ("CREATE DOMAIN d AS char(5) CHECK (length(VALUE) > 0)" to authored("char(5)", "length(VALUE) > 0")),
        "text mit Literal" to ("CREATE DOMAIN d AS text CHECK (VALUE <> 'x')" to authored("varchar", "VALUE <> 'x'")),
        "AND, Klammern wie der Server sie setzt" to
            ("CREATE DOMAIN d AS integer CHECK (VALUE > 0 AND VALUE < 10)" to
                authored("integer", "(VALUE > 0) AND (VALUE < 10)")),
    )
    for ((name, case) in sameDomains) {
        test("Domain in Autorenform ist dieselbe Domain: $name") {
            domainTable(case.first)
            val db = read()
            migratesOnlyTheColumn(withAuthoredDomain(db, case.second))
        }
    }

    test("Domain ueber Domain in Autorenform ist dieselbe Domain") {
        domainTable("CREATE DOMAIN b AS integer CHECK (VALUE > 0)", "CREATE DOMAIN d AS b")
        val db = read()
        migratesOnlyTheColumn(withAuthoredDomain(db, authored("b")))
    }

    test("Domain in Autorenform: der Rueckweg stellt den Ausgangszustand her") {
        domainTable("CREATE DOMAIN d AS decimal(10,2) CHECK (VALUE >= 0)")
        val db = read()
        val tmp = createTempDirectory("pg-domain-rollback")
        try {
            val down = tmp.resolve("down.sql")
            val up = migrate(withAuthoredDomain(db, authored("numeric", "VALUE >= 0", 10, 2)), rollbackOutput = down)
            withClue("${up.errors} ${up.report.take(900)}") { up.exit shouldBe 0 }
            read().tables.getValue("t").columns.keys shouldBe setOf("id", "v", "extra")

            val exit = SchemaRollbackRunner(
                dbLoader = { _, _ -> ResolvedSchemaOperand("live-pg", read(), ValidationResult(), dialect = DatabaseDialect.POSTGRESQL) },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            ).execute(
                SchemaRollbackRequest(source = down, target = "db:placeholder", execute = true, allowDestructive = true),
            )
            exit shouldBe 0
            read().tables.getValue("t").columns.keys shouldBe db.tables.getValue("t").columns.keys
            read().customTypes.keys shouldBe setOf("d")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // Bekannte Grenzen (Handbuch §3.21): der Server schreibt diese Ausdruecke um, der Reader gibt die
    // Katalogform zurueck. Die Tests halten fest, dass sie weiter an `check` bzw. `baseType` blockieren, bis die
    // Faltung entschieden ist. Sie haengen an der Katalogform des PostgreSQL-Images; ein anderes Image kann sie kippen.
    val knownLimits = mapOf(
        "BETWEEN" to ("CREATE DOMAIN d AS integer CHECK (VALUE BETWEEN 1 AND 10)" to
            authored("integer", "VALUE BETWEEN 1 AND 10")),
        "IN" to ("CREATE DOMAIN d AS text CHECK (VALUE IN ('a', 'b'))" to authored("text", "VALUE IN ('a', 'b')")),
        "AND ohne Klammern" to ("CREATE DOMAIN d AS integer CHECK (VALUE > 0 AND VALUE < 10)" to
            authored("integer", "VALUE > 0 AND VALUE < 10")),
        "OR ohne Klammern" to ("CREATE DOMAIN d AS integer CHECK (VALUE < 0 OR VALUE > 10)" to
            authored("integer", "VALUE < 0 OR VALUE > 10")),
        "Cast an VALUE (varchar)" to ("CREATE DOMAIN d AS varchar(50) CHECK (length(VALUE) > 0)" to
            authored("varchar", "length(VALUE) > 0")),
        "Array-Domain" to ("CREATE DOMAIN d AS integer[]" to authored("integer[]")),
    )
    for ((name, case) in knownLimits) {
        test("bekannte Grenze, blockiert weiter: $name") {
            domainTable(case.first)
            val db = read()
            val o = migrate(withAuthoredDomain(db, case.second))
            withClue("${o.errors} ${o.report.take(600)}") {
                o.exit shouldBe 8
                o.report.contains("kind=AlterCustomType") shouldBe true
            }
        }
    }

    val realChanges = mapOf(
        "anderer CHECK" to ("CREATE DOMAIN d AS integer CHECK (VALUE > 0)" to authored("integer", "VALUE >= 0")),
        "andere Genauigkeit" to ("CREATE DOMAIN d AS decimal(10,2) CHECK (VALUE >= 0)" to
            authored("decimal", "VALUE >= 0", 12, 2)),
        "anderer Basistyp" to ("CREATE DOMAIN d AS integer CHECK (VALUE > 0)" to authored("bigint", "VALUE > 0")),
        "andere Genauigkeit, eingebettet" to ("CREATE DOMAIN d AS decimal(10,2) CHECK (VALUE >= 0)" to
            authored("NUMERIC(12,2)", "VALUE >= 0")),
    )
    for ((name, case) in realChanges) {
        test("echte Aenderung einer Domain blockiert weiter: $name") {
            domainTable(case.first)
            val db = read()
            val o = migrate(withAuthoredDomain(db, case.second))
            withClue("${o.errors} ${o.report.take(600)}") {
                o.exit shouldBe 8
                o.report.contains("kind=AlterCustomType") shouldBe true
            }
        }
    }
})

private fun sql(vararg statements: String, pool: ConnectionPool) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }
}
