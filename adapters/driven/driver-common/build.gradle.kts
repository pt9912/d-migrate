plugins {
    `java-library`
}

// adapters:driven:driver-common — Shared adapter infrastructure for DB drivers.
// Abstract base classes, connection pooling, registries.

dependencies {
    api(project(":hexagon:ports"))
    api("com.zaxxer:HikariCP:${rootProject.properties["hikariVersion"]}")
    api("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

    testImplementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "dev.dmigrate.driver.DdlGenerator",
                    "dev.dmigrate.driver.TypeMapper",
                    "dev.dmigrate.driver.connection.PoolSettings",
                    // Phase C Schritt 12: pure Interfaces
                    "dev.dmigrate.driver.data.DataWriter",
                    "dev.dmigrate.driver.data.TableImportSession",
                    "dev.dmigrate.driver.data.SchemaSync",
                    // Schritt 13 entfernt diesen Exclude wieder,
                    // wenn SequenceAdjustment eigene Tests bekommt
                    "dev.dmigrate.driver.data.SequenceAdjustment",
                    "dev.dmigrate.driver.data.UnsupportedTriggerModeException",
                    // Phase C: pure data projections (no logic)
                    "dev.dmigrate.driver.metadata.TableRef",
                    "dev.dmigrate.driver.metadata.ColumnProjection",
                    "dev.dmigrate.driver.metadata.PrimaryKeyProjection",
                    "dev.dmigrate.driver.metadata.ForeignKeyProjection",
                    "dev.dmigrate.driver.metadata.IndexProjection",
                    "dev.dmigrate.driver.metadata.ConstraintProjection",
                )
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
