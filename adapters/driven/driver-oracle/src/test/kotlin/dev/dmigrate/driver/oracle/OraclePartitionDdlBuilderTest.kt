package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.TransformationNote
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class OraclePartitionDdlBuilderTest : FunSpec({

    val builder = OraclePartitionDdlBuilder { "\"$it\"" }

    fun render(
        partitioning: PartitionConfig,
        columns: Map<String, ColumnDefinition> = mapOf("d" to ColumnDefinition(NeutralType.Date)),
    ): Pair<String, List<TransformationNote>> {
        val notes = mutableListOf<TransformationNote>()
        return builder.clause("t", partitioning, columns, notes) to notes
    }

    test("a RANGE clause renders one VALUES LESS THAN per partition, MAXVALUE included") {
        val (sql, notes) = render(
            PartitionConfig(
                type = PartitionType.RANGE,
                key = listOf("d"),
                partitions = listOf(
                    PartitionDefinition(name = "p1", to = listOf(PartitionBound.Value("'2024-01-01'"))),
                    PartitionDefinition(name = "pmax", to = listOf(PartitionBound.MaxValue)),
                ),
            ),
        )
        sql shouldContain "PARTITION BY RANGE (\"d\") ("
        // Der Datumswert wird in eine explizite Maske gesetzt; blank haengt er
        // an NLS_DATE_FORMAT (live belegt: ORA-01861).
        sql shouldContain "PARTITION \"p1\" VALUES LESS THAN (TO_DATE('2024-01-01', 'YYYY-MM-DD'))"
        sql shouldContain "PARTITION \"pmax\" VALUES LESS THAN (MAXVALUE)"
        notes.shouldBeEmptyNotes()
    }

    test("a LIST clause carries the DEFAULT partition natively") {
        val (sql, notes) = render(
            PartitionConfig(
                type = PartitionType.LIST,
                key = listOf("st"),
                partitions = listOf(
                    PartitionDefinition(name = "l_ab", values = listOf("'A'", "'B'")),
                    PartitionDefinition(name = "l_rest", isDefault = true),
                ),
            ),
            columns = mapOf("st" to ColumnDefinition(NeutralType.Text(maxLength = 10))),
        )
        sql shouldContain "PARTITION \"l_ab\" VALUES ('A', 'B')"
        // MySQL muss diese Partition verwerfen (E063); Oracle kann sie.
        sql shouldContain "PARTITION \"l_rest\" VALUES (DEFAULT)"
        notes.shouldBeEmptyNotes()
    }

    test("a DEFAULT partition in a RANGE scheme becomes MAXVALUE, not an empty bound list") {
        // Ohne diesen Zweig entstuende `VALUES LESS THAN ()` -- ORA-14019.
        // Erreichbar, nicht theoretisch: PostgreSQL setzt `isDefault` vor der
        // Strategie-Fallunterscheidung, ein RANGE-Schema mit Catch-all ist
        // also eine gewoehnliche neutrale Form. MySQL loest sie identisch.
        val (sql, _) = render(
            PartitionConfig(
                type = PartitionType.RANGE,
                key = listOf("d"),
                partitions = listOf(
                    PartitionDefinition(name = "p1", to = listOf(PartitionBound.Value("'2024-01-01'"))),
                    PartitionDefinition(name = "p_rest", isDefault = true),
                ),
            ),
        )
        sql shouldContain "PARTITION \"p_rest\" VALUES LESS THAN (MAXVALUE)"
        sql shouldNotContain "VALUES LESS THAN ()"
    }

    test("a HASH clause keeps names but reports that placement differs") {
        val (sql, notes) = render(
            PartitionConfig(
                type = PartitionType.HASH,
                key = listOf("id"),
                partitions = listOf(
                    PartitionDefinition(name = "h0", modulus = 2, remainder = 0),
                    PartitionDefinition(name = "h1", modulus = 2, remainder = 1),
                ),
            ),
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
        )
        sql shouldContain "PARTITION BY HASH (\"id\") ("
        sql shouldContain "PARTITION \"h0\""
        notes.single { it.code == "W130" }.message shouldContain "own hash function"
    }

    test("lower bounds are reported only when the model actually carries them") {
        val withFrom = render(
            PartitionConfig(
                type = PartitionType.RANGE,
                key = listOf("d"),
                partitions = listOf(
                    PartitionDefinition(
                        name = "p1",
                        from = listOf(PartitionBound.MinValue),
                        to = listOf(PartitionBound.Value("'2024-01-01'")),
                    ),
                ),
            ),
        ).second
        withFrom.any { it.code == "W112" } shouldBe true

        // Eine aus einem Oracle-Reverse stammende Konfiguration traegt keine
        // `from`-Grenzen -- dann gibt es nichts zu melden, sonst warnte jeder
        // Oracle-Round-Trip vor einem Verlust, der nicht stattfindet.
        val withoutFrom = render(
            PartitionConfig(
                type = PartitionType.RANGE,
                key = listOf("d"),
                partitions = listOf(
                    PartitionDefinition(name = "p1", to = listOf(PartitionBound.Value("'2024-01-01'"))),
                ),
            ),
        ).second
        withoutFrom.any { it.code == "W112" } shouldBe false
    }

    test("shapes Oracle cannot express are refused, not bent into something valid") {
        val cases = listOf(
            "keine Partitionen" to PartitionConfig(PartitionType.RANGE, listOf("d")),
            "ohne obere Grenze" to PartitionConfig(
                PartitionType.RANGE, listOf("d"), listOf(PartitionDefinition(name = "p1")),
            ),
            // MINVALUE als OBERE Grenze als MAXVALUE zu rendern kehrte die
            // Bedeutung um: aus „nichts faellt hinein" wuerde „alles".
            "MINVALUE als obere Grenze" to PartitionConfig(
                PartitionType.RANGE, listOf("d"),
                listOf(PartitionDefinition(name = "p1", to = listOf(PartitionBound.MinValue))),
            ),
        )
        cases.forEach { (label, config) ->
            val (sql, notes) = render(config)
            withClueLabel(label) {
                sql shouldBe ""
                notes.single().code shouldBe "E055"
            }
        }
    }

    test("a bound that could break out of its clause is rejected outright") {
        shouldThrow<IllegalArgumentException> {
            render(
                PartitionConfig(
                    type = PartitionType.RANGE,
                    key = listOf("d"),
                    partitions = listOf(
                        PartitionDefinition(name = "p1", to = listOf(PartitionBound.Value("'x'); DROP TABLE t--"))),
                    ),
                ),
            )
        }
    }
})

private fun List<TransformationNote>.shouldBeEmptyNotes() {
    // Wirklich leer, nicht „leer bis auf W112": sonst faengt der Helfer
    // gerade die Notiz nicht, die hier faelschlich stehen koennte.
    map { it.code } shouldBe emptyList()
}

private fun withClueLabel(label: String, block: () -> Unit) {
    io.kotest.assertions.withClue(label, block)
}
