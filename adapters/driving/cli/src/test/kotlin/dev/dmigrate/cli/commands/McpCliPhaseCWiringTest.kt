package dev.dmigrate.cli.commands

import dev.dmigrate.server.adapter.storage.file.FileBackedArtifactContentStore
import dev.dmigrate.server.adapter.storage.file.FileBackedUploadSegmentStore
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
})
