package dev.dmigrate.cli.commands

import dev.dmigrate.server.adapter.storage.file.FileBackedArtifactContentStore
import dev.dmigrate.server.adapter.storage.file.FileBackedUploadSegmentStore
import dev.dmigrate.server.adapter.storage.file.FileSpoolAssembledUploadPayloadFactory
import dev.dmigrate.mcp.cursor.CursorKey
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpCliPhaseCWiringTest : FunSpec({

    test("phaseCWiring uses file-backed byte stores under the supplied state dir") {
        val dir = Files.createTempDirectory("dmigrate-mcp-wiring-")
        try {
            val wiring = McpCliPhaseCWiring.phaseCWiring(stateDir = dir)

            wiring.uploadSegmentStore.shouldBeInstanceOf<FileBackedUploadSegmentStore>()
            wiring.artifactContentStore.shouldBeInstanceOf<FileBackedArtifactContentStore>()
        } finally {
            dir.deleteRecursively()
        }
    }

    test("phaseCWiring uses the file-spool assembly factory (AP 6.22 heap guarantee)") {
        // Regression guard: PhaseCWiring's default for
        // assembledUploadPayloadFactory is the in-memory variant,
        // which would defeat the AP-6.22 streaming guarantee. The
        // CLI-production wiring MUST inject the file-spool factory.
        val dir = Files.createTempDirectory("dmigrate-mcp-wiring-spool-")
        try {
            val wiring = McpCliPhaseCWiring.phaseCWiring(stateDir = dir)
            wiring.assembledUploadPayloadFactory
                .shouldBeInstanceOf<FileSpoolAssembledUploadPayloadFactory>()
        } finally {
            dir.deleteRecursively()
        }
    }

    test("phaseCWiring loads secret-free connection refs from connectionConfigPath") {
        val dir = Files.createTempDirectory("dmigrate-mcp-wiring-conn-")
        try {
            val config = dir.resolve(".d-migrate.yaml")
            Files.writeString(
                config,
                """
                database:
                  connections:
                    pg-prod:
                      displayName: "Production PG"
                      dialectId: postgresql
                      sensitivity: PRODUCTION
                      credentialRef: env:PG_PASS
                      providerRef: env
                """.trimIndent(),
            )
            val wiring = McpCliPhaseCWiring.phaseCWiring(
                stateDir = dir,
                connectionConfigPath = config,
            )

            val ref = wiring.connectionStore.findById(TenantId("default"), "pg-prod")
            ref?.displayName shouldBe "Production PG"
            ref?.dialectId shouldBe "postgresql"
            // The store intentionally retains refs for later authorised
            // runner paths; resources/read projection drops them at the
            // MCP boundary.
            ref?.credentialRef shouldBe "env:PG_PASS"
        } finally {
            dir.deleteRecursively()
        }
    }

    test("phaseCWiring uses configured deterministic cursor keyring when supplied") {
        val dir = Files.createTempDirectory("dmigrate-mcp-wiring-cursor-")
        try {
            val keyring = CursorKeyring(
                signing = CursorKey("cursor-active", ByteArray(32) { 7 }),
            )
            val wiring = McpCliPhaseCWiring.phaseCWiring(
                stateDir = dir,
                cursorKeyring = keyring,
            )

            wiring.cursorKeyring.signing.kid shouldBe "cursor-active"
        } finally {
            dir.deleteRecursively()
        }
    }
})
