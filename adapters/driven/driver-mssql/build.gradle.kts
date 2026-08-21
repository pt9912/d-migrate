// MSSQL-Treibermodul (vierter Dialekt, ADR 0047). Slice 0 legt nur das
// Modul-Skeleton mit der Treiberabhängigkeit an; die Port-Implementierungen
// folgen ab Slice 1 (docs/planning/in-progress/mssql-dialect-scoping.md).

dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation("com.microsoft.sqlserver:mssql-jdbc:${rootProject.properties["mssqlJdbcVersion"]}")
}
