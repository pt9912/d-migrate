package dev.dmigrate.driver.mssql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeSegmentsAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.io.path.createTempDirectory

/**
 * Das Preserve-Fenster auf SQL Server, live abgenommen.
 *
 * Gebaut war es laengst — Probe, Executor und Renderer stehen seit dem
 * MSSQL-Slice. Belegt war es nicht: die drei Specs, die PostgreSQL, MySQL und
 * SQLite dafuer tragen, fehlten hier. „Der Code sagt ja, gemessen hat es
 * niemand" war der Befund, und diese Spec ist seine Antwort.
 *
 * **Alle drei Ebenen in einer Klasse**, weil der Containerstart der teuerste
 * Teil des Laufs ist und drei Klassen ihn dreimal bezahlten.
 *
 * Was T-SQL dabei anders macht als die drei belegten Dialekte:
 *
 * - Das Lock ist `sys.sp_getapplock` mit `@LockOwner = 'Transaction'`; es
 *   faellt beim Commit von selbst weg.
 * - `sys.sequences.current_value` traegt den zuletzt **ausgegebenen** Wert —
 *   bei einer nie benutzten Sequenz aber den Startwert. Fortgesetzt wird
 *   deshalb bei `Wert + Schrittweite`, was bei einer frischen Sequenz genau
 *   einen Wert ueberspringt. Das ist die sichere Richtung, und diese Spec
 *   nagelt sie fest.
 */
