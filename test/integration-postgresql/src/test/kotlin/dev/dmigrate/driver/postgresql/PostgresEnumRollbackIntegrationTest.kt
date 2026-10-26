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
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.createTempDirectory

/**
 * Der Rollback nach einer geaenderten Werteliste einer Inline-Enum-Spalte stellt den
 * Wertevorrat wieder her (Issue #61).
 *
 * Ein Inline-Enum ist in PostgreSQL eine Textspalte mit einem CHECK. Der Up loest den alten CHECK und
 * legt den neuen unbenannt an; ein Down, der nur `TYPE TEXT` rendert, liesse den neuen stehen — und
 * `schema rollback` endete mit Exit 0, ohne etwas zurueckgenommen zu haben.
 *
 * Zwei Wertelisten-CHECKs an einer Spalte: der Vergleich meldet beide als eigene `DropConstraint`,
 * der Up loest sie darueber (kein doppeltes `DROP`), der Down blockiert, weil er den Namen des vom Up
 * angelegten CHECKs nicht kennt.
 *
 * Jede Spec fuehrt den echten Hin- und Rueckweg aus (`schema migrate --execute --generate-rollback`,
 * dann `schema rollback --execute --allow-destructive`) und prueft, was die **Datenbank** danach tut:
 * der Katalog und ein `INSERT`, nicht der Text des Skripts.
 */
