package dev.dmigrate.driver.metadata

import dev.dmigrate.driver.PreferenceSource
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Die Bestaetigung der Praeferenz `identity` — ein Code fuer MySQL und SQLite. */
class AutoIncrementSyntaxNoteTest : FunSpec({

    fun mysql(source: PreferenceSource) =
        DeclaredPreference("--mysql-autoincrement-syntax", "reverse.mysql.autoincrement_syntax", source)

    test("an INFO note that names the column, the construct and the config key") {
        val note = AutoIncrementSyntaxNote.identity("orders.id", "MySQL AUTO_INCREMENT", mysql(PreferenceSource.CONFIG))
        note.code shouldBe AutoIncrementSyntaxNote.IDENTITY_DECLARED
        note.code shouldBe "R205"
        note.severity shouldBe SchemaReadSeverity.INFO
        note.objectName shouldBe "orders.id"
        note.message shouldContain "MySQL AUTO_INCREMENT"
        note.message shouldContain "(reverse.mysql.autoincrement_syntax: identity)"
        note.hint.shouldBeNull()
    }

    test("declared by flag, the note names the flag and not the config key") {
        val note = AutoIncrementSyntaxNote.identity("orders.id", "MySQL AUTO_INCREMENT", mysql(PreferenceSource.FLAG))
        note.message shouldContain "(--mysql-autoincrement-syntax identity)"
        note.message shouldNotContain "reverse.mysql"
    }

    test("the declaration renders flag and key the way the user writes them") {
        mysql(PreferenceSource.FLAG).render("serial") shouldBe "--mysql-autoincrement-syntax serial"
        mysql(PreferenceSource.CONFIG).render("serial") shouldBe "reverse.mysql.autoincrement_syntax: serial"
    }
})
