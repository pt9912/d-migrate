package dev.dmigrate.cli.commands

import dev.dmigrate.server.adapter.storage.file.FileBackedArtifactContentStore
import dev.dmigrate.server.adapter.storage.file.FileBackedUploadSegmentStore
import dev.dmigrate.server.adapter.storage.file.FileSpoolAssembledUploadPayloadFactory
import dev.dmigrate.mcp.cursor.CursorKey
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.server.adapter.storage.s3.ArtifactStorageConfig
import dev.dmigrate.server.adapter.storage.s3.S3ArtifactContentStore
import dev.dmigrate.server.adapter.storage.s3.S3StorageConfig
import dev.dmigrate.server.adapter.storage.s3.S3UploadSegmentStore
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpCliRuntimeWiringTest : FunSpec({

    test("runtimeWiring uses file-backed byte stores under the supplied state dir") {
        val dir = Files.createTempDirectory("dmigrate-mcp-wiring-")
        try {
            val wiring = McpCliRuntimeWiring.runtimeWiring(stateDir = dir)

            wiring.uploadSegmentStore.shouldBeInstanceOf<FileBackedUploadSegmentStore>()
            wiring.artifactContentStore.shouldBeInstanceOf<FileBackedArtifactContentStore>()
        } finally {
            dir.deleteRecursively()
        }
    }

    test("runtimeWiring uses the file-spool assembly factory (LF-012 / LN-027 / LN-028 / LN-038 heap guarantee)") {
        // Regression guard: McpRuntimeWiring's default for
        // assembledUploadPayloadFactory is the in-memory variant,
        // which would defeat the LF-012 / LN-027 / LN-028 / LN-038 streaming guarantee. The
        // CLI-production wiring MUST inject the file-spool factory.
        val dir = Files.createTempDirectory("dmigrate-mcp-wiring-spool-")
        try {
            val wiring = McpCliRuntimeWiring.runtimeWiring(stateDir = dir)
            wiring.assembledUploadPayloadFactory
                .shouldBeInstanceOf<FileSpoolAssembledUploadPayloadFactory>()
        } finally {
            dir.deleteRecursively()
        }
    }

    test("runtimeWiring with artifacts.store=s3 selects the S3 byte stores (S3.4b)") {
        val dir = Files.createTempDirectory("dmigrate-mcp-wiring-s3-")
        try {
            // Client construction is offline — no S3 endpoint is contacted
            // until a store operation runs (round-trip lives in
            // :test:integration-storage-s3 against SeaweedFS).
            val wiring = McpCliRuntimeWiring.runtimeWiring(
                stateDir = dir,
                artifacts = ArtifactStorageConfig.S3(
                    S3StorageConfig(bucket = "wiring-bucket", endpoint = URI.create("http://localhost:1")),
                ),
            )

            wiring.uploadSegmentStore.shouldBeInstanceOf<S3UploadSegmentStore>()
            wiring.artifactContentStore.shouldBeInstanceOf<S3ArtifactContentStore>()
            // The assembly spool is a local streaming concern and MUST stay
            // file-backed in s3 mode too.
            wiring.assembledUploadPayloadFactory
                .shouldBeInstanceOf<FileSpoolAssembledUploadPayloadFactory>()
        } finally {
            dir.deleteRecursively()
        }
    }

    test("runtimeWiring loads secret-free connection refs from connectionConfigPath") {
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
            val wiring = McpCliRuntimeWiring.runtimeWiring(
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

    test("runtimeWiring uses configured deterministic cursor keyring when supplied") {
        val dir = Files.createTempDirectory("dmigrate-mcp-wiring-cursor-")
        try {
            val keyring = CursorKeyring(
                signing = CursorKey("cursor-active", ByteArray(32) { 7 }),
            )
            val wiring = McpCliRuntimeWiring.runtimeWiring(
                stateDir = dir,
                cursorKeyring = keyring,
            )

            wiring.cursorKeyring.signing.kid shouldBe "cursor-active"
        } finally {
            dir.deleteRecursively()
        }
    }
})
