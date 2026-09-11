// Die Container-Images der Integrationstests — eine Stelle je Dialekt.
//
// Haengt nur an Testcontainers: die Konstanten sind `DockerImageName`, nicht
// blosse Zeichenketten. Ein Name mit Digest laesst sich von Testcontainers
// nicht mehr gegen den erwarteten Dialekt pruefen, deshalb traegt jede Angabe
// ihr `asCompatibleSubstituteFor` gleich mit.

dependencies {
    api("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
}
