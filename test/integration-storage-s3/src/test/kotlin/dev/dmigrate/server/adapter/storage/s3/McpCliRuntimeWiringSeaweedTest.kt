package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.cli.commands.McpCliRuntimeWiring
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.WriteArtifactOutcome
import dev.dmigrate.server.ports.WriteSegmentOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration

/**
 * S3.4b-Wiring-IT (ImpPlan-0.9.8-object-storage-s3): treibt den echten
 * CLI-Composition-Root [McpCliRuntimeWiring] mit einer aus YAML geladenen
 * `artifacts.store: s3`-Config gegen SeaweedFS — YAML → [ArtifactsConfigLoader]
 * → `runtimeWiring` → S3-Stores → write/read/delete-Round-Trip.
 *
 * Credentials: der Loader traegt bewusst keine (env-only via
 * `DefaultCredentialsProviderChain`); da Testprozesse keine env setzen
 * koennen, injiziert der Test die Container-Identity per `copy` auf dem
 * geladenen [S3StorageConfig] — exakt der Wert, den die Chain aus
 * `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` liefern wuerde.
 */
private class WiringSeaweed :
    GenericContainer<WiringSeaweed>(DockerImageName.parse("chrislusf/seaweedfs:4.31"))

private const val WIRING_ACCESS_KEY = "wiringkey"
private const val WIRING_SECRET_KEY = "wiringsecret"
private val WIRING_CONFIG = """
    {"identities":[{"name":"wiring","credentials":[{"accessKey":"$WIRING_ACCESS_KEY","secretKey":"$WIRING_SECRET_KEY"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
""".trimIndent()

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class McpCliRuntimeWiringSeaweedTest : FunSpec({

    val container = WiringSeaweed()
        .withCopyToContainer(Transferable.of(WIRING_CONFIG), "/etc/seaweed/s3.json")
        // Volume-Slot-Limits wie im Contract-Setup (S3ArtifactContentStoreSeaweedTest):
        // verhindert Slot-Erschoepfung auf CI-Runnern mit wenig freiem Disk.
        .withCommand(
            "server", "-dir=/data", "-s3", "-s3.config=/etc/seaweed/s3.json",
            "-master.volumeSizeLimitMB=64", "-volume.max=10000",
        )
        .withExposedPorts(8333)
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofSeconds(120))

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    test("YAML artifacts.store=s3 wires S3 byte stores that round-trip against SeaweedFS") {
        val stateDir = Files.createTempDirectory("dmigrate-wiring-it-")
        val yaml = stateDir.resolve(".d-migrate.yaml")
        Files.writeString(
            yaml,
            """
            artifacts:
              store: s3
              s3:
                endpoint: "http://${container.host}:${container.getMappedPort(8333)}"
                bucket: "mcp-wiring"
                prefix: "wiring"
                pathStyle: true
            """.trimIndent(),
        )

        val loaded = ArtifactsConfigLoader.load(yaml).shouldBeInstanceOf<ArtifactStorageConfig.S3>()
        val withCreds = ArtifactStorageConfig.S3(
            loaded.config.copy(accessKey = WIRING_ACCESS_KEY, secretKey = WIRING_SECRET_KEY),
        )
        val rawClient = S3ClientFactory.create(withCreds.config)
        rawClient.createBucket { it.bucket(withCreds.config.bucket) }

        val wiring = McpCliRuntimeWiring.runtimeWiring(stateDir = stateDir, artifacts = withCreds)
        try {
            // Artefakt-Round-Trip ueber den verdrahteten Store.
            val artifactBytes = "wiring-artifact-payload".toByteArray()
            wiring.artifactContentStore
                .write("wired-art", ByteArrayInputStream(artifactBytes), artifactBytes.size.toLong())
                .shouldBeInstanceOf<WriteArtifactOutcome.Stored>()
            wiring.artifactContentStore.exists("wired-art") shouldBe true
            wiring.artifactContentStore.openRangeRead("wired-art", offset = 7, length = 8)
                .readAllBytes()
                .decodeToString() shouldBe "artifact"

            // Segment-Round-Trip ueber den verdrahteten Store.
            val segmentBytes = "wiring-segment-payload".toByteArray()
            val segment = UploadSegment(
                uploadSessionId = "wired-session",
                segmentIndex = 0,
                segmentOffset = 0,
                sizeBytes = segmentBytes.size.toLong(),
                segmentSha256 = sha256(segmentBytes),
            )
            wiring.uploadSegmentStore.writeSegment(segment, ByteArrayInputStream(segmentBytes))
                .shouldBeInstanceOf<WriteSegmentOutcome.Stored>()
            wiring.uploadSegmentStore.listSegments("wired-session") shouldHaveSize 1

            // `prefix` aus dem YAML fliesst bis in die Objekt-Keys durch.
            val keys = rawClient.listObjectsV2 { it.bucket(withCreds.config.bucket).prefix("wiring/") }
                .contents().map { it.key() }
            keys.any { it.endsWith("wired-art") } shouldBe true
            keys.any { it.contains("segments/wired-session/") } shouldBe true

            wiring.uploadSegmentStore.deleteAllForSession("wired-session") shouldBe 1
            wiring.artifactContentStore.delete("wired-art") shouldBe true
            wiring.artifactContentStore.exists("wired-art") shouldBe false
        } finally {
            runCatching {
                Files.walk(stateDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
})
