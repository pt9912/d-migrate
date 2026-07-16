package dev.dmigrate.connection

import dev.dmigrate.server.ports.CredentialProvider
import dev.dmigrate.server.ports.CredentialResolution

/**
 * `env:`-[CredentialProvider] (ADR 0035): löst `env:VAR_NAME` durch Lesen der Prozess-Umgebung auf.
 * Aus dem früheren flachen `EnvConnectionSecretResolver` extrahiert — das Verhalten für `env:` ist
 * unverändert (die Env-Variable enthält die **vollständige** Connect-URL; [urlFromEnv] ist der
 * dormante Transform-Hook, Default Identity).
 *
 * `env:VAR` nicht gesetzt → [CredentialResolution.REASON_ENV_NOT_SET] (fail-closed). Weder
 * Variablenwert noch aufgelöste URL werden geloggt.
 */
class EnvCredentialProvider(
    private val envLookup: (String) -> String? = System::getenv,
    private val urlFromEnv: (envVarValue: String) -> String = { it },
) : CredentialProvider {

    override val scheme: String = SCHEME

    override fun resolve(credentialRef: String): CredentialResolution {
        if (!credentialRef.startsWith(SCHEME)) {
            return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_PROVIDER_MISSING,
                detail = "credentialRef is not an '$SCHEME' reference",
            )
        }
        val envName = credentialRef.removePrefix(SCHEME)
        val envValue = envLookup(envName)
            ?: return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_ENV_NOT_SET,
                detail = "environment variable '$envName' is not set",
            )
        return CredentialResolution.Success(url = urlFromEnv(envValue))
    }

    companion object {
        const val SCHEME: String = "env:"
    }
}
