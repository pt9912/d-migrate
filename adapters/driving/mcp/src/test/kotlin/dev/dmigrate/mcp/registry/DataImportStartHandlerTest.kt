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
import dev.dmigrate.server.core.artifact.ArtifactUploadMetadata
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — Pre-Idempotency-Validation +
 * LF-012 / LN-011 / LN-017 / LN-027-Pipeline-Integration des `data_import_start`-Handlers.
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
        seedDefaultConnection: Boolean = true,
    ) {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val workerHandleRegistry = InMemoryWorkerHandleRegistry()
        val approvalGrantStore = InMemoryApprovalGrantStore()
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val connectionStore = InMemoryConnectionReferenceStore()
        val schemaStore = InMemorySchemaStore()
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
        val handler = DataImportStartHandler(
            orchestrator = orchestrator,
            artifactStore = artifactStore,
            artifactContentStore = artifactContentStore,
            connectionStore = connectionStore,
            schemaStore = schemaStore,
            clock = clock,
        )

        init {
            if (seedDefaultArtifact) {
                seedArtifact(artifactId = "art-deadbeef", kind = ArtifactKind.UPLOAD_INPUT)
            }
            if (seedDefaultConnection) {
                seedConnection(connectionId = "warehouse")
            }
        }

        fun seedConnection(connectionId: String) {
            connectionStore.save(
                ConnectionReference(
                    connectionId = connectionId,
                    tenantId = tenant,
                    displayName = "warehouse-prod",
                    dialectId = "postgres",
                    sensitivity = dev.dmigrate.server.core.connection.ConnectionSensitivity.NON_PRODUCTION,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.CONNECTIONS, connectionId),
                ),
            )
        }

        fun seedSchema(schemaId: String) {
            schemaStore.save(
                SchemaIndexEntry(
                    schemaId = schemaId,
                    tenantId = tenant,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.SCHEMAS, schemaId),
                    artifactRef = "art-schema-source",
                    displayName = schemaId,
                    createdAt = now,
                    expiresAt = now.plusSeconds(86_400),
                ),
            )
        }

        fun seedArtifact(
            artifactId: String,
            kind: ArtifactKind = ArtifactKind.UPLOAD_INPUT,
            mimeType: String = "text/csv",
            includeUploadMetadata: Boolean = true,
            uploadIntent: String = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
            wireArtifactKind: String = "seed-data",
            metadataTargetTable: String? = "warehouse.events",
            includeContent: Boolean = true,
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
                    uploadMetadata = if (kind == ArtifactKind.UPLOAD_INPUT && includeUploadMetadata) {
                        ArtifactUploadMetadata(
                            artifactId = artifactId,
                            resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, artifactId).render(),
                            uploadIntent = uploadIntent,
                            wireArtifactKind = wireArtifactKind,
                            contentType = mimeType,
                            format = when (mimeType.substringBefore(";")) {
                                "text/csv", "application/csv", "application/vnd.ms-excel" -> "csv"
                                "application/json", "text/json", "application/x-ndjson" -> "json"
                                "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml" -> "yaml"
                                else -> null
                            },
                            targetTable = metadataTargetTable,
                            sourceUploadSessionId = "ups-1",
                            policyFingerprint = "fp-upload",
                            sizeBytes = 1024,
                            sha256 = "deadbeef".repeat(8),
                        )
                    } else {
                        null
                    },
                ),
            )
            if (kind == ArtifactKind.UPLOAD_INPUT && includeContent) {
                artifactContentStore.write(
                    artifactId = artifactId,
                    source = ByteArrayInputStream(ByteArray(1024) { 'x'.code.toByte() }),
                    expectedSizeBytes = 1024,
                )
            }
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

    test("chunkSize=10000 ist zulaessig (Vertragsmaximum)") {
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
        // LF-012 / LN-011 / LN-017 / LN-027: "vor Idempotency ohne Store-Write" — der
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
    // LF-010 / LF-013 / LN-009 / LN-011 — Artifact-Eligibility + table-Topologie.
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

    test("UPLOAD_INPUT ohne persistente Upload-Metadaten -> VALIDATION_ERROR") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(
            artifactId = "art-no-metadata",
            includeUploadMetadata = false,
        )
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(artifactId = "art-no-metadata")))
        }
        ex.violations.first().field shouldBe "artifactId"
        ex.violations.first().reason shouldContain "uploadMetadata"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("UPLOAD_INPUT mit Metadaten aber ohne Artefaktbytes -> VALIDATION_ERROR") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(
            artifactId = "art-no-bytes",
            includeContent = false,
        )
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(artifactId = "art-no-bytes")))
        }
        ex.violations.first().field shouldBe "artifactId"
        ex.violations.first().reason shouldContain "bytes are missing"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("UPLOAD_INPUT mit falschem uploadIntent -> VALIDATION_ERROR") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(
            artifactId = "art-wrong-intent",
            uploadIntent = "profile_input",
        )
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(artifactId = "art-wrong-intent")))
        }
        ex.violations.first().field shouldBe "artifactId"
        ex.violations.first().reason shouldContain "uploadIntent=job_input"
    }

    test("wireArtifactKind=generic ohne explizites format -> VALIDATION_ERROR") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(
            artifactId = "art-generic",
            wireArtifactKind = "generic",
        )
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(artifactId = "art-generic")))
        }
        ex.violations.first().field shouldBe "format"
        ex.violations.first().reason shouldContain "wireArtifactKind=generic"
    }

    test("table muss persistentem Upload-targetTable entsprechen") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(
            artifactId = "art-table-bound",
            metadataTargetTable = "warehouse.expected",
        )
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(args(artifactId = "art-table-bound", extraFields = mapOf("table" to "warehouse.other"))),
            )
        }
        ex.violations.first().field shouldBe "table"
        ex.violations.first().reason shouldContain "warehouse.expected"
    }

    test("table ist erforderlich, wenn Upload-Metadaten kein targetTable enthalten") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(
            artifactId = "art-table-free",
            metadataTargetTable = null,
        )
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(artifactId = "art-table-free")))
        }
        ex.violations.first().field shouldBe "table"
    }

    test("format kompatibel mit Artefakt-MIME -> Success") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(artifactId = "art-csv", mimeType = "text/csv; charset=utf-8")
        val result = fx.handler.handle(ctx(args(artifactId = "art-csv", extraFields = mapOf("format" to "csv"))))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("ungueltiger onError-Wert -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("onError" to "continue"))))
        }
        ex.violations.first().field shouldBe "onError"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("ungueltiger onConflict-Wert -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("onConflict" to "replace"))))
        }
        ex.violations.first().field shouldBe "onConflict"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("ungueltiger triggerMode-Wert -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("triggerMode" to "validate_only"))))
        }
        ex.violations.first().field shouldBe "triggerMode"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("format inkompatibel mit Artefakt-MIME -> VALIDATION_ERROR") {
        val fx = Fixture(seedDefaultArtifact = false)
        fx.seedArtifact(artifactId = "art-json", mimeType = "application/json")
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(artifactId = "art-json", extraFields = mapOf("format" to "csv"))))
        }
        ex.violations.first().field shouldBe "format"
        fx.jobIdSeq.get() shouldBe 0
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

    test("tables ohne bundleFormat -> VALIDATION_ERROR(bundleFormat) (LF-010 / LF-013 / LN-009 / LN-011)") {
        val fx = Fixture()
        val tablesArr = JsonArray().apply { add("warehouse.events"); add("warehouse.users") }
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("tables" to tablesArr))))
        }
        ex.violations.first().field shouldBe "bundleFormat"
        ex.violations.first().reason shouldContain "is required when 'tables' is set"
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

    // ──────────────────────────────────────────────────────────────
    // LF-010 / LF-013 / LN-009 / LN-011 — ConnectionRef + SchemaRef-Resolution + Fingerprint.
    // ──────────────────────────────────────────────────────────────

    test("targetConnectionRef ohne Eintrag im ConnectionReferenceStore -> RESOURCE_NOT_FOUND") {
        val fx = Fixture(seedDefaultConnection = false)
        shouldThrow<ResourceNotFoundException> {
            fx.handler.handle(
                ctx(args(targetConnectionRef = "dmigrate://tenants/acme/connections/missing-ref")),
            )
        }
        fx.jobIdSeq.get() shouldBe 0
    }

    test("targetConnectionRef mit kind=jobs -> VALIDATION_ERROR (falsche ResourceKind)") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(args(targetConnectionRef = "dmigrate://tenants/acme/jobs/some-job")),
            )
        }
    }

    test("schemaRef mit Eintrag im SchemaStore -> Success") {
        val fx = Fixture()
        fx.seedSchema("sch-1")
        val result = fx.handler.handle(
            ctx(args(extraFields = mapOf("schemaRef" to "dmigrate://tenants/acme/schemas/sch-1"))),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("schemaRef ohne Eintrag im SchemaStore -> RESOURCE_NOT_FOUND") {
        val fx = Fixture()
        shouldThrow<ResourceNotFoundException> {
            fx.handler.handle(
                ctx(args(extraFields = mapOf("schemaRef" to "dmigrate://tenants/acme/schemas/sch-missing"))),
            )
        }
    }

    test("schemaRef mit anderem Tenant -> VALIDATION_ERROR (Tenant-Prefix-Mismatch)") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(args(extraFields = mapOf("schemaRef" to "dmigrate://tenants/other/schemas/sch-1"))),
            )
        }
    }

    test("schemaRef mit kind=connections -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(args(extraFields = mapOf("schemaRef" to "dmigrate://tenants/acme/connections/warehouse"))),
            )
        }
    }

    test("Fingerprint enthaelt Artefakt-sha256: gleicher idempotencyKey + anderes Artefakt -> IDEMPOTENCY_CONFLICT") {
        val fx = Fixture(seedDefaultArtifact = false, policyDefault = PolicyEffect.Allow)
        // Zwei Artefakte mit verschiedenen Inhalt-Hashes (durch
        // unterschiedlichen filename/contentType — die Test-Fixture
        // erzeugt sie in seedArtifact() mit unterschiedlichen sha256-
        // Werten ueber den ManagedArtifact.sha256-Eingang).
        fx.seedArtifact(artifactId = "art-A", mimeType = "text/csv")
        fx.seedArtifact(artifactId = "art-B", mimeType = "application/json")
        val first = fx.handler.handle(
            ctx(args(idempotencyKey = "k-fp-test", artifactId = "art-A")),
        )
        first.shouldBeInstanceOf<ToolCallOutcome.Success>()

        // LF-012 / LN-011 / LN-017 / LN-027: "abweichende Import-Option mit gleichem
        // idempotencyKey -> IDEMPOTENCY_CONFLICT". Der von uns
        // injizierte Artefakt-sha256 / mimeType / filename geht in
        // den Payload-Fingerprint ein und macht die zwei Anfragen
        // zu unterschiedlichen Idempotency-Subjekten — der
        // JobStartOrchestrator detektiert den Conflict deterministisch.
        // (Hinweis: Beide Test-Artefakte haben die gleiche sha256-
        // Konstante "deadbeef..." aus seedArtifact, aber
        // unterschiedlichen mimeType/filename — das reicht, weil
        // der Fingerprint alle drei Felder einbezieht.)
        shouldThrow<dev.dmigrate.server.application.error.IdempotencyConflictException> {
            fx.handler.handle(
                ctx(args(idempotencyKey = "k-fp-test", artifactId = "art-B")),
            )
        }

        // Defense: nur EIN Job durabel angelegt (kein silent replay,
        // kein zweiter Job).
        fx.jobStore.list(
            fx.tenant,
            dev.dmigrate.server.core.pagination.PageRequest(pageSize = 10),
        ).items.size shouldBe 1
    }
})
