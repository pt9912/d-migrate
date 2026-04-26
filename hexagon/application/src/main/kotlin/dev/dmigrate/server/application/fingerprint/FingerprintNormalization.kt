package dev.dmigrate.server.application.fingerprint

internal object FingerprintNormalization {

    const val BIND_KEY: String = "_bind"

    val CONTROL_FIELDS: Set<String> = setOf(
        "idempotencyKey",
        "approvalToken",
        "clientRequestId",
        "requestId",
    )

    fun normalize(
        scope: FingerprintScope,
        payload: JsonValue.Obj,
        bind: BindContext,
    ): JsonValue.Obj {
        require(BIND_KEY !in payload.fields) {
            "$BIND_KEY is a reserved top-level key; remove it from the payload"
        }
        val stripped = payload.fields
            .filter { (key, _) -> key !in CONTROL_FIELDS }
        val bindObj = buildBind(scope, bind)
        val combined = LinkedHashMap<String, JsonValue>(stripped.size + 1)
        combined[BIND_KEY] = bindObj
        combined.putAll(stripped)
        return JsonValue.Obj(combined)
    }

    private fun buildBind(scope: FingerprintScope, bind: BindContext): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["tenantId"] = JsonValue.Str(bind.tenantId.value)
        fields["callerId"] = JsonValue.Str(bind.callerId.value)
        fields["toolName"] = JsonValue.Str(bind.toolName)
        fields["scope"] = JsonValue.Str(scope.wireValue)
        for ((key, value) in bind.extras) {
            require(key !in fields) {
                "BindContext.extras may not override reserved key '$key'"
            }
            fields[key] = value
        }
        return JsonValue.Obj(fields)
    }
}
