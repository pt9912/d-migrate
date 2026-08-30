package dev.dmigrate.cli.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class DdlConfigResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-ddl-test-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    fun resolverFor(file: Path) = DdlConfigResolver(configPathFromCli = file)

    test("no config file — empty block, no default baked in") {
        val config = DdlConfigResolver(
            defaultConfigPath = Path.of("/tmp/does-not-exist-${System.nanoTime()}.yaml"),
            envLookup = { null },
        ).resolve()
        config shouldBe DdlConfig(mssqlPartitionStorage = null)
    }

    test("ddl.mssql.partition_storage is read") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                partition_storage: FG_DATA
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe DdlConfig(mssqlPartitionStorage = "FG_DATA")
    }

    test("the mysql sub-block is read alongside the mssql one") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                partition_storage: FG_DATA
              mysql:
                engine: MyISAM
                charset: latin1
                collation: latin1_german2_ci
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe DdlConfig(
            mssqlPartitionStorage = "FG_DATA",
            mysqlEngine = "MyISAM",
            mysqlCharset = "latin1",
            mysqlCollation = "latin1_german2_ci",
        )
    }

    // Der Leser stieg frueher aus, sobald der mssql-Block fehlte — und haette
    // damit jeden weiteren Dialektblock mitgenommen.
    test("a missing mssql block does not swallow the mysql one") {
        val file = tempConfig(
            """
            ddl:
              mysql:
                engine: MyISAM
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe DdlConfig(mysqlEngine = "MyISAM")
    }

    // Engine, Zeichensatz und Kollation gehen unquotiert in `CREATE TABLE`.
    test("a mysql value that is not an identifier fails loudly") {
        val file = tempConfig(
            """
            ddl:
              mysql:
                engine: "InnoDB; DROP TABLE users"
            """.trimIndent()
        )
        shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
    }

    test("ddl block without the mssql sub-block — null, not an error") {
        val file = tempConfig(
            """
            ddl:
              postgresql:
                something_the_reader_does_not_know: 1
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe DdlConfig(mssqlPartitionStorage = null)
    }

    test("unknown key inside the mssql block stays inconsequential") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                partition_storage: FG_DATA
                not_implemented_yet: whatever
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe DdlConfig(mssqlPartitionStorage = "FG_DATA")
    }

    test("empty value fails loudly instead of falling back to PRIMARY") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                partition_storage: "  "
            """.trimIndent()
        )
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message!! shouldContain "must be a non-empty string"
    }

    test("non-string value fails loudly (no toString() coercion)") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                partition_storage: 42
            """.trimIndent()
        )
        shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
    }

    // Der Wert geht unquotiert in `CREATE PARTITION SCHEME … TO ([<wert>])`.
    test("a value that could break out of the filegroup bracket is rejected") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                partition_storage: "PRIMARY]); DROP TABLE [orders"
            """.trimIndent()
        )
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message!! shouldContain "plain identifier"
    }

    test("ddl.mssql.hash_partitions is read") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                hash_partitions: computed_column
            """.trimIndent()
        )
        resolverFor(file).resolve().mssqlHashPartitions shouldBe "computed_column"
    }

    // Beim CLI-Flag erzwingt Clikt die Auswahl; hier kommt beliebiger Text an.
    // Ein Tippfehler darf nicht still auf den Default fallen — sonst glaubte
    // der Anwender, die Emulation sei eingeschaltet.
    test("a typo in hash_partitions fails loudly instead of silently defaulting") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                hash_partitions: computed_colum
            """.trimIndent()
        )
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message!! shouldContain "must be one of"
    }

    test("hash_partitions precedence: CLI beats config") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                hash_partitions: computed_column
            """.trimIndent()
        )
        resolveEffectiveHashPartitions(file, cliValue = "action_required") shouldBe "action_required"
        resolveEffectiveHashPartitions(file, cliValue = null) shouldBe "computed_column"
        resolveEffectiveHashPartitions(
            configPath = null,
            cliValue = null,
            preloaded = LoadedConfig(root = null, path = Path.of(".d-migrate.yaml")),
        ) shouldBe null
    }

    test("precedence: CLI beats config, config beats default") {
        val file = tempConfig(
            """
            ddl:
              mssql:
                partition_storage: FG_FROM_FILE
            """.trimIndent()
        )
        resolveEffectivePartitionStorage(file, cliValue = "FG_FROM_CLI") shouldBe "FG_FROM_CLI"
        resolveEffectivePartitionStorage(file, cliValue = null) shouldBe "FG_FROM_FILE"
        // Weder CLI noch Datei: der eingebaute Default traegt. `preloaded` mit
        // leerer Wurzel steht fuer „keine Konfigurationsdatei" — ein nicht
        // existierender *expliziter* Pfad waere ein Fehler, kein Default-Fall.
        resolveEffectivePartitionStorage(
            configPath = null,
            cliValue = null,
            preloaded = LoadedConfig(root = null, path = Path.of(".d-migrate.yaml")),
        ) shouldBe "PRIMARY"
    }
})
