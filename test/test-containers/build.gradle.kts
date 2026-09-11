// Die Testcontainer der Integrationstests — eine Stelle je Dialekt.
//
// Traegt die Bilder aus `test:test-images` weiter, damit eine Spec nur eine
// Abhaengigkeit braucht.

dependencies {
    api(project(":test:test-images"))
    api("org.testcontainers:testcontainers-mssqlserver:${rootProject.properties["testcontainersVersion"]}")
}
