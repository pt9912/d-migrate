package dev.dmigrate.server.adapter.storage.s3

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI

/**
 * S3.4b: [S3ByteStores.create] kapselt den `S3Client` als Modul-Detail.
 * Client-Konstruktion ist offline — der Round-Trip gegen SeaweedFS liegt
 * im Wiring-IT (`:test:integration-storage-s3`).
 */
class S3ByteStoresTest : FunSpec({

    test("create builds both byte stores from a single config; close releases the client") {
        val stores = S3ByteStores.create(
            S3StorageConfig(
                bucket = "bundle-bucket",
                endpoint = URI.create("http://localhost:1"),
                keyPrefix = "bundle-prefix",
            ),
        )

        stores.uploadSegmentStore.shouldBeInstanceOf<S3UploadSegmentStore>()
        stores.artifactContentStore.shouldBeInstanceOf<S3ArtifactContentStore>()
        stores.close()
    }
})
