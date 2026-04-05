plugins {
    application
}

application {
    mainClass.set("dev.dmigrate.cli.MainKt")
}

dependencies {
    implementation(project(":d-migrate-core"))
    implementation(project(":d-migrate-formats"))
    implementation("com.github.ajalt.clikt:clikt:${rootProject.properties["cliktVersion"]}")
    implementation("ch.qos.logback:logback-classic:${rootProject.properties["logbackVersion"]}")
    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")
}

kover {
    reports {
        verify {
            rule {
                // TODO: Increase to 90% once CLI integration tests are added
                minBound(50)
            }
        }
    }
}
