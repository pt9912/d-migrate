package dev.dmigrate.format.data

import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.csv.CsvChunkReader
import dev.dmigrate.format.data.json.JsonChunkReader
import dev.dmigrate.format.data.yaml.YamlChunkReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream

class DefaultDataChunkReaderFactoryTest : FunSpec({

    val factory = DefaultDataChunkReaderFactory()
    val chunkSize = 100
    val table = "test_table"

    fun input(text: String) = ByteArrayInputStream(text.toByteArray())

    test("JSON format → JsonChunkReader") {
        factory.create(DataExportFormat.JSON, input("[]"), table, chunkSize)
            .use { it.shouldBeInstanceOf<JsonChunkReader>() }
    }

    test("YAML format → YamlChunkReader") {
        factory.create(DataExportFormat.YAML, input("[]"), table, chunkSize)
            .use { it.shouldBeInstanceOf<YamlChunkReader>() }
    }

    test("CSV format → CsvChunkReader") {
        factory.create(DataExportFormat.CSV, input("id\n"), table, chunkSize)
            .use { it.shouldBeInstanceOf<CsvChunkReader>() }
    }

    test("JSON reader reads minimal input") {
        factory.create(DataExportFormat.JSON, input("""[{"id":1}]"""), table, chunkSize).use { reader ->
            val chunk = reader.nextChunk()!!
            chunk.rows.size shouldBe 1
            reader.nextChunk() shouldBe null
        }
    }

    test("YAML reader reads minimal input") {
        factory.create(DataExportFormat.YAML, input("- {id: 1}\n"), table, chunkSize).use { reader ->
            val chunk = reader.nextChunk()!!
            chunk.rows.size shouldBe 1
            reader.nextChunk() shouldBe null
        }
    }

    test("CSV reader reads minimal input") {
        factory.create(DataExportFormat.CSV, input("id\n1\n"), table, chunkSize).use { reader ->
            val chunk = reader.nextChunk()!!
            chunk.rows.size shouldBe 1
            reader.nextChunk() shouldBe null
        }
    }

    test("chunkSize is propagated to reader") {
        factory.create(
            DataExportFormat.JSON,
            input("""[{"id":1},{"id":2},{"id":3}]"""),
            table,
            chunkSize = 2,
        ).use { reader ->
            reader.nextChunk()!!.rows.size shouldBe 2
            reader.nextChunk()!!.rows.size shouldBe 1
            reader.nextChunk() shouldBe null
        }
    }

    test("rejects non-positive chunkSize") {
        shouldThrow<IllegalArgumentException> {
            factory.create(DataExportFormat.JSON, input("[]"), table, chunkSize = 0)
        }
        shouldThrow<IllegalArgumentException> {
            factory.create(DataExportFormat.JSON, input("[]"), table, chunkSize = -1)
        }
    }

    test("options are propagated to CSV reader") {
        val opts = FormatReadOptions(csvNoHeader = true)
        factory.create(DataExportFormat.CSV, input("1\n"), table, chunkSize, opts).use { reader ->
            reader.headerColumns() shouldBe null
        }
    }

    test("empty JSON array → nextChunk() returns null") {
        factory.create(DataExportFormat.JSON, input("[]"), table, chunkSize).use { reader ->
            reader.nextChunk() shouldBe null
        }
    }

    test("close() without read does not throw") {
        factory.create(DataExportFormat.JSON, input("[]"), table, chunkSize).close()
    }
})
