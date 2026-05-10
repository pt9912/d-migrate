package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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

    // ── F.6.d: Round-Trip-Tampering — extended coverage beyond the
    //         two existing tampering cases (SQL body + fingerprint).
    //         Each test mutates a different metadata field and pins
    //         that the parser refuses the resulting artefact via
    //         ARTIFACT_HASH_MISMATCH (or, where the mutation also
    //         breaks structural pre-checks, the corresponding
    //         structural error). The point is to demonstrate that
    //         the artifactHash covers EVERY field, not just the two
    //         already proven.

    test("F.6.d — tampered recovery=false → true is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact(recovery = false)
        val tampered = text.replace("\"recovery\":false", "\"recovery\":true")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        // The flip alone breaks the hash; without allowedPostUpFingerprints
        // the parser may also raise MISSING_ALLOWED. Both signal strict
        // rejection — important is that no Success is returned.
        (r.code == "ARTIFACT_HASH_MISMATCH" || r.code == "MISSING_ALLOWED_POST_UP_FINGERPRINTS") shouldBe true
    }

    test("F.6.d — tampered dialect value is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact(dialect = DatabaseDialect.POSTGRESQL)
        val tampered = text.replace("\"dialect\":\"POSTGRESQL\"", "\"dialect\":\"MYSQL\"")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "ARTIFACT_HASH_MISMATCH"
    }

    test("F.6.d — tampered formatVersion is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact()
        val tampered = text.replace("\"formatVersion\":\"v1\"", "\"formatVersion\":\"v2\"")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        // Either an UNKNOWN_FORMAT_VERSION pre-hash check or the hash
        // mismatch — both are strict-rejection signals.
        (r.code == "UNKNOWN_FORMAT_VERSION" || r.code == "ARTIFACT_HASH_MISMATCH") shouldBe true
    }

    test("F.6.d — tampered risk.destructive is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact()
        val tampered = text.replace("\"destructive\":false", "\"destructive\":true")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "ARTIFACT_HASH_MISMATCH"
    }

    test("F.6.d — tampered operationIds list is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact()
        val tampered = text.replace("\"operationIds\":[\"op-1\"]", "\"operationIds\":[\"op-99\"]")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "ARTIFACT_HASH_MISMATCH"
    }

    test("F.6.d — tampered artifactHash itself (re-hash dance) is detected") {
        // Attacker substitutes BOTH the body and the artifactHash to a
        // self-consistent pair (common attack — without re-knowing the
        // canonical-encoding, they pick the wrong hash). Pin: the parser
        // recomputes via the documented algorithm and rejects.
        val text = makeArtefact()
        val tampered = text
            .replace("DROP TABLE x;", "DROP TABLE attacker;")
            // Substitute the hash with a plausible-looking but wrong value.
            .replace(
                Regex("\"artifactHash\":\"[0-9a-f]+\""),
                "\"artifactHash\":\"" + "0".repeat(64) + "\"",
            )
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "ARTIFACT_HASH_MISMATCH"
    }

    // ── F.6.d: Secret-Scrubbing — pin the contract from §7.3 / §10
    //         that the metadata block must NEVER carry secrets,
    //         unmasked connection URLs, or absolute local paths.
    //         The current builder achieves this structurally (none
    //         of the fields can carry user-supplied free-form
    //         strings except `createdByVersion`); this test makes
    //         that invariant explicit so a future field addition
    //         that breaks the contract fails loudly.

    test("F.6.d — metadata block carries no JDBC URL / connection-string-shaped substrings") {
        val text = makeArtefact()
        val header = text.substringAfter("v1 begin\n").substringBefore("\nv1 end").lines().first()
        // None of the documented fields ever carry a JDBC URL — verify
        // this empirically against the canonical text.
        header shouldNotContain "jdbc:"
        header shouldNotContain "://"
    }

    test("F.6.d — metadata block carries no obvious secret-keyword-shaped substrings") {
        // Negative pin: rendering normal inputs must NOT produce a
        // header that looks like it carries a secret. If a future field
        // contains user-supplied free-text, this test fires when the
        // field name OR a default value matches a secret-keyword token.
        val text = makeArtefact()
        val header = text.substringAfter("v1 begin\n").substringBefore("\nv1 end").lines().first()
        for (forbidden in listOf("password", "passwd", "secret", "api_key", "token=", "/Users/", "/home/")) {
            header.contains(forbidden, ignoreCase = true) shouldBe false
        }
    }
})
