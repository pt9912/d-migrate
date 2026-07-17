// adapters:driven:text-icu — ICU4J-based driven adapter for the
// `UnicodeTextService` port in `hexagon:ports-common`. Encapsulates the
// ICU4J runtime so the application layer stays free of Unicode-library
// specifics.
//
// Driving adapters (CLI, MCP, REST/gRPC) depend on this module at the
// composition root and instantiate `IcuUnicodeTextService`. The
// application layer never references this module.
dependencies {
    api(project(":hexagon:ports-common"))

    implementation("com.ibm.icu:icu4j:78.3")

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
