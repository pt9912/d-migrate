package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.ports.contract.ArtifactContentStoreContractTests
import java.nio.file.Files

class FileBackedArtifactContentStoreTest : ArtifactContentStoreContractTests({
    FileBackedArtifactContentStore(Files.createTempDirectory("d-migrate-artifact-store-"))
})
