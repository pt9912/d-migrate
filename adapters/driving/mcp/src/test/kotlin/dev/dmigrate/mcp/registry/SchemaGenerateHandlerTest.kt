package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSourceResolver
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val ACME = TenantId("acme")

private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-05-02T12:00:00Z"), ZoneOffset.UTC)

/**
 * Recording fake — captures the call so tests can assert that the
 * handler forwards the right [DdlGenerationOptions] and dialect.
 */
private class FakeDdlGenerator(
    override val dialect: DatabaseDialect,
    private val result: DdlResult,
) : DdlGenerator {
    var lastOptions: DdlGenerationOptions? = null
        private set

    override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        lastOptions = options
        return result
    }

    override fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult =
        result
}

private fun handler(
    fake: FakeDdlGenerator,
    limits: McpLimitsConfig = McpLimitsConfig(),
): Triple<SchemaGenerateHandler, InMemoryArtifactStore, InMemoryArtifactContentStore> {
    val schemaStore = InMemorySchemaStore()
    val artifactStore = InMemoryArtifactStore()
    val contentStore = InMemoryArtifactContentStore()
    val sink = ArtifactSink(artifactStore, contentStore, FIXED_CLOCK)
    val sut = SchemaGenerateHandler(
        resolver = SchemaSourceResolver(schemaStore, limits),
        contentLoader = SchemaContentLoader(artifactStore, contentStore, limits),
        artifactSink = sink,
        limits = limits,
        generatorLookup = { dialect ->
            require(dialect == fake.dialect) { "no generator for $dialect" }
            fake
        },
    )
    return Triple(sut, artifactStore, contentStore)
}

private fun args(content: String): JsonObject = JsonParser.parseString(content).asJsonObject

private fun parsePayload(outcome: ToolCallOutcome): JsonObject {
    val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
    return JsonParser.parseString(text).asJsonObject
}

private const val SIMPLE_SCHEMA: String =
    """{"name":"orders","version":"1.0","tables":{"t1":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}}}"""

