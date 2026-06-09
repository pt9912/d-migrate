package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.server.ports.WriteArtifactOutcome
import dev.dmigrate.server.ports.contract.ArtifactContentStoreContractTests
import dev.dmigrate.server.ports.contract.UploadSegmentStoreContractTests
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.S3Client
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * S3.2/S3.5: die wiederverwendbare [ArtifactContentStoreContractTests]-Suite,
 * gegen [S3ArtifactContentStore] auf einem SeaweedFS-Testcontainer ausgefuehrt
 * (reales Demo-Ziel, bi-demo §5.3). Subclassing-Muster analog
 * `FileBackedArtifactContentStoreTest`. Der `S3Client` kommt aus
 * [S3ClientFactory] — so wird auch die gate-validierte Client-Config (S3.1)
 * mitgetestet. Jeder `factory()`-Aufruf bekommt einen frischen Bucket auf dem
 * geteilten Container (Test-Isolation).
 */

private class ContractSeaweed :
    GenericContainer<ContractSeaweed>(DockerImageName.parse("chrislusf/seaweedfs:4.31"))

private const val CT_ACCESS_KEY = "ctkey"
private const val CT_SECRET_KEY = "ctsecret"
private val CT_CONFIG = """
    {"identities":[{"name":"ct","credentials":[{"accessKey":"$CT_ACCESS_KEY","secretKey":"$CT_SECRET_KEY"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
""".trimIndent()

private val container: ContractSeaweed by lazy {
    ContractSeaweed()
        .withCopyToContainer(Transferable.of(CT_CONFIG), "/etc/seaweed/s3.json")
        .withCommand("server", "-dir=/data", "-s3", "-s3.config=/etc/seaweed/s3.json")
        .withExposedPorts(8333)
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofSeconds(120))
        .apply { start() }
}

private val s3Client: S3Client by lazy {
    S3ClientFactory.create(
        S3StorageConfig(
            bucket = "unused",
            endpoint = URI.create("http://${container.host}:${container.getMappedPort(8333)}"),
            accessKey = CT_ACCESS_KEY,
            secretKey = CT_SECRET_KEY,
        ),
    )
}

private val bucketCounter = AtomicInteger()

private fun freshStore(): S3ArtifactContentStore {
    val bucket = "ct${bucketCounter.incrementAndGet()}"
    s3Client.createBucket { it.bucket(bucket) }
    return S3ArtifactContentStore(s3Client, bucket)
}

private fun freshSegmentStore(): S3UploadSegmentStore {
    val bucket = "seg${bucketCounter.incrementAndGet()}"
    s3Client.createBucket { it.bucket(bucket) }
    return S3UploadSegmentStore(s3Client, bucket)
}

class S3ArtifactContentStoreSeaweedTest : ArtifactContentStoreContractTests({ freshStore() })

class S3UploadSegmentStoreSeaweedTest : UploadSegmentStoreContractTests({ freshSegmentStore() })

class S3ArtifactContentStoreMultipartTest : FunSpec({
    test("write eines > 8-MiB-Artefakts nimmt den Multipart-Pfad und round-trips") {
        val store = freshStore()
        val size = 20 * 1024 * 1024
        val data = ByteArray(size) { (it % 251).toByte() }
        val outcome = store.write("big", ByteArrayInputStream(data), size.toLong())
        outcome.shouldBeInstanceOf<WriteArtifactOutcome.Stored>()
        store.exists("big") shouldBe true
        val tail = store.openRangeRead("big", offset = size - 4L, length = 4).readAllBytes()
        tail.toList() shouldBe data.takeLast(4)
    }
})
