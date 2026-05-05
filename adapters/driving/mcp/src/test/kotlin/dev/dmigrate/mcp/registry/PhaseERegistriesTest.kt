package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PhaseERegistriesTest : FunSpec({

    val clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)

    fun phaseEWiring(): PhaseEWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val quotaStore = InMemoryQuotaStore()
        val phaseC = PhaseCWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            jobStore = jobStore,
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = clock,
        )
        return PhaseEWiring(
            phaseCWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
        )
    }

    test("defaultToolRegistry: Phase-E Start-Tools sind produktive Handler") {
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        registry.findHandler("schema_reverse_start")
            .shouldBeInstanceOf<SchemaReverseStartHandler>()
        registry.findHandler("data_profile_start")
            .shouldBeInstanceOf<DataProfileStartHandler>()
        registry.findHandler("schema_compare_start")
            .shouldBeInstanceOf<SchemaCompareStartHandler>()
    }

    test("defaultToolRegistry: Start-Tools sind nicht mehr UnsupportedToolHandler") {
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        registry.findHandler("schema_reverse_start")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("data_profile_start")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("schema_compare_start")
            .shouldNotBeInstanceOf<UnsupportedToolHandler>()
    }

    test("defaultToolRegistry: Phase-C-Handler bleiben unveraendert (Sample: schema_validate)") {
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        // schema_validate ist Phase-C, kein UnsupportedToolHandler.
        val handler = registry.findHandler("schema_validate")
        handler shouldNotBe null
        handler.shouldNotBeInstanceOf<UnsupportedToolHandler>()
    }

    test("defaultToolRegistry: nicht-E-Tools, die noch nicht implementiert sind, bleiben Unsupported") {
        // data_export_start, data_import_start, data_transfer_start sind
        // Phase-F-Carve-out. Plan §3.2 schliesst sie aus dieser Phase aus.
        val registry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        registry.findHandler("data_export_start")
            .shouldBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("data_import_start")
            .shouldBeInstanceOf<UnsupportedToolHandler>()
        registry.findHandler("data_transfer_start")
            .shouldBeInstanceOf<UnsupportedToolHandler>()
    }

    test("defaultToolRegistry: alle Descriptors aus PhaseC bleiben sichtbar") {
        val phaseCRegistry = PhaseCRegistries.defaultToolRegistry(phaseEWiring().phaseCWiring)
        val phaseERegistry = PhaseERegistries.defaultToolRegistry(phaseEWiring())
        phaseERegistry.all().map { it.name }.toSet() shouldBe
            phaseCRegistry.all().map { it.name }.toSet()
    }
})
