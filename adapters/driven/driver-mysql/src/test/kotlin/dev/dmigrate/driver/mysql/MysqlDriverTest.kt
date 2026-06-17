package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Structural transfer compatibility through the real MySQL type mapping (M2/N3). */
class MysqlDriverTest : FunSpec({

    val compat = MysqlDriver().transferCompatibility()

    test("PG array → MySQL JSON is compatible (M2: both map to JSON)") {
        compat.isCompatible(NeutralType.Array("text"), NeutralType.Json) shouldBe true
    }

    test("enum → text is compatible (both map to TEXT)") {
        compat.isCompatible(NeutralType.Enum(values = listOf("a", "b")), NeutralType.Text()) shouldBe true
    }

    test("named enum ↔ inline enum is compatible (N3)") {
        compat.isCompatible(
            NeutralType.Enum(values = null, refType = "mpaa"),
            NeutralType.Enum(values = listOf("G", "PG"), refType = null),
        ) shouldBe true
    }

    test("text → integer remains incompatible") {
        compat.isCompatible(NeutralType.Text(), NeutralType.Integer) shouldBe false
    }
})
