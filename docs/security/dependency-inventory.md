# Dependency-Inventar (Runtime-SBOM des CLI-Artefakts)

> **Zweck:** Audit-Readiness ([ADR 0039](../adr/0039-externer-security-audit-kein-1.0.0-gate.md),
> Ticket [`audit-readiness-package.md`](../planning/open/audit-readiness-package.md)).
> Der GitHub-Dependency-Graph sieht nur ~6 Pakete, weil die meisten Gradle-Deps ihre
> Version dynamisch aus `gradle.properties` interpolieren (statisches Parsen blind).
> Dieses Inventar ist die **aufgelöste** Wahrheit für das ausgelieferte Artefakt.

**Scope:** `runtimeClasspath` des Moduls `:adapters:driving:cli` — die Dependencies, die
im ausgelieferten CLI-Shadow-Jar / OCI-Image landen (inkl. des mitgebündelten
MCP-Servers). Erststellige `project :…`-Module sind ausgeschlossen (das sind die 28
d-migrate-eigenen Module, s. `settings.gradle.kts`).

**Stand:** 2026-07-19 · **Artefakte:** 239 (aufgelöste `group:name:version`).

## Regenerieren

```
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driving:cli:dependencies --configuration runtimeClasspath" \
  -t d-migrate:deps-list .
```

Die aufgelösten Koordinaten aus dem Build-Log extrahieren (bei `X -> Y` die aufgelöste
Version `Y` nehmen, `project :…` ausschließen). Der CI-Workflow
`.github/workflows/dependency-submission.yml` erzeugt zusätzlich ab jedem `main`-Push
ein vollständiges SBOM für den GitHub-Dependency-Graph (nutzt den Gradle-Wrapper, sieht
also dieselben aufgelösten Versionen).

## Sicherheits-relevante Kern-Dependencies (Schnellzugriff)

| Fläche | Artefakt(e) | Version |
| ------ | ----------- | ------- |
| JDBC-Treiber PostgreSQL | `org.postgresql:postgresql` | 42.7.10 |
| JDBC-Treiber MySQL | `com.mysql:mysql-connector-j` | 9.6.0 |
| JDBC-Treiber SQLite (native lib) | `org.xerial:sqlite-jdbc` | 3.51.3.0 |
| Connection-Pool | `com.zaxxer:HikariCP` | 6.2.1 |
| MCP-Auth (JWT/JWKS-Validierung) | `com.nimbusds:nimbus-jose-jwt` | 10.9 |
| MCP-HTTP-Transport (Netz) | `io.ktor:ktor-server-cio`, `io.ktor:ktor-network-tls` | 3.0.3 |
| MCP-JSON-RPC-Parsing | `com.google.code.gson:gson` | 2.14.0 (≥2.11 → strukturelles Nesting-Limit; s. `JsonNestingGuard`) |
| JSON/YAML-Deserialisierung (read-side) | `com.fasterxml.jackson.core:jackson-databind` | 2.21.2 |
| Krypto (transitiv; Credential-Store nutzt JDK-`javax.crypto` AES-GCM) | `org.bouncycastle:bcprov-jdk18on` | 1.78.1 |
| Unicode-Normalisierung (Payload-Fingerprint) | `com.ibm.icu:icu4j` | 76.1 |
| Parquet-I/O | `org.apache.parquet:parquet-*`, `org.apache.hadoop:hadoop-common` | 1.17.1 / 3.4.1 |
| S3-Storage-Adapter | `software.amazon.awssdk:*` | 2.44.14 |
| Logging | `ch.qos.logback:logback-classic` | 1.5.15 |
| Migrations-Exporter | `org.flywaydb:flyway-database-postgresql` | 11.8.2 |

## Vollständige Liste

