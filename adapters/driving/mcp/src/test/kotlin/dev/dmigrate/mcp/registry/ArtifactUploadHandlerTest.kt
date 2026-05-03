package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.RateLimitedException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.UploadSessionAbortedException
import dev.dmigrate.server.application.error.UploadSessionExpiredException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

private val ACME = TenantId("acme")
private val ALICE = PrincipalId("alice")
private val BOB = PrincipalId("bob")

private val PRINCIPAL = PrincipalContext(
    principalId = ALICE,
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:artifact:upload"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private val FIXED_NOW = Instant.parse("2026-05-02T12:00:00Z")
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private class UploadFixture(
    val handler: ArtifactUploadHandler,
    val sessionStore: InMemoryUploadSessionStore,
    val segmentStore: InMemoryUploadSegmentStore,
    val quotaStore: InMemoryQuotaStore,
)

private fun fixture(
    limits: McpLimitsConfig = McpLimitsConfig(maxUploadSegmentBytes = 8),
    parallelLimit: Long = 5,
    finalizer: SchemaStagingFinalizer? = null,
): UploadFixture {
    val sessionStore = InMemoryUploadSessionStore()
    val segmentStore = InMemoryUploadSegmentStore()
    val quotaStore = InMemoryQuotaStore()
    val parallelService = DefaultQuotaService(quotaStore) {
        if (it.dimension == dev.dmigrate.server.ports.quota.QuotaDimension.PARALLEL_SEGMENT_WRITES) {
            parallelLimit
        } else {
            Long.MAX_VALUE
        }
    }
    val handler = ArtifactUploadHandler(
        sessionStore = sessionStore,
        segmentStore = segmentStore,
        quotaService = parallelService,
        limits = limits,
        clock = FIXED_CLOCK,
        finalizer = finalizer,
    )
    return UploadFixture(handler, sessionStore, segmentStore, quotaStore)
}

private fun stageSession(
    fixture: UploadFixture,
    sessionId: String,
    sizeBytes: Long,
    segmentTotal: Int,
    checksumSha256: String,
    state: UploadSessionState = UploadSessionState.ACTIVE,
    owner: PrincipalId = ALICE,
) {
    fixture.sessionStore.save(
        UploadSession(
            uploadSessionId = sessionId,
            tenantId = ACME,
            ownerPrincipalId = owner,
            resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, sessionId),
            artifactKind = ArtifactKind.SCHEMA,
            mimeType = "application/octet-stream",
            sizeBytes = sizeBytes,
            segmentTotal = segmentTotal,
            checksumSha256 = checksumSha256,
            uploadIntent = ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY,
            state = state,
            createdAt = FIXED_NOW,
            updatedAt = FIXED_NOW,
            idleTimeoutAt = FIXED_NOW.plusSeconds(300),
            absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(3600),
        ),
    )
}

private fun args(content: String): JsonObject = JsonParser.parseString(content).asJsonObject

private fun parsePayload(outcome: ToolCallOutcome): JsonObject {
    val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
    return JsonParser.parseString(text).asJsonObject
}

private fun segmentArgs(
    sessionId: String = "ups-1",
    segmentIndex: Int,
    segmentOffset: Long,
    segmentTotal: Int,
    isFinalSegment: Boolean,
    bytes: ByteArray,
    segmentSha256: String = sha256Hex(bytes),
): String = """{
    "uploadSessionId":"$sessionId",
    "segmentIndex":$segmentIndex,
    "segmentOffset":$segmentOffset,
    "segmentTotal":$segmentTotal,
    "isFinalSegment":$isFinalSegment,
    "segmentSha256":"$segmentSha256",
    "contentBase64":"${b64(bytes)}"
}""".replace("\n", "").replace("    ", "")

