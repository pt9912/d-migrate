package dev.dmigrate.server.application.fingerprint

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

internal object FingerprintFixtures {

    fun bind(
        tenant: String = "acme",
        caller: String = "alice",
        tool: String = "start.export.data",
        extras: Map<String, JsonValue> = emptyMap(),
    ) = BindContext(
        tenantId = TenantId(tenant),
        callerId = PrincipalId(caller),
        toolName = tool,
        extras = extras,
    )

    fun service(): PayloadFingerprintService = DefaultPayloadFingerprintService()
}
