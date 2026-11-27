package dev.dmigrate.mcp.schema

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * `additionalProperties=false` wird durchgesetzt: Was das Eingabeschema nicht nennt, weist der Server ab, auch
 * wenn der Handler es lesen wuerde. So war `uploadIntent=job_input` ueber die Leitung unerreichbar (Issue #87).
 */
class ArtifactUploadInitSchemaTest : FunSpec({

    val input = McpToolSchemas.forTool("artifact_upload_init")!!.inputSchema

    fun properties() = input["properties"] as Map<*, *>

    test("das Eingabeschema nennt jedes Feld, das der Handler fuer beide Intents liest") {
        properties().keys.filterIsInstance<String>().shouldContainAll(
            "uploadIntent", "expectedSizeBytes", "sizeBytes", "checksumSha256", "filename",
            "approvalKey", "approvalToken", "mimeType", "artifactKind", "targetTable", "bundleFormat", "tables",
        )
    }

    test("uploadIntent laesst beide Intents zu") {
        ((properties()["uploadIntent"] as Map<*, *>)["enum"] as List<*>)
            .shouldContainExactly("schema_staging_readonly", "job_input")
    }

    test("Pflicht ist nur, was beide Intents verlangen") {
        input["required"] shouldBe listOf("uploadIntent", "checksumSha256")
    }
})
