package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig

/**
 * LN-049 **Stufe 4**: ergänzt ein **weiterhin** fehlendes DB-Passwort aus dem verschlüsselten Store unter
 * [name] (dem `--source`/`--target`-Connection-Namen = dem `config credentials set --name`). Läuft **nach**
 * [EnvCredentialFiller] (Stufe 2 gewinnt, Spec 4.1). Additiv, dialekt-gegatet, **kein** fail-closed:
 * fehlt ein Eintrag / das Master-Secret, bleibt die Config unverändert (mit einer secret-freien Diagnose,
 * falls ein Store existiert — sonst still).
 */
internal class StoreCredentialFiller(
    private val name: String,
    private val session: CredentialFillSession,
    private val stderr: (String) -> Unit,
) {
    fun fill(config: ConnectionConfig): ConnectionConfig {
        if (!config.password.isNullOrEmpty()) return config
        if (config.dialect == DatabaseDialect.SQLITE) return config
        val cred = session.resolve(name)
        if (cred == null) {
            if (session.storeExists()) {
                stderr(
                    "Note: no usable stored credential for '$name' " +
                        "(missing entry or master secret) — continuing without it.",
                )
            }
            return config
        }
        val pwChars = cred.password()
        return try {
            // D-9: fehlt in der URL der User, kommt er aus dem Store; sonst gewinnt der URL-User.
            config.copy(user = config.user ?: cred.user, password = String(pwChars))
        } finally {
            pwChars.fill(' ')
            cred.wipe()
        }
    }
}
