package dev.dmigrate.server.application.fingerprint

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class FingerprintReservedBindKeyTest : FunSpec({

    val service = FingerprintFixtures.service()

    test("payload with top-level _bind is rejected") {
        val ex = shouldThrow<IllegalArgumentException> {
            service.fingerprint(
                FingerprintScope.START_TOOL,
                JsonValue.obj(FingerprintNormalization.BIND_KEY to JsonValue.Str("user-supplied")),
                FingerprintFixtures.bind(),
            )
        }
        ex.message shouldContain FingerprintNormalization.BIND_KEY
    }

    test("nested _bind is allowed (only top level is reserved)") {
        service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj(
                "nested" to JsonValue.obj(FingerprintNormalization.BIND_KEY to JsonValue.Str("ok")),
            ),
            FingerprintFixtures.bind(),
        )
    }

    test("BindContext.extras may not override reserved bind keys") {
        listOf("tenantId", "callerId", "toolName", "scope").forEach { reserved ->
            shouldThrow<IllegalArgumentException> {
                service.fingerprint(
                    FingerprintScope.UPLOAD_INIT,
                    JsonValue.Obj.EMPTY,
                    FingerprintFixtures.bind(extras = mapOf(reserved to JsonValue.Str("override"))),
                )
            }
        }
    }
})
