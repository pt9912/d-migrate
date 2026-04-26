// adapters:driven:storage-file: file-backed Implementierungen der
// Byte-Store-Ports `UploadSegmentStore` und `ArtifactContentStore`
// (0.9.6 Phase A AP 6.3). Phase A liefert produktnahe Spool-Pfade auf
// dem lokalen Dateisystem; spaetere Phasen koennen Cloud-/Object-Stores
// als zusaetzliche Adapter-Module erganzen.
dependencies {
    api(project(":hexagon:ports-common"))

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
