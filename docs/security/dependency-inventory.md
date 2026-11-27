# Dependency-Inventar (Runtime-SBOM des CLI-Artefakts)

> **Zweck:** Audit-Readiness ([ADR 0039](../adr/0039-externer-security-audit-kein-1.0.0-gate.md),
> Ticket [`audit-readiness-package.md`](../planning/open/audit-readiness-package.md)).
> Dieses Inventar ist die **aufgelöste** Liste für das ausgelieferte Artefakt.

**Scope:** `runtimeClasspath` des Moduls `:adapters:driving:cli` — die Dependencies der
ZIP-/TAR-Distribution und des JVM-Images (samt MCP-Server). Das native Binary wird aus
demselben Klassenpfad gebaut und enthält davon, was der Bau erreicht. Die d-migrate-eigenen
Module sind ausgeschlossen (s. `settings.gradle.kts`).

**Stand:** 2026-11-27 · **Koordinaten:** 175 (aufgelöste `group:name:version`,
einschließlich Plattform-BOMs und Metadaten-Modulen ohne eigenes Jar).

## Regenerieren

```
docker build --progress=plain --target build --no-cache-filter build \
  --build-arg GRADLE_TASKS=":adapters:driving:cli:dependencies --configuration runtimeClasspath" \
  -t d-migrate:deps-list . 2>&1 | tee deps.log
```

Die Koordinaten aus dem Log extrahieren: bei `X -> Y` gilt die aufgelöste Version `Y`;
`project :…` und die mit `(c)` markierten Constraint-Zeilen zählen nicht. Denselben
Klassenpfad meldet `.github/workflows/dependency-submission.yml` bei jedem `main`-Push an
den GitHub-Dependency-Graph.

## Sicherheits-relevante Kern-Dependencies (Schnellzugriff)

| Fläche | Artefakt(e) | Version |
| ------ | ----------- | ------- |
| JDBC-Treiber PostgreSQL | `org.postgresql:postgresql` | 42.7.12 |
| JDBC-Treiber MySQL | `com.mysql:mysql-connector-j` | 9.6.0 |
| JDBC-Treiber SQLite (native lib) | `org.xerial:sqlite-jdbc` | 3.53.4.0 |
| JDBC-Treiber MS SQL Server | `com.microsoft.sqlserver:mssql-jdbc` | 13.4.0.jre11 |
| JDBC-Treiber Oracle | `com.oracle.database.jdbc:ojdbc11` | 23.26.3.0.0 |
| Connection-Pool | `com.zaxxer:HikariCP` | 6.2.1 |
| MCP-Auth (JWT/JWKS-Validierung) | `com.nimbusds:nimbus-jose-jwt` | 10.9 |
| MCP-HTTP-Transport (Netz) | `io.ktor:ktor-server-cio`, `io.ktor:ktor-network-tls` | 3.0.3 |
| MCP-JSON-RPC-Parsing | `com.google.code.gson:gson` | 2.15.0 (≥2.11 → strukturelles Nesting-Limit; s. `JsonNestingGuard`) |
| JSON/YAML-Deserialisierung (read-side) | `com.fasterxml.jackson.core:jackson-databind` | 2.21.7 |
| Jackson 3 (über Flyway) | `tools.jackson.core:jackson-databind` | 3.1.7 |
| Unicode-Normalisierung (Payload-Fingerprint) | `com.ibm.icu:icu4j` | 79.1 |
| Parquet-I/O | `org.apache.parquet:parquet-*`, `org.apache.hadoop:hadoop-common` | 1.18.2 / 3.4.1 |
| S3-Storage-Adapter | `software.amazon.awssdk:*` | 2.44.14 |
| Logging | `ch.qos.logback:logback-classic` | 1.5.34 |
| Migrationen für `server.state` | `org.flywaydb:flyway-core`, `flyway-database-postgresql` | 13.1.0 |

## Vollständige Liste

