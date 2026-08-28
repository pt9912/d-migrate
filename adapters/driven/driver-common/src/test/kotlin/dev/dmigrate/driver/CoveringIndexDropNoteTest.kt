package dev.dmigrate.driver

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * ADR 0049: ein Dialekt, der abdeckende oder clustered Indizes nicht kennt,
 * legt einen anderen Index an als den beschriebenen. Das ist gültiges DDL —
 * aber nichts, was stillschweigend passieren darf.
 */
class CoveringIndexDropNoteTest : FunSpec({

    val plain = IndexDefinition(name = "ix", columns = listOf(IndexColumn("id")))

    test("an index with neither property produces no note") {
        CoveringIndexDropNote.forDialect(plain, "ix", "MySQL").shouldBeEmpty()
    }

    test("dropped INCLUDE columns are named in W142") {
        val note = CoveringIndexDropNote
            .forDialect(plain.copy(includeColumns = listOf("title", "body")), "ix", "MySQL")
            .single()
        note.code shouldBe "W142"
        note.type shouldBe NoteType.WARNING
        note.message shouldContain "title, body"
        note.message shouldContain "MySQL"
    }

    test("a dropped storage steering is W143") {
        val note = CoveringIndexDropNote.forDialect(plain.copy(clustered = true), "ix", "SQLite").single()
        note.code shouldBe "W143"
        note.message shouldContain "SQLite"
    }

    test("both properties produce both notes, in a stable order") {
        val notes = CoveringIndexDropNote
            .forDialect(plain.copy(includeColumns = listOf("title"), clustered = true), "ix", "MySQL")
        notes.map { it.code } shouldBe listOf("W142", "W143")
    }

    test("the hint warns about the uniqueness the naive rescue would change") {
        // Die eingeschlossenen Spalten an den Schluessel zu haengen waere die
        // naheliegende Rettung — und bei `unique` eine andere Aussage.
        val note = CoveringIndexDropNote
            .forDialect(plain.copy(includeColumns = listOf("title")), "ix", "MySQL")
            .single()
        note.hint.shouldNotBeNull() shouldContain "uniqueness"
    }
})
