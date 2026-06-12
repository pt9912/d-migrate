package dev.dmigrate.server.adapter.storage.s3

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

private fun yamlFile(content: String): Path =
    Files.createTempFile("dmg-artifacts-", ".yaml").also { Files.writeString(it, content.trimIndent()) }

class ArtifactsConfigLoaderTest : FunSpec({

    test("null config path -> File (Default)") {
        ArtifactsConfigLoader.load(null) shouldBe ArtifactStorageConfig.File
    }

    test("non-existent config path -> File") {
        ArtifactsConfigLoader.load(Path.of("/does/not/exist.yaml")) shouldBe ArtifactStorageConfig.File
    }

    test("config without artifacts section -> File") {
        val path = yamlFile(
            """
            database:
              connections:
                pg: "postgresql://localhost/db"
            """,
        )
        ArtifactsConfigLoader.load(path) shouldBe ArtifactStorageConfig.File
    }

    test("artifacts.store=file -> File") {
        ArtifactsConfigLoader.load(yamlFile("artifacts:\n  store: file")) shouldBe ArtifactStorageConfig.File
    }

    test("artifacts.store=s3 with full block -> S3 with all fields") {
        val path = yamlFile(
            """
            artifacts:
              store: s3
              s3:
                endpoint: "https://s3.example.com"
                bucket: "d-migrate-artifacts"
                region: "eu-central-1"
                prefix: "prod/"
                pathStyle: false
            """,
        )
        val s3 = ArtifactsConfigLoader.load(path).shouldBeInstanceOf<ArtifactStorageConfig.S3>().config
        s3.bucket shouldBe "d-migrate-artifacts"
        s3.region shouldBe "eu-central-1"
        s3.endpoint shouldBe URI.create("https://s3.example.com")
        s3.keyPrefix shouldBe "prod/"
        s3.pathStyle shouldBe false
        s3.accessKey shouldBe null
        s3.secretKey shouldBe null
    }

    test("artifacts.store=s3 with only bucket -> S3 with defaults") {
        val path = yamlFile(
            """
            artifacts:
              store: s3
              s3:
                bucket: "only-bucket"
            """,
        )
        val s3 = ArtifactsConfigLoader.load(path).shouldBeInstanceOf<ArtifactStorageConfig.S3>().config
        s3.bucket shouldBe "only-bucket"
        s3.region shouldBe "us-east-1"
        s3.endpoint shouldBe null
        s3.keyPrefix shouldBe ""
        s3.pathStyle shouldBe true
    }

    test("artifacts.store=s3 without s3 block -> error") {
        shouldThrow<ArtifactsConfigException> {
            ArtifactsConfigLoader.load(yamlFile("artifacts:\n  store: s3"))
        }
    }

    test("artifacts.store=s3 without bucket -> error") {
        val path = yamlFile(
            """
            artifacts:
              store: s3
              s3:
                region: "eu-central-1"
            """,
        )
        shouldThrow<ArtifactsConfigException> { ArtifactsConfigLoader.load(path) }
    }

    test("unknown artifacts.store value -> error") {
        shouldThrow<ArtifactsConfigException> {
            ArtifactsConfigLoader.load(yamlFile("artifacts:\n  store: gcs"))
        }
    }

    test("invalid s3 endpoint URI -> ArtifactsConfigException (not raw IllegalArgumentException)") {
        val path = yamlFile(
            """
            artifacts:
              store: s3
              s3:
                bucket: "b"
                endpoint: "ht tp://broken"
            """,
        )
        shouldThrow<ArtifactsConfigException> { ArtifactsConfigLoader.load(path) }
    }

    test("non-boolean pathStyle (quoted string) -> error, not silent default") {
        val path = yamlFile(
            """
            artifacts:
              store: s3
              s3:
                bucket: "b"
                pathStyle: "false"
            """,
        )
        shouldThrow<ArtifactsConfigException> { ArtifactsConfigLoader.load(path) }
    }

    test("artifacts.s3 block present but store is not s3 -> error (foot-gun guard)") {
        val withFile = yamlFile(
            """
            artifacts:
              store: file
              s3:
                bucket: "b"
            """,
        )
        shouldThrow<ArtifactsConfigException> { ArtifactsConfigLoader.load(withFile) }

        val storeAbsent = yamlFile(
            """
            artifacts:
              s3:
                bucket: "b"
            """,
        )
        shouldThrow<ArtifactsConfigException> { ArtifactsConfigLoader.load(storeAbsent) }
    }

    test("scalar / empty YAML root -> File") {
        ArtifactsConfigLoader.load(yamlFile("just-a-scalar")) shouldBe ArtifactStorageConfig.File
        ArtifactsConfigLoader.load(yamlFile("")) shouldBe ArtifactStorageConfig.File
    }

    test("malformed YAML -> ArtifactsConfigException (not raw snakeyaml exception)") {
        // Dieser Loader laeuft als erster Parser im `mcp serve`-Startup —
        // ein YAML-Syntaxfehler muss als Config-Fehler enden (Runner: Exit 2),
        // nicht als ungefangener snakeyaml-Stacktrace (S3.4b-R1).
        val path = yamlFile("artifacts: [unclosed")
        val failure = shouldThrow<ArtifactsConfigException> { ArtifactsConfigLoader.load(path) }
        failure.message shouldContain "failed to parse artifacts config"
    }
})
