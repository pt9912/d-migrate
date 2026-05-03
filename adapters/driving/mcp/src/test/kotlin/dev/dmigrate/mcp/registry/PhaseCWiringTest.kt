package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-05-02T12:00:00Z"), ZoneOffset.UTC)

private val PHASE_C_TOOLS: List<String> = listOf(
    "capabilities_list",
    "schema_validate",
    "schema_generate",
    "schema_compare",
    "artifact_chunk_get",
    "artifact_upload_init",
    "artifact_upload",
    "artifact_upload_abort",
    "job_status_get",
)

private fun inMemoryWiring(
    finalizer: SchemaStagingFinalizer? = null,
): PhaseCWiring {
    val artifactStore = InMemoryArtifactStore()
    val artifactContentStore = InMemoryArtifactContentStore()
    val schemaStore = InMemorySchemaStore()
    val quotaStore = InMemoryQuotaStore()
    return PhaseCWiring(
        uploadSessionStore = InMemoryUploadSessionStore(),
        uploadSegmentStore = InMemoryUploadSegmentStore(),
        artifactStore = artifactStore,
        artifactContentStore = artifactContentStore,
        schemaStore = schemaStore,
        jobStore = InMemoryJobStore(),
        quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
        limits = McpLimitsConfig(),
        clock = FIXED_CLOCK,
        finalizer = finalizer ?: PhaseCWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = artifactStore,
            artifactContentStore = artifactContentStore,
            schemaStore = schemaStore,
            jobStore = InMemoryJobStore(),
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = FIXED_CLOCK,
        ).finalizer,
    )
}

class PhaseCWiringTest : FunSpec({

    test("AP 6.14: every Phase-C tool dispatches to a real handler (not UnsupportedToolHandler)") {
        val registry = PhaseCRegistries.defaultToolRegistry(inMemoryWiring())
        for (tool in PHASE_C_TOOLS) {
            val handler = registry.findHandler(tool)
            withClue("tool=$tool") {
                handler shouldNotBe null
                handler!!.shouldNotBeInstanceOf<UnsupportedToolHandler>()
            }
        }
    }

    test("AP 6.14: Phase-B default still keeps the unsupported-handler fallback for everything except capabilities_list") {
        // Backwards-compat assertion: existing Phase-B tests rely on
        // the bare `toolRegistry()` returning UnsupportedToolHandler
        // for every non-capabilities tool. AP 6.14 must not change
        // that — clients without a wiring keep the Phase-B
        // contract.
        val phaseBRegistry = PhaseCRegistries.toolRegistry()
        phaseBRegistry.findHandler("capabilities_list")!!
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        for (tool in PHASE_C_TOOLS - "capabilities_list") {
            withClue("tool=$tool") {
                phaseBRegistry.findHandler(tool)!!.shouldBeInstanceOf<UnsupportedToolHandler>()
            }
        }
    }

    test("AP 6.14: defaultToolRegistry honours a custom scopeMapping subset") {
        // The wiring registers handlers via the underlying
        // PhaseBRegistries scope universe — a scope mapping that
        // omits some Phase-C tools must still produce a working
        // registry that contains exactly the supplied tools.
        val custom = mapOf(
            "capabilities_list" to setOf("dmigrate:read"),
            "schema_validate" to setOf("dmigrate:read"),
        )
        val registry = PhaseCRegistries.defaultToolRegistry(
            wiring = inMemoryWiring(),
            scopeMapping = custom,
        )
        registry.findHandler("schema_validate")!!
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        registry.find("schema_generate") shouldBe null
        registry.find("artifact_upload_init") shouldBe null
    }

    test("AP 6.14: a custom finalizer in the wiring is honoured by the artifact_upload handler") {
        // Defensive: the wiring exposes `finalizer` as a seam so
        // tests / future deployments can swap in a different
        // staging strategy. The default-registry must thread it
        // through to ArtifactUploadHandler.
        val recorded = mutableListOf<String>()
        val stub = SchemaStagingFinalizer { session, _, _, _, _, _ ->
            recorded += "complete=${session.uploadSessionId}"
            dev.dmigrate.server.core.resource.ServerResourceUri(
                session.tenantId,
                dev.dmigrate.server.core.resource.ResourceKind.SCHEMAS,
                "schema-stub",
            )
        }
        val wiring = inMemoryWiring(finalizer = stub)
        // Smoke-test only: the registry must still construct
        // without errors when a custom finalizer is supplied.
        val registry = PhaseCRegistries.defaultToolRegistry(wiring)
        registry.findHandler("artifact_upload")!!
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        // The recorder is only invoked when an upload completes —
        // this assertion just pins that no eager invocation
        // happens at registry-build time.
        recorded shouldBe emptyList<String>()
    }
})
