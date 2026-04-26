// adapters:driving:mcp — MCP-v1-Server-Adapter (docs/ImpPlan-0.9.6-B.md §12).
// Deps werden pro AP dazugenommen, sobald sie tatsaechlich benutzt werden;
// lsp4j folgt in AP 6.4, Nimbus + ktor-client in AP 6.6.
dependencies {
    implementation(project(":hexagon:core"))
    implementation(project(":hexagon:application"))
    implementation(project(":hexagon:ports"))
    implementation(project(":hexagon:ports-common"))

    // §6.3 Runtime-Bootstrap — Driver, Profiling, Streaming, Formate
    // muessen auf dem Runtime-Classpath liegen, damit ServiceLoader
    // (RuntimeBootstrap.initialize) sie entdeckt. Compile-Zugriff auf
    // Driver-/Codec-/Streaming-APIs gibt es ueber transitive Reads
    // (formats fuer SchemaFileResolver, streaming wenn Tool-Handler in
    // Phase C/D Ports brauchen).
    implementation(project(":adapters:driven:formats"))
    implementation(project(":adapters:driven:streaming"))
    runtimeOnly(project(":adapters:driven:driver-common"))
    runtimeOnly(project(":adapters:driven:driver-postgresql"))
    runtimeOnly(project(":adapters:driven:driver-postgresql-profiling"))
    runtimeOnly(project(":adapters:driven:driver-mysql"))
    runtimeOnly(project(":adapters:driven:driver-mysql-profiling"))
    runtimeOnly(project(":adapters:driven:driver-sqlite"))
    runtimeOnly(project(":adapters:driven:driver-sqlite-profiling"))

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
