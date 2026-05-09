package dev.dmigrate.server.application.fingerprint

import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.text.UnicodeTextService

interface PayloadFingerprintService {

    fun fingerprint(
        scope: FingerprintScope,
        payload: JsonValue.Obj,
        bind: BindContext,
    ): String
}

class DefaultPayloadFingerprintService(
    private val unicodeText: UnicodeTextService,
) : PayloadFingerprintService {

    private val canonicalizer = JsonCanonicalizer(unicodeText)

    override fun fingerprint(
        scope: FingerprintScope,
        payload: JsonValue.Obj,
        bind: BindContext,
    ): String {
        val normalized = FingerprintNormalization.normalize(scope, payload, bind)
        val canonical = canonicalizer.canonicalize(normalized)
        return sha256Hex(canonical)
    }
}
