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
class AbstractDdlGeneratorTest : FunSpec({

    // ─── Topological sort ────────────────────────────────────────

    test("topologicalSort orders independent tables in stable order") {
        val gen = TestDdlGenerator()
        val schema = schema(
            "a" to table(),
            "b" to table(),
            "c" to table(),
        )
        gen.generate(schema)
        gen.tableOrder shouldContainExactly listOf("a", "b", "c")
    }

    test("topologicalSort orders dependent tables before their dependents") {
        val gen = TestDdlGenerator()
        val schema = schema(
            "orders" to table(
                "user_id" to col(refs("users", "id")),
            ),
            "users" to table("id" to col()),
        )
        gen.generate(schema)
        (gen.tableOrder.indexOf("users") < gen.tableOrder.indexOf("orders")) shouldBe true
    }

    test("topologicalSort detects circular FKs and reports them via handleCircularReferences") {
        val gen = TestDdlGenerator()
        val schema = schema(
            "a" to table("b_id" to col(refs("b", "id"))),
            "b" to table("a_id" to col(refs("a", "id"))),
        )
        gen.generate(schema)
        // Both tables get created
        gen.tableOrder shouldContainExactly listOf("a", "b")
        // Both circular edges are reported (a→b and b→a)
        gen.circularEdges shouldHaveSize 2
        gen.circularEdges.map { it.fromTable }.toSet() shouldBe setOf("a", "b")
    }

    test("topologicalSort ignores self-references (a column referencing its own table)") {
        val gen = TestDdlGenerator()
        val schema = schema(
            "node" to table(
                "id" to col(),
                "parent" to col(refs("node", "id")),
            ),
        )
        gen.generate(schema)
        gen.tableOrder shouldContainExactly listOf("node")
        gen.circularEdges shouldBe emptyList()
    }

    test("topologicalSort ignores references to unknown tables") {
        val gen = TestDdlGenerator()
        val schema = schema(
            "orders" to table("user_id" to col(refs("nonexistent", "id"))),
        )
        gen.generate(schema)
        gen.tableOrder shouldContainExactly listOf("orders")
    }

    // ─── generate() template method ──────────────────────────────

    test("generate() invokes the abstract methods in the documented order") {
        val gen = TestDdlGenerator()
        val schema = schema("t" to table())
        gen.generate(schema)
        // Order: header → custom types → sequences → tables → indices → circular FKs
        // → views → functions → procedures → triggers
        // views is called twice: once for PRE_DATA bucket, once for POST_DATA bucket
        gen.callOrder shouldContainExactly listOf(
            "customTypes", "sequences",
            "table:t", "indices:t",
            "circular", "views", "views", "functions", "procedures", "triggers",
        )
    }

    test("generate() filters out empty statements") {
        val gen = TestDdlGenerator(emitBlankSequence = true)
        val schema = schema("t" to table())
        val result = gen.generate(schema)
        result.statements.none { it.sql.isBlank() } shouldBe true
    }

    test("generate() includes the standard header as first statement") {
        val gen = TestDdlGenerator()
        val schema = schema("t" to table())
        val result = gen.generate(schema)
        val header = result.statements.first().sql
        header shouldContain "Generated by d-migrate"
        header shouldContain "Source: neutral schema v1.0.0"
        header shouldContain "Target: postgresql"
    }

    test("generate() sorts views topologically by dependencies before delegating") {
        val gen = TestDdlGenerator()
        val schema = schema(
            views = linkedMapOf(
                "view_b" to ViewDefinition(
                    query = "SELECT * FROM view_a",
                    dependencies = DependencyInfo(views = listOf("view_a"))
                ),
                "view_a" to ViewDefinition(query = "SELECT 1")
            )
        )

        gen.generate(schema)

        gen.viewOrder shouldContainExactly listOf("view_a", "view_b")
    }

    test("generate() infers view dependencies from query references") {
        val gen = TestDdlGenerator()
        val schema = schema(
            views = linkedMapOf(
                "view_b" to ViewDefinition(query = "SELECT * FROM view_a"),
                "view_a" to ViewDefinition(query = "SELECT 1"),
            )
        )

        gen.generate(schema)

        gen.viewOrder shouldContainExactly listOf("view_a", "view_b")
    }

    test("generate() warns when view dependencies are circular and preserves original order for remaining views") {
        val gen = TestDdlGenerator()
        val schema = schema(
            views = linkedMapOf(
                "view_b" to ViewDefinition(query = "SELECT * FROM view_a"),
                "view_a" to ViewDefinition(query = "SELECT * FROM view_b"),
            )
        )

        val result = gen.generate(schema)

        gen.viewOrder shouldContainExactly listOf("view_b", "view_a")
        result.notes.any { it.code == "W113" && it.objectName == "views" } shouldBe true
    }

    test("generate() treats blocking notes as table blockers independent of error code") {
        val gen = TestDdlGenerator(blockedTable = "t", blockCode = "E055")
        val schema = schema("t" to table())

        val result = gen.generate(schema)

        gen.callOrder shouldContainExactly listOf(
            "customTypes", "sequences",
            "table:t",
            "circular", "views", "views", "functions", "procedures", "triggers",
        )
        result.notes.any { it.code == "E055" && it.blocksTable && it.objectName == "t" } shouldBe true
        result.skippedObjects shouldContainExactly listOf(
            SkippedObject(
                type = "table",
                name = "t",
                reason = "Table 't' blocked in test double",
                code = "E055",
                hint = "test hint",
                phase = DdlPhase.PRE_DATA,
            )
        )
    }

    // ─── generateRollback() / invertStatement ────────────────────

    test("generateRollback inverts CREATE TABLE → DROP TABLE in reverse order") {
        val gen = TestDdlGenerator()
        val schema = schema("a" to table(), "b" to table())
        val rollback = gen.generateRollback(schema)
        // The forward order is a, b → reversed b, a
        val drops = rollback.statements.filter { it.sql.startsWith("DROP TABLE") }
        drops.size shouldBe 2
        drops[0].sql shouldContain "b"
        drops[1].sql shouldContain "a"
    }

    test("invertStatement inverts CREATE INDEX → DROP INDEX") {
        val gen = TestDdlGenerator()
        val inv = gen.invertForTest(DdlStatement("CREATE INDEX idx_x ON t (col);"))
        inv?.sql shouldBe "DROP INDEX IF EXISTS idx_x;"
    }

    test("invertStatement inverts CREATE UNIQUE INDEX → DROP INDEX") {
        val gen = TestDdlGenerator()
        val inv = gen.invertForTest(DdlStatement("CREATE UNIQUE INDEX idx_email ON users (email);"))
        inv?.sql shouldBe "DROP INDEX IF EXISTS idx_email;"
    }

    test("invertStatement inverts CREATE TYPE → DROP TYPE") {
        val gen = TestDdlGenerator()
        val inv = gen.invertForTest(DdlStatement("CREATE TYPE status AS ENUM ('a', 'b');"))
        inv?.sql shouldBe "DROP TYPE IF EXISTS status;"
    }

    test("invertStatement inverts CREATE VIEW and CREATE OR REPLACE VIEW") {
        val gen = TestDdlGenerator()
        gen.invertForTest(DdlStatement("CREATE VIEW v AS SELECT 1;"))?.sql shouldBe "DROP VIEW IF EXISTS v;"
        gen.invertForTest(DdlStatement("CREATE OR REPLACE VIEW v AS SELECT 1;"))?.sql shouldBe "DROP VIEW IF EXISTS v;"
    }

    test("invertStatement inverts CREATE MATERIALIZED VIEW") {
        val gen = TestDdlGenerator()
        gen.invertForTest(DdlStatement("CREATE MATERIALIZED VIEW mv AS SELECT 1;"))?.sql shouldBe
            "DROP MATERIALIZED VIEW IF EXISTS mv;"
    }

    test("invertStatement inverts CREATE FUNCTION and CREATE OR REPLACE FUNCTION") {
        val gen = TestDdlGenerator()
        // Note: extractNameAfter splits on the first whitespace OR '(', so the
        // emitted DROP statement carries only the bare identifier, not the
        // parameter list — that is sufficient for `DROP FUNCTION IF EXISTS`
        // in PostgreSQL/MySQL when the function name is unique.
        gen.invertForTest(DdlStatement("CREATE FUNCTION fn() RETURNS INT AS \$\$ ... \$\$;"))?.sql shouldBe
            "DROP FUNCTION IF EXISTS fn;"
        gen.invertForTest(DdlStatement("CREATE OR REPLACE FUNCTION fn() RETURNS INT AS \$\$ ... \$\$;"))?.sql shouldBe
            "DROP FUNCTION IF EXISTS fn;"
    }

    test("invertStatement inverts CREATE PROCEDURE → DROP PROCEDURE") {
        val gen = TestDdlGenerator()
        gen.invertForTest(DdlStatement("CREATE PROCEDURE p() AS \$\$ ... \$\$;"))?.sql shouldBe
            "DROP PROCEDURE IF EXISTS p;"
    }

    test("invertStatement inverts CREATE TRIGGER → DROP TRIGGER") {
        val gen = TestDdlGenerator()
        gen.invertForTest(DdlStatement("CREATE TRIGGER trg BEFORE INSERT ON t ..."))?.sql shouldBe
            "DROP TRIGGER IF EXISTS trg;"
    }

    test("invertStatement inverts CREATE SEQUENCE → DROP SEQUENCE") {
        val gen = TestDdlGenerator()
        gen.invertForTest(DdlStatement("CREATE SEQUENCE seq START WITH 1;"))?.sql shouldBe
            "DROP SEQUENCE IF EXISTS seq;"
    }

    test("invertStatement inverts ALTER TABLE ADD CONSTRAINT → ALTER TABLE DROP CONSTRAINT") {
        val gen = TestDdlGenerator()
        val sql = "ALTER TABLE orders ADD CONSTRAINT fk_user FOREIGN KEY (uid) REFERENCES users(id);"
        gen.invertForTest(DdlStatement(sql))?.sql shouldBe
            "ALTER TABLE orders DROP CONSTRAINT IF EXISTS fk_user;"
    }

    test("invertStatement skips comment-only statements") {
        val gen = TestDdlGenerator()
        gen.invertForTest(DdlStatement("-- just a comment")) shouldBe null
    }

    test("invertStatement skips unknown statements") {
        val gen = TestDdlGenerator()
        gen.invertForTest(DdlStatement("SELECT 1;")) shouldBe null
    }

    test("invertStatement handles CREATE TABLE IF NOT EXISTS") {
        val gen = TestDdlGenerator()
        gen.invertForTest(DdlStatement("CREATE TABLE IF NOT EXISTS users (id INT);"))?.sql shouldBe
            "DROP TABLE IF EXISTS users;"
    }

    // ─── columnSql + referentialActionSql ────────────────────────

    test("columnSql includes type, NOT NULL, DEFAULT and UNIQUE in order") {
        val gen = TestDdlGenerator()
        val schema = schema(
            "users" to TableDefinition(
                columns = mapOf(
                    "email" to ColumnDefinition(
                        type = NeutralType.Text(maxLength = 254),
                        required = true,
                        unique = true,
                        default = DefaultValue.StringLiteral("a@b"),
                    ),
                ),
            ),
        )
        val sql = gen.columnSqlForTest("email", schema.tables["users"]!!.columns["email"]!!, schema)
        sql shouldBe "\"email\" VARCHAR(254) NOT NULL DEFAULT 'a@b' UNIQUE"
    }

    test("columnSql with no flags returns just name + type") {
        val gen = TestDdlGenerator()
        val col = ColumnDefinition(type = NeutralType.Integer)
        gen.columnSqlForTest("id", col, schema()) shouldBe "\"id\" INTEGER"
    }

    test("referentialActionSql maps all enum values") {
        val gen = TestDdlGenerator()
        gen.referentialForTest(ReferentialAction.RESTRICT) shouldBe "RESTRICT"
        gen.referentialForTest(ReferentialAction.CASCADE) shouldBe "CASCADE"
        gen.referentialForTest(ReferentialAction.SET_NULL) shouldBe "SET NULL"
        gen.referentialForTest(ReferentialAction.SET_DEFAULT) shouldBe "SET DEFAULT"
        gen.referentialForTest(ReferentialAction.NO_ACTION) shouldBe "NO ACTION"
    }

    // ─── Phase tagging (0.9.2 AP 6.3 Steps B+D) ────────────────

    test("generate() tags tables and indices as PRE_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema("t" to table()))
        result.statementsForPhase(DdlPhase.PRE_DATA)
            .any { it.sql.contains("CREATE TABLE") } shouldBe true
        result.statementsForPhase(DdlPhase.POST_DATA)
            .none { it.sql.contains("CREATE TABLE") } shouldBe true
    }

    test("generate() tags functions as POST_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            functions = mapOf("fn1" to FunctionDefinition()),
        ))
        result.statementsForPhase(DdlPhase.POST_DATA)
            .any { it.sql.contains("CREATE FUNCTION") } shouldBe true
        result.statementsForPhase(DdlPhase.PRE_DATA)
            .none { it.sql.contains("CREATE FUNCTION") } shouldBe true
    }

    test("generate() tags procedures as POST_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            procedures = mapOf("proc1" to ProcedureDefinition()),
        ))
        result.statementsForPhase(DdlPhase.POST_DATA)
            .any { it.sql.contains("CREATE PROCEDURE") } shouldBe true
    }

    test("generate() tags triggers as POST_DATA") {
        val gen = TestDdlGenerator()
        val result = gen.generate(schema(
            "t" to table(),
            triggers = mapOf("trg1" to TriggerDefinition(table = "t", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER)),
        ))
        result.statementsForPhase(DdlPhase.POST_DATA)
            .any { it.sql.contains("CREATE TRIGGER") } shouldBe true
    }

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

private fun schema(
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

private fun table(vararg cols: Pair<String, ColumnDefinition>) = TableDefinition(
    columns = cols.toMap(),
)

private fun col(refs: ReferenceDefinition? = null) = ColumnDefinition(
    type = NeutralType.Integer,
    references = refs,
)

private fun refs(table: String, column: String) = ReferenceDefinition(
    table = table,
    column = column,
)

/**
 * Test double for AbstractDdlGenerator. Records the call order and arguments
 * for assertion purposes; produces tiny synthetic statements so generate()
 * has something to filter and invertStatement() has something to chew on.
 */
private class TestDdlGenerator(
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
        columnSql(name, col, schema)
    fun referentialForTest(action: ReferentialAction): String = referentialActionSql(action)
}

private class StubTypeMapper : TypeMapper {
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
    }
}
