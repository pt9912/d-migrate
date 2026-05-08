// adapters:driven:storage-file: file-backed Implementierungen der
// Byte-Store-Ports `UploadSegmentStore` und `ArtifactContentStore`
// (LN-009 / LN-011). Liefert produktnahe Spool-Pfade auf dem lokalen
// Dateisystem; Cloud-/Object-Stores koennen als zusaetzliche Adapter-
// Module ergaenzt werden.
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
