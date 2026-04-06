plugins {
    `java-library`
}

dependencies {
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

// Coverage in diesem Modul fokussiert sich auf den 0.3.0-Connection-Layer.
// Die 0.2.0-DDL-Klassen (AbstractDdlGenerator, DdlGenerator, TypeMapper, etc.)
// werden cross-module über die konkreten Treiber-Module abgedeckt — Kover
// misst Coverage aber pro Modul, deshalb sind sie hier ausgenommen.
//
// 0.3.0 Phase A wird durch:
// - ConnectionUrlParserTest (alle Dialekte, Aliase, SQLite-Sonderformen, URL-Encoding)
// - LogScrubberTest (Passwort-Maskierung)
// - HikariConnectionPoolFactoryTest gegen SQLite :memory: (Lifecycle, F15-Defaults, F16 Round-Trip)
// abgedeckt — Schwelle 90% für die nicht-ausgeschlossenen Klassen.
kover {
    reports {
        filters {
            excludes {
                classes(
                    // 0.2.0 DDL-Layer — getestet in d-migrate-driver-{postgresql,mysql,sqlite}
                    "dev.dmigrate.driver.AbstractDdlGenerator",
                    "dev.dmigrate.driver.AbstractDdlGenerator\$*",
                    "dev.dmigrate.driver.DatabaseDialect",
                    "dev.dmigrate.driver.DatabaseDialect\$Companion",
                    "dev.dmigrate.driver.DdlGenerator",
                    "dev.dmigrate.driver.DdlGenerator\$DefaultImpls",
                    "dev.dmigrate.driver.TypeMapper",
                    "dev.dmigrate.driver.TypeMapper\$DefaultImpls",
                    "dev.dmigrate.driver.NoteType",
                    "dev.dmigrate.driver.DdlResult",
                    "dev.dmigrate.driver.DdlStatement",
                    "dev.dmigrate.driver.TransformationNote",
                    "dev.dmigrate.driver.SkippedObject",
                    // 0.3.0 — pure data classes ohne Logik
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
