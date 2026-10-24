package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.CustomTypeDiff
import dev.dmigrate.core.diff.NamedCustomType
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Benannte Typen im Diff-Pfad von SQLite (Issue #48): eine ENUM lebt dort an
 * den Spalten als `TEXT` + `CHECK`, es gibt kein Typ-Objekt zu erzeugen.
 */
class SqliteDiffCustomTypeOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun enumType(vararg values: String) = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = values.toList())

    fun schema(
        types: Map<String, CustomTypeDefinition>,
        extra: Boolean = false,
        usesType: Boolean = true,
    ) = SchemaDefinition(
        name = "App", version = "1", customTypes = types,
        tables = mapOf(
            "t" to TableDefinition(
                columns = buildMap {
                    put("id", ColumnDefinition(NeutralType.Integer, required = true))
                    if (usesType) {
                        put("e", ColumnDefinition(NeutralType.Enum(refType = "ee"), required = true))
                    }
                    if (extra) put("extra", ColumnDefinition(NeutralType.Text()))
                },
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun valuesChanged(before: CustomTypeDefinition, after: CustomTypeDefinition) = SchemaDiff(
        customTypesChanged = listOf(
            CustomTypeDiff(name = "ee", values = ValueChange(before.values.orEmpty(), after.values.orEmpty())),
        ),
    )

    test("CreateCustomType einer ENUM bucht die Operation ohne Anweisung und ohne Blocker") {
        val diff = SchemaDiff(customTypesAdded = listOf(NamedCustomType("ee", enumType("a", "b"))))
        val plan = planner.plan(schema(emptyMap(), usesType = false), schema(mapOf("ee" to enumType("a", "b"))), diff)
        val r = gen.generateUp(plan, DdlGenerationOptions())

        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe null
        r.operationsRendered shouldBe plan.operations.map { it.id }.toSet()
        r.diagnostics.map { it.code } shouldContain "SQLITE_CUSTOM_TYPE_AT_COLUMN"
    }

    test("DropCustomType einer ENUM bucht die Operation ohne Anweisung und ohne Blocker") {
        val diff = SchemaDiff(customTypesRemoved = listOf(NamedCustomType("ee", enumType("a", "b"))))
        val plan = planner.plan(schema(mapOf("ee" to enumType("a", "b")), usesType = false), schema(emptyMap(), usesType = false), diff)
        val r = gen.generateUp(plan, DdlGenerationOptions())

        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe null
    }

    test("AlterCustomType baut jede nutzende Tabelle mit dem neuen CHECK neu auf") {
        val before = enumType("a", "b")
        val after = enumType("a", "b", "c")
        val plan = planner.plan(schema(mapOf("ee" to before)), schema(mapOf("ee" to after)), valuesChanged(before, after))
        val r = gen.generateUp(plan, DdlGenerationOptions())

        r.primaryBlockedReason shouldBe null
        val sql = r.statements.joinToString("\n") { it.sql }
        sql shouldContain "CREATE TABLE \"t__dmg_rebuild_"
        sql shouldContain "CHECK (\"e\" IN ('a', 'b', 'c'))"
        // Unbenannt, wie `schema generate` ihn schreibt: ein benannter CHECK kaeme
        // vom Reverse zurueck, und jeder Folgelauf meldete Drift.
        sql shouldNotContain "ck_t_e"
        sql shouldContain "INSERT INTO"
        r.operationsRendered shouldBe plan.operations.map { it.id }.toSet()
    }

    test("AlterCustomType ohne nutzende Spalte bucht die Operation ohne Neubau") {
        val before = enumType("a", "b")
        val after = enumType("a", "b", "c")
        val plan = planner.plan(
            schema(mapOf("ee" to before), usesType = false),
            schema(mapOf("ee" to after), usesType = false),
            valuesChanged(before, after),
        )
        val r = gen.generateUp(plan, DdlGenerationOptions())

        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe null
        r.diagnostics.map { it.code } shouldContain "SQLITE_CUSTOM_TYPE_NO_USERS"
    }

    test("AlterCustomType in DOWN-Richtung ist ROLLBACK_NOT_POSSIBLE und baut nichts") {
        val before = enumType("a", "b")
        val after = enumType("a", "b", "c")
        val plan = planner.plan(schema(mapOf("ee" to before)), schema(mapOf("ee" to after)), valuesChanged(before, after))
        val r = gen.generateDown(plan, DdlGenerationOptions())

        r.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        r.statements.shouldBeEmpty()
    }

    test("Ein Neubau aus anderem Anlass behaelt den CHECK einer ref_type-Spalte") {
        // Vorher schrieb der Neubau `TEXT` ohne CHECK und meldete nur W134.
        val types = mapOf("ee" to enumType("a", "b"))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                dev.dmigrate.core.diff.TableDiff(
                    name = "t",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.Integer, NeutralType.BigInteger),
                        ),
                    ),
                ),
            ),
        )
        val desired = schema(types).let { s ->
            s.copy(
                tables = s.tables.mapValues { (_, t) ->
                    t.copy(columns = t.columns + ("id" to ColumnDefinition(NeutralType.BigInteger, required = true)))
                },
            )
        }
        val r = gen.generateUp(planner.plan(schema(types), desired, diff), DdlGenerationOptions())

        r.primaryBlockedReason shouldBe null
        r.statements.joinToString("\n") { it.sql } shouldContain "CHECK (\"e\" IN ('a', 'b'))"
        r.diagnostics.map { it.code }.contains("W134") shouldBe false
        r.statements.size shouldNotBe 0
    }

    fun domain(check: String, base: String = "integer") =
        CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base, check = check)

    fun domainSchema(types: Map<String, CustomTypeDefinition>) = SchemaDefinition(
        name = "App", version = "1", customTypes = types,
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "n" to ColumnDefinition(NeutralType.Enum(refType = "pos"), required = true),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    test("CreateCustomType einer DOMAIN bucht die Operation ohne Anweisung und ohne Blocker") {
        val pos = domain("VALUE >= 0")
        val diff = SchemaDiff(customTypesAdded = listOf(NamedCustomType("pos", pos)))
        val plan = planner.plan(SchemaDefinition(name = "App", version = "1"), domainSchema(mapOf("pos" to pos)), diff)
        // Die Tabelle gibt es im Ist nicht: nur der Typ-Teil ist hier die Frage.
        val r = gen.generateUp(plan, DdlGenerationOptions())

        r.operationsRendered.containsAll(
            plan.operations.filterIsInstance<dev.dmigrate.core.diff.migration.DiffOperation.CreateCustomType>().map { it.id },
        ) shouldBe true
        r.diagnostics.map { it.code } shouldContain "SQLITE_CUSTOM_TYPE_AT_COLUMN"
    }

    test("AlterCustomType einer DOMAIN baut die nutzende Tabelle mit neuem CHECK neu auf") {
        val before = domain("VALUE >= 0")
        val after = domain("VALUE >= 10")
        val diff = SchemaDiff(
            customTypesChanged = listOf(
                CustomTypeDiff(name = "pos", check = ValueChange("VALUE >= 0", "VALUE >= 10")),
            ),
        )
        val plan = planner.plan(domainSchema(mapOf("pos" to before)), domainSchema(mapOf("pos" to after)), diff)
        val r = gen.generateUp(plan, DdlGenerationOptions())

        r.primaryBlockedReason shouldBe null
        val sql = r.statements.joinToString("\n") { it.sql }
        sql shouldContain "\"n\" INTEGER NOT NULL CHECK (\"n\" >= 10)"
        sql shouldNotContain "ck_t_n"
    }

    test("AlterCustomType einer DOMAIN mit neuem Basistyp wandelt die Spalte um") {
        val before = domain("VALUE >= 0", base = "integer")
        val after = domain("VALUE >= 0", base = "bigint")
        val diff = SchemaDiff(
            customTypesChanged = listOf(CustomTypeDiff(name = "pos", baseType = ValueChange("integer", "bigint"))),
        )
        val plan = planner.plan(domainSchema(mapOf("pos" to before)), domainSchema(mapOf("pos" to after)), diff)
        val r = gen.generateUp(plan, DdlGenerationOptions())

        r.primaryBlockedReason shouldBe null
        r.statements.joinToString("\n") { it.sql } shouldContain "CHECK (\"n\" >= 0)"
    }
})
