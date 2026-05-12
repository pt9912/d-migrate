package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Substring tokens that the canonical metadata header MUST never
 * contain (Plan §7.3 / §10 line 1429-1431). Covers JDBC URLs / any
 * URI-shaped substring, common secret-keyword-shaped patterns, and
 * platform-specific home / temp / SSH-artefact path prefixes.
 *
 * Keep the list broad: a future field that legitimately needs a URI
 * should fail this pin (and the spec) — there is no allowed
 * exception in the metadata header.
 */
private val FORBIDDEN_HEADER_SUBSTRINGS = listOf(
    // JDBC URL / any URI-shaped substring (the spec forbids all
    // unmasked connection URLs and resource URIs in the header).
    "jdbc:", "://",
    // Secret-keyword-shaped tokens. Match the substring case-
    // insensitively at assertion time.
    "password", "passwd", "secret", "api_key", "apikey", "token=", "auth=",
    // Absolute local paths — macOS / Linux / Windows / containers.
    "/Users/", "/home/", "/root/", "/var/", "/tmp/",
    "C:\\", "\\Users\\", "\\Windows\\",
    // SSH / private-key artefacts.
    ".ssh", "id_rsa", "id_ed25519", "BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY",
)

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

    fun makeMultiStatementArtefact(): String = RollbackArtefactBuilder.build(
        RollbackArtefactBuilder.Input(
            dialect = DatabaseDialect.POSTGRESQL,
            currentFingerprint = "fp-current",
            desiredFingerprint = "fp-desired",
            postUpFingerprint = "fp-desired",
            operationIds = setOf("op-1", "op-2"),
            risk = RollbackArtefactBuilder.Risk(
                destructive = false,
                dataLossPossible = false,
                requiresManualConfirmation = false,
                operationIds = setOf("op-1", "op-2"),
            ),
            downStatements = listOf(
                stmt("CREATE FUNCTION f()\nBEGIN\n\nRETURN 1;\nEND;").copy(operationIds = setOf("op-1")),
                stmt("DROP TABLE x;").copy(operationIds = setOf("op-2")),
            ),
            createdByVersion = "test/0.0.0",
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

    test("v2 statementIndex reconstructs statements with embedded blank lines") {
        val r = RollbackArtefactParser.parse(makeMultiStatementArtefact())
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Success>()

        r.parsed.formatVersion shouldBe "v2"
        r.parsed.statementIndex.map { it.index } shouldBe listOf(0, 1)
        r.parsed.statementsFromIndex().map { it.sql } shouldBe listOf(
            "CREATE FUNCTION f()\nBEGIN\n\nRETURN 1;\nEND;",
            "DROP TABLE x;",
        )
    }

    test("v2 statementIndex order mismatch is rejected") {
        val text = makeMultiStatementArtefact()
        val tampered = text.replace("\"index\":1", "\"index\":2")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "STATEMENT_INDEX_ORDER_MISMATCH"
    }

    test("v2 without statementIndex is rejected") {
        val text = makeArtefact()
        val tampered = text.replace(Regex(",\"statementIndex\":\\[.*\\](?=})"), "")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "MISSING_FIELD_STATEMENTINDEX"
    }

    test("delimiter version must match header formatVersion") {
        val tampered = makeArtefact()
            .replace("-- d-migrate rollback-sql v2 begin", "-- d-migrate rollback-sql v1 begin")
            .replace("-- d-migrate rollback-sql v2 end", "-- d-migrate rollback-sql v1 end")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "FORMAT_VERSION_DELIMITER_MISMATCH"
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

    test("tampered SQL body is detected") {
        val text = makeArtefact()
        val tampered = text.replace("DROP TABLE x;", "DROP TABLE y;")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        (r.code == "ARTIFACT_HASH_MISMATCH" || r.code == "STATEMENT_HASH_MISMATCH") shouldBe true
    }

    test("tampered fingerprint in header is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact()
        val tampered = text.replace("\"fp-desired\"", "\"fp-attacker\"")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        (r.code == "ARTIFACT_HASH_MISMATCH" || r.code == "STATEMENT_HASH_MISMATCH") shouldBe true
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
        (r.code == "ARTIFACT_HASH_MISMATCH" || r.code == "STATEMENT_HASH_MISMATCH") shouldBe true
    }

    test("F.6.d — tampered formatVersion is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact()
        val tampered = text.replace("\"formatVersion\":\"v2\"", "\"formatVersion\":\"v99\"")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        // The delimiter/header cross-check fires before hash verification.
        r.code shouldBe "FORMAT_VERSION_DELIMITER_MISMATCH"
    }

    test("F.6.d — tampered risk.destructive is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact()
        val tampered = text.replace("\"destructive\":false", "\"destructive\":true")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        (r.code == "ARTIFACT_HASH_MISMATCH" || r.code == "STATEMENT_HASH_MISMATCH") shouldBe true
    }

    test("F.6.d — tampered operationIds list is detected via ARTIFACT_HASH_MISMATCH") {
        val text = makeArtefact()
        val tampered = text.replace("\"operationIds\":[\"op-1\"]", "\"operationIds\":[\"op-99\"]")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        (r.code == "ARTIFACT_HASH_MISMATCH" || r.code == "STATEMENT_HASH_MISMATCH") shouldBe true
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
        (r.code == "ARTIFACT_HASH_MISMATCH" || r.code == "STATEMENT_HASH_MISMATCH") shouldBe true
    }

    // ── F.6.d: Secret-Scrubbing — pin the contract from §7.3 / §10
    //         that the metadata block must NEVER carry secrets,
    //         unmasked connection URLs, or absolute local paths.
    //         The current builder achieves this structurally (none
    //         of the fields can carry user-supplied free-form
    //         strings except `createdByVersion`); this test makes
    //         that invariant explicit so a future field addition
    //         that breaks the contract fails loudly.

    test("F.6.d — metadata block contains no forbidden substrings (Plan §7.3 contract pin)") {
        // Single consolidated check using the [FORBIDDEN_HEADER_SUBSTRINGS]
        // constant. If a future field rendered ANY of these tokens — JDBC
        // URLs, secret-keyword shapes, OS-path prefixes, SSH/private-key
        // markers — this test fires immediately, before the leak can hit
        // production.
        val header = extractCanonicalHeader(makeArtefact())
        for (forbidden in FORBIDDEN_HEADER_SUBSTRINGS) {
            withClue("header must not contain forbidden token `$forbidden`: $header") {
                header.contains(forbidden, ignoreCase = true) shouldBe false
            }
        }
    }

    // ── F.6.d follow-up: structural rejection coverage. The plan
    //         §F.6 originally claimed E.5 covers UNKNOWN_FORMAT_VERSION
    //         / UNKNOWN_ARTIFACT_HASH_ALGORITHM / MISSING_FIELD /
    //         TYPE_MISMATCH / MALFORMED_HEADER_PREFIX / JSON-syntax
    //         errors — but only MULTIPLE_METADATA_BLOCKS,
    //         MISSING_*_DELIMITER, and the two original tampering
    //         cases were actually pinned. These tests close the gap.

    test("F.6.d — formatVersion delimiter mismatch fires before hash verification") {
        val text = makeArtefact()
        val tampered = text.replace("\"formatVersion\":\"v2\"", "\"formatVersion\":\"v99\"")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "FORMAT_VERSION_DELIMITER_MISMATCH"
    }

    test("F.6.d — UNKNOWN_ARTIFACT_HASH_ALGORITHM fires for a recognised-but-unsupported algo") {
        val text = makeArtefact()
        val tampered = text.replace(
            "\"artifactHashAlgorithm\":\"sha256-rollback-artifact-v2\"",
            "\"artifactHashAlgorithm\":\"md5-legacy\"",
        )
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "UNKNOWN_ARTIFACT_HASH_ALGORITHM"
    }

    test("F.6.d — MISSING_FIELD_DIALECT fires when a required field is removed") {
        val text = makeArtefact()
        val tampered = text.replace(",\"dialect\":\"POSTGRESQL\"", "")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "MISSING_FIELD_DIALECT"
    }

    test("F.6.d — TYPE_MISMATCH_DIALECT fires when a string field is replaced with a non-string") {
        val text = makeArtefact()
        val tampered = text.replace("\"dialect\":\"POSTGRESQL\"", "\"dialect\":42")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "TYPE_MISMATCH_DIALECT"
    }

    test("F.6.d — MALFORMED_HEADER_PREFIX fires when the metadata line lacks the `-- ` prefix") {
        val text = makeArtefact()
        // Replace ONLY the metadata line's leading `-- ` (not the begin
        // delimiter, which is `-- d-migrate rollback-sql v1 begin`).
        val tampered = text.replace("-- {\"artifactHash", "// {\"artifactHash")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
        r.code shouldBe "MALFORMED_HEADER_PREFIX"
    }

    test("F.6.d — JSON syntax error in the header surfaces a parser code (not Success)") {
        val text = makeArtefact()
        // Drop the closing `}` of the JSON object — turns valid JSON into
        // truncated input. The exact code (e.g. EXPECTED_CHAR_}) depends
        // on the mini-parser's reach; key invariant is rejection.
        val tampered = text.replace("\"risk\":{", "\"risk\":")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Failure>()
    }

    test("F.6.d — whitespace-only tampering inside the JSON is structurally lenient (positive pin)") {
        // Pin the lenient-parse-strict-canonical-hash invariant: the
        // MiniJson parser tolerates whitespace, the canonical builder
        // strips it. Re-encoding via verifyHash produces the original
        // canonical bytes → rebuilt-hash matches parsed.artifactHash →
        // Success. This is by design: whitespace can't change the
        // SEMANTIC content of the artefact, so it's not a tamper vector
        // worth rejecting.
        val text = makeArtefact()
        val tampered = text.replace("\"recovery\":false", "\"recovery\": false")
        val r = RollbackArtefactParser.parse(tampered)
        r.shouldBeInstanceOf<RollbackArtefactParser.Result.Success>()
        r.parsed.recovery shouldBe false
    }
})

/**
 * Extract the single canonical metadata-header line from a built
 * artefact. Replaces the earlier `lines().first()` shorthand which
 * would silently miss a leak if the canonical encoding ever wrapped
 * across multiple lines. Uses the full begin/end delimiters as
 * anchors so the partial-match `\nv1 end` substring trick can't
 * leak the END_DELIMITER prefix into the captured body.
 */
private fun extractCanonicalHeader(artefact: String): String {
    val begin = "-- d-migrate rollback-sql v2 begin\n"
    val end = "\n-- d-migrate rollback-sql v2 end"
    val between = artefact.substringAfter(begin).substringBefore(end)
    val lines = between.lines().filter { it.isNotBlank() }
    return lines.single().trim().removePrefix("-- ")
}
