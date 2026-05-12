package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldEndWith

class RollbackArtefactBuilderTest : FunSpec({

    fun stmt(sql: String) = MigrationDdlStatement(
        sql = sql,
        operationIds = setOf("op-1"),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    fun risk() = RollbackArtefactBuilder.Risk(
        destructive = false,
        dataLossPossible = false,
        requiresManualConfirmation = false,
        operationIds = setOf("op-1"),
    )

    test("canonical artefact starts with the begin delimiter and ends with a single trailing newline") {
        val out = RollbackArtefactBuilder.build(
            RollbackArtefactBuilder.Input(
                dialect = DatabaseDialect.POSTGRESQL,
                currentFingerprint = "fp-current",
                desiredFingerprint = "fp-desired",
                postUpFingerprint = "fp-desired",
                operationIds = setOf("op-1"),
                risk = risk(),
                downStatements = listOf(stmt("DROP TABLE x;")),
                createdByVersion = "test/0.0.0",
            ),
        )
        out shouldStartWith "-- d-migrate rollback-sql v2 begin\n"
        out shouldContain "-- d-migrate rollback-sql v2 end\n"
        out shouldEndWith "DROP TABLE x;\n"
    }

    test("metadata header is canonical JSON with sorted keys") {
        val out = RollbackArtefactBuilder.build(
            RollbackArtefactBuilder.Input(
                dialect = DatabaseDialect.POSTGRESQL,
                currentFingerprint = "fp-current",
                desiredFingerprint = "fp-desired",
                postUpFingerprint = "fp-desired",
                operationIds = setOf("op-z", "op-a"),
                risk = risk(),
                downStatements = listOf(stmt("SELECT 1;")),
                createdByVersion = "v0",
            ),
        )
        // operationIds are sorted alphabetically: ["op-a","op-z"]
        out shouldContain "\"operationIds\":[\"op-a\",\"op-z\"]"
        out shouldContain "\"formatVersion\":\"v2\""
        out shouldContain "\"statementIndex\":["
        out shouldContain "\"transactionScope\":\"RUNNER_OWNED\""
        // Top-level keys are sorted: artifactHash, artifactHashAlgorithm, createdByVersion, currentFingerprint, …
        out shouldContain "\"artifactHash\":"
        // artifactHash precedes artifactHashAlgorithm
        val hashIdx = out.indexOf("\"artifactHash\"")
        val algIdx = out.indexOf("\"artifactHashAlgorithm\"")
        (hashIdx < algIdx) shouldBe true
    }

    test("artifactHash changes when the SQL body changes") {
        val a = RollbackArtefactBuilder.build(
            RollbackArtefactBuilder.Input(
                dialect = DatabaseDialect.POSTGRESQL,
                currentFingerprint = "fp-c",
                desiredFingerprint = "fp-d",
                postUpFingerprint = "fp-d",
                operationIds = setOf("op-1"),
                risk = risk(),
                downStatements = listOf(stmt("DROP TABLE x;")),
                createdByVersion = "v0",
            ),
        )
        val b = RollbackArtefactBuilder.build(
            RollbackArtefactBuilder.Input(
                dialect = DatabaseDialect.POSTGRESQL,
                currentFingerprint = "fp-c",
                desiredFingerprint = "fp-d",
                postUpFingerprint = "fp-d",
                operationIds = setOf("op-1"),
                risk = risk(),
                downStatements = listOf(stmt("DROP TABLE y;")),
                createdByVersion = "v0",
            ),
        )
        val hashA = Regex("\"artifactHash\":\"([a-f0-9]+)\"").find(a)!!.groupValues[1]
        val hashB = Regex("\"artifactHash\":\"([a-f0-9]+)\"").find(b)!!.groupValues[1]
        hashA shouldNotBe hashB
    }

    test("artifactHash changes when fingerprints change") {
        val a = RollbackArtefactBuilder.build(
            RollbackArtefactBuilder.Input(
                dialect = DatabaseDialect.POSTGRESQL,
                currentFingerprint = "fp-c1",
                desiredFingerprint = "fp-d",
                postUpFingerprint = "fp-d",
                operationIds = setOf("op-1"),
                risk = risk(),
                downStatements = listOf(stmt("DROP TABLE x;")),
                createdByVersion = "v0",
            ),
        )
        val b = RollbackArtefactBuilder.build(
            RollbackArtefactBuilder.Input(
                dialect = DatabaseDialect.POSTGRESQL,
                currentFingerprint = "fp-c2",
                desiredFingerprint = "fp-d",
                postUpFingerprint = "fp-d",
                operationIds = setOf("op-1"),
                risk = risk(),
                downStatements = listOf(stmt("DROP TABLE x;")),
                createdByVersion = "v0",
            ),
        )
        val hashA = Regex("\"artifactHash\":\"([a-f0-9]+)\"").find(a)!!.groupValues[1]
        val hashB = Regex("\"artifactHash\":\"([a-f0-9]+)\"").find(b)!!.groupValues[1]
        hashA shouldNotBe hashB
    }

    test("recovery=true emits allowedPostUpFingerprints in the header") {
        val out = RollbackArtefactBuilder.build(
            RollbackArtefactBuilder.Input(
                dialect = DatabaseDialect.SQLITE,
                currentFingerprint = "fp-c",
                desiredFingerprint = "fp-d",
                postUpFingerprint = "fp-observed",
                operationIds = setOf("op-1"),
                risk = risk(),
                downStatements = listOf(stmt("DROP TABLE x;")),
                createdByVersion = "v0",
                recovery = true,
                postUpVerified = true,
                allowedPostUpFingerprints = listOf("fp-observed"),
            ),
        )
        out shouldContain "\"recovery\":true"
        out shouldContain "\"postUpVerified\":true"
        out shouldContain "\"allowedPostUpFingerprints\":[\"fp-observed\"]"
    }

    test("identical inputs produce byte-identical output (deterministic)") {
        repeat(3) {
            val a = RollbackArtefactBuilder.build(
                RollbackArtefactBuilder.Input(
                    dialect = DatabaseDialect.MYSQL,
                    currentFingerprint = "fp-c",
                    desiredFingerprint = "fp-d",
                    postUpFingerprint = "fp-d",
                    operationIds = setOf("op-1", "op-2"),
                    risk = risk(),
                    downStatements = listOf(stmt("DROP TABLE x;")),
                    createdByVersion = "v0",
                ),
            )
            val b = RollbackArtefactBuilder.build(
                RollbackArtefactBuilder.Input(
                    dialect = DatabaseDialect.MYSQL,
                    currentFingerprint = "fp-c",
                    desiredFingerprint = "fp-d",
                    postUpFingerprint = "fp-d",
                    operationIds = setOf("op-1", "op-2"),
                    risk = risk(),
                    downStatements = listOf(stmt("DROP TABLE x;")),
                    createdByVersion = "v0",
                ),
            )
            a shouldBe b
        }
    }
})
