// Oracle-Treibermodul (fünfter Dialekt, ADR 0052). Slice 1 liefert die
// Reverse-Read-Fläche (docs/planning/in-progress/oracle-dialect-scoping.md);
// die Schreib-/Generate-Ports folgen in späteren Slices.
//
// ojdbc11 steht unter den Oracle Free Use Terms and Conditions (FUTC), nicht
// MIT -- Weiterverbreitung des unmodifizierten Treibers ist erlaubt, verlangt
// aber die Lizenztext-Mitfuehrung (siehe docs/user/quality.md).

dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation("com.oracle.database.jdbc:ojdbc11:${rootProject.properties["oracleJdbcVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                // Thin wrapper with no testable logic (mirror of MssqlDriver/MysqlDriver):
                classes("dev.dmigrate.driver.oracle.OracleDriver")
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
