package dev.dmigrate.format

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * Vertrags-Guard fuer `spec/schema.json`.
 *
 * `spec/schema.json` ist ein handgepflegtes JSON-Schema, das von keinem
 * Produktionspfad konsumiert wird — entsprechend leicht driftet es gegenueber
 * dem tatsaechlichen Parser/Modell. In der Vergangenheit fehlten dort bereits
 * `geometry`/`srid`/`geometry_type`, Tabellen-`metadata`,
 * `preserve_current_value`, View-`columns` und die `dependencies`-
 * Projektionsstatus-Felder. Da das Schema ueberall `additionalProperties:
 * false` setzt, scheitern gueltige — insbesondere reverse-generierte —
 * Schemas dann an ihrer eigenen Validierung.
 *
 * Dieser Test validiert ein Fixture, das bewusst **jede** in
 * `neutral-model-spec.md` dokumentierte Elementgruppe nutzt, gegen das echte
 * `spec/schema.json` aus dem Repo. Schlaegt er fehl, ist das Schema erneut
 * hinter Spec/Code zurueckgefallen.
 */
class SchemaJsonContractTest : FunSpec({

    val mapper = ObjectMapper()

    val repoRoot: File = run {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").exists()) return@run dir
            dir = dir.parentFile
        }
        File(System.getProperty("user.dir"))
    }

    val schema: JsonSchema = run {
        val schemaFile = File(repoRoot, "spec/schema.json")
        check(schemaFile.exists()) {
            "spec/schema.json nicht gefunden unter ${schemaFile.absolutePath}"
        }
        val node = mapper.readTree(schemaFile)
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node)
    }

    fun loadFixture(): JsonNode {
        val stream = SchemaJsonContractTest::class.java
            .getResourceAsStream("/schema-contract/full-feature-schema.json")
            ?: error("Fixture /schema-contract/full-feature-schema.json fehlt im Classpath")
        return stream.use { mapper.readTree(it) }
    }

    test("Voll-Feature-Schema validiert fehlerfrei gegen spec/schema.json") {
        val violations = schema.validate(loadFixture())
        withClue("spec/schema.json lehnt dokumentierte Schema-Elemente ab; violations=$violations") {
            violations.size shouldBe 0
        }
    }

    test("additionalProperties:false bleibt scharf — unbekanntes Top-Level-Feld wird abgelehnt") {
        val doc = loadFixture() as ObjectNode
        doc.put("totally_unknown_top_level", "x")
        schema.validate(doc).size shouldNotBe 0
    }

    test("unzulaessiger geometry_type wird abgelehnt") {
        val doc = loadFixture()
        val location = doc.path("tables").path("orders").path("columns").path("location") as ObjectNode
        location.put("geometry_type", "banana")
        schema.validate(doc).size shouldNotBe 0
    }
})
