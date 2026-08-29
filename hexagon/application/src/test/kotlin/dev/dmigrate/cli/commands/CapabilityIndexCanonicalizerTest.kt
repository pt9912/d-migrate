package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * ADR 0049: der Fingerabdruck-Pfad sieht einen Index durch die Brille des
 * Ziel-Dialekts. Was ein Dialekt nicht ausdrücken kann, kann er auch nicht
 * zurückmelden — ohne diese Projektion meldete der Post-Compare nach einem
 * `migrate --execute` Drift für etwas, das der Zielserver gar nicht kennt.
 */
class CapabilityIndexCanonicalizerTest : FunSpec({

    val index = IndexDefinition(
        name = "ix",
        columns = listOf(IndexColumn("id")),
        includeColumns = listOf("title"),
        clustered = true,
    )

    test("SQL Server keeps both fields") {
        val projected = capabilityIndexCanonicalizer(DatabaseDialect.MSSQL)(index)
        projected.includeColumns shouldContainExactly listOf("title")
        projected.clustered shouldBe true
    }

    test("PostgreSQL keeps INCLUDE but not the storage steering") {
        val projected = capabilityIndexCanonicalizer(DatabaseDialect.POSTGRESQL)(index)
        projected.includeColumns shouldContainExactly listOf("title")
        projected.clustered shouldBe false
    }

    test("MySQL and SQLite keep neither") {
        listOf(DatabaseDialect.MYSQL, DatabaseDialect.SQLITE).forEach { dialect ->
            val projected = capabilityIndexCanonicalizer(dialect)(index)
            projected.includeColumns.shouldBeEmpty()
            projected.clustered shouldBe false
        }
    }

    // SQL Server benennt Volltext-Indizes nicht; der Reverse synthetisiert einen
    // Namen. Bliebe er im Vergleich, driftete jeder Round-Trip an einem Namen,
    // den niemand vergeben hat.
    test("the name of a full-text index is dropped where the dialect does not store one") {
        val fullText = index.copy(name = "fx_articles", type = IndexType.FULLTEXT)

        capabilityIndexCanonicalizer(DatabaseDialect.MSSQL)(fullText).name shouldBe null
    }

    test("the name survives for dialects that do name full-text indexes") {
        val fullText = index.copy(name = "fx_articles", type = IndexType.FULLTEXT)

        listOf(DatabaseDialect.POSTGRESQL, DatabaseDialect.MYSQL, DatabaseDialect.SQLITE).forEach { dialect ->
            capabilityIndexCanonicalizer(dialect)(fullText).name shouldBe "fx_articles"
        }
    }

    test("a non-full-text index keeps its name even on SQL Server") {
        capabilityIndexCanonicalizer(DatabaseDialect.MSSQL)(index).name shouldBe "ix"
    }

    test("everything else about the index is left alone") {
        val projected = capabilityIndexCanonicalizer(DatabaseDialect.SQLITE)(index)
        projected.name shouldBe "ix"
        projected.columns shouldContainExactly listOf(IndexColumn("id"))
    }
})
