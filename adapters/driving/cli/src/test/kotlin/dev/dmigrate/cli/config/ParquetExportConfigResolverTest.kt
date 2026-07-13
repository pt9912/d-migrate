package dev.dmigrate.cli.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class ParquetExportConfigResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-parquet-cfg-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    fun resolverFor(file: Path) = ParquetExportConfigResolver(configPathFromCli = file)

    test("no config file — returns null (built-in default applies)") {
        ParquetExportConfigResolver(
            defaultConfigPath = Path.of("/tmp/does-not-exist-${System.nanoTime()}.yaml"),
            envLookup = { null },
        ).resolveRowGroupBytes() shouldBe null
    }

    test("export.parquet.row_group_bytes present — parsed") {
        val file = tempConfig(
            """
            export:
              parquet:
                row_group_bytes: 8388608
            """.trimIndent()
        )
        resolverFor(file).resolveRowGroupBytes() shouldBe 8_388_608L
    }

    test("export block without parquet.row_group_bytes — returns null") {
        val file = tempConfig("export:\n  csv:\n    delimiter: \";\"\n")
        resolverFor(file).resolveRowGroupBytes() shouldBe null
    }

    test("non-positive row_group_bytes — throws ConfigResolveException") {
        val file = tempConfig("export:\n  parquet:\n    row_group_bytes: 0\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolveRowGroupBytes() }
        ex.message shouldContain "row_group_bytes"
        ex.message shouldContain "> 0"
    }

    test("non-numeric row_group_bytes — throws ConfigResolveException") {
        val file = tempConfig("export:\n  parquet:\n    row_group_bytes: \"big\"\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolveRowGroupBytes() }
        ex.message shouldContain "row_group_bytes"
        ex.message shouldContain "positive integer"
    }

    test("fractional row_group_bytes is rejected, not silently coerced (#4)") {
        val file = tempConfig("export:\n  parquet:\n    row_group_bytes: 1048576.5\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolveRowGroupBytes() }
        ex.message shouldContain "positive integer"
    }
})
