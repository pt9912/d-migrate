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
        header shouldContain "Generated by d-migrate 0.9.5"
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

})
