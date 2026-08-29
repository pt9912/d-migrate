package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.AggregateDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.col
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.idTable
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.notesWithCode
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.schema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MssqlDdlGeneratorObjectsTest : FunSpec({

    val generator = MssqlDdlGenerator()

    test("sequences render natively as BIGINT with the neutral attributes") {
        val ddl = generator.generate(
            schema(
                sequences = mapOf(
                    "invoice_seq" to SequenceDefinition(start = 10000, increment = 1, minValue = 10000, maxValue = 99999999, cache = 20),
                    "simple_seq" to SequenceDefinition(),
                    "cyc" to SequenceDefinition(start = 5, increment = -1, cycle = true),
                ),
            ),
        ).render()
        ddl shouldContain "CREATE SEQUENCE [invoice_seq] AS BIGINT START WITH 10000 INCREMENT BY 1 MINVALUE 10000 " +
            "MAXVALUE 99999999 NO CYCLE CACHE 20;"
        ddl shouldContain "CREATE SEQUENCE [simple_seq] AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;"
        // CYCLE ohne explizite Schranke: SQL Server würde sonst auf die BIGINT-Typgrenze umbrechen.
        ddl shouldContain "CREATE SEQUENCE [cyc] AS BIGINT START WITH 5 INCREMENT BY -1 MAXVALUE 5 CYCLE;"
    }

    test("ascending CYCLE sequence without min_value gets the standard lower bound 1") {
        val ddl = generator.generate(
            schema(sequences = mapOf("wrap" to SequenceDefinition(start = 1, maxValue = 100, cycle = true))),
        ).render()
        ddl shouldContain "CREATE SEQUENCE [wrap] AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 100 CYCLE;"
    }

    test("views render as CREATE OR ALTER VIEW; materialized views degrade with W103") {
        val result = generator.generate(
            schema(
                tables = mapOf("orders" to idTable("amount" to col(NeutralType.Decimal(10, 2)))),
                views = mapOf(
                    "active_orders" to ViewDefinition(query = "SELECT * FROM orders WHERE amount > 0", sourceDialect = "postgresql"),
                    "stats" to ViewDefinition(query = "SELECT COUNT(*) FROM orders", materialized = true),
                ),
            ),
        )
        val ddl = result.render()
        ddl shouldContain "CREATE OR ALTER VIEW [active_orders] AS\nSELECT * FROM orders WHERE amount > 0;"
        ddl shouldContain "CREATE OR ALTER VIEW [stats] AS\nSELECT COUNT(*) FROM orders;"
        ddl shouldNotContain "MATERIALIZED"
        result.notesWithCode("W103").single().objectName shouldBe "stats"
    }

    test("non-portable view bodies and views without query are skipped") {
        val result = generator.generate(
            schema(
                tables = mapOf("orders" to idTable()),
                views = mapOf(
                    "computed" to ViewDefinition(query = "SELECT calc_total(id) FROM orders", sourceDialect = "postgresql"),
                    "empty" to ViewDefinition(query = null),
                ),
            ),
        )
        result.render() shouldNotContain "CREATE OR ALTER VIEW [computed]"
        result.notesWithCode("E053").single().objectName shouldBe "computed"
        result.skippedObjects.map { it.name } shouldBe listOf("computed", "empty")
    }

    test("T-SQL routines and triggers render as CREATE OR ALTER with the signature from the model") {
        val result = generator.generate(
            schema(
                tables = mapOf("orders" to idTable()),
                functions = mapOf(
                    "calc(in:integer)" to FunctionDefinition(
                        parameters = listOf(ParameterDefinition("order_id", "integer")),
                        returns = ReturnType("decimal", precision = 10, scale = 2),
                        body = "BEGIN RETURN 0 END",
                        sourceDialect = "mssql",
                    ),
                    "rows(in:integer)" to FunctionDefinition(
                        parameters = listOf(ParameterDefinition("order_id", "integer")),
                        returns = ReturnType("table"),
                        body = "RETURN (SELECT id FROM orders)",
                        sourceDialect = "mssql",
                    ),
                ),
                procedures = mapOf(
                    "upd(in:integer,out:integer)" to ProcedureDefinition(
                        parameters = listOf(
                            ParameterDefinition("id", "integer"),
                            ParameterDefinition("affected", "integer", ParameterDirection.OUT),
                        ),
                        body = "BEGIN SET @affected = 1 END",
                        sourceDialect = "mssql",
                    ),
                ),
                triggers = mapOf(
                    "orders::trg_audit" to TriggerDefinition(
                        table = "orders",
                        events = setOf(TriggerEvent.INSERT, TriggerEvent.UPDATE),
                        timing = TriggerTiming.AFTER,
                        forEach = TriggerForEach.STATEMENT,
                        body = "BEGIN SET NOCOUNT ON END",
                        sourceDialect = "mssql",
                    ),
                ),
            ),
        )
        val ddl = result.render()
        ddl shouldContain "CREATE OR ALTER FUNCTION [calc](@order_id INT)\nRETURNS DECIMAL(10,2) AS\nBEGIN RETURN 0 END"
        // Eine Inline-Tabellenfunktion gibt `TABLE` zurueck; die Spalten stehen im Rumpf.
        ddl shouldContain "CREATE OR ALTER FUNCTION [rows](@order_id INT)\nRETURNS TABLE AS\nRETURN (SELECT id FROM orders)"
        ddl shouldContain "CREATE OR ALTER PROCEDURE [upd](@id INT, @affected INT OUTPUT) AS"
        // T-SQL trennt Trigger-Ereignisse mit Komma, nicht mit OR.
        ddl shouldContain "CREATE OR ALTER TRIGGER [trg_audit] ON [orders]\nAFTER INSERT, UPDATE AS"
        result.notesWithCode("E053").shouldBeEmpty()
        result.skippedObjects.shouldBeEmpty()
    }

    test("rollback inverts the CREATE OR ALTER forms into DROP ... IF EXISTS") {
        val rollback = generator.generateRollback(
            schema(
                tables = mapOf("orders" to idTable()),
                functions = mapOf(
                    "calc()" to FunctionDefinition(
                        returns = ReturnType("integer"), body = "BEGIN RETURN 0 END", sourceDialect = "mssql",
                    ),
                ),
                procedures = mapOf(
                    "upd()" to ProcedureDefinition(body = "BEGIN END", sourceDialect = "mssql"),
                ),
                triggers = mapOf(
                    "orders::trg" to TriggerDefinition(
                        table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER,
                        forEach = TriggerForEach.STATEMENT, body = "BEGIN END", sourceDialect = "mssql",
                    ),
                ),
            ),
        ).render()
        rollback shouldContain "DROP FUNCTION IF EXISTS [calc];"
        rollback shouldContain "DROP PROCEDURE IF EXISTS [upd];"
        rollback shouldContain "DROP TRIGGER IF EXISTS [trg];"
    }

    test("what T-SQL cannot express stays an E053 skipped object") {
        val result = generator.generate(
            schema(
                tables = mapOf("orders" to idTable()),
                functions = mapOf(
                    "calc_total" to FunctionDefinition(body = "BEGIN RETURN 0; END;", sourceDialect = "postgresql"),
                    "no_body" to FunctionDefinition(),
                    "no_returns" to FunctionDefinition(body = "BEGIN RETURN 0 END", sourceDialect = "mssql"),
                ),
                procedures = mapOf("upd" to ProcedureDefinition(body = "BEGIN END", sourceDialect = "postgresql")),
                triggers = mapOf(
                    "orders::before" to TriggerDefinition(
                        table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.BEFORE,
                        forEach = TriggerForEach.STATEMENT, body = "BEGIN END", sourceDialect = "mssql",
                    ),
                    "orders::per_row" to TriggerDefinition(
                        table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER,
                        body = "BEGIN END", sourceDialect = "mssql",
                    ),
                    "orders::conditional" to TriggerDefinition(
                        table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER,
                        forEach = TriggerForEach.STATEMENT, condition = "NEW.id > 0",
                        body = "BEGIN END", sourceDialect = "mssql",
                    ),
                ),
                aggregates = mapOf("agg" to AggregateDefinition(stateType = "int", transitionFunction = "f")),
            ),
        )
        val post = result.renderPhase(DdlPhase.POST_DATA)
        post shouldNotContain "CREATE OR ALTER FUNCTION"
        post shouldNotContain "CREATE OR ALTER PROCEDURE"
        post shouldNotContain "CREATE OR ALTER TRIGGER"
        val e053 = result.notesWithCode("E053").associateBy { it.objectName }
        e053.keys shouldBe setOf("calc_total", "no_body", "no_returns", "upd", "before", "per_row", "conditional")
        e053.getValue("calc_total").message shouldContain "written for 'postgresql'"
        e053.getValue("no_body").message shouldContain "has no body"
        e053.getValue("no_returns").message shouldContain "requires a RETURNS clause"
        e053.getValue("before").message shouldContain "only knows AFTER and INSTEAD OF"
        e053.getValue("per_row").message shouldContain "fire once per statement"
        e053.getValue("conditional").message shouldContain "WHEN condition"
        result.notesWithCode("E054").single().objectName shouldBe "agg"
    }

    test("identically named triggers on different tables collide on the schema-global T-SQL namespace") {
        val result = generator.generate(
            schema(
                tables = mapOf("orders" to idTable(), "items" to idTable()),
                triggers = mapOf(
                    "orders::touch" to TriggerDefinition(
                        table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER,
                        forEach = TriggerForEach.STATEMENT, body = "BEGIN END", sourceDialect = "mssql",
                    ),
                    "items::touch" to TriggerDefinition(
                        table = "items", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER,
                        forEach = TriggerForEach.STATEMENT, body = "BEGIN END", sourceDialect = "mssql",
                    ),
                ),
            ),
        )
        result.render() shouldNotContain "CREATE OR ALTER TRIGGER"
        val e053 = result.notesWithCode("E053")
        e053.size shouldBe 2
        e053.first().message shouldContain "schema-global"
    }

    test("spatial profile native: geodetic SRID renders geography, planar geometry; W120 only for unenforced subtype/SRID") {
        val places = idTable(
            "location" to col(NeutralType.Geometry(GeometryType("point"), 4326), required = true),
            "footprint" to col(NeutralType.Geometry(srid = 4326)),
            "area" to col(NeutralType.Geometry(GeometryType("polygon"), 3857)),
            "shape" to col(NeutralType.Geometry()),
        )
        val result = generator.generate(
            schema(tables = mapOf("places" to places)),
            DdlGenerationOptions(spatialProfile = SpatialProfile.NATIVE),
        )
        val ddl = result.render()
        ddl shouldContain "[location] geography NOT NULL"
        ddl shouldContain "[footprint] geography"
        ddl shouldContain "[area] geometry"
        ddl shouldContain "[shape] geometry"
        result.notesWithCode("W120").map { it.objectName } shouldBe listOf("places.location", "places.area")
    }

    test("spatial profile none blocks the geometry table with E052") {
        val places = idTable("location" to col(NeutralType.Geometry(GeometryType("point"), 4326)))
        val result = generator.generate(
            schema(tables = mapOf("places" to places, "plain" to idTable())),
            DdlGenerationOptions(spatialProfile = SpatialProfile.NONE),
        )
        result.render() shouldNotContain "CREATE TABLE [places]"
        result.render() shouldContain "CREATE TABLE [plain]"
        result.notesWithCode("E052").single().objectName shouldBe "places"
        result.skippedObjects.map { it.name } shouldBe listOf("places")
    }

    test("generate with an empty schema yields only the header") {
        val result = generator.generate(schema())
        result.statements.size shouldBe 1
        result.statements.single().sql shouldContain "-- Generated by d-migrate"
        result.notes.shouldBeEmpty()
    }
})