```
ch.qos.logback:logback-classic:1.5.34
ch.qos.logback:logback-core:1.5.34
ch.qos.reload4j:reload4j:1.2.22
com.dslplatform:dsl-json-java8:1.10.0
com.dslplatform:dsl-json:1.10.0
com.fasterxml.jackson.core:jackson-annotations:2.21
com.fasterxml.jackson.core:jackson-core:2.21.7
com.fasterxml.jackson.core:jackson-databind:2.21.7
com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.7
com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.7
com.fasterxml.jackson.module:jackson-module-kotlin:2.21.7
com.fasterxml.jackson:jackson-bom:2.21.7
com.fasterxml.woodstox:woodstox-core:5.4.0
com.github.ajalt.clikt:clikt-core-jvm:5.0.3
com.github.ajalt.clikt:clikt-core:5.0.3
com.github.ajalt.clikt:clikt-jvm:5.0.3
com.github.ajalt.clikt:clikt:5.0.3
com.github.ajalt.colormath:colormath-jvm:3.6.0
com.github.ajalt.colormath:colormath:3.6.0
com.github.ajalt.mordant:mordant-core-jvm:3.0.1
com.github.ajalt.mordant:mordant-core:3.0.1
com.github.ajalt.mordant:mordant-jvm-ffm-jvm:3.0.1
com.github.ajalt.mordant:mordant-jvm-ffm:3.0.1
com.github.ajalt.mordant:mordant-jvm-graal-ffi-jvm:3.0.1
com.github.ajalt.mordant:mordant-jvm-graal-ffi:3.0.1
com.github.ajalt.mordant:mordant-jvm:3.0.1
com.github.ajalt.mordant:mordant:3.0.1
com.github.pjfanning:jersey-json:1.22.0
com.google.code.findbugs:jsr305:3.0.2
com.google.code.gson:gson:2.15.0
com.google.errorprone:error_prone_annotations:2.50.0
com.google.protobuf:protobuf-java:4.31.1
com.google.re2j:re2j:1.1
com.ibm.icu:icu4j:79.1
com.jcraft:jsch:0.1.55
com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11
com.mysql:mysql-connector-j:9.6.0
com.nimbusds:nimbus-jose-jwt:10.9
com.oracle.database.jdbc:ojdbc11:23.26.3.0.0
com.sun.jersey:jersey-core:1.19.4
com.sun.jersey:jersey-server:1.19.4
com.sun.jersey:jersey-servlet:1.19.4
com.sun.xml.bind:jaxb-impl:2.2.3-1
com.typesafe:config:1.4.3
com.univocity:univocity-parsers:2.9.1
com.zaxxer:HikariCP:6.2.1
commons-beanutils:commons-beanutils:1.11.0
commons-cli:commons-cli:1.5.0
commons-codec:commons-codec:1.16.1
commons-collections:commons-collections:3.2.2
commons-io:commons-io:2.16.1
commons-logging:commons-logging:1.2
commons-net:commons-net:3.9.0
commons-pool:commons-pool:1.6
dnsjava:dnsjava:3.6.1
io.airlift:aircompressor:2.0.3
io.dropwizard.metrics:metrics-core:3.2.4
io.ktor:ktor-client-cio-jvm:3.0.3
io.ktor:ktor-client-cio:3.0.3
io.ktor:ktor-client-core-jvm:3.0.3
io.ktor:ktor-client-core:3.0.3
io.ktor:ktor-events-jvm:3.0.3
io.ktor:ktor-events:3.0.3
io.ktor:ktor-http-cio-jvm:3.0.3
io.ktor:ktor-http-cio:3.0.3
io.ktor:ktor-http-jvm:3.0.3
io.ktor:ktor-http:3.0.3
io.ktor:ktor-io-jvm:3.0.3
io.ktor:ktor-io:3.0.3
io.ktor:ktor-network-jvm:3.0.3
io.ktor:ktor-network-tls-jvm:3.0.3
io.ktor:ktor-network-tls:3.0.3
io.ktor:ktor-network:3.0.3
io.ktor:ktor-serialization-jvm:3.0.3
io.ktor:ktor-serialization:3.0.3
io.ktor:ktor-server-cio-jvm:3.0.3
io.ktor:ktor-server-cio:3.0.3
io.ktor:ktor-server-core-jvm:3.0.3
io.ktor:ktor-server-core:3.0.3
io.ktor:ktor-sse-jvm:3.0.3
io.ktor:ktor-sse:3.0.3
io.ktor:ktor-utils-jvm:3.0.3
io.ktor:ktor-utils:3.0.3
io.ktor:ktor-websocket-serialization-jvm:3.0.3
io.ktor:ktor-websocket-serialization:3.0.3
io.ktor:ktor-websockets-jvm:3.0.3
io.ktor:ktor-websockets:3.0.3
jakarta.activation:jakarta.activation-api:1.2.1
javax.activation:activation:1.1
javax.annotation:javax.annotation-api:1.3.2
javax.servlet.jsp:jsp-api:2.1
javax.ws.rs:jsr311-api:1.1.1
javax.xml.bind:jaxb-api:2.2.2
javax.xml.stream:stax-api:1.0-2
org.apache.commons:commons-compress:1.26.1
org.apache.commons:commons-configuration2:2.15.0
org.apache.commons:commons-lang3:3.20.0
org.apache.commons:commons-math3:3.6.1
org.apache.commons:commons-text:1.15.0
org.apache.hadoop.thirdparty:hadoop-shaded-guava:1.6.0
org.apache.hadoop.thirdparty:hadoop-shaded-protobuf_3_25:1.3.0
org.apache.hadoop:hadoop-annotations:3.4.1
org.apache.hadoop:hadoop-auth:3.4.1
org.apache.hadoop:hadoop-common:3.4.1
org.apache.hadoop:hadoop-mapreduce-client-core:3.4.1
org.apache.httpcomponents:httpclient:4.5.13
org.apache.httpcomponents:httpcore:4.4.13
org.apache.parquet:parquet-column:1.18.2
org.apache.parquet:parquet-common:1.18.2
org.apache.parquet:parquet-encoding:1.18.2
org.apache.parquet:parquet-format-structures:1.18.2
org.apache.parquet:parquet-hadoop:1.18.2
org.apache.parquet:parquet-jackson:1.18.2
org.codehaus.jettison:jettison:1.5.4
org.codehaus.woodstox:stax2-api:4.2.1
org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.23.1
org.flywaydb:flyway-core:13.1.0
org.flywaydb:flyway-database-postgresql:13.1.0
org.fusesource.jansi:jansi:2.4.1
org.jetbrains.kotlin:kotlin-reflect:2.1.21
org.jetbrains.kotlin:kotlin-stdlib:2.4.21
org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.9.0
org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.5.4
org.jetbrains.kotlinx:kotlinx-io-bytestring:0.5.4
org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.5.4
org.jetbrains.kotlinx:kotlinx-io-core:0.5.4
org.jetbrains.kotlinx:kotlinx-serialization-bom:1.7.3
org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3
org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3
org.jetbrains:annotations:23.0.0
org.locationtech.jts:jts-core:1.20.0
org.postgresql:postgresql:42.7.12
org.reactivestreams:reactive-streams:1.0.4
org.slf4j:slf4j-api:2.0.17
org.slf4j:slf4j-reload4j:1.7.36
org.snakeyaml:snakeyaml-engine:2.7
org.xerial:sqlite-jdbc:3.53.4.0
org.yaml:snakeyaml:2.5
software.amazon.awssdk:annotations:2.44.14
software.amazon.awssdk:arns:2.44.14
software.amazon.awssdk:auth:2.44.14
software.amazon.awssdk:aws-core:2.44.14
software.amazon.awssdk:aws-query-protocol:2.44.14
software.amazon.awssdk:aws-xml-protocol:2.44.14
software.amazon.awssdk:bom:2.44.14
software.amazon.awssdk:checksums-spi:2.44.14
software.amazon.awssdk:checksums:2.44.14
software.amazon.awssdk:crt-core:2.44.14
software.amazon.awssdk:endpoints-spi:2.44.14
software.amazon.awssdk:http-auth-aws-eventstream:2.44.14
software.amazon.awssdk:http-auth-aws:2.44.14
software.amazon.awssdk:http-auth-spi:2.44.14
software.amazon.awssdk:http-auth:2.44.14
software.amazon.awssdk:http-client-spi:2.44.14
software.amazon.awssdk:identity-spi:2.44.14
software.amazon.awssdk:json-utils:2.44.14
software.amazon.awssdk:metrics-spi:2.44.14
software.amazon.awssdk:profiles:2.44.14
software.amazon.awssdk:protocol-core:2.44.14
software.amazon.awssdk:regions:2.44.14
software.amazon.awssdk:retries-spi:2.44.14
software.amazon.awssdk:retries:2.44.14
software.amazon.awssdk:s3:2.44.14
software.amazon.awssdk:sdk-core:2.44.14
software.amazon.awssdk:third-party-jackson-core:2.44.14
software.amazon.awssdk:url-connection-client:2.44.14
software.amazon.awssdk:utils-lite:2.44.14
software.amazon.awssdk:utils:2.44.14
software.amazon.eventstream:eventstream:1.0.1
tools.jackson.core:jackson-core:3.1.7
tools.jackson.core:jackson-databind:3.1.7
tools.jackson:jackson-bom:3.1.7
```
