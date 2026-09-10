package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationExecutionTrace
import dev.dmigrate.driver.migration.UncompiledObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Ein `CREATE OR REPLACE` mit einem Fehler im Rumpf gelingt bei Oracle ueber
 * JDBC und laesst das Objekt unbenutzbar zurueck. Was die Sonde findet, muss
 * den Lauf zu Fall bringen — sonst meldet `--execute` Erfolg fuer ein Ziel,
 * das nicht benutzbar ist.
 */
class SchemaMigrateExecutionStagePostApplyStatusTest : FunSpec({

    val plan = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = emptyList(),
    )

    val request = SchemaMigrateRequest(
        source = "file:src",
        target = "db:test",
        dialect = DatabaseDialect.ORACLE,
        execute = true,
    )
    val target = CompareOperand.Database("db:test")

    val applied = MigrationExecutionTrace(
        executionStarted = true,
        executionCompleted = true,
        statementsAttempted = 1,
        sideEffectsPossible = true,
    )

    fun stage(
        probe: PostApplyStatusProbeFn?,
        printed: MutableList<String> = mutableListOf(),
    ) = SchemaMigrateExecutionStage(
        executor = null,
        dbLoader = null,
        normalizer = { it },
        fingerprint = MigrationFingerprint::compute,
        printError = { message, _ -> printed += message },
        postApplyStatusProbe = probe,
    )

    fun uncompiled(name: String) = UncompiledObject(
        name = name,
        objectType = "FUNCTION",
        line = 1,
        position = 49,
        message = "PLS-00201: identifier 'KEIN_BEZEICHNER' must be declared",
    )

    test("ein nicht uebersetztes Objekt bringt den Lauf zu Fall, statt ihn gelingen zu lassen") {
        val trace = stage({ _, _, _, _ -> listOf(uncompiled("f")) })
            .checkPostApplyStatus(request, target, DatabaseDialect.ORACLE, plan, applied)

        trace!!.executionError!! shouldContain "FUNCTION f (Zeile 1, Spalte 49)"
        trace.executionError!! shouldContain "PLS-00201"
        // Das DDL steht — die Spur darf nicht behaupten, es sei nichts passiert.
        trace.sideEffectsPossible shouldBe true
    }

    test("ohne Befund bleibt die Spur, wie der Executor sie gab") {
        stage({ _, _, _, _ -> emptyList() })
            .checkPostApplyStatus(request, target, DatabaseDialect.ORACLE, plan, applied) shouldBe applied
    }

    test("eine bereits gescheiterte Ausfuehrung wird nicht umgedeutet") {
        val failed = applied.copy(executionError = "ORA-00942: table or view does not exist")

        stage({ _, _, _, _ -> listOf(uncompiled("f")) })
            .checkPostApplyStatus(request, target, DatabaseDialect.ORACLE, plan, failed) shouldBe failed
    }

    test("ohne Ausfuehrung wird nicht nachgefragt") {
        stage({ _, _, _, _ -> error("die Sonde darf ohne Ausfuehrung nicht laufen") })
            .checkPostApplyStatus(request, target, DatabaseDialect.ORACLE, plan, null).shouldBeNull()
    }

    test("scheitert die Nachfrage selbst, gilt der Lauf nicht als geprueft — und sagt es") {
        val printed = mutableListOf<String>()
        val trace = stage({ _, _, _, _ -> error("ORA-00942: table or view ALL_ERRORS does not exist") }, printed)
            .checkPostApplyStatus(request, target, DatabaseDialect.ORACLE, plan, applied)

        // Der Lauf selbst bleibt gelungen: gescheitert ist die Nachfrage, nicht
        // das DDL. Verschwiegen wird sie trotzdem nicht.
        trace shouldBe applied
        printed.single() shouldContain "unverified"
    }

    test("viele Befunde werden gezaehlt statt ausgerollt") {
        val many = (1..8).map { uncompiled("f$it") }

        val trace = stage({ _, _, _, _ -> many })
            .checkPostApplyStatus(request, target, DatabaseDialect.ORACLE, plan, applied)

        trace!!.executionError!! shouldContain "8 object error(s)"
        trace.executionError!! shouldContain "(und 3 weitere)"
    }
})
