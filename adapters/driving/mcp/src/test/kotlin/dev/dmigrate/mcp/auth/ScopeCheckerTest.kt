package dev.dmigrate.mcp.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ScopeCheckerTest : FunSpec({

    val mapping = mapOf(
        "tools/list" to setOf("dmigrate:read"),
        "data_export_start" to setOf("dmigrate:job:start"),
        "data_import_start" to setOf("dmigrate:data:write"),
    )

    test("isScopeFree recognizes initialize and notifications/initialized only") {
        ScopeChecker.isScopeFree("initialize") shouldBe true
        ScopeChecker.isScopeFree("notifications/initialized") shouldBe true
        ScopeChecker.isScopeFree("tools/list") shouldBe false
        ScopeChecker.isScopeFree("anything-else") shouldBe false
    }

    test("requiredScopes returns the configured set") {
        ScopeChecker.requiredScopes("tools/list", mapping) shouldBe setOf("dmigrate:read")
        ScopeChecker.requiredScopes("data_import_start", mapping) shouldBe setOf("dmigrate:data:write")
    }

    test("requiredScopes falls back to dmigrate:admin for unknown method (fail-closed)") {
        ScopeChecker.requiredScopes("unknown_method", mapping) shouldBe setOf("dmigrate:admin")
    }

    test("isSatisfied true when granted is a superset of required") {
        ScopeChecker.isSatisfied(
            granted = setOf("dmigrate:read", "dmigrate:job:start"),
            required = setOf("dmigrate:read"),
        ) shouldBe true
    }

    test("isSatisfied false when granted is missing a required scope") {
        ScopeChecker.isSatisfied(
            granted = setOf("dmigrate:read"),
            required = setOf("dmigrate:read", "dmigrate:job:start"),
        ) shouldBe false
    }

    test("isSatisfied is true for empty required (scope-free contract)") {
        ScopeChecker.isSatisfied(emptySet(), emptySet()) shouldBe true
    }
})
