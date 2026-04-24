package dev.dmigrate.core.diff

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class SchemaComparatorTestPart2Part2b : FunSpec({

    val comparator = SchemaComparator()

    // --- Builder helpers ---

    fun schema(
        name: String = "Test",
        version: String = "1.0",
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = name,
        version = version,
        tables = tables,
        customTypes = customTypes,
        views = views,
        sequences = sequences,
        functions = functions,
        procedures = procedures,
        triggers = triggers,
    )

    fun table(
        columns: Map<String, ColumnDefinition> = emptyMap(),
        primaryKey: List<String> = emptyList(),
        indices: List<IndexDefinition> = emptyList(),
        constraints: List<ConstraintDefinition> = emptyList(),
    ) = TableDefinition(
        columns = columns,
        primaryKey = primaryKey,
        indices = indices,
        constraints = constraints,
    )

    fun col(
        type: NeutralType = NeutralType.Text(),
        required: Boolean = false,
        unique: Boolean = false,
        default: DefaultValue? = null,
        references: ReferenceDefinition? = null,
    ) = ColumnDefinition(type = type, required = required, unique = unique,
        default = default, references = references)

    fun enumType(vararg values: String) =
        CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = values.toList())

    fun view(
        materialized: Boolean = false,
        refresh: String? = null,
        query: String? = null,
        sourceDialect: String? = null,
    ) = ViewDefinition(
        materialized = materialized,
        refresh = refresh,
        query = query,
        sourceDialect = sourceDialect,
    )

    // ===========================================
    // 8.1 Pflichtfaelle
    // ===========================================



    test("sequence changed reports increment, min/max, cycle, and cache diffs") {
        val left = schema(sequences = mapOf(
            "id_seq" to SequenceDefinition(
                start = 1,
                increment = 1,
                minValue = 10,
                maxValue = 100,
                cycle = false,
                cache = 5,
            ),
        ))
        val right = schema(sequences = mapOf(
            "id_seq" to SequenceDefinition(
                start = 1,
                increment = 10,
                minValue = 20,
                maxValue = 200,
                cycle = true,
                cache = 25,
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.sequencesAdded.shouldBeEmpty()
        diff.sequencesRemoved.shouldBeEmpty()
        diff.sequencesChanged shouldHaveSize 1

        val changed = diff.sequencesChanged[0]
        changed.name shouldBe "id_seq"
        changed.increment.shouldNotBeNull()
        changed.increment!!.before shouldBe 1L
        changed.increment!!.after shouldBe 10L
        changed.minValue.shouldNotBeNull()
        changed.minValue!!.before shouldBe 10L
        changed.minValue!!.after shouldBe 20L
        changed.maxValue.shouldNotBeNull()
        changed.maxValue!!.before shouldBe 100L
        changed.maxValue!!.after shouldBe 200L
        changed.cycle.shouldNotBeNull()
        changed.cycle!!.before shouldBe false
        changed.cycle!!.after shouldBe true
        changed.cache.shouldNotBeNull()
        changed.cache!!.before shouldBe 5
        changed.cache!!.after shouldBe 25
    }

    test("identical sequences produce no diff") {
        val seq = SequenceDefinition(start = 1, increment = 1)
        val left = schema(sequences = mapOf("s" to seq))
        val right = schema(sequences = mapOf("s" to seq))

        val diff = comparator.compare(left, right)
        diff.sequencesChanged.shouldBeEmpty()
    }

    test("function added and removed") {
        val left = schema(functions = mapOf(
            "old_fn" to FunctionDefinition(body = "RETURN 1", language = "sql"),
        ))
        val right = schema(functions = mapOf(
            "new_fn" to FunctionDefinition(body = "RETURN 2", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        diff.functionsAdded shouldHaveSize 1
        diff.functionsAdded[0].name shouldBe "new_fn"
        diff.functionsRemoved shouldHaveSize 1
        diff.functionsRemoved[0].name shouldBe "old_fn"
    }

    test("function changed - body") {
        val left = schema(functions = mapOf(
            "calc" to FunctionDefinition(body = "RETURN a + b", language = "sql"),
        ))
        val right = schema(functions = mapOf(
            "calc" to FunctionDefinition(body = "RETURN a * b", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        diff.functionsChanged shouldHaveSize 1
        diff.functionsChanged[0].body.shouldNotBeNull()
    }

    test("function changed - parameters") {
        val left = schema(functions = mapOf(
            "calc" to FunctionDefinition(
                parameters = listOf(ParameterDefinition("x", "integer")),
                language = "sql",
            ),
        ))
        val right = schema(functions = mapOf(
            "calc" to FunctionDefinition(
                parameters = listOf(
                    ParameterDefinition("x", "integer"),
                    ParameterDefinition("y", "integer"),
                ),
                language = "sql",
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.functionsChanged shouldHaveSize 1
        diff.functionsChanged[0].parameters.shouldNotBeNull()
    }

    test("procedure added and removed") {
        val left = schema(procedures = mapOf(
            "old_proc" to ProcedureDefinition(body = "DELETE FROM t", language = "sql"),
        ))
        val right = schema(procedures = mapOf(
            "new_proc" to ProcedureDefinition(body = "INSERT INTO t", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        diff.proceduresAdded shouldHaveSize 1
        diff.proceduresAdded[0].name shouldBe "new_proc"
        diff.proceduresRemoved shouldHaveSize 1
        diff.proceduresRemoved[0].name shouldBe "old_proc"
    }

    test("procedure changed - language") {
        val left = schema(procedures = mapOf(
            "proc" to ProcedureDefinition(body = "body", language = "sql"),
        ))
        val right = schema(procedures = mapOf(
            "proc" to ProcedureDefinition(body = "body", language = "plpgsql"),
        ))

        val diff = comparator.compare(left, right)
        diff.proceduresChanged shouldHaveSize 1
        diff.proceduresChanged[0].language.shouldNotBeNull()
    }

    test("trigger added and removed") {
        val left = schema(
            tables = mapOf("users" to table(columns = mapOf("id" to col()))),
            triggers = mapOf(
                "old_trg" to TriggerDefinition(
                    table = "users", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.BEFORE, body = "old body",
                ),
            ),
        )
        val right = schema(
            tables = mapOf("users" to table(columns = mapOf("id" to col()))),
            triggers = mapOf(
                "new_trg" to TriggerDefinition(
                    table = "users", event = TriggerEvent.DELETE,
                    timing = TriggerTiming.AFTER, body = "new body",
                ),
            ),
        )

        val diff = comparator.compare(left, right)
        diff.triggersAdded shouldHaveSize 1
        diff.triggersAdded[0].name shouldBe "new_trg"
        diff.triggersRemoved shouldHaveSize 1
        diff.triggersRemoved[0].name shouldBe "old_trg"
    }

    test("trigger changed - event and timing") {
        val left = schema(
            tables = mapOf("t" to table(columns = mapOf("id" to col()))),
            triggers = mapOf(
                "trg" to TriggerDefinition(
                    table = "t", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.BEFORE,
                ),
            ),
        )
        val right = schema(
            tables = mapOf("t" to table(columns = mapOf("id" to col()))),
            triggers = mapOf(
                "trg" to TriggerDefinition(
                    table = "t", event = TriggerEvent.UPDATE,
                    timing = TriggerTiming.AFTER,
                ),
            ),
        )

        val diff = comparator.compare(left, right)
        diff.triggersChanged shouldHaveSize 1
        diff.triggersChanged[0].event.shouldNotBeNull()
        diff.triggersChanged[0].timing.shouldNotBeNull()
    }

    // ===========================================
    // Phase B: Canonical key identity
    // ===========================================

    test("overloaded functions with canonical keys are tracked separately") {
        val paramsInt = listOf(ParameterDefinition("x", "integer"))
        val paramsText = listOf(ParameterDefinition("x", "text"))
        val keyInt = ObjectKeyCodec.routineKey("calc", paramsInt)
        val keyText = ObjectKeyCodec.routineKey("calc", paramsText)

        val left = schema(functions = mapOf(
            keyInt to FunctionDefinition(parameters = paramsInt, body = "RETURN x + 1", language = "sql"),
            keyText to FunctionDefinition(parameters = paramsText, body = "RETURN x || '!'", language = "sql"),
        ))
        val right = schema(functions = mapOf(
            keyInt to FunctionDefinition(parameters = paramsInt, body = "RETURN x + 2", language = "sql"),
            keyText to FunctionDefinition(parameters = paramsText, body = "RETURN x || '!'", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        // Only the integer overload changed; text overload is identical
        diff.functionsChanged shouldHaveSize 1
        diff.functionsChanged[0].name shouldBe keyInt
        diff.functionsChanged[0].body.shouldNotBeNull()
        diff.functionsAdded.shouldBeEmpty()
        diff.functionsRemoved.shouldBeEmpty()
    }

    test("overloaded function added as new overload") {
        val paramsInt = listOf(ParameterDefinition("x", "integer"))
        val paramsIntInt = listOf(ParameterDefinition("x", "integer"), ParameterDefinition("y", "integer"))
        val keyOne = ObjectKeyCodec.routineKey("calc", paramsInt)
        val keyTwo = ObjectKeyCodec.routineKey("calc", paramsIntInt)

        val left = schema(functions = mapOf(
            keyOne to FunctionDefinition(parameters = paramsInt, body = "RETURN x", language = "sql"),
        ))
        val right = schema(functions = mapOf(
            keyOne to FunctionDefinition(parameters = paramsInt, body = "RETURN x", language = "sql"),
            keyTwo to FunctionDefinition(parameters = paramsIntInt, body = "RETURN x + y", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        diff.functionsAdded shouldHaveSize 1
        diff.functionsAdded[0].name shouldBe keyTwo
        diff.functionsChanged.shouldBeEmpty()
    }

    test("same-named triggers on different tables use canonical keys without collision") {
        val keyUsersAudit = ObjectKeyCodec.triggerKey("users", "audit")
        val keyOrdersAudit = ObjectKeyCodec.triggerKey("orders", "audit")

        val left = schema(
            tables = mapOf(
                "users" to table(columns = mapOf("id" to col())),
                "orders" to table(columns = mapOf("id" to col())),
            ),
            triggers = mapOf(
                keyUsersAudit to TriggerDefinition(
                    table = "users", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER, body = "user audit v1",
                ),
                keyOrdersAudit to TriggerDefinition(
                    table = "orders", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER, body = "order audit",
                ),
            ),
        )
        val right = schema(
            tables = mapOf(
                "users" to table(columns = mapOf("id" to col())),
                "orders" to table(columns = mapOf("id" to col())),
            ),
            triggers = mapOf(
                keyUsersAudit to TriggerDefinition(
                    table = "users", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER, body = "user audit v2",
                ),
                keyOrdersAudit to TriggerDefinition(
                    table = "orders", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER, body = "order audit",
                ),
            ),
        )

        val diff = comparator.compare(left, right)
        // Only the users trigger changed; orders trigger is identical
        diff.triggersChanged shouldHaveSize 1
        diff.triggersChanged[0].name shouldBe keyUsersAudit
        diff.triggersChanged[0].body.shouldNotBeNull()
        diff.triggersAdded.shouldBeEmpty()
        diff.triggersRemoved.shouldBeEmpty()
    }

    // ===========================================
    // Phase B: Table metadata
    // ===========================================

})
