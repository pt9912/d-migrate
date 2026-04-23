package dev.dmigrate.driver

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests for AbstractDdlGenerator's shared logic — topological sort, statement
 * inversion (rollback), the generate() template-method orchestration,
 * generateHeader, columnSql and referentialActionSql.
 *
 * Uses a [TestDdlGenerator] test double instead of a mocking framework: the
 * abstract methods are implemented to return small, deterministic statements
 * tagged with the input table/object name, so we can assert on call order and
 * generated content without needing a real driver.
 */
class AbstractDdlGeneratorTestPart2 : FunSpec({

    // ─── Topological sort ────────────────────────────────────────


    test("generate() tags views without function deps as PRE_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            views = mapOf("v1" to ViewDefinition(query = "SELECT 1")),
        ))
        result.statementsForPhase(DdlPhase.PRE_DATA)
            .any { it.sql.contains("CREATE VIEW") } shouldBe true
        result.statementsForPhase(DdlPhase.POST_DATA)
            .none { it.sql.contains("CREATE VIEW") } shouldBe true
    }

    test("generate() tags views with explicit dependencies.functions as POST_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            functions = mapOf("calc" to FunctionDefinition()),
            views = mapOf("v1" to ViewDefinition(
                query = "SELECT calc(id) FROM t",
                dependencies = DependencyInfo(functions = listOf("calc")),
            )),
        ))
        result.statementsForPhase(DdlPhase.POST_DATA)
            .any { it.sql.contains("CREATE VIEW") } shouldBe true
        result.statementsForPhase(DdlPhase.PRE_DATA)
            .none { it.sql.contains("CREATE VIEW") } shouldBe true
    }

    test("generate() heuristic detects function call in view query") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            functions = mapOf("calc_total" to FunctionDefinition()),
            views = mapOf("v1" to ViewDefinition(
                query = "SELECT calc_total(id) FROM orders",
            )),
        ))
        result.statementsForPhase(DdlPhase.POST_DATA)
            .any { it.sql.contains("CREATE VIEW") } shouldBe true
    }

    test("generate() heuristic ignores function name in string literal") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            functions = mapOf("calc" to FunctionDefinition()),
            views = mapOf("v1" to ViewDefinition(
                query = "SELECT 'calc(x)' AS label FROM t",
            )),
        ))
        result.statementsForPhase(DdlPhase.PRE_DATA)
            .any { it.sql.contains("CREATE VIEW") } shouldBe true
    }

    test("generate() heuristic ignores function name in comment") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            functions = mapOf("calc" to FunctionDefinition()),
            views = mapOf("v1" to ViewDefinition(
                query = "SELECT id FROM t -- calc(x)",
            )),
        ))
        result.statementsForPhase(DdlPhase.PRE_DATA)
            .any { it.sql.contains("CREATE VIEW") } shouldBe true
    }

    test("generate() transitive view→view propagation to POST_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            functions = mapOf("fn" to FunctionDefinition()),
            views = mapOf(
                "base" to ViewDefinition(
                    query = "SELECT fn(id) FROM t",
                    dependencies = DependencyInfo(functions = listOf("fn")),
                ),
                "derived" to ViewDefinition(
                    query = "SELECT * FROM base",
                    dependencies = DependencyInfo(views = listOf("base")),
                ),
            ),
        ))
        // Both views should be POST_DATA
        result.statementsForPhase(DdlPhase.POST_DATA)
            .filter { it.sql.contains("CREATE VIEW") }.size shouldBe 2
    }

    test("generate() view without query and with functions in schema gets E060 diagnostic") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            functions = mapOf("fn" to FunctionDefinition()),
            views = mapOf("v_no_query" to ViewDefinition(query = null)),
        ))
        result.globalNotes.any { it.code == "E060" && it.objectName == "v_no_query" } shouldBe true
        // Conservatively placed in POST_DATA
        result.statementsForPhase(DdlPhase.POST_DATA)
            .any { it.sql.contains("v_no_query") } shouldBe true
    }

    test("generate() view without query but with explicit empty deps stays PRE_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            functions = mapOf("fn" to FunctionDefinition()),
            views = mapOf("v_explicit" to ViewDefinition(
                query = null,
                dependencies = DependencyInfo(), // author explicitly declared no deps
            )),
        ))
        result.globalNotes.none { it.code == "E060" } shouldBe true
        result.statementsForPhase(DdlPhase.PRE_DATA)
            .any { it.sql.contains("v_explicit") } shouldBe true
    }

    test("generate() no functions in schema means all views stay PRE_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            views = mapOf(
                "v1" to ViewDefinition(query = "SELECT 1"),
                "v2" to ViewDefinition(query = null),
            ),
        ))
        result.statementsForPhase(DdlPhase.PRE_DATA)
            .filter { it.sql.contains("CREATE VIEW") }.size shouldBe 2
        result.globalNotes.none { it.code == "E060" } shouldBe true
    }

    test("generate() tags header as PRE_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema("t" to table()))
        result.statementsForPhase(DdlPhase.PRE_DATA)
            .any { it.sql.contains("Generated by d-migrate") } shouldBe true
    }

    test("blocked table skip has phase PRE_DATA") {
        val gen = TestDdlGenerator(blockedTable = "t")
        val result = gen.generate(schema("t" to table()))
        result.skippedObjects.any { it.name == "t" && it.phase == DdlPhase.PRE_DATA } shouldBe true
    }

    test("generate() blocks geometry tables when spatial profile is NONE") {
        val gen = TestDdlGenerator()
        val result = gen.generate(
            schema(
                "geo" to TableDefinition(
                    columns = mapOf("shape" to ColumnDefinition(type = NeutralType.Geometry())),
                ),
            ),
            DdlGenerationOptions(spatialProfile = SpatialProfile.NONE),
        )

        result.notes.any { it.code == "E052" && it.objectName == "geo" } shouldBe true
        result.skippedObjects shouldContainExactly listOf(
            SkippedObject(
                type = "table",
                name = "geo",
                reason = "Table 'geo' skipped: contains geometry columns incompatible with spatial profile 'none'",
                code = "E052",
                hint = "Use --spatial-profile to enable spatial DDL generation for this dialect",
                phase = DdlPhase.PRE_DATA,
            ),
        )
        result.statements.none { it.sql.contains("CREATE TABLE \"geo\"") } shouldBe true
        gen.callOrder shouldContainExactly listOf(
            "customTypes", "sequences",
            "circular", "views", "views", "functions", "procedures", "triggers",
        )
    }

    test("generate() allows geometry tables when spatial profile supports them") {
        val gen = TestDdlGenerator()
        val result = gen.generate(
            schema(
                "geo" to TableDefinition(
                    columns = mapOf("shape" to ColumnDefinition(type = NeutralType.Geometry())),
                ),
            ),
            DdlGenerationOptions(spatialProfile = SpatialProfile.POSTGIS),
        )

        result.notes.none { it.code == "E052" } shouldBe true
        result.skippedObjects.shouldBeEmpty()
        result.statements.any { it.sql.contains("CREATE TABLE \"geo\"") } shouldBe true
        gen.callOrder shouldContainExactly listOf(
            "customTypes", "sequences",
            "table:geo", "indices:geo",
            "circular", "views", "views", "functions", "procedures", "triggers",
        )
    }

    test("full scenario: mixed objects land in correct phases") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            "orders" to table(),
            functions = mapOf("calc_total" to FunctionDefinition()),
            views = mapOf(
                "simple_view" to ViewDefinition(query = "SELECT * FROM orders"),
                "computed_view" to ViewDefinition(
                    query = "SELECT calc_total(id) FROM orders",
                    dependencies = DependencyInfo(functions = listOf("calc_total")),
                ),
                "dependent_view" to ViewDefinition(
                    query = "SELECT * FROM computed_view",
                    dependencies = DependencyInfo(views = listOf("computed_view")),
                ),
                "heuristic_view" to ViewDefinition(
                    query = "SELECT id, calc_total(id) FROM orders",
                ),
            ),
            triggers = mapOf("trg_audit" to TriggerDefinition(
                table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER,
            )),
        ))

        val preStmts = result.statementsForPhase(DdlPhase.PRE_DATA)
        val postStmts = result.statementsForPhase(DdlPhase.POST_DATA)

        // PRE_DATA: table + simple_view only
        preStmts.any { it.sql.contains("orders") && it.sql.contains("CREATE TABLE") } shouldBe true
        preStmts.any { it.sql.contains("simple_view") } shouldBe true
        preStmts.none { it.sql.contains("computed_view") } shouldBe true
        preStmts.none { it.sql.contains("dependent_view") } shouldBe true
        preStmts.none { it.sql.contains("heuristic_view") } shouldBe true

        // POST_DATA: computed_view, dependent_view, heuristic_view, calc_total, trg_audit
        postStmts.any { it.sql.contains("computed_view") } shouldBe true
        postStmts.any { it.sql.contains("dependent_view") } shouldBe true
        postStmts.any { it.sql.contains("heuristic_view") } shouldBe true
        postStmts.any { it.sql.contains("calc_total") } shouldBe true
        postStmts.any { it.sql.contains("trg_audit") } shouldBe true
    }

    test("render() is unchanged despite phase tagging") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            "t" to table(),
            functions = mapOf("fn1" to FunctionDefinition()),
            triggers = mapOf("trg1" to TriggerDefinition(table = "t", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER)),
        ))
        val rendered = result.render()
        rendered shouldContain "CREATE TABLE"
        rendered shouldContain "CREATE FUNCTION"
        rendered shouldContain "CREATE TRIGGER"
    }
})