```
aopalliance:aopalliance:1.0
ch.qos.logback:logback-classic:1.5.15
ch.qos.logback:logback-core:1.5.15
ch.qos.reload4j:reload4j:1.2.22
com.dslplatform:dsl-json:1.10.0
com.dslplatform:dsl-json-java8:1.10.0
com.fasterxml.jackson.core:jackson-annotations:2.21
com.fasterxml.jackson.core:jackson-core:2.21.2
com.fasterxml.jackson.core:jackson-databind:2.21.2
com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.2
com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.2
com.fasterxml.jackson:jackson-bom:2.21.2
com.fasterxml.jackson.jaxrs:jackson-jaxrs-base:2.21.2
com.fasterxml.jackson.jaxrs:jackson-jaxrs-json-provider:2.21.2
com.fasterxml.jackson.module:jackson-module-jaxb-annotations:2.21.2
com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2
com.fasterxml.woodstox:woodstox-core:5.4.0
com.github.ajalt.clikt:clikt:5.0.3
com.github.ajalt.clikt:clikt-core:5.0.3
com.github.ajalt.clikt:clikt-core-jvm:5.0.3
com.github.ajalt.clikt:clikt-jvm:5.0.3
com.github.ajalt.colormath:colormath:3.6.0
com.github.ajalt.colormath:colormath-jvm:3.6.0
com.github.ajalt.mordant:mordant:3.0.1
com.github.ajalt.mordant:mordant-core:3.0.1
com.github.ajalt.mordant:mordant-core-jvm:3.0.1
com.github.ajalt.mordant:mordant-jvm:3.0.1
com.github.ajalt.mordant:mordant-jvm-ffm:3.0.1
com.github.ajalt.mordant:mordant-jvm-ffm-jvm:3.0.1
com.github.ajalt.mordant:mordant-jvm-graal-ffi:3.0.1
com.github.ajalt.mordant:mordant-jvm-graal-ffi-jvm:3.0.1
com.github.ajalt.mordant:mordant-jvm-jna:3.0.1
com.github.ajalt.mordant:mordant-jvm-jna-jvm:3.0.1
com.github.pjfanning:jersey-json:1.22.0
com.google.code.findbugs:jsr305:3.0.2
com.google.code.gson:gson:2.14.0
com.google.errorprone:error_prone_annotations:2.48.0
com.google.guava:failureaccess:1.0.1
com.google.guava:guava:27.1-jre
com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava
com.google.inject.extensions:guice-servlet:4.2.3
com.google.inject:guice:4.2.3
com.google.j2objc:j2objc-annotations:1.1
com.google.protobuf:protobuf-java:4.31.1
com.google.re2j:re2j:1.1
com.ibm.icu:icu4j:76.1
com.jcraft:jsch:0.1.55
commons-beanutils:commons-beanutils:1.9.4
commons-cli:commons-cli:1.5.0
commons-codec:commons-codec:1.16.1
commons-collections:commons-collections:3.2.2
commons-io:commons-io:2.16.1
commons-logging:commons-logging:1.3.0
commons-net:commons-net:3.9.0
commons-pool:commons-pool:1.6
com.mysql:mysql-connector-j:9.6.0
com.nimbusds:nimbus-jose-jwt:10.9
com.sun.jersey.contribs:jersey-guice:1.19.4
com.sun.jersey:jersey-client:1.19.4
com.sun.jersey:jersey-core:1.19.4
com.sun.jersey:jersey-server:1.19.4
com.sun.jersey:jersey-servlet:1.19.4
com.sun.xml.bind:jaxb-impl:2.2.3-1
com.typesafe:config:1.4.3
com.univocity:univocity-parsers:2.9.1
com.zaxxer:HikariCP:6.2.1
dnsjava:dnsjava:3.6.1
io.airlift:aircompressor:2.0.2
io.dropwizard.metrics:metrics-core:3.2.4
io.ktor:ktor-client-cio:3.0.3
io.ktor:ktor-client-cio-jvm:3.0.3
io.ktor:ktor-client-core:3.0.3
io.ktor:ktor-client-core-jvm:3.0.3
io.ktor:ktor-events:3.0.3
io.ktor:ktor-events-jvm:3.0.3
io.ktor:ktor-http:3.0.3
io.ktor:ktor-http-cio:3.0.3
io.ktor:ktor-http-cio-jvm:3.0.3
io.ktor:ktor-http-jvm:3.0.3
io.ktor:ktor-io:3.0.3
io.ktor:ktor-io-jvm:3.0.3
io.ktor:ktor-network:3.0.3
io.ktor:ktor-network-jvm:3.0.3
io.ktor:ktor-network-tls:3.0.3
io.ktor:ktor-network-tls-jvm:3.0.3
io.ktor:ktor-serialization:3.0.3
io.ktor:ktor-serialization-jvm:3.0.3
io.ktor:ktor-server-cio:3.0.3
io.ktor:ktor-server-cio-jvm:3.0.3
io.ktor:ktor-server-core:3.0.3
io.ktor:ktor-server-core-jvm:3.0.3
io.ktor:ktor-sse:3.0.3
io.ktor:ktor-sse-jvm:3.0.3
io.ktor:ktor-utils:3.0.3
io.ktor:ktor-utils-jvm:3.0.3
io.ktor:ktor-websockets:3.0.3
io.ktor:ktor-websocket-serialization:3.0.3
io.ktor:ktor-websocket-serialization-jvm:3.0.3
io.ktor:ktor-websockets-jvm:3.0.3
io.netty:netty-all:4.1.100.Final
io.netty:netty-buffer:4.1.100.Final
io.netty:netty-codec:4.1.100.Final
io.netty:netty-codec-dns:4.1.100.Final
io.netty:netty-codec-haproxy:4.1.100.Final
io.netty:netty-codec-http2:4.1.100.Final
io.netty:netty-codec-http:4.1.100.Final
io.netty:netty-codec-memcache:4.1.100.Final
io.netty:netty-codec-mqtt:4.1.100.Final
io.netty:netty-codec-redis:4.1.100.Final
io.netty:netty-codec-smtp:4.1.100.Final
io.netty:netty-codec-socks:4.1.100.Final
io.netty:netty-codec-stomp:4.1.100.Final
io.netty:netty-codec-xml:4.1.100.Final
io.netty:netty-common:4.1.100.Final
io.netty:netty-handler:4.1.100.Final
io.netty:netty-handler-proxy:4.1.100.Final
io.netty:netty-handler-ssl-ocsp:4.1.100.Final
io.netty:netty-resolver:4.1.100.Final
io.netty:netty-resolver-dns:4.1.100.Final
io.netty:netty-resolver-dns-classes-macos:4.1.100.Final
io.netty:netty-resolver-dns-native-macos:4.1.100.Final
io.netty:netty-transport:4.1.100.Final
io.netty:netty-transport-classes-epoll:4.1.100.Final
io.netty:netty-transport-classes-kqueue:4.1.100.Final
io.netty:netty-transport-native-epoll:4.1.100.Final
io.netty:netty-transport-native-kqueue:4.1.100.Final
io.netty:netty-transport-native-unix-common:4.1.100.Final
io.netty:netty-transport-rxtx:4.1.100.Final
io.netty:netty-transport-sctp:4.1.100.Final
io.netty:netty-transport-udt:4.1.100.Final
jakarta.activation:jakarta.activation-api:1.2.2
javax.annotation:javax.annotation-api:1.3.2
javax.inject:javax.inject:1
javax.servlet.jsp:jsp-api:2.1
javax.ws.rs:jsr311-api:1.1.1
javax.xml.bind:jaxb-api:2.2.12
net.java.dev.jna:jna:5.14.0
org.apache.commons:commons-compress:1.26.1
org.apache.commons:commons-configuration2:2.10.1
org.apache.commons:commons-lang3:3.14.0
org.apache.commons:commons-math3:3.6.1
org.apache.commons:commons-text:1.11.0
org.apache.curator:curator-client:5.2.0
org.apache.curator:curator-framework:5.2.0
org.apache.curator:curator-recipes:5.2.0
org.apache.hadoop:hadoop-annotations:3.4.1
org.apache.hadoop:hadoop-auth:3.4.1
org.apache.hadoop:hadoop-common:3.4.1
org.apache.hadoop:hadoop-hdfs-client:3.4.1
org.apache.hadoop:hadoop-mapreduce-client-core:3.4.1
org.apache.hadoop:hadoop-yarn-api:3.4.1
org.apache.hadoop:hadoop-yarn-client:3.4.1
org.apache.hadoop:hadoop-yarn-common:3.4.1
org.apache.hadoop.thirdparty:hadoop-shaded-guava:1.3.0
org.apache.hadoop.thirdparty:hadoop-shaded-protobuf_3_25:1.3.0
org.apache.httpcomponents:httpclient:4.5.13
org.apache.httpcomponents:httpcore:4.4.13
org.apache.kerby:kerb-core:2.0.3
org.apache.kerby:kerb-crypto:2.0.3
org.apache.kerby:kerb-util:2.0.3
org.apache.kerby:kerby-asn1:2.0.3
org.apache.kerby:kerby-config:2.0.3
org.apache.kerby:kerby-pkix:2.0.3
org.apache.kerby:kerby-util:2.0.3
org.apache.parquet:parquet-column:1.17.1
org.apache.parquet:parquet-common:1.17.1
org.apache.parquet:parquet-encoding:1.17.1
org.apache.parquet:parquet-format-structures:1.17.1
org.apache.parquet:parquet-hadoop:1.17.1
org.apache.parquet:parquet-jackson:1.17.1
org.apache.yetus:audience-annotations:0.12.0
org.apache.zookeeper:zookeeper:3.8.4
org.apache.zookeeper:zookeeper-jute:3.8.4
org.bouncycastle:bcprov-jdk18on:1.78.1
org.checkerframework:checker-qual:3.52.0
org.codehaus.jettison:jettison:1.5.4
org.codehaus.mojo:animal-sniffer-annotations:1.17
org.codehaus.woodstox:stax2-api:4.2.1
org.eclipse.jetty.websocket:websocket-api:9.4.53.v20231009
org.eclipse.jetty.websocket:websocket-client:9.4.53.v20231009
org.eclipse.jetty.websocket:websocket-common:9.4.53.v20231009
org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.23.1
org.flywaydb:flyway-core:11.8.2
org.flywaydb:flyway-database-postgresql:11.8.2
org.fusesource.jansi:jansi:2.4.1
org.jetbrains:annotations:23.0.0
org.jetbrains.kotlin:kotlin-reflect:2.1.21
org.jetbrains.kotlin:kotlin-stdlib:2.1.21
org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.9.0
org.jetbrains.kotlinx:kotlinx-io-bytestring:0.5.4
org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.5.4
org.jetbrains.kotlinx:kotlinx-io-core:0.5.4
org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.5.4
org.jetbrains.kotlinx:kotlinx-serialization-bom:1.7.3
org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3
org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3
org.jline:jline:3.9.0
org.locationtech.jts:jts-core:1.20.0
org.postgresql:postgresql:42.7.10
org.reactivestreams:reactive-streams:1.0.4
org.slf4j:slf4j-api:2.0.16
org.slf4j:slf4j-reload4j:1.7.36
org.snakeyaml:snakeyaml-engine:2.7
org.xerial:sqlite-jdbc:3.51.3.0
org.yaml:snakeyaml:2.5
software.amazon.awssdk:annotations:2.44.14
software.amazon.awssdk:arns:2.44.14
software.amazon.awssdk:auth:2.44.14
software.amazon.awssdk:aws-core:2.44.14
software.amazon.awssdk:aws-query-protocol:2.44.14
software.amazon.awssdk:aws-xml-protocol:2.44.14
software.amazon.awssdk:bom:2.44.14
software.amazon.awssdk:checksums:2.44.14
software.amazon.awssdk:checksums-spi:2.44.14
software.amazon.awssdk:crt-core:2.44.14
software.amazon.awssdk:endpoints-spi:2.44.14
software.amazon.awssdk:http-auth:2.44.14
software.amazon.awssdk:http-auth-aws:2.44.14
software.amazon.awssdk:http-auth-aws-eventstream:2.44.14
software.amazon.awssdk:http-auth-spi:2.44.14
software.amazon.awssdk:http-client-spi:2.44.14
software.amazon.awssdk:identity-spi:2.44.14
software.amazon.awssdk:json-utils:2.44.14
software.amazon.awssdk:metrics-spi:2.44.14
software.amazon.awssdk:profiles:2.44.14
software.amazon.awssdk:protocol-core:2.44.14
software.amazon.awssdk:regions:2.44.14
software.amazon.awssdk:retries:2.44.14
software.amazon.awssdk:retries-spi:2.44.14
software.amazon.awssdk:s3:2.44.14
software.amazon.awssdk:sdk-core:2.44.14
software.amazon.awssdk:third-party-jackson-core:2.44.14
software.amazon.awssdk:url-connection-client:2.44.14
software.amazon.awssdk:utils:2.44.14
software.amazon.awssdk:utils-lite:2.44.14
software.amazon.eventstream:eventstream:1.0.1
```