class SchemaGenerateHandlerTest : FunSpec({

    test("inline schema produces inline DDL with structured note findings (AP 6.5)") {
        val ddl = DdlResult(
            statements = listOf(DdlStatement(sql = "CREATE TABLE t1 (id BIGINT);")),
            globalNotes = listOf(
                TransformationNote(
                    type = NoteType.WARNING,
                    code = "W042",
                    objectName = "tables.t1",
                    message = "FLOAT mapped to DECIMAL for monetary safety",
                    hint = "review precision",
                ),
            ),
        )
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.POSTGRESQL, ddl))
        val outcome = sut.handle(
            ToolCallContext(
                "schema_generate",
                args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"POSTGRESQL"}"""),
                PRINCIPAL,
            ),
        )
        val json = parsePayload(outcome)
        json.get("dialect").asString shouldBe "POSTGRESQL"
        json.get("statementCount").asInt shouldBe 1
        json.get("ddl").asString shouldBe "CREATE TABLE t1 (id BIGINT);"
        json.has("artifactRef") shouldBe false
        json.get("truncated").asBoolean shouldBe false
        val finding = json.getAsJsonArray("findings").get(0).asJsonObject
        finding.get("severity").asString shouldBe "warning"
        finding.get("code").asString shouldBe "W042"
        finding.get("hint").asString shouldBe "review precision"
    }

    test("DDL exceeding the inline threshold is moved to a tenant-scoped artefact") {
        val tinyLimits = McpLimitsConfig(maxToolResponseBytes = 64) // threshold = 32
        val largeSql = "x".repeat(40) // 40 bytes > 32 byte threshold
        val ddl = DdlResult(statements = listOf(DdlStatement(sql = largeSql)))
        val (sut, artifactStore, contentStore) = handler(
            FakeDdlGenerator(DatabaseDialect.POSTGRESQL, ddl),
            limits = tinyLimits,
        )
        val outcome = sut.handle(
            ToolCallContext(
                "schema_generate",
                args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"POSTGRESQL"}"""),
                PRINCIPAL,
            ),
        )
        val json = parsePayload(outcome)
        json.has("ddl") shouldBe false
        json.get("artifactRef").asString shouldStartWith "dmigrate://tenants/acme/artifacts/"
        json.get("truncated").asBoolean shouldBe true
        artifactStore.list(ACME, dev.dmigrate.server.core.pagination.PageRequest(20))
            .items.size shouldBe 1
    }

    test("note severities map per the wire contract (action_required → error, info → info)") {
        val ddl = DdlResult(
            statements = emptyList(),
            globalNotes = listOf(
                TransformationNote(NoteType.ACTION_REQUIRED, "E056", "sequences.s", "manual action"),
                TransformationNote(NoteType.INFO, "I001", "schema", "regenerated"),
            ),
        )
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.MYSQL, ddl))
        val outcome = sut.handle(
            ToolCallContext(
                "schema_generate",
                args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"MYSQL"}"""),
                PRINCIPAL,
            ),
        )
        val severities = parsePayload(outcome).getAsJsonArray("findings")
            .map { it.asJsonObject.get("severity").asString }
        severities shouldBe listOf("error", "info")
    }

    test("skipped objects surface as error-severity findings (AP 6.5)") {
        val ddl = DdlResult(
            statements = emptyList(),
            skippedObjects = listOf(
                SkippedObject(type = "view", name = "v1", reason = "circular dependency", code = "E020"),
            ),
        )
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.POSTGRESQL, ddl))
        val outcome = sut.handle(
            ToolCallContext(
                "schema_generate",
                args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"POSTGRESQL"}"""),
                PRINCIPAL,
            ),
        )
        val finding = parsePayload(outcome).getAsJsonArray("findings").get(0).asJsonObject
        finding.get("severity").asString shouldBe "error"
        finding.get("code").asString shouldBe "E020"
        finding.get("path").asString shouldBe "view.v1"
        finding.get("message").asString shouldBe "skipped: circular dependency"
    }

    test("missing targetDialect throws VALIDATION_ERROR with field=targetDialect") {
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.POSTGRESQL, DdlResult(emptyList())))
        val ex = shouldThrow<ValidationErrorException> {
            sut.handle(
                ToolCallContext(
                    "schema_generate",
                    args("""{"schema":${SIMPLE_SCHEMA}}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "targetDialect"
    }

    test("unknown targetDialect alias throws VALIDATION_ERROR") {
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.POSTGRESQL, DdlResult(emptyList())))
        shouldThrow<ValidationErrorException> {
            sut.handle(
                ToolCallContext(
                    "schema_generate",
                    args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"oracle"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("CLI-style dialect aliases (pg, postgres, maria, sqlite3) are rejected on the wire") {
        // The wire schema only allows the canonical enum names —
        // DatabaseDialect.fromString's CLI conveniences (postgres/pg/
        // maria/mariadb/sqlite3) must NOT slip through. Otherwise a
        // client could pass "pg" successfully while a JSON-Schema-
        // validating proxy in front of the server would have
        // rejected it, creating a security/observability mismatch.
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.POSTGRESQL, DdlResult(emptyList())))
        for (alias in listOf("pg", "postgres", "maria", "mariadb", "sqlite3", "POSTGRES")) {
            val ex = shouldThrow<ValidationErrorException> {
                sut.handle(
                    ToolCallContext(
                        "schema_generate",
                        args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"$alias"}"""),
                        PRINCIPAL,
                    ),
                )
            }
            ex.violations.map { it.field } shouldContain "targetDialect"
        }
    }

    test("targetDialect with no registered driver throws VALIDATION_ERROR (no IllegalArgumentException leak)") {
        // Defensive: the lookup function may throw when a driver is
        // missing — handler must surface VALIDATION_ERROR rather than
        // a raw IllegalArgumentException across the trust boundary.
        val sut = SchemaGenerateHandler(
            resolver = SchemaSourceResolver(InMemorySchemaStore(), McpLimitsConfig()),
            contentLoader = SchemaContentLoader(
                InMemoryArtifactStore(), InMemoryArtifactContentStore(), McpLimitsConfig(),
            ),
            artifactSink = ArtifactSink(InMemoryArtifactStore(), InMemoryArtifactContentStore(), FIXED_CLOCK),
            limits = McpLimitsConfig(),
            generatorLookup = { throw IllegalArgumentException("No DatabaseDriver registered for $it") },
        )
        shouldThrow<ValidationErrorException> {
            sut.handle(
                ToolCallContext(
                    "schema_generate",
                    args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"POSTGRESQL"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("invalid spatialProfile for the dialect throws VALIDATION_ERROR with field=spatialProfile") {
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.POSTGRESQL, DdlResult(emptyList())))
        // POSTGRESQL allows postgis/none — `spatialite` is a SQLite-only profile.
        val ex = shouldThrow<ValidationErrorException> {
            sut.handle(
                ToolCallContext(
                    "schema_generate",
                    args(
                        """{"schema":${SIMPLE_SCHEMA},"targetDialect":"POSTGRESQL","spatialProfile":"spatialite"}""",
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "spatialProfile"
    }

    test("mysqlNamedSequenceMode forwards to the generator options") {
        val fake = FakeDdlGenerator(DatabaseDialect.MYSQL, DdlResult(emptyList()))
        val (sut, _, _) = handler(fake)
        sut.handle(
            ToolCallContext(
                "schema_generate",
                args(
                    """{"schema":${SIMPLE_SCHEMA},"targetDialect":"MYSQL","mysqlNamedSequenceMode":"helper_table"}""",
                ),
                PRINCIPAL,
            ),
        )
        fake.lastOptions!!.mysqlNamedSequenceMode shouldBe MysqlNamedSequenceMode.HELPER_TABLE
        // POSTGRESQL default for MYSQL is NATIVE.
        fake.lastOptions!!.spatialProfile shouldBe SpatialProfile.NATIVE
    }

    test("AP 6.17: generator notes have message/path/hint scrubbed of Bearer tokens") {
        // A note that carries an object name containing a bearer
        // token must not leak the literal across the wire.
        val ddl = DdlResult(
            statements = listOf(DdlStatement(sql = "CREATE TABLE t1 (id BIGINT);")),
            globalNotes = listOf(
                TransformationNote(
                    type = NoteType.WARNING,
                    code = "W042",
                    objectName = "tables.$BEARER_TOKEN_LITERAL",
                    message = "object $BEARER_TOKEN_LITERAL needed adjustment",
                    hint = "rename to drop $BEARER_TOKEN_LITERAL prefix",
                ),
            ),
        )
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.POSTGRESQL, ddl))
        val outcome = sut.handle(
            ToolCallContext(
                "schema_generate",
                args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"POSTGRESQL"}"""),
                PRINCIPAL,
            ),
        )
        val finding = parsePayload(outcome).getAsJsonArray("findings").get(0).asJsonObject
        finding.assertNoBearerLeak("path")
        finding.assertNoBearerLeak("message")
        finding.assertNoBearerLeak("hint")
    }

    test("AP 6.17: skipped object reason is scrubbed of Bearer tokens") {
        val ddl = DdlResult(
            statements = emptyList(),
            skippedObjects = listOf(
                SkippedObject(
                    type = "view",
                    name = "v1",
                    reason = "depends on $BEARER_TOKEN_LITERAL which is unavailable",
                    code = "E020",
                ),
            ),
        )
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.POSTGRESQL, ddl))
        val outcome = sut.handle(
            ToolCallContext(
                "schema_generate",
                args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"POSTGRESQL"}"""),
                PRINCIPAL,
            ),
        )
        val finding = parsePayload(outcome).getAsJsonArray("findings").get(0).asJsonObject
        finding.assertNoBearerLeak("message")
    }

    test("DDL phase ordering is preserved by render() (statementCount echoes statements list)") {
        val ddl = DdlResult(
            statements = listOf(
                DdlStatement(sql = "CREATE TABLE t1 (id BIGINT);", phase = DdlPhase.PRE_DATA),
                DdlStatement(sql = "CREATE VIEW v1 AS SELECT 1;", phase = DdlPhase.POST_DATA),
            ),
        )
        val (sut, _, _) = handler(FakeDdlGenerator(DatabaseDialect.POSTGRESQL, ddl))
        val outcome = sut.handle(
            ToolCallContext(
                "schema_generate",
                args("""{"schema":${SIMPLE_SCHEMA},"targetDialect":"POSTGRESQL"}"""),
                PRINCIPAL,
            ),
        )
        val json = parsePayload(outcome)
        json.get("statementCount").asInt shouldBe 2
        json.get("ddl").asString shouldStartWith "CREATE TABLE t1"
    }
})
