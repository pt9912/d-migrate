// Integrationstests fuer den S3-Storage-Adapter gegen SeaweedFS via
// Testcontainers. Aus dem Default-Unit-Run ausgeschlossen (Projektpfad
// :test:integration-* → onlyIf in der Root build.gradle.kts); aktivieren
// mit -PintegrationTests.
//
// S3.0-Gate-Spike (ImpPlan-0.9.8-object-storage-s3): validiert die
// SeaweedFS-S3-Kompatibilitaet (User-Metadata bei HeadObject, Range-GET,
// Multipart) als reales Demo-Ziel — Gate-Punkte (3)/(4). Beherbergt spaeter
// die S3.5-Vertragssuiten (ArtifactContentStoreContractTests etc.).

dependencies {
    testImplementation(project(":adapters:driven:storage-s3"))
    testImplementation(project(":hexagon:ports-common"))
    testImplementation(testFixtures(project(":hexagon:ports-common")))

    // storage-s3 exponiert das AWS SDK nur als implementation; der Spike
    // ruft S3Client direkt, daher eigene testImplementation.
    testImplementation(platform("software.amazon.awssdk:bom:${rootProject.properties["awsSdkVersion"]}"))
    testImplementation("software.amazon.awssdk:s3")
    testImplementation("software.amazon.awssdk:url-connection-client")

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("io.kotest:kotest-runner-junit5:${rootProject.properties["kotestVersion"]}")
    testImplementation("io.kotest:kotest-assertions-core:${rootProject.properties["kotestVersion"]}")
}