class MssqlSequencePreserveIntegrationTest : FunSpec({

    val container = startMssqlContainer()
    lateinit var pool: ConnectionPool
    val tmp = createTempDirectory("mssql-preserve")

    beforeSpec {
        container.start()
        pool = poolFor(container, "preserve_it")
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
        tmp.toFile().deleteRecursively()
    }

    fun exec(vararg sql: String) = execDdl(pool, *sql)

    fun nextValue(name: String): Long = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { s ->
            s.executeQuery("SELECT NEXT VALUE FOR [$name]").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun ref(name: String) = SequenceObjectRef(name, "dbo", RenameProjectionDialect.MSSQL)

    // ── Die Probe ──────────────────────────────────────────────────────

    test("the probe reads what sys.sequences carries — seed before first use") {
        exec("CREATE SEQUENCE [probe_fresh] AS BIGINT START WITH 100 INCREMENT BY 1")

        val result = pool.borrow().asJdbc().use { MssqlSequenceCurrentValueProbe.probe(it, ref("probe_fresh")) }

        val read = result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
        withClue("eine nie benutzte Sequenz traegt ihren Startwert") { read.value shouldBe 100L }
        // T-SQL kennt kein `is_called`; die Probe darf dafuer nichts erfinden.
        read.isCalled shouldBe null
    }

    test("the probe follows the sequence as it advances") {
        exec("CREATE SEQUENCE [probe_used] AS BIGINT START WITH 100 INCREMENT BY 1")

        withClue("der erste Wert IST der Startwert") { nextValue("probe_used") shouldBe 100L }
        nextValue("probe_used") shouldBe 101L

        val result = pool.borrow().asJdbc().use { MssqlSequenceCurrentValueProbe.probe(it, ref("probe_used")) }

        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>().value shouldBe 101L
    }

    test("a sequence that is not there is NotFound, not an error") {
        val result = pool.borrow().asJdbc().use { MssqlSequenceCurrentValueProbe.probe(it, ref("probe_absent")) }

        (result is SequenceCurrentValueProbeResult.NotFound) shouldBe true
    }

    // ── Der Executor ───────────────────────────────────────────────────

    val executor = MssqlAtomicSequencePreserveExecutor()
    val protectedOp = ProtectedOperationId("AlterSequenceCurrentValue")
    val definition = SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true)

    test("Applied: probe, protected statements and restore commit as one") {
        exec("CREATE SEQUENCE [exec_one] AS BIGINT START WITH 100 INCREMENT BY 1")
        nextValue("exec_one") shouldBe 100L
        nextValue("exec_one") shouldBe 101L

        val batch = AtomicSequencePreserveBatch(
            // T-SQL braucht die Definition: `RESTART WITH` setzt den NAECHSTEN
            // Wert, also entscheiden Schrittweite und Schranken, wo es
            // weitergeht. Ohne sie wird geblockt statt geraten.
            requests = listOf(
                AtomicSequencePreserveRequest(
                    sequenceRef = ref("exec_one"),
                    sequence = SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
                ),
            ),
            protectedOperationIds = listOf(protectedOp),
            internalFollowUpIds = listOf("op-exec_one"),
        )

        pool.borrow().asJdbc().use { c ->
            val result = executor.execute(JdbcDatabaseConnection(c), batch, lockTimeoutMillis = 5_000) { conn, ops ->
                ops shouldBe listOf(protectedOp)
                // Die geschuetzte Anweisung treibt die Sequenz weiter; der
                // Restore muss das wieder einfangen.
                conn.asJdbc().createStatement().use { s -> s.execute("SELECT NEXT VALUE FOR [exec_one]") }
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 1)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>().refs shouldBe listOf(ref("exec_one"))
        }

        // Zuletzt ausgegeben war 101; fortgesetzt wird bei 101 + 1 — nicht bei
        // 103, was die geschuetzte Anweisung allein hinterlassen haette.
        nextValue("exec_one") shouldBe 102L
    }

    test("Applied: a multi-sequence batch reports in locked (sorted) order") {
        exec(
            "CREATE SEQUENCE [exec_z] AS BIGINT START WITH 200 INCREMENT BY 1",
            "CREATE SEQUENCE [exec_a] AS BIGINT START WITH 300 INCREMENT BY 1",
        )
        nextValue("exec_z")
        nextValue("exec_a")

        // Der Aufrufer gibt sie rueckwaerts — gesperrt wird trotzdem sortiert,
        // sonst warteten zwei parallele Laeufe ueber Kreuz.
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref("exec_z"), definition),
                AtomicSequencePreserveRequest(ref("exec_a"), definition),
            ),
            protectedOperationIds = listOf(protectedOp),
            internalFollowUpIds = listOf("op-multi"),
        )

        pool.borrow().asJdbc().use { c ->
            val result = executor.execute(JdbcDatabaseConnection(c), batch, lockTimeoutMillis = 5_000) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
                .refs shouldBe listOf(ref("exec_a"), ref("exec_z"))
        }
    }

    test("NotFound: a missing sequence rolls the batch back instead of guessing") {
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(AtomicSequencePreserveRequest(ref("exec_absent"))),
            protectedOperationIds = listOf(protectedOp),
            internalFollowUpIds = listOf("op-absent"),
        )

        pool.borrow().asJdbc().use { c ->
            val result = executor.execute(JdbcDatabaseConnection(c), batch, lockTimeoutMillis = 5_000) { _, _ ->
                error("die geschuetzte Anweisung darf gar nicht laufen")
            }
            (result is AtomicSequencePreserveResult.NotFound) shouldBe true
        }
    }

    // ── Der Weg ueber `schema migrate --execute` ───────────────────────

    fun runnerWith(source: SchemaDefinition, target: SchemaDefinition): SchemaMigrateRunner {
        var dbLoads = 0
        return SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "desired", schema = source, validation = ValidationResult())
            },
            dbLoader = { _, _ ->
                val schema = if (dbLoads++ == 0) target else source
                ResolvedSchemaOperand(
                    reference = "db:test",
                    schema = schema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.MSSQL,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else null },
            executor = { _, _, segments, lockTimeoutMs, _ ->
                executeSegmentsAgainstPool(pool, segments, MssqlAtomicSequencePreserveExecutor(), lockTimeoutMs)
            },
            renderReport = { r, _ -> r.toString() },
            printError = { _, _ -> },
        )
    }

    fun sequences(name: String, increment: Long) = SchemaDefinition(
        name = "App", version = "1",
        sequences = mapOf(
            name to SequenceDefinition(start = 100L, increment = increment, preserveCurrentValue = true),
        ),
    )

    test("a migrate with preserveCurrentValue keeps the value the server had") {
        exec("CREATE SEQUENCE [migrate_one] AS BIGINT START WITH 100 INCREMENT BY 1")
        nextValue("migrate_one") shouldBe 100L
        nextValue("migrate_one") shouldBe 101L

        val exit = runnerWith(sequences("migrate_one", 5L), sequences("migrate_one", 1L)).execute(
            SchemaMigrateRequest(
                source = "file:${tmp.resolve("desired.yaml")}",
                target = "db:placeholder",
                dialect = DatabaseDialect.MSSQL,
                execute = true,
                report = tmp.resolve("report.json"),
            ),
        )

        exit shouldBe 0
        // Die Sequenz traegt jetzt `INCREMENT BY 5`, und fortgesetzt wird bei
        // dem, was der Server hatte, plus der NEUEN Schrittweite.
        withClue("der vorgefundene Stand ueberlebt die Aenderung") { nextValue("migrate_one") shouldBe 106L }
    }
})
