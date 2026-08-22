// End-to-end + scenario tests for the d-migrate CLI: MCP harness scenarios
// (stdio + HTTP transports against the file-backed wiring) and Data
// export/import via real PostgreSQL/MySQL containers. Excluded from the
// default unit-test run via the Kotest `integration` tag (siehe Root
// build.gradle.kts). Activate with -PintegrationTests.
//
// These tests instantiate the full CLI in-process, so the test classpath
// mirrors the production runtime classpath of :adapters:driving:cli.

dependencies {
    testImplementation(project(":adapters:driving:cli"))
    testImplementation(project(":adapters:driving:mcp"))
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:application"))
    testImplementation(project(":hexagon:profiling"))
    testImplementation(project(":hexagon:ports-common"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:driver-postgresql"))
    testImplementation(project(":adapters:driven:driver-mysql"))
    testImplementation(project(":adapters:driven:driver-sqlite"))
    // MSSQL Slice 1a: Gate-Ablehnungs-E2Es (containerlos) + schema-reverse-
    // Subprozess-E2E gegen den SQL-Server-Testcontainer (ADR 0047).
    testImplementation(project(":adapters:driven:driver-mssql"))
    testImplementation(project(":adapters:driven:formats"))
    // S7-Review-Fix Finding 10: erlaubt dem DataParquetRoundTripE2EPostgresTest,
    // den Footer-KV der exportierten Parquet-Datei via
    // ParquetSingleFileManifestReader direkt zu inspizieren.
    testImplementation(project(":adapters:driven:formats-parquet"))
    testImplementation(project(":adapters:driven:integrations"))
    testImplementation(project(":adapters:driven:persistence-jdbc"))
    testImplementation(project(":adapters:driven:streaming"))
    testImplementation(project(":adapters:driven:audit-logging"))
    testImplementation(project(":adapters:driven:storage-file"))
    // S3.4c: MCP-Protokoll-E2E mit `artifacts.store: s3` gegen SeaweedFS —
    // S3ClientFactory/S3StorageConfig fuer Bucket-Bootstrap + Asserts,
    // testFixtures liefern das SeaweedFS-Container-Setup.
    testImplementation(project(":adapters:driven:storage-s3"))
    testImplementation(testFixtures(project(":adapters:driven:storage-s3")))
    testImplementation(platform("software.amazon.awssdk:bom:${rootProject.properties["awsSdkVersion"]}"))
    testImplementation("software.amazon.awssdk:s3")
    testImplementation("software.amazon.awssdk:url-connection-client")
    testImplementation(project(":adapters:driven:connection-config"))
    testImplementation(testFixtures(project(":hexagon:ports-common")))
    testImplementation(project(":adapters:driven:persistence-memory"))

    testImplementation("com.github.ajalt.clikt:clikt:${rootProject.properties["cliktVersion"]}")
    testImplementation("ch.qos.logback:logback-classic:${rootProject.properties["logbackVersion"]}")
    testImplementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")
    testImplementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-mysql:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-mssqlserver:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("com.microsoft.sqlserver:mssql-jdbc:${rootProject.properties["mssqlJdbcVersion"]}")

    // Integration-test harnesses build JSON-RPC payloads with Gson and
    // validate tool runtime outputs against McpToolSchemas (JSON Schema
    // 2020-12).
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("com.networknt:json-schema-validator:1.5.4")
}
