// adapters:driving:mcp — MCP-v1-Server-Adapter (docs/ImpPlan-0.9.6-B.md §12).
// Deps werden pro AP dazugenommen, sobald sie tatsaechlich benutzt werden;
// driven-Adapter folgen in AP 6.3, lsp4j in AP 6.4, Nimbus + ktor-client
// in AP 6.6.
dependencies {
    implementation(project(":hexagon:core"))
    implementation(project(":hexagon:application"))
    implementation(project(":hexagon:ports-common"))

    // §12.2 — Ktor (CIO engine) als HTTP-Server (AP 6.2 Bootstrap)
    val ktorVersion = rootProject.properties["ktorVersion"]
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")

    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

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
