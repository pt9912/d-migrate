dependencies {
    implementation(project(":d-migrate-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.properties["jacksonVersion"]}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${rootProject.properties["jacksonVersion"]}")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${rootProject.properties["jacksonVersion"]}")

    testImplementation(project(":d-migrate-driver-api"))
    testImplementation(project(":d-migrate-driver-postgresql"))
    testImplementation(project(":d-migrate-driver-mysql"))
    testImplementation(project(":d-migrate-driver-sqlite"))
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
