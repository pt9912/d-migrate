package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.ExtensionAvailabilityDeclaration
import dev.dmigrate.driver.ExtensionAvailabilityStatus
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * P7 — die Spatial-Metadaten-Regeln auf dem **Migrate**-Pfad.
 *
 * Fuer den Block `SPATIAL_METADATA_UNSUPPORTED` gab es bis hierher keinen
 * einzigen Test; die Regel stand nur im Generate-Pfad unter Zusicherung. Diese
 * Spec haelt beide Seiten fest:
 *
 * - **`CreateTable` sagt dasselbe wie `schema generate`**: `required` geht
 *   nativ ueber das sechste Argument von `AddGeometryColumn`, `unique`,
 *   `default`, ein Fremdschluessel, der Primaerschluessel und eine
 *   tabellenweite Einschraenkung blocken weiter.
 * - **`ADD COLUMN` blockt `required` weiter** — die Tabelle steht schon, und
 *   SpatiaLite fuellt Bestandszeilen mit einem Wert, der keine gueltige
 *   Geometrie ist (gemessen, s. Plan).
 * - **Der Tabellen-Neubau blockt** jede Operation an einer Geometriespalte:
 *   er schriebe sie inline und damit ausserhalb der SpatiaLite-Registrierung.
 */
class SqliteDiffSpatialMetadataTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun options() = DdlGenerationOptions(
        spatialProfile = SpatialProfile.SPATIALITE,
        extensionAvailability = listOf(
            ExtensionAvailabilityDeclaration(
                dialect = "sqlite",
                extension = "spatialite",
                status = ExtensionAvailabilityStatus.VERIFIED_PRESENT,
            ),
        ),
    )

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(diff: SchemaDiff) =
        gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), options())

    fun geometryColumn(required: Boolean = false, unique: Boolean = false, default: DefaultValue? = null) =
        ColumnDefinition(
            type = NeutralType.Geometry(GeometryType("point"), srid = 4326),
            required = required,
            unique = unique,
            default = default,
        )

    fun table(
        geometry: ColumnDefinition,
        primaryKey: List<String> = listOf("id"),
        constraints: List<ConstraintDefinition> = emptyList(),
    ) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer, ordinal = 1),
            "loc" to geometry.copy(ordinal = 2),
        ),
        primaryKey = primaryKey,
        constraints = constraints,
    )

    // ── CreateTable ───────────────────────────────

    test("CreateTable: eine required-Geometriespalte bekommt das sechste Argument, ohne Block") {
        val r = planAndUp(
            SchemaDiff(tablesAdded = listOf(NamedTable("places", table(geometryColumn(required = true))))),
        )

        withClue(r.diagnostics.map { it.code to it.message }.toString()) { r.isBlocked shouldBe false }
        r.statements.map { it.sql }.first { it.contains("AddGeometryColumn") } shouldContainStr
            "AddGeometryColumn('places', 'loc', 4326, 'POINT', 'XY', 1)"
    }

    test("CreateTable: eine nullbare Geometriespalte behaelt die fuenfstellige Form") {
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("places", table(geometryColumn())))))

        r.isBlocked shouldBe false
        r.statements.map { it.sql }.first { it.contains("AddGeometryColumn") } shouldBe
            "SELECT AddGeometryColumn('places', 'loc', 4326, 'POINT', 'XY');"
    }

    test("CreateTable: unique blockt weiter") {
        val r = planAndUp(
            SchemaDiff(tablesAdded = listOf(NamedTable("places", table(geometryColumn(unique = true))))),
        )

        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "SPATIAL_METADATA_UNSUPPORTED" }.message shouldContainStr "UNIQUE"
    }

    test("CreateTable: ein DEFAULT blockt weiter") {
        val r = planAndUp(
            SchemaDiff(
                tablesAdded = listOf(
                    NamedTable("places", table(geometryColumn(default = DefaultValue.StringLiteral("x")))),
                ),
            ),
        )

        r.isBlocked shouldBe true
        r.diagnostics.single { it.code == "SPATIAL_METADATA_UNSUPPORTED" }.message shouldContainStr "DEFAULT"
    }

    test("CreateTable: die Geometriespalte im Primaerschluessel blockt weiter") {
        val r = planAndUp(
            SchemaDiff(
                tablesAdded = listOf(
                    NamedTable("places", table(geometryColumn(), primaryKey = listOf("loc"))),
                ),
            ),
        )

        r.isBlocked shouldBe true
        r.diagnostics.single { it.code == "SPATIAL_METADATA_UNSUPPORTED" }.message shouldContainStr "primary key"
    }

    test("CreateTable: eine tabellenweite Einschraenkung auf der Geometriespalte blockt weiter") {
        val r = planAndUp(
            SchemaDiff(
                tablesAdded = listOf(
                    NamedTable(
                        "places",
                        table(
                            geometryColumn(required = true),
                            constraints = listOf(
                                ConstraintDefinition(
                                    name = "uq_loc",
                                    type = ConstraintType.UNIQUE,
                                    columns = listOf("loc"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        r.isBlocked shouldBe true
        r.diagnostics.single { it.code == "SPATIAL_METADATA_UNSUPPORTED" }.message shouldContainStr
            "table-level constraint"
    }

    // ── AddColumn ─────────────────────────────────

    test("AddColumn: eine required-Geometriespalte bleibt blockiert") {
        val r = planAndUp(
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(name = "places", columnsAdded = mapOf("loc" to geometryColumn(required = true))),
                ),
            ),
        )

        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        val message = r.diagnostics.single { it.code == "SPATIAL_METADATA_UNSUPPORTED" }.message
        message shouldContainStr "NOT NULL"
        // Der Grund gehoert in die Meldung: SpatiaLite fuellt Bestandszeilen
        // mit einem Wert, den dieselbe Tabelle beim Einfuegen abwiese.
        message shouldContainStr "existing rows"
    }

    test("AddColumn: eine nullbare Geometriespalte entsteht weiter") {
        val r = planAndUp(
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(name = "places", columnsAdded = mapOf("loc" to geometryColumn())),
                ),
            ),
        )

        r.isBlocked shouldBe false
        r.statements.map { it.sql }.first { it.contains("AddGeometryColumn") } shouldBe
            "SELECT AddGeometryColumn('places', 'loc', 4326, 'POINT', 'XY');"
    }

    // ── Tabellen-Neubau (M11) ─────────────────────

    test("Rebuild: eine Nullbarkeitsaenderung an einer anderen Spalte blockt, solange die Tabelle Geometrie fuehrt") {
        fun schema(noteRequired: Boolean) = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf(
                "places" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, ordinal = 1),
                        "loc" to geometryColumn().copy(ordinal = 2),
                        "note" to ColumnDefinition(NeutralType.Text(), required = noteRequired, ordinal = 3),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val current = schema(noteRequired = false)
        val desired = schema(noteRequired = true)
        val diff = SchemaComparator().compare(current, desired)

        val r = gen.generateUp(planner.plan(current, desired, diff), options())

        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        val message = r.diagnostics.single { it.code == "SPATIAL_METADATA_UNSUPPORTED" }.message
        message shouldContainStr "loc"
        message shouldContainStr "geometry_columns"
        // Kein Neubau-DDL: die Folge liefe sonst durch und die Spalte waere
        // hinterher keine registrierte Geometrie mehr.
        r.statements.none { it.sql.contains("CREATE TABLE") } shouldBe true
    }

    test("Rebuild: dieselbe Aenderung ohne Geometriespalte laeuft unveraendert durch") {
        fun schema(noteRequired: Boolean) = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf(
                "places" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, ordinal = 1),
                        "note" to ColumnDefinition(NeutralType.Text(), required = noteRequired, ordinal = 2),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val current = schema(noteRequired = false)
        val desired = schema(noteRequired = true)
        val diff = SchemaComparator().compare(current, desired)

        val r = gen.generateUp(planner.plan(current, desired, diff), options())

        r.isBlocked shouldBe false
        r.statements.any { it.sql.contains("CREATE TABLE") } shouldBe true
    }
})