class PostgresEnumRollbackIntegrationTest : FunSpec({

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

    fun exec(vararg statements: String) = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }

    beforeEach { exec("DROP SCHEMA public CASCADE", "CREATE SCHEMA public") }

    fun table(vararg columns: String) =
        "CREATE TABLE t (id integer NOT NULL, ${columns.joinToString(", ")}, PRIMARY KEY (id))"

    fun read(): SchemaDefinition = PostgresSchemaReader().read(pool).schema

    fun schemaOf(columns: Map<String, NeutralType>) = SchemaDefinition(
        name = "enum_rollback", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = LinkedHashMap<String, ColumnDefinition>().also { all ->
                    all["id"] = ColumnDefinition(NeutralType.Integer, required = true)
                    columns.forEach { (name, type) -> all[name] = ColumnDefinition(type, required = true) }
                },
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun enumOf(vararg values: String) = NeutralType.Enum(values = values.toList())

    data class Outcome(val exit: Int, val report: String, val errors: String)

    fun migrate(want: SchemaDefinition, rollbackOutput: Path? = null, targetVersion: String? = null): Outcome {
        val tmp = createTempDirectory("pg-enum-rollback")
        try {
            val errors = mutableListOf<String>()
            var report = ""
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ -> PostgresSchemaReader().read(pool).let { result ->
                    ResolvedSchemaOperand(
                        reference = "live-pg", schema = result.schema, validation = ValidationResult(),
                        dialect = DatabaseDialect.POSTGRESQL, serverVersion = result.serverVersion,
                    )
                } },
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
                    rollbackOutput = rollbackOutput, generateRollback = rollbackOutput != null, targetVersion = targetVersion,
                ),
            )
            return Outcome(exit, report, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun rollback(down: Path): Int = SchemaRollbackRunner(
        dbLoader = { _, _ -> ResolvedSchemaOperand("live-pg", read(), ValidationResult(), dialect = DatabaseDialect.POSTGRESQL) },
        executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
        printError = { msg, src -> System.err.println("[$src] $msg") },
    ).execute(SchemaRollbackRequest(source = down, target = "db:placeholder", execute = true, allowDestructive = true))

    /** Name und Definition aller CHECKs von `t`, sortiert. */
    fun checks(): List<String> = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT con.conname || ' ' || pg_get_constraintdef(con.oid) FROM pg_constraint con " +
                    "JOIN pg_class rel ON rel.oid = con.conrelid " +
                    "WHERE con.contype = 'c' AND rel.relname = 't' ORDER BY 1",
            ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    var nextId = 1000

    /** `true`, wenn die Datenbank den Wert annimmt; nur ein CHECK-Verstoss gilt als Ablehnung. */
    fun accepts(column: String, value: String): Boolean = try {
        // Die anderen Enum-Spalten tragen einen Wert, den jede Werteliste dieser Specs erlaubt.
        val others = mapOf("e" to "a", "f" to "x").filterKeys { it != column }
        val names = listOf("id", column) + others.keys
        val values = listOf("${nextId++}", "'$value'") + others.values.map { "'$it'" }
        val present = pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = 't'")
                    .use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() }
            }
        }
        val used = names.indices.filter { names[it] in present }
        exec("INSERT INTO t (${used.joinToString { names[it] }}) VALUES (${used.joinToString { values[it] }})")
        true
    } catch (e: SQLException) {
        if (e.sqlState != "23514") throw e
        false
    }

    /** Hin- und Rueckweg: Migrate mit Rollback-Skript, danach das Skript ausfuehren. */
    fun roundTrip(want: SchemaDefinition) {
        val tmp = createTempDirectory("pg-enum-rollback-down")
        try {
            val down = tmp.resolve("down.sql")
            val up = migrate(want, rollbackOutput = down)
            withClue("${up.errors} ${up.report.take(900)}") { up.exit shouldBe 0 }
            rollback(down) shouldBe 0
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun noPlannedOperations(want: SchemaDefinition) {
        val again = migrate(want)
        withClue("${again.errors} ${again.report.take(900)}") {
            again.exit shouldBe 0
            again.report.contains("operationsTotal=0") shouldBe true
        }
    }

    test("a widened list: after the rollback the database rejects the new value again") {
        exec(table("e text NOT NULL CHECK (\"e\" IN ('a','b'))"))
        val before = checks()
        val original = schemaOf(mapOf("e" to enumOf("a", "b")))

        roundTrip(schemaOf(mapOf("e" to enumOf("a", "b", "c"))))

        withClue(checks().toString()) {
            checks() shouldBe before
            accepts("e", "c") shouldBe false
            accepts("e", "a") shouldBe true
            accepts("e", "b") shouldBe true
        }
        // Und der Ausgangszustand ist wirklich wieder der Ist-Zustand.
        noPlannedOperations(original)
    }

    test("rows with the new value stop the rollback and leave the widened CHECK in place") {
        exec(table("e text NOT NULL CHECK (\"e\" IN ('a','b'))"))
        val tmp = createTempDirectory("pg-enum-rollback-rows")
        try {
            val down = tmp.resolve("down.sql")
            migrate(schemaOf(mapOf("e" to enumOf("a", "b", "c"))), rollbackOutput = down).exit shouldBe 0
            val widened = checks()
            accepts("e", "c") shouldBe true

            (rollback(down) != 0) shouldBe true

            // Alles oder nichts: der DROP des weiten CHECKs bleibt nicht stehen.
            checks() shouldBe widened
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("a shortened list: after the rollback the removed value is allowed again") {
        exec(table("e text NOT NULL CHECK (\"e\" IN ('a','b','c'))"))
        val before = checks()

        roundTrip(schemaOf(mapOf("e" to enumOf("a", "b"))))

        withClue(checks().toString()) {
            checks() shouldBe before
            accepts("e", "c") shouldBe true
            accepts("e", "z") shouldBe false
        }
        noPlannedOperations(schemaOf(mapOf("e" to enumOf("a", "b", "c"))))
    }

    test("several enum columns: only the changed ones are touched") {
        exec(table("e text NOT NULL CHECK (\"e\" IN ('a','b'))", "f text NOT NULL CHECK (\"f\" IN ('x','y'))"))
        val before = checks()

        roundTrip(schemaOf(mapOf("e" to enumOf("a", "b", "c"), "f" to enumOf("x", "y"))))
        checks() shouldBe before
        accepts("e", "c") shouldBe false

        roundTrip(schemaOf(mapOf("e" to enumOf("a", "b", "c"), "f" to enumOf("x", "y", "z"))))
        withClue(checks().toString()) {
            checks() shouldBe before
            accepts("e", "c") shouldBe false
            accepts("f", "z") shouldBe false
            accepts("f", "x") shouldBe true
        }
    }

    test("a CHECK the user named is restored under its name") {
        exec(table("e text NOT NULL CONSTRAINT my_e_values CHECK (\"e\" IN ('a','b'))"))
        val before = checks()

        roundTrip(schemaOf(mapOf("e" to enumOf("a", "b", "c"))))

        withClue(checks().toString()) {
            checks() shouldBe before
            accepts("e", "c") shouldBe false
        }
        noPlannedOperations(schemaOf(mapOf("e" to enumOf("a", "b"))))
    }

    test("a text column that gained a value list loses the CHECK again") {
        exec(table("e text NOT NULL"))

        roundTrip(schemaOf(mapOf("e" to enumOf("a", "b"))))

        withClue(checks().toString()) {
            checks() shouldBe emptyList()
            accepts("e", "c") shouldBe true
        }
    }

    test("an enum column that lost its value list gets the CHECK back") {
        exec(table("e text NOT NULL CHECK (\"e\" IN ('a','b'))"))
        val before = checks()

        roundTrip(schemaOf(mapOf("e" to NeutralType.Text())))

        withClue(checks().toString()) {
            checks() shouldBe before
            accepts("e", "c") shouldBe false
        }
    }

    test("only the order of the values changed: nothing to roll back, nothing changes") {
        exec(table("e text NOT NULL CHECK (\"e\" IN ('a','b'))"))
        val before = checks()

        roundTrip(schemaOf(mapOf("e" to enumOf("b", "a"))))

        checks() shouldBe before
    }

    test("an unnamed CHECK takes a digit suffix when another table holds the default name") {
        // Messung zur Namensregel: ein gleichnamiger CHECK einer anderen Tabelle im selben Schema.
        exec(
            "CREATE TABLE u (id integer NOT NULL, CONSTRAINT t_e_check CHECK (id > 0))",
            table("e text NOT NULL"),
            "ALTER TABLE t ADD CHECK (\"e\" IN ('a','b'))",
        )
        val names = pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT rel.relname || '.' || con.conname FROM pg_constraint con " +
                        "JOIN pg_class rel ON rel.oid = con.conrelid WHERE con.contype = 'c' ORDER BY 1",
                ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }
        // PostgreSQL prueft den Namen schemaweit: der Standardname waere belegt, der neue CHECK bekommt eine Ziffer.
        names shouldBe listOf("t.t_e_check1", "u.t_e_check")
    }

    test("a text column gaining a value list while another table holds the default name blocks the Down") {
        // `taken` zaehlt schemaweit, wie PostgreSQL (Messung oben): ein `DROP` auf `t_e_check` traefe den
        // falschen CHECK der anderen Tabelle nicht, der neue heisst `t_e_check1` — der Down blockiert deshalb.
        exec(
            "CREATE TABLE u (id integer NOT NULL, CONSTRAINT t_e_check CHECK (id > 0))",
            table("e text NOT NULL"),
        )
        val before = checks()

        val tmp = createTempDirectory("pg-enum-rollback-other-table")
        try {
            val out = migrate(schemaOf(mapOf("e" to enumOf("a", "b"))).let { want ->
                want.copy(tables = want.tables + ("u" to TableDefinition(
                    columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                    constraints = listOf(
                        dev.dmigrate.core.model.ConstraintDefinition(
                            name = "t_e_check", type = dev.dmigrate.core.model.ConstraintType.CHECK, expression = "id > 0",
                        ),
                    ),
                )))
            }, rollbackOutput = tmp.resolve("down.sql"))

            withClue("${out.errors} ${out.report.take(1500)}") {
                out.exit shouldBe 8
                out.report.contains("POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN") shouldBe true
            }
            checks() shouldBe before
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("--target-version does not make a file a catalog: file against file still blocks the Down") {
        // Beide Seiten Dateien; die ausdrueckliche Version setzt die Serverversion, belegt aber keinen Katalog.
        val text = schemaOf(mapOf("e" to NeutralType.Text()))
        val ab = schemaOf(mapOf("e" to enumOf("a", "b")))
        val abc = schemaOf(mapOf("e" to enumOf("a", "b", "c")))
        // A: Werteliste erweitert, B: Textspalte erhaelt eine Werteliste, C: Spalte verliert sie.
        for ((from, want) in listOf(ab to abc, text to ab, ab to text)) {
            val errors = mutableListOf<String>()
            var report = ""
            val exit = SchemaMigrateRunner(
                fileLoader = { op ->
                    val file = (op as dev.dmigrate.cli.commands.CompareOperand.File).path.toString()
                    ResolvedSchemaOperand("f", if (file.endsWith("current.yaml")) from else want, ValidationResult())
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                rendererFor = { d -> if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null },
                atomicWriter = { _, _ -> },
                renderReport = { r, _ -> r.toString().also { report = it } },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "desired.yaml", target = "current.yaml", dialect = DatabaseDialect.POSTGRESQL,
                    planOnly = true, generateRollback = true, targetVersion = "16.4",
                ),
            )
            withClue("${errors.joinToString("; ")} ${report.take(1200)}") {
                exit shouldBe 8
                report.contains("POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN") shouldBe true
            }
        }
    }

    test("--target-version against a live database keeps the catalog: the Down renders and restores") {
        exec(table("e text NOT NULL CHECK (\"e\" IN ('a','b'))"))
        val before = checks()
        val tmp = createTempDirectory("pg-enum-rollback-target-version")
        try {
            val down = tmp.resolve("down.sql")
            val up = migrate(schemaOf(mapOf("e" to enumOf("a", "b", "c"))), rollbackOutput = down, targetVersion = "16.4")
            withClue("${up.errors} ${up.report.take(900)}") { up.exit shouldBe 0 }
            rollback(down) shouldBe 0
            checks() shouldBe before
            accepts("e", "c") shouldBe false
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("a name the rollback cannot determine blocks the migrate instead of leaving the CHECK widened") {
        // Der Up legt den neuen CHECK unbenannt an; `t_e_check` ist belegt, er hiesse also anders.
        exec(
            table(
                "e text NOT NULL CONSTRAINT my_e_values CHECK (\"e\" IN ('a','b'))",
                "CONSTRAINT t_e_check CHECK (id > 0)",
            ),
        )
        val before = checks()
        val tmp = createTempDirectory("pg-enum-rollback-blocked")
        try {
            val out = migrate(schemaOf(mapOf("e" to enumOf("a", "b", "c"))), rollbackOutput = tmp.resolve("down.sql"))

            withClue("${out.errors} ${out.report.take(1500)}") {
                out.exit shouldBe 8
                out.report.contains("downBlocked=true") shouldBe true
                out.report.contains("ROLLBACK_NOT_POSSIBLE") shouldBe true
                out.report.contains("POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN") shouldBe true
            }
            // Nichts ausgefuehrt: der Up laeuft nicht, wenn sein Rueckweg nicht tragen kann.
            checks() shouldBe before
        } finally {
            tmp.toFile().deleteRecursively()
        }
        // Ohne Rollback-Skript bleibt der Up moeglich — der Blocker gehoert dem Down.
        migrate(schemaOf(mapOf("e" to enumOf("a", "b", "c")))).exit shouldBe 0
    }

    val twoChecks =
        "e text NOT NULL CHECK (\"e\" IN ('a','b','c')), CONSTRAINT second CHECK (\"e\" IN ('a','b'))"

    fun valuesAfterUp() {
        // Ein CHECK, nicht zwei; der zweite Migrate plant nichts.
        checks().size shouldBe 1
    }

    test("two value CHECKs on one column: a widened list migrates and leaves one CHECK") {
        exec(table(twoChecks))
        val want = schemaOf(mapOf("e" to enumOf("a", "b", "d")))

        val out = migrate(want)

        withClue("${out.errors} ${out.report.take(1500)}") { out.exit shouldBe 0 }
        withClue(checks().toString()) {
            valuesAfterUp()
            accepts("e", "d") shouldBe true
            accepts("e", "c") shouldBe false
        }
        noPlannedOperations(want)
    }

    test("two value CHECKs on one column: a shortened list migrates") {
        exec(table(twoChecks))
        val want = schemaOf(mapOf("e" to enumOf("a")))

        migrate(want).exit shouldBe 0

        withClue(checks().toString()) {
            valuesAfterUp()
            accepts("e", "b") shouldBe false
            accepts("e", "a") shouldBe true
        }
        noPlannedOperations(want)
    }

    test("two value CHECKs on one column: the list of one of them migrates too") {
        exec(table(twoChecks))
        val want = schemaOf(mapOf("e" to enumOf("a", "b")))

        migrate(want).exit shouldBe 0

        withClue(checks().toString()) { valuesAfterUp() }
        noPlannedOperations(want)
    }

    test("two value CHECKs on one column: with a rollback script the migrate blocks and changes nothing") {
        exec(table(twoChecks))
        val before = checks()
        val tmp = createTempDirectory("pg-enum-rollback-two")
        try {
            val out = migrate(schemaOf(mapOf("e" to enumOf("a", "b", "d"))), rollbackOutput = tmp.resolve("down.sql"))

            withClue("${out.errors} ${out.report.take(1500)}") {
                out.exit shouldBe 8
                out.report.contains("ROLLBACK_NOT_POSSIBLE") shouldBe true
                out.report.contains("POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN") shouldBe true
            }
            checks() shouldBe before
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("a value CHECK next to a business CHECK on the same column: the business CHECK survives the round trip") {
        exec(table("e text NOT NULL CHECK (\"e\" IN ('a','b')), CONSTRAINT biz CHECK (length(\"e\") > 0)"))
        val before = checks()

        roundTrip(schemaOf(mapOf("e" to enumOf("a", "b", "c"))))

        withClue(checks().toString()) {
            checks() shouldBe before
            accepts("e", "c") shouldBe false
        }
    }

    for ((label, names) in listOf("chk_a/chk_b" to ("chk_a" to "chk_b"), "t_e_check/zzz" to ("t_e_check" to "zzz"))) {
        test("two value CHECKs named $label: the Up runs, with a rollback script the migrate blocks") {
            val columns = "e text NOT NULL CONSTRAINT ${names.first} CHECK (\"e\" IN ('a','b','c')), " +
                "CONSTRAINT ${names.second} CHECK (\"e\" IN ('a','b'))"
            exec(table(columns))
            val before = checks()
            val want = schemaOf(mapOf("e" to enumOf("a", "b", "d")))
            val tmp = createTempDirectory("pg-enum-rollback-names")
            try {
                val blocked = migrate(want, rollbackOutput = tmp.resolve("down.sql"))
                withClue("${blocked.errors} ${blocked.report.take(1500)}") {
                    blocked.exit shouldBe 8
                    blocked.report.contains("POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN") shouldBe true
                }
                checks() shouldBe before
            } finally {
                tmp.toFile().deleteRecursively()
            }
            migrate(want).exit shouldBe 0
            valuesAfterUp()
            noPlannedOperations(want)
        }
    }
})