// ───────────────────────────────────────────────────────────────
// Test fixtures and test double
// ───────────────────────────────────────────────────────────────

internal fun schema(
    vararg tables: Pair<String, TableDefinition>,
    views: Map<String, ViewDefinition> = emptyMap(),
    functions: Map<String, FunctionDefinition> = emptyMap(),
    procedures: Map<String, ProcedureDefinition> = emptyMap(),
    triggers: Map<String, TriggerDefinition> = emptyMap(),
) = SchemaDefinition(
    name = "Test",
    version = "1.0.0",
    tables = tables.toMap(),
    views = views,
    functions = functions,
    procedures = procedures,
    triggers = triggers,
)

internal fun table(vararg cols: Pair<String, ColumnDefinition>) = TableDefinition(
    columns = cols.toMap(),
)

internal fun col(refs: ReferenceDefinition? = null) = ColumnDefinition(
    type = NeutralType.Integer,
    references = refs,
)

internal fun refs(table: String, column: String) = ReferenceDefinition(
    table = table,
    column = column,
)

/**
 * Test double for AbstractDdlGenerator. Records the call order and arguments
 * for assertion purposes; produces tiny synthetic statements so generate()
 * has something to filter and invertStatement() has something to chew on.
 */
internal class TestDdlGenerator(
    private val emitBlankSequence: Boolean = false,
    private val blockedTable: String? = null,
    private val blockCode: String = "E052",
) : AbstractDdlGenerator(StubTypeMapper()) {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    val callOrder = mutableListOf<String>()
    val tableOrder = mutableListOf<String>()
    val viewOrder = mutableListOf<String>()
    val circularEdges = mutableListOf<CircularFkEdge>()

    override fun quoteIdentifier(name: String): String = "\"$name\""

    override fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        callOrder += "table:$name"
        tableOrder += name
        if (name == blockedTable) {
            return listOf(DdlStatement(
                "",
                notes = listOf(TransformationNote(
                    type = NoteType.ACTION_REQUIRED,
                    code = blockCode,
                    objectName = name,
                    message = "Table '$name' blocked in test double",
                    hint = "test hint",
                    blocksTable = true,
                ))
            ))
        }
        return listOf(DdlStatement("CREATE TABLE \"$name\" ();"))
    }

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> {
        callOrder += "customTypes"
        return emptyList()
    }

    override fun generateSequences(
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        callOrder += "sequences"
        return if (emitBlankSequence) listOf(DdlStatement("   ")) else emptyList()
    }

    override fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> {
        callOrder += "indices:$tableName"
        return emptyList()
    }

    override fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        if (edges.isNotEmpty() && circularEdges.isEmpty()) {
            // Track only the first call to keep call-order deterministic
            circularEdges += edges
        }
        if ("circular" !in callOrder) callOrder += "circular"
        return emptyList()
    }

    override fun generateViews(
        views: Map<String, ViewDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        callOrder += "views"
        viewOrder += views.keys
        return views.keys.map { DdlStatement("CREATE VIEW \"$it\" AS SELECT 1;") }
    }

    override fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        callOrder += "functions"
        return functions.keys.map { DdlStatement("CREATE FUNCTION \"$it\"();") }
    }

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        callOrder += "procedures"
        return procedures.keys.map { DdlStatement("CREATE PROCEDURE \"$it\"();") }
    }

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        callOrder += "triggers"
        return triggers.keys.map { DdlStatement("CREATE TRIGGER \"$it\";") }
    }

    // Public bridges to the protected helpers — only used in this test class.
    fun invertForTest(stmt: DdlStatement): DdlStatement? = invertStatement(stmt)
    fun columnSqlForTest(name: String, col: ColumnDefinition, schema: SchemaDefinition): String =
        columnSql("_test_", name, col, schema)
    fun referentialForTest(action: ReferentialAction): String = referentialActionSql(action)
}

internal class StubTypeMapper : TypeMapper {
    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL
    override fun toSql(type: NeutralType): String = when (type) {
        is NeutralType.Integer -> "INTEGER"
        is NeutralType.Text -> if (type.maxLength != null) "VARCHAR(${type.maxLength})" else "TEXT"
        else -> "TEXT"
    }
    override fun toDefaultSql(default: DefaultValue, type: NeutralType): String = when (default) {
        is DefaultValue.StringLiteral -> "'${default.value}'"
        is DefaultValue.NumberLiteral -> default.value.toString()
        is DefaultValue.BooleanLiteral -> if (default.value) "TRUE" else "FALSE"
        is DefaultValue.FunctionCall -> "${default.name}()"
        is DefaultValue.SequenceNextVal -> "nextval('${default.sequenceName}')"
    }
}
