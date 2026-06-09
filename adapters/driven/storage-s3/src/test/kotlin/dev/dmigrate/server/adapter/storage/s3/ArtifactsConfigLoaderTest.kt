package dev.dmigrate.server.adapter.storage.s3

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
})
