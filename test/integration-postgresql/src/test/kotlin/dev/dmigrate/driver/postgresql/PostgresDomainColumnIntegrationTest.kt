package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
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
 * Eine Spalte vom Typ einer Domain gegen eine echte Datenbank (Issue #68): der Reader nennt die Domain
 * (`type: enum` + `ref_type`), `schema migrate` plant fuer die unveraenderte Spalte nichts -- auch mit einer
 * aelteren Datei (Basistyp) --, der Post-Compare nach einer unabhaengigen Aenderung bleibt sauber, und der
 * Rollback-Rundlauf (`schema rollback`) trifft den Abdruck des Ziels.
 *
 * Die Datei-Seite entsteht aus dem Lesen der Datenbank: mit der Domain-Spalte als `ref_type` ([refFile]) oder in
 * der Form einer aelteren `reverse`-Datei mit dem Basistyp ([altFile]). Eine zusaetzliche Spalte `n` ist die einzige
 * echte Aenderung.
 */
class PostgresDomainColumnIntegrationTest : FunSpec({

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

    fun exec(vararg statements: String) = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }

    beforeEach { exec("DROP SCHEMA public CASCADE", "CREATE SCHEMA public") }

    fun read(): SchemaDefinition = PostgresSchemaReader().read(pool).schema

    fun column(schema: SchemaDefinition, name: String) = schema.tables.getValue("t").columns.getValue(name)

    fun withColumns(schema: SchemaDefinition, change: (MutableMap<String, ColumnDefinition>) -> Unit) = schema.copy(
        tables = schema.tables.mapValues { (_, t) ->
            t.copy(columns = LinkedHashMap(t.columns).also(change))
        },
    )

    val ref = NeutralType.Enum(refType = "dd")

    /** Die Datei, wie ein Autor sie schreibt: die Domain-Spalte `d` als `type: enum` + `ref_type`. */
    fun refTyped(schema: SchemaDefinition) = withColumns(schema) { it["d"] = it.getValue("d").copy(type = ref) }

    /** Die Datei mit der Domain-Spalte als `ref_type` und der zusaetzlichen Spalte `n`. */
    fun refFile(): SchemaDefinition = refTyped(withColumns(read()) { it["n"] = ColumnDefinition(NeutralType.Text()) })

    /** Die Form einer aelteren `reverse`-Datei: der Basistyp an der Domain-Spalte, die Domain unter `custom_types`. */
    fun altFile(base: NeutralType): SchemaDefinition = withColumns(read()) {
        it["d"] = it.getValue("d").copy(type = base)
        it["n"] = ColumnDefinition(NeutralType.Text())
    }

    data class Outcome(val exit: Int, val report: String, val errors: String)

    fun migrate(want: SchemaDefinition, rollbackOutput: Path? = null): Outcome {
        val tmp = createTempDirectory("pg-domain-column")
        try {
            val errors = mutableListOf<String>()
            var report = ""
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ ->
                    PostgresSchemaReader().read(pool).let { result ->
                        ResolvedSchemaOperand(
                            reference = "live-pg", schema = result.schema, validation = ValidationResult(),
                            dialect = DatabaseDialect.POSTGRESQL, serverVersion = result.serverVersion,
                        )
                    }
                },
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

    fun rollback(down: Path): Int = SchemaRollbackRunner(
        dbLoader = { _, _ ->
            ResolvedSchemaOperand("live-pg", read(), ValidationResult(), dialect = DatabaseDialect.POSTGRESQL)
        },
        executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
        printError = { msg, src -> System.err.println("[$src] $msg") },
    ).execute(SchemaRollbackRequest(source = down, target = "db:placeholder", execute = true, allowDestructive = true))

    fun createDomainTable(base: String = "integer", check: String = "VALUE >= 0") = exec(
        "CREATE DOMAIN dd AS $base CHECK ($check)",
        "CREATE TABLE t (id integer NOT NULL, d dd, PRIMARY KEY (id))",
    )

    // Der Basistyp je Domain und die Form, die eine aeltere reverse-Datei dafuer schrieb.
    val bases = listOf(
        Triple("integer", "VALUE >= 0", NeutralType.Integer),
        Triple("bigint", "VALUE >= 0", NeutralType.BigInteger),
        Triple("text", "VALUE <> ''", NeutralType.Text()),
        Triple("varchar(50)", "VALUE <> ''", NeutralType.Text(50)),
        Triple("numeric(10,2)", "VALUE >= 0", NeutralType.Decimal(10, 2)),
        Triple("uuid", "VALUE IS NOT NULL", NeutralType.Uuid),
        Triple("boolean", "VALUE IS NOT NULL", NeutralType.BooleanType),
    )

    test("der Reader nennt die Domain an der Spalte: type enum + ref_type, fuer jeden Basistyp") {
        for ((base, check, _) in bases) {
            exec("DROP SCHEMA public CASCADE", "CREATE SCHEMA public")
            createDomainTable(base, check)
            withClue(base) { column(read(), "d").type shouldBe ref }
        }
    }

    test("eine Basistyp-Spalte neben derselben Domain bleibt beim Basistyp") {
        exec(
            "CREATE DOMAIN dd AS integer CHECK (VALUE >= 0)",
            "CREATE TABLE t (id integer NOT NULL, d integer, PRIMARY KEY (id))",
        )
        column(read(), "d").type shouldBe NeutralType.Integer
    }

    test("eine Domain-Spalte mit Array-Basistyp, NOT NULL und DEFAULT nennt die Domain") {
        exec(
            "CREATE DOMAIN ia AS integer[]",
            "CREATE DOMAIN nn AS integer DEFAULT 1 NOT NULL",
            "CREATE TABLE t (id integer NOT NULL, a ia, b nn, c nn NOT NULL, PRIMARY KEY (id))",
        )
        val schema = read()
        column(schema, "a").type shouldBe NeutralType.Enum(refType = "ia")
        column(schema, "b").type shouldBe NeutralType.Enum(refType = "nn")
        column(schema, "b").required shouldBe false
        column(schema, "c").required shouldBe true
    }

    test("eine Domain ueber einer Domain ist nicht aufloesbar: die Spalte liest wie ohne Domain-Erkennung") {
        exec(
            "CREATE DOMAIN d1 AS integer",
            "CREATE DOMAIN d2 AS d1",
            "CREATE TABLE t (id integer NOT NULL, a d1, b d2, PRIMARY KEY (id))",
        )
        val schema = read()
        column(schema, "a").type shouldBe NeutralType.Enum(refType = "d1")
        // `udt_name` nennt den inneren Namen; die aeussere Domain steht nicht als `ref_type`.
        column(schema, "b").type shouldBe NeutralType.Enum(refType = "d1")
    }

    // Domains, deren Basistyp kein neutrales Gegenstueck hat oder der Spalte nicht gleicht: der Reader nennt sie nicht
    // (die Spalte liest wie ohne Domain-Erkennung), und die Datei in dieser Form plant nichts.
    val unresolvable = listOf(
        "real" to "real",
        "bit(5)" to "bit(5)",
        "numeric" to "numeric",
        "varchar(50)[]" to "varchar(50)[]",
        "inet" to "inet",
        "interval" to "interval",
    )

    test("Domain ueber real, bit(n), numeric, varchar(n)[], inet, interval: nicht genannt, die Datei plant nichts") {
        for ((label, base) in unresolvable) {
            exec("DROP SCHEMA public CASCADE", "CREATE SCHEMA public")
            exec("CREATE DOMAIN dd AS $base", "CREATE TABLE t (id integer NOT NULL, d dd, PRIMARY KEY (id))")
            val type = column(read(), "d").type
            withClue(label) { (type is NeutralType.Enum && type.refType == "dd") shouldBe false }
            val o = migrate(withColumns(read()) { it["n"] = ColumnDefinition(NeutralType.Text()) })
            withClue("$label ${o.errors} ${o.report.take(700)}") {
                o.exit shouldBe 0
                o.report.contains("AlterColumnType") shouldBe false
                o.report.contains("operationsTotal=1") shouldBe true
            }
        }
    }

    test("Domain ueber einer Domain: die Datei in der gelesenen Form plant nichts, und der Basistyp von Hand ebenso") {
        exec(
            "CREATE DOMAIN d1 AS integer",
            "CREATE DOMAIN d2 AS d1",
            "CREATE TABLE t (id integer NOT NULL, b d2, PRIMARY KEY (id))",
        )
        val o = migrate(withColumns(read()) { it["n"] = ColumnDefinition(NeutralType.Text()) })
        withClue("${o.errors} ${o.report.take(700)}") { o.exit shouldBe 0 }
        val byHand = migrate(withColumns(read()) {
            it["b"] = it.getValue("b").copy(type = NeutralType.Integer)
            it["n"] = ColumnDefinition(NeutralType.Text())
        })
        withClue("${byHand.errors} ${byHand.report.take(700)}") { byHand.exit shouldBe 0 }
    }

    test("eine Domain aus einem anderen Schema steht als Basistyp") {
        exec(
            "CREATE SCHEMA s2",
            "CREATE DOMAIN s2.dd2 AS integer",
            "CREATE DOMAIN dd AS integer",
            "CREATE TABLE t (id integer NOT NULL, a s2.dd2, b dd, PRIMARY KEY (id))",
        )
        val schema = read()
        schema.customTypes.keys shouldBe setOf("dd")
        column(schema, "a").type shouldBe NeutralType.Integer
        column(schema, "b").type shouldBe ref
    }

    test("Datei mit ref_type gegen die erzeugte Datenbank: kein Plan, fuer jeden Basistyp") {
        for ((base, check, _) in bases) {
            exec("DROP SCHEMA public CASCADE", "CREATE SCHEMA public")
            createDomainTable(base, check)
            val file = refTyped(read())
            val o = migrate(file)
            withClue("$base ${o.errors} ${o.report.take(600)}") {
                o.exit shouldBe 0
                o.report.contains("operationsTotal=0") shouldBe true
            }
            // Strikt: die Datei mit ref_type und die gelesene Datenbank sind dieselbe Form.
            SchemaComparator().compare(read(), file).tablesChanged.size shouldBe 0
        }
    }

    test("aeltere Datei mit Basistyp gegen die Datenbank mit Domain-Spalte: kein Plan fuer die Spalte, fuer jeden Basistyp") {
        for ((base, check, neutral) in bases) {
            exec("DROP SCHEMA public CASCADE", "CREATE SCHEMA public")
            createDomainTable(base, check)
            val o = migrate(altFile(neutral))
            withClue("$base ${o.errors} ${o.report.take(900)}") {
                o.exit shouldBe 0
                // Einzige Operation: die zusaetzliche Spalte `n`.
                o.report.contains("operationsTotal=1") shouldBe true
                o.report.contains("AlterColumnType") shouldBe false
            }
        }
    }

    test("Datei mit ref_type gegen eine Datenbank-Spalte vom Basistyp bleibt AlterColumnType (Exit 8)") {
        exec(
            "CREATE DOMAIN dd AS integer CHECK (VALUE >= 0)",
            "CREATE TABLE t (id integer NOT NULL, d integer, PRIMARY KEY (id))",
        )
        val o = migrate(refTyped(read()))
        withClue("${o.errors} ${o.report.take(900)}") {
            o.exit shouldBe 8
            o.report.contains("AlterColumnType") shouldBe true
        }
    }

    test("ein anderer Verweis und ein Basistypwechsel der Domain planen weiter") {
        exec(
            "CREATE DOMAIN dd AS integer CHECK (VALUE >= 0)",
            "CREATE DOMAIN ee AS integer CHECK (VALUE >= 0)",
            "CREATE TABLE t (id integer NOT NULL, d dd, PRIMARY KEY (id))",
        )
        val other = withColumns(read()) { it["d"] = it.getValue("d").copy(type = NeutralType.Enum(refType = "ee")) }
        migrate(other).exit shouldBe 8

        val changedBase = read().let { s ->
            s.copy(customTypes = s.customTypes + ("dd" to s.customTypes.getValue("dd").copy(baseType = "text")))
        }
        migrate(changedBase).exit shouldBe 8
    }

    test("strikter Vergleich: die Domain-Spalte gegen den Basistyp bleibt ein Unterschied, gegen ref_type identisch") {
        createDomainTable()
        val db = read()
        SchemaComparator().compare(db, db).tablesChanged.size shouldBe 0
        val alt = withColumns(db) { it["d"] = it.getValue("d").copy(type = NeutralType.Integer) }
        SchemaComparator().compare(db, alt).tablesChanged.single().columnsChanged.single().name shouldBe "d"
    }

    test("reverse -> validate: Domain-Spalte mit Spalten-DEFAULT und Fremdschluessel-Spalte vom Domain-Typ bestehen") {
        exec(
            "CREATE DOMAIN dd AS integer CHECK (VALUE >= 0)",
            "CREATE DOMAIN du AS uuid",
            "CREATE TABLE p (id dd NOT NULL, PRIMARY KEY (id))",
            "CREATE TABLE q (id integer NOT NULL, d dd DEFAULT 5, fk dd REFERENCES p (id), " +
                "u du DEFAULT gen_random_uuid(), PRIMARY KEY (id))",
            "CREATE TABLE r (id integer NOT NULL, fk integer REFERENCES p (id), PRIMARY KEY (id))",
        )
        val schema = read()
        column(schema.copy(tables = mapOf("t" to schema.tables.getValue("q"))), "d").type shouldBe ref
        val result = SchemaValidator().validate(schema)
        withClue(result.errors.toString()) { result.errors.map { it.code } shouldBe emptyList() }
    }

    // ── Post-Compare und Rollback-Rundlauf (Abdruck der Domain-Spalte) ──

    fun rollbackRoundTrip(want: () -> SchemaDefinition) {
        createDomainTable()
        val before = read()
        val tmp = createTempDirectory("pg-domain-column-rollback")
        try {
            val down = tmp.resolve("down.sql")
            val up = migrate(want(), rollbackOutput = down)
            withClue("${up.errors} ${up.report.take(900)}") {
                up.exit shouldBe 0
                up.report.contains("POST_EXECUTE_DRIFT") shouldBe false
            }
            read().tables.getValue("t").columns.keys shouldBe setOf("id", "d", "n")
            column(read(), "d").type shouldBe ref

            val exit = rollback(down)
            exit shouldBe 0
            read().tables.getValue("t").columns.keys shouldBe before.tables.getValue("t").columns.keys
            column(read(), "d").type shouldBe ref
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("Rollback-Rundlauf mit der ref_type-Datei: Migrate, Post-Compare ohne Drift, Rollback trifft den Abdruck") {
        rollbackRoundTrip { refFile() }
    }

    test("Rollback-Rundlauf mit der aelteren Datei (Basistyp): Migrate, Post-Compare ohne Drift, Rollback") {
        rollbackRoundTrip { altFile(NeutralType.Integer) }
    }
})
