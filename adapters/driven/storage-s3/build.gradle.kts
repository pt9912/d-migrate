// adapters:driven:storage-s3: S3-kompatible Implementierungen der
// Byte-Store-Ports `ArtifactContentStore` + `UploadSegmentStore`
// (ImpPlan-0.9.8-object-storage-s3). Verdict object-storage-s3-eval.md:
// AWS SDK for Java v2 mit `url-connection-client` (sync-Transport). Native
// S3-Multipart nur fuer `ArtifactContentStore.write` grosser Artefakte
// (> 5 GiB); Upload-Segmente = eigenstaendige S3-Objekte (Eval §2-Korrektur).
//
// S3.0/S3.1: Modul-Skelett + Dependency. Produktive Impls folgen in
// S3.2 (`S3ArtifactContentStore`) und S3.3 (`S3UploadSegmentStore`).
plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports-common"))

    implementation(platform("software.amazon.awssdk:bom:${rootProject.properties["awsSdkVersion"]}"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    // S3.4a: `artifacts`-Sektion der `.d-migrate.yaml` parsen. Dieselbe
    // YAML-Toolchain wie JSON/YAML/CSV + connection-config — kein zweiter
    // YAML-Stack, bereits im Distributions-Artefakt.
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")

    testImplementation(testFixtures(project(":hexagon:ports-common")))
}

kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

configurations.all {
    // Eval §6 / S3.0-Gate: `url-connection-client` ist der gewaehlte
    // sync-Transport. Die Default-HTTP-Clients (Netty async, Apache sync)
    // raus, damit kein Netty/Apache-Stack ins Distributions-Artefakt wandert
    // (Footprint-Ziel, 1.0.0-Native-Image-Cut).
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    exclude(group = "software.amazon.awssdk", module = "apache-client")
}
