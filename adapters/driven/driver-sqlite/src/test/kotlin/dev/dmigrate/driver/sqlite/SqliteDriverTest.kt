package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Structural transfer compatibility through the real SQLite type mapping (M2/N3). */
class SqliteDriverTest : FunSpec({

    val compat = SqliteDriver().transferCompatibility()

    test("decimal → float is compatible (M2: both map to REAL)") {
        compat.isCompatible(NeutralType.Decimal(10, 2), NeutralType.Float()) shouldBe true
    }

    test("datetime/date/time → text is compatible (N3: SQLite stores temporals as TEXT)") {
        compat.isCompatible(NeutralType.DateTime(), NeutralType.Text()) shouldBe true
        compat.isCompatible(NeutralType.Date, NeutralType.Text()) shouldBe true
        compat.isCompatible(NeutralType.Time, NeutralType.Text()) shouldBe true
    }

    test("boolean → integer is compatible (SQLite stores boolean as INTEGER)") {
        compat.isCompatible(NeutralType.BooleanType, NeutralType.Integer) shouldBe true
    }

    test("text → integer remains incompatible") {
        compat.isCompatible(NeutralType.Text(), NeutralType.Integer) shouldBe false
    }
})
