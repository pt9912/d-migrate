package dev.dmigrate.format

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import dev.dmigrate.core.model.IndexType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
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
 *
 * Der Validator (json-schema-validator 3.x) arbeitet auf Jackson 3
 * (`tools.jackson.*`); deshalb liest der Test mit Jackson 3, auch wenn der
 * Produktionscode Jackson 2 nutzt. Beide liegen in getrennten Packages
 * nebeneinander auf dem Test-Classpath.
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

    val schema: Schema = run {
        val schemaFile = File(repoRoot, "spec/schema.json")
        check(schemaFile.exists()) {
            "spec/schema.json nicht gefunden unter ${schemaFile.absolutePath}"
        }
        val node = mapper.readTree(schemaFile)
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(node)
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

    // Der Vertrag geht in BEIDE Richtungen: das Schema muss jeden Indextyp
    // zulassen, den der Code schreiben kann. Sonst erzeugt d-migrate ein
    // Modell, das sein eigenes Schema zurueckweist -- und zwar erst beim
    // Wiedereinlesen, nicht beim Erzeugen.
    test("schema.json laesst genau die Indextypen zu, die das Modell kennt") {
        val declared = mapper.readTree(File(repoRoot, "spec/schema.json"))
            .findParents("enum")
            .asSequence()
            .map { it.path("enum") }
            .first { node -> node.values().any { it.asString() == "btree" } }
            // `values()` ist Pflicht: Jackson 3 gibt `JsonNode` ein eigenes
            // `map(Function)`, das die Funktion auf den Knoten selbst anwendet
            // und Kotlins `Iterable.map` verdeckt.
            .values()
            .map { it.asString() }
            .toSet()
        declared shouldBe IndexType.entries.map { it.name.lowercase() }.toSet()
    }

    test("unzulaessiger geometry_type wird abgelehnt") {
        val doc = loadFixture()
        val location = doc.path("tables").path("orders").path("columns").path("location") as ObjectNode
        location.put("geometry_type", "banana")
        schema.validate(doc).size shouldNotBe 0
    }
})
