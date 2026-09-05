// Oracle-Treibermodul (fünfter Dialekt, ADR 0052). Slice 0 legt nur das
// Modul-Skeleton mit der Treiberabhängigkeit an; die Port-Implementierungen
// folgen ab Slice 1 (docs/planning/in-progress/oracle-dialect-scoping.md).
//
// ojdbc11 steht unter den Oracle Free Use Terms and Conditions (FUTC), nicht
// MIT -- Weiterverbreitung des unmodifizierten Treibers ist erlaubt, verlangt
// aber die Lizenztext-Mitfuehrung (siehe docs/user/quality.md).

dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation("com.oracle.database.jdbc:ojdbc11:${rootProject.properties["oracleJdbcVersion"]}")
}
