package dev.dmigrate.server.adapter.storage.s3

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Geteiltes SeaweedFS-Container-Setup fuer alle Specs dieses Moduls
 * (Spike, Vertragssuiten, Wiring-IT) — Image-Version, Identity und
 * Volume-Flags leben genau einmal hier.
 *
 * Identity: SeaweedFS lehnt signierte Objekt-Operationen ohne
 * `-s3.config`-Identity ab ("Signed request requires setting up SeaweedFS
 * S3 authentication", bi-demo-compose.md) — daher wird eine Test-Identity
 * zur Laufzeit injiziert.
 *
 * Volume-Flags: SeaweedFS cappt die Volume-Anzahl nach freiem Disk-Space,
 * und jeder frische Bucket (= Collection) alloziert beim ersten Write
 * 7 Volumes a volumeSizeLimitMB (Default 1 GiB). Bucket-pro-Test
 * erschoepft auf CI-Runnern mit wenig freiem Platz die Slots nach wenigen
 * Tests -> dauerhafte 500 InternalError ("topo failed to pick 1 from 0
 * node candidates"). Kleine Volumes + explizites Maximum machen die Slots
 * praktisch unerschoepflich (Volumes sind sparse, kein realer
 * Disk-Verbrauch).
 */
internal const val SEAWEED_TEST_ACCESS_KEY = "seaweedtestkey"
internal const val SEAWEED_TEST_SECRET_KEY = "seaweedtestsecret"

private val IDENTITY_CONFIG = """
    {"identities":[{"name":"test","credentials":[{"accessKey":"$SEAWEED_TEST_ACCESS_KEY","secretKey":"$SEAWEED_TEST_SECRET_KEY"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
""".trimIndent()

internal const val SEAWEED_S3_PORT = 8333

internal class SeaweedS3TestContainer :
    GenericContainer<SeaweedS3TestContainer>(DockerImageName.parse("chrislusf/seaweedfs:4.31"))

internal fun newSeaweedS3Container(): SeaweedS3TestContainer =
    SeaweedS3TestContainer()
        .withCopyToContainer(Transferable.of(IDENTITY_CONFIG), "/etc/seaweed/s3.json")
        .withCommand(
            "server", "-dir=/data", "-s3", "-s3.config=/etc/seaweed/s3.json",
            "-master.volumeSizeLimitMB=64", "-volume.max=10000",
        )
        .withExposedPorts(SEAWEED_S3_PORT)
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofSeconds(120))

internal fun SeaweedS3TestContainer.s3Endpoint(): String =
    "http://$host:${getMappedPort(SEAWEED_S3_PORT)}"
