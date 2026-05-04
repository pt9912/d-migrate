// adapters:driven:connection-config — Plan-D §8 / §10.10 adapter
// für den secret-freien Connection-Reference-Bootstrap. Lädt
// Connection-Refs aus Projekt-/Server-YAML und liefert
// ausschliesslich diskursive Metadaten (kein JDBC-URL, kein
// expandiertes Secret). Sowohl der CLI- als auch der MCP-Adapter
// rufen diesen Loader an, damit beide Adapter dieselbe Quelle der
// Wahrheit haben.
plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports-common"))
    implementation(project(":hexagon:core"))
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")

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
