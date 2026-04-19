plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:core"))
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Interfaces (including $DefaultImpls inner classes)
                    "dev.dmigrate.driver.connection.ConnectionPool",
                    "dev.dmigrate.driver.connection.JdbcUrlBuilder",
                    "dev.dmigrate.driver.connection.JdbcUrlBuilder\$DefaultImpls",
                    "dev.dmigrate.driver.TypeMapper",
                    "dev.dmigrate.format.SchemaCodec",
                    "dev.dmigrate.format.SchemaCodec\$DefaultImpls",
                    // Pure data containers without logic
                    "dev.dmigrate.driver.connection.ConnectionConfig",
                    "dev.dmigrate.driver.connection.PoolSettings",
                    "dev.dmigrate.driver.data.ResumeMarker",
                    "dev.dmigrate.driver.data.ResumeMarker\$Position",
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
