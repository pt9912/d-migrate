package dev.dmigrate.server.persistence.jdbc.schema

import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant

class IndexSelectTest : FunSpec({

    val tenant = TenantId("acme")
    val after = Instant.parse("2026-11-27T10:00:00Z")

    test("jeder Wert geht als Bind-Parameter hinein, so viele wie Platzhalter") {
        val statement = indexSelect(
            "diff_index_entries",
            "diff_id",
            tenant,
            IndexQuery(jobRef = "job-1", createdAfter = after, entryFields = mapOf("sourceRef" to "a' OR 1=1")),
        )

        statement.parameters shouldBe listOf("acme", "job-1", "job-1", after, after, null, null, "a' OR 1=1")
        statement.sql.count { it == '?' } shouldBe statement.parameters.size
        statement.sql shouldContain "AND entry->>'sourceRef' = ?"
        statement.sql shouldNotContain "OR 1=1"
        statement.sql shouldContain "ORDER BY created_at DESC, diff_id ASC"
    }

    test("ein nicht gesetztes Feld filtert nicht, und ungefiltert steht das aelteste zuerst") {
        val statement = indexSelect(
            "profile_index_entries",
            "profile_id",
            tenant,
            IndexQuery(entryFields = mapOf("targetRef" to null), newestFirst = false),
        )

        statement.sql shouldNotContain "entry->>"
        statement.sql.count { it == '?' } shouldBe statement.parameters.size
        statement.sql shouldContain "ORDER BY created_at ASC, profile_id ASC"
    }
})
