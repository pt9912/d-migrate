package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.cli.commands.McpCliRuntimeWiring
import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.WriteArtifactOutcome
import dev.dmigrate.server.ports.WriteSegmentOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.nio.file.Files

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
 *
 * Container-Setup (Image, Identity, Volume-Flags): SeaweedTestSupport.
 */
class McpCliRuntimeWiringSeaweedTest : FunSpec({

    val container = newSeaweedS3Container()

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
                endpoint: "${container.s3Endpoint()}"
                bucket: "mcp-wiring"
                prefix: "wiring"
                pathStyle: true
            """.trimIndent(),
        )

        val loaded = ArtifactsConfigLoader.load(yaml).shouldBeInstanceOf<ArtifactStorageConfig.S3>()
        val withCreds = ArtifactStorageConfig.S3(
            loaded.config.copy(accessKey = SEAWEED_TEST_ACCESS_KEY, secretKey = SEAWEED_TEST_SECRET_KEY),
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
                segmentSha256 = sha256Hex(segmentBytes),
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
