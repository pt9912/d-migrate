package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.AggregateDefinition
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class OracleDdlGeneratorObjectsTest : FunSpec({

    val generator = OracleDdlGenerator()

    fun schema(
        tables: Map<String, TableDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
        aggregates: Map<String, AggregateDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = "s", version = "1.0", tables = tables, sequences = sequences, views = views,
        functions = functions, procedures = procedures, triggers = triggers, aggregates = aggregates,
    )

    fun optionsForVersion(raw: String) = dev.dmigrate.driver.DdlGenerationOptions(
        dialectContext = dev.dmigrate.driver.DdlDialectContext.Oracle(
            serverVersion = dev.dmigrate.driver.OracleServerVersion.parse(raw),
        ),
    )

    fun rollbackSchema() = schema(
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)),
                indices = listOf(IndexDefinition(name = "ix_id", columns = listOf(IndexColumn("id")))),
            ),
        ),
        sequences = mapOf("seq" to SequenceDefinition()),
        views = mapOf("v" to ViewDefinition(query = "SELECT 1")),
    )

    // ── Sequences ────────────────────────────────

    test("sequence with all bounds renders a full CREATE SEQUENCE") {
        val seq = SequenceDefinition(start = 100, increment = 5, minValue = 1, maxValue = 9999, cycle = true, cache = 20)
        val sql = generator.generate(schema(sequences = mapOf("order_seq" to seq))).render()
        sql shouldContain "CREATE SEQUENCE \"order_seq\" START WITH 100 INCREMENT BY 5 " +
            "MINVALUE 1 MAXVALUE 9999 CYCLE CACHE 20;"
    }

    test("sequence without bounds uses NOMINVALUE/NOMAXVALUE/NOCYCLE/NOCACHE") {
        val seq = SequenceDefinition()
        val sql = generator.generate(schema(sequences = mapOf("s" to seq))).render()
        sql shouldContain "CREATE SEQUENCE \"s\" START WITH 1 INCREMENT BY 1 " +
            "NOMINVALUE NOMAXVALUE NOCYCLE NOCACHE;"
    }

    // ── Indices ──────────────────────────────────

    fun tableWith(index: IndexDefinition, columnType: NeutralType = NeutralType.Integer) = TableDefinition(
        columns = mapOf("a" to ColumnDefinition(type = columnType, ordinal = 1)),
        indices = listOf(index),
    )

    test("a plain BTREE index renders CREATE INDEX with a derived name") {
        val table = tableWith(IndexDefinition(columns = listOf(IndexColumn("a"))))
        val sql = generator.generate(schema(tables = mapOf("t" to table))).render()
        sql shouldContain "CREATE INDEX \"idx_t_a\" ON \"t\" (\"a\");"
    }

    test("a unique index renders CREATE UNIQUE INDEX; direction is preserved") {
        val table = tableWith(
            IndexDefinition(name = "ux_a", columns = listOf(IndexColumn("a", IndexSortDirection.DESC)), unique = true),
        )
        val sql = generator.generate(schema(tables = mapOf("t" to table))).render()
        sql shouldContain "CREATE UNIQUE INDEX \"ux_a\" ON \"t\" (\"a\" DESC);"
    }

    test("a non-BTREE index degrades to a standard B-tree index with a W102 note") {
        val table = tableWith(IndexDefinition(name = "ix_hash", columns = listOf(IndexColumn("a")), type = IndexType.HASH))
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.render() shouldContain "CREATE INDEX \"ix_hash\" ON \"t\" (\"a\");"
        result.notes.single { it.objectName == "ix_hash" }.code shouldBe "W102"
    }

    test("a single-column FULLTEXT index renders an Oracle Text index that syncs on commit") {
        val table = tableWith(IndexDefinition(name = "ix_ft", columns = listOf(IndexColumn("a")), type = IndexType.FULLTEXT))
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        val sql = result.render()
        sql shouldContain "CREATE INDEX \"ix_ft\" ON \"t\" (\"a\") INDEXTYPE IS CTXSYS.CONTEXT"
        // Live gemessen: ohne SYNC (ON COMMIT) findet der Index nach einem
        // INSERT nichts und bleibt leer, bis jemand CTX_DDL.SYNC_INDEX ruft.
        sql shouldContain "PARAMETERS ('SYNC (ON COMMIT)')"
        result.notes.none { it.objectName == "ix_ft" } shouldBe true
    }

    test("a FULLTEXT index on a CLOB column renders — the LOB guard must not catch it first") {
        // Der Normalfall: Oracle Text indiziert gerade grosse Textspalten.
        // Stuende der Volltext-Zweig hinter dem W152-Waechter, verschwaende
        // dieser Index -- und die drei anderen Faelle hier merkten es nicht,
        // weil ihre Spalte ein INTEGER ist.
        val table = tableWith(
            IndexDefinition(name = "ix_ft_lob", columns = listOf(IndexColumn("a")), type = IndexType.FULLTEXT),
            columnType = NeutralType.Text(null),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.render() shouldContain "INDEXTYPE IS CTXSYS.CONTEXT"
        result.notes.none { it.code == "W152" } shouldBe true
    }

    test("a unique declaration on a FULLTEXT index is reported, not silently dropped") {
        val table = tableWith(
            IndexDefinition(
                name = "ix_ft_uq", columns = listOf(IndexColumn("a")),
                type = IndexType.FULLTEXT, unique = true,
            ),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.notes.single { it.objectName == "ix_ft_uq" }.code shouldBe "W102"
    }

    test("a multi-column FULLTEXT index is refused, not split into several") {
        // ORA-29851: ein Domain-Index deckt genau eine Spalte. Ihn zu
        // zerlegen aenderte, was eine Suche trifft.
        val table = tableWith(
            IndexDefinition(
                name = "ix_ft2",
                columns = listOf(IndexColumn("a"), IndexColumn("b")),
                type = IndexType.FULLTEXT,
            ),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.notes.single { it.objectName == "ix_ft2" }.code shouldBe "E057"
        result.render() shouldNotContain "INDEXTYPE IS"
    }

    test("a dropped text search configuration is reported") {
        val table = tableWith(
            IndexDefinition(
                name = "ix_ft3", columns = listOf(IndexColumn("a")),
                type = IndexType.FULLTEXT, textSearchConfig = "german",
            ),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.notes.single { it.objectName == "ix_ft3" }.code shouldBe "W154"
    }

    test("an index on a CLOB column is skipped with W152 (not key/index-eligible)") {
        val table = tableWith(
            IndexDefinition(name = "ix_big", columns = listOf(IndexColumn("a"))),
            columnType = NeutralType.Text(null),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.notes.single { it.objectName == "ix_big" }.code shouldBe "W152"
    }

    test("a partial index is created in full and says so with W155") {
        val table = tableWith(
            IndexDefinition(name = "ix_active", columns = listOf(IndexColumn("a")), where = "a IS NOT NULL"),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))

        val note = result.notes.single { it.objectName == "ix_active" }
        note.code shouldBe "W155"
        note.message shouldContain "a IS NOT NULL"
        // Der Index entsteht -- er ist nur weiter als verlangt. Ihn
        // wegzulassen naehme dem Ziel einen Index ohne Not.
        result.render() shouldContain "CREATE INDEX \"ix_active\" ON \"t\" (\"a\");"
        result.render() shouldNotContain "WHERE"
    }

    test("a unique partial index says that the promise itself changed") {
        val table = tableWith(
            IndexDefinition(
                name = "ix_one_active", columns = listOf(IndexColumn("a")),
                unique = true, where = "active",
            ),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))

        // Aus "hoechstens eine aktive Zeile je Schluessel" wird "hoechstens
        // eine Zeile je Schluessel ueberhaupt" -- ein Bedeutungswechsel, kein
        // blosser Groessenunterschied.
        result.notes.single { it.objectName == "ix_one_active" }.message shouldContain "every row"
        result.render() shouldContain "CREATE UNIQUE INDEX \"ix_one_active\" ON \"t\" (\"a\");"
    }

    // ── Views ────────────────────────────────────

    test("a simple view renders CREATE OR REPLACE FORCE VIEW") {
        val view = ViewDefinition(query = "SELECT 1 AS one", sourceDialect = "oracle")
        val sql = generator.generate(schema(views = mapOf("v" to view))).render()
        sql shouldContain "CREATE OR REPLACE FORCE VIEW \"v\" AS\nSELECT 1 AS one;"
    }

    test("a materialized view renders natively; the refresh default stays implicit") {
        val view = ViewDefinition(query = "SELECT 1 FROM dual", materialized = true, sourceDialect = "oracle")
        val result = generator.generate(schema(views = mapOf("v" to view)))
        result.render() shouldContain "CREATE MATERIALIZED VIEW \"v\"\nAS\nSELECT 1 FROM dual;"
        // Oracles Voreinstellung ist FORCE ON DEMAND; sie auszuschreiben
        // behauptete einen Unterschied, wo keiner ist.
        result.render() shouldNotContain "REFRESH"
        result.skippedObjects.shouldBeEmpty()
    }

    test("an explicit refresh setting is rendered") {
        val view = ViewDefinition(
            query = "SELECT 1 FROM dual", materialized = true, refresh = "complete on demand",
            sourceDialect = "oracle",
        )
        generator.generate(schema(views = mapOf("v" to view))).render() shouldContain
            "CREATE MATERIALIZED VIEW \"v\"\nREFRESH COMPLETE ON DEMAND\nAS"
    }

    test("REFRESH FAST is reported: Oracle needs a materialized view log the model does not carry") {
        val view = ViewDefinition(
            query = "SELECT 1 FROM dual", materialized = true, refresh = "fast on commit",
            sourceDialect = "oracle",
        )
        val result = generator.generate(schema(views = mapOf("v" to view)))
        result.skippedObjects.single().code shouldBe "E053"
        result.render() shouldContain "ORA-23413"
    }

    test("a view without a query is skipped, not rendered as broken DDL") {
        val view = ViewDefinition(query = null)
        val result = generator.generate(schema(views = mapOf("v" to view)))
        result.skippedObjects.single().name shouldBe "v"
    }

    test("a non-portable view (LIMIT clause, no Oracle equivalent) is skipped with E053") {
        val view = ViewDefinition(query = "SELECT 1 LIMIT 10", sourceDialect = "postgresql")
        val result = generator.generate(schema(views = mapOf("v" to view)))
        result.skippedObjects.single().code shouldBe "E053"
    }

    // ── Routines / aggregates (Slice 9 out of scope) ──

    test("functions, procedures and triggers render as PL/SQL with a `/` script terminator") {
        val result = generator.generate(
            schema(
                functions = mapOf(
                    "f(in:integer)" to FunctionDefinition(
                        parameters = listOf(ParameterDefinition("x", "integer")),
                        returns = ReturnType("integer"),
                        body = "BEGIN RETURN x; END;",
                    ),
                ),
                procedures = mapOf("p()" to ProcedureDefinition(body = "BEGIN NULL; END;")),
                triggers = mapOf(
                    "t::trg" to TriggerDefinition(
                        table = "t", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER, body = "BEGIN NULL; END;",
                    ),
                ),
                tables = mapOf("t" to TableDefinition(columns = mapOf("a" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)))),
            ),
        )
        result.skippedObjects.shouldBeEmpty()
        val sql = result.statements.map { it.sql }
        // Parameter- und Rueckgabetyp ohne Laenge: `IN NUMBER(10)` und
        // `RETURN NUMBER(10)` erzeugen die Routine INVALID (live gemessen).
        sql.single { it.startsWith("CREATE OR REPLACE FUNCTION") } shouldBe
            "CREATE OR REPLACE FUNCTION \"f\"(\"x\" IN NUMBER)\nRETURN NUMBER IS\nBEGIN RETURN x; END;"
        // Leere Parameterliste ohne Klammern: `PROCEDURE p()` ist ein
        // Uebersetzungsfehler.
        sql.single { it.startsWith("CREATE OR REPLACE PROCEDURE") } shouldBe
            "CREATE OR REPLACE PROCEDURE \"p\" IS\nBEGIN NULL; END;"
        sql.single { it.startsWith("CREATE OR REPLACE TRIGGER") } shouldBe
            "CREATE OR REPLACE TRIGGER \"trg\"\nAFTER INSERT ON \"t\"\nFOR EACH ROW\nBEGIN NULL; END;"
        // Das `/` gehoert in die Skriptdarstellung, nicht ins SQL.
        result.statements.filter { it.sql.startsWith("CREATE OR REPLACE ") }
            .forEach { it.scriptTerminator shouldBe "/" }
    }

    test("a body from another dialect is not translated but reported as E053") {
        val result = generator.generate(
            schema(
                functions = mapOf(
                    "f()" to FunctionDefinition(
                        returns = ReturnType("integer"),
                        body = "BEGIN RETURN 1; END",
                        sourceDialect = "postgresql",
                    ),
                ),
            ),
        )
        result.skippedObjects.single().code shouldBe "E053"
        result.skippedObjects.single().name shouldBe "f"
    }

    test("aggregates are rejected with E054 (no ODCI implementation type carried by the model)") {
        val agg = AggregateDefinition(stateType = "internal", transitionFunction = "sf")
        val result = generator.generate(schema(aggregates = mapOf("agg" to agg)))
        result.skippedObjects.single().code shouldBe "E054"
    }

    // ── Rollback (invertStatement) ────────────────

    test("without a known server version the rollback drops bare — every Oracle runs that") {
        val rollback = generator.generateRollback(rollbackSchema()).render()
        rollback shouldContain "DROP VIEW \"v\";"
        rollback shouldContain "DROP SEQUENCE \"seq\";"
        rollback shouldContain "DROP INDEX \"ix_id\";"
        rollback shouldContain "DROP TABLE \"t\";"
        rollback shouldNotContain "IF EXISTS"
    }

    test("against a 23er target the rollback carries IF EXISTS — that is what makes it re-runnable") {
        val rollback = generator.generateRollback(rollbackSchema(), optionsForVersion("23.0.0.0.0")).render()
        rollback shouldContain "DROP VIEW IF EXISTS \"v\";"
        rollback shouldContain "DROP SEQUENCE IF EXISTS \"seq\";"
        rollback shouldContain "DROP INDEX IF EXISTS \"ix_id\";"
        rollback shouldContain "DROP TABLE IF EXISTS \"t\";"
    }

    test("a pre-23 target keeps the bare form, though the version is known") {
        val rollback = generator.generateRollback(rollbackSchema(), optionsForVersion("19.0.0.0.0")).render()
        rollback shouldContain "DROP TABLE \"t\";"
        rollback shouldNotContain "IF EXISTS"
    }

    test("the constraint form stays bare even on 23 — Oracle rejects IF EXISTS there") {
        val parent = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)),
            primaryKey = listOf("id"),
        )
        val child = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1),
                "pid" to ColumnDefinition(
                    type = NeutralType.Integer,
                    ordinal = 2,
                    references = dev.dmigrate.core.model.ReferenceDefinition(table = "p", column = "id"),
                ),
            ),
        )
        val rollback = generator.generateRollback(
            schema(tables = mapOf("p" to parent, "c" to child)),
            optionsForVersion("23.0.0.0.0").copy(deferForeignKeys = true),
        ).render()
        rollback shouldContain "DROP CONSTRAINT"
        rollback shouldNotContain "DROP CONSTRAINT IF EXISTS"
    }

    test("a materialized view is taken back too — its CREATE is not a plain CREATE VIEW") {
        val rollback = generator.generateRollback(
            schema(views = mapOf("mv" to ViewDefinition(query = "SELECT 1", materialized = true))),
            optionsForVersion("23.0.0.0.0"),
        ).render()
        rollback shouldContain "DROP MATERIALIZED VIEW IF EXISTS \"mv\";"
    }

    test("rollback drops the spatial index, whose CREATE is wrapped in a PL/SQL block") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1),
                "geom" to ColumnDefinition(type = NeutralType.Geometry(), ordinal = 2),
            ),
            indices = listOf(
                IndexDefinition(
                    name = "sx_geom",
                    columns = listOf(IndexColumn("geom")),
                    type = dev.dmigrate.core.model.IndexType.SPATIAL,
                ),
            ),
        )
        val options = dev.dmigrate.driver.DdlGenerationOptions(
            spatialProfile = dev.dmigrate.driver.SpatialProfile.NATIVE,
        )
        val forward = generator.generate(schema(tables = mapOf("t" to table)), options).render()
        forward shouldContain "INDEXTYPE IS MDSYS.SPATIAL_INDEX_V2"

        // Der Vorwaerts-Block beginnt mit BEGIN, nicht mit CREATE -- der
        // praefixbasierte Inverter griffe hier sonst nicht.
        val rollback = generator.generateRollback(schema(tables = mapOf("t" to table)), options).render()
        rollback shouldContain "DROP INDEX \"sx_geom\";"
        // `FORCE` gehoert zum Aufraeumzweig des Blocks, nicht zur Ruecknahme
        // eines erfolgreich angelegten Index.
        rollback shouldNotContain "FORCE"
    }

    test("rollback of a deferred foreign key drops the named constraint, no IF EXISTS") {
        val parent = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)),
            primaryKey = listOf("id"),
        )
        val child = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1),
                "parent_id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    references = dev.dmigrate.core.model.ReferenceDefinition(table = "parent", column = "id"),
                    ordinal = 2,
                ),
            ),
        )
        val schemaDef = schema(tables = mapOf("parent" to parent, "child" to child))
        val options = dev.dmigrate.driver.DdlGenerationOptions(deferForeignKeys = true)
        val forward = generator.generate(schemaDef, options)
        forward.render() shouldContain "ALTER TABLE \"child\" ADD CONSTRAINT \"fk_child_parent_id\""

        val rollback = generator.generateRollback(schemaDef, options).render()
        rollback shouldContain "ALTER TABLE \"child\" DROP CONSTRAINT \"fk_child_parent_id\";"
        rollback shouldNotContain "IF EXISTS"
    }
})
