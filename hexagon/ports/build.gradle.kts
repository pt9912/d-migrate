plugins {
    `java-library`
}

// hexagon:ports — Pure port interfaces and associated data types.
// No implementation classes, no external libraries (only JDK java.sql).

dependencies {
    api(project(":d-migrate-core"))
}
