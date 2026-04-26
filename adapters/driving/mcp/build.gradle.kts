// adapters:driving:mcp — MCP-v1-Server-Adapter (docs/ImpPlan-0.9.6-B.md §12).
// Deps werden pro AP dazugenommen, sobald sie tatsaechlich benutzt werden.
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

    // §12.1 — JSON-RPC-Layer (AP 6.4 Initialize-Handler + NDJSON-Framing)
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:${rootProject.properties["lsp4jJsonrpcVersion"]}")

    // §12.2 — Ktor (CIO engine) als HTTP-Server (AP 6.2 Bootstrap)
    val ktorVersion = rootProject.properties["ktorVersion"]
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")

    // §12.3 / §12.14 — Bearer-Validation: Nimbus JOSE-JWT fuer JWT/JWKS,
    // Ktor-Client fuer RFC 7662 Token-Introspection, Jackson zum
    // Parsen der Introspection-JSON-Response.
    implementation("com.nimbusds:nimbus-jose-jwt:${rootProject.properties["nimbusJoseJwtVersion"]}")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.properties["jacksonVersion"]}")

    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")

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
