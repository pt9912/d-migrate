package dev.dmigrate.connection

import dev.dmigrate.server.ports.CredentialProvider
import dev.dmigrate.server.ports.CredentialResolution

/**
 * `keychain:`-[CredentialProvider] (ADR 0040): löst `keychain://<service>[/<account>]` über ein
 * austauschbares [KeychainBackend] auf. Der Keychain-Eintrag trägt die **vollständige** Connect-URL
 * (World-B-Parität, wie `file:`/`env:`).
 *
 * Fail-closed: Backend nicht verfügbar (headless / OS-Tool fehlt / Timeout) →
 * [CredentialResolution.REASON_KEYCHAIN_UNAVAILABLE]; Eintrag fehlt →
 * [CredentialResolution.REASON_KEYCHAIN_ENTRY_NOT_FOUND]; leerer Wert →
 * [CredentialResolution.REASON_EMPTY_VALUE]. Der Eintrags-Wert (Secret) wird nie in `detail`/Logs
 * echot — nur der operator-taugliche Service-Name.
 *
 * Backend-Auswahl liegt beim Wiring (`defaultCredentialProviderRegistry`): Default ist der
 * native-freie [ShelloutKeychainBackend]; ein opt-in-`keychain-native`-Modul kann denselben
 * [KeychainBackend]-Port anders erfüllen (ADR 0040).
 */
class KeychainCredentialProvider(
    private val backend: KeychainBackend,
) : CredentialProvider {

    override val scheme: String = SCHEME

    // Vertrag (ADR 0035): [credentialRef] beginnt mit [scheme] — die Registry dispatcht am Prefix.
    override fun resolve(credentialRef: String): CredentialResolution {
        val target = parseRef(credentialRef)
            ?: return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_KEYCHAIN_ENTRY_NOT_FOUND,
                detail = "keychain: credentialRef carries no service name",
            )
        if (!backend.isAvailable()) {
            return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_KEYCHAIN_UNAVAILABLE,
                detail = "no keychain backend available (headless or unsupported OS/tool) " +
                    "for service '${target.service}'",
            )
        }
        return when (val lookup = backend.lookup(target.service, target.account)) {
            is KeychainLookup.Found -> onFound(lookup.value, target.service)
            KeychainLookup.NotFound -> CredentialResolution.Failure(
                reason = CredentialResolution.REASON_KEYCHAIN_ENTRY_NOT_FOUND,
                detail = "keychain entry not found for service '${target.service}'",
            )
            is KeychainLookup.Unavailable -> CredentialResolution.Failure(
                reason = CredentialResolution.REASON_KEYCHAIN_UNAVAILABLE,
                detail = lookup.detail,
            )
        }
    }

    private fun onFound(rawValue: String, service: String): CredentialResolution {
        // Führendes UTF-8-BOM strippen (wie file:; code 0xFEFF ist kein Whitespace, überlebt trim()),
        // dann trimmen.
        val url = rawValue.dropWhile { it.code == BOM }.trim()
        return if (url.isEmpty()) {
            CredentialResolution.Failure(
                reason = CredentialResolution.REASON_EMPTY_VALUE,
                detail = "keychain entry is empty for service '$service'",
            )
        } else {
            CredentialResolution.Success(url = url)
        }
    }

    /** `keychain://<service>[/<account>]` → (service, account?); `null` bei leerem Service. */
    private fun parseRef(credentialRef: String): Target? {
        val rest = credentialRef.removePrefix(SCHEME).removePrefix("//")
        if (rest.isBlank()) return null
        val slash = rest.indexOf('/')
        if (slash < 0) return Target(rest, null)
        val service = rest.substring(0, slash)
        if (service.isBlank()) return null
        val account = rest.substring(slash + 1).takeIf { it.isNotBlank() }
        return Target(service, account)
    }

    private data class Target(val service: String, val account: String?)

    companion object {
        const val SCHEME: String = "keychain:"
        private const val BOM: Int = 0xFEFF
    }
}
