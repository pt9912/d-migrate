package dev.dmigrate.mcp.schema

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class SchemaSecretGuardTest : FunSpec({

    test("clean schema has no leaks") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "tenantId" to mapOf("type" to "string"),
                "jobId" to mapOf("type" to "string"),
            ),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldBe emptyList()
    }

    test("top-level password property is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("password" to mapOf("type" to "string")),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldContain "properties.password"
    }

    test("nested object's apiKey property is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "connection" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("apiKey" to mapOf("type" to "string")),
                ),
            ),
        )
        val leaks = SchemaSecretGuard.findSecretLeaks(schema)
        leaks shouldContain "properties.connection.properties.apiKey"
    }

    test("array items' password property is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "connections" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("password" to mapOf("type" to "string")),
                    ),
                ),
            ),
        )
        val leaks = SchemaSecretGuard.findSecretLeaks(schema)
        leaks.any { it.contains("password") } shouldBe true
    }

    test("oneOf-branch leaks are caught") {
        val schema = mapOf(
            "oneOf" to listOf(
                mapOf(
                    "type" to "object",
                    "properties" to mapOf("token" to mapOf("type" to "string")),
                ),
                mapOf("type" to "string"),
            ),
        )
        SchemaSecretGuard.findSecretLeaks(schema).any { it.contains("token") } shouldBe true
    }

    test("\$defs branch leaks are caught") {
        val schema = mapOf(
            "type" to "object",
            "\$defs" to mapOf(
                "Auth" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("jdbcUrl" to mapOf("type" to "string")),
                ),
            ),
        )
        SchemaSecretGuard.findSecretLeaks(schema).any { it.contains("jdbcUrl") } shouldBe true
    }

    test("case-insensitive match for forbidden names") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "PASSWORD" to mapOf("type" to "string"),
                "Api_Key" to mapOf("type" to "string"),
            ),
        )
        val leaks = SchemaSecretGuard.findSecretLeaks(schema)
        leaks.any { it.contains("PASSWORD") } shouldBe true
        leaks.any { it.contains("Api_Key") } shouldBe true
    }

    test("forbidden-property names spelled like camelCase are detected") {
        // credentialRef is on the list (lowercase normalised to "credentialref")
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("credentialRef" to mapOf("type" to "string")),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldContain "properties.credentialRef"
    }

    test("non-properties top-level keys are not treated as payload names") {
        // 'type', 'required', 'description', 'title' are JSON-Schema
        // keywords, not payload field names — the guard must not raise
        // alarms on them even if they accidentally match a forbidden
        // word later (none currently do, but the test pins the
        // recursion semantics).
        val schema = mapOf(
            "type" to "object",
            "title" to "secret",
            "description" to "contains the word password in prose",
            "required" to listOf("foo"),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldBe emptyList()
    }

    test("malformed 'properties' value (string instead of map) does not throw") {
        // A misconfigured author wrote `properties: "ref-string"`. The
        // walk should NOT match anything (no payload-name keys to
        // check) and must NOT throw — early-return path under
        // walkPropertyKeyedMap.
        val schema = mapOf<String, Any?>("properties" to "not a map")
        SchemaSecretGuard.findSecretLeaks(schema) shouldBe emptyList()
    }

    test("payload field literally named 'properties' is treated as a name, not a keyword") {
        // Outer `properties` is the JSON-Schema keyword (recursed into).
        // Inner `properties` is a payload field name — lowercased and
        // checked against FORBIDDEN_PROPERTIES (which it isn't).
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "properties" to mapOf("type" to "string"),
            ),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldBe emptyList()
    }

    test("patternProperties value-schema leak is caught") {
        // patternProperties keys are regex strings (NOT payload names)
        // — they must NOT trigger the FORBIDDEN_PROPERTIES check. The
        // value schema is still walked; a leak inside it must be
        // caught.
        val schema = mapOf(
            "type" to "object",
            "patternProperties" to mapOf(
                "^x_.*$" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("apiKey" to mapOf("type" to "string")),
                ),
            ),
        )
        val leaks = SchemaSecretGuard.findSecretLeaks(schema)
        leaks.any { it.contains("apiKey") } shouldBe true
        // The regex key itself does NOT appear as a leak path.
        leaks.any { it == "patternProperties.^x_.*\$" } shouldBe false
    }

    // ──────────────────────────────────────────────────────────────
    // AP 6.23: normalised forbidden-key detection. Variants like
    // `dbPassword`, `credentialToken`, `api_token`, `JDBCUrl` must
    // be blocked even though they are not exact lowercase matches
    // for an existing FORBIDDEN_TOKENS entry.
    // ──────────────────────────────────────────────────────────────

    test("AP 6.23: dbPassword (substring of password) is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("dbPassword" to mapOf("type" to "string")),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldContain "properties.dbPassword"
    }

    test("AP 6.23: credentialToken (two forbidden substrings) is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("credentialToken" to mapOf("type" to "string")),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldContain "properties.credentialToken"
    }

    test("AP 6.23: api_token (separator-tolerant) is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("api_token" to mapOf("type" to "string")),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldContain "properties.api_token"
    }

    test("AP 6.23: JDBCUrl (mixed case + no separator) is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("JDBCUrl" to mapOf("type" to "string")),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldContain "properties.JDBCUrl"
    }

    test("AP 6.23: separator-tolerant connection.string is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("connection.string" to mapOf("type" to "string")),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldContain "properties.connection.string"
    }

    test("AP 6.23: SchemaSecretGuard.normalize collapses case + separators") {
        SchemaSecretGuard.normalize("dbPassword") shouldBe "dbpassword"
        SchemaSecretGuard.normalize("DB_PASSWORD") shouldBe "dbpassword"
        SchemaSecretGuard.normalize("api-key") shouldBe "apikey"
        SchemaSecretGuard.normalize("Api.Token") shouldBe "apitoken"
        SchemaSecretGuard.normalize("JDBC-Url") shouldBe "jdbcurl"
    }

    test("AP 6.23: SchemaSecretGuard.isForbidden direct check") {
        SchemaSecretGuard.isForbidden("dbPassword") shouldBe true
        SchemaSecretGuard.isForbidden("credentialToken") shouldBe true
        SchemaSecretGuard.isForbidden("api_token") shouldBe true
        SchemaSecretGuard.isForbidden("JDBCUrl") shouldBe true
        SchemaSecretGuard.isForbidden("providerRef") shouldBe true
        // Negative cases.
        SchemaSecretGuard.isForbidden("tenantId") shouldBe false
        SchemaSecretGuard.isForbidden("schemaRef") shouldBe false
        SchemaSecretGuard.isForbidden("scopes") shouldBe false
    }

    test("patternProperties regex key matching a forbidden name is NOT flagged (keys aren't payload)") {
        val schema = mapOf(
            "type" to "object",
            "patternProperties" to mapOf(
                // Even if someone writes `password` as a regex pattern,
                // it's still a regex (not a payload field name) and
                // must not raise an alarm.
                "password" to mapOf("type" to "string"),
            ),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldBe emptyList()
    }
})
