package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe

/**
 * 0.9.7 SQLite-Sequence Phase D unit coverage for
 * [SqliteSequenceReverseSupport] — tests against hand-built
 * snapshots so the matching/diagnostic logic can be exercised
 * without going through `JdbcMetadataSession`. The DB-side
 * integration is covered by `SqliteSequenceRoundTripIntegrationTest`
 * in `test/integration-sqlite/`.
 */
class SqliteSequenceReverseSupportTest : FunSpec({

    val support = SqliteSequenceReverseSupport()

    fun row(
        name: String = "order_seq",
        nextValue: Long = 1000,
        increment: Long = 1,
        minValue: Long? = null,
        maxValue: Long? = null,
        cycle: Int = 0,
        cache: Int? = null,
    ): Map<String, Any?> = mapOf(
        "managed_by" to "d-migrate",
        "format_version" to "sqlite-sequence-v1",
        "name" to name,
        "next_value" to nextValue,
        "last_returned_value" to null,
        "exhausted" to 0,
        "increment_by" to increment,
        "min_value" to minValue,
        "max_value" to maxValue,
        "cycle_enabled" to cycle,
        "cache_size" to cache,
    )

    fun biTrigger(name: String, table: String, body: String, rowid: Long = 100): TriggerObservation =
        TriggerObservation(
            name = name,
            table = table,
            rowid = rowid,
            sql = body,
            marker = SqliteSequenceMarkerParser.parse(body),
            classification = when {
                SqliteSequenceMarkerParser.parse(body) != null -> TriggerClassification.PRIMARY_MATCH
                SqliteSequenceNaming.isCanonicalSupportTriggerName(name) ->
                    TriggerClassification.SECONDARY_CANDIDATE
                else -> TriggerClassification.USER_DEFINED
            },
        )

    fun makeCanonicalBody(
        table: String,
        column: String,
        sequence: String,
        ofType: SqliteSequenceMarkerParser.ObjectType,
    ): String {
        val obj = when (ofType) {
            SqliteSequenceMarkerParser.ObjectType.BEFORE_INSERT -> "sequence-trigger"
            SqliteSequenceMarkerParser.ObjectType.AFTER_INSERT -> "sequence-trigger-post"
        }
        val tail = when (ofType) {
            SqliteSequenceMarkerParser.ObjectType.BEFORE_INSERT ->
                "UPDATE \"dmg_sequences\" SET \"next_value\" = … WHERE \"name\" = '$sequence';"
            SqliteSequenceMarkerParser.ObjectType.AFTER_INSERT ->
                "UPDATE \"$table\" SET \"$column\" = … WHERE ROWID = NEW.ROWID;"
        }
        return """
            CREATE TRIGGER "x" BEFORE INSERT ON "$table"
            FOR EACH ROW WHEN NEW."$column" IS NULL BEGIN
                /* d-migrate:sqlite-sequence-v1 object=$obj sequence=$sequence table=$table column=$column */
                $tail
            END;
        """.trimIndent()
    }

    fun canonicalPair(table: String, column: String, sequence: String): List<TriggerObservation> {
        val biName = SqliteSequenceNaming.beforeInsertTriggerName(table, column, sequence)
        val aiName = SqliteSequenceNaming.afterInsertTriggerName(table, column, sequence)
        val biBody = makeCanonicalBody(table, column, sequence, SqliteSequenceMarkerParser.ObjectType.BEFORE_INSERT)
        val aiBody = makeCanonicalBody(table, column, sequence, SqliteSequenceMarkerParser.ObjectType.AFTER_INSERT)
        return listOf(
            biTrigger(biName, table, biBody),
            biTrigger(aiName, table, aiBody, rowid = 101),
        )
    }

    // ── materializeSequences ───────────────────────────────────────

    test("materializeSequences reads canonical rows into SequenceDefinition") {
        val snapshot = SqliteSequenceSupportSnapshot(
            supportTableState = SupportTableState.AVAILABLE,
            rows = listOf(
                row(name = "order_seq", nextValue = 1000, increment = 2, maxValue = 9999, cycle = 1, cache = 20),
                row(name = "plain_seq"),
            ),
        )
        val sequences = support.materializeSequences(snapshot)
        sequences.shouldContainKey("order_seq")
        sequences["order_seq"]!!.start shouldBe 1000L
        sequences["order_seq"]!!.increment shouldBe 2L
        sequences["order_seq"]!!.maxValue shouldBe 9999L
        sequences["order_seq"]!!.cycle shouldBe true
        sequences["order_seq"]!!.cache shouldBe 20
        sequences["plain_seq"]!!.start shouldBe 1000L
        sequences["plain_seq"]!!.cycle shouldBe false
    }

    test("materializeSequences returns empty when support table is absent") {
        support.materializeSequences(
            SqliteSequenceSupportSnapshot.absent(SupportTableState.NOT_FOUND),
        ).size shouldBe 0
    }

    // ── materializeSequenceDefaults ─────────────────────────────────

    test("materializeSequenceDefaults enriches column defaults from trigger pairs") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "order_number" to ColumnDefinition(NeutralType.BigInteger),
            ),
            primaryKey = listOf("id"),
        )
        val triggers = canonicalPair("orders", "order_number", "order_seq")
        val snapshot = SqliteSequenceSupportSnapshot(
            supportTableState = SupportTableState.AVAILABLE,
            rows = listOf(row(name = "order_seq")),
            triggers = triggers,
            pairings = listOf(
                TriggerPairing(
                    tableName = "orders",
                    columnName = "order_number",
                    sequenceName = "order_seq",
                    affectedTriggerNames = triggers.map { it.name },
                    diagnostic = PairingDiagnostic.NONE,
                ),
            ),
        )
        val enriched = support.materializeSequenceDefaults(snapshot, mapOf("orders" to table))
        val column = enriched["orders"]!!.columns["order_number"]!!
        column.default shouldBe DefaultValue.SequenceNextVal("order_seq")
    }

    // ── filterSupportTable / filterSupportTriggers ─────────────────

    test("filterSupportTable drops dmg_sequences from the user-table map") {
        val tables = mapOf(
            "dmg_sequences" to TableDefinition(columns = linkedMapOf()),
            "orders" to TableDefinition(columns = linkedMapOf()),
        )
        val snapshot = SqliteSequenceSupportSnapshot(supportTableState = SupportTableState.AVAILABLE)
        val filtered = support.filterSupportTable(tables, snapshot)
        filtered.shouldContainKey("orders")
        filtered.shouldNotContainKey("dmg_sequences")
    }

    test("filterSupportTriggers strips canonical-pair triggers but keeps user triggers") {
        val triggers = canonicalPair("orders", "order_number", "order_seq")
        val biKey = ObjectKeyCodec.triggerKey("orders", triggers[0].name)
        val aiKey = ObjectKeyCodec.triggerKey("orders", triggers[1].name)
        val userKey = ObjectKeyCodec.triggerKey("orders", "user_audit")
        val triggerMap = mapOf(
            biKey to TriggerDefinition(table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.BEFORE),
            aiKey to TriggerDefinition(table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER),
            userKey to TriggerDefinition(table = "orders", event = TriggerEvent.UPDATE, timing = TriggerTiming.AFTER),
        )
        val snapshot = SqliteSequenceSupportSnapshot(
            supportTableState = SupportTableState.AVAILABLE,
            triggers = triggers,
        )
        val filtered = support.filterSupportTriggers(triggerMap, snapshot)
        filtered.keys shouldContainExactly setOf(userKey)
    }

    // ── aggregateNotes ─────────────────────────────────────────────

    test("aggregateNotes emits W116 when support table shape is invalid") {
        val notes = support.aggregateNotes(
            SqliteSequenceSupportSnapshot.absent(SupportTableState.INVALID_SHAPE),
        )
        notes shouldHaveSize 1
        notes[0].code shouldBe "W116"
        notes[0].severity shouldBe SchemaReadSeverity.WARNING
    }

    test("aggregateNotes emits W116 for secondary-match diagnostic") {
        val snapshot = SqliteSequenceSupportSnapshot(
            supportTableState = SupportTableState.AVAILABLE,
            pairings = listOf(
                TriggerPairing(
                    tableName = "orders",
                    columnName = "order_number",
                    sequenceName = "order_seq",
                    affectedTriggerNames = listOf("a", "b"),
                    diagnostic = PairingDiagnostic.SECONDARY_MATCH_DEGRADED,
                ),
            ),
        )
        support.aggregateNotes(snapshot).any { it.code == "W116" } shouldBe true
    }

    test("aggregateNotes emits W120 for body-modified diagnostic") {
        val snapshot = SqliteSequenceSupportSnapshot(
            supportTableState = SupportTableState.AVAILABLE,
            pairings = listOf(
                TriggerPairing(
                    tableName = "orders",
                    columnName = "order_number",
                    sequenceName = "order_seq",
                    affectedTriggerNames = listOf("bi", "ai"),
                    diagnostic = PairingDiagnostic.BODY_MODIFIED,
                ),
            ),
        )
        support.aggregateNotes(snapshot).any { it.code == "W120" } shouldBe true
    }

    test("aggregateNotes emits W124 when user trigger created before support trigger") {
        val snapshot = SqliteSequenceSupportSnapshot(
            supportTableState = SupportTableState.AVAILABLE,
            pairings = listOf(
                TriggerPairing(
                    tableName = "orders",
                    columnName = "order_number",
                    sequenceName = "order_seq",
                    affectedTriggerNames = listOf("bi", "ai"),
                    diagnostic = PairingDiagnostic.MASKED_BY_USER_TRIGGER,
                ),
            ),
        )
        support.aggregateNotes(snapshot).any { it.code == "W124" } shouldBe true
    }

    test("aggregateNotes is silent when support table is genuinely absent") {
        support.aggregateNotes(
            SqliteSequenceSupportSnapshot.absent(SupportTableState.NOT_FOUND),
        ) shouldBe emptyList<SchemaReadNote>()
    }
})
