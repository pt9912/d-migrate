package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.error.UnsupportedToolOperationException
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = TenantId("acme"),
    effectiveTenantId = TenantId("acme"),
    allowedTenantIds = setOf(TenantId("acme")),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

class UnsupportedToolHandlerTest : FunSpec({

    test("handler throws UnsupportedToolOperationException with the call's tool name") {
        val sut = UnsupportedToolHandler("test")
        val context = ToolCallContext(name = "schema_validate", arguments = null, principal = PRINCIPAL)
        val ex = shouldThrow<UnsupportedToolOperationException> { sut.handle(context) }
        ex.toolName shouldBe "schema_validate"
        ex.operation shouldBe "test"
    }

    test("operation is configurable via constructor") {
        val sut = UnsupportedToolHandler("upload-init")
        val context = ToolCallContext(name = "artifact_upload_init", arguments = null, principal = PRINCIPAL)
        val ex = shouldThrow<UnsupportedToolOperationException> { sut.handle(context) }
        ex.operation shouldBe "upload-init"
    }
})
