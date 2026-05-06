package dev.dmigrate.mcp.registry

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.job.ApprovedRetryService
import dev.dmigrate.server.application.job.JobStartOrchestrator
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase F § 6.1 + § 8.7 (F.7 2/5) — Pre-Idempotency-Validation +
 * Phase-E-Pipeline-Integration des `data_import_start`-Handlers.
 *
 * Pin't:
 *
 * - Allow-Pfad ueber JobStartOrchestrator -> Job durabel mit
 *   `data_import`-Operation in QUEUED.
 * - Pflichtfeld-Tests (idempotencyKey, targetConnectionRef,
 *   artifactId|sourceArtifactRef) -> VALIDATION_ERROR.
 * - exactly-one Quelle: beide gesetzt oder beide fehlen -> Conflict.
 * - chunkSize-Range: 0 oder >10000 -> VALIDATION_ERROR.
 * - Freier JDBC-URL in `targetConnectionRef` -> Pre-Idempotency
 *   abgelehnt (kein Store-Write, kein Job-ID-Allokation).
 */
class DataImportStartHandlerTest : FunSpec({

    val now = Instant.parse("2026-05-06T12:00:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)
    val gson = Gson()

    class Fixture(
        policyDefault: PolicyEffect = PolicyEffect.Allow,
        val jobIdSeq: AtomicInteger = AtomicInteger(0),
        val tenant: TenantId = Fixtures.tenant("acme"),
        seedDefaultArtifact: Boolean = true,
    ) {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val workerHandleRegistry = InMemoryWorkerHandleRegistry()
        val approvalGrantStore = InMemoryApprovalGrantStore()
        val artifactStore = InMemoryArtifactStore()
        val transaction = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        val grantService = DefaultApprovalGrantService(approvalGrantStore, ApprovalGrantValidator())
        val approvedRetryService = ApprovedRetryService(
            approvalGrantService = grantService,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
        )
        val orchestrator = JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            approvalGrantStore = approvalGrantStore,
            approvedRetryService = approvedRetryService,
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyDefault),
            payloadFingerprintService = DefaultPayloadFingerprintService(),
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
        )
        val handler = DataImportStartHandler(orchestrator, artifactStore, clock)

        init {
            if (seedDefaultArtifact) {
                seedArtifact(artifactId = "art-deadbeef", kind = ArtifactKind.UPLOAD_INPUT)
            }
        }

        fun seedArtifact(
            artifactId: String,
            kind: ArtifactKind = ArtifactKind.UPLOAD_INPUT,
            mimeType: String = "text/csv",
        ) {
            artifactStore.save(
                ArtifactRecord(
                    managedArtifact = ManagedArtifact(
                        artifactId = artifactId,
                        filename = "upload-ups-1-$artifactId.bin",
                        contentType = mimeType,
                        sizeBytes = 1024,
                        sha256 = "deadbeef".repeat(8),
                        createdAt = now,
                        expiresAt = now.plusSeconds(86_400),
                    ),
                    kind = kind,
                    tenantId = tenant,
                    ownerPrincipalId = Fixtures.principal("alice"),
                    visibility = JobVisibility.TENANT,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, artifactId),
                ),
            )
        }
    }

    fun ctx(args: JsonObject) = ToolCallContext(
        name = "data_import_start",
        arguments = args,
        principal = Fixtures.principalContext(principalId = "alice", tenant = "acme"),
        requestId = "req-import",
    )

    fun args(
        idempotencyKey: String? = "k-import-1",
        targetConnectionRef: String? = "dmigrate://tenants/acme/connections/warehouse",
        artifactId: String? = "art-deadbeef",
        sourceArtifactRef: String? = null,
        chunkSize: Int? = null,
        approvalToken: String? = null,
        extraFields: Map<String, Any> = emptyMap(),
    ): JsonObject = JsonObject().apply {
        if (idempotencyKey != null) addProperty("idempotencyKey", idempotencyKey)
        if (targetConnectionRef != null) addProperty("targetConnectionRef", targetConnectionRef)
        if (artifactId != null) addProperty("artifactId", artifactId)
        if (sourceArtifactRef != null) addProperty("sourceArtifactRef", sourceArtifactRef)
        if (chunkSize != null) addProperty("chunkSize", chunkSize)
        if (approvalToken != null) addProperty("approvalToken", approvalToken)
        for ((k, v) in extraFields) {
            add(k, gson.toJsonTree(v))
        }
    }

    test("Allow-Policy + gueltige Args -> Success mit jobId/resourceUri") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(ctx(args()))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val text = result.content.single().text!!
        text shouldContain "\"jobId\":\"job_1\""
        text shouldContain "\"resourceUri\":\"dmigrate://tenants/acme/jobs/job_1\""
        text shouldContain "\"requestId\":\"req-import\""

        val record = fx.jobStore.findById(Fixtures.tenant("acme"), "job_1")!!
        record.managedJob.operation shouldBe DataImportStartHandler.OPERATION
    }

    test("targetConnectionRef fehlt -> ValidationErrorException(targetConnectionRef)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(targetConnectionRef = null)))
        }
        ex.violations.first().field shouldBe "targetConnectionRef"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("idempotencyKey fehlt -> ValidationErrorException, kein Job") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(idempotencyKey = null)))
        }
        ex.violations.first().field shouldBe "idempotencyKey"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("weder artifactId noch sourceArtifactRef -> VALIDATION_ERROR(artifactId)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(artifactId = null, sourceArtifactRef = null)))
        }
        ex.violations.first().field shouldBe "artifactId"
    }

    test("artifactId UND sourceArtifactRef gleichzeitig -> VALIDATION_ERROR (mutually exclusive)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    args(
                        artifactId = "art-1",
                        sourceArtifactRef = "dmigrate://tenants/acme/artifacts/art-1",
                    ),
                ),
            )
        }
        ex.violations.first().reason shouldContain "mutually exclusive"
    }

    test("sourceArtifactRef allein wird akzeptiert (Pre-Idempotency-Pfad)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(
            ctx(
                args(
                    artifactId = null,
                    sourceArtifactRef = "dmigrate://tenants/acme/artifacts/art-deadbeef",
                ),
            ),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("chunkSize=0 -> VALIDATION_ERROR(chunkSize)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(chunkSize = 0)))
        }
        ex.violations.first().field shouldBe "chunkSize"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("chunkSize > 10000 -> VALIDATION_ERROR(chunkSize)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(chunkSize = 10_001)))
        }
        ex.violations.first().field shouldBe "chunkSize"
    }

    test("chunkSize=10000 ist zulaessig (Plan-Maximum)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(ctx(args(chunkSize = 10_000)))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("freier JDBC-URL in targetConnectionRef -> ValidationErrorException, kein Store-Write") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(targetConnectionRef = "jdbc:postgresql://prod:5432/db")))
        }
        ex.violations.first().field shouldBe "targetConnectionRef"
        // Plan § 8.7: "vor Idempotency ohne Store-Write" — der
        // Job-ID-Counter ist Indikator dafuer, dass die Pipeline nicht
        // bis zur durablen Reservierung gelaufen ist.
        fx.jobIdSeq.get() shouldBe 0
    }

    test("Cross-Tenant targetConnectionRef -> Tenant-Prefix-Mismatch -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(args(targetConnectionRef = "dmigrate://tenants/other/connections/c1")),
            )
        }
        fx.jobIdSeq.get() shouldBe 0
    }

    // ──────────────────────────────────────────────────────────────
    // F.7 (3/5) — Artifact-Eligibility + table-Topologie.
    // ──────────────────────────────────────────────────────────────

    test("artifactId zeigt auf nicht existierendes Artefakt -> RESOURCE_NOT_FOUND") {
        val fx = Fixture(seedDefaultArtifact = false)
        shouldThrow<ResourceNotFoundException> {
            fx.handler.handle(ctx(args(artifactId = "art-missing")))
        }
        fx.jobIdSeq.get() shouldBe 0
    }

    test("Artefakt mit kind=SCHEMA (read-only Schema-Staging) -> VALIDATION_ERROR") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(artifactId = "art-schema-1", kind = ArtifactKind.SCHEMA)
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(artifactId = "art-schema-1")))
        }
        ex.violations.first().field shouldBe "artifactId"
        ex.violations.first().reason shouldContain "UPLOAD_INPUT"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("Artefakt mit kind=PROFILE -> VALIDATION_ERROR (kein Import-Material)") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(artifactId = "art-profile-1", kind = ArtifactKind.PROFILE)
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(artifactId = "art-profile-1")))
        }
    }

    test("sourceArtifactRef wird tenant-scoped aufgeloest und gegen ArtifactStore validiert") {
        val fx = Fixture()
        val result = fx.handler.handle(
            ctx(
                args(
                    artifactId = null,
                    sourceArtifactRef = "dmigrate://tenants/acme/artifacts/art-deadbeef",
                ),
            ),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("sourceArtifactRef mit anderem Tenant -> VALIDATION_ERROR (Tenant-Mismatch)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    args(
                        artifactId = null,
                        sourceArtifactRef = "dmigrate://tenants/other/artifacts/art-deadbeef",
                    ),
                ),
            )
        }
        ex.violations.first().field shouldBe "sourceArtifactRef"
    }

    test("sourceArtifactRef mit kind=jobs (falsche ResourceKind) -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    args(
                        artifactId = null,
                        sourceArtifactRef = "dmigrate://tenants/acme/jobs/some-job",
                    ),
                ),
            )
        }
    }

    test("table und tables gleichzeitig -> VALIDATION_ERROR (mutually exclusive)") {
        val fx = Fixture()
        val tablesArr = JsonArray().apply { add("warehouse.events") }
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    args(extraFields = mapOf("table" to "warehouse.events", "tables" to tablesArr)),
                ),
            )
        }
        ex.violations.first().reason shouldContain "mutually exclusive"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("tables in Phase F nicht erlaubt (kein Bundle-Format) -> VALIDATION_ERROR") {
        val fx = Fixture()
        val tablesArr = JsonArray().apply { add("warehouse.events"); add("warehouse.users") }
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("tables" to tablesArr))))
        }
        ex.violations.first().field shouldBe "tables"
        ex.violations.first().reason shouldContain "bundle format"
    }

    test("tables als leeres Array -> VALIDATION_ERROR (auch ohne Bundle-Format-Carve-out)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("tables" to JsonArray()))))
        }
        ex.violations.first().reason shouldContain "must not be empty"
    }

    test("tables mit leeren Strings -> VALIDATION_ERROR") {
        val fx = Fixture()
        val tablesArr = JsonArray().apply { add(""); add("warehouse.users") }
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("tables" to tablesArr))))
        }
        ex.violations.first().reason shouldContain "non-blank strings"
    }

    test("table allein bleibt zulaessig (Single-File-Topologie)") {
        val fx = Fixture()
        val result = fx.handler.handle(
            ctx(args(extraFields = mapOf("table" to "warehouse.events"))),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }
})
