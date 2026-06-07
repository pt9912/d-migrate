package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.assertions.throwables.shouldThrow
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class CompositeDataChunkWriterFactoryTest : FunSpec({

    class FakeChunkWriter(val tag: String) : DataChunkWriter {
        override fun begin(table: String, schema: dev.dmigrate.format.data.ChunkSchema) = Unit
        override fun write(chunk: dev.dmigrate.core.data.DataChunk) = Unit
        override fun end() = Unit
        override fun close() = Unit
    }

    class RecordingFactory(private val tag: String) : DataChunkWriterFactory {
        val calls = mutableListOf<DataExportFormat>()
        override fun create(
            format: DataExportFormat,
            output: OutputStream,
            options: ExportOptions,
        ): DataChunkWriter {
            calls += format
            return FakeChunkWriter(tag)
        }
    }

    fun composite(
        default: DataChunkWriterFactory = RecordingFactory("default"),
        parquet: DataChunkWriterFactory = RecordingFactory("parquet"),
    ) = CompositeDataChunkWriterFactory(default, parquet)

    test("PARQUET routes to the parquet factory") {
        val default = RecordingFactory("default")
        val parquet = RecordingFactory("parquet")
        val factory = composite(default, parquet)

        val writer = factory.create(DataExportFormat.PARQUET, ByteArrayOutputStream(), ExportOptions())

        (writer as FakeChunkWriter).tag shouldBe "parquet"
        parquet.calls shouldBe listOf(DataExportFormat.PARQUET)
        default.calls shouldBe emptyList()
    }

    test("JSON, YAML, CSV all route to the default factory") {
        val default = RecordingFactory("default")
        val parquet = RecordingFactory("parquet")
        val factory = composite(default, parquet)

        listOf(DataExportFormat.JSON, DataExportFormat.YAML, DataExportFormat.CSV).forEach { format ->
            val writer = factory.create(format, ByteArrayOutputStream(), ExportOptions())
            (writer as FakeChunkWriter).tag shouldBe "default"
        }

        default.calls shouldBe listOf(DataExportFormat.JSON, DataExportFormat.YAML, DataExportFormat.CSV)
        parquet.calls shouldBe emptyList()
    }

    test("Composite forwards the output and options instance to the selected factory") {
        val capturedOutputs = mutableListOf<OutputStream>()
        val capturedOptions = mutableListOf<ExportOptions>()
        val recording = object : DataChunkWriterFactory {
            override fun create(
                format: DataExportFormat,
                output: OutputStream,
                options: ExportOptions,
            ): DataChunkWriter {
                capturedOutputs += output
                capturedOptions += options
                return FakeChunkWriter("recording")
            }
        }
        val factory = composite(default = recording, parquet = RecordingFactory("parquet"))
        val out = ByteArrayOutputStream()
        val opts = ExportOptions()

        factory.create(DataExportFormat.JSON, out, opts)

        capturedOutputs.single() shouldBeSameInstanceAs out
        capturedOptions.single() shouldBeSameInstanceAs opts
    }

    test("Parquet factory's require remains active when wired through the composite") {
        // ParquetChunkWriterFactory.require(format == PARQUET) sits behind the
        // composite — i.e. it is unreachable for non-Parquet formats because the
        // composite routes them to the default factory before the Parquet require
        // can fire. Here we verify the require still fires for direct calls so the
        // contract-branch is not silently bypassed (AP12 §5.2).
        val parquet = dev.dmigrate.format.parquet.ParquetChunkWriterFactory()
        val ex = shouldThrow<IllegalArgumentException> {
            parquet.create(DataExportFormat.JSON, ByteArrayOutputStream(), ExportOptions())
        }
        ex.message!! shouldContain "ParquetChunkWriterFactory does not support format=JSON"
    }
})
