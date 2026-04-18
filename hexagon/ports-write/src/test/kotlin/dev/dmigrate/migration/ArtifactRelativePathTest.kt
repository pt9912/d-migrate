package dev.dmigrate.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ArtifactRelativePathTest : FunSpec({

    test("simple relative path") {
        val p = ArtifactRelativePath.of("V1__init.sql")
        p.normalized shouldBe "V1__init.sql"
    }

    test("nested relative path") {
        val p = ArtifactRelativePath.of("db/migrations/V1__init.sql")
        p.normalized shouldBe "db/migrations/V1__init.sql"
    }

    test("normalizes redundant segments") {
        val p = ArtifactRelativePath.of("db/./migrations/V1__init.sql")
        p.normalized shouldBe "db/migrations/V1__init.sql"
    }

    test("backslashes normalized to forward slashes") {
        val p = ArtifactRelativePath.of("db\\migrations\\V1__init.sql")
        p.normalized shouldBe "db/migrations/V1__init.sql"
    }

    test("rejects absolute path") {
        shouldThrow<IllegalArgumentException> {
            ArtifactRelativePath.of("/etc/V1__init.sql")
        }.message shouldContain "relative"
    }

    test("rejects parent escape") {
        shouldThrow<IllegalArgumentException> {
            ArtifactRelativePath.of("../V1__init.sql")
        }.message shouldContain "escape"
    }

    test("rejects nested parent escape") {
        shouldThrow<IllegalArgumentException> {
            ArtifactRelativePath.of("db/../../V1__init.sql")
        }.message shouldContain "escape"
    }

    test("rejects blank path") {
        shouldThrow<IllegalArgumentException> {
            ArtifactRelativePath.of("  ")
        }.message shouldContain "blank"
    }

    test("same canonical paths are equal") {
        ArtifactRelativePath.of("a/b.sql") shouldBe ArtifactRelativePath.of("a/./b.sql")
    }
})
