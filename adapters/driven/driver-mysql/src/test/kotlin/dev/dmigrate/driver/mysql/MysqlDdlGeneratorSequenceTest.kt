package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MysqlDdlGeneratorSequenceTest : FunSpec({

    val generator = MysqlDdlGenerator()

    fun emptySchema(
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap()
    ) = SchemaDefinition(
        name = "test_schema",
        version = "1.0",
        tables = tables,
        customTypes = customTypes,
        sequences = sequences,
        views = views,
        functions = functions,
        procedures = procedures,
        triggers = triggers
    )

    test("sequence is skipped with E056 action required note") {
        val schema = emptySchema(
            sequences = mapOf(
                "order_seq" to SequenceDefinition(start = 1, increment = 1)
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "E056"
        ddl shouldContain "Sequence 'order_seq' is not supported in MySQL"

        val notes = result.notes
        val seqNote = notes.find { it.code == "E056" && it.objectName == "order_seq" }
        seqNote shouldBe TransformationNote(
            type = NoteType.ACTION_REQUIRED,
            code = "E056",
            objectName = "order_seq",
            message = "Sequence 'order_seq' is not supported in MySQL without helper_table mode.",
            hint = "Add --mysql-named-sequences helper_table to enable sequence emulation."
        )

        result.skippedObjects.any { it.name == "order_seq" && it.type == "sequence" } shouldBe true
    }

    val helperOpts = DdlGenerationOptions(
        mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE,
    )

    fun seqSchema(
        seqName: String = "invoice_seq",
        tableName: String = "invoices",
        colName: String = "invoice_number",
        cache: Int? = null,
    ) = SchemaDefinition(
        name = "SeqTest", version = "1",
        sequences = mapOf(seqName to SequenceDefinition(start = 1000, increment = 1, cache = cache)),
        tables = mapOf(tableName to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                colName to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal(seqName),
                ),
            ),
            primaryKey = listOf("id"),
        )),
    )

    test("helper_table generates dmg_sequences table and seed") {
        val result = generator.generate(seqSchema(), helperOpts)
        val ddl = result.render()
        ddl shouldContain "CREATE TABLE `dmg_sequences`"
        ddl shouldContain "INSERT INTO `dmg_sequences`"
        ddl shouldContain "'invoice_seq'"
        ddl shouldContain "1000"
    }

    test("helper_table generates dmg_nextval and dmg_setval routines") {
        val result = generator.generate(seqSchema(), helperOpts)
        val ddl = result.render()
        ddl shouldContain "CREATE FUNCTION `dmg_nextval`"
        ddl shouldContain "CREATE FUNCTION `dmg_setval`"
        ddl shouldContain "DELIMITER //"
    }

    test("helper_table generates BEFORE INSERT trigger for SequenceNextVal column") {
        val result = generator.generate(seqSchema(), helperOpts)
        val ddl = result.render()
        ddl shouldContain "BEFORE INSERT ON `invoices`"
        ddl shouldContain "dmg_nextval"
        ddl shouldContain "invoice_seq"
        ddl shouldContain "NEW.`invoice_number`"
    }

    test("helper_table column with SequenceNextVal has no DEFAULT clause on that column") {
        val result = generator.generate(seqSchema(), helperOpts)
        val ddl = result.render()
        val colLine = ddl.lines().firstOrNull { it.contains("`invoice_number`") }
        colLine shouldNotBe null
        colLine!! shouldNotContain "DEFAULT"
    }

    test("helper_table emits W115 per SequenceNextVal column") {
        val result = generator.generate(seqSchema(), helperOpts)
        result.notes.any { it.code == "W115" && it.objectName == "invoices.invoice_number" } shouldBe true
    }

    test("helper_table emits W117 global warning") {
        val result = generator.generate(seqSchema(), helperOpts)
        result.globalNotes.any { it.code == "W117" } shouldBe true
    }

    test("helper_table emits W114 when sequence has cache") {
        val result = generator.generate(seqSchema(cache = 20), helperOpts)
        result.notes.any { it.code == "W114" && it.objectName == "invoice_seq" } shouldBe true
    }

    test("action_required + SequenceNextVal emits E056 with helper_table hint") {
        val result = generator.generate(seqSchema())
        val notes = result.notes
        val e056 = notes.filter { it.code == "E056" }
        e056.any { it.objectName == "invoices.invoice_number" && it.hint?.contains("helper_table") == true } shouldBe true
    }

    test("helper_table rollback drops in correct order: triggers → routines → tables") {
        val rollback = generator.generateRollback(seqSchema(), helperOpts)
        val ddl = rollback.render()
        ddl shouldContain "DROP"
        rollback.statements.size shouldNotBe 0
        val lines = ddl.lines()
        val triggerDropIdx = lines.indexOfFirst { it.contains("DROP TRIGGER") }
        val funcDropIdx = lines.indexOfFirst { it.contains("DROP FUNCTION") }
        val seqTableDropIdx = lines.indexOfFirst { it.contains("dmg_sequences") && it.contains("DROP") }
        if (triggerDropIdx >= 0 && funcDropIdx >= 0) {
            (triggerDropIdx < funcDropIdx) shouldBe true
        }
        if (funcDropIdx >= 0 && seqTableDropIdx >= 0) {
            (funcDropIdx < seqTableDropIdx) shouldBe true
        }
    }

    test("helper_table phases: dmg_sequences in PRE_DATA, routines and triggers in POST_DATA") {
        val result = generator.generate(seqSchema(), helperOpts)
        val preData = result.renderPhase(dev.dmigrate.driver.DdlPhase.PRE_DATA)
        val postData = result.renderPhase(dev.dmigrate.driver.DdlPhase.POST_DATA)
        preData shouldContain "CREATE TABLE `dmg_sequences`"
        postData shouldContain "CREATE FUNCTION `dmg_nextval`"
        postData shouldContain "BEFORE INSERT"
    }

    test("E124: helper_table with schema containing dmg_sequences table emits collision") {
        val schema = SchemaDefinition(
            name = "Collision", version = "1",
            sequences = mapOf("my_seq" to SequenceDefinition(start = 1)),
            tables = mapOf(
                "dmg_sequences" to TableDefinition(
                    columns = linkedMapOf("id" to ColumnDefinition(type = NeutralType.Integer)),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val result = generator.generate(schema, helperOpts)
        result.skippedObjects.any { it.code == "E124" && it.name == "dmg_sequences" } shouldBe true
    }

    test("E124: helper_table with schema containing dmg_nextval function emits collision") {
        val schema = SchemaDefinition(
            name = "FnCollision", version = "1",
            sequences = mapOf("my_seq" to SequenceDefinition(start = 1)),
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(type = NeutralType.Integer)),
                primaryKey = listOf("id"),
            )),
            functions = mapOf("dmg_nextval" to FunctionDefinition(
                returns = ReturnType("BIGINT"), language = "SQL",
            )),
        )
        val result = generator.generate(schema, helperOpts)
        result.skippedObjects.any { it.code == "E124" && it.name == "dmg_nextval" } shouldBe true
    }

    test("E124: helper_table with trigger name collision emits collision for trigger") {
        val trigName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        val schema = SchemaDefinition(
            name = "TrigCollision", version = "1",
            sequences = mapOf("my_seq" to SequenceDefinition(start = 1)),
            tables = mapOf("invoices" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                    "invoice_number" to ColumnDefinition(
                        type = NeutralType.Integer,
                        default = DefaultValue.SequenceNextVal("my_seq"),
                    ),
                ),
                primaryKey = listOf("id"),
            )),
            triggers = mapOf(trigName to TriggerDefinition(
                table = "invoices", event = TriggerEvent.INSERT, timing = TriggerTiming.BEFORE,
            )),
        )
        val result = generator.generate(schema, helperOpts)
        result.skippedObjects.any { it.code == "E124" && it.name == trigName } shouldBe true
    }

    test("helper_table rollback contains DROP TRIGGER for support triggers") {
        val rollback = generator.generateRollback(seqSchema(), helperOpts)
        val ddl = rollback.render()
        ddl shouldContain "DROP TRIGGER"
        ddl shouldContain "DROP FUNCTION"
    }
})
