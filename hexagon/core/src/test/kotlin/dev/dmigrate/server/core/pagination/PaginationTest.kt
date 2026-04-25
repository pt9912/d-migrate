package dev.dmigrate.server.core.pagination

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PaginationTest : FunSpec({

    test("PageRequest holds size and optional token") {
        val request = PageRequest(pageSize = 50, pageToken = "tok_1")
        request.pageSize shouldBe 50
        request.pageToken shouldBe "tok_1"
    }

    test("PageRequest pageToken defaults to null") {
        PageRequest(pageSize = 25).pageToken shouldBe null
    }

    test("PageResult carries items and optional next token") {
        val page = PageResult(items = listOf("a", "b"), nextPageToken = "tok_2")
        page.items shouldBe listOf("a", "b")
        page.nextPageToken shouldBe "tok_2"
    }

    test("PageResult nextPageToken defaults to null when omitted") {
        val page = PageResult<String>(items = emptyList())
        page.items shouldBe emptyList()
        page.nextPageToken shouldBe null
    }
})
