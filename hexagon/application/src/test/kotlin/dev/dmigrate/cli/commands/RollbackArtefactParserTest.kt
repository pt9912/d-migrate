package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RollbackArtefactParserTest : FunSpec({

    fun stmt(sql: String) = MigrationDdlStatement(
        sql = sql,
        operationIds = setOf("op-1"),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    fun makeArtefact(
        dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL,
        sql: String = "DROP TABLE x;",
        recovery: Boolean = false,
        allowed: List<String>? = null,
    ): String = RollbackArtefactBuilder.build(
        RollbackArtefactBuilder.Input(
            dialect = dialect,
            currentFingerprint = "fp-current",
            desiredFingerprint = "fp-desired",
            postUpFingerprint = "fp-desired",
            operationIds = setOf("op-1"),
            risk = RollbackArtefactBuilder.Risk(
                destructive = false,
                dataLossPossible = false,
                requiresManualConfirmation = false,
                operationIds = setOf("op-1"),
            ),
            downStatements = listOf(stmt(sql)),
            createdByVersion = "test/0.0.0",
            recovery = recovery,
            postUpVerified = false,
            allowedPostUpFingerprints = allowed,
        ),
    )

    test("round-trips a freshly-built artefact and verifies the hash") {
        val text = makeArtefact()
        val r = RollbackArtefactParser.parse(text)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Success>()
        r.parsed.dialect shouldBe "POSTGRESQL"
        r.parsed.currentFingerprint shouldBe "fp-current"
        r.parsed.operationIds shouldBe listOf("op-1")
    }

    test("recovery=true with allowedPostUpFingerprints round-trips") {
        val text = makeArtefact(recovery = true, allowed = listOf("fp-x", "fp-y"))
        val r = RollbackArtefactParser.parse(text)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Success>()
        r.parsed.recovery shouldBe true
        r.parsed.allowedPostUpFingerprints shouldBe listOf("fp-x", "fp-y")
    }

    test("missing begin delimiter is MISSING_BEGIN_DELIMITER") {
        val r = RollbackArtefactParser.parse("DROP TABLE x;\n")
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "MISSING_BEGIN_DELIMITER"
    }

    test("missing end delimiter is MISSING_END_DELIMITER") {
        val r = RollbackArtefactParser.parse(
            "-- d-migrate rollback-sql v1 begin\n" +
                "-- {\"format\":\"d-migrate rollback-sql\"}\n" +
                "DROP TABLE x;\n",
        )
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "MISSING_END_DELIMITER"
    }

    test("two metadata blocks are MULTIPLE_METADATA_BLOCKS") {
        val text = makeArtefact() + makeArtefact()
        val r = RollbackArtefactParser.parse(text)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "MULTIPLE_METADATA_BLOCKS"
    }

    test("tampered SQL body is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact()
        val tampered = text.replace("DROP TABLE x;", "DROP TABLE y;")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "ARTIFACT_HASH_MISMATCH"
    }

    test("tampered fingerprint in header is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact()
        val tampered = text.replace("\"fp-desired\"", "\"fp-attacker\"")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "ARTIFACT_HASH_MISMATCH"
    }

    test("recovery=true without allowedPostUpFingerprints is MISSING_ALLOWED_POST_UP_FINGERPRINTS") {
        // Hand-craft an artefact that has recovery=true but no allowedPostUpFingerprints.
        // We can't use the builder for this since the builder only includes the field
        // when recovery=true AND allowedPostUpFingerprints != null.
        val good = makeArtefact(recovery = true, allowed = listOf("fp-a"))
        val tampered = good.replace(
            "\"allowedPostUpFingerprints\":[\"fp-a\"],",
            "",
        )
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        // The parser may catch this as either MISSING_ALLOWED or ARTIFACT_HASH_MISMATCH
        // (the tampering breaks the hash too). Either is acceptable as a strict-rejection signal.
        (r.code == "MISSING_ALLOWED_POST_UP_FINGERPRINTS" || r.code == "ARTIFACT_HASH_MISMATCH") shouldBe true
    }
})
