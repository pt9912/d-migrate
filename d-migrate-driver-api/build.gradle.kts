plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports"))
    api(project(":d-migrate-core"))
    // HikariCP wird über das public ConnectionPool-Interface verwendet —
    // konkrete Treiber sehen es transitiv via api(...).
    api("com.zaxxer:HikariCP:${rootProject.properties["hikariVersion"]}")
    api("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

    // Phase A: Lifecycle-Tests für HikariConnectionPool/Factory laufen gegen
    // eine SQLite :memory:-DB. SQLite-JDBC ist NICHT im production classpath,
    // nur für Tests in diesem Modul — die konkreten Treiber-Module sind
    // davon unabhängig.
    testImplementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")
}

// Coverage in diesem Modul:
// - 0.2.0 DDL-Layer (AbstractDdlGenerator, DatabaseDialect, NoteType, DdlResult,
//   DdlStatement, TransformationNote, SkippedObject) wird durch
//   AbstractDdlGeneratorTest, DatabaseDialectTest und DdlModelTest abgedeckt.
// - 0.3.0 Connection-Layer (ConnectionConfig, ConnectionUrlParser, LogScrubber,
//   HikariConnectionPool/Factory) wird durch ConnectionUrlParserTest,
//   LogScrubberTest und HikariConnectionPoolFactoryTest abgedeckt.
// - DdlGenerator und TypeMapper sind reine Interfaces ohne Default-
//   Implementierungen — kover misst dafür keine Lines, sie tauchen nur als
//   ausgeschlossene Stubs auf.
// - PoolSettings ist eine pure data class ohne nicht-generierte Logik.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "dev.dmigrate.driver.DdlGenerator",
                    "dev.dmigrate.driver.TypeMapper",
                    "dev.dmigrate.driver.connection.PoolSettings",
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
