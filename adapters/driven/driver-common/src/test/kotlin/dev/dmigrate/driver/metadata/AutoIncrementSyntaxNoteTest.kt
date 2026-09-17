package dev.dmigrate.driver.metadata

import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Die Bestaetigung der Praeferenz `identity` — ein Code fuer MySQL und SQLite. */
class AutoIncrementSyntaxNoteTest : FunSpec({

    test("an INFO note that names the column, the construct and the config key") {
        val note = AutoIncrementSyntaxNote.identity(
            "orders.id",
            "MySQL AUTO_INCREMENT",
            "reverse.mysql.autoincrement_syntax",
        )
        note.code shouldBe AutoIncrementSyntaxNote.IDENTITY_DECLARED
        note.code shouldBe "R205"
        note.severity shouldBe SchemaReadSeverity.INFO
        note.objectName shouldBe "orders.id"
        note.message shouldContain "MySQL AUTO_INCREMENT"
        note.message shouldContain "reverse.mysql.autoincrement_syntax: identity"
        note.hint.shouldBeNull()
    }
})
