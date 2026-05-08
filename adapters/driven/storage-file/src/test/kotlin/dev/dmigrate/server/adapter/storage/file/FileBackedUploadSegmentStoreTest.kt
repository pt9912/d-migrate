package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.ports.contract.UploadSegmentStoreContractTests
import java.nio.file.Files

class FileBackedUploadSegmentStoreTest : UploadSegmentStoreContractTests({
    FileBackedUploadSegmentStore(Files.createTempDirectory("d-migrate-segment-store-"))
})
