package dev.dmigrate.format.data

import dev.dmigrate.format.data.csv.CsvChunkWriter
import dev.dmigrate.format.data.json.JsonChunkWriter
import dev.dmigrate.format.data.yaml.YamlChunkWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream

class DefaultDataChunkWriterFactoryTest : FunSpec({

    val factory = DefaultDataChunkWriterFactory()
    val out = ByteArrayOutputStream()
    val opts = ExportOptions()

    test("JSON format → JsonChunkWriter") {
        factory.create(DataExportFormat.JSON, out, opts).shouldBeInstanceOf<JsonChunkWriter>()
    }

    test("YAML format → YamlChunkWriter") {
        factory.create(DataExportFormat.YAML, out, opts).shouldBeInstanceOf<YamlChunkWriter>()
    }

    test("CSV format → CsvChunkWriter") {
        factory.create(DataExportFormat.CSV, out, opts).shouldBeInstanceOf<CsvChunkWriter>()
    }

    test("warningSink is propagated to writers") {
        val warnings = mutableListOf<ValueSerializer.W202>()
        val factoryWithSink = DefaultDataChunkWriterFactory(warningSink = { warnings += it })
        // Smoke-Test: Factory akzeptiert den Sink ohne zu werfen
        val writer = factoryWithSink.create(DataExportFormat.JSON, out, opts)
        writer.close()
        warnings.size shouldBe 0  // Kein W202 ohne unbekannte Klassen
    }
})
