package dev.dmigrate.cli.commands

import dev.dmigrate.server.adapter.storage.file.FileBackedArtifactContentStore
import dev.dmigrate.server.adapter.storage.file.FileBackedUploadSegmentStore
import dev.dmigrate.server.adapter.storage.file.FileSpoolAssembledUploadPayloadFactory
import io.kotest.core.spec.style.FunSpec
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
})
