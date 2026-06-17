package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.ExtensionAvailabilityDeclaration
import dev.dmigrate.driver.ExtensionAvailabilityStatus
import dev.dmigrate.driver.ExtensionInstallPolicy
import dev.dmigrate.driver.ExtensionInstallPrivilegeStatus
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PostgresDiffSpatialTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        options: DdlGenerationOptions = DdlGenerationOptions(),
    ) = gen.generateUp(planner.plan(current, desired, diff), options)

    test("§C.1: PostgreSQL geometry CreateTable blocks when PostGIS availability is unknown") {
        val t = TableDefinition(
            columns = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
        )
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("places", t))))

        r.statements.shouldBeEmpty()
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.operationsSkipped.size shouldBe 1
        r.diagnostics.single { it.code == "EXTENSION_DEPENDENCY_UNKNOWN" }
            .message shouldContain "postgis"
        r.extensionDependencies.single().extension shouldBe "postgis"
        r.extensionDependencies.single().status shouldBe ExtensionAvailabilityStatus.UNKNOWN
    }

    test("§C.1: PostgreSQL geometry DDL renders only when PostGIS is verified") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "places",
                    columnsAdded = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
                ),
            ),
        )
        val r = planAndUp(
            diff,
            options = verifiedPostgisOptions(),
        )

        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "ADD COLUMN \"shape\" geometry"
        r.diagnostics.single { it.code == "EXTENSION_DEPENDENCY_VERIFIED" }
            .message shouldContain "postgis"
        r.extensionDependencies.single().status shouldBe ExtensionAvailabilityStatus.VERIFIED_PRESENT
    }

    test("§C.1: PostgreSQL geometry AddColumn blocks when PostGIS is declared missing") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "places",
                    columnsAdded = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
                ),
            ),
        )
        val r = planAndUp(
            diff,
            options = DdlGenerationOptions(
                extensionAvailability = listOf(
                    ExtensionAvailabilityDeclaration(
                        dialect = "postgresql",
                        extension = "postgis",
                        status = ExtensionAvailabilityStatus.MISSING,
                    ),
                ),
            ),
        )

        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "EXTENSION_DEPENDENCY_MISSING" }
            .message shouldContain "MISSING"
        r.extensionDependencies.single().status shouldBe ExtensionAvailabilityStatus.MISSING
    }

    test("§C.1: PostgreSQL can plan explicit PostGIS install before dependent DDL") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "places",
                    columnsAdded = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
                ),
            ),
        )
        val r = planAndUp(
            diff,
            options = DdlGenerationOptions(
                extensionInstallPolicy = ExtensionInstallPolicy.ALLOW_CREATE_IF_MISSING,
            ),
        )

        r.isBlocked shouldBe false
        r.requiresConfirmation shouldBe true
        r.statements.map { it.sql } shouldBe listOf(
            "CREATE EXTENSION IF NOT EXISTS \"postgis\";",
            "ALTER TABLE \"places\" ADD COLUMN \"shape\" geometry(Geometry, 0);",
        )
        r.diagnostics.single { it.code == "EXTENSION_INSTALL_PLANNED" }
            .message shouldContain "explicitly allowed"
        r.diagnostics.single { it.code == "EXTENSION_INSTALL_PRIVILEGE_UNVERIFIED" }
            .message shouldContain "privileges were not verified"
        r.extensionDependencies.single().installStatement shouldBe
            "CREATE EXTENSION IF NOT EXISTS \"postgis\";"
    }

    test("§C.1: PostgreSQL extension install blocks when CREATE EXTENSION privilege is missing") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "places",
                    columnsAdded = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
                ),
            ),
        )
        val r = planAndUp(
            diff,
            options = DdlGenerationOptions(
                extensionInstallPolicy = ExtensionInstallPolicy.ALLOW_CREATE_IF_MISSING,
                extensionInstallPrivilegeStatus = ExtensionInstallPrivilegeStatus.MISSING,
            ),
        )

        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "EXTENSION_INSTALL_PRIVILEGE_MISSING" }
            .message shouldContain "CREATE EXTENSION privileges are declared MISSING"
        r.extensionDependencies.single().installStatement shouldBe null
        r.extensionDependencies.single().status shouldBe ExtensionAvailabilityStatus.UNKNOWN
    }

    test("§C.1: extension blockers use distinct diagnostics for unknown, missing and missing privilege") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "places",
                    columnsAdded = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
                ),
            ),
        )

        val unknown = planAndUp(diff)
        val missing = planAndUp(
            diff,
            options = DdlGenerationOptions(
                extensionAvailability = listOf(
                    ExtensionAvailabilityDeclaration(
                        dialect = "postgresql",
                        extension = "postgis",
                        status = ExtensionAvailabilityStatus.MISSING,
                    ),
                ),
            ),
        )
        val missingPrivilege = planAndUp(
            diff,
            options = DdlGenerationOptions(
                extensionInstallPolicy = ExtensionInstallPolicy.ALLOW_CREATE_IF_MISSING,
                extensionInstallPrivilegeStatus = ExtensionInstallPrivilegeStatus.MISSING,
            ),
        )

        val codes = listOf(
            unknown.diagnostics.single { it.code == "EXTENSION_DEPENDENCY_UNKNOWN" }.code,
            missing.diagnostics.single { it.code == "EXTENSION_DEPENDENCY_MISSING" }.code,
            missingPrivilege.diagnostics.single { it.code == "EXTENSION_INSTALL_PRIVILEGE_MISSING" }.code,
        )
        codes.toSet().size shouldBe 3
    }

    test("GIST index on a text column is blocked — no operator class (I-08)") {
        val before = TableDefinition(columns = mapOf("body" to ColumnDefinition(NeutralType.Text())))
        val index = IndexDefinition(name = "idx_docs_body", columns = listOf(IndexColumn("body")), type = IndexType.GIST)
        val after = before.copy(indices = listOf(index))
        val current = emptySchema().copy(tables = mapOf("docs" to before))
        val desired = emptySchema().copy(tables = mapOf("docs" to after))
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(index))))
        val r = planAndUp(diff, current = current, desired = desired)

        r.isBlocked shouldBe true
        r.statements.none { it.sql.contains("USING GIST") } shouldBe true
        r.diagnostics.any { it.code == "INDEX_OPCLASS_MISSING" } shouldBe true
    }

    test("§C.2: PostgreSQL GIST index on geometry column renders with verified PostGIS") {
        val before = TableDefinition(
            columns = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
        )
        val index = IndexDefinition(name = "idx_places_shape", columns = listOf(IndexColumn("shape")), type = IndexType.GIST)
        val after = before.copy(indices = listOf(index))
        val current = emptySchema().copy(tables = mapOf("places" to before))
        val desired = emptySchema().copy(tables = mapOf("places" to after))
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "places", indicesAdded = listOf(index))))
        val r = planAndUp(diff, current = current, desired = desired, options = verifiedPostgisOptions())

        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "CREATE INDEX \"idx_places_shape\" ON \"places\" USING GIST"
        r.extensionDependencies.single().status shouldBe ExtensionAvailabilityStatus.VERIFIED_PRESENT
    }

    test("§C.2: PostgreSQL non-GIST index on geometry column blocks") {
        val before = TableDefinition(
            columns = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
        )
        val index = IndexDefinition(name = "idx_places_shape", columns = listOf(IndexColumn("shape")), type = IndexType.BTREE)
        val after = before.copy(indices = listOf(index))
        val current = emptySchema().copy(tables = mapOf("places" to before))
        val desired = emptySchema().copy(tables = mapOf("places" to after))
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "places", indicesAdded = listOf(index))))
        val r = planAndUp(diff, current = current, desired = desired)

        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "SPATIAL_INDEX_UNSUPPORTED" }
            .message shouldContain "BTREE"
    }
})

private fun verifiedPostgisOptions(): DdlGenerationOptions =
    DdlGenerationOptions(
        extensionAvailability = listOf(
            ExtensionAvailabilityDeclaration(
                dialect = "postgresql",
                extension = "postgis",
                status = ExtensionAvailabilityStatus.VERIFIED_PRESENT,
            ),
        ),
    )
