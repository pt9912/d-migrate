package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.diff.migration.RawSqlTextProjection
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Das Rollback-Artefakt, das `schema migrate --execute --generate-rollback` fuer
 * den erreichten Zustand schreibt, haelt den Post-Compare-Fingerabdruck — und
 * der blendet rohen SQL-Text aus ([RawSqlTextProjection]). `schema rollback`
 * muss das Ziel durch dieselbe Projektion legen, sonst endet ein unveraenderter
 * Zustand mit `TARGET_STATE_MISMATCH`, sobald das Schema einen CHECK-Ausdruck
 * oder ein Index-Praedikat traegt (GitHub-Issue #46).
 */
class SchemaRollbackRunnerRawSqlTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("rollback-raw-sql-test")

    fun schemaWith(table: TableDefinition) = SchemaDefinition(name = "demo", version = "1", tables = mapOf("t" to table))

    val columns = mapOf(
        "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
        "j" to ColumnDefinition(type = NeutralType.Integer),
    )

    fun checkTable(expression: String) = TableDefinition(
        columns = columns,
        primaryKey = listOf("id"),
        constraints = listOf(ConstraintDefinition("chk_t", ConstraintType.CHECK, expression = expression)),
    )

    fun indexTable(where: String) = TableDefinition(
        columns = columns,
        primaryKey = listOf("id"),
        indices = listOf(IndexDefinition(name = "ix_t", columns = listOf(IndexColumn(name = "j")), where = where)),
    )

    /** Rollback gegen ein Ziel, das [authored] als [served] zurueckgibt; das Artefakt traegt den Migrate-Wert. */
    fun rollbackExit(authored: SchemaDefinition, served: SchemaDefinition, name: String): Int {
        val postUpFp = MigrationFingerprint.compute(RawSqlTextProjection.blank(authored))
        val artefact = tmpDir.resolve("$name.sql")
        Files.writeString(
            artefact,
            buildArtefact(currentFp = "fp-pre", desiredFp = postUpFp, postUpFp = postUpFp, postUpVerified = true),
        )
        val runner = SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = served,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            printError = { _, _ -> },
        )
        return runner.execute(
            SchemaRollbackRequest(source = artefact, target = "db:postgres://localhost", execute = true),
        )
    }

    test("roher CHECK: Katalogform des Servers weicht vom Autorentext ab, der Rollback laeuft") {
        rollbackExit(
            authored = schemaWith(checkTable("id >= 1")),
            served = schemaWith(checkTable("((id >= 1))")),
            name = "check",
        ) shouldBe 0
    }

    test("rohes Index-Praedikat: Katalogform des Servers weicht vom Autorentext ab, der Rollback laeuft") {
        rollbackExit(
            authored = schemaWith(indexTable("j IS NOT NULL")),
            served = schemaWith(indexTable("(j IS NOT NULL)")),
            name = "index",
        ) shouldBe 0
    }

    test("ohne rohen Text bleibt der Abgleich streng: ein anderer Spaltentyp ist weiter TARGET_STATE_MISMATCH") {
        val authored = schemaWith(TableDefinition(columns = columns, primaryKey = listOf("id")))
        val drifted = schemaWith(
            TableDefinition(
                columns = columns + ("extra" to ColumnDefinition(type = NeutralType.Integer)),
                primaryKey = listOf("id"),
            ),
        )
        rollbackExit(authored = authored, served = drifted, name = "drift") shouldBe 8
    }
})