@Suppress("LargeClass")
// LargeClass: the spec's segment-acceptance contract has many
// branches (lifecycle states, sequence/offset/size/hash failures,
// quota, race outcomes, finaliser hookup); folding them into one
// FunSpec keeps the "what does artifact_upload do" answer in one
// place. Splitting by AP number would scatter the contract.
class ArtifactUploadHandlerTest : FunSpec({

    test("single-segment session: final segment marks state COMPLETED with valid total hash") {
        val payload = "schema-bytes".toByteArray() // 12 bytes
        val totalHash = sha256Hex(payload)
        val f = fixture(limits = McpLimitsConfig(maxUploadSegmentBytes = 16))
        stageSession(
            f, "ups-1",
            sizeBytes = payload.size.toLong(),
            segmentTotal = 1,
            checksumSha256 = totalHash,
        )
        val outcome = f.handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 1,
                        segmentOffset = 0,
                        segmentTotal = 1,
                        isFinalSegment = true,
                        bytes = payload,
                    ),
                ),
                PRINCIPAL,
            ),
        )
        val json = parsePayload(outcome)
        json.get("uploadSessionId").asString shouldBe "ups-1"
        json.get("acceptedSegmentIndex").asInt shouldBe 1
        json.get("deduplicated").asBoolean shouldBe false
        json.get("bytesReceived").asLong shouldBe payload.size.toLong()
        json.get("uploadSessionState").asString shouldBe "COMPLETED"
        json.get("uploadSessionTtlSeconds").asLong shouldBe 0L
        // Session state has actually been transitioned in the store.
        f.sessionStore.findById(ACME, "ups-1")!!.state shouldBe UploadSessionState.COMPLETED
    }

    test("multi-segment session: intermediate segments stay ACTIVE; final segment completes the session") {
        // 8-byte segments, 16 bytes total → 2 segments.
        val seg1 = "abcdefgh".toByteArray()
        val seg2 = "12345678".toByteArray()
        val totalHash = sha256Hex(seg1 + seg2)
        val f = fixture(limits = McpLimitsConfig(maxUploadSegmentBytes = 8))
        stageSession(
            f, "ups-1",
            sizeBytes = (seg1.size + seg2.size).toLong(),
            segmentTotal = 2,
            checksumSha256 = totalHash,
        )

        val first = parsePayload(
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1,
                            segmentOffset = 0,
                            segmentTotal = 2,
                            isFinalSegment = false,
                            bytes = seg1,
                        ),
                    ),
                    PRINCIPAL,
                ),
            ),
        )
        first.get("uploadSessionState").asString shouldBe "ACTIVE"
        first.get("bytesReceived").asLong shouldBe 8L

        val second = parsePayload(
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 2,
                            segmentOffset = 8,
                            segmentTotal = 2,
                            isFinalSegment = true,
                            bytes = seg2,
                        ),
                    ),
                    PRINCIPAL,
                ),
            ),
        )
        second.get("uploadSessionState").asString shouldBe "COMPLETED"
        second.get("bytesReceived").asLong shouldBe 16L
    }

    test("retrying after COMPLETED without a persisted schemaRef throws IDEMPOTENCY_CONFLICT") {
        // The base fixture wires no finalizer, so finaliseToSchemaRef
        // returns null and the session reaches COMPLETED with
        // `finalisedSchemaRef == null`. AP 6.18's idempotent-replay
        // path requires a non-null schemaRef to authorise the reuse,
        // so this case stays IDEMPOTENCY_CONFLICT — there's no
        // deterministic answer to return.
        val payload = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payload)
        val f = fixture(limits = McpLimitsConfig(maxUploadSegmentBytes = 8))
        stageSession(f, "ups-1", payload.size.toLong(), segmentTotal = 1, checksumSha256 = totalHash)
        val argString = segmentArgs(
            segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
            isFinalSegment = true, bytes = payload,
        )
        f.handler.handle(ToolCallContext("artifact_upload", args(argString), PRINCIPAL))
        shouldThrow<IdempotencyConflictException> {
            f.handler.handle(ToolCallContext("artifact_upload", args(argString), PRINCIPAL))
        }
    }

    test("AP 6.18: replay of completing segment with same hash returns the persisted schemaRef") {
        // Stub finalizer pins the schemaRef on the first call so the
        // replay has something deterministic to return.
        val schemaUri = ServerResourceUri(ACME, ResourceKind.SCHEMAS, "schema-fixed")
        val stubFinalizer = SchemaStagingFinalizer { _, _, _, _, _, _ -> schemaUri }
        val payload = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payload)
        val f = fixture(finalizer = stubFinalizer)
        stageSession(f, "ups-1", payload.size.toLong(), 1, totalHash)
        val argString = segmentArgs(
            segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
            isFinalSegment = true, bytes = payload,
        )

        val firstResponse = parsePayload(
            f.handler.handle(ToolCallContext("artifact_upload", args(argString), PRINCIPAL)),
        )
        firstResponse.get("schemaRef").asString shouldBe schemaUri.render()
        firstResponse.get("uploadSessionState").asString shouldBe "COMPLETED"

        val replayResponse = parsePayload(
            f.handler.handle(ToolCallContext("artifact_upload", args(argString), PRINCIPAL)),
        )
        replayResponse.get("schemaRef").asString shouldBe schemaUri.render()
        replayResponse.get("uploadSessionState").asString shouldBe "COMPLETED"
        replayResponse.get("deduplicated").asBoolean shouldBe true
        f.sessionStore.findById(ACME, "ups-1")!!.finalisedSchemaRef shouldBe schemaUri.render()
    }

    test("AP 6.18: replay with divergent hash at same index throws IDEMPOTENCY_CONFLICT") {
        val schemaUri = ServerResourceUri(ACME, ResourceKind.SCHEMAS, "schema-fixed")
        val stubFinalizer = SchemaStagingFinalizer { _, _, _, _, _, _ -> schemaUri }
        val payload = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payload)
        val f = fixture(finalizer = stubFinalizer)
        stageSession(f, "ups-1", payload.size.toLong(), 1, totalHash)
        f.handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
                        isFinalSegment = true, bytes = payload,
                    ),
                ),
                PRINCIPAL,
            ),
        )
        val divergentHash = "f".repeat(64)
        shouldThrow<IdempotencyConflictException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
                            isFinalSegment = true, bytes = payload,
                            segmentSha256 = divergentHash,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("identical segment retry inside an ACTIVE session surfaces deduplicated=true") {
        val seg1 = "abcdefgh".toByteArray()
        val seg2 = "12345678".toByteArray()
        val totalHash = sha256Hex(seg1 + seg2)
        val f = fixture()
        stageSession(f, "ups-1", 16, segmentTotal = 2, checksumSha256 = totalHash)
        val first = segmentArgs(
            segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
            isFinalSegment = false, bytes = seg1,
        )
        f.handler.handle(ToolCallContext("artifact_upload", args(first), PRINCIPAL))
        val replay = parsePayload(
            f.handler.handle(ToolCallContext("artifact_upload", args(first), PRINCIPAL)),
        )
        replay.get("deduplicated").asBoolean shouldBe true
        replay.get("uploadSessionState").asString shouldBe "ACTIVE"
    }

    test("conflicting segment (same index, different bytes) raises IDEMPOTENCY_CONFLICT") {
        val seg1 = "abcdefgh".toByteArray()
        val seg2 = "12345678".toByteArray()
        val totalHash = sha256Hex(seg1 + seg2)
        val f = fixture()
        stageSession(f, "ups-1", 16, segmentTotal = 2, checksumSha256 = totalHash)
        f.handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                        isFinalSegment = false, bytes = seg1,
                    ),
                ),
                PRINCIPAL,
            ),
        )
        // Same index, different bytes → store returns Conflict.
        val different = "DIFFERENT".toByteArray().copyOf(8)
        shouldThrow<IdempotencyConflictException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                            isFinalSegment = false, bytes = different,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("unknown sessionId throws RESOURCE_NOT_FOUND") {
        val f = fixture()
        shouldThrow<ResourceNotFoundException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            sessionId = "ups-missing", segmentIndex = 1, segmentOffset = 0,
                            segmentTotal = 1, isFinalSegment = true, bytes = "x".toByteArray(),
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("session owned by a different principal throws FORBIDDEN_PRINCIPAL") {
        val payload = "hello".toByteArray()
        val f = fixture()
        stageSession(
            f, "ups-1", payload.size.toLong(), segmentTotal = 1,
            checksumSha256 = sha256Hex(payload), owner = BOB,
        )
        shouldThrow<ForbiddenPrincipalException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
                            isFinalSegment = true, bytes = payload,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("aborted session surfaces UPLOAD_SESSION_ABORTED; expired surfaces UPLOAD_SESSION_EXPIRED") {
        val payload = "x".toByteArray()
        val totalHash = sha256Hex(payload)
        val f = fixture()
        stageSession(
            f, "ups-aborted", 1, segmentTotal = 1, checksumSha256 = totalHash,
            state = UploadSessionState.ABORTED,
        )
        stageSession(
            f, "ups-expired", 1, segmentTotal = 1, checksumSha256 = totalHash,
            state = UploadSessionState.EXPIRED,
        )
        shouldThrow<UploadSessionAbortedException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            sessionId = "ups-aborted", segmentIndex = 1, segmentOffset = 0,
                            segmentTotal = 1, isFinalSegment = true, bytes = payload,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        shouldThrow<UploadSessionExpiredException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            sessionId = "ups-expired", segmentIndex = 1, segmentOffset = 0,
                            segmentTotal = 1, isFinalSegment = true, bytes = payload,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("segmentTotal mismatch throws VALIDATION_ERROR") {
        val payload = "x".toByteArray()
        val f = fixture()
        stageSession(f, "ups-1", 1, segmentTotal = 2, checksumSha256 = sha256Hex(payload))
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 5,
                            isFinalSegment = false, bytes = payload,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "segmentTotal"
    }

    test("isFinalSegment flag must match segmentIndex == segmentTotal") {
        val payload = "x".toByteArray()
        val f = fixture()
        stageSession(f, "ups-1", 1, segmentTotal = 2, checksumSha256 = sha256Hex(payload))
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                            isFinalSegment = true, // wrong: 1 < 2
                            bytes = payload,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "isFinalSegment"
    }

    test("decoded segment over maxUploadSegmentBytes throws PAYLOAD_TOO_LARGE") {
        val tinyLimits = McpLimitsConfig(maxUploadSegmentBytes = 4)
        val f = fixture(limits = tinyLimits)
        val payload = "TOO-LONG-FOR-LIMIT".toByteArray()
        stageSession(
            f, "ups-1", payload.size.toLong(), segmentTotal = 1,
            checksumSha256 = sha256Hex(payload),
        )
        shouldThrow<PayloadTooLargeException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
                            isFinalSegment = true, bytes = payload,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("segmentSha256 mismatch throws VALIDATION_ERROR with field=segmentSha256") {
        val payload = "abcdefgh".toByteArray()
        val f = fixture()
        stageSession(f, "ups-1", 8, segmentTotal = 1, checksumSha256 = sha256Hex(payload))
        val wrongHash = "f".repeat(64)
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
                            isFinalSegment = true, bytes = payload, segmentSha256 = wrongHash,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "segmentSha256"
    }

    test("malformed base64 in contentBase64 throws VALIDATION_ERROR") {
        val payload = "x".toByteArray()
        val f = fixture()
        stageSession(f, "ups-1", 1, segmentTotal = 1, checksumSha256 = sha256Hex(payload))
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        """{"uploadSessionId":"ups-1","segmentIndex":1,"segmentOffset":0,
                        "segmentTotal":1,"isFinalSegment":true,
                        "segmentSha256":"${sha256Hex(payload)}",
                        "contentBase64":"!!! not base64 !!!"}""".trimIndent().replace("\n", ""),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "contentBase64"
    }

    test("non-final segment with size != maxUploadSegmentBytes throws VALIDATION_ERROR") {
        val seg1 = "ab".toByteArray() // only 2 bytes; maxUploadSegmentBytes = 8
        val totalHash = sha256Hex(seg1 + ByteArray(14))
        val f = fixture()
        stageSession(f, "ups-1", 16, segmentTotal = 2, checksumSha256 = totalHash)
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                            isFinalSegment = false, bytes = seg1,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "contentBase64"
    }

    test("final segment with cumulative-hash mismatch throws VALIDATION_ERROR (rolls to ABORTED per AP 6.22)") {
        // Two segments stored, but session.checksumSha256 declares a
        // hash that doesn't match the actual concatenated bytes.
        // The streaming-assembly SHA check runs inside the finaliser
        // pipeline, so a stub finaliser must be wired for AP 6.22's
        // ABORTED + sanitised-FAILED-outcome path to exercise.
        val seg1 = "abcdefgh".toByteArray()
        val seg2 = "12345678".toByteArray()
        val claimedTotalHash = "f".repeat(64) // wrong on purpose
        val schemaUri = ServerResourceUri(ACME, ResourceKind.SCHEMAS, "stub")
        val stubFinalizer = SchemaStagingFinalizer { _, _, _, _, _, _ -> schemaUri }
        val f = fixture(finalizer = stubFinalizer)
        stageSession(f, "ups-1", 16, segmentTotal = 2, checksumSha256 = claimedTotalHash)
        f.handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                        isFinalSegment = false, bytes = seg1,
                    ),
                ),
                PRINCIPAL,
            ),
        )
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 2, segmentOffset = 8, segmentTotal = 2,
                            isFinalSegment = true, bytes = seg2,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "checksumSha256"
        // AP 6.22: persistent assembly inconsistencies (size/SHA
        // mismatch over stored segments) become a terminal ABORTED
        // outcome with a sanitised FAILED FinalizationOutcome. Replays
        // get the same error class without a fresh assembly attempt.
        f.sessionStore.findById(ACME, "ups-1")!!.state shouldBe UploadSessionState.ABORTED
    }

    test("each accepted segment extends absoluteLeaseExpiresAt, capped at createdAt + 3600s (spec §5.3:614)") {
        // Stage a session whose original absoluteLeaseExpiresAt is
        // 30 min after creation; after a successful segment with the
        // fixed clock at creation+0, the lease should advance to
        // min(now + initialTtl=900s, createdAt + 3600s).
        val seg1 = "abcdefgh".toByteArray()
        val seg2 = "12345678".toByteArray()
        val totalHash = sha256Hex(seg1 + seg2)
        val f = fixture()
        // sessionStore.save uses our fixed clock at creation; we
        // replace the session post-stage so absoluteLeaseExpiresAt is
        // closer than the spec ceiling, ensuring the cap kicks in.
        stageSession(f, "ups-1", 16, segmentTotal = 2, checksumSha256 = totalHash)
        val original = f.sessionStore.findById(ACME, "ups-1")!!
        f.sessionStore.save(original.copy(absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(120)))

        f.handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                        isFinalSegment = false, bytes = seg1,
                    ),
                ),
                PRINCIPAL,
            ),
        )

        val updated = f.sessionStore.findById(ACME, "ups-1")!!
        // FIXED_NOW + 900s (initialTtl), capped by FIXED_NOW + 3600s
        // (createdAt + MAX_ABSOLUTE_LEASE) — initialTtl wins.
        updated.absoluteLeaseExpiresAt shouldBe FIXED_NOW.plusSeconds(900)
        // Idle window is min(now + 300s, newAbsolute).
        updated.idleTimeoutAt shouldBe FIXED_NOW.plusSeconds(300)
    }

    test("Conflict store-outcome releases the PARALLEL_SEGMENT_WRITES slot before throwing") {
        // The handler reserves one slot per segment and must release
        // it on every store-outcome. Otherwise repeated Conflicts
        // would burn the per-principal quota until the counter
        // exhausts.
        val seg1 = "abcdefgh".toByteArray()
        val seg2 = "12345678".toByteArray()
        val totalHash = sha256Hex(seg1 + seg2)
        val f = fixture(parallelLimit = 1)
        stageSession(f, "ups-1", 16, segmentTotal = 2, checksumSha256 = totalHash)
        f.handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                        isFinalSegment = false, bytes = seg1,
                    ),
                ),
                PRINCIPAL,
            ),
        )
        // Same index, different bytes → Conflict from the store.
        val conflicting = "DIFFERENT".toByteArray().copyOf(8)
        shouldThrow<IdempotencyConflictException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                            isFinalSegment = false, bytes = conflicting,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        // After the Conflict, the slot must be free again so a
        // legitimate second-segment write succeeds.
        f.handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 2, segmentOffset = 8, segmentTotal = 2,
                        isFinalSegment = true, bytes = seg2,
                    ),
                ),
                PRINCIPAL,
            ),
        )
        f.sessionStore.findById(ACME, "ups-1")!!.state shouldBe UploadSessionState.COMPLETED
    }

    test("3-segment session: total hash is computed over all segments in index order") {
        // Pinning the multi-segment hash walk: a bug in
        // computeTotalHash that reads segments out of order would
        // surface as a hash-mismatch on the final segment. The test
        // declares the canonical segment order, then asserts the
        // session reaches COMPLETED.
        val seg1 = "AAAAAAAA".toByteArray()
        val seg2 = "BBBBBBBB".toByteArray()
        val seg3 = "CCCCCCCC".toByteArray()
        val totalHash = sha256Hex(seg1 + seg2 + seg3)
        val f = fixture()
        stageSession(f, "ups-1", 24, segmentTotal = 3, checksumSha256 = totalHash)
        // Send segments in order.
        for ((i, bytes) in listOf(seg1, seg2, seg3).withIndex()) {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = i + 1,
                            segmentOffset = (i * 8).toLong(),
                            segmentTotal = 3,
                            isFinalSegment = i == 2,
                            bytes = bytes,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        f.sessionStore.findById(ACME, "ups-1")!!.state shouldBe UploadSessionState.COMPLETED
    }

    test("concurrent ABORTED race surfaces UPLOAD_SESSION_ABORTED, not INTERNAL_AGENT_ERROR") {
        // Race window: the handler observes ACTIVE state, then a
        // concurrent abort flips the session before the COMPLETED
        // transition runs. The shared `transitionOrThrow` helper
        // maps that to the typed lifecycle exception so the client
        // sees the real cause.
        val payload = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payload)
        val f = fixture()
        stageSession(f, "ups-1", 8, segmentTotal = 1, checksumSha256 = totalHash)
        val racingHandler = ArtifactUploadHandler(
            sessionStore = racingTransitionStore(f.sessionStore, illegalFrom = UploadSessionState.ABORTED),
            segmentStore = f.segmentStore,
            quotaService = DefaultQuotaService(f.quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxUploadSegmentBytes = 8),
            clock = FIXED_CLOCK,
        )
        shouldThrow<UploadSessionAbortedException> {
            racingHandler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
                            isFinalSegment = true, bytes = payload,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("AP 6.9: when a finalizer is wired, completing the session returns the registered schemaRef") {
        // Stub finalizer: returns a fixed schemaRef so the test pins
        // only the wiring/payload contract — actual parse+validate is
        // covered by SchemaStagingFinalizerTest.
        val schemaUri = ServerResourceUri(ACME, ResourceKind.SCHEMAS, "schema-fixed")
        val recorded = mutableListOf<String>()
        val stubFinalizer = SchemaStagingFinalizer { session, principal, payload, _, _, _ ->
            recorded += "complete(session=${session.uploadSessionId}," +
                "principal=${principal.principalId.value},bytes=${payload.sizeBytes})"
            schemaUri
        }
        val payload = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payload)
        val sessionStore = InMemoryUploadSessionStore()
        val segmentStore = InMemoryUploadSegmentStore()
        val quotaStore = InMemoryQuotaStore()
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxUploadSegmentBytes = 8),
            clock = FIXED_CLOCK,
            finalizer = stubFinalizer,
        )
        sessionStore.save(
            dev.dmigrate.server.core.upload.UploadSession(
                uploadSessionId = "ups-1",
                tenantId = ACME,
                ownerPrincipalId = ALICE,
                resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, "ups-1"),
                artifactKind = ArtifactKind.SCHEMA,
                mimeType = "application/octet-stream",
                sizeBytes = payload.size.toLong(),
                segmentTotal = 1,
                checksumSha256 = totalHash,
                uploadIntent = "schema_staging_readonly",
                state = UploadSessionState.ACTIVE,
                createdAt = FIXED_NOW,
                updatedAt = FIXED_NOW,
                idleTimeoutAt = FIXED_NOW.plusSeconds(300),
                absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(3600),
            ),
        )
        val outcome = handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
                        isFinalSegment = true, bytes = payload,
                    ),
                ),
                PRINCIPAL,
            ),
        )
        val json = parsePayload(outcome)
        json.get("uploadSessionState").asString shouldBe "COMPLETED"
        json.get("schemaRef").asString shouldBe schemaUri.render()
        recorded shouldBe listOf("complete(session=ups-1,principal=alice,bytes=${payload.size})")
    }

    test("AP 6.9: finalisation failure rolls the session to ABORTED and rethrows the typed exception") {
        // The finalizer throws ValidationErrorException (parse or
        // schema-validate failure). The handler must transition the
        // session to ABORTED so segment cleanup runs on the standard
        // lifecycle path; the exception propagates so the client sees
        // structured findings.
        val angryFinalizer = SchemaStagingFinalizer { _, _, _, _, _, _ ->
            throw dev.dmigrate.server.application.error.ValidationErrorException(
                listOf(
                    dev.dmigrate.server.application.error.ValidationViolation(
                        "schema",
                        "schema parse failed: malformed",
                    ),
                ),
            )
        }
        val payload = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payload)
        val sessionStore = InMemoryUploadSessionStore()
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = InMemoryUploadSegmentStore(),
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxUploadSegmentBytes = 8),
            clock = FIXED_CLOCK,
            finalizer = angryFinalizer,
        )
        sessionStore.save(
            dev.dmigrate.server.core.upload.UploadSession(
                uploadSessionId = "ups-1",
                tenantId = ACME,
                ownerPrincipalId = ALICE,
                resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, "ups-1"),
                artifactKind = ArtifactKind.SCHEMA,
                mimeType = "application/octet-stream",
                sizeBytes = payload.size.toLong(),
                segmentTotal = 1,
                checksumSha256 = totalHash,
                uploadIntent = "schema_staging_readonly",
                state = UploadSessionState.ACTIVE,
                createdAt = FIXED_NOW,
                updatedAt = FIXED_NOW,
                idleTimeoutAt = FIXED_NOW.plusSeconds(300),
                absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(3600),
            ),
        )
        shouldThrow<dev.dmigrate.server.application.error.ValidationErrorException> {
            handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
                            isFinalSegment = true, bytes = payload,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        sessionStore.findById(ACME, "ups-1")!!.state shouldBe UploadSessionState.ABORTED
    }

    test("AP 6.16: non-final segment with offset != (index-1)*chunkSize throws VALIDATION_ERROR") {
        // Defense-in-depth: a client that sends an offset that
        // doesn't match the sequential layout the assembler reads
        // could sneak overlapping bytes past the store. The handler
        // pins the offset to the exact `(index-1) * chunkSize`
        // position for non-final segments.
        val seg1 = "abcdefgh".toByteArray() // 8 bytes
        val seg2 = "12345678".toByteArray()
        val totalHash = sha256Hex(seg1 + seg2)
        val f = fixture()
        stageSession(f, "ups-1", 16, segmentTotal = 2, checksumSha256 = totalHash)
        val ex = shouldThrow<dev.dmigrate.server.application.error.ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1,
                            segmentOffset = 4, // wrong: must be 0
                            segmentTotal = 2,
                            isFinalSegment = false,
                            bytes = seg1,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "segmentOffset"
    }

    test("AP 6.16: final segment with offset != (index-1)*chunkSize throws VALIDATION_ERROR") {
        val seg1 = "abcdefgh".toByteArray()
        val seg2 = "12345678".toByteArray()
        val totalHash = sha256Hex(seg1 + seg2)
        val f = fixture()
        stageSession(f, "ups-1", 16, segmentTotal = 2, checksumSha256 = totalHash)
        // Stage segment 1 at the right offset.
        f.handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                        isFinalSegment = false, bytes = seg1,
                    ),
                ),
                PRINCIPAL,
            ),
        )
        val ex = shouldThrow<dev.dmigrate.server.application.error.ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 2,
                            segmentOffset = 12, // wrong: must be 8
                            segmentTotal = 2,
                            isFinalSegment = true,
                            bytes = seg2,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "segmentOffset"
    }

    test("AP 6.16: final segment that doesn't close the byte range exactly throws VALIDATION_ERROR") {
        // 12-byte session, 8-byte chunks, expected segments: 2.
        // Final segment at index=2 must have offset=8 and size=4 to
        // close the range. Sending size=8 makes offset+size=16 ≠ 12.
        val seg1 = "abcdefgh".toByteArray() // 8
        val seg2 = "1234".toByteArray() // 4
        val totalHash = sha256Hex(seg1 + seg2)
        val f = fixture()
        stageSession(f, "ups-1", 12, segmentTotal = 2, checksumSha256 = totalHash)
        f.handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(
                    segmentArgs(
                        segmentIndex = 1, segmentOffset = 0, segmentTotal = 2,
                        isFinalSegment = false, bytes = seg1,
                    ),
                ),
                PRINCIPAL,
            ),
        )
        val ex = shouldThrow<dev.dmigrate.server.application.error.ValidationErrorException> {
            // Send an oversized final segment that lands offset+size
            // at 16 instead of 12.
            val tooLong = "12345678".toByteArray()
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 2, segmentOffset = 8, segmentTotal = 2,
                            isFinalSegment = true, bytes = tooLong,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.map { it.field } shouldContain "segmentOffset"
    }

    test("PARALLEL_SEGMENT_WRITES quota exhaustion surfaces RATE_LIMITED") {
        val payload = "abcdefgh".toByteArray()
        val f = fixture(parallelLimit = 0)
        stageSession(f, "ups-1", payload.size.toLong(), segmentTotal = 1, checksumSha256 = sha256Hex(payload))
        shouldThrow<RateLimitedException> {
            f.handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        segmentArgs(
                            segmentIndex = 1, segmentOffset = 0, segmentTotal = 1,
                            isFinalSegment = true, bytes = payload,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
    }
})
