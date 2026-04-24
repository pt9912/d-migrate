package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class MysqlSequenceSupportTest : FunSpec({

    val support = MysqlSequenceSupport()
    val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")

    fun snapshot(
        supportTableState: SupportTableState = SupportTableState.AVAILABLE,
        routineStates: Map<String, SupportRoutineState> = emptyMap(),
    ) = MysqlSequenceSupportSnapshot(
        scope = scope,
        supportTableState = supportTableState,
        sequenceRows = emptyList(),
        ambiguousKeys = emptySet(),
        routineStates = routineStates,
        triggerStates = emptyMap(),
        triggerAssessments = emptyList(),
        diagnostics = emptyList(),
    )

    test("filterSupportRoutines removes only confirmed support routines") {
        val functions = linkedMapOf(
            "${MysqlSequenceNaming.NEXTVAL_ROUTINE}(VARCHAR(255))" to FunctionDefinition(
                returns = ReturnType("BIGINT"),
                language = "SQL",
            ),
            "${MysqlSequenceNaming.SETVAL_ROUTINE}(VARCHAR(255),BIGINT)" to FunctionDefinition(
                returns = ReturnType("BIGINT"),
                language = "SQL",
            ),
            "dmg_nextval_user()" to FunctionDefinition(
                returns = ReturnType("BIGINT"),
                language = "SQL",
            ),
        )

        val filtered = support.filterSupportRoutines(
            functions,
            snapshot(
                routineStates = linkedMapOf(
                    MysqlSequenceNaming.NEXTVAL_ROUTINE to SupportRoutineState.CONFIRMED,
                    MysqlSequenceNaming.SETVAL_ROUTINE to SupportRoutineState.NON_CANONICAL,
                ),
            ),
        )

        filtered.keys.toList() shouldContainExactly listOf(
            "${MysqlSequenceNaming.SETVAL_ROUTINE}(VARCHAR(255),BIGINT)",
            "dmg_nextval_user()",
        )
    }

    test("filterSupportTable strips dmg_sequences only when support table is available") {
        val tables = linkedMapOf(
            "users" to TableDefinition(),
            MysqlSequenceNaming.SUPPORT_TABLE to TableDefinition(),
        )

        val filteredAvailable = support.filterSupportTable(
            tables,
            snapshot(supportTableState = SupportTableState.AVAILABLE),
        )
        filteredAvailable.keys.toList() shouldContainExactly listOf("users")

        val filteredInvalidShape = support.filterSupportTable(
            tables,
            snapshot(supportTableState = SupportTableState.INVALID_SHAPE),
        )
        filteredInvalidShape.keys.toList() shouldContainExactly listOf(
            "users",
            MysqlSequenceNaming.SUPPORT_TABLE,
        )
        filteredInvalidShape[MysqlSequenceNaming.SUPPORT_TABLE] shouldBe TableDefinition()
    }
})
