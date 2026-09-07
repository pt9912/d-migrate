package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Die Refresh-Angabe in beide Richtungen, und das Urteil ueber ihre Renderbarkeit. */
class OracleMaterializedViewDdlTest : FunSpec({

    val quote = { name: String -> "\"$name\"" }
    fun mv(refresh: String? = null) =
        ViewDefinition(materialized = true, refresh = refresh, query = "SELECT 1 FROM dual")

    test("the catalog default FORCE/DEMAND is not stored") {
        // Sonst truege jede gelesene MV eine Angabe, die der Autor nie
        // geschrieben hat, und ein Round-Trip zeigte einen Unterschied.
        OracleMaterializedViewDdl.readRefresh("FORCE", "DEMAND").shouldBeNull()
    }

    test("a non-default refresh comes back in the neutral two-part form") {
        OracleMaterializedViewDdl.readRefresh("COMPLETE", "DEMAND") shouldBe "complete on demand"
        OracleMaterializedViewDdl.readRefresh("FAST", "COMMIT") shouldBe "fast on commit"
        OracleMaterializedViewDdl.readRefresh("FORCE", "COMMIT") shouldBe "force on commit"
    }

    test("an unreadable catalog value is carried through, not degraded to the default") {
        // Oracle kennt `ON STATEMENT`; als `null` gelesen wuerde die Sicht
        // danach als FORCE ON DEMAND wiedererzeugt -- eine stille Aenderung
        // ihrer Semantik. Der Reader meldet sie stattdessen (R364).
        OracleMaterializedViewDdl.readRefresh("FAST", "STATEMENT") shouldBe "fast on statement"
        OracleMaterializedViewDdl.isReadable("FAST", "STATEMENT") shouldBe false
        OracleMaterializedViewDdl.isReadable("COMPLETE", "DEMAND") shouldBe true
        OracleMaterializedViewDdl.readRefresh(null, "DEMAND").shouldBeNull()
    }

    test("no refresh setting renders no REFRESH clause") {
        OracleMaterializedViewDdl.createSql("v", mv(), "SELECT 1 FROM dual", quote) shouldBe
            "CREATE MATERIALIZED VIEW \"v\"\nAS\nSELECT 1 FROM dual;"
    }

    test("a method without a trigger defaults to ON DEMAND") {
        OracleMaterializedViewDdl.createSql("v", mv("complete"), "SELECT 1 FROM dual", quote) shouldContain
            "REFRESH COMPLETE ON DEMAND"
    }

    test("NEVER REFRESH stands before the keyword, not after it") {
        // `REFRESH NEVER` ist kein gueltiges Oracle (ORA-00905, gemessen).
        OracleMaterializedViewDdl.createSql("v", mv("never"), "SELECT 1 FROM dual", quote) shouldContain
            "NEVER REFRESH"
    }

    test("FAST is reported because Oracle needs a materialized view log") {
        // Gemessen: ORA-23413 ohne Log. FORCE braucht keins -- es faellt auf
        // einen vollstaendigen Refresh zurueck, auch mit ON COMMIT.
        OracleMaterializedViewDdl.unsupportedShape("v", mv("fast on commit"))!!.reason shouldContain "ORA-23413"
        OracleMaterializedViewDdl.unsupportedShape("v", mv("force on commit")).shouldBeNull()
        OracleMaterializedViewDdl.unsupportedShape("v", mv("complete on demand")).shouldBeNull()
        OracleMaterializedViewDdl.unsupportedShape("v", mv()).shouldBeNull()
    }

    test("an unreadable refresh setting is reported instead of being ignored") {
        OracleMaterializedViewDdl.unsupportedShape("v", mv("hourly"))!!.reason shouldContain "'hourly'"
        OracleMaterializedViewDdl.unsupportedShape("v", mv("complete on tuesday"))!!.reason shouldContain "tuesday"
    }

    test("drop names the object kind: DROP VIEW does not remove a materialized view") {
        OracleMaterializedViewDdl.dropSql("v", quote) shouldBe "DROP MATERIALIZED VIEW \"v\";"
    }

    test("a trigger alone is a valid setting: the author says when, not how") {
        OracleMaterializedViewDdl.createSql("v", mv("force on commit"), "SELECT 1 FROM dual", quote) shouldContain
            "REFRESH FORCE ON COMMIT"
        OracleMaterializedViewDdl.unsupportedShape("v", mv("force on commit")).shouldBeNull()
    }
})
